package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import com.damien.youyu.domain.CustomReminder;
import com.damien.youyu.domain.ReminderFrequency;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.CustomReminderRepository;
import com.damien.youyu.repository.ReminderQuotaRepository;

/**
 * {@link ReminderService} 的服务层示例/边界单元测试（任务 4.4，关联需求 1.5、1.6、1.9、5.3、5.4、5.8、
 * 7.2、7.3、7.5）。
 *
 * <p>走 {@code @DataJpaTest} + H2（{@code MODE=MySQL}）连真实的两个仓储与固定 {@link Clock}，
 * 服务本体经 {@link Import} 注入（与 {@link ReminderRepositoryTest} 同一切片口径）。这些断言全是
 * <b>校验优先级短路、库侧唯一键去重、条件更新落库、上限夹取</b>等真实行为，用测试替身会把被测机制删掉，
 * 因此在真实 H2 连接上跑。并发 {@code addCapped} 不丢更新须真实提交多事务，另置于
 * {@link ReminderQuotaConcurrencyTest}（{@code @SpringBootTest}，见该类）。</p>
 *
 * <ul>
 *   <li>{@code create}：校验优先级 {@code FREQUENCY > TIME > DUPLICATE > LIMIT}（需求 1.9）、
 *       10 条上限（需求 1.6）、时间边界与大小写频率（需求 1.3、1.4）、同频同时去重（需求 1.5）。</li>
 *   <li>{@code update}：只写提交字段、其余保持原值（需求 7.3）；不存在与不属于本人归一 {@code NOT_FOUND}
 *       （需求 7.5）；改动后撞本人另一条 → {@code REMINDER_DUPLICATE}（需求 7.8）。</li>
 *   <li>{@code grantQuota}：上限 50（需求 5.3）、非法值 {@code REMINDER_GRANT_INVALID} 且额度不变（需求 5.4）。</li>
 *   <li>{@code list}：空列表与额度默认 0（需求 7.2、5.7）。</li>
 * </ul>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ReminderService.class)
class ReminderServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 固定时钟：判定日恒为 2025-06-01T21:00（Asia/Shanghai），created_at/updated_at 可复现。 */
    private static final LocalDateTime NOW = LocalDateTime.of(2025, 6, 1, 21, 0);

    private static final long USER = 42L;
    private static final long OTHER_USER = 99L;

    @Autowired private ReminderService service;
    @Autowired private CustomReminderRepository reminderRepository;
    @Autowired private ReminderQuotaRepository quotaRepository;
    @Autowired private TestEntityManager em;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(ZONE).toInstant(), ZONE);
        }
    }

    // ============================================================ create：校验优先级（需求 1.9）

    /** 同时命中频率非法 + 时间非法：只返回优先级最高的 {@code REMINDER_FREQUENCY_INVALID}。 */
    @Test
    void create_frequencyBeatsTime_whenBothInvalid() {
        assertCode(() -> service.create(USER, "daily", "08:60", true), "REMINDER_FREQUENCY_INVALID");
        assertThat(reminderRepository.countByUserId(USER)).isZero();   // 表数据不变
    }

    /** 频率合法但时间非法，且已存在同频同时的提醒：时间优先于去重，返回 {@code REMINDER_TIME_INVALID}。 */
    @Test
    void create_timeBeatsDuplicate_whenTimeInvalid() {
        service.create(USER, "DAILY", "09:00", true);                  // 先占一条，构造潜在 DUPLICATE
        // 频率合法、时间非法（24:00）→ 时间校验先于去重短路。
        assertCode(() -> service.create(USER, "DAILY", "24:00", true), "REMINDER_TIME_INVALID");
        assertThat(reminderRepository.countByUserId(USER)).isEqualTo(1);
    }

    /** 已达上限且提交的又是重复项：去重优先于上限，返回 {@code REMINDER_DUPLICATE}。 */
    @Test
    void create_duplicateBeatsLimit_whenBothHit() {
        // 建满 10 条（其中一条为 DAILY 00:00），使 LIMIT 与 DUPLICATE 同时可命中。
        seedReminders(USER, 10);
        // 提交与首条完全相同（DAILY 00:00）→ 去重先于上限短路。
        assertCode(() -> service.create(USER, "DAILY", "00:00", true), "REMINDER_DUPLICATE");
        assertThat(reminderRepository.countByUserId(USER)).isEqualTo(10);
    }

    // ============================================================ create：10 条上限（需求 1.6）

    /** 已有 10 条时拒绝创建第 11 条：{@code REMINDER_LIMIT_EXCEEDED}，表数据不变。 */
    @Test
    void create_rejectsEleventh_whenAtLimit() {
        seedReminders(USER, 10);
        // 提交一条全新的（不与任何已存在项撞）→ 唯有上限命中。
        assertCode(() -> service.create(USER, "WEEKEND", "23:59", true), "REMINDER_LIMIT_EXCEEDED");
        assertThat(reminderRepository.countByUserId(USER)).isEqualTo(10);
    }

    /** 上限是每用户维度：本人满 10 条不影响他人创建。 */
    @Test
    void create_limitIsPerUser() {
        seedReminders(USER, 10);
        assertThatCode(() -> service.create(OTHER_USER, "DAILY", "09:00", true)).doesNotThrowAnyException();
        assertThat(reminderRepository.countByUserId(OTHER_USER)).isEqualTo(1);
    }

    // ============================================================ create：时间边界（需求 1.4）

    /** 非法时间一律 {@code REMINDER_TIME_INVALID}：24:00 越界、8:00 非零填充、08:60 分钟越界、空、含秒。 */
    @ParameterizedTest
    @ValueSource(strings = {"24:00", "8:00", "08:60", "", " ", "0900", "09:00:00", "23:60", "24:01"})
    void create_rejectsInvalidTime(String badTime) {
        assertCode(() -> service.create(USER, "DAILY", badTime, true), "REMINDER_TIME_INVALID");
        assertThat(reminderRepository.countByUserId(USER)).isZero();
    }

    /** 时间缺失（null）→ {@code REMINDER_TIME_INVALID}。 */
    @Test
    void create_rejectsNullTime() {
        assertCode(() -> service.create(USER, "DAILY", null, true), "REMINDER_TIME_INVALID");
        assertThat(reminderRepository.countByUserId(USER)).isZero();
    }

    /** 合法边界 00:00 与 23:59 均被接受。 */
    @ParameterizedTest
    @ValueSource(strings = {"00:00", "23:59", "09:30"})
    void create_acceptsValidTimeBoundaries(String goodTime) {
        ReminderItem item = service.create(USER, "DAILY", goodTime, true);
        assertThat(item.remindTime()).isEqualTo(goodTime);
    }

    // ============================================================ create：大小写频率（需求 1.3）

    /** 频率区分大小写：小写 daily / 空 / 非枚举一律 {@code REMINDER_FREQUENCY_INVALID}。 */
    @ParameterizedTest
    @ValueSource(strings = {"daily", "Daily", "WEEK", "", " ", "DAILY "})
    @NullSource
    void create_rejectsInvalidFrequency(String badFreq) {
        assertCode(() -> service.create(USER, badFreq, "09:00", true), "REMINDER_FREQUENCY_INVALID");
        assertThat(reminderRepository.countByUserId(USER)).isZero();
    }

    // ============================================================ create：同频同时去重（需求 1.5）

    /** 同频率同时间不重复创建（无论已存在项启用与否）：第二次 {@code REMINDER_DUPLICATE}，表数据不变。 */
    @Test
    void create_rejectsDuplicate_regardlessOfEnabled() {
        service.create(USER, "DAILY", "09:00", false);                 // 已存在项为停用
        assertCode(() -> service.create(USER, "DAILY", "09:00", true), "REMINDER_DUPLICATE");
        assertThat(reminderRepository.countByUserId(USER)).isEqualTo(1);
    }

    /** 缺省启用（需求 1.1）：enabled 传 null 落库为 true。 */
    @Test
    void create_defaultsEnabledToTrue_whenNull() {
        ReminderItem item = service.create(USER, "DAILY", "09:00", null);
        assertThat(item.enabled()).isTrue();
    }

    // ============================================================ update：部分更新（需求 7.3）

    /** 只提交 enabled：频率与时间保持原值，仅启用状态改变。 */
    @Test
    void update_onlyEnabled_keepsFrequencyAndTime() {
        ReminderItem created = service.create(USER, "DAILY", "09:00", true);

        ReminderItem updated = service.update(USER, created.reminderId(), null, null, false);

        assertThat(updated.frequency()).isEqualTo("DAILY");
        assertThat(updated.remindTime()).isEqualTo("09:00");
        assertThat(updated.enabled()).isFalse();
    }

    /** 只提交 remindTime：频率与启用状态保持原值。 */
    @Test
    void update_onlyTime_keepsFrequencyAndEnabled() {
        ReminderItem created = service.create(USER, "WEEKDAY", "09:00", false);

        ReminderItem updated = service.update(USER, created.reminderId(), null, "21:30", null);

        assertThat(updated.frequency()).isEqualTo("WEEKDAY");
        assertThat(updated.remindTime()).isEqualTo("21:30");
        assertThat(updated.enabled()).isFalse();
    }

    /** 更新校验失败（时间非法）：整行保持不变（需求 7.4）。 */
    @Test
    void update_invalidTime_leavesRowUnchanged() {
        ReminderItem created = service.create(USER, "DAILY", "09:00", true);

        assertCode(() -> service.update(USER, created.reminderId(), null, "24:00", false),
                "REMINDER_TIME_INVALID");

        em.clear();
        CustomReminder row = reminderRepository.findById(created.reminderId()).orElseThrow();
        assertThat(row.getFrequency()).isEqualTo(ReminderFrequency.DAILY);
        assertThat(row.getRemindTime()).isEqualTo(LocalTime.of(9, 0));
        assertThat(row.isEnabled()).isTrue();                          // enabled 未被改动
    }

    // ============================================================ update：NOT_FOUND 归属（需求 7.5、8.8）

    /** 更新不存在的提醒 → {@code NOT_FOUND}。 */
    @Test
    void update_nonexistentId_returnsNotFound() {
        assertCode(() -> service.update(USER, 123456L, "DAILY", "09:00", true), "NOT_FOUND");
    }

    /** 更新他人的提醒 → 与不存在完全相同的 {@code NOT_FOUND}（不泄漏他人提醒是否存在）。 */
    @Test
    void update_othersReminder_returnsSameNotFound() {
        ReminderItem othersReminder = service.create(OTHER_USER, "DAILY", "09:00", true);

        ApiException nonexistent = catchApi(() -> service.update(USER, 123456L, "DAILY", "10:00", true));
        ApiException othersOwned = catchApi(() -> service.update(USER, othersReminder.reminderId(), "DAILY", "10:00", true));

        assertThat(othersOwned.getCode()).isEqualTo(nonexistent.getCode()).isEqualTo("NOT_FOUND");
        assertThat(othersOwned.getMessage()).isEqualTo(nonexistent.getMessage());
        // 他人提醒整行保持不变。
        em.clear();
        CustomReminder row = reminderRepository.findById(othersReminder.reminderId()).orElseThrow();
        assertThat(row.getRemindTime()).isEqualTo(LocalTime.of(9, 0));
    }

    /** 删除他人的提醒 → 与不存在相同的 {@code NOT_FOUND}，他人行仍在。 */
    @Test
    void delete_othersReminder_returnsSameNotFound() {
        ReminderItem othersReminder = service.create(OTHER_USER, "DAILY", "09:00", true);

        ApiException nonexistent = catchApi(() -> service.delete(USER, 123456L));
        ApiException othersOwned = catchApi(() -> service.delete(USER, othersReminder.reminderId()));

        assertThat(othersOwned.getCode()).isEqualTo(nonexistent.getCode()).isEqualTo("NOT_FOUND");
        assertThat(reminderRepository.findById(othersReminder.reminderId())).isPresent();
    }

    // ============================================================ update：改动撞重复（需求 7.8）

    /** 更新后的频率与时间会撞本人另一条 → {@code REMINDER_DUPLICATE}，目标行保持不变。 */
    @Test
    void update_collidesWithAnotherOwnReminder_returnsDuplicate() {
        service.create(USER, "DAILY", "09:00", true);                  // A
        ReminderItem b = service.create(USER, "DAILY", "10:00", true); // B

        // 把 B 的时间改到 09:00 → 与 A 撞（同 user 同频同时）。
        assertCode(() -> service.update(USER, b.reminderId(), null, "09:00", null), "REMINDER_DUPLICATE");

        em.clear();
        CustomReminder row = reminderRepository.findById(b.reminderId()).orElseThrow();
        assertThat(row.getRemindTime()).isEqualTo(LocalTime.of(10, 0)); // B 整行不变
    }

    /** 更新为「自身当前组合」（无实际变化）不误判为重复。 */
    @Test
    void update_toOwnSameCombo_isNotDuplicate() {
        ReminderItem a = service.create(USER, "DAILY", "09:00", true);
        assertThatCode(() -> service.update(USER, a.reminderId(), "DAILY", "09:00", false))
                .doesNotThrowAnyException();
    }

    // ============================================================ grantQuota：上限与非法值（需求 5.3、5.4）

    /** 合法上报累加返回增加后的剩余次数。 */
    @Test
    void grantQuota_accumulates() {
        assertThat(service.grantQuota(USER, "5")).isEqualTo(5);
        assertThat(service.grantQuota(USER, "3")).isEqualTo(8);
    }

    /** 累加超过 50 被夹到上限 50（需求 5.3）。 */
    @Test
    void grantQuota_capsAtFifty() {
        quotaRepository.addCapped(USER, 48, NOW);                      // 先垫到 48
        em.clear();
        // 48 + 5 = 53 → 夹到 50。
        assertThat(service.grantQuota(USER, "5")).isEqualTo(50);
        // 已在上限再上报仍为 50。
        assertThat(service.grantQuota(USER, "5")).isEqualTo(50);
    }

    /** 非法授权次数一律 {@code REMINDER_GRANT_INVALID} 且额度不变（需求 5.4）。 */
    @ParameterizedTest
    @ValueSource(strings = {"0", "6", "-1", "abc", "", " ", "2.5", "10"})
    @NullSource
    void grantQuota_rejectsInvalid_andLeavesQuotaUnchanged(String badCount) {
        service.grantQuota(USER, "4");                                 // 先有 4
        assertCode(() -> service.grantQuota(USER, badCount), "REMINDER_GRANT_INVALID");
        assertThat(quotaRepository.findRemaining(USER)).contains(4);   // 不变
    }

    /** 合法边界 1 与 5 均被接受。 */
    @ParameterizedTest
    @ValueSource(strings = {"1", "5", " 3 "})
    void grantQuota_acceptsBoundaryValues(String goodCount) {
        assertThatCode(() -> service.grantQuota(USER, goodCount)).doesNotThrowAnyException();
    }

    // ============================================================ list：空列表与额度默认 0（需求 7.2、5.7）

    /** 无任何提醒且无授权记录：返回空列表 + 剩余额度 0（需求 7.2、5.7）。 */
    @Test
    void list_emptyAndZeroQuota_whenNoData() {
        ReminderListResponse response = service.list(USER);
        assertThat(response.reminders()).isEmpty();
        assertThat(response.remainingQuota()).isZero();
    }

    /** 列表仅含本人提醒、按 created_at 升序、每项 4 字段，并带真实剩余额度。 */
    @Test
    void list_returnsOwnRemindersAndQuota() {
        service.create(USER, "DAILY", "09:00", true);
        service.create(USER, "WEEKEND", "21:00", false);
        service.create(OTHER_USER, "DAILY", "09:00", true);            // 他人的，不应出现
        service.grantQuota(USER, "5");

        ReminderListResponse response = service.list(USER);

        assertThat(response.reminders()).hasSize(2)
                .extracting(ReminderItem::frequency)
                .containsExactly("DAILY", "WEEKEND");                  // created_at 升序
        assertThat(response.remainingQuota()).isEqualTo(5);
    }

    // ---------------------------------------------------------------- 辅助

    /** 建 count 条互不相同的提醒（DAILY 00:00、00:01、…），用于逼近 / 触达 10 条上限。 */
    private void seedReminders(long userId, int count) {
        for (int i = 0; i < count; i++) {
            service.create(userId, "DAILY", String.format("%02d:%02d", i / 60, i % 60), true);
        }
    }

    private void assertCode(Runnable action, String expectedCode) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getCode()).isEqualTo(expectedCode));
    }

    private ApiException catchApi(Runnable action) {
        try {
            action.run();
        } catch (ApiException ex) {
            return ex;
        }
        throw new AssertionError("期望抛出 ApiException，但未抛出");
    }
}
