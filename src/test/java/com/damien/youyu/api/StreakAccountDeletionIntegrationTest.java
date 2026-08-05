package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.StreakSegmentRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;

/**
 * 注销与历史连续区间（{@code streak_segments}）的联动集成测试（任务 7.3，需求 8.8、8.9）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)：真实 HTTP、真实过滤链与 JWT、真实
 * {@link com.damien.youyu.service.AccountDeletionService} 与 H2 持久化层。账号一律经
 * {@code /api/auth/email-login} 真实建立，注销一律经 {@code POST /api/me/delete} 走完
 * 「协作牵连拦截 → 二次验证 → 单事务级联硬删」全流程，因此「段行的删除（第 12.7 步）与删 {@code users}
 * 行同处一个事务」这一点是被真正验证的。</p>
 *
 * <p>段行与成长数据一并直接以 {@link JdbcTemplate} 预置（本任务只关心注销联动，不关心结算如何生成这些行）：
 * {@code user_growth} 一行 + 若干 {@code growth_events} 行 + 若干 {@code streak_segments} 行，
 * 取值与真实结算产物同构，便于逐列快照断言。</p>
 *
 * <h2>五组断言</h2>
 * <ol>
 *   <li><b>注销后段行清零、响应契约不变</b>（需求 8.8）：有段行的用户注销后 {@code streak_segments}
 *       对该用户的行数为 0、全表无「反查 {@code users.id} 不存在」的孤儿行；且注销接口仍返回 204、
 *       空响应体——注销接口的响应字段集、HTTP 状态码与既有错误码不因段删除步骤而改变。</li>
 *   <li><b>段删除失败整事务回滚</b>（需求 8.9）：让 {@code streakSegmentRepository.deleteByUserId}
 *       抛错，断言注销失败，且 {@code users}、{@code user_growth}、{@code growth_events} 与
 *       {@code streak_segments} 四表全列快照与注销前逐行相等（要么整体成功、要么整体失败，不留半个账号）。</li>
 *   <li><b>无段行时影响 0 且不中止注销</b>（需求 8.8）：从未落过段的用户注销照常返回 204、
 *       {@code users} 行消失——若「影响行数 0」被当成失败而中止事务，{@code users} 行会留下来。</li>
 *   <li><b>前置校验失败零副作用</b>：{@code verifySecondFactor}（错误的注销验证码）与
 *       {@code requireDeletable}（协作账本仍有他人成员 → {@code DELETE_BLOCKED_COLLAB}）两条路径下，
 *       {@code streak_segments} 全表七列快照逐行不变、该用户仍存在。</li>
 *   <li><b>不影响其它用户的段</b>：注销 A 后 B 在 {@code streak_segments} 的行数与全部列取值逐行不变。</li>
 * </ol>
 *
 * <p>{@code streakSegmentRepository.deleteByUserId} 抛错只能靠替身制造（真实路径下这条 DELETE 不会失败），
 * 故对 {@link StreakSegmentRepository} 用 {@link MockitoSpyBean}：未打桩时全部方法委托真实实现，其余四组
 * 断言因此仍走真实仓储。使用独立命名的内存库，避免污染其它共享内存库的切片测试。</p>
 *
 * <p>Validates: Requirements 8.8, 8.9</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-streakdel-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建多个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class StreakAccountDeletionIntegrationTest {

    /** streak_segments 七列快照（全部列，用于回滚 / 零副作用逐行比对）。 */
    private static final String SEGMENT_COLUMNS =
            "SELECT id, user_id, start_date, end_date, days, created_at, updated_at FROM streak_segments";

    /** growth_events 六列快照（全部列）。 */
    private static final String EVENT_COLUMNS =
            "SELECT id, user_id, event_type, event_key, exp_amount, created_at FROM growth_events";

    /** user_growth 十列快照（全部列）。 */
    private static final String PROFILE_COLUMNS =
            "SELECT user_id, exp, level, total_record_days, current_streak_days, max_streak_days, "
                    + "last_record_date, last_settled_at, created_at, updated_at FROM user_growth";

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

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private LedgerMemberRepository ledgerMemberRepository;

    /** 未打桩时委托真实仓储；仅第 2 组断言对 {@code deleteByUserId} 打桩抛错。 */
    @MockitoSpyBean
    private StreakSegmentRepository streakSegmentRepository;

    // ============ 1) 注销后段行清零、无孤儿行、响应契约不变（需求 8.8）============

    @Test
    void deletingUser_removesSegmentRows_leavesNoOrphans_andKeepsDeleteContract() {
        String email = "streak_del_ok@example.com";
        String token = registerAndLogin(email);
        long uid = userIdOf(email);
        seedGrowth(uid);
        seedSegments(uid);

        assertThat(segmentCount(uid)).as("注销前存在段行").isPositive();

        ResponseEntity<Map> deleted = postDelete(token, Map.of("code", freshDeleteCode(email)));

        // 注销接口的响应字段集、状态码不因段删除步骤而改变：仍是 204 + 空响应体（需求 8.8）。
        assertThat(deleted.getStatusCode()).as("注销应成功: " + deleted).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deleted.getBody()).as("注销成功响应无响应体").isNull();
        assertThat(userRepository.findById(uid)).isEmpty();

        // 该用户在段表的行数为 0（需求 8.8）。
        assertThat(segmentCount(uid)).isZero();
        // 全表无「反查 users.id 不存在」的孤儿段行。
        assertThat(orphanSegments()).isZero();
    }

    // ============ 2) 段删除失败：四表整事务回滚（需求 8.9）============

    @Test
    void segmentDeletionFailure_rollsBackWholeDeletion_restoringFourTables() {
        String email = "streak_del_rb@example.com";
        String token = registerAndLogin(email);
        long uid = userIdOf(email);
        seedGrowth(uid);
        seedSegments(uid);

        // 注销前四表全列快照。
        User before = userRepository.findById(uid).orElseThrow();
        Long idBefore = before.getId();
        String emailBefore = before.getEmail();
        String openidBefore = before.getWxOpenid();
        String nicknameBefore = before.getNickname();
        String inviteCodeBefore = before.getInviteCode();
        List<Map<String, Object>> profileBefore = profileSnapshot(uid);
        List<Map<String, Object>> eventsBefore = eventsSnapshot(uid);
        List<Map<String, Object>> segmentsBefore = segmentsSnapshot(uid);

        // 让第 12.7 步（段硬删）抛错（真实路径下这条 DELETE 不会失败，只能靠替身制造）。
        doThrow(new DataIntegrityViolationException("模拟 streak_segments 删除失败"))
                .when(streakSegmentRepository).deleteByUserId(eq(uid));

        ResponseEntity<Map> failed = postDelete(token, Map.of("code", freshDeleteCode(email)));
        assertThat(failed.getStatusCode().is5xxServerError()).as("注销应失败: " + failed).isTrue();

        // users 行整体回滚：五列与注销前相同（需求 8.9）。
        User after = userRepository.findById(uid).orElseThrow(
                () -> new AssertionError("注销事务应整体回滚，users 行不应被删除"));
        assertThat(after.getId()).isEqualTo(idBefore);
        assertThat(after.getEmail()).isEqualTo(emailBefore);
        assertThat(after.getWxOpenid()).isEqualTo(openidBefore);
        assertThat(after.getNickname()).isEqualTo(nicknameBefore);
        assertThat(after.getInviteCode()).isEqualTo(inviteCodeBefore);

        // user_growth / growth_events / streak_segments 三表逐行快照与注销前相同（需求 8.9）。
        assertThat(profileSnapshot(uid)).isEqualTo(profileBefore);
        assertThat(eventsSnapshot(uid)).isEqualTo(eventsBefore);
        assertThat(segmentsSnapshot(uid)).isEqualTo(segmentsBefore);
    }

    // ============ 3) 无段行时影响 0 且不中止注销（需求 8.8）============

    @Test
    void deletingUserWithoutSegments_succeeds_andDoesNotAbortTheSequence() {
        String email = "streak_del_noseg@example.com";
        String token = registerAndLogin(email);
        long uid = userIdOf(email);
        seedGrowth(uid);
        // 刻意不落任何段行：该用户没有 streak_segments。
        assertThat(segmentCount(uid)).isZero();

        deleteAccountExpectingSuccess(token, email);

        // 「影响行数 0 即视为成功」：若被当成失败而中止/回滚，users 行会留下来（需求 8.8）。
        assertThat(userRepository.findByEmail(email)).isEmpty();
        assertThat(segmentCount(uid)).isZero();
    }

    // ============ 4) 前置校验失败：段表零副作用（需求 8.8/8.9 的前置门禁）============

    @Test
    void preflightFailures_leaveSegmentTableUntouched() {
        String email = "streak_del_pre@example.com";
        String token = registerAndLogin(email);
        long uid = userIdOf(email);
        seedGrowth(uid);
        seedSegments(uid);

        // --- 4a) 二次验证失败（错误的注销验证码）：requireDeletable 已通过，卡在 verifySecondFactor ---
        List<Map<String, Object>> beforeBadCode = allSegments();
        ResponseEntity<Map> badCode = postDelete(token, Map.of("code", "000000"));
        assertThat(badCode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(badCode)).containsEntry("code", "CODE_INVALID");
        assertThat(allSegments()).as("二次验证失败后段表全表七列逐行不变").isEqualTo(beforeBadCode);

        // --- 4b) 协作牵连拦截：拥有的协作账本仍有他人成员 → DELETE_BLOCKED_COLLAB ---
        seedCollaborativeLedgerWithOtherMember(uid);
        List<Map<String, Object>> beforeCollab = allSegments();
        ResponseEntity<Map> blocked = postDelete(token, Map.of("code", freshDeleteCode(email)));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(blocked)).containsEntry("code", "DELETE_BLOCKED_COLLAB");
        assertThat(allSegments()).as("协作牵连拦截后段表全表七列逐行不变").isEqualTo(beforeCollab);

        // 两条路径下该用户仍存在，段行也还在。
        assertThat(userRepository.findById(uid)).isPresent();
        assertThat(segmentCount(uid)).isPositive();
    }

    // ============ 5) 不影响其它用户的段 ============

    @Test
    void deletingUser_doesNotTouchOtherUsersSegments() {
        String emailA = "streak_del_a@example.com";
        String emailB = "streak_del_b@example.com";
        String tokenA = registerAndLogin(emailA);
        registerAndLogin(emailB);
        long idA = userIdOf(emailA);
        long idB = userIdOf(emailB);

        seedGrowth(idA);
        seedSegments(idA);
        seedGrowth(idB);
        seedSegments(idB);

        // 注销前 B 的段快照（逐行、含全部列）。
        List<Map<String, Object>> bSegmentsBefore = segmentsSnapshot(idB);
        assertThat(bSegmentsBefore).isNotEmpty();

        deleteAccountExpectingSuccess(tokenA, emailA);

        // A 清零、B 一列未动。
        assertThat(segmentCount(idA)).isZero();
        assertThat(segmentsSnapshot(idB)).isEqualTo(bSegmentsBefore);
        assertThat(orphanSegments()).isZero();
    }

    // ---------------------------------- 数据播种与快照 ----------------------------------

    /**
     * 直接以 {@link JdbcTemplate} 预置一名用户的成长数据（{@code user_growth} 一行 + 两条
     * {@code growth_events}），取值与真实结算产物同构，供注销后「四表全列还原」的逐列快照断言使用。
     */
    private void seedGrowth(long userId) {
        LocalDate day = LocalDate.of(2025, 6, 3);
        LocalDateTime ts = LocalDateTime.of(2025, 6, 3, 10, 0);
        insertEvent(userId, GrowthEventType.FIRST_RECORD, "FIRST_RECORD", 10, ts);
        insertEvent(userId, GrowthEventType.DAILY_RECORD, "DAILY_RECORD:" + day, 5, ts);

        jdbcTemplate.update(
                "INSERT INTO user_growth (user_id, exp, level, total_record_days, current_streak_days, "
                        + "max_streak_days, last_record_date, last_settled_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                userId, 15L, 2, 3, 1, 2,
                Date.valueOf(day), Timestamp.valueOf(ts), Timestamp.valueOf(ts), Timestamp.valueOf(ts));
    }

    private void insertEvent(long userId, String type, String key, int exp, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                userId, type, key, exp, Timestamp.valueOf(createdAt));
    }

    /**
     * 直插两条段行（互不相邻、互不相交，与不变式一致）：一段 2 天、一段 1 天。段的写入正常只走
     * {@code StreakSegmentMaintainer} 的 ODKU；本类只验注销联动，直插即可。
     */
    private void seedSegments(long userId) {
        LocalDateTime ts = LocalDateTime.of(2025, 6, 3, 10, 0);
        insertSegment(userId, LocalDate.of(2025, 5, 20), LocalDate.of(2025, 5, 21), 2, ts);
        insertSegment(userId, LocalDate.of(2025, 6, 3), LocalDate.of(2025, 6, 3), 1, ts);
    }

    private void insertSegment(long userId, LocalDate start, LocalDate end, int days, LocalDateTime ts) {
        jdbcTemplate.update(
                "INSERT INTO streak_segments (user_id, start_date, end_date, days, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                userId, Date.valueOf(start), Date.valueOf(end), days,
                Timestamp.valueOf(ts), Timestamp.valueOf(ts));
    }

    private long segmentCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streak_segments WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    /** 反查 {@code users.id} 不存在的段行数（孤儿行对账）。 */
    private long orphanSegments() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streak_segments s "
                        + "WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = s.user_id)", Long.class);
        return n == null ? 0L : n;
    }

    private List<Map<String, Object>> allSegments() {
        return jdbcTemplate.queryForList(SEGMENT_COLUMNS + " ORDER BY id");
    }

    private List<Map<String, Object>> segmentsSnapshot(long userId) {
        return jdbcTemplate.queryForList(SEGMENT_COLUMNS + " WHERE user_id = ? ORDER BY id", userId);
    }

    private List<Map<String, Object>> eventsSnapshot(long userId) {
        return jdbcTemplate.queryForList(EVENT_COLUMNS + " WHERE user_id = ? ORDER BY id", userId);
    }

    private List<Map<String, Object>> profileSnapshot(long userId) {
        return jdbcTemplate.queryForList(PROFILE_COLUMNS + " WHERE user_id = ?", userId);
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

    /** 给指定用户造一个「仍有他人成员」的协作账本，触发 {@code DELETE_BLOCKED_COLLAB}。 */
    private void seedCollaborativeLedgerWithOtherMember(long ownerId) {
        LocalDateTime now = LocalDateTime.now();
        Ledger ledger = new Ledger();
        ledger.setUserId(ownerId);
        ledger.setName("协作账本");
        ledger.setType(Ledger.TYPE_COLLABORATIVE);
        ledger.setDefault(false);
        ledger.setCreatedAt(now);
        ledger.setUpdatedAt(now);
        ledger = ledgerRepository.saveAndFlush(ledger);

        LedgerMember other = new LedgerMember();
        other.setLedgerId(ledger.getId());
        other.setUserId(ownerId + 100_000L);   // 任意「他人」id：requireDeletable 只看 user_id != 本人
        other.setRole(LedgerMember.ROLE_EDITOR);
        other.setCreatedAt(now);
        ledgerMemberRepository.saveAndFlush(other);
    }
}
