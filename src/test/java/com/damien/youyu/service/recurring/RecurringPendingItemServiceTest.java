package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import com.damien.youyu.domain.EndCondition;
import com.damien.youyu.domain.Frequency;
import com.damien.youyu.domain.PendingStatus;
import com.damien.youyu.domain.RecurringPendingItem;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.RuleStatus;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * {@link RecurringPendingItemService} 懒生成的<b>单规则补齐失败隔离</b>单元测试（tasks 4.4，需求 3.8）。
 *
 * <p>服务的懒生成把每条规则的补齐包在各自的 try/catch 内（design.md「懒生成算法」）：某规则补齐抛异常时
 * 仅记 {@code [RECURRING_GEN_FAILED]} 告警日志并<b>就地隔离</b>，不阻断同账本其余规则的补齐，也不阻断
 * 已有待确认项的返回。为聚焦这一行为，本测试不起 Spring 上下文，四个协作者用 Mockito 桩、{@code Clock}
 * 用固定时钟（今日恒为 {@code 2025-06-15}，Asia/Shanghai），并以 logback {@link ListAppender} 捕获 WARN 日志。</p>
 *
 * <ul>
 *   <li><b>同账本其余规则不受连坐</b>：注入规则 A 的 {@code generate} 抛异常，断言规则 B 仍被补齐、
 *       {@code lazyGenerate} 不向外抛异常，且记录了针对规则 A 的 {@code [RECURRING_GEN_FAILED]} 告警（需求 3.8）。</li>
 *   <li><b>不阻断已有待确认项的返回</b>：一条规则补齐失败时，{@code queryPendingItems} 仍返回当前账本
 *       既有的 {@code PENDING} 项，不向查询主路径抛错（需求 3.8）。</li>
 * </ul>
 */
class RecurringPendingItemServiceTest {

