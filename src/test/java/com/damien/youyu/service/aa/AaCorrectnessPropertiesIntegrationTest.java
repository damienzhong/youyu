package com.damien.youyu.service.aa;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
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

import com.damien.youyu.api.dto.AaOverviewResponse;
import com.damien.youyu.api.dto.AaSettlementResponse;
import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionSplit;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.AaSettlementRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionSplitRepository;
import com.damien.youyu.service.AggregateService;

/**
 * 任务 8.2：<b>在真实服务 / 持久化链路上</b>校验 design.md 的 6 条 Correctness Properties 全部成立、
 * 金额闭合（关联需求「全部」）。
 *
 * <p>本类是对纯核心属性（{@link AaMathPropertyTest}、{@link AaSettlementConservationPropertyTest}）与
 * 结算集成属性（{@link AaSettlementConservationIntegrationTest}）的<b>汇总收口</b>：把六条不变量放在<b>同一条
 * 真实链路</b>上（{@link AaExpenseService} 记账 + 落分摊 + 账户扣款 → {@link AaLedgerService#overview}
 * 三口径 → {@link AaSettlementService} 净额 / 清算 / 结清 → {@link AggregateService} 全部账本聚合），
 * 经 H2 + 真实 Repository 驱动，全部断言从数据库 / 服务真实读回，不使用任何桩。</p>
 *
 * <p>采用与 {@link AaSettlementConservationIntegrationTest} 一致的<b>有种子随机化属性风格</b>
 * （{@link #RANDOM_ITERATIONS} 次、账本规模有界），符合 design.md「Testing Strategy」对集成级属性
 * 「较少 tries / 示例化」的取舍；另附两个确定性示例（金额闭合逐分核对、特性隔离）便于回归定位。</p>
 *
 * <ul>
 *   <li><b>Property 1（分摊守恒）</b>：每笔 AA 支出落库后 Σ(各参与人 {@code share_amount}) = 总额。
 *       <b>Validates: Requirements 3.3, 3.4, 4.5</b></li>
 *   <li><b>Property 2（净额闭合）</b>：{@code settlement} 服务派生的全体成员净额 Σ = 0，记账后与结清后均成立。
 *       <b>Validates: Requirements 5.1</b></li>
 *   <li><b>Property 3（清算可清零）</b>：执行 {@code settlement} 给出的建议转账（笔数 ≤ n−1）后
 *       {@code allSettled=true} 且全体净额归 0。<b>Validates: Requirements 5.3</b></li>
 *   <li><b>Property 4（账户守恒 / 金额闭合）</b>：每个成员「初始余额 − 当前余额」= 其 {@code overview}
 *       的 {@code accountPaid}（真实现金净流出），记账后与结清后均成立。
 *       <b>Validates: Requirements 6.2, 6.3, 7.1</b></li>
 *   <li><b>Property 5（消费口径隔离）</b>：每个成员 {@code overview.myConsumption} = 其自身各笔
 *       {@code share_amount} 之和（从库独立汇总核对）；Σ 全体消费 = Σ 全部支出总额（应收 / 应付不计入消费）。
 *       <b>Validates: Requirements 4.4, 7.2</b></li>
 *   <li><b>Property 6（特性隔离）</b>：AA 账本的支出 / 结算 / 分类不进入 {@link AggregateService} 的
 *       「全部账本」聚合，个人 / 家庭账本原样纳入。<b>Validates: Requirements 7.4, 10.3</b></li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AaCorrectnessPropertiesIntegrationTest {

    /** 集成级随机化属性的迭代次数（有界账本规模，保证套件运行时长可控）。 */
    private static final int RANDOM_ITERATIONS = 60;
    private static final long SEED = 20250612L;

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

    private AaExpenseService expenseService() {
        return new AaExpenseService(transactionRepository, splitRepository, accountRepository,
                categoryRepository, ledgerRepository, memberRepository, settlementRepository,
                Clock.fixed(T0, ZONE));
    }

    private AaSettlementService settlementService() {
        return new AaSettlementService(transactionRepository, splitRepository, settlementRepository,
                ledgerRepository, memberRepository, accountRepository, Clock.fixed(T0, ZONE));
    }

    private AaLedgerService ledgerService(AaSettlementService settlement) {
        return new AaLedgerService(ledgerRepository, memberRepository, transactionRepository,
                splitRepository, settlementRepository, settlement);
    }

    private AggregateService aggregateService() {
        return new AggregateService(ledgerRepository, accountRepository, transactionRepository,
                categoryRepository, memberRepository);
    }

    // ===================================================================================
    // 随机化属性风格：真实链路上 Property 1 / 2 / 3 / 4 / 5 + 金额闭合
    // ===================================================================================

    @Test
    void randomizedLedgers_allSixPropertiesHold_onRealChain() {
        Random rnd = new Random(SEED);
        for (int iter = 0; iter < RANDOM_ITERATIONS; iter++) {
            runOneScenario(rnd, iter);
        }
    }

    private void runOneScenario(Random rnd, int iter) {
        AaExpenseService expenses = expenseService();
        AaSettlementService settle = settlementService();
        AaLedgerService overview = ledgerService(settle);

        // 1) 成员（2–5）与各自账户；user_id 全局唯一，避免跨迭代账户冲突。
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

        // 2) 若干支出（1–6 笔），本人付 / 他人代记（不触账户）× 均分 / 自定义混合。
        int expenseCount = 1 + rnd.nextInt(6);
        List<Long> expenseIds = new ArrayList<>();
        Map<Long, BigDecimal> selfPaidByUser = new HashMap<>(); // 本人付款实付额（进入其 accountPaid）
        BigDecimal totalOfAllExpenses = BigDecimal.ZERO;
        for (int k = 0; k < expenseCount; k++) {
            long payer = users.get(rnd.nextInt(n));
            // 记账人：一半几率由付款人本人记（本人付、扣账户），一半几率由他人代记（不触账户）。
            long recorder = rnd.nextBoolean() ? payer : users.get(rnd.nextInt(n));
            boolean selfPaid = recorder == payer;

            long totalCents = 1 + rnd.nextInt(80_000); // 0.01 – 800.00
            BigDecimal amount = BigDecimal.valueOf(totalCents, 2);
            totalOfAllExpenses = totalOfAllExpenses.add(amount);

            boolean custom = rnd.nextBoolean();
            String mode = custom ? AaExpenseService.SPLIT_CUSTOM : AaExpenseService.SPLIT_EVEN;
            Map<Long, BigDecimal> customShares = custom ? randomCustomShares(users, totalCents, rnd) : null;

            Long payerAccountId = selfPaid ? accountIdByUser.get(payer) : null;
            Transaction tx = expenses.create(recorder, ledger.getId(), amount, cat.getId(), payer,
                    payerAccountId, null, null, mode, users, customShares);
            expenseIds.add(tx.getId());
            if (selfPaid) {
                selfPaidByUser.merge(payer, amount, BigDecimal::add);
            }
        }

        // 3) Property 1（分摊守恒）：每笔落库 splits Σ = 该笔总额。
        for (Long txId : expenseIds) {
            List<TransactionSplit> splits = splitRepository.findByTransactionId(txId);
            BigDecimal splitSum = splits.stream()
                    .map(TransactionSplit::getShareAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal txTotal = transactionRepository.findById(txId).orElseThrow().getAmount();
            assertThat(splitSum)
                    .as("iter %d Property1 split conservation for tx %d", iter, txId)
                    .isEqualByComparingTo(txTotal);
        }

        // 4) 从库独立汇总每人分摊之和（用于 Property 5 交叉核对，不依赖被测服务）。
        Map<Long, BigDecimal> expectedConsumption = consumptionFromDb(expenseIds, users);

        // 5) Property 5（消费口径隔离）+ Property 4（账户守恒，记账后）+ receivable 口径：逐成员核对 overview。
        BigDecimal sumConsumption = BigDecimal.ZERO;
        Map<Long, BigDecimal> preNets = netByUser(settle.settlement(owner, ledger.getId()));
        for (long u : users) {
            AaOverviewResponse.Calibers cal = overview.overview(u, ledger.getId()).calibers();

            // Property 5：我的消费 = 自身各笔 share_amount 之和（应收 / 应付不计入消费）。
            assertThat(cal.myConsumption())
                    .as("iter %d Property5 myConsumption for user %d", iter, u)
                    .isEqualByComparingTo(expectedConsumption.get(u));
            sumConsumption = sumConsumption.add(cal.myConsumption());

            // receivable = max(net, 0)（应收口径，不含应付）。
            BigDecimal expectedReceivable = preNets.get(u).max(BigDecimal.ZERO);
            assertThat(cal.receivable())
                    .as("iter %d receivable for user %d", iter, u)
                    .isEqualByComparingTo(expectedReceivable);

            // Property 4（金额闭合，记账后）：初始 − 当前 = accountPaid = 其本人付款实付额之和。
            BigDecimal netOut = INITIAL_BALANCE.subtract(balance(accountIdByUser.get(u)));
            BigDecimal expectedPaid = selfPaidByUser.getOrDefault(u, BigDecimal.ZERO);
            assertThat(cal.accountPaid())
                    .as("iter %d Property4 accountPaid caliber for user %d", iter, u)
                    .isEqualByComparingTo(expectedPaid);
            assertThat(netOut)
                    .as("iter %d Property4 account closure (post-record) for user %d", iter, u)
                    .isEqualByComparingTo(cal.accountPaid());
        }
        // Property 5 闭合：Σ 全体消费 = Σ 全部支出总额（每一分钱恰被参与人分摊一次）。
        assertThat(sumConsumption)
                .as("iter %d Property5 total consumption closes to total spend", iter)
                .isEqualByComparingTo(totalOfAllExpenses);

        // 6) Property 2（净额闭合，记账后）：Σ net = 0。
        assertThat(sum(preNets.values()))
                .as("iter %d Property2 net closure (post-record)", iter)
                .isEqualByComparingTo("0.00");

        // 7) Property 3（清算可清零）：执行建议转账（债务方作为付款方逐条结清），笔数 ≤ n−1。
        List<AaSettlementResponse.SuggestedTransfer> suggestions =
                settle.settlement(owner, ledger.getId()).suggestedTransfers();
        assertThat(suggestions.size())
                .as("iter %d Property3 transfer count ≤ n−1", iter)
                .isLessThanOrEqualTo(n - 1);
        for (AaSettlementResponse.SuggestedTransfer t : suggestions) {
            long debtor = t.fromUserId();
            long creditor = t.toUserId();
            settle.settle(debtor, ledger.getId(), creditor, null, t.amount(),
                    accountIdByUser.get(debtor));
        }

        // 执行后：全部结清 + Σ net = 0（Property 2 结清后仍成立）。
        AaSettlementResponse settled = settle.settlement(owner, ledger.getId());
        assertThat(settled.allSettled())
                .as("iter %d Property3 allSettled after executing suggestions", iter)
                .isTrue();
        Map<Long, BigDecimal> postNets = netByUser(settled);
        assertThat(postNets.values())
                .as("iter %d Property2/3 all nets zero after settle", iter)
                .allSatisfy(v -> assertThat(v).isEqualByComparingTo("0.00"));

        // 8) Property 4（金额闭合，结清后）：初始 − 当前 = 结清后的 accountPaid（含结算现金流）。
        for (long u : users) {
            AaOverviewResponse.Calibers cal = overview.overview(u, ledger.getId()).calibers();
            BigDecimal netOut = INITIAL_BALANCE.subtract(balance(accountIdByUser.get(u)));
            assertThat(netOut)
                    .as("iter %d Property4 account closure (post-settle) for user %d", iter, u)
                    .isEqualByComparingTo(cal.accountPaid());
        }
    }

    // ===================================================================================
    // 确定性示例：金额闭合逐分核对（Property 1 / 2 / 4 / 5）
    // ===================================================================================

    /**
     * 3 人、本人付 + 他人代记 + 均分 + 自定义混合，逐分核对分摊守恒、消费口径、净额闭合与账户闭合。
     */
    @Test
    void example_threeMembers_exactAmountClosure() {
        AaExpenseService expenses = expenseService();
        AaSettlementService settle = settlementService();
        AaLedgerService overview = ledgerService(settle);

        long alice = 900_001L;
        long bob = 900_002L;
        long carol = 900_003L;
        Ledger l = aaLedger(alice);
        member(l.getId(), alice, LedgerMember.ROLE_OWNER);
        member(l.getId(), bob, LedgerMember.ROLE_EDITOR);
        member(l.getId(), carol, LedgerMember.ROLE_EDITOR);
        long aliceAcc = account(alice, INITIAL_BALANCE).getId();
        long bobAcc = account(bob, INITIAL_BALANCE).getId();
        long carolAcc = account(carol, INITIAL_BALANCE).getId();
        Category cat = category(l.getId());
        List<Long> all = List.of(alice, bob, carol);

        // E1 本人付 + 均分：Alice 付 90（各 30）→ Alice 账户 −90。
        expenses.create(alice, l.getId(), new BigDecimal("90.00"), cat.getId(), alice, aliceAcc,
                null, null, AaExpenseService.SPLIT_EVEN, all, null);
        // E2 本人付 + 自定义：Bob 付 120（A20/B40/C60）→ Bob 账户 −120。
        expenses.create(bob, l.getId(), new BigDecimal("120.00"), cat.getId(), bob, bobAcc,
                null, null, AaExpenseService.SPLIT_CUSTOM, all, Map.of(
                        alice, new BigDecimal("20.00"),
                        bob, new BigDecimal("40.00"),
                        carol, new BigDecimal("60.00")));
        // E3 他人代记 + 均分：Alice 代记付款人=Carol 30（各 10）→ 不触任何账户。
        expenses.create(alice, l.getId(), new BigDecimal("30.00"), cat.getId(), carol, null,
                null, null, AaExpenseService.SPLIT_EVEN, all, null);

        // Property 4（记账后）：Alice 99910、Bob 99880、Carol 100000（代记不触账户）。
        assertThat(balance(aliceAcc)).isEqualByComparingTo("99910.00");
        assertThat(balance(bobAcc)).isEqualByComparingTo("99880.00");
        assertThat(balance(carolAcc)).isEqualByComparingTo("100000.00");

        // Property 5：myConsumption = 自身分摊之和。Alice 30+20+10=60；Bob 30+40+10=80；Carol 30+60+10=100。
        assertThat(overview.overview(alice, l.getId()).calibers().myConsumption())
                .isEqualByComparingTo("60.00");
        assertThat(overview.overview(bob, l.getId()).calibers().myConsumption())
                .isEqualByComparingTo("80.00");
        assertThat(overview.overview(carol, l.getId()).calibers().myConsumption())
                .isEqualByComparingTo("100.00");
        // Σ 消费 = 90+120+30 = 240（Property 5 闭合）。
        assertThat(overview.overview(alice, l.getId()).calibers().myConsumption()
                .add(overview.overview(bob, l.getId()).calibers().myConsumption())
                .add(overview.overview(carol, l.getId()).calibers().myConsumption()))
                .isEqualByComparingTo("240.00");

        // accountPaid（记账后）：Alice 90、Bob 120、Carol 0（代记不计入账户）。
        assertThat(overview.overview(alice, l.getId()).calibers().accountPaid())
                .isEqualByComparingTo("90.00");
        assertThat(overview.overview(carol, l.getId()).calibers().accountPaid())
                .isEqualByComparingTo("0.00");

        // Property 2：净额 Alice=90−60=+30、Bob=120−80=+40、Carol=0−100=−70，Σ=0。
        Map<Long, BigDecimal> nets = netByUser(settle.settlement(alice, l.getId()));
        assertThat(nets.get(alice)).isEqualByComparingTo("30.00");
        assertThat(nets.get(bob)).isEqualByComparingTo("40.00");
        assertThat(nets.get(carol)).isEqualByComparingTo("-70.00");
        assertThat(sum(nets.values())).isEqualByComparingTo("0.00");

        // Property 3：Carol 作为付款方逐条结清 → 全部结清、账户闭合仍成立。
        for (AaSettlementResponse.SuggestedTransfer t : settle.settlement(alice, l.getId())
                .suggestedTransfers()) {
            settle.settle(t.fromUserId(), l.getId(), t.toUserId(), null, t.amount(), carolAcc);
        }
        assertThat(settle.settlement(alice, l.getId()).allSettled()).isTrue();
        // Carol 结清付出 70：账户 100000 − 70 = 99930；accountPaid = 70。
        assertThat(balance(carolAcc)).isEqualByComparingTo("99930.00");
        assertThat(overview.overview(carol, l.getId()).calibers().accountPaid())
                .isEqualByComparingTo("70.00");
        assertThat(INITIAL_BALANCE.subtract(balance(carolAcc)))
                .isEqualByComparingTo(overview.overview(carol, l.getId()).calibers().accountPaid());
    }

    // ===================================================================================
    // Property 6（特性隔离）：AA 账本不进入「全部账本」聚合
    // ===================================================================================

    @Test
    void property6_aaLedgerExcludedFromAggregate_personalAndCollaborativeIncluded() {
        AaExpenseService expenses = expenseService();
        AaSettlementService settle = settlementService();
        AggregateService aggregate = aggregateService();

        long user = 950_001L;
        long other = 950_002L;

        // 个人 + 家庭（协作）账本各一笔普通支出（应进入「全部」聚合）。
        Ledger personal = plainLedger(user, Ledger.TYPE_PERSONAL, "个人");
        Ledger family = plainLedger(user, Ledger.TYPE_COLLABORATIVE, "家庭");
        member(personal.getId(), user, LedgerMember.ROLE_OWNER);
        member(family.getId(), user, LedgerMember.ROLE_OWNER);
        Category personalCat = category(personal.getId());
        plainExpense(personal.getId(), user, "100.00", dt("2025-06-05T12:00:00"), personalCat.getId());
        plainExpense(family.getId(), user, "200.00", dt("2025-06-06T12:00:00"), category(family.getId()).getId());

        // AA 账本：真实记账 + 结算（其支出 / 结算流水 / 分类均不得进入聚合）。
        Ledger aa = aaLedger(user);
        member(aa.getId(), user, LedgerMember.ROLE_OWNER);
        member(aa.getId(), other, LedgerMember.ROLE_EDITOR);
        long userAcc = account(user, INITIAL_BALANCE).getId();
        long otherAcc = account(other, INITIAL_BALANCE).getId();
        Category aaCat = category(aa.getId());
        // user 付 100，两人均分（各 50）→ other 欠 user 50。
        expenses.create(user, aa.getId(), new BigDecimal("100.00"), aaCat.getId(), user, userAcc,
                dt("2025-06-07T12:00:00"), null, AaExpenseService.SPLIT_EVEN,
                List.of(user, other), null);
        // other 作为付款方结清 50 → 生成一条 aa_settlement 展示流水。
        for (AaSettlementResponse.SuggestedTransfer t : settle.settlement(user, aa.getId())
                .suggestedTransfers()) {
            settle.settle(t.fromUserId(), aa.getId(), t.toUserId(), null, t.amount(), otherAcc);
        }

        // 「全部账本」交易聚合：仅个人 + 家庭两笔，AA 支出与结算流水整本被排除。
        List<Transaction> all = aggregate.allTransactionsInMonth(user, YearMonth.of(2025, 6));
        assertThat(all).hasSize(2);
        assertThat(all).extracting(Transaction::getLedgerId)
                .containsExactlyInAnyOrder(personal.getId(), family.getId());
        assertThat(all).noneMatch(t -> t.getLedgerId().equals(aa.getId()));
        assertThat(all).extracting(Transaction::getType)
                .doesNotContain(TransactionType.AA_EXPENSE, TransactionType.AA_SETTLEMENT);

        // 「全部账本」分类聚合：包含个人分类，排除 AA 分类。
        List<Category> cats = aggregate.allCategories(user);
        assertThat(cats).extracting(Category::getId).contains(personalCat.getId());
        assertThat(cats).extracting(Category::getId).doesNotContain(aaCat.getId());
    }

    // ---------------------------------- helpers ----------------------------------

    /** 从库独立汇总每人分摊之和（Property 5 的期望值，不经被测 overview 服务）。 */
    private Map<Long, BigDecimal> consumptionFromDb(List<Long> expenseIds, List<Long> users) {
        Map<Long, BigDecimal> out = new HashMap<>();
        for (long u : users) {
            out.put(u, BigDecimal.ZERO);
        }
        for (Long txId : expenseIds) {
            for (TransactionSplit s : splitRepository.findByTransactionId(txId)) {
                out.merge(s.getParticipantUserId(), s.getShareAmount(), BigDecimal::add);
            }
        }
        // 归一到 2 位小数，便于 isEqualByComparingTo 语义清晰。
        out.replaceAll((k, v) -> v.setScale(2));
        return out;
    }

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

    private static BigDecimal sum(java.util.Collection<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<Long, BigDecimal> netByUser(AaSettlementResponse resp) {
        return resp.nets().stream()
                .collect(Collectors.toMap(AaSettlementResponse.MemberNet::userId,
                        AaSettlementResponse.MemberNet::net));
    }

    private static LocalDateTime dt(String iso) {
        return LocalDateTime.parse(iso);
    }

    private Ledger aaLedger(long ownerId) {
        return plainLedger(ownerId, Ledger.TYPE_AA, "旅行 AA");
    }

    private Ledger plainLedger(long ownerId, String type, String name) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Ledger l = new Ledger();
        l.setUserId(ownerId);
        l.setName(name);
        l.setType(type);
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

    /** 普通（个人 / 家庭）账本的一笔支出交易，用于验证聚合纳入。 */
    private void plainExpense(long ledgerId, long userId, String amount, LocalDateTime when,
            long categoryId) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Transaction t = new Transaction();
        t.setLedgerId(ledgerId);
        t.setCreatedBy(userId);
        t.setType(TransactionType.EXPENSE);
        t.setAmount(new BigDecimal(amount));
        t.setCategoryId(categoryId);
        t.setOccurredAt(when);
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        transactionRepository.save(t);
    }
}
