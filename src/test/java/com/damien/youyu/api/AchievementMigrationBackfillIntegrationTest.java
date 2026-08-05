package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.GrowthEvent;
import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.repository.GrowthEventRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.damien.youyu.service.BadgeDef;
import com.damien.youyu.service.GrowthBadgeCatalog;

/**
 * 迁移游标回填的<b>最终验收</b>：在有存量 {@code BADGE} 行的库上执行回填后，
 * 老用户第一次打开小程序<b>不弹任何成就</b>（任务 10.2 中可自动化的那一半，需求 10.7、10.8、5.4、5.16）。
 *
 * <h2>为什么要有这一条</h2>
 *
 * <p>{@code V33__achievement.sql} 的最后一句是游标回填：</p>
 *
 * <pre>{@code
 * INSERT INTO achievement_notices (user_id, last_notified_event_id, created_at, updated_at)
 * SELECT user_id, MAX(id), NOW(), NOW() FROM growth_events
 * WHERE event_type COLLATE utf8mb4_bin = 'BADGE' GROUP BY user_id
 * }</pre>
 *
 * <p>它存在的唯一理由是产品事故防线：成长体系上线以来已解锁的历史徽章一律视为<b>已播报</b>，
 * 否则存量用户升级后第一次打开小程序会被 9 枚（现为至多 16 枚）历史成就连续轰炸。这条语义横跨
 * 「迁移写入的游标取值」与「待播报查询的过滤条件」两处，任何一处写错都会让老用户被轰炸，
 * 而两处各自的单元测试都<b>不会</b>失败——所以它需要一条端到端的锁。</p>
 *
 * <h2>H2 上能验什么，什么归 MySQL 人工清单</h2>
 *
 * <p>本类<b>不执行 Flyway</b>（H2 的表由实体生成，见 {@code MigrationDirectoryTest} 的分工），
 * 而是把 V33 回填语句的<b>等价形式</b>发到 H2 上，再走真实 HTTP 打三个成就端点。因此：</p>
 *
 * <ul>
 *   <li><b>本类断言</b>：回填后每个有 {@code BADGE} 行的用户恰好一行游标、取值等于该用户最大
 *       {@code BADGE} 事件 id、{@code created_at == updated_at}；没有 {@code BADGE} 行的用户不被回填；
 *       老用户回填后第一次打开（{@code GET /api/achievements} 触发结算 → {@code GET
 *       /api/achievements/pending}）待播报<b>为空</b>且 {@code total} 为 0，且这次打开
 *       <b>不新增任何 {@code growth_events} 行</b>。</li>
 *   <li><b>不在本类断言（归 MySQL 人工清单与任务 1.4 的实测结论块）</b>：回填语句里的
 *       {@code COLLATE utf8mb4_bin}（H2 无此排序规则）、{@code ck_growth_events_type} 对
 *       {@code 'badge'} 这类仅大小写不同取值的拒绝、以及 Flyway 版本记录与幂等。这三项已在
 *       MySQL {@code 8.0.46} 上以一次性探针库实测（见 design.md「迁移 V33__achievement.sql」）。</li>
 * </ul>
 *
 * <p><b>反向对照</b>（第三个用例）：同一批存量 {@code BADGE} 行，<b>不</b>回填游标时待播报立刻变成满员
 * ——证明前两个用例的「为空」来自回填本身，而不是因为待播报查询压根查不出东西。少了这一条，
 * 把回填语句整句删掉本类也照样绿。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-migration-backfill-it;"
                + "DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建多个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class AchievementMigrationBackfillIntegrationTest {

    /**
     * V33 回填语句的 H2 等价形式：只有两处差异，其余逐字相同——同一个 {@code MAX(id)}、同一个
     * {@code GROUP BY user_id}、{@code created_at} 与 {@code updated_at} 同取一次 {@code NOW()}。
     *
     * <ol>
     *   <li>去掉 {@code COLLATE utf8mb4_bin}：H2 无该排序规则，大小写敏感性归 MySQL 人工清单；</li>
     *   <li>多一个 {@code user_id = ?}：三个用例共用同一个内存库（{@code DB_CLOSE_DELAY=-1}）且执行
     *       顺序不保证，若按迁移原文对全库回填，先跑的用例造的行会被后跑的用例重复回填（主键冲突）
     *       或被计入其行数断言。按 {@code user_id} 收窄不改变语义：回填本就是
     *       {@code GROUP BY user_id} 的逐用户聚合。</li>
     * </ol>
     */
    private static final String V33_BACKFILL_SQL =
            "INSERT INTO achievement_notices (user_id, last_notified_event_id, created_at, updated_at) "
                    + "SELECT user_id, MAX(id), NOW(), NOW() FROM growth_events "
                    + "WHERE event_type = 'BADGE' AND user_id = ? GROUP BY user_id";

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private VerificationCodeRepository verificationCodeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private GrowthEventRepository growthEventRepository;

    @Autowired
    private GrowthBadgeCatalog badgeCatalog;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // ============ 1) 老用户第一次打开小程序不弹任何成就 ============

    /**
     * 存量老用户（16 枚历史 {@code BADGE} 行、无任何交易）→ 执行 V33 回填 → 第一次打开小程序：
     * 待播报<b>空列表</b> + {@code total} 0（需求 10.7、5.16）。
     *
     * <p>「第一次打开」按 miniapp 的真实次序模拟：先 {@code GET /api/achievements}
     * （写入型 GET，内含一次同步结算，等于用户点进成就页/成长页），再
     * {@code GET /api/achievements/pending}（任务 9.7 的三处挂载点都发这一条）。
     * 该用户没有任何交易，故结算不应新解锁任何成就——{@code growth_events} 行数在这次打开前后不变，
     * 于是「待播报为空」只能来自回填后的游标。</p>
     */
    @Test
    void legacyUserWithBadgeRows_afterBackfill_firstOpenBroadcastsNothing() {
        String email = "ach_mig_legacy@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        List<Long> badgeIds = seedAllCatalogBadges(userId);
        long maxBadgeId = badgeIds.get(badgeIds.size() - 1);

        // —— 迁移：执行 V33 的回填语句 ——
        int backfilled = jdbcTemplate.update(V33_BACKFILL_SQL, userId);
        assertThat(backfilled).as("有 BADGE 行的用户被回填恰好一行").isEqualTo(1);

        // 回填取该用户最大 BADGE 事件 id，且两个时间列取同一时刻（需求 10.7）。
        assertThat(noticeRowCount(userId)).isEqualTo(1L);
        assertThat(cursorInDb(userId)).isEqualTo(maxBadgeId);
        Map<String, Object> row = noticeRow(userId);
        assertThat(row.get("created_at")).isEqualTo(row.get("updated_at"));

        long eventsBefore = growthEventCount(userId);

        // —— 老用户第一次打开小程序 ——
        ResponseEntity<Map> list = get("/api/achievements", token);
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(unlockedCountOf(body(list))).as("16 枚历史成就仍全部展示为已解锁").isEqualTo(16);

        Map<String, Object> pending = body(get("/api/achievements/pending", token));
        assertThat(itemsOf(pending)).as("老用户第一次打开不弹任何成就").isEmpty();
        assertThat(totalOf(pending)).isZero();

        // 这次打开没有新解锁（否则「待播报为空」的理由就不纯粹了）。
        assertThat(growthEventCount(userId)).isEqualTo(eventsBefore);
        // 只读路径不动游标。
        assertThat(cursorInDb(userId)).isEqualTo(maxBadgeId);
    }

    // ============ 2) 没有 BADGE 行的用户不被回填，且待播报同样为空 ============

    /**
     * 只有非 {@code BADGE} 成长事件的用户与无任何成长事件的用户<b>都不被回填</b>（需求 10.8），
     * 游标缺失按 0 处理，待播报仍为空——因为他们压根没有 {@code BADGE} 行。
     */
    @Test
    void usersWithoutBadgeRows_areNotBackfilled_andSeeEmptyPending() {
        String nonBadgeEmail = "ach_mig_nonbadge@example.com";
        String emptyEmail = "ach_mig_empty@example.com";
        String nonBadgeToken = registerAndLogin(nonBadgeEmail);
        String emptyToken = registerAndLogin(emptyEmail);
        long nonBadgeUserId = userIdOf(nonBadgeEmail);
        long emptyUserId = userIdOf(emptyEmail);

        seedGrowthEvent(nonBadgeUserId, GrowthEventType.DAILY_RECORD, "DAILY_RECORD:2025-12-03", 2);
        seedGrowthEvent(nonBadgeUserId, GrowthEventType.STREAK, "STREAK:7", 20);

        assertThat(jdbcTemplate.update(V33_BACKFILL_SQL, nonBadgeUserId))
                .as("只有非 BADGE 行的用户，回填零行").isZero();
        assertThat(jdbcTemplate.update(V33_BACKFILL_SQL, emptyUserId))
                .as("无任何成长事件的用户，回填零行").isZero();

        assertThat(noticeRowCount(nonBadgeUserId)).as("只有非 BADGE 行的用户不被回填").isZero();
        assertThat(noticeRowCount(emptyUserId)).as("无任何成长事件的用户不被回填").isZero();

        for (String token : List.of(nonBadgeToken, emptyToken)) {
            Map<String, Object> pending = body(get("/api/achievements/pending", token));
            assertThat(itemsOf(pending)).isEmpty();
            assertThat(totalOf(pending)).isZero();
        }
    }

    // ============ 3) 反向对照：不回填时老用户会被历史成就轰炸 ============

    /**
     * <b>反向对照</b>：同一批存量 {@code BADGE} 行，<b>不</b>执行回填时，游标缺失按 0 处理，
     * 于是第一次打开就把历史成就全部当成待播报（本次返回 id 最小的 10 项、{@code total} 为截断前的 16）。
     *
     * <p>这条用例是前两条的非空洞性证明：把 V33 的回填语句整句删掉，第 1 条用例会红在这里描述的行为上。
     * 它同时说明「16 枚历史成就连续轰炸」不是假想的风险，而是不回填时的确定结果。</p>
     */
    @Test
    void withoutBackfill_legacyBadgeRowsFloodPendingList() {
        String email = "ach_mig_noback@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        seedAllCatalogBadges(userId);

        // 刻意不执行 V33_BACKFILL_SQL。
        assertThat(noticeRowCount(userId)).isZero();

        Map<String, Object> pending = body(get("/api/achievements/pending", token));
        assertThat(itemsOf(pending)).as("单次至多 10 项").hasSize(10);
        assertThat(totalOf(pending)).as("total 是截断前的全部 16 项").isEqualTo(16L);
    }

    // ---------------------------------- 读库辅助 ----------------------------------

    private Map<String, Object> noticeRow(long userId) {
        return jdbcTemplate.queryForMap(
                "SELECT user_id, last_notified_event_id, created_at, updated_at "
                        + "FROM achievement_notices WHERE user_id = ?", userId);
    }

    private long noticeRowCount(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM achievement_notices WHERE user_id = ?", Long.class, userId);
        return count == null ? 0L : count;
    }

    private long cursorInDb(long userId) {
        Long cursor = jdbcTemplate.queryForObject(
                "SELECT last_notified_event_id FROM achievement_notices WHERE user_id = ?",
                Long.class, userId);
        return cursor == null ? 0L : cursor;
    }

    private long growthEventCount(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ?", Long.class, userId);
        return count == null ? 0L : count;
    }

    // ---------------------------------- 响应解析辅助 ----------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    private long totalOf(Map<String, Object> body) {
        return ((Number) body.get("total")).longValue();
    }

    private int unlockedCountOf(Map<String, Object> body) {
        return ((Number) body.get("unlockedCount")).intValue();
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<Map> get(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    // ---------------------------------- 数据准备辅助 ----------------------------------

    /**
     * 按清单序号升序落全部 16 枚成就的 {@code BADGE} 行（与结算写出的行逐列同形：
     * {@code event_type = 'BADGE'}、{@code event_key = 'BADGE:<编码>'}、{@code exp_amount = 0}），
     * 返回其自增 id（升序）——模拟「成长体系上线以来已解锁」的存量数据。
     */
    private List<Long> seedAllCatalogBadges(long userId) {
        LocalDateTime now = LocalDateTime.now().minusDays(30);
        List<Long> ids = new ArrayList<>();
        int index = 0;
        for (BadgeDef def : badgeCatalog.badges()) {
            ids.add(seedGrowthEvent(userId, GrowthEventType.BADGE,
                    GrowthBadgeCatalog.eventKeyOf(def.code()), 0, now.plusSeconds(index++)));
        }
        assertThat(ids).isSorted();
        return ids;
    }

    private long seedGrowthEvent(long userId, String eventType, String eventKey, int expAmount) {
        return seedGrowthEvent(userId, eventType, eventKey, expAmount, LocalDateTime.now().minusDays(30));
    }

    private long seedGrowthEvent(long userId, String eventType, String eventKey, int expAmount,
            LocalDateTime createdAt) {
        GrowthEvent event = new GrowthEvent();
        event.setUserId(userId);
        event.setEventType(eventType);
        event.setEventKey(eventKey);
        event.setExpAmount(expAmount);
        event.setCreatedAt(createdAt);
        return growthEventRepository.saveAndFlush(event).getId();
    }

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
}
