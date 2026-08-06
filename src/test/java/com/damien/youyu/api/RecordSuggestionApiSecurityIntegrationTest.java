package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * {@code GET /api/transactions/suggestions} 的<b>接口与安全边界</b>集成测试（record-suggestion 任务 6.1，
 * 需求 6.2、6.3、6.4、6.5）。
 *
 * <p>沿用既有安全集成测试（{@link ReminderApiSecurityIntegrationTest} 等）的 {@code Jwts} 手工签发范式，
 * 但改用 {@link MockMvc} 全栈直调（经真实 Spring Security 过滤链、真实 JWT、真实 Jackson 序列化与真实
 * H2 只读派生），使用<b>独立命名</b>的内存库，直接以仓库播种真实用户 / 账本 / 成员 / 流水，覆盖五件事：</p>
 *
 * <ol>
 *   <li><b>无令牌 / 过期令牌 → 401 {@code UNAUTHENTICATED} 且响应不含任何候选</b>（需求 6.2）。</li>
 *   <li><b>{@code X-Ledger-Id} 指向当前用户无权访问的账本 → 既有 {@code LEDGER_NOT_ACCESSIBLE}（404）
 *       且不返回任何候选</b>（需求 6.3）。</li>
 *   <li><b>只返回 {@code X-Ledger-Id} 所指当前账本的候选</b>：同一用户在两个账本各有不同历史，
 *       响应只含请求账本的形态，不串入另一账本的形态（需求 6.4）。</li>
 *   <li><b>携带指定用户 / 账本的入参被忽略</b>：查询参数 {@code userId/ledgerId/uid} 指向他人 / 他账本时，
 *       响应与不带这些入参时逐字段相等——归属只认令牌用户 + {@code X-Ledger-Id} 头（需求 6.4）。</li>
 *   <li><b>服务端处理耗时 &lt; 2000ms</b>（需求 6.5）。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-record-suggestion-api-sec;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecordSuggestionApiSecurityIntegrationTest {

    private static final String PATH = "/api/transactions/suggestions";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 与 {@code app.jwt.secret} 不同的密钥，用于制造验签失败（长度满足 HS256）。 */
    private static final String FOREIGN_SECRET =
            "foreign-secret-key-only-for-suggestion-security-test-do-not-use";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockMvc mockMvc;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private LedgerMemberRepository ledgerMemberRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetTables() {
        // 走原生 SQL：transactions 带 @SQLRestriction，deleteAll 只删未软删行；这里连软删副本一并清掉。
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM categories");
        jdbcTemplate.update("DELETE FROM ledger_members");
        jdbcTemplate.update("DELETE FROM ledgers");
        jdbcTemplate.update("DELETE FROM users");
    }

    // ============ 1) 无 / 过期令牌 → 401 UNAUTHENTICATED，且响应不含候选（需求 6.2）============

    @Test
    void missingToken_returnsUnauthenticated_withoutCandidates() throws Exception {
        mockMvc.perform(get(PATH))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.suggestions").doesNotExist());
    }

    @Test
    void expiredToken_returnsUnauthenticated_withoutCandidates() throws Exception {
        long userId = createUser("suggestion_sec_expired@example.com");
        long ledgerId = createLedger(userId, "本账本");
        seedTwoShapes(ledgerId, userId); // 即便有可派生历史，过期令牌也拿不到

        String expired = token(userId, jwtSecret, Duration.ofSeconds(-10));

        mockMvc.perform(get(PATH).headers(bearer(expired)).header("X-Ledger-Id", ledgerId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.suggestions").doesNotExist());
    }

    @Test
    void forgedSignatureToken_returnsUnauthenticated_withoutCandidates() throws Exception {
        long userId = createUser("suggestion_sec_forged@example.com");
        long ledgerId = createLedger(userId, "本账本");
        seedTwoShapes(ledgerId, userId);

        String forged = token(userId, FOREIGN_SECRET, Duration.ofHours(1));

        mockMvc.perform(get(PATH).headers(bearer(forged)).header("X-Ledger-Id", ledgerId))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.suggestions").doesNotExist());
    }

    // ============ 2) X-Ledger-Id 越权 → LEDGER_NOT_ACCESSIBLE，且不返回候选（需求 6.3）============

    @Test
    void unauthorizedLedgerHeader_returnsLedgerNotAccessible_withoutCandidates() throws Exception {
        long userA = createUser("suggestion_sec_a@example.com");
        long userB = createUser("suggestion_sec_b@example.com");
        createLedger(userA, "A 的账本");
        long ledgerB = createLedger(userB, "B 的账本");
        seedTwoShapes(ledgerB, userB); // B 的账本有历史，但 A 无权访问

        // A 的有效令牌 + 指向 B 账本的 X-Ledger-Id → 既有账本不可访问错误，绝不返回 B 的候选。
        String tokenA = token(userA, jwtSecret, Duration.ofHours(1));

        mockMvc.perform(get(PATH).headers(bearer(tokenA)).header("X-Ledger-Id", ledgerB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("LEDGER_NOT_ACCESSIBLE"))
                .andExpect(jsonPath("$.suggestions").doesNotExist());
    }

    // ============ 3) 只返回当前账本的候选（需求 6.4）============

    @Test
    void returnsOnlyCurrentLedgerCandidates() throws Exception {
        long userId = createUser("suggestion_sec_scope@example.com");
        long ledgerA = createLedger(userId, "账本 A");
        long ledgerB = createLedger(userId, "账本 B");

        // 两个账本各有 2 个不同形态：A 用「午餐/地铁」，B 用「咖啡/打车」。
        seedShape(ledgerA, userId, TransactionType.EXPENSE, "35.00", 6001L, "午餐", 2);
        seedShape(ledgerA, userId, TransactionType.EXPENSE, "6.00", 6002L, "地铁", 1);
        seedShape(ledgerB, userId, TransactionType.EXPENSE, "28.00", 7001L, "咖啡", 2);
        seedShape(ledgerB, userId, TransactionType.EXPENSE, "50.00", 7002L, "打车", 1);

        String token = token(userId, jwtSecret, Duration.ofHours(1));

        List<String> notesA = notesOf(getSuggestions(token, ledgerA));
        assertThat(notesA).containsExactlyInAnyOrder("午餐", "地铁");
        assertThat(notesA).doesNotContain("咖啡", "打车");

        List<String> notesB = notesOf(getSuggestions(token, ledgerB));
        assertThat(notesB).containsExactlyInAnyOrder("咖啡", "打车");
        assertThat(notesB).doesNotContain("午餐", "地铁");
    }

    // ============ 4) 携带指定用户 / 账本入参被忽略（需求 6.4）============

    @Test
    void forgedUserAndLedgerParams_areIgnored() throws Exception {
        long userA = createUser("suggestion_sec_ignore_a@example.com");
        long userB = createUser("suggestion_sec_ignore_b@example.com");
        long ledgerA = createLedger(userA, "A 的账本");
        long ledgerB = createLedger(userB, "B 的账本");

        // A、B 两账本刻意不同形态；若入参被采纳则结果会变。
        seedShape(ledgerA, userA, TransactionType.EXPENSE, "35.00", 6001L, "午餐", 2);
        seedShape(ledgerA, userA, TransactionType.EXPENSE, "6.00", 6002L, "地铁", 1);
        seedShape(ledgerB, userB, TransactionType.EXPENSE, "28.00", 7001L, "咖啡", 2);
        seedShape(ledgerB, userB, TransactionType.EXPENSE, "50.00", 7002L, "打车", 1);

        String tokenA = token(userA, jwtSecret, Duration.ofHours(1));

        // 基线：仅 X-Ledger-Id 指向 A 的账本。
        String baseline = getSuggestions(tokenA, ledgerA);

        // 携带伪造入参：userId/uid 指向 B、ledgerId 指向 B 的账本 —— 应被忽略，结果与基线逐字段相等。
        MvcResult forged = mockMvc.perform(get(PATH)
                        .headers(bearer(tokenA))
                        .header("X-Ledger-Id", ledgerA)
                        .param("userId", String.valueOf(userB))
                        .param("uid", String.valueOf(userB))
                        .param("ledgerId", String.valueOf(ledgerB)))
                .andExpect(status().isOk())
                .andReturn();
        String forgedBody = forged.getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(parseSuggestions(forgedBody)).isEqualTo(parseSuggestions(baseline));
        // 且确未串入 B 的形态。
        assertThat(notesOf(forgedBody)).containsExactlyInAnyOrder("午餐", "地铁");
    }

    // ============ 5) 服务端处理耗时 < 2000ms（需求 6.5）============

    @Test
    void respondsWithinTwoSeconds() throws Exception {
        long userId = createUser("suggestion_sec_perf@example.com");
        long ledgerId = createLedger(userId, "本账本");
        // 播种较多历史（窗口内多形态多笔），逼近真实计算量。
        for (int shape = 0; shape < 20; shape++) {
            seedShape(ledgerId, userId, TransactionType.EXPENSE,
                    (10 + shape) + ".00", 6000L + shape, "形态" + shape, 5);
        }

        String token = token(userId, jwtSecret, Duration.ofHours(1));

        // 预热一次（规避 JIT / 首次类加载噪声），再计时。
        getSuggestions(token, ledgerId);

        long startNanos = System.nanoTime();
        mockMvc.perform(get(PATH).headers(bearer(token)).header("X-Ledger-Id", ledgerId))
                .andExpect(status().isOk());
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000L;

        assertThat(elapsedMillis).as("服务端处理耗时应在 2000ms 内，实际 %dms", elapsedMillis)
                .isLessThan(2000L);
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    /** 以有效令牌 + X-Ledger-Id 调接口，断言 200 并返回原始响应体。 */
    private String getSuggestions(String token, long ledgerId) throws Exception {
        return mockMvc.perform(get(PATH).headers(bearer(token)).header("X-Ledger-Id", ledgerId))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    /** 自行签发令牌：可指定用户 id、密钥（制造验签失败）与有效期（负值即已过期）。 */
    private String token(long userId, String secret, Duration ttl) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        Date issuedAt = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", "user")
                .issuedAt(issuedAt)
                .expiration(new Date(issuedAt.getTime() + ttl.toMillis()))
                .signWith(key)
                .compact();
    }

    /** 解析响应体的 suggestions 列表（每项为字段 Map）。 */
    private List<java.util.Map<String, Object>> parseSuggestions(String body) {
        try {
            java.util.Map<String, Object> root =
                    MAPPER.readValue(body, new TypeReference<java.util.Map<String, Object>>() {
                    });
            @SuppressWarnings("unchecked")
            List<java.util.Map<String, Object>> suggestions =
                    (List<java.util.Map<String, Object>>) root.get("suggestions");
            return suggestions == null ? List.of() : suggestions;
        } catch (Exception e) {
            throw new AssertionError("响应体不是合法 JSON: " + body, e);
        }
    }

    private List<String> notesOf(String body) {
        return parseSuggestions(body).stream()
                .map(item -> (String) item.get("note"))
                .toList();
    }

    // ---------------------------------- 数据准备辅助 ----------------------------------

    private long createUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setNickname(email);
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        LocalDateTime now = LocalDateTime.now();
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.save(u).getId();
    }

    /** 建一个账本并把 owner 设为该用户（成员是访问控制真源）。 */
    private long createLedger(long ownerId, String name) {
        LocalDateTime now = LocalDateTime.now();
        Ledger ledger = new Ledger();
        ledger.setUserId(ownerId);
        ledger.setName(name);
        ledger.setType(Ledger.TYPE_PERSONAL);
        ledger.setDefault(false);
        ledger.setCreatedAt(now);
        ledger.setUpdatedAt(now);
        ledger = ledgerRepository.saveAndFlush(ledger);

        LedgerMember owner = new LedgerMember();
        owner.setLedgerId(ledger.getId());
        owner.setUserId(ownerId);
        owner.setRole(LedgerMember.ROLE_OWNER);
        owner.setCreatedAt(now);
        ledgerMemberRepository.saveAndFlush(owner);
        return ledger.getId();
    }

    /** 播种恰好两个形态（达到渲染门槛），供令牌 / 越权用例证明「即便有历史也拿不到」。 */
    private void seedTwoShapes(long ledgerId, long userId) {
        seedShape(ledgerId, userId, TransactionType.EXPENSE, "35.00", 6001L, "午餐", 2);
        seedShape(ledgerId, userId, TransactionType.EXPENSE, "6.00", 6002L, "地铁", 1);
    }

    /** 播种一个形态的 {@code count} 笔重复流水（occurredAt 落在窗口内、各笔错开分钟）。 */
    private void seedShape(long ledgerId, long userId, TransactionType type, String amount,
                           Long categoryId, String note, int count) {
        // 昨天中午（Asia/Shanghai）：稳落在 [今天-29, 今天] 窗口内，避开当日边界抖动。
        LocalDateTime base = LocalDate.now(ZONE).minusDays(1).atTime(12, 0);
        for (int i = 0; i < count; i++) {
            Transaction t = new Transaction();
            t.setUserId(userId);
            t.setLedgerId(ledgerId);
            t.setCreatedBy(userId);
            t.setType(type);
            t.setAmount(new BigDecimal(amount));
            t.setAccountId(9001L);
            t.setCategoryId(categoryId);
            t.setNote(note);
            t.setOccurredAt(base.minusMinutes(i));
            t.setCreatedAt(base);
            t.setUpdatedAt(base);
            transactionRepository.save(t);
        }
    }
}
