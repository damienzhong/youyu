package com.damien.youyu.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import com.damien.youyu.domain.CustomReminder;
import com.damien.youyu.domain.ReminderFrequency;
import com.damien.youyu.domain.ReminderQuota;
import com.damien.youyu.domain.ReminderSendLog;
import com.damien.youyu.domain.ReminderSendResult;

/**
 * 自定义提醒三仓储的持久化行为测试（任务 1.7）。
 *
 * <p>走 {@code @DataJpaTest} + H2（{@code MODE=MySQL}，表由实体经 {@code ddl-auto=create-drop} 生成，
 * 与仓库既有 {@code @DataJpaTest} 切片一致）。这些断言全是<b>落库事实</b>——{@code between} 边界的开闭、
 * 原子 UPSERT 的上限、条件更新的下界、以及唯一约束在库侧的冲突翻译——用测试替身会把被测机制删掉，
 * 因此必须在真实 H2 连接上跑。</p>
 *
 * <ul>
 *   <li>{@link CustomReminderRepository#findDue}：窗口 {@code [start,end]} 闭区间边界、停用不入选、频率集合过滤（需求 3.3）。</li>
 *   <li>{@link ReminderQuotaRepository#addCapped}：不存在→插入 {@code min(delta,50)}，存在→{@code min(remaining+delta,50)}（需求 5.3）。</li>
 *   <li>{@link ReminderQuotaRepository#decrementFloorZero}：在 0 时不变负（需求 5.5）。</li>
 *   <li>唯一键冲突翻译为 {@link DataIntegrityViolationException}（需求 5.8 支撑幂等/去重的库侧兜底）。</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ReminderRepositoryTest {

    private static final LocalDateTime NOW = LocalDateTime.parse("2025-06-01T21:00:00");

    @Autowired private CustomReminderRepository reminderRepository;
    @Autowired private ReminderQuotaRepository quotaRepository;
    @Autowired private ReminderSendLogRepository sendLogRepository;
    @Autowired private TestEntityManager em;

    // ---------------------------------------------------------------- findDue

    @Test
    void findDue_includesClosedIntervalBoundaries_excludesOutOfWindowDisabledAndOtherFrequency() {
        LocalTime end = LocalTime.of(21, 0);            // now
        LocalTime start = end.minusMinutes(10);         // now-10 = 20:50

        Long atEnd = save(1L, ReminderFrequency.DAILY, LocalTime.of(21, 0), true);     // == end 边界，入选
        Long atStart = save(2L, ReminderFrequency.DAILY, LocalTime.of(20, 50), true);  // == start 边界，入选
        Long inside = save(3L, ReminderFrequency.DAILY, LocalTime.of(20, 55), true);   // 窗口内，入选
        save(4L, ReminderFrequency.DAILY, LocalTime.of(20, 49), true);                 // 早于 start，不入选
        save(5L, ReminderFrequency.DAILY, LocalTime.of(21, 1), true);                  // 晚于 end，不入选
        save(6L, ReminderFrequency.DAILY, LocalTime.of(20, 55), false);                // 停用，不入选
        save(7L, ReminderFrequency.WEEKEND, LocalTime.of(20, 55), true);               // 频率不在集合，不入选
        Long weekdayInside = save(8L, ReminderFrequency.WEEKDAY, LocalTime.of(20, 55), true); // 频率在集合，入选
        em.flush();

        // 模拟工作日：命中频率集合 {DAILY, WEEKDAY}（WEEKEND 应被排除）。
        List<CustomReminder> due = reminderRepository.findDue(
                Set.of(ReminderFrequency.DAILY, ReminderFrequency.WEEKDAY), start, end);

        assertThat(due).extracting(CustomReminder::getId)
                .containsExactlyInAnyOrder(atEnd, atStart, inside, weekdayInside);
    }

    // -------------------------------------------------------------- addCapped

    @Test
    void addCapped_insertsMinDeltaAndCap_whenRowAbsent() {
        quotaRepository.addCapped(100L, 3, NOW);
        em.clear();
        assertThat(quotaRepository.findRemaining(100L)).contains(3);

        // delta 超过上限 50 → 插入值被夹到 50。
        quotaRepository.addCapped(101L, 60, NOW);
        em.clear();
        assertThat(quotaRepository.findRemaining(101L)).contains(50);
    }

    @Test
    void addCapped_accumulatesAndCapsAtFifty_whenRowPresent() {
        quotaRepository.addCapped(200L, 10, NOW);
        em.clear();
        quotaRepository.addCapped(200L, 5, NOW);
        em.clear();
        assertThat(quotaRepository.findRemaining(200L)).contains(15);

        // 48 + 5 = 53 → 夹到 50。
        quotaRepository.addCapped(201L, 48, NOW);
        em.clear();
        quotaRepository.addCapped(201L, 5, NOW);
        em.clear();
        assertThat(quotaRepository.findRemaining(201L)).contains(50);
    }

    // ------------------------------------------------------- decrementFloorZero

    @Test
    void decrementFloorZero_decrementsByOne_whenPositive() {
        quotaRepository.addCapped(300L, 3, NOW);
        em.clear();

        int affected = quotaRepository.decrementFloorZero(300L, NOW);
        em.clear();

        assertThat(affected).isEqualTo(1);
        assertThat(quotaRepository.findRemaining(300L)).contains(2);
    }

    @Test
    void decrementFloorZero_doesNotGoNegative_whenAlreadyZero() {
        // 直接建一行 remaining=0（应用赋值主键，无 @GeneratedValue）。
        ReminderQuota zeroQuota = new ReminderQuota();
        zeroQuota.setUserId(301L);
        zeroQuota.setRemaining(0);
        zeroQuota.setCreatedAt(NOW);
        zeroQuota.setUpdatedAt(NOW);
        quotaRepository.save(zeroQuota);
        em.flush();
        em.clear();

        int affected = quotaRepository.decrementFloorZero(301L, NOW);
        em.clear();

        assertThat(affected).isZero();                              // 条件 remaining>0 不满足，未更新
        assertThat(quotaRepository.findRemaining(301L)).contains(0); // 仍为 0，不变负
    }

    // ----------------------------------------------------------- 唯一键冲突

    @Test
    void customReminders_uniqueKey_throwsDataIntegrityViolation() {
        save(400L, ReminderFrequency.DAILY, LocalTime.of(9, 0), true);
        em.flush();

        // 同 (user_id, frequency, remind_time) 再插一条 → 撞 uk_custom_reminders_user_freq_time。
        assertThatThrownBy(() -> {
            save(400L, ReminderFrequency.DAILY, LocalTime.of(9, 0), false);
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void reminderSendLogs_uniqueKey_throwsDataIntegrityViolation() {
        LocalDate triggerDate = LocalDate.parse("2025-06-01");
        sendLogRepository.save(sendLog(500L, 900L, triggerDate));
        em.flush();

        // 同 (reminder_id, trigger_date) 再插一条 → 撞 uk_reminder_send_logs_reminder_date。
        assertThatThrownBy(() -> {
            sendLogRepository.save(sendLog(500L, 900L, triggerDate));
            em.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------- fixtures

    private Long save(Long userId, ReminderFrequency frequency, LocalTime remindTime, boolean enabled) {
        CustomReminder r = new CustomReminder();
        r.setUserId(userId);
        r.setFrequency(frequency);
        r.setRemindTime(remindTime);
        r.setEnabled(enabled);
        r.setCreatedAt(NOW);
        r.setUpdatedAt(NOW);
        return reminderRepository.save(r).getId();
    }

    private ReminderSendLog sendLog(Long reminderId, Long userId, LocalDate triggerDate) {
        ReminderSendLog log = new ReminderSendLog();
        log.setReminderId(reminderId);
        log.setUserId(userId);
        log.setTriggerDate(triggerDate);
        log.setResult(ReminderSendResult.SENT);
        log.setMessageVariant("DONE");
        log.setWxErrcode(0);
        log.setCreatedAt(NOW);
        return log;
    }
}
