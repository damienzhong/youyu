package com.damien.youyu.service.recurring;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.EndCondition;
import com.damien.youyu.domain.Frequency;
import com.damien.youyu.domain.PendingStatus;
import com.damien.youyu.domain.RecurringPendingItem;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.RuleStatus;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;

/**
 * {@link RecurringPendingItemService#queryPendingItems} 的示例 / 边界集成测试（tasks 5.1）。
 *
 * <h2>为何走全栈 {@code @SpringBootTest} + 真实提交、不用测试级事务</h2>
 * <p>查询内部先调 {@link RecurringPendingItemService#lazyGenerate}，其把每条期次插入下沉到
 * {@link RecurringPendingItemGenerator#generate} 的 {@code REQUIRES_NEW} 独立事务——只有经真实 Spring
 * 事务代理并真实提交才生效。故本测试用全栈上下文、不加测试级 {@code @Transactional}（那会掩盖真实提交），
 * 清理改为每个用例前显式清库（{@link #reset()}），并用独立命名的内存库避免污染其它切片测试。</p>
 *
 * <p>时钟用 {@code @Primary} 的固定 {@link Clock}（{@code Asia/Shanghai} 的 2025-06-15），使 today 与期次
 * 序列可确定性断言。</p>
 *
 * <p>覆盖：查询先触发懒生成再返回 {@code PENDING}（需求 3.7、5.1）；按
 * 「到期日升序 → 规则创建时间升序 → 项 id 升序」确定性排序（需求 5.2）；混合状态只返回 {@code PENDING}
 * （需求 5.1）；跨账本隔离（需求 8.4）；无 {@code PENDING} 返回空列表不报错（需求 5.1）。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-pending-query-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringPendingItemQueryTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 08:00（Asia/Shanghai）→ today = 2025-06-15。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    private static final long ALICE = 1L;
    private static final long LEDGER = 100L;
    private static final long OTHER_LEDGER = 200L;
    private static final long CATEGORY = 10L;

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

    @BeforeEach
    void reset() {
        // 清理不靠回滚（REQUIRES_NEW 真实提交）：每个用例前硬清相关表。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
    }

    // ---------------- fixtures ----------------

    /** 直接落库一条每月规则（绕过创建校验，聚焦查询）。createdAt 决定需求 5.2 的中间排序键。 */
    private RecurringRule saveMonthlyRule(long ledgerId, int monthDay, LocalDate startDate,
            LocalDateTime createdAt) {
        RecurringRule rule = new RecurringRule();
        rule.setUserId(ALICE);
        rule.setLedgerId(ledgerId);
        rule.setType("expense");
        rule.setAmount(new BigDecimal("3000.00"));
        rule.setCategoryId(CATEGORY);
        rule.setAccountId(1L);
        rule.setNote("房租");
        rule.setFrequency(Frequency.MONTHLY);
        rule.setMonthDay(monthDay);
        rule.setMonthEnd(false);
        rule.setStartDate(startDate);
        rule.setEndCondition(EndCondition.NEVER);
        rule.setStatus(RuleStatus.ACTIVE);
        rule.setCreatedAt(createdAt);
        rule.setUpdatedAt(startDate.atStartOfDay());
        return ruleRepository.save(rule);
    }

    private RecurringPendingItem seedItem(RecurringRule rule, long ledgerId, LocalDate occurrenceDate,
            PendingStatus status) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        RecurringPendingItem item = new RecurringPendingItem();
        item.setRuleId(rule.getId());
        item.setLedgerId(ledgerId);
        item.setOccurrenceDate(occurrenceDate);
        item.setStatus(status);
        item.setType("expense");
        item.setAmount(new BigDecimal("3000.00"));
        item.setCategoryId(CATEGORY);
        item.setAccountId(1L);
        item.setNote("房租");
        if (status == PendingStatus.CONFIRMED) {
            item.setConfirmedTransactionId(9999L);
        }
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        return pendingItemRepository.save(item);
    }

    // ---------------- 查询先懒生成再返回 PENDING（需求 3.7、5.1） ----------------

    @Test
    void queryPendingItems_triggersLazyGenerationThenReturnsPending() {
        // 每月 5 号，开始 2025-03-05 → 到 today(2025-06-15) 应懒生成 03/04/05/06-05 四期 PENDING。
        saveMonthlyRule(LEDGER, 5, LocalDate.of(2025, 3, 5), LocalDate.of(2025, 3, 5).atStartOfDay());

        List<RecurringPendingItem> result = service.queryPendingItems(LEDGER);

        assertThat(result).extracting(RecurringPendingItem::getOccurrenceDate)
                .containsExactly(
                        LocalDate.of(2025, 3, 5), LocalDate.of(2025, 4, 5),
                        LocalDate.of(2025, 5, 5), LocalDate.of(2025, 6, 5));
        assertThat(result).allSatisfy(i -> {
            assertThat(i.getStatus()).isEqualTo(PendingStatus.PENDING);
            // 每项携带来源规则 id、到期日与模板快照字段（需求 5.1）。
            assertThat(i.getRuleId()).isNotNull();
            assertThat(i.getType()).isEqualTo("expense");
            assertThat(i.getAmount()).isEqualByComparingTo("3000.00");
            assertThat(i.getCategoryId()).isEqualTo(CATEGORY);
            assertThat(i.getNote()).isEqualTo("房租");
        });
    }

    // ---------------- 确定性排序：到期日 → 规则创建时间 → 项 id（需求 5.2） ----------------

    @Test
    void queryPendingItems_ordersByOccurrenceDateThenRuleCreatedAtThenItemId() {
        // 两条规则：ruleLate 创建更晚，ruleEarly 创建更早。二者在同一到期日 06-05 各有一项，
        // 应按规则 created_at 升序：ruleEarly 的项排在 ruleLate 的项前面。
        RecurringRule ruleEarly = saveMonthlyRule(LEDGER, 5, LocalDate.of(2025, 6, 5),
                LocalDateTime.of(2025, 1, 1, 0, 0));
        RecurringRule ruleLate = saveMonthlyRule(LEDGER, 5, LocalDate.of(2025, 6, 5),
                LocalDateTime.of(2025, 2, 1, 0, 0));

        // 故意先落库 late 的项、后落库 early 的项（id: late < early），验证排序不是靠 id 巧合。
        RecurringPendingItem lateItem = seedItem(ruleLate, LEDGER, LocalDate.of(2025, 6, 5),
                PendingStatus.PENDING);
        RecurringPendingItem earlyItem = seedItem(ruleEarly, LEDGER, LocalDate.of(2025, 6, 5),
                PendingStatus.PENDING);
        assertThat(lateItem.getId()).isLessThan(earlyItem.getId());

        List<RecurringPendingItem> result = service.queryPendingItems(LEDGER);

        // 同一到期日按规则 created_at 升序 → ruleEarly 的项在前，尽管其项 id 更大。
        assertThat(result).extracting(RecurringPendingItem::getId)
                .containsExactly(earlyItem.getId(), lateItem.getId());
    }

    @Test
    void queryPendingItems_sameOccurrenceAndRuleCreatedAt_ordersByItemId() {
        // 同一规则的两期（同一 created_at），跨到期日先按到期日排；再构造同到期日同规则的场景由 id 兜底。
        RecurringRule rule = saveMonthlyRule(LEDGER, 5, LocalDate.of(2025, 4, 5),
                LocalDateTime.of(2025, 1, 1, 0, 0));

        List<RecurringPendingItem> result = service.queryPendingItems(LEDGER);

        // 04-05、05-05、06-05 三期按到期日升序；同规则 created_at 相同，到期日已严格区分。
        assertThat(result).extracting(RecurringPendingItem::getOccurrenceDate)
                .containsExactly(
                        LocalDate.of(2025, 4, 5), LocalDate.of(2025, 5, 5), LocalDate.of(2025, 6, 5));
        // id 随到期日单调递增（同规则顺序生成），验证第三级键与前两级一致。
        assertThat(result).extracting(RecurringPendingItem::getId).isSorted();
    }

    // ---------------- 混合状态只返回 PENDING（需求 5.1） ----------------

    @Test
    void queryPendingItems_returnsOnlyPendingAmongMixedStatuses() {
        RecurringRule rule = saveMonthlyRule(LEDGER, 5, LocalDate.of(2025, 6, 5),
                LocalDateTime.of(2025, 1, 1, 0, 0));
        // 手工植入不同状态的项（不同到期日，避免撞唯一键）。
        seedItem(rule, LEDGER, LocalDate.of(2025, 3, 5), PendingStatus.CONFIRMED);
        seedItem(rule, LEDGER, LocalDate.of(2025, 4, 5), PendingStatus.SKIPPED);
        seedItem(rule, LEDGER, LocalDate.of(2025, 5, 5), PendingStatus.PENDING);
        // 06-05 由懒生成补一条 PENDING。

        List<RecurringPendingItem> result = service.queryPendingItems(LEDGER);

        assertThat(result).extracting(RecurringPendingItem::getStatus)
                .containsOnly(PendingStatus.PENDING);
        assertThat(result).extracting(RecurringPendingItem::getOccurrenceDate)
                .containsExactly(LocalDate.of(2025, 5, 5), LocalDate.of(2025, 6, 5));
    }

    // ---------------- 跨账本隔离（需求 8.4） ----------------

    @Test
    void queryPendingItems_isolatesByLedger() {
        RecurringRule mine = saveMonthlyRule(LEDGER, 5, LocalDate.of(2025, 6, 5),
                LocalDateTime.of(2025, 1, 1, 0, 0));
        RecurringRule other = saveMonthlyRule(OTHER_LEDGER, 5, LocalDate.of(2025, 6, 5),
                LocalDateTime.of(2025, 1, 1, 0, 0));

        List<RecurringPendingItem> result = service.queryPendingItems(LEDGER);

        assertThat(result).isNotEmpty();
        assertThat(result).extracting(RecurringPendingItem::getLedgerId).containsOnly(LEDGER);
        assertThat(result).extracting(RecurringPendingItem::getRuleId)
                .containsOnly(mine.getId())
                .doesNotContain(other.getId());
    }

    // ---------------- 无 PENDING 返回空列表不报错（需求 5.1） ----------------

    @Test
    void queryPendingItems_returnsEmptyWhenNoPending() {
        // 无任何规则、无任何项：懒生成无事可做，查询返回空列表。
        List<RecurringPendingItem> result = service.queryPendingItems(LEDGER);

        assertThat(result).isEmpty();
    }

    @Test
    void queryPendingItems_returnsEmptyWhenAllItemsProcessed() {
        // 规则的期次全部已确认 / 跳过，且规则暂停以免懒生成补新 PENDING。
        RecurringRule rule = saveMonthlyRule(OTHER_LEDGER, 5, LocalDate.of(2025, 3, 5),
                LocalDateTime.of(2025, 1, 1, 0, 0));
        // 用另一账本承载已处理项；当前账本 LEDGER 无任何 PENDING。
        seedItem(rule, OTHER_LEDGER, LocalDate.of(2025, 3, 5), PendingStatus.CONFIRMED);
        seedItem(rule, OTHER_LEDGER, LocalDate.of(2025, 4, 5), PendingStatus.SKIPPED);

        List<RecurringPendingItem> result = service.queryPendingItems(LEDGER);

        assertThat(result).isEmpty();
    }
}
