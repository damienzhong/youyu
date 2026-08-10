package com.damien.youyu.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import com.damien.youyu.domain.EndCondition;
import com.damien.youyu.domain.Frequency;
import com.damien.youyu.domain.PendingStatus;
import com.damien.youyu.domain.RecurringPendingItem;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.RuleStatus;

/**
 * {@link RecurringRule} / {@link RecurringPendingItem} 实体映射与 {@link RecurringRuleRepository} /
 * {@link RecurringPendingItemRepository} 查询方法的落地冒烟测试（任务 1.2）。
 *
 * <p>走 {@code @DataJpaTest} + 真实 H2（{@code MODE=MySQL}，表结构由实体生成，{@code ddl-auto=create-drop}）：
 * 一方面验证实体字段映射与 V38 DDL 列一致（H2 建表成功即证明枚举/日期/金额列映射无误），另一方面
 * 覆盖任务要求的四个仓库查询方法：按 ledger+status 列 ACTIVE 规则、按 id+user+ledger 归属查询、
 * {@code existsByRuleIdAndOccurrenceDate}、按 ledger+status 排序查询、按 rule 级联删除 PENDING。</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RecurringRepositoryMappingTest {

    private static final long USER = 1L;
    private static final long LEDGER = 100L;
    private static final LocalDateTime NOW = LocalDateTime.parse("2025-06-01T12:30:00");

    @Autowired private RecurringRuleRepository ruleRepository;
    @Autowired private RecurringPendingItemRepository pendingRepository;

    private RecurringRule rule(long ledgerId, RuleStatus status, LocalDateTime createdAt) {
        RecurringRule r = new RecurringRule();
        r.setUserId(USER);
        r.setLedgerId(ledgerId);
        r.setType("expense");
        r.setAmount(new BigDecimal("30.00"));
        r.setCategoryId(11L);
        r.setAccountId(21L);
        r.setNote("房租");
        r.setFrequency(Frequency.MONTHLY);
        r.setMonthDay(5);
        r.setMonthEnd(false);
        r.setStartDate(LocalDate.of(2025, 1, 1));
        r.setEndCondition(EndCondition.NEVER);
        r.setStatus(status);
        r.setCreatedAt(createdAt);
        r.setUpdatedAt(createdAt);
        return r;
    }

    private RecurringPendingItem pending(long ruleId, long ledgerId, LocalDate occurrenceDate,
            PendingStatus status) {
        RecurringPendingItem p = new RecurringPendingItem();
        p.setRuleId(ruleId);
        p.setLedgerId(ledgerId);
        p.setOccurrenceDate(occurrenceDate);
        p.setStatus(status);
        p.setType("expense");
        p.setAmount(new BigDecimal("30.00"));
        p.setCategoryId(11L);
        p.setAccountId(21L);
        p.setNote("房租");
        p.setCreatedAt(NOW);
        p.setUpdatedAt(NOW);
        return p;
    }

    @Test
    void rulePersistsAndOwnershipQueriesWork() {
        RecurringRule active = ruleRepository.save(rule(LEDGER, RuleStatus.ACTIVE, NOW));
        RecurringRule paused = ruleRepository.save(rule(LEDGER, RuleStatus.PAUSED, NOW.plusMinutes(1)));
        ruleRepository.save(rule(999L, RuleStatus.ACTIVE, NOW)); // 别的账本，隔离验证

        // 按 ledger+status 只列出本账本 ACTIVE 规则
        List<RecurringRule> activeRules = ruleRepository.findByLedgerIdAndStatus(LEDGER, RuleStatus.ACTIVE);
        assertThat(activeRules).extracting(RecurringRule::getId).containsExactly(active.getId());

        // 全部字段回读正确（枚举、日期、金额映射）
        RecurringRule reloaded = activeRules.get(0);
        assertThat(reloaded.getFrequency()).isEqualTo(Frequency.MONTHLY);
        assertThat(reloaded.getMonthDay()).isEqualTo(5);
        assertThat(reloaded.getEndCondition()).isEqualTo(EndCondition.NEVER);
        assertThat(reloaded.getAmount()).isEqualByComparingTo("30.00");
        assertThat(reloaded.getStartDate()).isEqualTo(LocalDate.of(2025, 1, 1));

        // 列表含 ACTIVE + PAUSED，按 created_at 升序
        assertThat(ruleRepository.findByUserIdAndLedgerIdOrderByCreatedAtAsc(USER, LEDGER))
                .extracting(RecurringRule::getId)
                .containsExactly(active.getId(), paused.getId());

        // 归属查询：命中本人本账本；越权账本返回空
        assertThat(ruleRepository.findByIdAndUserIdAndLedgerId(active.getId(), USER, LEDGER)).isPresent();
        assertThat(ruleRepository.findByIdAndUserIdAndLedgerId(active.getId(), USER, 999L)).isEmpty();
    }

    @Test
    void pendingItemPersistsQueriesSortAndCascadeDeleteWork() {
        long ruleId = 500L;
        RecurringPendingItem p2 = pendingRepository.save(pending(ruleId, LEDGER, LocalDate.of(2025, 3, 5), PendingStatus.PENDING));
        RecurringPendingItem p1 = pendingRepository.save(pending(ruleId, LEDGER, LocalDate.of(2025, 2, 5), PendingStatus.PENDING));
        RecurringPendingItem confirmed = pendingRepository.save(pending(ruleId, LEDGER, LocalDate.of(2025, 1, 5), PendingStatus.CONFIRMED));

        // existsByRuleIdAndOccurrenceDate
        assertThat(pendingRepository.existsByRuleIdAndOccurrenceDate(ruleId, LocalDate.of(2025, 2, 5))).isTrue();
        assertThat(pendingRepository.existsByRuleIdAndOccurrenceDate(ruleId, LocalDate.of(2025, 12, 31))).isFalse();

        // 按 ledger+status 过滤 PENDING 并按 occurrence_date 升序
        List<RecurringPendingItem> pendingList =
                pendingRepository.findByLedgerIdAndStatusOrderByOccurrenceDateAscIdAsc(LEDGER, PendingStatus.PENDING);
        assertThat(pendingList).extracting(RecurringPendingItem::getId).containsExactly(p1.getId(), p2.getId());

        // 级联删除 PENDING：仅删 PENDING，保留 CONFIRMED
        int deleted = pendingRepository.deleteByRuleIdAndStatus(ruleId, PendingStatus.PENDING);
        assertThat(deleted).isEqualTo(2);
        assertThat(pendingRepository.findByLedgerIdAndStatusOrderByOccurrenceDateAscIdAsc(LEDGER, PendingStatus.PENDING)).isEmpty();
        assertThat(pendingRepository.findById(confirmed.getId())).isPresent();
    }
}
