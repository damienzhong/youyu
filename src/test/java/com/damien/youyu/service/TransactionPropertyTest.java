package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link TransactionService} 的属性测试，覆盖设计文档 Correctness Properties 中的
 * Property 1-3（关联需求 4.1、4.2、4.3、4.4、4.5、4.6、4.8、4.9、4.13）：
 *
 * <ul>
 *   <li>Property 1（<b>有状态</b>）：对任意账户集合与任意合法交易操作序列（随机
 *       create/update/delete 支出/收入/转账），在<b>每一步执行之后</b>，每个账户的
 *       {@code current_balance} 都等于「初始余额 + Σ收入 − Σ支出 + Σ转入 − Σ转出」，
 *       并与 {@link AccountService#recomputeBalance} 的重算结果一致；作为推论，任一笔转账
 *       不改变所有账户余额之和。</li>
 *   <li>Property 2：任意非法创建请求（金额 &lt;0.01 / &gt;上限 / 小数位&gt;2、缺少
 *       金额/账户/(支出或收入)分类、引用不存在账户）都被拒绝，不创建任何 Transaction，
 *       所有账户 {@code current_balance} 保持不变。</li>
 *   <li>Property 3：源账户 == 目标账户的转账被拒（{@code TRANSFER_SAME_ACCOUNT}），
 *       不改变任何账户 {@code current_balance}、不落库。</li>
 * </ul>
 *
 * <p>沿用仓库内 DB 支撑型属性测试的既定范式（见 {@code AccountPropertyTest}、
 * {@code CategoryPropertyTest}）：在 {@code @DataJpaTest} + 真实 H2 与真实
 * {@link TransactionRepository}/{@link AccountRepository}/{@link CategoryRepository} 上，
 * 以固定种子的 {@link Random} 在 {@code @Test} 循环内智能生成受约束的随机输入，被测的
 * {@link TransactionService}/{@link AccountService} 业务逻辑全部真实执行，不使用任何 mock。
 * 时间以固定 {@link Clock} 注入以获得确定性。（本类为 JUnit Jupiter 切片测试，随机输入用
 * {@link Random} 生成而非 jqwik {@code Arbitrary}。）每个属性至少驱动 ≥100 次迭代。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TransactionPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final LocalDateTime FIXED_TIME = LocalDateTime.ofInstant(T0, ZONE);

    /** Property 2/3 迭代次数。 */
    private static final int ITERATIONS = 150;
    /** Property 1 独立操作序列条数与每条序列的步数（step 总数 = SEQUENCES × STEPS ≥ 100）。 */
    private static final int SEQUENCES = 14;
    private static final int STEPS = 14;

    private static final BigDecimal AMOUNT_MAX = new BigDecimal("9999999999999999.99");

    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private CategoryRepository categoryRepository;

    private TransactionService txService() {
        return new TransactionService(
                transactionRepository, accountRepository, categoryRepository, Clock.fixed(T0, ZONE));
    }

    private AccountService accountService() {
        return new AccountService(accountRepository, transactionRepository, Clock.fixed(T0, ZONE));
    }

    // ---------------- 智能生成器 ----------------

    /** 合法金额：范围 [0.01, ...]、恰好两位小数；上界受 maxCents 约束以避免多步累加溢出列精度。 */
    private static BigDecimal validAmount(Random rng, long maxCents) {
        long cents = 1 + (long) (rng.nextDouble() * (maxCents - 1));
        return new BigDecimal(cents).movePointLeft(2);
    }

    /** 合法初始余额：范围内、恰好两位小数（含负值，覆盖信用卡欠款），限幅以避免累加溢出。 */
    private static BigDecimal validInitialBalance(Random rng) {
        long cents = rng.nextLong(-1_000_000L, 1_000_001L); // ±10,000.00
        return new BigDecimal(cents).movePointLeft(2);
    }

    private static String letters(Random rng, int minLen, int maxLen) {
        int len = minLen + rng.nextInt(maxLen - minLen + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + rng.nextInt(26)));
        }
        return sb.toString();
    }

    // ---------------- 持久化辅助 ----------------

    private Account createAccount(long ledgerId, Random rng) {
        return accountService().create(ledgerId, letters(rng, 1, 12),
                AccountType.values()[rng.nextInt(AccountType.values().length)].name(),
                validInitialBalance(rng), rng.nextInt(101));
    }

    private Category createCategory(long ledgerId, CategoryKind kind, Random rng) {
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(kind);
        c.setName(letters(rng, 1, 12));
        c.setCreatedAt(FIXED_TIME);
        c.setUpdatedAt(FIXED_TIME);
        return categoryRepository.save(c);
    }

    private BigDecimal balanceOf(long ledgerId, Long accountId) {
        return accountRepository.findByIdAndUserId(accountId, ledgerId).orElseThrow().getCurrentBalance();
    }

    // ---------------- Property 1：余额守恒不变式（有状态操作序列） ----------------

    /** 模型侧记录一笔「存活」交易对账户余额的影响所需信息。 */
    private record TxRecord(com.damien.youyu.domain.TransactionType type, BigDecimal amount,
                            Long accountId, Long sourceId, Long destId) {
    }

    /**
     * Feature: youyu-ledger, Property 1: 对任意用户账户集合与任意合法交易操作序列（创建/修改/删除
     * 支出、收入、转账），在序列中任意一步执行之后，每个账户的 current_balance 都应等于：该账户初始余额
     * + 其全部收入金额 − 其全部支出金额 + 以其为目标账户的全部转账金额 − 以其为源账户的全部转账金额。
     * 作为推论，任一笔转账不改变所有账户余额之和。
     */
    @Test
    void property1_balanceConservationHoldsAfterEveryOperation() {
        Random rng = new Random(101_001L);
        TransactionService tx = txService();
        AccountService accounts = accountService();

        for (int seq = 0; seq < SEQUENCES; seq++) {
            long ledgerId = 100_000_000L + seq;

            // 账户集合（2-5 个）与支出/收入分类各一。
            int accCount = 2 + rng.nextInt(4);
            List<Long> accountIds = new ArrayList<>();
            Map<Long, BigDecimal> initial = new HashMap<>();
            for (int i = 0; i < accCount; i++) {
                Account a = createAccount(ledgerId, rng);
                accountIds.add(a.getId());
                initial.put(a.getId(), a.getInitialBalance());
            }
            Long expenseCat = createCategory(ledgerId, CategoryKind.EXPENSE, rng).getId();
            Long incomeCat = createCategory(ledgerId, CategoryKind.INCOME, rng).getId();

            // 模型：存活交易 id → 影响记录。
            Map<Long, TxRecord> live = new HashMap<>();

            for (int step = 0; step < STEPS; step++) {
                int op = rng.nextInt(4); // 0/1/2=创建(支出/收入/转账)，3=修改或删除（若有存活交易）。
                if (op == 3 && live.isEmpty()) {
                    op = rng.nextInt(3);
                }

                switch (op) {
                    case 0 -> { // 创建支出
                        Long acc = accountIds.get(rng.nextInt(accCount));
                        BigDecimal amount = validAmount(rng, 1_000_000L);
                        Transaction t = tx.create(ledgerId, ledgerId, "expense", amount, acc, expenseCat,
                                null, null, null, "e");
                        live.put(t.getId(), new TxRecord(
                                com.damien.youyu.domain.TransactionType.EXPENSE, amount, acc, null, null));
                    }
                    case 1 -> { // 创建收入
                        Long acc = accountIds.get(rng.nextInt(accCount));
                        BigDecimal amount = validAmount(rng, 1_000_000L);
                        Transaction t = tx.create(ledgerId, ledgerId, "income", amount, acc, incomeCat,
                                null, null, null, "i");
                        live.put(t.getId(), new TxRecord(
                                com.damien.youyu.domain.TransactionType.INCOME, amount, acc, null, null));
                    }
                    case 2 -> { // 创建转账（源≠目标）
                        int si = rng.nextInt(accCount);
                        int di = rng.nextInt(accCount);
                        while (di == si) {
                            di = rng.nextInt(accCount);
                        }
                        Long src = accountIds.get(si);
                        Long dst = accountIds.get(di);
                        BigDecimal amount = validAmount(rng, 1_000_000L);
                        Transaction t = tx.create(ledgerId, ledgerId, "transfer", amount, null, null,
                                src, dst, null, "t");
                        live.put(t.getId(), new TxRecord(
                                com.damien.youyu.domain.TransactionType.TRANSFER, amount, null, src, dst));
                    }
                    default -> { // 修改或删除一笔存活交易
                        List<Long> ids = new ArrayList<>(live.keySet());
                        Long targetId = ids.get(rng.nextInt(ids.size()));
                        if (rng.nextBoolean()) {
                            // 删除：回滚原影响。
                            tx.delete(ledgerId, ledgerId, targetId);
                            live.remove(targetId);
                        } else {
                            // 修改：随机生成新的合法形态，替换记录。
                            int newType = rng.nextInt(3);
                            BigDecimal amount = validAmount(rng, 1_000_000L);
                            if (newType == 2) {
                                int si = rng.nextInt(accCount);
                                int di = rng.nextInt(accCount);
                                while (di == si) {
                                    di = rng.nextInt(accCount);
                                }
                                Long src = accountIds.get(si);
                                Long dst = accountIds.get(di);
                                tx.update(ledgerId, ledgerId, targetId, "transfer", amount, null, null,
                                        src, dst, null, "t2");
                                live.put(targetId, new TxRecord(
                                        com.damien.youyu.domain.TransactionType.TRANSFER,
                                        amount, null, src, dst));
                            } else {
                                Long acc = accountIds.get(rng.nextInt(accCount));
                                String type = newType == 0 ? "expense" : "income";
                                Long cat = newType == 0 ? expenseCat : incomeCat;
                                tx.update(ledgerId, ledgerId, targetId, type, amount, acc, cat,
                                        null, null, null, "u");
                                live.put(targetId, new TxRecord(newType == 0
                                        ? com.damien.youyu.domain.TransactionType.EXPENSE
                                        : com.damien.youyu.domain.TransactionType.INCOME,
                                        amount, acc, null, null));
                            }
                        }
                    }
                }

                // ---- 每一步之后断言不变式 ----
                Map<Long, BigDecimal> expected = computeModel(accountIds, initial, live);
                for (Long accId : accountIds) {
                    BigDecimal stored = balanceOf(ledgerId, accId);
                    // 存储的 current_balance == 模型重算值。
                    assertThat(stored).as("seq=%d step=%d account=%d 模型守恒", seq, step, accId)
                            .isEqualByComparingTo(expected.get(accId));
                    // 存储的 current_balance == 服务重算(recomputeBalance)值（需求 4.13）。
                    assertThat(stored).as("seq=%d step=%d account=%d recompute", seq, step, accId)
                            .isEqualByComparingTo(accounts.recomputeBalance(ledgerId, accId));
                }
                // 推论：所有账户余额之和 == Σ初始 + Σ收入 − Σ支出（转账净额为 0）。
                assertThat(sumBalances(ledgerId, accountIds))
                        .as("seq=%d step=%d 账户余额之和(转账不改变总和)", seq, step)
                        .isEqualByComparingTo(expectedTotal(initial, live));
            }
        }
    }

    /** 由初始余额与存活交易集合折叠出每个账户的应有余额（模型基准，独立于被测的增量实现）。 */
    private static Map<Long, BigDecimal> computeModel(
            List<Long> accountIds, Map<Long, BigDecimal> initial, Map<Long, TxRecord> live) {
        Map<Long, BigDecimal> bal = new HashMap<>();
        for (Long id : accountIds) {
            bal.put(id, initial.get(id));
        }
        for (TxRecord r : live.values()) {
            switch (r.type()) {
                case EXPENSE -> bal.merge(r.accountId(), r.amount().negate(), BigDecimal::add);
                case INCOME -> bal.merge(r.accountId(), r.amount(), BigDecimal::add);
                case TRANSFER -> {
                    bal.merge(r.sourceId(), r.amount().negate(), BigDecimal::add);
                    bal.merge(r.destId(), r.amount(), BigDecimal::add);
                }
                default -> {
                    // 不会发生
                }
            }
        }
        return bal;
    }

    private BigDecimal sumBalances(long ledgerId, List<Long> accountIds) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Long id : accountIds) {
            sum = sum.add(balanceOf(ledgerId, id));
        }
        return sum;
    }

    private static BigDecimal expectedTotal(Map<Long, BigDecimal> initial, Map<Long, TxRecord> live) {
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal v : initial.values()) {
            sum = sum.add(v);
        }
        for (TxRecord r : live.values()) {
            switch (r.type()) {
                case EXPENSE -> sum = sum.subtract(r.amount());
                case INCOME -> sum = sum.add(r.amount());
                default -> {
                    // 转账净额为 0
                }
            }
        }
        return sum;
    }

    // ---------------- Property 2：交易非法输入零副作用 ----------------

    /**
     * Feature: youyu-ledger, Property 2: 对任意交易创建请求，若其金额 &lt; 0.01、&gt; 上限、或小数位
     * 超过 2 位，或缺少金额/账户/(支出或收入)分类等必填字段，或引用了不存在的账户，则该请求应被拒绝，
     * 不创建任何 Transaction，且所有账户的 current_balance 保持不变。
     */
    @Test
    void property2_invalidCreateRejectedWithZeroSideEffect() {
        Random rng = new Random(202_002L);
        TransactionService tx = txService();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            long ledgerId = 200_000_000L + iter;
            Account acc = createAccount(ledgerId, rng);
            Long expenseCat = createCategory(ledgerId, CategoryKind.EXPENSE, rng).getId();
            Long incomeCat = createCategory(ledgerId, CategoryKind.INCOME, rng).getId();
            BigDecimal balanceBefore = acc.getCurrentBalance();
            boolean incomeKind = rng.nextBoolean();
            String type = incomeKind ? "income" : "expense";
            Long cat = incomeKind ? incomeCat : expenseCat;

            int badCase = rng.nextInt(7);
            String expectedCode;
            final BigDecimal amount;
            final Long accountId;
            final Long categoryId;
            switch (badCase) {
                case 0 -> { // 金额 < 0.01（0 或负）
                    amount = rng.nextBoolean() ? new BigDecimal("0.00")
                            : new BigDecimal(-(1 + rng.nextInt(1_000_000))).movePointLeft(2);
                    accountId = acc.getId();
                    categoryId = cat;
                    expectedCode = "AMOUNT_INVALID";
                }
                case 1 -> { // 金额 > 上限
                    amount = AMOUNT_MAX.add(new BigDecimal(1 + rng.nextInt(1_000_000)).movePointLeft(2));
                    accountId = acc.getId();
                    categoryId = cat;
                    expectedCode = "AMOUNT_INVALID";
                }
                case 2 -> { // 小数位 > 2（第三位非零）
                    long thirds = 1 + rng.nextInt(9_999_999);
                    if (thirds % 10 == 0) {
                        thirds += 1;
                    }
                    amount = new BigDecimal(thirds).movePointLeft(3);
                    accountId = acc.getId();
                    categoryId = cat;
                    expectedCode = "AMOUNT_INVALID";
                }
                case 3 -> { // 缺少金额
                    amount = null;
                    accountId = acc.getId();
                    categoryId = cat;
                    expectedCode = "FIELD_REQUIRED";
                }
                case 4 -> { // 缺少账户
                    amount = validAmount(rng, 1_000_000L);
                    accountId = null;
                    categoryId = cat;
                    expectedCode = "FIELD_REQUIRED";
                }
                case 5 -> { // 缺少分类（支出/收入）
                    amount = validAmount(rng, 1_000_000L);
                    accountId = acc.getId();
                    categoryId = null;
                    expectedCode = "FIELD_REQUIRED";
                }
                default -> { // 引用不存在的账户
                    amount = validAmount(rng, 1_000_000L);
                    accountId = acc.getId() + 9_000_000L;
                    categoryId = cat;
                    expectedCode = "NOT_FOUND";
                }
            }

            ApiException ex = catchThrowableOfType(() -> tx.create(ledgerId, ledgerId, type, amount,
                    accountId, categoryId, null, null, null, null), ApiException.class);

            assertThat(ex).as("badCase=%d 应被拒绝", badCase).isNotNull();
            assertThat(ex.getCode()).isEqualTo(expectedCode);
            // 零副作用：无任何 Transaction 落库，账户余额不变。
            assertThat(transactionRepository.findByLedgerId(ledgerId)).isEmpty();
            assertThat(balanceOf(ledgerId, acc.getId())).isEqualByComparingTo(balanceBefore);
        }
    }

    // ---------------- Property 3：转账源目标相同被拒 ----------------

    /**
     * Feature: youyu-ledger, Property 3: 对任意账户，当提交一笔源账户与目标账户相同的转账交易时，系统应
     * 拒绝该交易并返回源目标不可相同错误（TRANSFER_SAME_ACCOUNT），且不改变任何账户的 current_balance。
     */
    @Test
    void property3_transferSameAccountRejectedWithZeroSideEffect() {
        Random rng = new Random(303_003L);
        TransactionService tx = txService();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            long ledgerId = 300_000_000L + iter;
            Account acc = createAccount(ledgerId, rng);
            BigDecimal balanceBefore = acc.getCurrentBalance();
            BigDecimal amount = validAmount(rng, 1_000_000L);

            ApiException ex = catchThrowableOfType(() -> tx.create(ledgerId, ledgerId, "transfer", amount,
                    null, null, acc.getId(), acc.getId(), null, null), ApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getCode()).isEqualTo("TRANSFER_SAME_ACCOUNT");
            // 零副作用：余额不变、无 Transaction 落库。
            assertThat(balanceOf(ledgerId, acc.getId())).isEqualByComparingTo(balanceBefore);
            assertThat(transactionRepository.findByLedgerId(ledgerId)).isEmpty();
        }
    }
}
