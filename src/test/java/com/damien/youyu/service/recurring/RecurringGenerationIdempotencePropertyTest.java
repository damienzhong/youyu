package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.EndCondition;
import com.damien.youyu.domain.Frequency;
import com.damien.youyu.domain.RecurringPendingItem;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.RuleStatus;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;
import com.damien.youyu.repository.TransactionRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * Feature: recurring-transactions, Property 4: 生成幂等（构造性 Σ）
 *
 * <p>{@link RecurringPendingItemService#lazyGenerate} 的属性测试，覆盖 design.md「Correctness Properties」
 * Property 4：</p>
 *
 * <p><em>对任意</em>规则与任意次数、任意交错的懒生成，每个 {@code (rule_id, occurrence_date)} 组合在
 * {@code recurring_pending_items} 表中的记录数<b>至多为 1</b>；重复 / 并发生成尝试<b>不新增第二条、
 * 不改动既有记录、不向查询等主路径抛出异常或返回错误</b>。</p>
 *
 * <h2>为何走全栈 {@code @SpringBootTest} + 真实提交、不用测试级事务</h2>
 * <p>懒生成把每条期次插入下沉到 {@link RecurringPendingItemGenerator#generate} 的
 * {@code REQUIRES_NEW} 独立事务——只有经真实 Spring 事务代理该注解才生效，且只有<b>真实提交</b>才能验证
 * 「一条撞唯一键 {@code uk_recurring_pending_rule_date} 的失败 flush 不毒化其余插入、且被服务层就地静默」
 * 这一构造性幂等（需求 3.3、3.4、9.3、9.4）。故与 {@link RecurringLazyGenerationTest} 同款：全栈上下文、
 * 不加测试级 {@code @Transactional}（那会在方法结束回滚并掩盖 REQUIRES_NEW 的真实提交），用独立命名的
 * 内存库避免污染其它切片测试，并在每次迭代前显式清库（{@link #injectAndReset()}）。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效：依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（Spring 上下文静态缓存复用，多次迭代只加载
 * 一次），同一钩子内随即硬清相关表，使各次迭代（真实提交、无回滚）互不串味。时钟用 {@code @Primary} 的
 * 固定 {@link Clock}（{@code Asia/Shanghai} 的 2025-06-15），使 {@code today} 与期次序列可确定性推导。</p>
 *
 * <h2>测试构造</h2>
 * <p>每次迭代随机生成 1–3 条 {@code ACTIVE} 规则（各类频率 / 开始日期 / 结束条件，日期范围有界），直接经
 * 仓库落库（绕过创建校验，聚焦生成本身）；随后执行一串<b>随机交错的动作</b>：既有多次
 * {@code service.lazyGenerate}（面向调用方的幂等路径），也穿插经
 * {@link RecurringPendingItemGenerator#generate} 对某合法期次的<b>直接写入</b>（模拟并发 / 重复生成的竞争者，
 * 撞唯一键时该低层单元会抛 {@link DataIntegrityViolationException}，此处容忍）。动作跑完后再收敛一次懒生成取
 * 定点快照，随后再次重复懒生成并断言：</p>
 * <ul>
 *   <li><b>至多一条：</b>每个 {@code (ruleId, occurrenceDate)} 组合恰一行（需求 3.3、9.3）。</li>
 *   <li><b>幂等且不改动：</b>重复懒生成后行数不变、既有每行的全部字段逐一不变（需求 3.4、9.4）。</li>
 *   <li><b>不抛错：</b>{@code lazyGenerate} 不加 try/catch 直接调用，任何抛出都会使属性失败（需求 3.4、9.4）。</li>
 *   <li><b>集合正确：</b>最终生成集恰等于各规则「≥ 生成下界的已到期期次」并集（构造性 Σ 的完整性侧）。</li>
 * </ul>
 *
 * <p><strong>Validates: Requirements 3.3, 3.4, 9.3, 9.4</strong></p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-gen-idem-pbt;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringGenerationIdempotencePropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）→ today = 2025-06-15。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);
    private static final long ALICE = 1L;
    private static final long LEDGER = 100L;
    private static final long CATEGORY = 10L;
    private static final long ACCOUNT = 1L;

    private final OccurrenceCalculator calculator = new OccurrenceCalculator();

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZONE);
        }
    }

    @Autowired
    private RecurringPendingItemService service;
    @Autowired
    private RecurringPendingItemGenerator generator;
    @Autowired
    private RecurringRuleRepository ruleRepository;
    @Autowired
    private RecurringPendingItemRepository pendingItemRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private AccountRepository accountRepository;

    @BeforeTry
    void injectAndReset() throws Exception {
        // jqwik 不经 SpringExtension：手工注入 bean（上下文静态缓存，多次迭代只加载一次）。
        new TestContextManager(RecurringGenerationIdempotencePropertyTest.class)
                .prepareTestInstance(this);
        // 清理不靠回滚（REQUIRES_NEW 真实提交）：每次迭代前硬清相关表，使各迭代互不串味。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
    }

    // =====================================================================
    // Property 4
    // =====================================================================

    /**
     * Feature: recurring-transactions, Property 4: 生成幂等（构造性 Σ）
     *
     * <p><strong>Validates: Requirements 3.3, 3.4, 9.3, 9.4</strong></p>
     */
    @Property(tries = 100)
    void lazyGenerationIsIdempotentAndConstructivelyUnique(@ForAll("scenarios") Scenario scenario) {
        // 1) 落库随机规则集（全部 ACTIVE、归属同一账本），并计算各自的期望期次集。
        List<RecurringRule> rules = new ArrayList<>();
        Set<String> expectedKeys = new LinkedHashSet<>();
        for (RuleGen g : scenario.rules()) {
            RecurringRule rule = ruleRepository.save(toEntity(g));
            rules.add(rule);
            for (LocalDate d : expectedOccurrences(rule)) {
                expectedKeys.add(key(rule.getId(), d));
            }
        }

        // 2) 随机交错的动作：多次 lazyGenerate（幂等路径）穿插直接写入（模拟并发 / 重复生成竞争者）。
        for (Action action : scenario.actions()) {
            if (action.lazy()) {
                // 面向调用方：不得抛出（需求 3.4、9.4）——不包 try/catch，抛出即属性失败。
                service.lazyGenerate(LEDGER);
            } else {
                preseedDirectly(rules, action);
            }
        }

        // 3) 收敛一次，取定点快照。
        service.lazyGenerate(LEDGER);
        Map<Long, ItemSnap> snapshotAfterSettle = snapshotById();

        // 4) 再任意次重复懒生成（“任意次数、任意交错”的幂等验证）。
        service.lazyGenerate(LEDGER);
        service.lazyGenerate(LEDGER);
        Map<Long, ItemSnap> snapshotAfterRepeat = snapshotById();

        // ---- 断言 ----

        // (a) 每个 (ruleId, occurrenceDate) 至多一条（构造性唯一，需求 3.3、9.3）。
        Map<String, Long> countByKey = snapshotAfterRepeat.values().stream()
                .collect(Collectors.groupingBy(s -> key(s.ruleId(), s.occurrenceDate()),
                        Collectors.counting()));
        assertThat(countByKey.values())
                .as("每个 (ruleId, occurrenceDate) 组合至多一条记录")
                .allSatisfy(c -> assertThat(c).isEqualTo(1L));

        // (b) 重复懒生成幂等：行数不变、既有每行全部字段逐一不变（不新增第二条、不改动既有记录，需求 3.4、9.4）。
        assertThat(snapshotAfterRepeat)
                .as("重复懒生成不新增、不改动既有记录（快照逐字段相等）")
                .isEqualTo(snapshotAfterSettle);

        // (c) 生成集恰等于各规则「≥ 生成下界的已到期期次」并集（构造性 Σ 的完整性侧）。
        Set<String> generatedKeys = snapshotAfterRepeat.values().stream()
                .map(s -> key(s.ruleId(), s.occurrenceDate()))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        assertThat(generatedKeys)
                .as("最终生成集应恰等于期望期次集")
                .isEqualTo(expectedKeys);

        // (d) 生成期零触账：不建交易（需求 3.2 侧证，强化“生成尝试无第二副作用”）。
        assertThat(transactionRepository.count())
                .as("懒生成不得创建任何交易")
                .isZero();
    }

    /**
     * 直接经 {@link RecurringPendingItemGenerator#generate} 写入某规则的一个<b>合法</b>期次，模拟并发 / 重复
     * 生成的竞争者。撞唯一键 {@code uk_recurring_pending_rule_date} 时该低层单元抛
     * {@link DataIntegrityViolationException}（构造性保证第二条被拒，需求 9.4），此处容忍——因为面向调用方的
     * 幂等由 {@code lazyGenerate} 兜底，本动作只为逼出并发 / 重复写入路径。
     */
    private void preseedDirectly(List<RecurringRule> rules, Action action) {
        if (rules.isEmpty()) {
            return;
        }
        RecurringRule rule = rules.get(clampIndex(action.ruleFraction(), rules.size()));
        List<LocalDate> occs = expectedOccurrences(rule);
        if (occs.isEmpty()) {
            return;
        }
        LocalDate occ = occs.get(clampIndex(action.occFraction(), occs.size()));
        try {
            generator.generate(rule, occ);
        } catch (DataIntegrityViolationException duplicate) {
            // 并发 / 重复写入撞唯一键：构造性拒绝第二条，容忍（需求 9.4）。
        }
    }

    private static int clampIndex(double fraction, int size) {
        int idx = (int) (fraction * size);
        if (idx < 0) {
            return 0;
        }
        return Math.min(idx, size - 1);
    }

    /** 某规则「≥ 生成下界的已到期期次」升序序列（与懒生成的生成集口径一致）。 */
    private List<LocalDate> expectedOccurrences(RecurringRule rule) {
        RuleSpec spec = RecurringPendingItemService.toRuleSpec(rule);
        LocalDate lowerBound = RecurringPendingItemService.generationLowerBound(rule);
        List<LocalDate> occs = new ArrayList<>();
        for (LocalDate d : calculator.occurrencesUpTo(spec, TODAY)) {
            if (!d.isBefore(lowerBound)) {
                occs.add(d);
            }
        }
        return occs;
    }

    private Map<Long, ItemSnap> snapshotById() {
        Map<Long, ItemSnap> snap = new HashMap<>();
        for (RecurringPendingItem i : pendingItemRepository.findAll()) {
            snap.put(i.getId(), new ItemSnap(
                    i.getRuleId(),
                    i.getLedgerId(),
                    i.getOccurrenceDate(),
                    i.getStatus().name(),
                    i.getType(),
                    i.getAmount().stripTrailingZeros().toPlainString(),
                    i.getCategoryId(),
                    i.getAccountId(),
                    i.getNote(),
                    i.getConfirmedTransactionId(),
                    i.getCreatedAt(),
                    i.getUpdatedAt()));
        }
        return snap;
    }

    private static String key(Long ruleId, LocalDate date) {
        return ruleId + "@" + date;
    }

    private RecurringRule toEntity(RuleGen g) {
        LocalDateTime anchor = g.startDate().atStartOfDay();
        RecurringRule rule = new RecurringRule();
        rule.setUserId(ALICE);
        rule.setLedgerId(LEDGER);
        rule.setType("expense");
        rule.setAmount(new BigDecimal("3000.00"));
        rule.setCategoryId(CATEGORY);
        rule.setAccountId(ACCOUNT);
        rule.setNote("房租");
        rule.setFrequency(g.frequency());
        rule.setWeeklyDays(toCsv(g.weeklyDays()));
        rule.setMonthDay(g.monthDay());
        rule.setMonthEnd(g.monthEnd());
        rule.setYearMonth(g.yearMonth());
        rule.setYearDay(g.yearDay());
        rule.setStartDate(g.startDate());
        rule.setEndCondition(g.endCondition());
        rule.setUntilDate(g.untilDate());
        rule.setCountN(g.countN());
        rule.setStatus(RuleStatus.ACTIVE);
        // start=updated → 生成下界 = startDate（不回补语义在别处覆盖；本属性聚焦幂等）。
        rule.setCreatedAt(anchor);
        rule.setUpdatedAt(anchor);
        return rule;
    }

    private static String toCsv(Set<Integer> days) {
        if (days == null || days.isEmpty()) {
            return null;
        }
        return new TreeSet<>(days).stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    // =====================================================================
    // 生成器
    // =====================================================================

    /** 待确认项快照（值语义相等，用于逐字段幂等断言）。 */
    record ItemSnap(Long ruleId, Long ledgerId, LocalDate occurrenceDate, String status, String type,
            String amount, Long categoryId, Long accountId, String note, Long confirmedTransactionId,
            LocalDateTime createdAt, LocalDateTime updatedAt) {
    }

    /** 规则的频率配置与边界（金额 / 分类 / 账户等模板字段固定，不影响生成幂等）。 */
    record RuleGen(Frequency frequency, Set<Integer> weeklyDays, Integer monthDay, boolean monthEnd,
            Integer yearMonth, Integer yearDay, LocalDate startDate, EndCondition endCondition,
            LocalDate untilDate, Integer countN) {
    }

    /** 一个动作：{@code lazy=true} 为一次 {@code lazyGenerate}；否则为对某合法期次的直接写入。 */
    record Action(boolean lazy, double ruleFraction, double occFraction) {
    }

    record Scenario(List<RuleGen> rules, List<Action> actions) {
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<List<RuleGen>> rules = ruleGen().list().ofMinSize(1).ofMaxSize(3);
        Arbitrary<List<Action>> actions = actionGen().list().ofMinSize(0).ofMaxSize(6);
        return Combinators.combine(rules, actions).as(Scenario::new);
    }

    private Arbitrary<Action> actionGen() {
        Arbitrary<Boolean> lazy = Arbitraries.of(true, true, false); // 偏向 lazyGenerate，穿插直接写入
        Arbitrary<Double> frac1 = Arbitraries.doubles().between(0.0, 0.999).ofScale(3);
        Arbitrary<Double> frac2 = Arbitraries.doubles().between(0.0, 0.999).ofScale(3);
        return Combinators.combine(lazy, frac1, frac2).as(Action::new);
    }

    private Arbitrary<RuleGen> ruleGen() {
        return startDates().flatMap(start ->
                Combinators.combine(freqConfigs(), endConfigs(start))
                        .as((freq, end) -> new RuleGen(
                                freq.frequency(), freq.weeklyDays(), freq.monthDay(), freq.monthEnd(),
                                freq.yearMonth(), freq.yearDay(), start,
                                end.endCondition(), end.untilDate(), end.countN())));
    }

    /** 开始日期：2025-01-01 .. 2025-06-15（对 DAILY 亦将期次数收敛在 ≤166，保持有界快速）。 */
    private Arbitrary<LocalDate> startDates() {
        long min = LocalDate.of(2025, 1, 1).toEpochDay();
        long max = TODAY.toEpochDay();
        return Arbitraries.longs().between(min, max).map(LocalDate::ofEpochDay);
    }

    private record FreqConfig(Frequency frequency, Set<Integer> weeklyDays, Integer monthDay,
            boolean monthEnd, Integer yearMonth, Integer yearDay) {
    }

    private record EndConfig(EndCondition endCondition, LocalDate untilDate, Integer countN) {
    }

    /** 合法频率配置：DAILY / WEEKLY(非空 1–7) / MONTHLY(指定日) / MONTHLY(月末) / YEARLY(月+日)。 */
    private Arbitrary<FreqConfig> freqConfigs() {
        Arbitrary<FreqConfig> daily =
                Arbitraries.just(new FreqConfig(Frequency.DAILY, null, null, false, null, null));
        Arbitrary<FreqConfig> weekly = Arbitraries.integers().between(1, 7).set().ofMinSize(1).ofMaxSize(7)
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

    /** 合法结束条件：NEVER / UNTIL_DATE(≥ start) / COUNT(1–40，收敛期次数)。 */
    private Arbitrary<EndConfig> endConfigs(LocalDate start) {
        Arbitrary<EndConfig> never = Arbitraries.just(new EndConfig(EndCondition.NEVER, null, null));
        Arbitrary<EndConfig> until = Arbitraries.integers().between(0, 300)
                .map(n -> new EndConfig(EndCondition.UNTIL_DATE, start.plusDays(n), null));
        Arbitrary<EndConfig> count = Arbitraries.integers().between(1, 40)
                .map(n -> new EndConfig(EndCondition.COUNT, null, n));
        return Arbitraries.oneOf(never, until, count);
    }
}
