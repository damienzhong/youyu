package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.service.AssetsCashflowService.CashflowResult;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

/**
 * {@link AssetsCashflowService#cashflow} 聚合的属性测试
 * （assets-monthly-cashflow 设计文档 Property 6–9，任务 2.2）。
 *
 * <h2>测试层级与替身策略</h2>
 * <p>被测的是<b>聚合编排</b>：本人账户 id 集合 → 半开区间月界 → 按账户过滤取当月未软删交易 →
 * 逐笔归类累加 → 今日子集。逐笔归类口径本身（含 AA 实付、结算方向、转账排除）已由
 * {@link CashflowClassifierPropertyTest} 覆盖，本类聚焦编排层的四条性质：时区月界、今日子集、空集归零、
 * 仅本人账户。</p>
 *
 * <p>不引入 Spring 上下文、不落库：以 {@code Clock.fixed(Asia/Shanghai)} 注入确定「当前月/今日」，
 * 用 Mockito 桩 {@link AccountRepository}（返回本人账户集合）与 {@link TransactionRepository}
 * （用 {@code thenAnswer} <b>真实复刻仓库查询语义</b>：{@code account_id ∈ 传入 id 集合} 且
 * {@code occurred_at ∈ [from, to)} 且 {@code deleted_at IS NULL}，后者复刻实体
 * {@code @SQLRestriction("deleted_at is null")}）。桩用的 {@code from/to} 与 id 集合<b>取自服务实际传入的
 * 实参</b>，因此若服务算错月界、或没按本人账户 id 集合查询，桩返回的行集就会与下面<b>独立</b>写的参考
 * 聚合分叉，属性即失败。</p>
 *
 * <h2>期望值的独立计算</h2>
 * <p>{@link #reference} 不复用 {@link CashflowClassifier}，按需求 1 口径另写一遍归类 + 半开区间过滤 +
 * 今日子集，作为服务结果的独立参照。金额比较一律 {@code isEqualByComparingTo}，忽略标度差异。</p>
 */
class AssetsCashflowServicePropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 当前用户 id（唯一数据归属依据）。 */
    private static final long USER_ID = 7L;

    /** 本人拥有账户 id 池。 */
    private static final List<Long> OWN_ACCOUNT_IDS = List.of(10L, 11L, 12L);

    /** 他人账户 id 池（与本人账户不相交，用于验证仅本人账户参与聚合）。 */
    private static final List<Long> OTHER_ACCOUNT_IDS = List.of(90L, 91L);

    /** 用户 id 小基数池（payer/creator，覆盖 AA 结算付出/收款两个方向）。 */
    private static final List<Long> USER_IDS = List.of(USER_ID, 8L);

    /** 金额边界池（恒为正，{@code DECIMAL(18,2)} 语义）。 */
    private static final List<BigDecimal> AMOUNTS = List.of(
            new BigDecimal("0.01"),
            new BigDecimal("1.00"),
            new BigDecimal("12.34"),
            new BigDecimal("100.00"),
            new BigDecimal("9999.99"),
            new BigDecimal("1234567.89"));

    /** 计入现金流的交易类型（非 transfer）。 */
    private static final List<TransactionType> COUNTED_TYPES = List.of(
            TransactionType.EXPENSE,
            TransactionType.INCOME,
            TransactionType.AA_EXPENSE,
            TransactionType.AA_SETTLEMENT);

    /** 交易 id 分配器，保证构造的交易 id 互不相同。 */
    private static final AtomicLong TX_ID = new AtomicLong(1);

    // ---------------- 轻量交易投影 ----------------

    /** 单笔交易投影：仅保留聚合口径关心的维度。 */
    private record TxSpec(
            TransactionType type,
            BigDecimal amount,
            Long accountId,
            Long payerUserId,
            Long createdBy,
            LocalDateTime occurredAt,
            boolean deleted) {
    }

    // ---------------- 被测服务装配（固定 Clock + 桩 repository）----------------

    /**
     * 用固定 {@link Clock} 与桩 repository 组装 {@link AssetsCashflowService}。
     *
     * @param clock          注入的确定时钟（{@code Asia/Shanghai}）
     * @param ownAccountIds  本人拥有账户 id 集合（{@code accountRepository.findByUserId...} 返回）
     * @param allTxns        全体交易（桩按「account_id∈实参集合 且 occurredAt∈[from,to) 且 未软删」过滤后返回）
     */
    private AssetsCashflowService buildService(Clock clock, Set<Long> ownAccountIds, List<TxSpec> allTxns) {
        AccountRepository accountRepository = mock(AccountRepository.class);
        List<Account> accounts = ownAccountIds.stream()
                .map(id -> account(id, USER_ID))
                .collect(Collectors.toList());
        when(accountRepository.findByUserIdOrderBySortOrderAscIdAsc(USER_ID)).thenReturn(accounts);

        TransactionRepository transactionRepository = mock(TransactionRepository.class);
        when(transactionRepository
                .findByAccountIdInAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(any(), any(), any()))
                .thenAnswer(invocation -> {
                    Collection<Long> ids = invocation.getArgument(0);
                    LocalDateTime from = invocation.getArgument(1);
                    LocalDateTime to = invocation.getArgument(2);
                    List<Transaction> rows = new ArrayList<>();
                    for (TxSpec spec : allTxns) {
                        // 复刻 @SQLRestriction：软删除行不进入常规查询结果。
                        if (spec.deleted()) {
                            continue;
                        }
                        // 复刻 account_id IN (:accountIds)。
                        if (spec.accountId() == null || !ids.contains(spec.accountId())) {
                            continue;
                        }
                        // 复刻半开区间 occurred_at ∈ [from, to)。
                        if (spec.occurredAt().isBefore(from) || !spec.occurredAt().isBefore(to)) {
                            continue;
                        }
                        rows.add(toEntity(spec));
                    }
                    return rows;
                });

        return new AssetsCashflowService(clock, accountRepository, transactionRepository);
    }

    private static Account account(Long id, Long userId) {
        Account a = new Account();
        a.setId(id);
        a.setUserId(userId);
        return a;
    }

    private static Transaction toEntity(TxSpec spec) {
        Transaction t = new Transaction();
        t.setId(TX_ID.getAndIncrement());
        t.setType(spec.type());
        t.setAmount(spec.amount());
        t.setAccountId(spec.accountId());
        t.setPayerUserId(spec.payerUserId());
        t.setCreatedBy(spec.createdBy());
        t.setOccurredAt(spec.occurredAt());
        t.setDeletedAt(spec.deleted() ? spec.occurredAt() : null);
        return t;
    }

    // ---------------- 独立参考聚合（不复用 CashflowClassifier）----------------

    /**
     * 按需求 1 口径独立复算某月现金流：过滤（本人账户、半开区间、未软删）→ 归类累加 → 今日子集。
     *
     * @return {@code [outflow, inflow, netInflow, todayOutflow, todayInflow]}，均已 {@code setScale(2)}
     */
    private static BigDecimal[] reference(
            List<TxSpec> allTxns, Set<Long> ownAccountIds, YearMonth month, Clock clock) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        boolean isCurrentMonth = month.equals(YearMonth.now(clock));
        LocalDate today = LocalDate.now(clock);

        BigDecimal outflow = BigDecimal.ZERO;
        BigDecimal inflow = BigDecimal.ZERO;
        BigDecimal todayOutflow = BigDecimal.ZERO;
        BigDecimal todayInflow = BigDecimal.ZERO;

        for (TxSpec spec : allTxns) {
            if (spec.deleted()) {
                continue;
            }
            if (spec.accountId() == null || !ownAccountIds.contains(spec.accountId())) {
                continue;
            }
            if (spec.occurredAt().isBefore(from) || !spec.occurredAt().isBefore(to)) {
                continue;
            }
            BigDecimal txOut = BigDecimal.ZERO;
            BigDecimal txIn = BigDecimal.ZERO;
            switch (spec.type()) {
                case EXPENSE, AA_EXPENSE -> txOut = spec.amount();
                case INCOME -> txIn = spec.amount();
                case AA_SETTLEMENT -> {
                    if (Objects.equals(spec.payerUserId(), spec.createdBy())) {
                        txOut = spec.amount();
                    } else {
                        txIn = spec.amount();
                    }
                }
                case TRANSFER -> {
                    // 转账不计入任何一侧。
                }
            }
            outflow = outflow.add(txOut);
            inflow = inflow.add(txIn);
            if (isCurrentMonth && spec.occurredAt().toLocalDate().isEqual(today)) {
                todayOutflow = todayOutflow.add(txOut);
                todayInflow = todayInflow.add(txIn);
            }
        }
        return new BigDecimal[] {
                scale(outflow), scale(inflow), scale(inflow.subtract(outflow)),
                scale(todayOutflow), scale(todayInflow)};
    }

    private static BigDecimal scale(BigDecimal v) {
        return v.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static void assertMatchesReference(CashflowResult actual, BigDecimal[] expected) {
        assertThat(actual.outflow()).as("月实际流出").isEqualByComparingTo(expected[0]);
        assertThat(actual.inflow()).as("月实际流入").isEqualByComparingTo(expected[1]);
        assertThat(actual.netInflow()).as("净流入").isEqualByComparingTo(expected[2]);
        assertThat(actual.todayOutflow()).as("今日实际流出").isEqualByComparingTo(expected[3]);
        assertThat(actual.todayInflow()).as("今日实际流入").isEqualByComparingTo(expected[4]);
    }

    // ---------------- 通用生成器 ----------------

    private Arbitrary<BigDecimal> amounts() {
        return Arbitraries.of(AMOUNTS);
    }

    private Arbitrary<Long> userIds() {
        return Arbitraries.of(USER_IDS);
    }

    private Arbitrary<TransactionType> countedType() {
        return Arbitraries.of(COUNTED_TYPES);
    }

    // ==================================================================================
    // Property 6: 时区月界
    // ==================================================================================

    /** 月界场景：一个选定月 + 一批 occurredAt 聚焦在该月半开区间边界内外的本人账户计入交易。 */
    private record MonthBoundaryScenario(YearMonth month, List<TxSpec> txns) {
    }

    /** 相对某月半开区间 {@code [from, to)} 的边界时刻取值，覆盖恰好命中与刚好越界。 */
    private static List<LocalDateTime> boundaryInstants(YearMonth month) {
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();
        return List.of(
                from,                       // 当月 1 日 00:00 —— 半开区间左端点，计入
                from.minusNanos(1),         // 左端点前 1 纳秒 —— 不计入
                from.minusSeconds(1),       // 上月末尾 —— 不计入
                from.plusDays(15),          // 月中 —— 计入
                to.minusNanos(1),           // 次月 1 日 00:00 前 1 纳秒 —— 计入
                to.minusSeconds(1),         // 当月末尾 —— 计入
                to,                         // 次月 1 日 00:00 —— 半开区间右端点，不计入
                to.plusSeconds(1),          // 次月初 —— 不计入
                from.minusMonths(2),        // 远早于本月 —— 不计入
                to.plusMonths(1));          // 远晚于本月 —— 不计入
    }

    private Arbitrary<YearMonth> months() {
        return Combinators.combine(
                Arbitraries.integers().between(2020, 2030),
                Arbitraries.integers().between(1, 12))
                .as(YearMonth::of);
    }

    @Provide
    Arbitrary<MonthBoundaryScenario> monthBoundaryScenarios() {
        return months().flatMap(month -> {
            Arbitrary<TxSpec> tx = Combinators.combine(
                    countedType(),
                    amounts(),
                    Arbitraries.of(OWN_ACCOUNT_IDS),
                    userIds(),
                    userIds(),
                    Arbitraries.of(boundaryInstants(month)))
                    .as((type, amount, accountId, payer, creator, occurredAt) ->
                            new TxSpec(type, amount, accountId, payer, creator, occurredAt, false));
            return tx.list().ofMinSize(1).ofMaxSize(30)
                    .map(txns -> new MonthBoundaryScenario(month, txns));
        });
    }

    // Feature: assets-monthly-cashflow, Property 6: 时区月界
    /**
     * 对任意交易，其是否计入某自然月，恰由 {@code occurredAt} 是否落在该月 {@code Asia/Shanghai} 半开区间
     * {@code [1 日 00:00, 次月 1 日 00:00)} 决定：服务的月度流出/流入等于对「落在半开区间内」的交易独立复算之和。
     * 由于桩仓库使用服务实际传入的 {@code from/to} 过滤，若服务算错月界（如闭区间或时区偏移），结果必与独立参考分叉。
     *
     * <p>Validates: Requirements 1.12</p>
     */
    @Property(tries = 200)
    void property6_monthBoundaryIsHalfOpenInterval(@ForAll("monthBoundaryScenarios") MonthBoundaryScenario s) {
        // 固定 Clock 落在与选定月无关的时刻，隔离「今日子集」，专测月界。
        Clock clock = Clock.fixed(Instant.parse("2099-01-15T05:00:00Z"), ZONE);
        Set<Long> ownIds = Set.copyOf(OWN_ACCOUNT_IDS);

        AssetsCashflowService service = buildService(clock, ownIds, s.txns());
        CashflowResult result = service.cashflow(USER_ID, s.month());

        assertMatchesReference(result, reference(s.txns(), ownIds, s.month(), clock));
    }

    // ==================================================================================
    // Property 7: 今日子集与选定月的关系
    // ==================================================================================

    /** 固定「今日」= 2025-06-15（Asia/Shanghai），当前月 = 2025-06。 */
    private static final LocalDate FIXED_TODAY = LocalDate.of(2025, 6, 15);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(FIXED_TODAY.atTime(13, 0).atZone(ZONE).toInstant(), ZONE);

    /** 今日子集场景：选定月相对当前月的偏移 + 一批落在选定月内的本人账户计入交易（部分落在今日）。 */
    private record TodaySubsetScenario(int monthOffset, List<TxSpec> txns) {
    }

    @Provide
    Arbitrary<TodaySubsetScenario> todaySubsetScenarios() {
        Arbitrary<Integer> offsets = Arbitraries.integers().between(-3, 3);
        return offsets.flatMap(offset -> {
            YearMonth currentMonth = YearMonth.now(FIXED_CLOCK);
            YearMonth month = currentMonth.plusMonths(offset);
            LocalDateTime from = month.atDay(1).atStartOfDay();
            int lengthDays = month.lengthOfMonth();
            // 选定月内的候选时刻：覆盖月内多天，且当 offset==0 时高频命中今日。
            List<LocalDateTime> withinMonth = new ArrayList<>();
            withinMonth.add(from);                                  // 月首
            withinMonth.add(from.plusDays(lengthDays - 1L).plusHours(23)); // 月末当天
            for (int day = 0; day < lengthDays; day += 5) {
                withinMonth.add(from.plusDays(day).plusHours(9));
            }
            if (offset == 0) {
                // 显式加入「今日」多个时刻，保证今日子集非空高频出现。
                withinMonth.add(FIXED_TODAY.atTime(0, 0));
                withinMonth.add(FIXED_TODAY.atTime(9, 30));
                withinMonth.add(FIXED_TODAY.atTime(23, 59, 59));
            }
            Arbitrary<TxSpec> tx = Combinators.combine(
                    countedType(),
                    amounts(),
                    Arbitraries.of(OWN_ACCOUNT_IDS),
                    userIds(),
                    userIds(),
                    Arbitraries.of(withinMonth))
                    .as((type, amount, accountId, payer, creator, occurredAt) ->
                            new TxSpec(type, amount, accountId, payer, creator, occurredAt, false));
            return tx.list().ofMinSize(0).ofMaxSize(30)
                    .map(txns -> new TodaySubsetScenario(offset, txns));
        });
    }

    // Feature: assets-monthly-cashflow, Property 7: 今日子集与选定月的关系
    /**
     * 对任意输入：选定月 ≠ 当前月 → 今日流出/流入恒为 {@code 0.00}（今日不落在该历史/未来月内）；
     * 选定月 == 当前月 → 今日两值等于该月中 {@code occurredAt} 落在今日（{@code Asia/Shanghai}）的交易同口径累加，
     * 且今日流出 ≤ 月流出、今日流入 ≤ 月流入。全程与独立参考一致。
     *
     * <p>Validates: Requirements 2.3, 2.4</p>
     */
    @Property(tries = 200)
    void property7_todaySubsetRelatesToSelectedMonth(@ForAll("todaySubsetScenarios") TodaySubsetScenario s) {
        Set<Long> ownIds = Set.copyOf(OWN_ACCOUNT_IDS);
        YearMonth month = YearMonth.now(FIXED_CLOCK).plusMonths(s.monthOffset());

        AssetsCashflowService service = buildService(FIXED_CLOCK, ownIds, s.txns());
        CashflowResult result = service.cashflow(USER_ID, month);

        assertMatchesReference(result, reference(s.txns(), ownIds, month, FIXED_CLOCK));

        if (s.monthOffset() != 0) {
            assertThat(result.todayOutflow())
                    .as("选定月非当前月时今日流出恒为 0.00").isEqualByComparingTo("0.00");
            assertThat(result.todayInflow())
                    .as("选定月非当前月时今日流入恒为 0.00").isEqualByComparingTo("0.00");
        } else {
            assertThat(result.todayOutflow())
                    .as("今日流出应 ≤ 月流出").isLessThanOrEqualTo(result.outflow());
            assertThat(result.todayInflow())
                    .as("今日流入应 ≤ 月流入").isLessThanOrEqualTo(result.inflow());
        }
    }

    // ==================================================================================
    // Property 8: 空集归零
    // ==================================================================================

    /** 空集场景：本人账户可空；交易均按构造保证「零计入」（他人账户/转账/软删/月外）。 */
    private record EmptyScenario(boolean hasOwnAccounts, List<TxSpec> txns) {
    }

    /** 生成一笔「保证不计入本月」的交易（四种非计入形态之一）。 */
    @Provide
    Arbitrary<TxSpec> nonContributingTx() {
        LocalDateTime withinMonth = YearMonth.of(2025, 6).atDay(10).atStartOfDay().plusHours(9);
        LocalDateTime outsideMonth = YearMonth.of(2024, 1).atDay(10).atStartOfDay();
        return Combinators.combine(
                Arbitraries.integers().between(0, 3),
                amounts(),
                countedType(),
                userIds(),
                userIds())
                .as((kind, amount, type, payer, creator) -> switch (kind) {
                    // 0: 本人账户上的转账 —— 类型不计入。
                    case 0 -> new TxSpec(TransactionType.TRANSFER, amount,
                            OWN_ACCOUNT_IDS.get(0), payer, creator, withinMonth, false);
                    // 1: 他人账户上的计入类型 —— 账户不属本人。
                    case 1 -> new TxSpec(type, amount,
                            OTHER_ACCOUNT_IDS.get(0), payer, creator, withinMonth, false);
                    // 2: 本人账户上、当月的计入类型，但已软删 —— 被 @SQLRestriction 排除。
                    case 2 -> new TxSpec(type, amount,
                            OWN_ACCOUNT_IDS.get(0), payer, creator, withinMonth, true);
                    // 3: 本人账户上的计入类型，但 occurredAt 落在选定月之外。
                    default -> new TxSpec(type, amount,
                            OWN_ACCOUNT_IDS.get(0), payer, creator, outsideMonth, false);
                });
    }

    @Provide
    Arbitrary<EmptyScenario> emptyScenarios() {
        Arbitrary<List<TxSpec>> txns = nonContributingTx().list().ofMinSize(0).ofMaxSize(30);
        return Combinators.combine(Arbitraries.of(true, false), txns)
                .as(EmptyScenario::new);
    }

    // Feature: assets-monthly-cashflow, Property 8: 空集归零
    /**
     * 对任意无计入交易的用户与月份（本人无账户，或交易全为他人账户/转账/软删/月外），
     * 五项（月流出/月流入/净流入/今日流出/今日流入）均为 {@code 0.00}。
     *
     * <p>Validates: Requirements 2.7</p>
     */
    @Property(tries = 200)
    void property8_emptyYieldsAllZero(@ForAll("emptyScenarios") EmptyScenario s) {
        // 选定月固定为 2025-06（与 nonContributingTx 的 withinMonth 一致），当前月同为 2025-06 以让今日子集也被检验。
        Set<Long> ownIds = s.hasOwnAccounts() ? Set.copyOf(OWN_ACCOUNT_IDS) : Set.of();
        YearMonth month = YearMonth.of(2025, 6);
        Clock clock = Clock.fixed(FIXED_TODAY.atTime(13, 0).atZone(ZONE).toInstant(), ZONE);

        AssetsCashflowService service = buildService(clock, ownIds, s.txns());
        CashflowResult result = service.cashflow(USER_ID, month);

        assertThat(result.outflow()).as("月流出应为 0.00").isEqualByComparingTo("0.00");
        assertThat(result.inflow()).as("月流入应为 0.00").isEqualByComparingTo("0.00");
        assertThat(result.netInflow()).as("净流入应为 0.00").isEqualByComparingTo("0.00");
        assertThat(result.todayOutflow()).as("今日流出应为 0.00").isEqualByComparingTo("0.00");
        assertThat(result.todayInflow()).as("今日流入应为 0.00").isEqualByComparingTo("0.00");
    }

    // ==================================================================================
    // Property 9: 仅本人账户
    // ==================================================================================

    /** 仅本人账户场景：本人账户计入交易（base）+ 他人账户交易（extra），后者不应改变结果。 */
    private record OwnershipScenario(List<TxSpec> ownTxns, List<TxSpec> otherTxns) {
    }

    private Arbitrary<TxSpec> ownTx() {
        LocalDateTime withinMonth = YearMonth.of(2025, 6).atDay(10).atStartOfDay().plusHours(9);
        return Combinators.combine(countedType(), amounts(), Arbitraries.of(OWN_ACCOUNT_IDS), userIds(), userIds())
                .as((type, amount, accountId, payer, creator) ->
                        new TxSpec(type, amount, accountId, payer, creator, withinMonth, false));
    }

    private Arbitrary<TxSpec> otherAccountTx() {
        LocalDateTime withinMonth = YearMonth.of(2025, 6).atDay(12).atStartOfDay().plusHours(9);
        // 他人账户上任意类型（含计入类型），均不应参与本人聚合。
        return Combinators.combine(
                Arbitraries.of(TransactionType.values()),
                amounts(),
                Arbitraries.of(OTHER_ACCOUNT_IDS),
                userIds(),
                userIds())
                .as((type, amount, accountId, payer, creator) ->
                        new TxSpec(type, amount, accountId, payer, creator, withinMonth, false));
    }

    @Provide
    Arbitrary<OwnershipScenario> ownershipScenarios() {
        return Combinators.combine(
                ownTx().list().ofMinSize(0).ofMaxSize(20),
                otherAccountTx().list().ofMinSize(0).ofMaxSize(20))
                .as(OwnershipScenario::new);
    }

    // Feature: assets-monthly-cashflow, Property 9: 仅本人账户
    /**
     * 对任意交易集合，只有 {@code account_id} 属于本人拥有账户的交易参与聚合；掺入任意他人账户交易，
     * 结果（月流出/流入/净流入/今日流出/流入）不变，等于仅本人账户交易的聚合，也等于独立参考。
     *
     * <p>Validates: Requirements 1.11, 3.4</p>
     */
    @Property(tries = 200)
    void property9_onlyOwnAccountsContribute(@ForAll("ownershipScenarios") OwnershipScenario s) {
        Set<Long> ownIds = Set.copyOf(OWN_ACCOUNT_IDS);
        YearMonth month = YearMonth.of(2025, 6);
        Clock clock = Clock.fixed(FIXED_TODAY.atTime(13, 0).atZone(ZONE).toInstant(), ZONE);

        // 仅本人账户交易的结果。
        CashflowResult baseResult =
                buildService(clock, ownIds, s.ownTxns()).cashflow(USER_ID, month);

        // 掺入他人账户交易后的结果。
        List<TxSpec> merged = new ArrayList<>(s.ownTxns());
        merged.addAll(s.otherTxns());
        CashflowResult mergedResult =
                buildService(clock, ownIds, merged).cashflow(USER_ID, month);

        // 掺入他人账户交易不改变任何一项。
        assertThat(mergedResult.outflow()).as("他人账户交易不影响月流出").isEqualByComparingTo(baseResult.outflow());
        assertThat(mergedResult.inflow()).as("他人账户交易不影响月流入").isEqualByComparingTo(baseResult.inflow());
        assertThat(mergedResult.netInflow()).as("他人账户交易不影响净流入").isEqualByComparingTo(baseResult.netInflow());
        assertThat(mergedResult.todayOutflow()).as("他人账户交易不影响今日流出").isEqualByComparingTo(baseResult.todayOutflow());
        assertThat(mergedResult.todayInflow()).as("他人账户交易不影响今日流入").isEqualByComparingTo(baseResult.todayInflow());

        // 且与独立参考（仅本人账户）一致。
        assertMatchesReference(mergedResult, reference(s.ownTxns(), ownIds, month, clock));
    }
}
