package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.UserRepository;

/**
 * 公开查询 {@code GET /api/invite/inviter} 的<b>报文同构</b>与<b>IP 限流</b>集成测试
 *（任务 6.5，需求 8.6、8.7、8.9、8.10）。
 *
 * <p>走 {@code @SpringBootTest} + MockMvc：真实控制器、真实 {@code SecurityConfig} 过滤链
 *（该端点 {@code permitAll}）、真实 {@link com.damien.youyu.service.InviteService}、真实
 * {@link com.damien.youyu.service.InviteRateLimiter} 与 H2 持久化层。<b>不使用任何测试替身</b>：
 * 本测试要断言的正是「HTTP 状态 + 序列化后的响应报文逐字段相同」与「限流键取自
 * {@code X-Forwarded-For} 末位」，这两条都跨越控制器、序列化与请求头解析，替身一换就变成自证。</p>
 *
 * <h2>四种入参形态</h2>
 * <ol>
 *   <li>库中存在的邀请码 → 200，报文有且仅有 {@code nickname} 一个字段；</li>
 *   <li>格式合法但库中不存在的邀请码 → 404；</li>
 *   <li>格式非法串（规整后长度不等于 8）→ 404；</li>
 *   <li>含字母表以外字符的串（长度为 8）→ 404。</li>
 * </ol>
 * <p>后三者的 HTTP 状态与<b>完整响应体字符串</b>必须逐字节相同（需求 8.9）：任何差异——多一个
 * {@code field}、文案里带上入参原文或长度——都是可用来区分「格式非法」与「邀请码不存在」的旁路信号，
 * 攻击者据此即可批量枚举有效邀请码。故这里比的不是「都含某个 code 字段」，而是整段报文相等。</p>
 *
 * <h2>限流与时钟</h2>
 * <p>{@link com.damien.youyu.service.InviteRateLimiter} 是<b>单例 Bean，状态跨测试方法留存于同一
 * Spring 上下文</b>（进程内滑动窗口，无清理入口，见需求 8.11）。因此每个测试方法都用<b>各自独立的
 * 末位 IP</b>，方法间不会互相消耗额度；同一方法内的请求数也刻意控制在 30 次额度以内（限流方法除外）。
 * 不要在这里给限流器加「清空」方法：那是只为测试存在的后门，反而会让「进程启动后计数从 0 开始」
 * 这条不变式失去防线。</p>
 *
 * <p>时钟由嵌套的 {@link TestClockConfig} 换成可推进的 {@link MutableClock}（覆盖
 * {@code TimeConfig} 的 {@code clock} Bean，故开启 {@code allow-bean-definition-overriding}）。
 * 「被拒请求不消耗额度」这条只能靠推进时钟观察：窗口内一律被拒，看不出被拒请求有没有偷偷记账，
 * 必须让先前的时刻滑出窗口后数一数恢复的额度（见
 * {@link #rejectedRequestsConsumeNoQuotaAndChangeNoData()}）。</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-invite-public-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 用可推进的测试时钟覆盖 TimeConfig 的 clock Bean（限流窗口边界必须可控）。
        "spring.main.allow-bean-definition-overriding=true"
})
class InvitePublicLookupIntegrationTest {

    /** 存在的邀请码：由 {@link #seedData()} 写入某个用户的 {@code invite_code}。 */
    private static final String EXISTING_CODE = "K7M2Q9XT";
    /** 格式合法（8 位、字符全在字母表内）但库中不存在。 */
    private static final String ABSENT_CODE = "ZZZZ2345";
    /** 格式非法：规整后长度不等于 8。 */
    private static final String MALFORMED_CODE = "ABC";
    /** 含字母表以外的字符：长度为 8，但 {@code 0}/{@code I} 不在字母表内。 */
    private static final String ILLEGAL_CHARS_CODE = "ABCDEF0I";

    private static final String INVITER_NICKNAME = "有余小林";

    private static final LocalDateTime SEED_AT = LocalDateTime.of(2025, 6, 1, 12, 0, 0);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private MutableClock clock;

    @BeforeEach
    void seedData() {
        if (userRepository.findByInviteCode(EXISTING_CODE).isPresent()) {
            return;   // 同一上下文内的多个测试方法共用同一份预置数据
        }
        Long inviterId = persistUser("inviter", INVITER_NICKNAME, EXISTING_CODE).getId();
        Long otherId = persistUser("other", "另一个人", "ABCD2345").getId();
        // 两行邀请关系：用于断言公开查询（含被限流拒绝的请求）不改动 invite_relations 的任何取值。
        insertRelation(inviterId, 900_001L, InviteStatus.REGISTERED);
        insertRelation(otherId, 900_002L, InviteStatus.INVALID);
    }

    // ==================== 1) 存在的码：200 + 仅 nickname 一个字段 ====================

    /**
     * 库中存在的邀请码：200，报文有且仅有 {@code nickname}；去空白 + 转大写后命中，
     * 与直接传规整取值的报文完全相同（需求 8.9 的规整口径）。
     */
    @Test
    void existingCodeReturnsNicknameOnly() throws Exception {
        String ip = "203.0.113.11";

        MvcResult exact = perform(EXISTING_CODE, ip);
        assertThat(exact.getResponse().getStatus()).isEqualTo(200);
        assertThat(bodyOf(exact)).isEqualTo("{\"nickname\":\"" + INVITER_NICKNAME + "\"}");

        // 规整（去首尾空白 + 转大写）后同样命中，且报文逐字节相同。
        MvcResult normalized = perform("  " + EXISTING_CODE.toLowerCase() + "  ", ip);
        assertThat(normalized.getResponse().getStatus()).isEqualTo(200);
        assertThat(bodyOf(normalized)).isEqualTo(bodyOf(exact));
    }

    // ==================== 2) 三种失败：状态与报文逐字段相等（需求 8.9） ====================

    /**
     * 不存在的合法码 / 格式非法串 / 含非法字符的串：HTTP 状态与<b>完整响应体字符串</b>逐字节相同。
     *
     * <p>顺带断言报文不回显入参原文、不含长度或「格式」这类细分线索，也不含 {@code field} 键
     * ——它们同样构成可区分两类失败的信号。</p>
     */
    @Test
    void threeFailureShapesAreIndistinguishable() throws Exception {
        String ip = "203.0.113.12";

        MvcResult absent = perform(ABSENT_CODE, ip);
        MvcResult malformed = perform(MALFORMED_CODE, ip);
        MvcResult illegalChars = perform(ILLEGAL_CHARS_CODE, ip);
        // 控制器把 code 声明为可选，缺参数同样收敛到这一条出口（否则"缺参"会变成另一套可区分响应）。
        MvcResult missingParam = performWithoutCode(ip);

        List<MvcResult> failures = List.of(absent, malformed, illegalChars, missingParam);
        String expectedStatus = String.valueOf(absent.getResponse().getStatus());
        String expectedBody = bodyOf(absent);

        assertThat(expectedStatus).as("三种失败一律 404").isEqualTo("404");
        for (MvcResult failure : failures) {
            assertThat(failure.getResponse().getStatus())
                    .as("HTTP 状态相等").isEqualTo(absent.getResponse().getStatus());
            assertThat(failure.getResponse().getContentType())
                    .as("Content-Type 相等").isEqualTo(absent.getResponse().getContentType());
            assertThat(bodyOf(failure)).as("完整响应报文逐字节相等").isEqualTo(expectedBody);
        }

        // 报文既不回显入参，也不透出失败细分原因；field 键不出现（ErrorResponse 对 null 字段不序列化）。
        assertThat(expectedBody)
                .isEqualTo("{\"code\":\"NOT_FOUND\",\"message\":\"邀请码不存在\"}")
                .doesNotContain(ABSENT_CODE, MALFORMED_CODE, ILLEGAL_CHARS_CODE, "field", "长度", "格式");
    }

    // ==================== 3) 限流：第 31 次 429，被拒不消耗额度、不改数据 ====================

    /**
     * 同一末位 IP 的 60 秒窗口内第 31 次请求返回 429 {@code INVITE_RATE_LIMITED}；被拒后两张表
     * 数据不变（需求 8.6、8.7）；邀请码存在与不存在同等计入该窗口（需求 8.10）；且限流键取
     * {@code X-Forwarded-For} <b>末位</b>——伪造前序不影响判定，换一个末位 IP 仍能通过。
     *
     * <p><b>「被拒请求不消耗额度」怎么断言</b>：窗口内继续请求一律 429，看不出被拒请求有没有记账。
     * 所以这里把 30 次成功请求打在 {@code T0}、把被拒请求打在 {@code T0+10s}，再把时钟推到
     * {@code T0+61s}——{@code T0} 的 30 个时刻全部滑出窗口，若被拒请求当时偷偷记了账，
     * {@code T0+10s} 的那几个时刻仍在窗口内，恢复的额度就会少于 30。故随后恰好还能连续通过
     * 30 次，第 31 次才被拒。</p>
     */
    @Test
    void rejectedRequestsConsumeNoQuotaAndChangeNoData() throws Exception {
        String limitedIp = "203.0.113.13";
        String forgedPrefix = "10.0.0.1, 8.8.8.8";   // 客户端可伪造的前序，不得影响限流键
        List<Map<String, Object>> usersBefore = snapshot("users");
        List<Map<String, Object>> relationsBefore = snapshot("invite_relations");

        // 30 次额度：存在与不存在的码交替，二者同等计入（需求 8.10）。
        for (int i = 1; i <= 30; i++) {
            boolean existing = i % 2 == 0;
            MvcResult result = perform(existing ? EXISTING_CODE : ABSENT_CODE,
                    forgedPrefix + ", " + limitedIp);
            assertThat(result.getResponse().getStatus())
                    .as("第 %d 次请求应放行（%s）", i, existing ? "存在的码" : "不存在的码")
                    .isEqualTo(existing ? 200 : 404);
        }

        // 第 31 次：429 INVITE_RATE_LIMITED，且报文不含任何用户字段值。
        MvcResult rejected = perform(EXISTING_CODE, forgedPrefix + ", " + limitedIp);
        assertThat(rejected.getResponse().getStatus()).isEqualTo(429);
        assertThat(bodyOf(rejected))
                .isEqualTo("{\"code\":\"INVITE_RATE_LIMITED\",\"message\":\"请求过于频繁，请稍后再试\"}")
                .doesNotContain(INVITER_NICKNAME);

        // 末位 IP 才是限流键：同一伪造前序换一个末位地址仍能通过（需求 8.6）。
        MvcResult otherLastHop = perform(EXISTING_CODE, forgedPrefix + ", 203.0.113.14");
        assertThat(otherLastHop.getResponse().getStatus()).isEqualTo(200);

        // 被拒请求打在 T0+10s：若它们记了账，推进时钟后恢复的额度就会少于 30。
        clock.advance(Duration.ofSeconds(10));
        for (int i = 0; i < 3; i++) {
            assertThat(perform(EXISTING_CODE, forgedPrefix + ", " + limitedIp)
                    .getResponse().getStatus()).isEqualTo(429);
        }

        // 被拒后两张表逐行逐列不变（需求 8.7）。
        assertThat(snapshot("users")).isEqualTo(usersBefore);
        assertThat(snapshot("invite_relations")).isEqualTo(relationsBefore);

        // T0 的 30 个时刻滑出窗口 → 额度恰好恢复 30 次（证明被拒请求未消耗额度）。
        clock.advance(Duration.ofSeconds(51));
        for (int i = 1; i <= 30; i++) {
            assertThat(perform(EXISTING_CODE, forgedPrefix + ", " + limitedIp)
                    .getResponse().getStatus())
                    .as("窗口滑出后第 %d 次请求应放行", i).isEqualTo(200);
        }
        assertThat(perform(EXISTING_CODE, forgedPrefix + ", " + limitedIp).getResponse().getStatus())
                .as("新窗口的第 31 次仍被拒").isEqualTo(429);
    }

    // ---------------------------------- 辅助 ----------------------------------

    private MvcResult perform(String code, String xForwardedFor) throws Exception {
        return mockMvc.perform(get("/api/invite/inviter")
                        .param("code", code)
                        .header("X-Forwarded-For", xForwardedFor)
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private MvcResult performWithoutCode(String xForwardedFor) throws Exception {
        return mockMvc.perform(get("/api/invite/inviter")
                        .header("X-Forwarded-For", xForwardedFor)
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    /** 响应体原文（UTF-8）：报文同构断言比的是这段字符串本身，不做 JSON 归一化。 */
    private static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 整表快照（全部行、全部列），用于断言请求前后数据一字不变。 */
    private List<Map<String, Object>> snapshot(String table) {
        String orderBy = "users".equals(table) ? "id" : "invite_id";
        return jdbcTemplate.queryForList("SELECT * FROM " + table + " ORDER BY " + orderBy);
    }

    private User persistUser(String tag, String nickname, String inviteCode) {
        User u = new User();
        u.setEmail("invite-public-" + tag + "@example.com");
        u.setNickname(nickname);
        u.setInviteCode(inviteCode);
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(SEED_AT);
        u.setPlanExpiresAt(SEED_AT.plusDays(365));
        u.setCreatedAt(SEED_AT);
        u.setUpdatedAt(SEED_AT);
        return userRepository.save(u);
    }

    /** 预置邀请关系走 JDBC：{@code invitee_id} 是悬空 id（该列无外键，见需求 9.6）。 */
    private void insertRelation(Long inviterId, long inviteeId, InviteStatus status) {
        jdbcTemplate.update("INSERT INTO invite_relations "
                        + "(inviter_id, invitee_id, register_time, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                inviterId, inviteeId, SEED_AT, status.name(), SEED_AT, SEED_AT);
    }

    /** 覆盖 {@code TimeConfig#clock()}：限流窗口边界必须由测试推进，不能等真实的 60 秒。 */
    @TestConfiguration
    static class TestClockConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(Instant.parse("2025-06-01T04:00:00Z"), ZoneId.of("Asia/Shanghai"));
        }
    }

    /** 可推进的固定时钟：{@link #advance(Duration)} 之外的取值恒定，使窗口判定完全确定。 */
    static class MutableClock extends Clock {
        private final ZoneId zone;
        private volatile Instant instant;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
