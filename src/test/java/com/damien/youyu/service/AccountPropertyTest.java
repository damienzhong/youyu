package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * {@link AccountService} 的属性测试，覆盖设计文档 Correctness Properties 中的
 * Property 4-8（关联需求 3.1、3.3、3.5、3.6、3.7）：
 *
 * <ul>
 *   <li>Property 4：账户创建后 {@code current_balance} 严格等于提交的初始余额。</li>
 *   <li>Property 5：名称/类型/初始余额任一非法则拒绝创建、零持久化，并返回指明具体无效字段的错误。</li>
 *   <li>Property 6：修改名称/类型后余额保持不变。</li>
 *   <li>Property 7：账户列表返回且仅返回本人全部账户（数量与内容一致），无账户时为空且跨用户隔离。</li>
 *   <li>Property 8：仍被至少一笔交易引用的账户不可删除，账户与余额保持不变。</li>
 * </ul>
 *
 * <p>沿用仓库内 DB 支撑型属性测试的既定范式（见 {@code MultiTenantIsolationPropertyTest}）：在
 * {@code @DataJpaTest} + 真实 H2 与真实 {@link AccountRepository}/{@link TransactionRepository} 上，
 * 以固定种子的 {@link Random} 在 {@code @Test} 循环内智能生成受约束的随机输入，驱动 ≥100 次迭代，
 * 被测的 {@link AccountService} 业务逻辑全部真实执行，不使用任何 mock。时间以固定 {@link Clock}
 * 注入以获得确定性。（本类为 JUnit Jupiter 切片测试，随机输入用 {@link Random} 生成而非 jqwik
 * {@code Arbitrary}，因为字符串/枚举 Arbitrary 需运行在 jqwik 线程内。）</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final LocalDateTime FIXED_TIME = LocalDateTime.ofInstant(T0, ZONE);
    private static final int ITERATIONS = 120;

    /** 与 {@link AccountService} 一致的初始余额允许边界（DECIMAL(18,2)）。 */
    private static final BigDecimal BALANCE_MAX = new BigDecimal("9999999999999999.99");
    private static final BigDecimal BALANCE_MIN = BALANCE_MAX.negate();

    private static final AccountType[] TYPES = AccountType.values();

    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    private AccountService service() {
        return new AccountService(accountRepository, transactionRepository, Clock.fixed(T0, ZONE));
    }

    // ---------------- 智能生成器（约束到输入空间的随机输入） ----------------

    /** 合法名称：字母，长度 minLen-maxLen（去空白后仍在该范围）。 */
    private static String letters(Random rng, int minLen, int maxLen) {
        int len = minLen + rng.nextInt(maxLen - minLen + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + rng.nextInt(26)));
        }
        return sb.toString();
    }

    /** 合法名称：去空白后 1-50 个字符。 */
    private static String validName(Random rng) {
        return letters(rng, 1, 50);
    }

    /** 受支持账户类型。 */
    private static AccountType validType(Random rng) {
        return TYPES[rng.nextInt(TYPES.length)];
    }

    /**
     * 合法初始余额：范围内、恰好两位小数（含负值，覆盖信用卡欠款）。
     * 以「分」为单位生成后右移两位，保证 scale == 2。
     */
    private static BigDecimal validBalance(Random rng) {
        long cents = rng.nextLong(-9_999_999_999L, 10_000_000_000L);
        return new BigDecimal(cents).movePointLeft(2);
    }

    /** 非法名称：去空白后为空，或长度 > 50。 */
    private static String invalidName(Random rng) {
        if (rng.nextBoolean()) {
            // 全空白（含空串）。
            int len = rng.nextInt(5);
            return " ".repeat(len);
        }
        // 超长（51-80）。
        return letters(rng, 51, 80);
    }

    /** 非法类型：小写字母串，绝不等于任何大写枚举名。 */
    private static String invalidType(Random rng) {
        return letters(rng, 1, 12);
    }

    /** 非法初始余额：小数位超过 2（第三位非零），或超出允许范围（过大/过小）。 */
    private static BigDecimal invalidBalance(Random rng) {
        int kind = rng.nextInt(3);
        if (kind == 0) {
            // 三位小数且第三位非零 -> 无法无损缩放到 2 位。
            long thirds = 1 + rng.nextInt(9_999_999);
            if (thirds % 10 == 0) {
                thirds += 1;
            }
            return new BigDecimal(thirds).movePointLeft(3);
        }
        long over = 1 + rng.nextInt(9_999_999);
        BigDecimal delta = new BigDecimal(over).movePointLeft(2);
        // 超上界或低于下界（scale 2，仅越界）。
        return kind == 1 ? BALANCE_MAX.add(delta) : BALANCE_MIN.subtract(delta);
    }

    // ---------------- 属性 ----------------

    /**
     * Feature: youyu-ledger, Property 4: 对任意满足约束的账户输入（名称去空白后 1–50 字符、类型受支持、
     * 初始余额在允许范围内且最多两位小数），创建该账户后其 current_balance 应严格等于提交的初始余额。
     */
    @Test
    void property4_createSetsCurrentBalanceToInitialBalance() {
        Random rng = new Random(40040L);
        AccountService service = service();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            long userId = 4_000_000L + iter;
            String name = validName(rng);
            AccountType type = validType(rng);
            BigDecimal submitted = validBalance(rng);
            int sortOrder = rng.nextInt(101);

            Account account = service.create(userId, name, type.name(), submitted, sortOrder);

            // current_balance 严格等于提交的初始余额（数值与 scale 均一致）。
            assertThat(account.getCurrentBalance()).isEqualByComparingTo(submitted);
            assertThat(account.getCurrentBalance()).isEqualTo(submitted.setScale(2));
            // current_balance 与 initial_balance 一致（创建即初始化）。
            assertThat(account.getCurrentBalance()).isEqualByComparingTo(account.getInitialBalance());
            assertThat(account.getInitialBalance()).isEqualByComparingTo(submitted);
        }
    }

    /**
     * Feature: youyu-ledger, Property 5: 对任意账户创建输入，若其名称、类型或初始余额中任一项不满足约束,
     * 则系统应拒绝创建、不持久化任何数据，并返回指明具体无效字段的错误。
     */
    @Test
    void property5_invalidFieldRejectedWithZeroSideEffect() {
        Random rng = new Random(50050L);
        AccountService service = service();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            long userId = 5_000_000L + iter;
            int badCase = rng.nextInt(3);

            // 仅让被测字段非法，其余字段保持合法，使期望的无效字段确定。
            final String name;
            final String type;
            final BigDecimal balance;
            final String expectedField;
            switch (badCase) {
                case 0 -> {
                    name = invalidName(rng);
                    type = validType(rng).name();
                    balance = validBalance(rng);
                    expectedField = "name";
                }
                case 1 -> {
                    name = validName(rng);
                    type = invalidType(rng);
                    balance = validBalance(rng);
                    expectedField = "type";
                }
                default -> {
                    name = validName(rng);
                    type = validType(rng).name();
                    balance = invalidBalance(rng);
                    expectedField = "initialBalance";
                }
            }

            ApiException ex = catchThrowableOfType(
                    () -> service.create(userId, name, type, balance, 0), ApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getCode()).isEqualTo("ACCOUNT_FIELD_INVALID");
            assertThat(ex.getField()).isEqualTo(expectedField);
            // 零副作用：未持久化任何账户。
            assertThat(accountRepository.countByUserId(userId)).isZero();
        }
    }

    /**
     * Feature: youyu-ledger, Property 6: 对任意已存在账户与任意满足约束的新名称/新类型，修改该账户的
     * 名称或类型后，其 current_balance 应与修改前相等。
     */
    @Test
    void property6_updatePreservesBalance() {
        Random rng = new Random(60060L);
        AccountService service = service();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            long userId = 6_000_000L + iter;
            BigDecimal balance = validBalance(rng);
            Account created = service.create(userId, validName(rng), validType(rng).name(),
                    balance, rng.nextInt(101));
            BigDecimal balanceBefore = created.getCurrentBalance();

            String newName = validName(rng);
            AccountType newType = validType(rng);
            Account updated = service.update(userId, created.getId(), newName, newType.name());

            assertThat(updated.getName()).isEqualTo(newName);
            assertThat(updated.getType()).isEqualTo(newType);
            // 余额保持不变（数值与 scale 均一致）。
            assertThat(updated.getCurrentBalance()).isEqualByComparingTo(balanceBefore);
            assertThat(updated.getCurrentBalance()).isEqualTo(balanceBefore);
            assertThat(updated.getInitialBalance()).isEqualByComparingTo(balanceBefore);
        }
    }

    /**
     * Feature: youyu-ledger, Property 7: 对任意用户的账户集合，查询账户列表应返回且仅返回该用户自己的
     * 全部账户（数量与内容一致）；当该用户无账户时应返回空列表。
     */
    @Test
    void property7_listReturnsExactlyOwnAccounts() {
        Random rng = new Random(70070L);
        AccountService service = service();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            long userA = 7_000_000L + iter * 2L;
            long userB = userA + 1L;

            int nA = rng.nextInt(5);
            int nB = rng.nextInt(5);

            List<Long> idsA = new ArrayList<>();
            for (int i = 0; i < nA; i++) {
                idsA.add(service.create(userA, validName(rng), validType(rng).name(),
                        validBalance(rng), rng.nextInt(101)).getId());
            }
            List<Long> idsB = new ArrayList<>();
            for (int i = 0; i < nB; i++) {
                idsB.add(service.create(userB, validName(rng), validType(rng).name(),
                        validBalance(rng), rng.nextInt(101)).getId());
            }

            List<Account> listA = service.list(userA);
            // 数量一致、内容一致、且全部归属 userA。
            assertThat(listA).hasSize(nA);
            assertThat(listA).allSatisfy(a -> assertThat(a.getUserId()).isEqualTo(userA));
            assertThat(listA.stream().map(Account::getId).toList())
                    .containsExactlyInAnyOrderElementsOf(idsA);
            // 隔离：不含 userB 的任何账户（仅当 userB 确有账户时校验，避免空集断言前置条件）。
            if (!idsB.isEmpty()) {
                assertThat(listA.stream().map(Account::getId).toList())
                        .doesNotContainAnyElementsOf(idsB);
            }

            // 无账户的用户返回空列表。
            assertThat(service.list(9_900_000L + iter)).isEmpty();
        }
    }

    /**
     * Feature: youyu-ledger, Property 8: 对任意仍被至少一笔 Transaction 引用的账户，删除请求应被拒绝，
     * 账户及其余额保持不变，并返回该账户存在交易记录的提示。
     */
    @Test
    void property8_accountWithTransactionsCannotBeDeleted() {
        Random rng = new Random(80080L);
        AccountService service = service();

        for (int iter = 0; iter < ITERATIONS; iter++) {
            long userId = 8_000_000L + iter;
            BigDecimal balance = validBalance(rng);
            Account account = service.create(userId, validName(rng), validType(rng).name(),
                    balance, rng.nextInt(101));
            BigDecimal balanceBefore = account.getCurrentBalance();

            // 以账户/源账户/目标账户三种引用方式之一持久化至少一笔交易。
            persistReferencingTransaction(userId, account.getId(), rng.nextInt(3), rng);

            ApiException ex = catchThrowableOfType(
                    () -> service.delete(userId, account.getId()), ApiException.class);

            assertThat(ex).isNotNull();
            assertThat(ex.getCode()).isEqualTo("ACCOUNT_IN_USE");
            // 账户仍存在，余额未变。
            Account after = accountRepository.findByIdAndUserId(account.getId(), userId).orElseThrow();
            assertThat(after.getCurrentBalance()).isEqualTo(balanceBefore);
            assertThat(after.getInitialBalance()).isEqualByComparingTo(balance);
        }
    }

    // ---------------- 持久化辅助 ----------------

    private void persistReferencingTransaction(long userId, Long accountId, int refKind, Random rng) {
        BigDecimal amount = new BigDecimal(1 + rng.nextInt(9_999_999)).movePointLeft(2);
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setAmount(amount);
        tx.setOccurredAt(FIXED_TIME);
        tx.setCreatedAt(FIXED_TIME);
        tx.setUpdatedAt(FIXED_TIME);
        switch (refKind) {
            case 0 -> {
                // 作为普通账户被引用（支出）。
                tx.setType(TransactionType.EXPENSE);
                tx.setAccountId(accountId);
                tx.setCategoryId(1L);
            }
            case 1 -> {
                // 作为转账源账户被引用。
                tx.setType(TransactionType.TRANSFER);
                tx.setSourceAccountId(accountId);
                tx.setDestinationAccountId(accountId + 1_000_000L);
            }
            default -> {
                // 作为转账目标账户被引用。
                tx.setType(TransactionType.TRANSFER);
                tx.setSourceAccountId(accountId + 1_000_000L);
                tx.setDestinationAccountId(accountId);
            }
        }
        transactionRepository.save(tx);
    }
}
