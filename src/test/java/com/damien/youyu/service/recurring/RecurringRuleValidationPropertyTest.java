package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.TestContextManager;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.EndCondition;
import com.damien.youyu.domain.Frequency;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.RuleStatus;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;
import com.damien.youyu.service.LedgerAccountResolver;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * Feature: recurring-transactions, Property 3: 模板字段与频率配置校验（零副作用）
 *
 * <p>{@link RecurringRuleService#create} 的属性测试，覆盖 design.md「Correctness Properties」Property 3：</p>
 *
 * <p><em>对任意</em>违反模板字段约束（类型非 expense/income、金额越界或小数位超 2、分类不属当前账本、
 * 账户不可用、备注超 200）或违反频率 / 结束条件约束（频率枚举外、{@code WEEKLY} 集合为空或含 1–7 之外值、
 * {@code MONTHLY} 缺日、{@code YEARLY} 缺月日、{@code UNTIL_DATE} 早于开始日期、{@code COUNT} 的 N 不在
 * 1–9999）的创建请求：系统都拒绝创建并返回指示对应无效字段的错误（{@link ApiException} 的 {@code code} /
 * {@code field} 精确匹配），且规则表零新增（{@code ruleRepository.count()} 增量为 0）。反之，任意满足全部
 * 约束的请求都成功创建、归属当前用户 / 当前账本，且初始状态为 {@link RuleStatus#ACTIVE}、表新增恰一行。</p>
 *
 * <h2>为什么必须走真实持久化（{@code @DataJpaTest} + H2）</h2>
 * <p>本属性的核心是「拒绝即<b>零副作用</b>」与「接受即<b>恰落一行且 ACTIVE</b>」——两者都直接断言
 * {@code recurring_rules} 表里的行数变化。分类归属（{@code categoryRepository.findByIdAndLedgerId}）与
 * 账户可用性（{@link LedgerAccountResolver#selectableAccounts}）也都是真实的库查询。把仓储换成替身，
 * 「越权分类被拒」「他人账户不可用」这类真正会咬人的回归就测不出来。故与 {@link RecurringRuleServiceTest}
 * 一致，走 {@code @DataJpaTest} + H2（表结构由实体生成，与生产 MySQL 同为裸 id、无外键），并手工构造
 * {@link RecurringRuleService}（固定 {@link Clock}，与既有单测同款）。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效：依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（Spring 上下文静态缓存复用，多次迭代只加载
 * 一次）；也没有测试事务回滚，各次迭代写入的行留在同一张表里。因此每次迭代都<b>各自新建独立的
 * 账本 / 分类 / 账户</b>，并以「调用前后 {@code count()} 的<em>增量</em>」（而非全表绝对值）断言副作用，
 * 从而与同库中其它测试类遗留的行、以及本类前序迭代累积的行完全解耦。</p>
 *
 * <p><strong>Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.6, 1.7, 1.8, 2.10</strong></p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RecurringRuleValidationPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    // 2025-06-15 12:30 (Asia/Shanghai) → 创建当日为 2025-06-15。
    private static final Instant T0 = Instant.parse("2025-06-15T04:30:00Z");
    private static final long ALICE = 1L;
    private static final long BOB = 2L;

    @Autowired
    private RecurringRuleRepository ruleRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AccountLedgerRepository accountLedgerRepository;
    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private RecurringPendingItemRepository pendingItemRepository;

    @BeforeTry
    void injectSpringBeans() throws Exception {
        new TestContextManager(RecurringRuleValidationPropertyTest.class).prepareTestInstance(this);
    }

    private RecurringRuleService service() {
        Clock clock = Clock.fixed(T0, ZONE);
        LedgerAccountResolver resolver =
                new LedgerAccountResolver(accountRepository, accountLedgerRepository);
        return new RecurringRuleService(ruleRepository, pendingItemRepository, categoryRepository,
                resolver, new RecurringTemplateValidator(), clock);
    }

    // =====================================================================
    // Property 3
    // =====================================================================

    /**
     * Feature: recurring-transactions, Property 3: 模板字段与频率配置校验（零副作用）
     *
     * <p>合法请求 → 创建成功、归属正确、初始 {@code ACTIVE}、表恰增一行；非法请求 → 抛
     * {@link ApiException} 且 {@code code} / {@code field} 与该违规项精确对应、表零新增。</p>
     *
     * <p><strong>Validates: Requirements 1.1, 1.2, 1.3, 1.4, 1.6, 1.7, 1.8, 2.10</strong></p>
     */
    @Property(tries = 100)
    void createValidatesTemplateAndFrequencyWithZeroSideEffectOnRejection(
            @ForAll("scenarios") Scenario scenario) {

        // 每次迭代独立的账本 / 分类 / 账户，互不串味（见类级 Javadoc）。
        Fixture f = freshFixture();
        Long categoryId = resolveCategory(scenario.categorySource(), f);
        Long accountId = resolveAccount(scenario.accountSource(), f);

        long countBefore = ruleRepository.count();

        if (scenario.valid()) {
            RecurringRule rule = invokeCreate(scenario, f.ledgerId(), categoryId, accountId);

            // 接受：恰落一行，归属当前用户 / 当前账本，初始状态 ACTIVE（需求 1.1）。
            assertThat(rule.getId()).as("合法创建应返回自增 id").isNotNull();
            assertThat(rule.getUserId()).isEqualTo(ALICE);
            assertThat(rule.getLedgerId()).isEqualTo(f.ledgerId());
            assertThat(rule.getStatus()).as("初始状态应为 ACTIVE").isEqualTo(RuleStatus.ACTIVE);
            assertThat(ruleRepository.count()).as("合法创建应使规则表恰增一行").isEqualTo(countBefore + 1);
            assertThat(ruleRepository.findById(rule.getId())).as("合法创建应可回读").isPresent();
        } else {
            ApiException ex = catchThrowableOfType(
                    () -> invokeCreate(scenario, f.ledgerId(), categoryId, accountId),
                    ApiException.class);

            // 拒绝：错误码 + 出错字段精确对应该违规项（需求 1.2–1.8、2.10）。
            assertThat(ex).as("非法请求 %s 应抛 ApiException", scenario.violation()).isNotNull();
            assertThat(ex.getCode())
                    .as("违规 %s 的错误码", scenario.violation())
                    .isEqualTo(scenario.expectedCode());
            assertThat(ex.getField())
                    .as("违规 %s 的出错字段", scenario.violation())
                    .isEqualTo(scenario.expectedField());
            // 零副作用：规则表行数不变（需求 1.4 尾句「不创建任何规则、不改动任何数据」）。
            assertThat(ruleRepository.count())
                    .as("非法请求应零副作用，规则表行数不变")
                    .isEqualTo(countBefore);
        }
    }

    private RecurringRule invokeCreate(Scenario s, Long ledgerId, Long categoryId, Long accountId) {
        return service().create(ALICE, ledgerId, s.type(), s.amount(), categoryId, accountId,
                s.note(), s.frequency(), s.weeklyDays(), s.monthDay(), s.monthEnd(), s.yearMonth(),
                s.yearDay(), s.startDate(), s.endCondition(), s.untilDate(), s.countN());
    }

    // =====================================================================
    // 分类 / 账户来源解析（合法 = 当前账本内；非法 = 越权他账本 / 他人 / 缺失）
    // =====================================================================

    private Long resolveCategory(FieldSource src, Fixture f) {
        return switch (src) {
            case MAIN -> f.categoryId();
            case FOREIGN -> f.foreignCategoryId();  // 属另一账本 → 不属当前账本
            case NULL -> null;
        };
    }

    private Long resolveAccount(FieldSource src, Fixture f) {
        return switch (src) {
            case MAIN -> f.accountId();
            case FOREIGN -> f.foreignAccountId();   // Bob 的账户，未参与当前账本 → 不可用
            case NULL -> null;
        };
    }

    // =====================================================================
    // Fixtures（每次迭代新建，id 自增互不冲突）
    // =====================================================================

    /** Alice 当前账本 + 该账本一个分类 + Alice 参与该账本的可用账户；另备越权分类与他人账户。 */
    private Fixture freshFixture() {
        Ledger ledger = ledger(ALICE);
        Category cat = category(ledger.getId());
        Account acc = account(ALICE);
        link(acc.getId(), ledger.getId(), true);

        // 越权分类：属另一（Bob 的）账本 → 不属当前账本。
        Ledger otherLedger = ledger(BOB);
        Category foreignCat = category(otherLedger.getId());
        // 他人账户：Bob 的账户，未参与当前账本 → 对 Alice 不可用。
        Account foreignAcc = account(BOB);

        return new Fixture(ledger.getId(), cat.getId(), acc.getId(),
                foreignCat.getId(), foreignAcc.getId());
    }

    private record Fixture(Long ledgerId, Long categoryId, Long accountId,
            Long foreignCategoryId, Long foreignAccountId) {
    }

    private Ledger ledger(long ownerId) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Ledger l = new Ledger();
        l.setUserId(ownerId);
        l.setName("个人");
        l.setType(Ledger.TYPE_PERSONAL);
        l.setSortOrder(0);
        l.setDefault(true);
        l.setCreatedAt(now);
        l.setUpdatedAt(now);
        return ledgerRepository.save(l);
    }

    private Category category(long ledgerId) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(CategoryKind.EXPENSE);
        c.setName("房租");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c);
    }

    private Account account(long userId) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        Account a = new Account();
        a.setUserId(userId);
        a.setName("现金");
        a.setType(AccountType.CASH);
        a.setInitialBalance(new BigDecimal("1000.00"));
        a.setCurrentBalance(new BigDecimal("1000.00"));
        a.setSortOrder(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return accountRepository.save(a);
    }

    private void link(long accountId, long ledgerId, boolean visibleToOthers) {
        LocalDateTime now = LocalDateTime.ofInstant(T0, ZONE);
        AccountLedger al = new AccountLedger();
        al.setAccountId(accountId);
        al.setLedgerId(ledgerId);
        al.setVisibleToOthers(visibleToOthers);
        al.setShowBalance(false);
        al.setCreatedAt(now);
        accountLedgerRepository.save(al);
    }

    // =====================================================================
    // 生成场景
    // =====================================================================

    /** 分类 / 账户 id 的来源。 */
    enum FieldSource { MAIN, FOREIGN, NULL }

    /** 违规类别（{@link #NONE} 表示完全合法）。 */
    enum Violation { NONE, TYPE, AMOUNT, NOTE, CATEGORY, ACCOUNT, FREQUENCY, END_CONDITION }

    /**
     * 一个创建请求场景：全部 {@code create} 入参 + 分类 / 账户来源 + 期望结果。分类 / 账户以来源枚举携带，
     * 运行时映射为该次迭代 fixture 的真实 id（生成期尚无真实 id）。恰注入至多一个违规项，从而错误码 /
     * 字段可精确断言（校验顺序 type→amount→note→category→account→frequency→endCondition，注入项即首个命中）。
     */
    record Scenario(String type, BigDecimal amount, String note, Frequency frequency,
            Set<Integer> weeklyDays, Integer monthDay, boolean monthEnd, Integer yearMonth,
            Integer yearDay, LocalDate startDate, EndCondition endCondition, LocalDate untilDate,
            Integer countN, FieldSource categorySource, FieldSource accountSource,
            Violation violation, boolean valid, String expectedCode, String expectedField) {
    }

    /** 一组合法的基础字段（各违规生成器在此之上覆盖单一字段）。 */
    private record ValidBase(String type, BigDecimal amount, String note, FreqConfig freq,
            LocalDate startDate, EndConfig end) {
    }

    private record FreqConfig(Frequency frequency, Set<Integer> weeklyDays, Integer monthDay,
            boolean monthEnd, Integer yearMonth, Integer yearDay) {
    }

    private record EndConfig(EndCondition endCondition, LocalDate untilDate, Integer countN) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        // 合法与各违规按权重混合；合法占足够比例以覆盖「接受」分支。
        return Arbitraries.frequency(
                Tuple.of(8, "valid"),
                Tuple.of(2, "type"),
                Tuple.of(3, "amount"),
                Tuple.of(1, "note"),
                Tuple.of(2, "category"),
                Tuple.of(2, "account"),
                Tuple.of(3, "frequency"),
                Tuple.of(2, "end")
        ).flatMap(kind -> switch (kind) {
            case "valid" -> validScenarios();
            case "type" -> typeViolations();
            case "amount" -> amountViolations();
            case "note" -> noteViolations();
            case "category" -> categoryViolations();
            case "account" -> accountViolations();
            case "frequency" -> frequencyViolations();
            default -> endConditionViolations();
        });
    }

    // ---- 合法基础字段生成 ----

    private Arbitrary<String> validTypes() {
        return Arbitraries.of("expense", "income");
    }

    /** 合法金额：0.01–999,999,999.99，恰 2 位小数（以「分」为单位构造，保证精确）。 */
    private Arbitrary<BigDecimal> validAmounts() {
        return Arbitraries.longs().between(1L, 99_999_999_999L)
                .map(cents -> BigDecimal.valueOf(cents, 2));
    }

    /** 合法备注：null 或长度 0–200。 */
    private Arbitrary<String> validNotes() {
        Arbitrary<String> text = Arbitraries.strings().ofMinLength(0).ofMaxLength(200);
        return Arbitraries.oneOf(Arbitraries.just((String) null), text);
    }

    /** 合法频率配置：DAILY / WEEKLY(非空 1–7) / MONTHLY(指定日) / MONTHLY(月末) / YEARLY(月+日)。 */
    private Arbitrary<FreqConfig> validFreqConfigs() {
        Arbitrary<FreqConfig> daily =
                Arbitraries.just(new FreqConfig(Frequency.DAILY, null, null, false, null, null));
        Arbitrary<FreqConfig> weekly = validWeekdaySets()
                .map(days -> new FreqConfig(Frequency.WEEKLY, days, null, false, null, null));
        Arbitrary<FreqConfig> monthlyDay = Arbitraries.integers().between(1, 31)
                .map(d -> new FreqConfig(Frequency.MONTHLY, null, d, false, null, null));
        Arbitrary<FreqConfig> monthlyEnd =
                Arbitraries.just(new FreqConfig(Frequency.MONTHLY, null, null, true, null, null));
        Arbitrary<FreqConfig> yearly = Combinators.combine(
                        Arbitraries.integers().between(1, 12),
                        Arbitraries.integers().between(1, 31))
                .as((m, d) -> new FreqConfig(Frequency.YEARLY, null, null, false, m, d));
        return Arbitraries.oneOf(daily, weekly, monthlyDay, monthlyEnd, yearly);
    }

    private Arbitrary<Set<Integer>> validWeekdaySets() {
        return Arbitraries.integers().between(1, 7).set().ofMinSize(1).ofMaxSize(7);
    }

    /** 合法开始日期：2024–2026。 */
    private Arbitrary<LocalDate> validStartDates() {
        long min = LocalDate.of(2024, 1, 1).toEpochDay();
        long max = LocalDate.of(2026, 12, 31).toEpochDay();
        return Arbitraries.longs().between(min, max).map(LocalDate::ofEpochDay);
    }

    /** 合法结束条件：NEVER / UNTIL_DATE(≥ start) / COUNT(1–9999)。 */
    private Arbitrary<EndConfig> validEndConfigs(LocalDate start) {
        Arbitrary<EndConfig> never = Arbitraries.just(new EndConfig(EndCondition.NEVER, null, null));
        Arbitrary<EndConfig> until = Arbitraries.integers().between(0, 2000)
                .map(n -> new EndConfig(EndCondition.UNTIL_DATE, start.plusDays(n), null));
        Arbitrary<EndConfig> count = Arbitraries.integers().between(1, 9999)
                .map(n -> new EndConfig(EndCondition.COUNT, null, n));
        return Arbitraries.oneOf(never, until, count);
    }

    private Arbitrary<ValidBase> validBases() {
        return validStartDates().flatMap(start ->
                Combinators.combine(validTypes(), validAmounts(), validNotes(),
                                validFreqConfigs(), validEndConfigs(start))
                        .as((type, amount, note, freq, end) ->
                                new ValidBase(type, amount, note, freq, start, end)));
    }

    private Scenario fromBase(ValidBase b, FieldSource cat, FieldSource acc, Violation v,
            boolean valid, String code, String field) {
        return new Scenario(b.type(), b.amount(), b.note(), b.freq().frequency(),
                b.freq().weeklyDays(), b.freq().monthDay(), b.freq().monthEnd(),
                b.freq().yearMonth(), b.freq().yearDay(), b.startDate(), b.end().endCondition(),
                b.end().untilDate(), b.end().countN(), cat, acc, v, valid, code, field);
    }

    // ---- 合法场景 ----

    private Arbitrary<Scenario> validScenarios() {
        return validBases().map(b -> fromBase(b, FieldSource.MAIN, FieldSource.MAIN,
                Violation.NONE, true, null, null));
    }

    // ---- 类型非法：RECURRING_RULE_INVALID / type ----

    private Arbitrary<Scenario> typeViolations() {
        Arbitrary<String> badTypes =
                Arbitraries.of("transfer", "TRANSFER", "Expense", "spend", "", "income ");
        Arbitrary<String> maybeNull =
                Arbitraries.oneOf(badTypes, Arbitraries.just((String) null));
        return Combinators.combine(validBases(), maybeNull).as((b, badType) -> {
            Scenario base = fromBase(b, FieldSource.MAIN, FieldSource.MAIN,
                    Violation.TYPE, false, "RECURRING_RULE_INVALID", "type");
            return withType(base, badType);
        });
    }

    // ---- 金额非法：AMOUNT_INVALID / amount ----

    private Arbitrary<Scenario> amountViolations() {
        Arbitrary<BigDecimal> tooSmall = Arbitraries.oneOf(
                Arbitraries.just(new BigDecimal("0.00")),
                Arbitraries.just(BigDecimal.ZERO),
                Arbitraries.longs().between(1L, 1000L).map(c -> BigDecimal.valueOf(-c, 2)));
        Arbitrary<BigDecimal> tooLarge = Arbitraries.longs().between(100_000_000_000L, 200_000_000_000L)
                .map(cents -> BigDecimal.valueOf(cents, 2)); // ≥ 1,000,000,000.00
        // 3 位小数且第 3 位非 0——否则如 0.040 可无损 setScale(2) 归一为合法的 0.04（不构成违规）。
        Arbitrary<BigDecimal> tooManyDecimals = Arbitraries.longs().between(1L, 999_999L)
                .filter(milli -> milli % 10 != 0)
                .map(milli -> BigDecimal.valueOf(milli, 3));
        Arbitrary<BigDecimal> nullAmount = Arbitraries.just((BigDecimal) null);
        Arbitrary<BigDecimal> badAmounts =
                Arbitraries.oneOf(tooSmall, tooLarge, tooManyDecimals, nullAmount);
        return Combinators.combine(validBases(), badAmounts).as((b, bad) -> {
            Scenario base = fromBase(b, FieldSource.MAIN, FieldSource.MAIN,
                    Violation.AMOUNT, false, "AMOUNT_INVALID", "amount");
            return withAmount(base, bad);
        });
    }

    // ---- 备注超长：NOTE_TOO_LONG / note ----

    private Arbitrary<Scenario> noteViolations() {
        Arbitrary<String> longNotes = Arbitraries.integers().between(201, 400)
                .map(len -> "x".repeat(len));
        return Combinators.combine(validBases(), longNotes).as((b, note) -> {
            Scenario base = fromBase(b, FieldSource.MAIN, FieldSource.MAIN,
                    Violation.NOTE, false, "NOTE_TOO_LONG", "note");
            return withNote(base, note);
        });
    }

    // ---- 分类不属当前账本：RECURRING_RULE_INVALID / categoryId ----

    private Arbitrary<Scenario> categoryViolations() {
        Arbitrary<FieldSource> src = Arbitraries.of(FieldSource.FOREIGN, FieldSource.NULL);
        return Combinators.combine(validBases(), src).as((b, s) ->
                fromBase(b, s, FieldSource.MAIN,
                        Violation.CATEGORY, false, "RECURRING_RULE_INVALID", "categoryId"));
    }

    // ---- 账户不可用：RECURRING_RULE_INVALID / accountId ----

    private Arbitrary<Scenario> accountViolations() {
        Arbitrary<FieldSource> src = Arbitraries.of(FieldSource.FOREIGN, FieldSource.NULL);
        return Combinators.combine(validBases(), src).as((b, s) ->
                fromBase(b, FieldSource.MAIN, s,
                        Violation.ACCOUNT, false, "RECURRING_RULE_INVALID", "accountId"));
    }

    // ---- 频率配置非法：RECURRING_FREQUENCY_INVALID / frequency ----

    private Arbitrary<Scenario> frequencyViolations() {
        // 每种非法频率给出一个覆盖单一字段的 FreqConfig。
        Arbitrary<FreqConfig> weeklyEmpty =
                Arbitraries.just(new FreqConfig(Frequency.WEEKLY, Set.of(), null, false, null, null));
        Arbitrary<FreqConfig> weeklyOutOfRange = Arbitraries.oneOf(
                Arbitraries.just(new FreqConfig(Frequency.WEEKLY, Set.of(0), null, false, null, null)),
                Arbitraries.just(new FreqConfig(Frequency.WEEKLY, Set.of(8), null, false, null, null)),
                Arbitraries.just(new FreqConfig(Frequency.WEEKLY, Set.of(1, 8), null, false, null, null)));
        Arbitrary<FreqConfig> monthlyMissingDay =
                Arbitraries.just(new FreqConfig(Frequency.MONTHLY, null, null, false, null, null));
        Arbitrary<FreqConfig> monthlyDayOutOfRange = Arbitraries.oneOf(
                Arbitraries.just(new FreqConfig(Frequency.MONTHLY, null, 0, false, null, null)),
                Arbitraries.just(new FreqConfig(Frequency.MONTHLY, null, 32, false, null, null)));
        Arbitrary<FreqConfig> yearlyMissing = Arbitraries.oneOf(
                Arbitraries.just(new FreqConfig(Frequency.YEARLY, null, null, false, null, 5)),
                Arbitraries.just(new FreqConfig(Frequency.YEARLY, null, null, false, 2, null)),
                Arbitraries.just(new FreqConfig(Frequency.YEARLY, null, null, false, null, null)));
        Arbitrary<FreqConfig> yearlyOutOfRange = Arbitraries.oneOf(
                Arbitraries.just(new FreqConfig(Frequency.YEARLY, null, null, false, 13, 5)),
                Arbitraries.just(new FreqConfig(Frequency.YEARLY, null, null, false, 2, 32)));
        Arbitrary<FreqConfig> nullFrequency =
                Arbitraries.just(new FreqConfig(null, null, null, false, null, null));

        Arbitrary<FreqConfig> badFreq = Arbitraries.oneOf(weeklyEmpty, weeklyOutOfRange,
                monthlyMissingDay, monthlyDayOutOfRange, yearlyMissing, yearlyOutOfRange,
                nullFrequency);

        return Combinators.combine(validBases(), badFreq).as((b, freq) -> {
            Scenario base = fromBase(b, FieldSource.MAIN, FieldSource.MAIN,
                    Violation.FREQUENCY, false, "RECURRING_FREQUENCY_INVALID", "frequency");
            return withFreq(base, freq);
        });
    }

    // ---- 结束条件非法：RECURRING_END_CONDITION_INVALID / endCondition ----

    private Arbitrary<Scenario> endConditionViolations() {
        return validBases().flatMap(b -> {
            // UNTIL_DATE 早于开始日期。
            Arbitrary<EndConfig> untilBeforeStart = Arbitraries.integers().between(1, 2000)
                    .map(n -> new EndConfig(EndCondition.UNTIL_DATE, b.startDate().minusDays(n), null));
            // UNTIL_DATE 缺结束日期。
            Arbitrary<EndConfig> untilNull =
                    Arbitraries.just(new EndConfig(EndCondition.UNTIL_DATE, null, null));
            // COUNT 越界（0 / 负 / >9999 / null）。
            Arbitrary<EndConfig> countBad = Arbitraries.oneOf(
                    Arbitraries.just(new EndConfig(EndCondition.COUNT, null, 0)),
                    Arbitraries.integers().between(1, 1000)
                            .map(n -> new EndConfig(EndCondition.COUNT, null, -n)),
                    Arbitraries.integers().between(10000, 20000)
                            .map(n -> new EndConfig(EndCondition.COUNT, null, n)),
                    Arbitraries.just(new EndConfig(EndCondition.COUNT, null, null)));
            Arbitrary<EndConfig> badEnd = Arbitraries.oneOf(untilBeforeStart, untilNull, countBad);
            return badEnd.map(end -> {
                Scenario base = fromBase(b, FieldSource.MAIN, FieldSource.MAIN,
                        Violation.END_CONDITION, false, "RECURRING_END_CONDITION_INVALID",
                        "endCondition");
                return withEnd(base, end);
            });
        });
    }

    // ---- 单字段覆盖辅助（保持其余字段合法，仅改一处触发目标违规） ----

    private static Scenario withType(Scenario s, String type) {
        return new Scenario(type, s.amount(), s.note(), s.frequency(), s.weeklyDays(), s.monthDay(),
                s.monthEnd(), s.yearMonth(), s.yearDay(), s.startDate(), s.endCondition(),
                s.untilDate(), s.countN(), s.categorySource(), s.accountSource(), s.violation(),
                s.valid(), s.expectedCode(), s.expectedField());
    }

    private static Scenario withAmount(Scenario s, BigDecimal amount) {
        return new Scenario(s.type(), amount, s.note(), s.frequency(), s.weeklyDays(), s.monthDay(),
                s.monthEnd(), s.yearMonth(), s.yearDay(), s.startDate(), s.endCondition(),
                s.untilDate(), s.countN(), s.categorySource(), s.accountSource(), s.violation(),
                s.valid(), s.expectedCode(), s.expectedField());
    }

    private static Scenario withNote(Scenario s, String note) {
        return new Scenario(s.type(), s.amount(), note, s.frequency(), s.weeklyDays(), s.monthDay(),
                s.monthEnd(), s.yearMonth(), s.yearDay(), s.startDate(), s.endCondition(),
                s.untilDate(), s.countN(), s.categorySource(), s.accountSource(), s.violation(),
                s.valid(), s.expectedCode(), s.expectedField());
    }

    private static Scenario withFreq(Scenario s, FreqConfig freq) {
        return new Scenario(s.type(), s.amount(), s.note(), freq.frequency(), freq.weeklyDays(),
                freq.monthDay(), freq.monthEnd(), freq.yearMonth(), freq.yearDay(), s.startDate(),
                s.endCondition(), s.untilDate(), s.countN(), s.categorySource(), s.accountSource(),
                s.violation(), s.valid(), s.expectedCode(), s.expectedField());
    }

    private static Scenario withEnd(Scenario s, EndConfig end) {
        return new Scenario(s.type(), s.amount(), s.note(), s.frequency(), s.weeklyDays(),
                s.monthDay(), s.monthEnd(), s.yearMonth(), s.yearDay(), s.startDate(),
                end.endCondition(), end.untilDate(), end.countN(), s.categorySource(),
                s.accountSource(), s.violation(), s.valid(), s.expectedCode(), s.expectedField());
    }
}
