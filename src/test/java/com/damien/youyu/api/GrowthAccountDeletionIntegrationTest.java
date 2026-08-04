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
import com.damien.youyu.repository.GrowthEventRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;

/**
 * 注销与成长数据的联动集成测试（任务 8.5，需求 12.1～12.7、12.11、11.21）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)：真实 HTTP、真实过滤链与 JWT、真实
 * {@link com.damien.youyu.service.AccountDeletionService}、真实 {@code GET /api/growth}
 * 与 H2 持久化层。账号一律经 {@code /api/auth/email-login} 真实建立，注销一律经
 * {@code POST /api/me/delete} 走完「协作牵连拦截 → 二次验证 → 单事务级联硬删」全流程，
 * 因此「先 growth_events、再 user_growth 硬删」与「删 users 行」同处一个事务这一点是被真正验证的。</p>
 *
 * <p>成长数据直接以 {@link JdbcTemplate} 预置（本任务只关心注销联动，不关心结算如何生成这些行）：
 * {@code user_growth} 一行 + 若干 {@code growth_events} 行，取值与真实结算产物同构（含 FIRST_RECORD /
 * DAILY_RECORD / STREAK / BADGE 四类），便于逐列快照断言。</p>
 *
 * <h2>五组断言</h2>
 * <ol>
 *   <li><b>注销后两表清零</b>（需求 12.1、12.2）：注销成功后该用户在 {@code growth_events} 与
 *       {@code user_growth} 两表的行数均为 0，且两表按 {@code user_id} 反查 {@code users.id} 不存在的
 *       行数为 0（需求 11.21 对账口径）。</li>
 *   <li><b>成长删除失败整事务回滚</b>（需求 12.4）：让 {@code growthEventRepository.deleteByUserId}
 *       抛错，断言注销失败、{@code users} 行五列与两表成长数据快照与注销前逐行相等，且注销前持有的令牌
 *       仍能成功请求 {@code GET /api/growth}。</li>
 *   <li><b>前置校验失败零副作用</b>（需求 12.5）：二次验证失败（错误的注销验证码）与
 *       {@code DELETE_BLOCKED_COLLAB}（协作账本仍有他人成员）两条路径下，两表全部行的列取值保持请求前状态。</li>
 *   <li><b>不触及他人成长与 invite_relations</b>（需求 12.6、12.7）：A 邀请 B 后注销 A，断言 B 的成长
 *       数据快照不变、{@code invite_relations} 全表七列快照不变（该表联动完全由既有 invite 逻辑负责，
 *       成长删除一行都不碰）。</li>
 *   <li><b>同邮箱重新注册从 Lv1</b>（需求 11.21、12.3）：注销后以同一邮箱重新注册（新 {@code users.id}），
 *       {@code GET /api/growth} 返回等级 1、9 枚徽章均未点亮；两表反查 {@code users.id} 不存在的行数为 0。</li>
 * </ol>
 *
 * <p>{@code growthEventRepository.deleteByUserId} 抛错只能靠替身制造（真实路径下这条 DELETE 不会失败），
 * 故对 {@link GrowthEventRepository} 用 {@link MockitoSpyBean}：未打桩时全部方法委托真实实现，其余四组
 * 断言因此仍走真实仓储。使用独立命名的内存库，避免污染其它共享内存库的切片测试。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-growthdel-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建多个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class GrowthAccountDeletionIntegrationTest {

    /** growth_events 六列快照（全部列，用于回滚 / 零副作用逐行比对）。 */
    private static final String EVENT_COLUMNS =
            "SELECT id, user_id, event_type, event_key, exp_amount, created_at FROM growth_events";

    /** user_growth 十列快照（全部列）。 */
    private static final String PROFILE_COLUMNS =
            "SELECT user_id, exp, level, total_record_days, current_streak_days, max_streak_days, "
                    + "last_record_date, last_settled_at, created_at, updated_at FROM user_growth";

    /** invite_relations 七列快照（含 updated_at）：需求 12.7 要求注销时成长删除不碰该表任何行。 */
    private static final String INVITE_SEVEN_COLUMNS =
            "SELECT invite_id, inviter_id, invitee_id, register_time, status, created_at, updated_at "
                    + "FROM invite_relations ORDER BY invite_id";

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
    private GrowthEventRepository growthEventRepository;

    // ============ 1) 注销后两表清零、无悬空 user_id（需求 12.1、12.2、11.21）============

    @Test
    void deletingUser_removesBothGrowthTablesRows_andLeavesNoOrphans() {
        String token = registerAndLogin("gdel_ok@example.com");
        long uid = userIdOf("gdel_ok@example.com");
        seedGrowth(uid);

        assertThat(growthEventCount(uid)).isPositive();
        assertThat(userGrowthCount(uid)).isEqualTo(1L);

        deleteAccountExpectingSuccess(token, "gdel_ok@example.com");

        // 该用户在两表的行数均为 0（需求 12.1、12.2）。
        assertThat(growthEventCount(uid)).isZero();
        assertThat(userGrowthCount(uid)).isZero();
        // 两表按 user_id 反查 users.id 不存在的行数为 0（需求 11.21 对账口径）。
        assertThat(orphanGrowthEvents()).isZero();
        assertThat(orphanUserGrowth()).isZero();
    }

    // ============ 2) 成长删除失败：整事务回滚、令牌仍可用（需求 12.4）============

    @Test
    void growthDeletionFailure_rollsBackWholeDeletion_andOriginalTokenStillWorks() {
        String token = registerAndLogin("gdel_rb@example.com");
        long uid = userIdOf("gdel_rb@example.com");
        seedGrowth(uid);

        // 注销前的 users 行五列与两表成长快照。
        User before = userRepository.findById(uid).orElseThrow();
        Long idBefore = before.getId();
        String emailBefore = before.getEmail();
        String openidBefore = before.getWxOpenid();
        String nicknameBefore = before.getNickname();
        String inviteCodeBefore = before.getInviteCode();
        List<Map<String, Object>> eventsBefore = eventsSnapshot(uid);
        List<Map<String, Object>> profileBefore = profileSnapshot(uid);

        // 让「先 growth_events」这一步硬删抛错（真实路径下不会失败，只能靠替身制造）。
        doThrow(new DataIntegrityViolationException("模拟 growth_events 删除失败"))
                .when(growthEventRepository).deleteByUserId(eq(uid));

        ResponseEntity<Map> failed = postDelete(token, Map.of("code", freshDeleteCode("gdel_rb@example.com")));
        assertThat(failed.getStatusCode().is5xxServerError()).as("注销应失败: " + failed).isTrue();

        // users 行整体回滚：五列与注销前相同（需求 12.4）。
        User after = userRepository.findById(uid).orElseThrow(
                () -> new AssertionError("注销事务应整体回滚，users 行不应被删除"));
        assertThat(after.getId()).isEqualTo(idBefore);
        assertThat(after.getEmail()).isEqualTo(emailBefore);
        assertThat(after.getWxOpenid()).isEqualTo(openidBefore);
        assertThat(after.getNickname()).isEqualTo(nicknameBefore);
        assertThat(after.getInviteCode()).isEqualTo(inviteCodeBefore);

        // 两表成长数据逐行快照与注销前相同（需求 12.4）。
        assertThat(eventsSnapshot(uid)).isEqualTo(eventsBefore);
        assertThat(profileSnapshot(uid)).isEqualTo(profileBefore);

        // 注销前持有的令牌仍可成功请求成长概览（需求 12.4）。
        ResponseEntity<Map> overview = get("/api/growth", bearer(token));
        assertThat(overview.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ============ 3) 前置校验失败：两表零副作用（需求 12.5）============

    @Test
    void preflightFailures_leaveGrowthTablesUntouched() {
        String token = registerAndLogin("gdel_pre@example.com");
        long uid = userIdOf("gdel_pre@example.com");
        seedGrowth(uid);

        // --- 3a) 二次验证失败（错误的注销验证码）：requireDeletable 已通过，卡在 verifySecondFactor ---
        List<Map<String, Object>> eventsBeforeBadCode = eventsSnapshot(uid);
        List<Map<String, Object>> profileBeforeBadCode = profileSnapshot(uid);
        ResponseEntity<Map> badCode = postDelete(token, Map.of("code", "000000"));
        assertThat(badCode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(badCode)).containsEntry("code", "CODE_INVALID");
        assertThat(eventsSnapshot(uid)).isEqualTo(eventsBeforeBadCode);
        assertThat(profileSnapshot(uid)).isEqualTo(profileBeforeBadCode);

        // --- 3b) 协作牵连拦截：拥有的协作账本仍有他人成员 → DELETE_BLOCKED_COLLAB ---
        seedCollaborativeLedgerWithOtherMember(uid);
        List<Map<String, Object>> eventsBeforeCollab = eventsSnapshot(uid);
        List<Map<String, Object>> profileBeforeCollab = profileSnapshot(uid);
        ResponseEntity<Map> blocked = postDelete(token, Map.of("code", freshDeleteCode("gdel_pre@example.com")));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(blocked)).containsEntry("code", "DELETE_BLOCKED_COLLAB");
        assertThat(eventsSnapshot(uid)).isEqualTo(eventsBeforeCollab);
        assertThat(profileSnapshot(uid)).isEqualTo(profileBeforeCollab);
        // 两条路径下该用户仍存在。
        assertThat(userRepository.findById(uid)).isPresent();
    }

    // ============ 4) 不触及他人成长数据与 invite_relations 任何行（需求 12.6、12.7）============

    @Test
    void deletingUser_doesNotTouchOtherUsersGrowth_norInviteRelations() {
        // A 邀请 B：invite_relations 出现一行（inviter=A、invitee=B、REGISTERED）。
        String tokenA = registerAndLogin("gdel_a@example.com");
        String codeA = inviteCodeOf("gdel_a@example.com");
        registerWithInviteCodeExpectingBound("gdel_b@example.com", codeA);
        long idA = userIdOf("gdel_a@example.com");
        long idB = userIdOf("gdel_b@example.com");
        seedGrowth(idA);
        seedGrowth(idB);

        // 注销前：B 的成长快照 + invite_relations 全表七列快照。
        List<Map<String, Object>> bEventsBefore = eventsSnapshot(idB);
        List<Map<String, Object>> bProfileBefore = profileSnapshot(idB);
        List<Map<String, Object>> inviteBefore = jdbcTemplate.queryForList(INVITE_SEVEN_COLUMNS);
        assertThat(inviteBefore).hasSize(1);

        deleteAccountExpectingSuccess(tokenA, "gdel_a@example.com");

        // A 的成长数据被清零。
        assertThat(growthEventCount(idA)).isZero();
        assertThat(userGrowthCount(idA)).isZero();

        // B 的成长数据一列未动（需求 12.6：成长数据无跨用户引用）。
        assertThat(eventsSnapshot(idB)).isEqualTo(bEventsBefore);
        assertThat(profileSnapshot(idB)).isEqualTo(bProfileBefore);

        // invite_relations 全表七列快照不变：A 未被任何人邀请（无 invitee 行），其作为 inviter 的行由
        // invite 逻辑保持不动，成长删除更是一行都不碰（需求 12.7）。
        assertThat(jdbcTemplate.queryForList(INVITE_SEVEN_COLUMNS)).isEqualTo(inviteBefore);
        // 两表反查 users.id 不存在的行数仍为 0（B 的行合法、A 的行已删）。
        assertThat(orphanGrowthEvents()).isZero();
        assertThat(orphanUserGrowth()).isZero();
    }

    // ============ 5) 同邮箱重新注册从 Lv1、9 枚未点亮（需求 11.21、12.3）============

    @Test
    void reRegisterWithSameEmail_startsAtLevelOne_withNineBadgesLocked_andNoOrphans() {
        String email = "gdel_reuse@example.com";
        String token = registerAndLogin(email);
        long oldId = userIdOf(email);
        seedGrowth(oldId);

        deleteAccountExpectingSuccess(token, email);

        // 同邮箱重新注册：新 users.id（旧行已删，唯一键已释放）。
        String newToken = registerAndLogin(email);
        long newId = userIdOf(email);
        assertThat(newId).isNotEqualTo(oldId);

        // GET /api/growth：等级 1、9 枚徽章均未点亮（需求 12.3、11.21）。
        ResponseEntity<Map> overview = get("/api/growth", bearer(newToken));
        assertThat(overview.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = body(overview);
        assertThat(((Number) body.get("level")).intValue()).isEqualTo(1);
        assertThat(body.get("maxLevelReached")).isEqualTo(Boolean.FALSE);

        List<Map<String, Object>> badges = badgesOf(body);
        assertThat(badges).hasSize(9);
        assertThat(badges).allSatisfy(badge -> {
            assertThat(badge.get("unlocked")).isEqualTo(Boolean.FALSE);
            assertThat(badge.get("unlockedAt")).isNull();
        });

        // 旧用户的成长数据早已随注销清零，两表反查 users.id 不存在的行数为 0（需求 11.21）。
        assertThat(growthEventCount(oldId)).isZero();
        assertThat(userGrowthCount(oldId)).isZero();
        assertThat(orphanGrowthEvents()).isZero();
        assertThat(orphanUserGrowth()).isZero();
    }

    // ---------------------------------- 成长数据播种与快照 ----------------------------------

    /**
     * 直接以 {@link JdbcTemplate} 预置一名用户的成长数据：{@code user_growth} 一行 + 四条
     * {@code growth_events}（FIRST_RECORD / DAILY_RECORD / STREAK / BADGE），取值与真实结算产物同构。
     * {@code exp} 取事件 {@code exp_amount} 之和（35），{@code level} 取一个非平凡值，便于「重新注册回到
     * Lv1」形成对照。
     */
    private void seedGrowth(long userId) {
        LocalDate day = LocalDate.of(2025, 6, 1);
        LocalDateTime ts = LocalDateTime.of(2025, 6, 1, 10, 0);
        insertEvent(userId, GrowthEventType.FIRST_RECORD, "FIRST_RECORD", 10, ts);
        insertEvent(userId, GrowthEventType.DAILY_RECORD, "DAILY_RECORD:" + day, 5, ts);
        insertEvent(userId, GrowthEventType.STREAK, "STREAK_7", 20, ts);
        insertEvent(userId, GrowthEventType.BADGE, "BADGE:FIRST_RECORD", 0, ts);

        jdbcTemplate.update(
                "INSERT INTO user_growth (user_id, exp, level, total_record_days, current_streak_days, "
                        + "max_streak_days, last_record_date, last_settled_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                userId, 35L, 3, 1, 1, 1,
                Date.valueOf(day), Timestamp.valueOf(ts), Timestamp.valueOf(ts), Timestamp.valueOf(ts));
    }

    private void insertEvent(long userId, String type, String key, int exp, LocalDateTime createdAt) {
        jdbcTemplate.update(
                "INSERT INTO growth_events (user_id, event_type, event_key, exp_amount, created_at) "
                        + "VALUES (?, ?, ?, ?, ?)",
                userId, type, key, exp, Timestamp.valueOf(createdAt));
    }

    private long growthEventCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    private long userGrowthCount(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_growth WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
    }

    private long orphanGrowthEvents() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events ge "
                        + "WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = ge.user_id)", Long.class);
        return n == null ? 0L : n;
    }

    private long orphanUserGrowth() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_growth ug "
                        + "WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = ug.user_id)", Long.class);
        return n == null ? 0L : n;
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

    private ResponseEntity<Map> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
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

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> badgesOf(Map<String, Object> overviewBody) {
        return (List<Map<String, Object>>) overviewBody.get("badges");
    }

    private ResponseEntity<Map> postDelete(String token, Map<String, String> payload) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url("/api/me/delete"), HttpMethod.POST,
                new HttpEntity<>(payload, headers), Map.class);
    }

    // ---------------------------------- 数据准备辅助 ----------------------------------

    /** 邮箱验证码登录/注册合一（不携带邀请码），返回 JWT。 */
    private String registerAndLogin(String email) {
        ResponseEntity<Map> login = emailLogin(email, null);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) body(login).get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    /** 携带邀请码建号并断言绑定成功（关系经真实登录链路建立），返回新用户的 JWT。 */
    private String registerWithInviteCodeExpectingBound(String email, String inviteCode) {
        ResponseEntity<Map> login = emailLogin(email, inviteCode);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(body(login)).as("应建立邀请关系: " + email)
                .containsEntry("inviteBound", true)
                .containsEntry("inviteUnboundReason", null);
        String token = (String) body(login).get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    /** 以「新鲜」LOGIN 验证码执行 email-login（清历史码以规避 60s 发码冷却）；{@code inviteCode} 可为 null。 */
    private ResponseEntity<Map> emailLogin(String email, String inviteCode) {
        verificationCodeRepository.deleteByEmail(email);

        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "LOGIN"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        Map<String, String> payload = new HashMap<>();
        payload.put("email", email);
        payload.put("code", latestCode(email, EmailCodePurpose.LOGIN));
        payload.put("inviteCode", inviteCode);
        return rest.postForEntity(url("/api/auth/email-login"), payload, Map.class);
    }

    /** 发一枚新鲜的 DELETE 用途验证码并返回其取值。 */
    private String freshDeleteCode(String email) {
        verificationCodeRepository.deleteByEmail(email);
        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "DELETE"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        return latestCode(email, EmailCodePurpose.DELETE);
    }

    /** 走完整注销流程并断言成功（204 + users 行消失）。 */
    private void deleteAccountExpectingSuccess(String token, String email) {
        long userId = userIdOf(email);
        ResponseEntity<Map> deleted = postDelete(token, Map.of("code", freshDeleteCode(email)));
        assertThat(deleted.getStatusCode()).as("注销应成功: " + deleted).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(userRepository.findById(userId)).isEmpty();
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

    private long userIdOf(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new AssertionError("用户未建立: " + email))
                .getId();
    }

    private String inviteCodeOf(String email) {
        String inviteCode = userRepository.findByEmail(email)
                .orElseThrow(() -> new AssertionError("用户未建立: " + email))
                .getInviteCode();
        assertThat(inviteCode).as("建号时应写入邀请码").isNotBlank();
        return inviteCode;
    }

    private String latestCode(String email, EmailCodePurpose purpose) {
        return verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(email, purpose)
                .orElseThrow(() -> new AssertionError("验证码未生成: " + email + "/" + purpose))
                .getCode();
    }
}
