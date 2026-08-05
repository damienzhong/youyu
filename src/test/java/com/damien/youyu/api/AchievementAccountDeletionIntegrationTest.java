package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.damien.youyu.service.LedgerService;

/**
 * 注销与成就播报游标的联动集成测试（任务 7.4，需求 11.1～11.11）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)：真实 HTTP、真实过滤链与 JWT、真实
 * {@link com.damien.youyu.service.AccountDeletionService} 与 H2 持久化层。账号一律经
 * {@code /api/auth/email-login} 真实建立，注销一律经 {@code POST /api/me/delete} 走完
 * 「协作牵连拦截 → 二次验证 → 单事务级联硬删」全流程，因此「游标行的删除与删 {@code users} 行同处
 * 一个事务」这一点是被真正验证的。</p>
 *
 * <p>游标行同样经<b>真实链路</b>产生：先落有效记账交易，请求 {@code GET /api/achievements} 触发结算
 * 写入 {@code BADGE} 事件，再 {@code POST /api/achievements/notices/ack} 推进游标——本类刻意不用
 * 原生 SQL 造游标行，因为「游标行是怎么来的」本身也在需求 11 的覆盖面内（重注册后必须重新从 0 开始）。</p>
 *
 * <h2>六组断言</h2>
 * <ol>
 *   <li><b>注销删游标行</b>（需求 11.1、11.9）：有游标行的用户注销后，{@code achievement_notices} 与
 *       {@code growth_events} 对该用户的行数均为 0，且全表无「反查 {@code users.id} 不存在」的孤儿行。</li>
 *   <li><b>无游标行时影响 0 且不中止</b>（需求 11.3）：从未 ack 过的用户注销照常返回 204、
 *       {@code users} 行消失、成长两表清零——若「影响行数 0」被当成失败而中止事务，
 *       {@code users} 行会留下来，本断言立刻变红。</li>
 *   <li><b>同邮箱重注册从零开始</b>（需求 11.6、11.10）：新 {@code users.id} 下成就清单返回 16 项
 *       全未解锁、当前值全 0、解锁时刻与事件 id 均为空、{@code unlockedCount} 0；待播报为空列表 +
 *       总条数 0；{@code achievement_notices} 行数 0（游标按 0 处理）。</li>
 *   <li><b>不影响其它用户</b>（需求 11.7）：注销 A 后 B 在三表的行数与全部列取值逐行不变。</li>
 *   <li><b>前置校验失败零副作用</b>（需求 11.8）：{@code DELETE_BLOCKED_COLLAB}（协作账本仍有他人成员）
 *       与错误的注销验证码两条路径下，{@code achievement_notices} 全表四列快照逐行不变、该用户仍存在。</li>
 *   <li><b>注销前签发的令牌注销后失效</b>（需求 11.11）：同一枚令牌对三个成就端点一律返回
 *       {@code UNAUTHENTICATED}，且请求之后 {@code achievement_notices} 不多出任何行
 *       ——尤其是 ack 端点，它是三者中唯一会写该表的，鉴权必须先于任何写入。</li>
 * </ol>
 *
 * <p>结算与 ack 真实提交，清理不能靠事务回滚：各用例使用<b>各自独立的邮箱</b>，并以「全表快照」而不是
 * 「表为空」的方式断言零副作用，因此方法间互不影响。使用独立命名的内存库，避免污染其它共享内存库的
 * 切片测试。</p>
 *
 * <p>Validates: Requirements 11.1, 11.2, 11.3, 11.4, 11.5, 11.6, 11.7, 11.8, 11.9, 11.10, 11.11</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-del-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建多个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class AchievementAccountDeletionIntegrationTest {

    /** {@code achievement_notices} 的全部 4 列，用于零副作用的逐行比对。 */
    private static final String NOTICE_COLUMNS =
            "SELECT user_id, last_notified_event_id, created_at, updated_at "
                    + "FROM achievement_notices ORDER BY user_id";

    /** {@code growth_events} 的全部 6 列。 */
    private static final String EVENT_COLUMNS =
            "SELECT id, user_id, event_type, event_key, exp_amount, created_at FROM growth_events";

    /** {@code user_growth} 的全部 10 列。 */
    private static final String PROFILE_COLUMNS =
            "SELECT user_id, exp, level, total_record_days, current_streak_days, max_streak_days, "
                    + "last_record_date, last_settled_at, created_at, updated_at FROM user_growth";

    /** 三个成就端点（需求 11.11）。 */
    private static final List<String> ACHIEVEMENT_PATHS =
            List.of("/api/achievements", "/api/achievements/pending", "/api/achievements/notices/ack");

    private static final int TOTAL_ACHIEVEMENTS = 16;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private LedgerMemberRepository ledgerMemberRepository;
    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private VerificationCodeRepository verificationCodeRepository;

    // ============ 1) 注销删游标行、无孤儿行（需求 11.1、11.9）============

    @Test
    void deletingUser_removesCursorRow_andLeavesNoOrphans() {
        String email = "ach_del_ok@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        seedRecords(userId, 10);
        long cursor = unlockAndAck(token);

        assertThat(cursor).as("ack 应把游标推进到正数").isPositive();
        assertThat(noticeCountOf(userId)).as("注销前存在游标行").isEqualTo(1L);
        assertThat(growthEventCountOf(userId)).isPositive();

        deleteAccountExpectingSuccess(token, email);

        // 该用户在游标表与成长事件表的行数均为 0（需求 11.9）。
        assertThat(noticeCountOf(userId)).isZero();
        assertThat(growthEventCountOf(userId)).isZero();
        assertThat(userGrowthCountOf(userId)).isZero();
        // 全表无「反查 users.id 不存在」的孤儿行（需求 11.1 的对账口径）。
        assertThat(orphanNotices()).isZero();
    }

    // ============ 2) 无游标行时影响 0 且不中止注销（需求 11.3）============

    @Test
    void deletingUserWithoutCursorRow_succeeds_andDoesNotAbortTheSequence() {
        String email = "ach_del_nocursor@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        seedRecords(userId, 10);
        // 触发结算写入 BADGE 事件，但<b>刻意不 ack</b>：该用户没有游标行。
        assertThat(getAsMap("/api/achievements", bearer(token)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(noticeCountOf(userId)).as("未 ack 过的用户没有游标行").isZero();
        assertThat(growthEventCountOf(userId)).isPositive();

        deleteAccountExpectingSuccess(token, email);

        // 「影响行数 0 即视为成功」：若被当成失败而中止/回滚，users 行与成长两表都会留下来（需求 11.3）。
        assertThat(userRepository.findByEmail(email)).isEmpty();
        assertThat(noticeCountOf(userId)).isZero();
        assertThat(growthEventCountOf(userId)).isZero();
        assertThat(userGrowthCountOf(userId)).isZero();
    }

    // ============ 3) 同邮箱重注册从零开始（需求 11.6、11.10）============

    @Test
    void reRegisterWithSameEmail_returnsSixteenLockedAchievements_zeroPending_andNoCursorRow() {
        String email = "ach_del_reuse@example.com";
        String token = registerAndLogin(email);
        long oldId = userIdOf(email);
        seedRecords(oldId, 10);
        assertThat(unlockAndAck(token)).isPositive();

        deleteAccountExpectingSuccess(token, email);

        // 同邮箱重新注册：新 users.id（旧行已删，唯一键已释放）。
        String newToken = registerAndLogin(email);
        long newId = userIdOf(email);
        assertThat(newId).isNotEqualTo(oldId);

        // 成就清单：16 项全未解锁、当前值全 0、解锁时刻与事件 id 均为空、unlockedCount 0（需求 11.6）。
        Map<String, Object> list = body(getAsMap("/api/achievements", bearer(newToken)));
        assertThat(((Number) list.get("total")).intValue()).isEqualTo(TOTAL_ACHIEVEMENTS);
        assertThat(((Number) list.get("unlockedCount")).intValue()).isZero();
        List<Map<String, Object>> views = listOf(list, "achievements");
        assertThat(views).hasSize(TOTAL_ACHIEVEMENTS);
        for (Map<String, Object> view : views) {
            String code = (String) view.get("code");
            assertThat(view).as("成就 " + code + " 未解锁").containsEntry("unlocked", false);
            assertThat(view).as("成就 " + code + " 当前值为 0").containsEntry("current", 0);
            assertThat(view.get("unlockedAt")).as("成就 " + code + " 无解锁时刻").isNull();
            assertThat(view.get("eventId")).as("成就 " + code + " 无成就事件 id").isNull();
        }

        // 待播报：空列表 + 总条数 0，且游标表零行（游标按 0 处理，需求 11.10）。
        Map<String, Object> pending = body(getAsMap("/api/achievements/pending", bearer(newToken)));
        assertThat(listOf(pending, "items")).isEmpty();
        assertThat(((Number) pending.get("total")).longValue()).isZero();
        assertThat(noticeCountOf(newId)).isZero();
        assertThat(noticeCountOf(oldId)).isZero();
    }

    // ============ 4) 不影响其它用户的三表（需求 11.7）============

    @Test
    void deletingUser_doesNotTouchOtherUsersThreeTables() {
        String emailA = "ach_del_a@example.com";
        String emailB = "ach_del_b@example.com";
        String tokenA = registerAndLogin(emailA);
        String tokenB = registerAndLogin(emailB);
        long idA = userIdOf(emailA);
        long idB = userIdOf(emailB);

        seedRecords(idA, 10);
        seedRecords(idB, 10);
        assertThat(unlockAndAck(tokenA)).isPositive();
        assertThat(unlockAndAck(tokenB)).isPositive();

        // 注销前 B 的三表快照（逐行、含全部列）。
        List<Map<String, Object>> bNoticesBefore = noticesOf(idB);
        List<Map<String, Object>> bEventsBefore = eventsOf(idB);
        List<Map<String, Object>> bProfileBefore = profileOf(idB);
        assertThat(bNoticesBefore).hasSize(1);
        assertThat(bEventsBefore).isNotEmpty();
        assertThat(bProfileBefore).hasSize(1);

        deleteAccountExpectingSuccess(tokenA, emailA);

        // A 清零、B 一列未动（需求 11.7）。
        assertThat(noticeCountOf(idA)).isZero();
        assertThat(growthEventCountOf(idA)).isZero();
        assertThat(userGrowthCountOf(idA)).isZero();
        assertThat(noticesOf(idB)).isEqualTo(bNoticesBefore);
        assertThat(eventsOf(idB)).isEqualTo(bEventsBefore);
        assertThat(profileOf(idB)).isEqualTo(bProfileBefore);
        assertThat(orphanNotices()).isZero();
    }

    // ============ 5) 前置校验失败：游标表零副作用（需求 11.8）============

    @Test
    void preflightFailures_leaveCursorTableUntouched() {
        String email = "ach_del_pre@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        seedRecords(userId, 10);
        assertThat(unlockAndAck(token)).isPositive();

        // --- 5a) 二次验证失败（错误的注销验证码）：requireDeletable 已通过，卡在 verifySecondFactor ---
        List<Map<String, Object>> beforeBadCode = allNotices();
        ResponseEntity<Map> badCode = postDelete(token, Map.of("code", "000000"));
        assertThat(badCode.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body(badCode)).containsEntry("code", "CODE_INVALID");
        assertThat(allNotices()).as("二次验证失败后游标表全表四列逐行不变（需求 11.8）").isEqualTo(beforeBadCode);

        // --- 5b) 协作牵连拦截：拥有的协作账本仍有他人成员 → DELETE_BLOCKED_COLLAB ---
        seedCollaborativeLedgerWithOtherMember(userId);
        List<Map<String, Object>> beforeCollab = allNotices();
        ResponseEntity<Map> blocked = postDelete(token, Map.of("code", freshDeleteCode(email)));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(body(blocked)).containsEntry("code", "DELETE_BLOCKED_COLLAB");
        assertThat(allNotices()).as("协作牵连拦截后游标表全表四列逐行不变（需求 11.8）").isEqualTo(beforeCollab);

        // 两条路径下该用户仍存在，游标行也还在。
        assertThat(userRepository.findById(userId)).isPresent();
        assertThat(noticeCountOf(userId)).isEqualTo(1L);
    }

    // ============ 6) 注销前签发的令牌在注销后失效（需求 11.11）============

    @Test
    void tokenIssuedBeforeDeletion_returnsUnauthenticatedOnAllThreeEndpoints_andCreatesNoOrphanCursor() {
        String email = "ach_del_token@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        seedRecords(userId, 10);
        long cursor = unlockAndAck(token);
        assertThat(cursor).isPositive();

        deleteAccountExpectingSuccess(token, email);
        List<Map<String, Object>> noticesAfterDeletion = allNotices();

        for (String path : ACHIEVEMENT_PATHS) {
            ResponseEntity<Map> response = "/api/achievements/notices/ack".equals(path)
                    ? postAck(token, "0")
                    : getAsMap(path, bearer(token));
            assertThat(response.getStatusCode()).as(path + " / 注销后应 401").isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(body(response)).as(path + " / 统一错误体")
                    .containsEntry("code", "UNAUTHENTICATED");
            // 响应不含任何成就数据（需求 11.11）。
            assertThat(body(response)).doesNotContainKeys("achievements", "items", "lastNotifiedEventId");
        }

        // 三次请求（含唯一会写该表的 ack）之后，游标表一行都没多出来（需求 11.11）。
        assertThat(allNotices()).isEqualTo(noticesAfterDeletion);
        assertThat(noticeCountOf(userId)).isZero();
        assertThat(orphanNotices()).isZero();
    }

    // ---------------------------------- 成就链路辅助 ----------------------------------

    /**
     * 经真实链路造出游标行：{@code GET /api/achievements} 触发结算写入 {@code BADGE} 事件，
     * 取待播报里最大的成就事件 id 后 {@code POST /api/achievements/notices/ack} 推进游标。
     *
     * @return 推进后的游标取值
     */
    private long unlockAndAck(String token) {
        assertThat(getAsMap("/api/achievements", bearer(token)).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> pending = body(getAsMap("/api/achievements/pending", bearer(token)));
        List<Map<String, Object>> items = listOf(pending, "items");
        assertThat(items).as("结算后应有待播报成就").isNotEmpty();
        long maxEventId = items.stream()
                .mapToLong(item -> ((Number) item.get("eventId")).longValue())
                .max()
                .orElseThrow();

        ResponseEntity<Map> ack = postAck(token, String.valueOf(maxEventId));
        assertThat(ack.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) body(ack).get("lastNotifiedEventId")).longValue();
    }

    // ---------------------------------- 库读取辅助 ----------------------------------

    private List<Map<String, Object>> allNotices() {
        return jdbcTemplate.queryForList(NOTICE_COLUMNS);
    }

    private List<Map<String, Object>> noticesOf(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT user_id, last_notified_event_id, created_at, updated_at "
                        + "FROM achievement_notices WHERE user_id = ?", userId);
    }

    private List<Map<String, Object>> eventsOf(long userId) {
        return jdbcTemplate.queryForList(EVENT_COLUMNS + " WHERE user_id = ? ORDER BY id", userId);
    }

    private List<Map<String, Object>> profileOf(long userId) {
        return jdbcTemplate.queryForList(PROFILE_COLUMNS + " WHERE user_id = ?", userId);
    }

    private long noticeCountOf(long userId) {
        return count("SELECT COUNT(*) FROM achievement_notices WHERE user_id = ?", userId);
    }

    private long growthEventCountOf(long userId) {
        return count("SELECT COUNT(*) FROM growth_events WHERE user_id = ?", userId);
    }

    private long userGrowthCountOf(long userId) {
        return count("SELECT COUNT(*) FROM user_growth WHERE user_id = ?", userId);
    }

    /** 反查 {@code users.id} 不存在的游标行数（孤儿行对账，需求 11.1、11.11）。 */
    private long orphanNotices() {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM achievement_notices n "
                        + "WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.id = n.user_id)", Long.class);
        return n == null ? 0L : n;
    }

    private long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    // ---------------------------------- 数据播种辅助 ----------------------------------

    /**
     * 直插若干「有效记账交易」（{@code created_by} = 用户、{@code deleted_at} 为 NULL、
     * {@code type = 'expense'}、{@code ledger_id} 非 NULL），记账日均为当天。经原生 SQL 直插而不走
     * 记账接口：本类验的是注销联动，记账链路在别处覆盖。
     */
    private void seedRecords(long userId, int count) {
        // 默认账本是<b>惰性创建</b>的（见 CurrentLedger.requireLedger），建号本身不建账本，
        // 故这里显式确保一个：有效记账交易要求 ledger_id 非空。
        long ledgerId = ledgerService.ensureDefaultLedger(userId).getId();
        Timestamp now = Timestamp.valueOf(LocalDate.now().atTime(12, 0));
        // account_id / category_id 取一个「绝不可能是真实主键」的高位取值，且按用户隔离：注销前置校验
        // 会拿本人拥有的 account.id 去反查「是否被他人记的交易引用」，撞号会把注销误判成
        // DELETE_BLOCKED_COLLAB（本类的用例共用同一个内存库）。
        long syntheticRef = 900_000_000L + userId;
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO transactions "
                            + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                            + "occurred_at, created_at, updated_at, deleted_at) "
                            + "VALUES (?, ?, ?, 'expense', ?, ?, ?, ?, ?, ?, NULL)",
                    userId, ledgerId, userId, new BigDecimal("1.00"),
                    syntheticRef, syntheticRef, now, now, now);
        }
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

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<Map> getAsMap(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private ResponseEntity<Map> postAck(String token, String lastEventId) {
        return rest.exchange(url("/api/achievements/notices/ack"), HttpMethod.POST,
                new HttpEntity<>(Map.of("lastEventId", lastEventId), authJson(token)), Map.class);
    }

    private ResponseEntity<Map> postDelete(String token, Map<String, String> payload) {
        return rest.exchange(url("/api/me/delete"), HttpMethod.POST,
                new HttpEntity<>(payload, authJson(token)), Map.class);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    private HttpHeaders authJson(String token) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listOf(Map<String, Object> body, String key) {
        return (List<Map<String, Object>>) body.get(key);
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
