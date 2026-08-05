package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.damien.youyu.service.ReminderScheduler;

/**
 * 注销与自定义提醒三表（{@code custom_reminders} / {@code reminder_quota} / {@code reminder_send_logs}）
 * 的联动集成测试（任务 9.2，需求 9.11、11.4）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)：真实 HTTP、真实过滤链与 JWT、真实
 * {@link com.damien.youyu.service.AccountDeletionService} 与 H2 持久化层。账号一律经
 * {@code /api/auth/email-login} 真实建立，注销一律经 {@code POST /api/me/delete} 走完
 * 「协作牵连拦截 → 二次验证 → 单事务级联硬删」全流程，因此「三表的删除（第 12.8 步）与删
 * {@code users} 行同处一个事务」这一点是被真正验证的。</p>
 *
 * <p>三表的行与真实运行产物同构，直接以 {@link JdbcTemplate} 预置（本任务只关心注销联动，不关心
 * 提醒 CRUD / 调度如何生成这些行）：{@code custom_reminders} 若干行 + {@code reminder_quota} 一行 +
 * {@code reminder_send_logs} 若干行。</p>
 *
 * <h2>两组断言</h2>
 * <ol>
 *   <li><b>注销后三表该用户行数均为 0、注销响应契约不变</b>（需求 9.11、11.4）：建了提醒 + 额度 +
 *       发送记录的用户注销后，三张表对该用户的行数全部为 0；且注销接口仍返回 204、空响应体——注销接口
 *       的响应字段集、HTTP 状态码与既有错误码不因三表删除步骤而改变。为坐实「响应不变」，另注销一名
 *       未建任何提醒数据的用户作为基线，逐项比对两者的状态码与响应体完全一致。</li>
 *   <li><b>不影响其它用户的提醒数据</b>：注销 A 后，B 在三张表的行数与全部列取值逐行不变。</li>
 * </ol>
 *
 * <p>本项目自定义提醒引入了首个 {@code @Scheduled} 调度任务（{@link ReminderScheduler}，cron 每分钟触发）。
 * 为使本集成测试确定性、不受调度扫描时机干扰（否则真实调度可能在断言窗口内向 {@code reminder_send_logs}
 * 写入行），对 {@link ReminderScheduler} 用 {@link MockitoBean} 替身空转，注销路径本身不经过调度器，
 * 故不影响本测试的被测对象。使用独立命名的内存库，避免污染其它共享内存库的切片测试。</p>
 *
 * <p>Validates: Requirements 9.11, 11.4</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-reminderdel-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建多个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class ReminderAccountDeletionIntegrationTest {

    /** custom_reminders 七列快照（全部列，用于零副作用逐行比对）。 */
    private static final String REMINDER_COLUMNS =
            "SELECT reminder_id, user_id, frequency, remind_time, enabled, created_at, updated_at "
                    + "FROM custom_reminders";

    /** reminder_quota 四列快照（全部列）。 */
    private static final String QUOTA_COLUMNS =
            "SELECT user_id, remaining, created_at, updated_at FROM reminder_quota";

    /** reminder_send_logs 八列快照（全部列）。 */
    private static final String LOG_COLUMNS =
            "SELECT id, reminder_id, user_id, trigger_date, result, message_variant, wx_errcode, created_at "
                    + "FROM reminder_send_logs";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VerificationCodeRepository verificationCodeRepository;

    /** 空转替身：避免真实分钟级调度在断言窗口内向三表写行，使本集成测试确定性。 */
    @MockitoBean
    private ReminderScheduler reminderScheduler;

    // ============ 1) 注销后三表行清零、响应契约不变（需求 9.11、11.4）============

    @Test
    void deletingUser_removesReminderRows_andKeepsDeleteContract() {
        String email = "reminder_del_ok@example.com";
        String token = registerAndLogin(email);
        long uid = userIdOf(email);
        seedReminderData(uid);

        assertThat(reminderCount(uid)).as("注销前存在提醒行").isPositive();
        assertThat(quotaCount(uid)).as("注销前存在额度行").isPositive();
        assertThat(logCount(uid)).as("注销前存在发送记录行").isPositive();

        // 基线：一名未建任何提醒数据的用户注销，作为「响应契约」的对照。
        String baselineEmail = "reminder_del_baseline@example.com";
        String baselineToken = registerAndLogin(baselineEmail);
        long baselineUid = userIdOf(baselineEmail);
        ResponseEntity<Map> baseline = postDelete(baselineToken, Map.of("code", freshDeleteCode(baselineEmail)));

        ResponseEntity<Map> deleted = postDelete(token, Map.of("code", freshDeleteCode(email)));

        // 注销接口的响应字段集、状态码不因三表删除步骤而改变：仍是 204 + 空响应体（需求 9.11、11.4）。
        assertThat(deleted.getStatusCode()).as("注销应成功: " + deleted).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deleted.getBody()).as("注销成功响应无响应体").isNull();
        assertThat(userRepository.findById(uid)).isEmpty();

        // 与「无提醒数据用户」的注销响应逐项一致：状态码与响应体完全相同（需求 11.4）。
        assertThat(deleted.getStatusCode()).isEqualTo(baseline.getStatusCode());
        assertThat(deleted.getBody()).isEqualTo(baseline.getBody());
        assertThat(userRepository.findById(baselineUid)).isEmpty();

        // 该用户在三张表的行数均为 0（需求 9.11）。
        assertThat(reminderCount(uid)).isZero();
        assertThat(quotaCount(uid)).isZero();
        assertThat(logCount(uid)).isZero();
    }

    // ============ 2) 不影响其它用户的提醒数据 ============

    @Test
    void deletingUser_doesNotTouchOtherUsersReminderData() {
        String emailA = "reminder_del_a@example.com";
        String emailB = "reminder_del_b@example.com";
        String tokenA = registerAndLogin(emailA);
        registerAndLogin(emailB);
        long idA = userIdOf(emailA);
        long idB = userIdOf(emailB);

        seedReminderData(idA);
        seedReminderData(idB);

        // 注销前 B 的三表快照（逐行、含全部列）。
        List<Map<String, Object>> bRemindersBefore = reminderSnapshot(idB);
        List<Map<String, Object>> bQuotaBefore = quotaSnapshot(idB);
        List<Map<String, Object>> bLogsBefore = logSnapshot(idB);
        assertThat(bRemindersBefore).isNotEmpty();
        assertThat(bQuotaBefore).isNotEmpty();
        assertThat(bLogsBefore).isNotEmpty();

        deleteAccountExpectingSuccess(tokenA, emailA);

        // A 三表清零、B 一列未动。
        assertThat(reminderCount(idA)).isZero();
        assertThat(quotaCount(idA)).isZero();
        assertThat(logCount(idA)).isZero();
        assertThat(reminderSnapshot(idB)).isEqualTo(bRemindersBefore);
        assertThat(quotaSnapshot(idB)).isEqualTo(bQuotaBefore);
        assertThat(logSnapshot(idB)).isEqualTo(bLogsBefore);
    }

    // ---------------------------------- 数据播种与快照 ----------------------------------

    /**
     * 直接以 {@link JdbcTemplate} 预置一名用户的提醒数据：两条 {@code custom_reminders}（启用 + 停用，
     * 频率/时间各异，满足唯一键）、一行 {@code reminder_quota}、两条 {@code reminder_send_logs}
     * （不同触发日，满足 {@code (reminder_id, trigger_date)} 唯一键）。
     */
    private void seedReminderData(long userId) {
        LocalDateTime ts = LocalDateTime.of(2025, 6, 3, 10, 0);

        long r1 = insertReminder(userId, "DAILY", LocalTime.of(21, 0), true, ts);
        long r2 = insertReminder(userId, "WEEKDAY", LocalTime.of(9, 30), false, ts);

        jdbcTemplate.update(
                "INSERT INTO reminder_quota (user_id, remaining, created_at, updated_at) VALUES (?, ?, ?, ?)",
                userId, 12, Timestamp.valueOf(ts), Timestamp.valueOf(ts));

        insertLog(userId, r1, LocalDate.of(2025, 6, 2), "SENT", "NOT_YET", 0, ts);
        insertLog(userId, r1, LocalDate.of(2025, 6, 3), "SENT", "DONE", 0, ts);
    }

    private long insertReminder(long userId, String frequency, LocalTime remindTime, boolean enabled,
            LocalDateTime ts) {
        jdbcTemplate.update(
                "INSERT INTO custom_reminders (user_id, frequency, remind_time, enabled, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                userId, frequency, Time(remindTime), enabled ? 1 : 0,
                Timestamp.valueOf(ts), Timestamp.valueOf(ts));
        Long id = jdbcTemplate.queryForObject(
                "SELECT reminder_id FROM custom_reminders WHERE user_id = ? AND frequency = ? AND remind_time = ?",
                Long.class, userId, frequency, Time(remindTime));
        return id == null ? 0L : id;
    }

    private void insertLog(long userId, long reminderId, LocalDate triggerDate, String result,
            String variant, Integer errcode, LocalDateTime ts) {
        jdbcTemplate.update(
                "INSERT INTO reminder_send_logs (reminder_id, user_id, trigger_date, result, message_variant, "
                        + "wx_errcode, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                reminderId, userId, Date.valueOf(triggerDate), result, variant, errcode, Timestamp.valueOf(ts));
    }

    private static java.sql.Time Time(LocalTime t) {
        return java.sql.Time.valueOf(t);
    }

    private long reminderCount(long userId) {
        return count("custom_reminders", userId);
    }

    private long quotaCount(long userId) {
        return count("reminder_quota", userId);
    }

    private long logCount(long userId) {
        return count("reminder_send_logs", userId);
    }

    private long count(String table, long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    private List<Map<String, Object>> reminderSnapshot(long userId) {
        return jdbcTemplate.queryForList(REMINDER_COLUMNS + " WHERE user_id = ? ORDER BY reminder_id", userId);
    }

    private List<Map<String, Object>> quotaSnapshot(long userId) {
        return jdbcTemplate.queryForList(QUOTA_COLUMNS + " WHERE user_id = ?", userId);
    }

    private List<Map<String, Object>> logSnapshot(long userId) {
        return jdbcTemplate.queryForList(LOG_COLUMNS + " WHERE user_id = ? ORDER BY id", userId);
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    private ResponseEntity<Map> postDelete(String token, Map<String, String> payload) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url("/api/me/delete"), HttpMethod.POST,
                new HttpEntity<>(payload, headers), Map.class);
    }

    // ---------------------------------- 账号辅助 ----------------------------------

    private String registerAndLogin(String email) {
        verificationCodeRepository.deleteByEmail(email);
        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "LOGIN"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, String> payload = new HashMap<>();
        payload.put("email", email);
        payload.put("code", latestCode(email, EmailCodePurpose.LOGIN));
        ResponseEntity<Map> login = rest.postForEntity(url("/api/auth/email-login"), payload, Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) body(login).get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    /** 走完整注销流程并断言成功（204 + {@code users} 行消失）。 */
    private void deleteAccountExpectingSuccess(String token, String email) {
        long userId = userIdOf(email);
        ResponseEntity<Map> deleted = postDelete(token, Map.of("code", freshDeleteCode(email)));
        assertThat(deleted.getStatusCode()).as("注销应成功: " + deleted).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(userRepository.findById(userId)).isEmpty();
    }

    private String freshDeleteCode(String email) {
        verificationCodeRepository.deleteByEmail(email);
        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "DELETE"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        return latestCode(email, EmailCodePurpose.DELETE);
    }

    private String latestCode(String email, EmailCodePurpose purpose) {
        return verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(email, purpose)
                .orElseThrow(() -> new AssertionError("验证码未生成: " + email + "/" + purpose))
                .getCode();
    }

    private long userIdOf(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new AssertionError("用户未建立: " + email))
                .getId();
    }
}
