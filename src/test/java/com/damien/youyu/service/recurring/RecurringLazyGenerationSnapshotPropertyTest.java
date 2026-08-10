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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.EndCondition;
import com.damien.youyu.domain.Frequency;
import com.damien.youyu.domain.PendingStatus;
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
 * Feature: recurring-transactions, Property 5: 懒生成补齐且生成期不触账，快照不可变
 *
 * <p>{@link RecurringPendingItemService#lazyGenerate} 的属性测试，覆盖 design.md「Correctness Properties」
 * Property 5：</p>
 *
 * <p><em>对任意</em>当前账本下的 {@link RuleStatus#ACTIVE} 规则集合与任意「今天」，一次懒生成后：
 * 每个「到期日 ≤ 今天、≥ 生成下界且表中尚无任何状态记录」的期次恰有一条 {@link PendingStatus#PENDING}，
 * 其模板快照字段（{@code type}/{@code amount}/{@code categoryId}/{@code accountId}/{@code note}）等于生成时
 * 规则的模板字段；生成过程不创建任何交易（{@code transactionRepository.count()} 恒为 0）、不改变任何账户余额；
 * {@link RuleStatus#PAUSED} 规则不产生任何新待确认项；随后编辑规则（改金额 / 备注）不改变已生成 {@code PENDING}
 * 项的快照字段。</p>
 *
 * <h2>为什么走全栈 {@code @SpringBootTest} + 真实提交、不用测试级事务</h2>
 * <p>与 {@link RecurringLazyGenerationTest} 同源：懒生成把每条期次的插入下沉到
 * {@link RecurringPendingItemGenerator#generate} 的 {@code REQUIRES_NEW} 独立事务——只有经<b>真实 Spring
 * 事务代理</b>该注解才生效，且账户余额守恒、快照取值等断言需要<b>真实提交</b>后回读。故本测试用全栈上下文、
 * 不加测试级 {@code @Transactional}（那会在方法结束回滚并掩盖 REQUIRES_NEW 的真实提交），清理改为每个
 * try 前显式清库（{@link #resetAndInject()}），并用独立命名的内存库避免污染其它切片测试。</p>
 *
 * <p>时钟用 {@code @Primary} 的固定 {@link Clock}（{@code Asia/Shanghai} 的 2025-06-15），使 {@code today}
 * 与期次序列可确定性断言；规则的多样性（频率 / 开始 / 结束条件 / 生成下界 / 模板字段）由生成器覆盖，
 * 从而在固定 today 之上遍历规则输入空间。规则直接经仓库落库（绕过 {@code RecurringRuleService} 的创建校验），
 * 聚焦懒生成本身的行为。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效：依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（Spring 上下文静态缓存复用，多次迭代只加载
 * 一次），同一 {@link BeforeTry} 内随即显式清库并重新播种账户，使各 try 互不串味。</p>
 *
 * <p><strong>Validates: Requirements 3.1, 3.2, 3.5, 3.7, 6.1, 6.3, 6.4</strong></p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-lazygen-snapshot-pbt;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringLazyGenerationSnapshotPropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）→ today = 2025-06-15。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);
    private static final long ALICE = 1L;
    private static final long LEDGER = 100L;
    private static final BigDecimal SEED_BALANCE = new BigDecimal("1234.56");

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
    private RecurringRuleRepository ruleRepository;
    @Autowired
    private RecurringPendingItemRepository pendingItemRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionRepository transactionRepository;

    /** 每 try 播种的账户 id，用于余额守恒断言。 */
    private Long seededAccountId;

    @BeforeTry
    void resetAndInject() throws Exception {
        // jqwik 不走 SpringExtension：手工触发依赖注入（上下文缓存复用）。
        new TestContextManager(RecurringLazyGenerationSnapshotPropertyTest.class).prepareTestInstance(this);
        // 清理不靠回滚（REQUIRES_NEW 真实提交）：每个 try 前硬清相关表。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        seededAccountId = seedAccount();
    }

    // =====================================================================
    // Property 5
    // =====================================================================

    /**
     * Feature: recurring-transactions, Property 5: 懒生成补齐且生成期不触账，快照不可变
     *
     * <p>一次懒生成后：ACTIVE 规则恰补齐「到期 ≤ today 且 ≥ 生成下界且原表无记录」的每个期次为唯一
     * {@code PENDING} 且快照 = 规则模板；生成零触账（无交易、余额不变）；PAUSED 规则零新增；随后编辑规则
     * 不改变已生成 {@code PENDING} 项的快照。</p>
     *
     * <p><strong>Validates: Requirements 3.1, 3.2, 3.5, 3.7, 6.1, 6.3, 6.4</strong></p>
     */
    @Property(tries = 100)
    void lazyGenerateBackfillsWithoutTouchingAccountsAndSnapshotsAreImmutable(
            @ForAll("scenarios") Scenario scenario) {

        // 落库规则（绕过创建校验，聚焦懒生成），记住每条规则的期望快照与状态。
        List<RecurringRule> saved = new ArrayList<>();
        for (RuleDef def : scenario.rules()) {
            saved.add(saveRule(def));
        }

        // 独立用纯算法算出每条 ACTIVE 规则应补齐的期次（生成下界过滤），作为「期望」对照。
        Map<Long, Set<LocalDate>> expectedActive = new HashMap<>();
        for (RecurringRule rule : saved) {
            if (rule.getStatus() == RuleStatus.ACTIVE) {
                expectedActive.put(rule.getId(), expectedOccurrences(rule));
            }
        }

        service.lazyGenerate(LEDGER);

        // ---- 断言 1：ACTIVE 规则的每个应补齐期次恰一条 PENDING，快照 = 规则模板（需求 3.1、3.7）。----
        for (RecurringRule rule : saved) {
            List<RecurringPendingItem> items = itemsOf(rule.getId());
            if (rule.getStatus() == RuleStatus.PAUSED) {
                // ---- 断言 2：PAUSED 规则零新增（需求 3.5、6.1）。----
                assertThat(items)
                        .as("PAUSED 规则不应产生任何待确认项 ruleId=%s", rule.getId())
                        .isEmpty();
                continue;
            }
            Set<LocalDate> expected = expectedActive.get(rule.getId());
            assertThat(items).extracting(RecurringPendingItem::getOccurrenceDate)
                    .as("ACTIVE 规则应恰补齐每个应生成期次 ruleId=%s", rule.getId())
                    .containsExactlyInAnyOrderElementsOf(expected);
            assertThat(items).allSatisfy(item -> {
                assertThat(item.getStatus()).isEqualTo(PendingStatus.PENDING);
                assertThat(item.getLedgerId()).isEqualTo(LEDGER);
                // 模板快照 = 生成时规则的模板字段（需求 3.1）。
                assertThat(item.getType()).isEqualTo(rule.getType());
                assertThat(item.getAmount()).isEqualByComparingTo(rule.getAmount());
                assertThat(item.getCategoryId()).isEqualTo(rule.getCategoryId());
                assertThat(item.getAccountId()).isEqualTo(rule.getAccountId());
                assertThat(item.getNote()).isEqualTo(rule.getNote());
            });
        }

        // ---- 断言 3：生成期不触账——无交易、账户余额不变（需求 3.2）。----
        assertThat(transactionRepository.count())
                .as("懒生成不得创建任何交易")
                .isZero();
        assertThat(accountRepository.findById(seededAccountId).orElseThrow().getCurrentBalance())
                .as("懒生成不得改变账户余额")
                .isEqualByComparingTo(SEED_BALANCE);

        // 记录首次生成后所有项的快照（id → 快照），用于编辑后不可变对照。
        Map<Long, Snapshot> before = snapshotAll();

        // ---- 断言 4：编辑规则（改金额 / 备注）不改变已生成 PENDING 项的快照（需求 6.3、6.4）。----
        for (RecurringRule rule : saved) {
            RecurringRule managed = ruleRepository.findById(rule.getId()).orElseThrow();
            managed.setAmount(managed.getAmount().add(new BigDecimal("1000.00")));
            managed.setNote((managed.getNote() == null ? "" : managed.getNote()) + "-EDITED");
            ruleRepository.save(managed);
        }
        service.lazyGenerate(LEDGER);

        Map<Long, Snapshot> after = snapshotAll();
        // 编辑前已存在的每条项在编辑 + 再次懒生成后快照原样保留。
        for (Map.Entry<Long, Snapshot> entry : before.entrySet()) {
            assertThat(after)
                    .as("已生成项不应因编辑规则而消失 itemId=%s", entry.getKey())
                    .containsKey(entry.getKey());
            assertThat(after.get(entry.getKey()))
                    .as("已生成 PENDING 项的快照应在编辑规则后保持不变 itemId=%s", entry.getKey())
                    .isEqualTo(entry.getValue());
        }
    }

    // =====================================================================
    // 期望期次（纯算法对照，与服务同口径：occurrencesUpTo ∩ [generationLowerBound, today]）
    // =====================================================================

    private Set<LocalDate> expectedOccurrences(RecurringRule rule) {
        RuleSpec spec = RecurringPendingItemService.toRuleSpec(rule);
        LocalDate lowerBound = RecurringPendingItemService.generationLowerBound(rule);
        Set<LocalDate> expected = new TreeSet<>();
        for (LocalDate d : new OccurrenceCalculator().occurrencesUpTo(spec, TODAY)) {
            if (!d.isBefore(lowerBound)) {
                expected.add(d);
            }
        }
        return expected;
    }

    // =====================================================================
    // 持久化辅助
    // =====================================================================

    private Long seedAccount() {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Account account = new Account();
        account.setUserId(ALICE);
        account.setName("现金");
        account.setType(AccountType.CASH);
        account.setInitialBalance(SEED_BALANCE);
        account.setCurrentBalance(SEED_BALANCE);
        account.setSortOrder(0);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        return accountRepository.save(account).getId();
    }

    private RecurringRule saveRule(RuleDef def) {
        RecurringRule rule = new RecurringRule();
        rule.setUserId(ALICE);
        rule.setLedgerId(LEDGER);
        rule.setType(def.type());
        rule.setAmount(def.amount());
        rule.setCategoryId(def.categoryId());
        rule.setAccountId(def.accountId());
        rule.setNote(def.note());
        rule.setFrequency(def.frequency());
        rule.setWeeklyDays(toCsv(def.weeklyDays()));
        rule.setMonthDay(def.monthDay());
        rule.setMonthEnd(def.monthEnd());
        rule.setYearMonth(def.yearMonth());
        rule.setYearDay(def.yearDay());
        rule.setStartDate(def.startDate());
        rule.setEndCondition(def.endCondition());
        rule.setUntilDate(def.untilDate());
        rule.setCountN(def.countN());
        rule.setStatus(def.status());
        rule.setCreatedAt(def.startDate().atStartOfDay());
        // updatedAt 决定生成下界 max(startDate, updatedAt)：> startDate 即模拟恢复锚点。
        rule.setUpdatedAt(def.updatedDate().atStartOfDay());
        return ruleRepository.save(rule);
    }

    private static String toCsv(Set<Integer> weeklyDays) {
        if (weeklyDays == null || weeklyDays.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Integer d : new TreeSet<>(weeklyDays)) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(d);
        }
        return sb.toString();
    }

    private List<RecurringPendingItem> itemsOf(Long ruleId) {
        return pendingItemRepository.findAll().stream()
                .filter(i -> i.getRuleId().equals(ruleId))
                .toList();
    }

    private Map<Long, Snapshot> snapshotAll() {
        Map<Long, Snapshot> map = new HashMap<>();
        for (RecurringPendingItem item : pendingItemRepository.findAll()) {
            map.put(item.getId(), Snapshot.of(item));
        }
        return map;
    }

    /** 待确认项的不可变快照，用于「编辑规则后原样保留」的相等对照。 */
    private record Snapshot(Long ruleId, LocalDate occurrenceDate, PendingStatus status, String type,
            long amountCents, Long categoryId, Long accountId, String note) {
        static Snapshot of(RecurringPendingItem i) {
            return new Snapshot(i.getRuleId(), i.getOccurrenceDate(), i.getStatus(), i.getType(),
                    i.getAmount().movePointRight(2).longValueExact(), i.getCategoryId(),
                    i.getAccountId(), i.getNote());
        }
    }

    // =====================================================================
    // 生成器
    // =====================================================================

    /** 一条规则的全部落库字段。 */
    record RuleDef(RuleStatus status, Frequency frequency, Set<Integer> weeklyDays, Integer monthDay,
            boolean monthEnd, Integer yearMonth, Integer yearDay, LocalDate startDate,
            LocalDate updatedDate, EndCondition endCondition, LocalDate untilDate, Integer countN,
            String type, BigDecimal amount, Long categoryId, Long accountId, String note) {
    }

    private record FreqConfig(Frequency frequency, Set<Integer> weeklyDays, Integer monthDay,
            boolean monthEnd, Integer yearMonth, Integer yearDay) {
    }

    private record DateConfig(LocalDate startDate, LocalDate updatedDate, EndCondition endCondition,
            LocalDate untilDate, Integer countN) {
    }

    private record TemplateFields(String type, BigDecimal amount, String note, Long categoryId,
            Long accountId) {
    }

    /** 每个场景 1–4 条规则，混合 ACTIVE / PAUSED。 */
    @Provide
    Arbitrary<Scenario> scenarios() {
        return ruleDefs().list().ofMinSize(1).ofMaxSize(4).map(Scenario::new);
    }

    record Scenario(List<RuleDef> rules) {
    }

    private Arbitrary<RuleDef> ruleDefs() {
        return Combinators.combine(statuses(), freqConfigs(), dateConfigs(), templateFields())
                .as((status, freq, dates, tpl) -> new RuleDef(status, freq.frequency(),
                        freq.weeklyDays(), freq.monthDay(), freq.monthEnd(), freq.yearMonth(),
                        freq.yearDay(), dates.startDate(), dates.updatedDate(), dates.endCondition(),
                        dates.untilDate(), dates.countN(), tpl.type(), tpl.amount(), tpl.categoryId(),
                        tpl.accountId(), tpl.note()));
    }

    /** ACTIVE 占多数以覆盖补齐主路径，PAUSED 覆盖「暂停不生成」。 */
    private Arbitrary<RuleStatus> statuses() {
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(4, Arbitraries.just(RuleStatus.ACTIVE)),
                net.jqwik.api.Tuple.of(1, Arbitraries.just(RuleStatus.PAUSED)));
    }

    /** DAILY / WEEKLY(非空 1–7) / MONTHLY(指定日) / MONTHLY(月末) / YEARLY(月+日)。 */
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

    /**
     * 开始日期落 {@code [today-60, today+15]}（约束各频率期次数量以保证迭代高效）；生成下界锚点
     * {@code updatedDate ∈ [startDate, startDate+20]} 以覆盖「恢复后不回补」的下界跳过；结束条件覆盖
     * NEVER / UNTIL_DATE(≥start) / COUNT(小 N)。
     */
    private Arbitrary<DateConfig> dateConfigs() {
        long minStart = TODAY.minusDays(60).toEpochDay();
        long maxStart = TODAY.plusDays(15).toEpochDay();
        return Arbitraries.longs().between(minStart, maxStart).map(LocalDate::ofEpochDay)
                .flatMap(start -> Combinators.combine(
                                Arbitraries.integers().between(0, 20),
                                endConfigs(start))
                        .as((updOffset, end) -> new DateConfig(start, start.plusDays(updOffset),
                                end.endCondition(), end.untilDate(), end.countN())));
    }

    private Arbitrary<DateConfig> endConfigs(LocalDate start) {
        Arbitrary<DateConfig> never =
                Arbitraries.just(new DateConfig(start, start, EndCondition.NEVER, null, null));
        Arbitrary<DateConfig> until = Arbitraries.integers().between(0, 120)
                .map(n -> new DateConfig(start, start, EndCondition.UNTIL_DATE, start.plusDays(n), null));
        Arbitrary<DateConfig> count = Arbitraries.integers().between(1, 30)
                .map(n -> new DateConfig(start, start, EndCondition.COUNT, null, n));
        return Arbitraries.oneOf(never, until, count);
    }

    private Arbitrary<TemplateFields> templateFields() {
        Arbitrary<String> types = Arbitraries.of("expense", "income");
        // 金额以「分」构造，保证恰 2 位小数、精确可比。
        Arbitrary<BigDecimal> amounts = Arbitraries.longs().between(1L, 99_999_999_999L)
                .map(cents -> BigDecimal.valueOf(cents, 2));
        Arbitrary<String> notes = Arbitraries.oneOf(
                Arbitraries.just((String) null),
                Arbitraries.strings().ofMinLength(0).ofMaxLength(20));
        Arbitrary<Long> categoryIds = Arbitraries.longs().between(1L, 50L);
        Arbitrary<Long> accountIds = Arbitraries.longs().between(1L, 50L);
        return Combinators.combine(types, amounts, notes, categoryIds, accountIds)
                .as(TemplateFields::new);
    }
}