    private static final long LEDGER = 7L;
    private static final long RULE_A = 101L; // 补齐失败的规则
    private static final long RULE_B = 202L; // 正常补齐的规则
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);

    private final Clock clock =
            Clock.fixed(TODAY.atStartOfDay(ZONE).plusHours(12).toInstant(), ZONE);

    private RecurringRuleRepository ruleRepository;
    private RecurringPendingItemRepository pendingItemRepository;
    private RecurringPendingItemGenerator generator;
    private RecurringPendingItemService service;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(RecurringRuleRepository.class);
        pendingItemRepository = mock(RecurringPendingItemRepository.class);
        generator = mock(RecurringPendingItemGenerator.class);
        // 确认入账协作者对懒生成失败隔离用例无影响，用桩注入以满足构造签名。
        service = new RecurringPendingItemService(
                ruleRepository, pendingItemRepository, generator,
                mock(com.damien.youyu.service.TransactionService.class),
                mock(com.damien.youyu.repository.CategoryRepository.class),
                mock(com.damien.youyu.service.LedgerAccountResolver.class),
                new RecurringTemplateValidator(),
                clock);

        serviceLogger = (Logger) LoggerFactory.getLogger(RecurringPendingItemService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        serviceLogger.addAppender(logAppender);
        serviceLogger.setLevel(Level.WARN);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(logAppender);
        logAppender.stop();
    }

    /**
     * 单规则补齐失败就地隔离（需求 3.8）：规则 A 的 {@code generate} 抛 {@link RuntimeException} 时，
     * {@code lazyGenerate} 不抛出、规则 B 仍被补齐，并记录针对规则 A 的 {@code [RECURRING_GEN_FAILED]} 告警。
     */
    @Test
    void oneRuleGenerationFailureDoesNotBlockOtherRules() {
        RecurringRule ruleA = dailyRule(RULE_A);
        RecurringRule ruleB = dailyRule(RULE_B);
        when(ruleRepository.findByLedgerIdAndStatus(LEDGER, RuleStatus.ACTIVE))
                .thenReturn(List.of(ruleA, ruleB));
        // 两条规则该期次均尚无记录，都会走到 generate。
        when(pendingItemRepository.existsByRuleIdAndOccurrenceDate(anyLong(), any(LocalDate.class)))
                .thenReturn(false);
        // 注入规则 A 的补齐失败；规则 B 正常（void 默认不做事，显式写清语义）。
        doThrow(new RuntimeException("boom for rule A"))
                .when(generator).generate(eq(ruleA), any(LocalDate.class));
        doNothing().when(generator).generate(eq(ruleB), any(LocalDate.class));

        // 失败不向外传播（需求 3.8）。
        assertThatCode(() -> service.lazyGenerate(LEDGER)).doesNotThrowAnyException();

        // 规则 A 已尝试补齐（并失败），规则 B 仍被补齐——不连坐（需求 3.8）。
        verify(generator).generate(eq(ruleA), eq(TODAY));
        verify(generator).generate(eq(ruleB), eq(TODAY));

        // 就地隔离：记录针对规则 A 的 [RECURRING_GEN_FAILED] 告警。
        assertThat(warnMessages())
                .anyMatch(m -> m.contains("[RECURRING_GEN_FAILED]")
                        && m.contains("ruleId=" + RULE_A));
    }

    /**
     * 不阻断已有待确认项的返回（需求 3.8）：即便某规则补齐失败，{@code queryPendingItems} 仍返回当前账本
     * 既有的 {@code PENDING} 项，不向查询主路径抛错。
     */
    @Test
    void generationFailureDoesNotBlockReturningExistingPendingItems() {
        RecurringRule ruleA = dailyRule(RULE_A);
        when(ruleRepository.findByLedgerIdAndStatus(LEDGER, RuleStatus.ACTIVE))
                .thenReturn(List.of(ruleA));
        when(pendingItemRepository.existsByRuleIdAndOccurrenceDate(anyLong(), any(LocalDate.class)))
                .thenReturn(false);
        doThrow(new RuntimeException("boom for rule A"))
                .when(generator).generate(eq(ruleA), any(LocalDate.class));

        // 当前账本已有一条属于规则 A 的 PENDING 待确认项（早前已生成）。
        RecurringPendingItem existing = existingPendingItem(RULE_A, TODAY.minusDays(1));
        when(pendingItemRepository.findByLedgerIdAndStatusOrderByOccurrenceDateAscIdAsc(
                LEDGER, PendingStatus.PENDING)).thenReturn(newMutableList(existing));
        when(ruleRepository.findAllById(any())).thenReturn(List.of(ruleA));

        List<RecurringPendingItem> result = service.queryPendingItems(LEDGER);

        // 补齐失败被隔离，已有项照常返回、查询不抛错（需求 3.8）。
        assertThat(result).containsExactly(existing);
        assertThat(warnMessages())
                .anyMatch(m -> m.contains("[RECURRING_GEN_FAILED]")
                        && m.contains("ruleId=" + RULE_A));
    }

    // ---- 辅助 ----

    /**
     * 构造一条 {@code DAILY} 且开始日期与生成下界均为今日的 ACTIVE 规则，使 {@code occurrencesUpTo(rule, today)}
     * 恰好产出单一到期期次（今日），便于隔离验证「每规则一次 generate」。
     */
    private RecurringRule dailyRule(long id) {
        RecurringRule rule = new RecurringRule();
        rule.setId(id);
        rule.setUserId(1L);
        rule.setLedgerId(LEDGER);
        rule.setType("expense");
        rule.setAmount(new BigDecimal("10.00"));
        rule.setCategoryId(11L);
        rule.setAccountId(22L);
        rule.setNote("rent");
        rule.setFrequency(Frequency.DAILY);
        rule.setStartDate(TODAY);
        rule.setEndCondition(EndCondition.NEVER);
        rule.setStatus(RuleStatus.ACTIVE);
        // 生成下界 = max(startDate, updatedAt.toLocalDate()) = TODAY，仅今日期次进入补齐。
        rule.setCreatedAt(TODAY.atStartOfDay());
        rule.setUpdatedAt(TODAY.atStartOfDay());
        return rule;
    }

    private RecurringPendingItem existingPendingItem(long ruleId, LocalDate occurrenceDate) {
        RecurringPendingItem item = new RecurringPendingItem();
        item.setId(500L);
        item.setRuleId(ruleId);
        item.setLedgerId(LEDGER);
        item.setOccurrenceDate(occurrenceDate);
        item.setStatus(PendingStatus.PENDING);
        item.setType("expense");
        item.setAmount(new BigDecimal("10.00"));
        item.setCategoryId(11L);
        item.setAccountId(22L);
        item.setNote("rent");
        LocalDateTime now = LocalDateTime.now(clock);
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        return item;
    }

    /** {@code queryPendingItems} 会就地 {@code sort} 结果，故须传入可变列表。 */
    private static List<RecurringPendingItem> newMutableList(RecurringPendingItem... items) {
        return new java.util.ArrayList<>(List.of(items));
    }

    private List<String> warnMessages() {
        return logAppender.list.stream()
                .filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }
}
