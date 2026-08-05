package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.damien.youyu.service.LedgerService;

/**
 * 连续记账系统对成长体系与成就系统的<b>既有契约兼容</b>集成测试（任务 6.3，需求 10.1、10.4、10.5、
 * 10.6、10.7）。
 *
 * <p>本 spec 对成长体系与成就系统是<b>纯增量</b>：只新增 {@code streak_segments} 一张派生表，
 * 只读 {@code growth_events} / {@code user_growth}，不发经验、不改等级、不改成就解锁与播报游标。
 * 本测试用全栈 {@code @SpringBootTest}(RANDOM_PORT)——真实 HTTP、真实过滤链与 JWT、真实
 * {@code GrowthQueryService} / {@code AchievementQueryService} / {@code StreakQueryService} 与
 * H2（{@code MODE=MySQL}）持久化层——从接口层锁住四件事：</p>
 *
 * <ol>
 *   <li><b>既有响应字段集不被本 spec 撑大</b>（需求 10.4）：成长概览顶层仍<b>恰好 15 项</b>、
 *       成就清单顶层仍<b>恰好 3 项</b>，一个字段不加。</li>
 *   <li><b>段是派生数据，删空不影响两个既有体系</b>（需求 10.7）：{@code DELETE FROM streak_segments}
 *       清空全表后，成长概览、成就清单两个成功响应的字段集与逐项取值不变，成长明细分页参数错误码
 *       与未认证错误码不变。</li>
 *   <li><b>当前连续天数与最长连续天数两处同源</b>（需求 10.5）：同一时刻成长概览与连续记账概览返回的
 *       {@code currentStreakDays} 与 {@code maxStreakDays} 逐项相等（两处共用同一份 {@code StreakJudgment}
 *       判定，相等性构造性成立）。</li>
 *   <li><b>四枚 STREAK 成就与段序列自洽</b>（需求 10.6）：对 {@code STREAK_7 / 30 / 100 / 365} 四枚，
 *       当「最大段天数 ≥ 门槛」且「该成就已解锁」时，其成就当前值恰好等于门槛数值。</li>
 * </ol>
 *
 * <p>数据经真实链路生成：直插 30 个连续自然日各一笔有效记账交易（{@code created_by} = 用户、
 * {@code deleted_at} 为 NULL、{@code type = 'expense'}、{@code ledger_id} 非 NULL），再以
 * {@code GET /api/growth} 触发一次同步结算（成长概览是写入型 GET）。30 天连续使
 * {@code max_streak_days = 30}，从而 {@code STREAK_7}（门槛 7）与 {@code STREAK_30}（门槛 30）解锁，
 * {@code STREAK_100} / {@code STREAK_365} 保持未解锁——四枚里既有已解锁也有未解锁，自洽断言两侧都覆盖。
 * 使用<b>独立命名</b>的内存库，避免污染其它共享内存库的切片测试。</p>
 *
 * <p>Feature: streak-system, 兼容边界回归（任务 6.3）</p>
 * <p>Validates: Requirements 10.1, 10.4, 10.5, 10.6, 10.7</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-streak-compat-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建多个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class StreakCompatibilityIntegrationTest {

    /** 成长概览的 15 个顶层字段（growth-level-system 需求 10.1；本 spec 需求 10.4 要求一字不改）。 */
    private static final Set<String> GROWTH_OVERVIEW_TOP_KEYS = Set.of(
            "level", "exp", "currentLevelExp", "nextLevelExp", "expInCurrentLevel", "expToNextLevel",
            "maxLevel", "maxLevelReached", "totalRecordCount", "totalExpense", "totalIncome",
            "totalRecordDays", "currentStreakDays", "maxStreakDays", "badges");

    /** 成就清单顶层字段集，恰好 3 项（achievement-system 需求 6.1；本 spec 需求 10.4 要求一字不改）。 */
    private static final Set<String> ACHIEVEMENT_LIST_TOP_KEYS =
            Set.of("achievements", "unlockedCount", "total");

    /** 四枚连续记账成就的编码（本 spec 需求 10.6）。 */
    private static final List<String> STREAK_CODES =
            List.of("STREAK_7", "STREAK_30", "STREAK_100", "STREAK_365");

    /** 连续自然日的记账天数：30 天使 STREAK_7 与 STREAK_30 解锁、STREAK_100/365 未解锁。 */
    private static final int CONSECUTIVE_DAYS = 30;

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
    private LedgerService ledgerService;

    // ============ 1) 既有响应字段集不被本 spec 撑大（需求 10.4）============

    @Test
    void growthOverviewStillHas15Keys_andAchievementListStillHas3Keys() {
        String token = registerAndLogin("streak_compat_shape@example.com");
        seedConsecutiveDaysAndSettle(token, "streak_compat_shape@example.com", CONSECUTIVE_DAYS);

        Map<String, Object> overview = body(get("/api/growth", bearer(token)));
        assertThat(overview.keySet()).as("成长概览顶层恰好 15 项（需求 10.4）")
                .containsExactlyInAnyOrderElementsOf(GROWTH_OVERVIEW_TOP_KEYS);

        Map<String, Object> list = body(get("/api/achievements", bearer(token)));
        assertThat(list.keySet()).as("成就清单顶层恰好 3 项（需求 10.4）")
                .containsExactlyInAnyOrderElementsOf(ACHIEVEMENT_LIST_TOP_KEYS);
    }

    // ============ 2) 清空 streak_segments 后两个既有体系原样成立（需求 10.7）============

    @Test
    void deletingAllStreakSegments_leavesGrowthAndAchievementResponsesUnchanged() {
        String email = "streak_compat_delete@example.com";
        String token = registerAndLogin(email);
        long userId = seedConsecutiveDaysAndSettle(token, email, CONSECUTIVE_DAYS);

        // 前置确认：结算确实落了段（否则「删空」这一动作无从验证）。
        assertThat(segmentCountOf(userId)).as("结算后应已落段").isPositive();

        // 删空前的既有体系响应与错误码快照。
        Map<String, Object> growthBefore = body(get("/api/growth", bearer(token)));
        Map<String, Object> achievementBefore = body(get("/api/achievements", bearer(token)));
        ResponseEntity<Map> pageErrorBefore = get("/api/growth/events?page=-1&size=20", bearer(token));
        ResponseEntity<Map> unauthBefore = get("/api/achievements", new HttpHeaders());

        // 清空全表：段是记账日历的派生视图，删空只导致连续记账页在下一次结算前少展示历史区间。
        jdbcTemplate.update("DELETE FROM streak_segments");
        assertThat(segmentCountOf(userId)).as("段表已清空").isZero();

        // 删空后：成长概览、成就清单的字段集与逐项取值一字不变（需求 10.7）。
        Map<String, Object> growthAfter = body(get("/api/growth", bearer(token)));
        Map<String, Object> achievementAfter = body(get("/api/achievements", bearer(token)));
        assertThat(growthAfter.keySet())
                .as("删空段表后成长概览仍恰好 15 项").containsExactlyInAnyOrderElementsOf(GROWTH_OVERVIEW_TOP_KEYS);
        assertThat(achievementAfter.keySet())
                .as("删空段表后成就清单仍恰好 3 项").containsExactlyInAnyOrderElementsOf(ACHIEVEMENT_LIST_TOP_KEYS);
        assertThat(growthAfter).as("删空段表后成长概览逐项取值不变（需求 10.7）").isEqualTo(growthBefore);
        assertThat(achievementAfter).as("删空段表后成就清单逐项取值不变（需求 10.7）").isEqualTo(achievementBefore);

        // 删空后：既有错误码逐项不变（需求 10.7）。
        ResponseEntity<Map> pageErrorAfter = get("/api/growth/events?page=-1&size=20", bearer(token));
        assertErrorUnchanged(pageErrorBefore, pageErrorAfter, "GROWTH_PAGE_PARAM_INVALID",
                HttpStatus.BAD_REQUEST);
        ResponseEntity<Map> unauthAfter = get("/api/achievements", new HttpHeaders());
        assertErrorUnchanged(unauthBefore, unauthAfter, "UNAUTHENTICATED", HttpStatus.UNAUTHORIZED);
    }

    // ============ 3) 当前连续天数与最长连续天数两处同源（需求 10.5）============

    @Test
    void growthAndStreakOverview_returnEqualCurrentAndMaxStreakDays() {
        String email = "streak_compat_parity@example.com";
        String token = registerAndLogin(email);
        seedConsecutiveDaysAndSettle(token, email, CONSECUTIVE_DAYS);

        // 同一时刻分别请求两个概览（判定日同为 Asia/Shanghai 折算的今日）。
        Map<String, Object> growth = body(get("/api/growth", bearer(token)));
        Map<String, Object> streak = body(get("/api/streak", bearer(token)));

        assertThat(intOf(streak, "currentStreakDays"))
                .as("连续记账概览与成长概览的 currentStreakDays 相等（需求 10.5）")
                .isEqualTo(intOf(growth, "currentStreakDays"))
                .isEqualTo(CONSECUTIVE_DAYS);
        assertThat(intOf(streak, "maxStreakDays"))
                .as("连续记账概览与成长概览的 maxStreakDays 相等（需求 10.5）")
                .isEqualTo(intOf(growth, "maxStreakDays"))
                .isEqualTo(CONSECUTIVE_DAYS);
    }

    // ============ 4) 四枚 STREAK 成就与段序列自洽（需求 10.6）============

    @Test
    void streakAchievements_areSelfConsistentWithSegmentMaxDays() {
        String email = "streak_compat_selfconsist@example.com";
        String token = registerAndLogin(email);
        seedConsecutiveDaysAndSettle(token, email, CONSECUTIVE_DAYS);

        // 最大段天数 = 连续记账概览的 maxStreakDays（= 段序列中最大的段天数，需求 3.2）。
        Map<String, Object> streak = body(get("/api/streak", bearer(token)));
        int maxSegmentDays = intOf(streak, "maxStreakDays");
        assertThat(maxSegmentDays).as("30 天连续 → 最大段天数 30").isEqualTo(CONSECUTIVE_DAYS);

        Map<String, Map<String, Object>> viewByCode =
                viewByCode(body(get("/api/achievements", bearer(token))));

        boolean sawUnlocked = false;
        boolean sawLocked = false;
        for (String code : STREAK_CODES) {
            Map<String, Object> view = viewByCode.get(code);
            assertThat(view).as("成就清单含 " + code).isNotNull();

            int target = intOf(view, "target");
            int current = intOf(view, "current");
            boolean unlocked = Boolean.TRUE.equals(view.get("unlocked"));

            // 需求 10.6：最大段天数 ≥ 门槛且该成就已解锁时，当前值等于门槛数值。
            if (maxSegmentDays >= target && unlocked) {
                sawUnlocked = true;
                assertThat(current)
                        .as(code + "：最大段天数(" + maxSegmentDays + ") ≥ 门槛(" + target
                                + ") 且已解锁 → 当前值等于门槛（需求 10.6）")
                        .isEqualTo(target);
            }
            if (!unlocked) {
                sawLocked = true;
                // 门槛未达（100 / 365 > 30）自然不该解锁，佐证解锁判定仍取 max_streak_days。
                assertThat(maxSegmentDays)
                        .as(code + " 未解锁时最大段天数应小于门槛").isLessThan(target);
            }
        }
        assertThat(sawUnlocked).as("STREAK_7 / STREAK_30 应已解锁，覆盖自洽断言的解锁分支").isTrue();
        assertThat(sawLocked).as("STREAK_100 / STREAK_365 应未解锁，覆盖门槛未达分支").isTrue();
    }

    // ---------------------------------- 断言辅助 ----------------------------------

    /** 断言删空段表前后错误响应的状态码、{@code code} 与错误体逐项不变（需求 10.7）。 */
    private void assertErrorUnchanged(ResponseEntity<Map> before, ResponseEntity<Map> after,
                                      String expectedCode, HttpStatus expectedStatus) {
        assertThat(before.getStatusCode()).as(expectedCode + " 删空前状态码").isEqualTo(expectedStatus);
        assertThat(after.getStatusCode()).as(expectedCode + " 删空后状态码不变").isEqualTo(expectedStatus);
        Map<String, Object> beforeBody = body(before);
        Map<String, Object> afterBody = body(after);
        assertThat(beforeBody).as(expectedCode + " 删空前含该错误码").containsEntry("code", expectedCode);
        assertThat(afterBody).as(expectedCode + " 删空后错误体逐项不变（需求 10.7）").isEqualTo(beforeBody);
    }

    // ---------------------------------- 数据准备 ----------------------------------

    /**
     * 为该用户直插 {@code days} 个连续自然日（以今日为末日、向前覆盖）各一笔有效记账交易，
     * 再以 {@code GET /api/growth} 触发一次同步结算落库（段维护 + STREAK 成就解锁）。
     *
     * @return 该用户 id
     */
    private long seedConsecutiveDaysAndSettle(String token, String email, int days) {
        long userId = userIdOf(email);
        long ledgerId = ledgerIdOf(userId);
        LocalDate today = LocalDate.now();
        for (int back = days - 1; back >= 0; back--) {
            LocalDate day = today.minusDays(back);
            seedTransaction(userId, ledgerId, day);
        }
        // 成长概览是写入型 GET：触发一次真实结算，写回物化列、维护段序列、解锁 STREAK 成就。
        assertThat(get("/api/growth", bearer(token)).getStatusCode()).isEqualTo(HttpStatus.OK);
        return userId;
    }

    /**
     * 直插一笔「有效记账交易」，记账日与发生日均为 {@code day}（记账日历按 {@code created_at} 折算）。
     * {@code account_id} / {@code category_id} 取按用户隔离的高位合成值，避免与真实主键撞号。
     */
    private void seedTransaction(long userId, long ledgerId, LocalDate day) {
        Timestamp at = Timestamp.valueOf(day.atTime(12, 0));
        long syntheticRef = 900_000_000L + userId;
        jdbcTemplate.update(
                "INSERT INTO transactions "
                        + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                        + "occurred_at, created_at, updated_at, deleted_at) "
                        + "VALUES (?, ?, ?, 'expense', ?, ?, ?, ?, ?, ?, NULL)",
                userId, ledgerId, userId, new BigDecimal("1.00"), syntheticRef, syntheticRef,
                at, at, at);
    }

    private long segmentCountOf(long userId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM streak_segments WHERE user_id = ?", Long.class, userId);
        return n == null ? 0L : n;
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

    private int intOf(Map<String, Object> body, String key) {
        Object value = body.get(key);
        assertThat(value).as("字段 " + key + " 应为数值").isInstanceOf(Number.class);
        return ((Number) value).intValue();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> viewByCode(Map<String, Object> listBody) {
        Map<String, Map<String, Object>> byCode = new java.util.LinkedHashMap<>();
        for (Map<String, Object> view : (List<Map<String, Object>>) listBody.get("achievements")) {
            byCode.put((String) view.get("code"), view);
        }
        return byCode;
    }

    // ---------------------------------- 账号辅助 ----------------------------------

    private String registerAndLogin(String email) {
        verificationCodeRepository.deleteByEmail(email);

        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "LOGIN"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String code = verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(email, EmailCodePurpose.LOGIN)
                .orElseThrow(() -> new AssertionError("验证码未生成: " + email))
                .getCode();

        ResponseEntity<Map> login = rest.postForEntity(url("/api/auth/email-login"),
                Map.of("email", email, "code", code), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) body(login).get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    private long userIdOf(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new AssertionError("用户未建立: " + email))
                .getId();
    }

    private long ledgerIdOf(long userId) {
        return ledgerService.ensureDefaultLedger(userId).getId();
    }
}
