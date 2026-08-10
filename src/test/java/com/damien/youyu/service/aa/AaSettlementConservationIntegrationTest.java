package com.damien.youyu.service.aa;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.api.dto.AaSettlementResponse;
import com.damien.youyu.domain.AaSettlement;
import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.repository.AaSettlementRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionSplitRepository;

/**
 * 任务 4.4：{@link AaSettlementService} settle / revert 全链路的<b>服务 + DB 集成</b>属性 / 示例测试
 * （H2 + 真实 Repository），校验 design.md 的 Property 4（账户守恒）与需求 6.2–6.5、7.1：
 *
 * <ul>
 *   <li><b>Property 4（账户守恒）</b>：本人付款的 AA 支出使付款人账户恰减实付额；结算使结清人本人侧账户
 *       恰变动结算额；撤销结算精确回滚账户，无漂移。<b>Validates: Requirements 6.2, 6.3, 7.1</b></li>
 *   <li><b>执行建议后 net 全 0</b>：对随机 AA 账本（2–5 成员、若干本人付款支出、均分或自定义分摊）计算清算
 *       建议并逐条 {@code settle}（付 / 收两侧交替执行）后，全体成员 net=0。
 *       <b>Validates: Requirements 5.2, 5.4, 6.6</b></li>
 *   <li><b>撤销后精确回滚</b>：{@code revert} 全部结算后，账户余额与派生净额精确回到结算前状态。
 *       <b>Validates: Requirements 6.5</b></li>
 * </ul>
 *
 * <p>纯核心（分摊守恒 / Σnet=0 / 清算可清零 / 账户守恒模型）已在 {@link AaSettlementConservationPropertyTest}
 * 以 jqwik 高 tries（500/500/300/500）覆盖；本类聚焦真实 settle/revert 对<b>账户与数据库</b>的副作用，
 * 采用<b>有种子的随机化属性风格循环</b>（{@link #RANDOM_ITERATIONS} 次，账本规模有界）以控制运行时长，
 * 符合 design.md「Testing Strategy」对集成级属性「较少 tries / 示例化」的取舍。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AaSettlementConservationIntegrationTest {

    /** 集成级随机化属性的迭代次数（有界账本规模，保证套件运行时长可控）。 */
    private static final int RANDOM_ITERATIONS = 60;
    private static final long SEED = 20250609L;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant T0 = Instant.parse("2025-06-01T04:30:00Z");
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("100000.00");

    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private TransactionSplitRepository splitRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private LedgerMemberRepository memberRepository;
    @Autowired
    private AaSettlementRepository settlementRepository;

    private AaSettlementService settlementService() {
        return new AaSettlementService(transactionRepository, splitRepository, settlementRepository,
                ledgerRepository, memberRepository, accountRepository, Clock.fixed(T0, ZONE));
    }

    private AaExpenseService expenseService() {
        return new AaExpenseService(transactionRepository, splitRepository, accountRepository,
                categoryRepository, ledgerRepository, memberRepository, settlementRepository,
                Clock.fixed(T0, ZONE));
    }

    // ---------------- 随机化属性风格集成测试 ----------------

    /**
     * 随机化属性风格全链路：建随机 AA 账本 → 若干本人付款支出（均分 / 自定义）→ 断言付款人账户恰减实付
     * → 执行清算建议（付 / 收两侧交替 settle）→ 断言每次 settle 恰变动本人侧账户 + 最终 net 全 0
     * → 撤销全部结算 → 断言账户与净额精确回到结算前。
     */
    @Test
    void randomizedLedgers_conserveAccounts_zeroNets_andRevertRestoresExactly() {
        Random rnd = new Random(SEED);
        for (int iter = 0; iter < RANDOM_ITERATIONS; iter++) {
            runOneRandomScenario(rnd, iter);
        }
    }

    private void runOneRandomScenario(Random rnd, int iter) {
        AaSettlementService settle = settlementService();
        AaExpenseService expenses = expenseService();

        // 1) 成员（2–5）与各自账户；用户 id 全局唯一，避免跨迭代账户冲突。
        int n = 2 + rnd.nextInt(4);
        List<Long> users = new ArrayList<>();
        for (int m = 0; m < n; m++) {
            users.add((long) (iter * 100 + m + 1));
        }
        long owner = users.get(0);
        Ledger ledger = aaLedger(owner);
        Map<Long, Long> accountIdByUser = new HashMap<>();
        for (int m = 0; m < n; m++) {
            long u = users.get(m);
            member(ledger.getId(), u, m == 0 ? LedgerMember.ROLE_OWNER : LedgerMember.ROLE_EDITOR);
            accountIdByUser.put(u, account(u, INITIAL_BALANCE).getId());
        }
        Category cat = category(ledger.getId());

        // 2) 若干本人付款支出（1–5 笔）。
        int expenseCount = 1 + rnd.nextInt(5);
        Map<Long, BigDecimal> paidByUser = new HashMap<>();
        for (int k = 0; k < expenseCount; k++) {
            long payer = users.get(rnd.nextInt(n));
            long totalCents = 1 + rnd.nextInt(50_000); // 0.01 – 500.00
            BigDecimal amount = BigDecimal.valueOf(totalCents, 2);

            boolean custom = rnd.nextBoolean();
            String mode = custom ? AaExpenseService.SPLIT_CUSTOM : AaExpenseService.SPLIT_EVEN;
            Map<Long, BigDecimal> customShares = custom ? randomCustomShares(users, totalCents, rnd) : null;

            expenses.create(payer, ledger.getId(), amount, cat.getId(), payer,
                    accountIdByUser.get(payer), null, null, mode, users, customShares);
            paidByUser.merge(payer, amount, BigDecimal::add);
        }

        // 3) Property 4（支出）：付款人账户恰减其实付全额之和。
        for (long u : users) {
            BigDecimal expected = INITIAL_BALANCE.subtract(paidByUser.getOrDefault(u, BigDecimal.ZERO));
            assertThat(balance(accountIdByUser.get(u)))
                    .as("iter %d expense-debit for user %d", iter, u)
                    .isEqualByComparingTo(expected);
        }

        // 4) 结算前快照（账户 + 净额）。
        Map<Long, BigDecimal> preSettleBalances = new HashMap<>();
        for (long u : users) {
            preSettleBalances.put(u, balance(accountIdByUser.get(u)));
        }
        Map<Long, BigDecimal> preNets = netByUser(settle.settlement(owner, ledger.getId()));

        // 5) 执行清算建议：逐条 settle（付 / 收两侧交替），断言每次恰变动本人侧账户结算额。
        List<AaSettlementResponse.SuggestedTransfer> suggestions =
                settle.settlement(owner, ledger.getId()).suggestedTransfers();
        List<long[]> reverts = new ArrayList<>(); // [settlementId, settlerUserId, accountId]
        List<BigDecimal> revertAmounts = new ArrayList<>();
        int idx = 0;
        for (AaSettlementResponse.SuggestedTransfer t : suggestions) {
            long from = t.fromUserId();
            long to = t.toUserId();
            BigDecimal amt = t.amount();
            boolean asReceiver = (idx++ % 2 == 0); // 交替：偶数条由收款方结清，奇数条由付款方结清
            long settler = asReceiver ? to : from;
            long acct = accountIdByUser.get(settler);
            BigDecimal before = balance(acct);

            AaSettlement s = asReceiver
                    ? settle.settle(settler, ledger.getId(), null, from, amt, acct)  // 收款方：+amt
                    : settle.settle(settler, ledger.getId(), to, null, amt, acct);   // 付款方：−amt

            BigDecimal after = balance(acct);
            BigDecimal expectedAfter = asReceiver ? before.add(amt) : before.subtract(amt);
            assertThat(after)
                    .as("iter %d settle exact account effect (settler %d, receiver=%s)", iter, settler, asReceiver)
                    .isEqualByComparingTo(expectedAfter);

            reverts.add(new long[] { s.getId(), settler, acct });
            revertAmounts.add(amt);
        }

        // 6) 执行建议后所有 net 归零（需求 5.x / 6.6）。
        AaSettlementResponse settled = settle.settlement(owner, ledger.getId());
        assertThat(settled.allSettled())
                .as("iter %d all settled after executing suggestions", iter)
                .isTrue();
        assertThat(netByUser(settled).values())
                .allSatisfy(v -> assertThat(v).isEqualByComparingTo("0.00"));

        // 7) 撤销全部结算：每次 revert 精确回滚本人侧账户。
        for (int r = reverts.size() - 1; r >= 0; r--) {
            long[] info = reverts.get(r);
            long settlementId = info[0];
            long settler = info[1];
            long acct = info[2];
            BigDecimal amt = revertAmounts.get(r);
            boolean wasReceiver = (r % 2 == 0);
            BigDecimal before = balance(acct);

            settle.revert(settler, ledger.getId(), settlementId);

            BigDecimal after = balance(acct);
            // 收款方结清撤销 → −amt（抵消原 +amt）；付款方结清撤销 → +amt（抵消原 −amt）。
            BigDecimal expectedAfter = wasReceiver ? before.subtract(amt) : before.add(amt);
            assertThat(after)
                    .as("iter %d revert exact rollback (settler %d)", iter, settler)
                    .isEqualByComparingTo(expectedAfter);
        }

        // 8) 撤销后精确回滚：账户余额与净额精确回到结算前（需求 6.5）。
        for (long u : users) {
            assertThat(balance(accountIdByUser.get(u)))
                    .as("iter %d account restored for user %d", iter, u)
                    .isEqualByComparingTo(preSettleBalances.get(u));
        }
        Map<Long, BigDecimal> postNets = netByUser(settle.settlement(owner, ledger.getId()));
        assertThat(postNets.keySet()).isEqualTo(preNets.keySet());
        for (long u : users) {
            assertThat(postNets.get(u))
                    .as("iter %d net restored for user %d", iter, u)
                    .isEqualByComparingTo(preNets.get(u));
        }
    }

    // ---------------- 示例化全链路（确定性，便于回归定位） ----------------

    /**
     * 示例：3 人各付款，均分与自定义混合，执行建议清零后再全部撤销，账户 / 净额精确复原。
     * 用确定性数字验证 Property 4 + 执行建议后 net 全 0 + 撤销精确回滚。
     */
    @Test
    void example_threeMembers_executeThenRevert_exactRoundTrip() {
        AaSettlementService settle = settlementService();
        AaExpenseService expenses = expenseService();

        long alice = 1L;
        long bob = 2L;
        long carol = 3L;
        Ledger l = aaLedger(alice);
        member(l.getId(), alice, LedgerMember.ROLE_OWNER);
        member(l.getId(), bob, LedgerMember.ROLE_EDITOR);
        member(l.getId(), carol, LedgerMember.ROLE_EDITOR);
        long aliceAcc = account(alice, INITIAL_BALANCE).getId();
        long bobAcc = account(bob, INITIAL_BALANCE).getId();
        long carolAcc = account(carol, INITIAL_BALANCE).getId();
        Category cat = category(l.getId());
        List<Long> all = List.of(alice, bob, carol);

        // Alice 付 90 均分（各 30）；Carol 付 30 自定义（Alice 10 / Bob 20 / Carol 0）。
        expenses.create(alice, l.getId(), new BigDecimal("90.00"), cat.getId(), alice, aliceAcc,
                null, null, AaExpenseService.SPLIT_EVEN, all, null);
        expenses.create(carol, l.getId(), new BigDecimal("30.00"), cat.getId(), carol, carolAcc,
                null, null, AaExpenseService.SPLIT_CUSTOM, all, Map.of(
                        alice, new BigDecimal("10.00"),
                        bob, new BigDecimal("20.00"),
                        carol, new BigDecimal("0.00")));

        // 支出扣款：Alice 100000−90=99910；Carol 100000−30=99970；Bob 不动。
        assertThat(balance(aliceAcc)).isEqualByComparingTo("99910.00");
        assertThat(balance(bobAcc)).isEqualByComparingTo("100000.00");
        assertThat(balance(carolAcc)).isEqualByComparingTo("99970.00");

        // 净额：Alice = 90−(30+10)=50；Bob = -(30+20)=-50；Carol = 30−(30+0)=0。Σ=0。
        Map<Long, BigDecimal> preNets = netByUser(settle.settlement(alice, l.getId()));
        assertThat(preNets.get(alice)).isEqualByComparingTo("50.00");
        assertThat(preNets.get(bob)).isEqualByComparingTo("-50.00");
        assertThat(preNets.get(carol)).isEqualByComparingTo("0.00");

        Map<Long, BigDecimal> preSettleBalances = Map.of(
                alice, balance(aliceAcc), bob, balance(bobAcc), carol, balance(carolAcc));

        // 建议：Bob→Alice 50。Bob 作为付款方结清：Bob 账户 −50。
        List<AaSettlementResponse.SuggestedTransfer> suggestions =
                settle.settlement(alice, l.getId()).suggestedTransfers();
        assertThat(suggestions).hasSize(1);
        AaSettlementResponse.SuggestedTransfer t = suggestions.get(0);
        assertThat(t.fromUserId()).isEqualTo(bob);
        assertThat(t.toUserId()).isEqualTo(alice);
        assertThat(t.amount()).isEqualByComparingTo("50.00");

        AaSettlement s = settle.settle(bob, l.getId(), alice, null, t.amount(), bobAcc);
        assertThat(balance(bobAcc)).isEqualByComparingTo("99950.00"); // 100000 − 50

        // 执行建议后全体 net 归零。
        AaSettlementResponse after = settle.settlement(alice, l.getId());
        assertThat(after.allSettled()).isTrue();
        assertThat(netByUser(after).values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo("0.00"));

        // 撤销：Bob 账户回滚 +50 → 100000，净额精确复原。
        settle.revert(bob, l.getId(), s.getId());
        assertThat(balance(bobAcc)).isEqualByComparingTo(preSettleBalances.get(bob));
        assertThat(balance(aliceAcc)).isEqualByComparingTo(preSettleBalances.get(alice));
        assertThat(balance(carolAcc)).isEqualByComparingTo(preSettleBalances.get(carol));
        Map<Long, BigDecimal> postNets = netByUser(settle.settlement(alice, l.getId()));
        assertThat(postNets.get(alice)).isEqualByComparingTo("50.00");
        assertThat(postNets.get(bob)).isEqualByComparingTo("-50.00");
        assertThat(postNets.get(carol)).isEqualByComparingTo("0.00");
    }

    // ---------------- helpers ----------------

    /** 生成一组和为 totalCents 的非负自定义分摊（每人一份，Σ=总额，2 位小数）。 */
    private Map<Long, BigDecimal> randomCustomShares(List<Long> users, long totalCents, Random rnd) {
        Map<Long, BigDecimal> shares = new HashMap<>();
        long remaining = totalCents;
        for (int i = 0; i < users.size(); i++) {
            long c;
            if (i == users.size() - 1) {
                c = remaining;
            } else {
                c = remaining <= 0 ? 0 : (long) (rnd.nextDouble() * (remaining + 1));
                if (c > remaining) {
                    c = remaining;
                }
            }
            shares.put(users.get(i), BigDecimal.valueOf(c, 2));
            remaining -= c;
        }
        return shares;
    }

    private BigDecimal balance(long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    private Map<Long, BigDecimal> netByUser(AaSettlementResponse resp) {
        return resp.nets().stream()
                .collect(Collectors.toMap(AaSettlementResponse.MemberNet::userId,
                        AaSettlementResponse.MemberNet::net));
    }

    private Ledger aaLedger(long ownerId) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Ledger l = new Ledger();
        l.setUserId(ownerId);
        l.setName("旅行 AA");
        l.setType(Ledger.TYPE_AA);
        l.setSortOrder(0);
        l.setDefault(false);
        l.setCreatedAt(now);
        l.setUpdatedAt(now);
        return ledgerRepository.save(l);
    }

    private void member(long ledgerId, long userId, String role) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        LedgerMember m = new LedgerMember();
        m.setLedgerId(ledgerId);
        m.setUserId(userId);
        m.setRole(role);
        m.setCreatedAt(now);
        memberRepository.save(m);
    }

    private Account account(long userId, BigDecimal balance) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Account a = new Account();
        a.setUserId(userId);
        a.setName("现金");
        a.setType(AccountType.CASH);
        a.setInitialBalance(balance);
        a.setCurrentBalance(balance);
        a.setSortOrder(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return accountRepository.save(a);
    }

    private Category category(long ledgerId) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(CategoryKind.EXPENSE);
        c.setName("餐饮");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c);
    }
}
