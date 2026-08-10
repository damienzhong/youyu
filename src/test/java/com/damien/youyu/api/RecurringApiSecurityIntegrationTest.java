package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.EndCondition;
import com.damien.youyu.domain.Frequency;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.RuleStatus;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 周期记账两个控制器（{@link RecurringRuleController} {@code /api/recurring/rules} 与
 * {@link RecurringPendingItemController} {@code /api/recurring/pending-items}）的<b>跨端点鉴权 / 越权 /
 * 账本隔离</b>合并集成测试（任务 7.5，需求 8.1、8.2、8.3、8.4、8.5）。
 *
 * <p>本类<b>不重复</b> {@link RecurringRuleControllerTest} / {@link RecurringPendingItemControllerTest}
 * 已覆盖的场景（缺令牌 → 401、跨用户 / 跨账本按 id → 404、{@code X-Ledger-Id} 列表隔离），只补齐它们的
 * 空白，且横跨两个控制器：</p>
 *
 * <ol>
 *   <li><b>失效令牌（非缺令牌）→ 401 {@code UNAUTHENTICATED}</b>：签名非法（伪造密钥）与已过期两种失效
 *       令牌，对两个控制器均返回 401，响应体含 {@code UNAUTHENTICATED} 且不含任何规则 / 待确认项数据
 *       （需求 8.2）。</li>
 *   <li><b>{@code X-Ledger-Id} 缺省 → 回退默认账本</b>：不带账本头时，两个控制器的读写均作用于当前用户的
 *       <em>默认账本</em>（{@code is_default=true}），而非其它账本，且能确定性复现（需求 8.1、8.4）。</li>
 *   <li><b>外部用户越权按 id 操作待确认项 → 404 {@code NOT_FOUND}</b>：以另一用户身份 + 其自有账本头对本人
 *       待确认项执行确认 / 跳过，返回 {@code NOT_FOUND} 且零副作用（需求 8.5；补 7.2 只测「跨账本确认」而未测
 *       「外部用户」的空白）。</li>
 * </ol>
 *
 * <p>范式沿用两个控制器测试：{@code @SpringBootTest}(RANDOM_PORT) + {@code TestRestTemplate} + 手工签发
 * JWT，经真实 HTTP、真实 Spring Security 过滤链、真实 {@code CurrentLedger}（默认账本回退） 与真实
 * {@code TransactionService}；固定时钟取 {@code Asia/Shanghai} 的 2025-06-15，配合开始日期 2025-06-13 的
 * 每天规则，使懒生成对默认账本确定性补齐 3 期。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-security-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringApiSecurityIntegrationTest {

    private static final String RULES_PATH = "/api/recurring/rules";
    private static final String ITEMS_PATH = "/api/recurring/pending-items";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 00:00Z → today = 2025-06-15（Asia/Shanghai）。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    /** DAILY 规则开始日期：跨 3 个自然日（13/14/15）→ 懒生成恰产出 3 条 PENDING。 */
    private static final LocalDate START = LocalDate.of(2025, 6, 13);

    private static final long USER = 6001L;
    private static final long STRANGER = 6002L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZONE);
        }
    }

    @LocalServerPort
    private int port;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private LedgerMemberRepository memberRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private AccountLedgerRepository accountLedgerRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private RecurringRuleRepository ruleRepository;
    @Autowired
    private RecurringPendingItemRepository pendingItemRepository;

    @BeforeEach
    void reset() {
        // 真实提交、不靠回滚：每个用例前硬清相关表，保证默认账本回退可确定性复现。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountLedgerRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();
        memberRepository.deleteAll();
        ledgerRepository.deleteAll();
    }

    // ===================================================================================
    // 1) 失效令牌（非缺令牌）→ 401 UNAUTHENTICATED（需求 8.2）
    //    7.1/7.2 只覆盖「缺令牌」；此处补「签名非法」与「已过期」两种失效令牌，横跨两个控制器。
    // ===================================================================================

    @Test
    void rulesList_withInvalidSignatureToken_returnsUnauthenticated() {
        HttpHeaders headers = bearer(forgedToken(USER));
        ResponseEntity<String> response = get(RULES_PATH, headers);

        assertUnauthenticated(response);
    }

    @Test
    void rulesList_withExpiredToken_returnsUnauthenticated() {
        HttpHeaders headers = bearer(token(USER, jwtSecret, Duration.ofHours(-1)));
        ResponseEntity<String> response = get(RULES_PATH, headers);

        assertUnauthenticated(response);
    }

    @Test
    void pendingItemsList_withInvalidSignatureToken_returnsUnauthenticated() {
        HttpHeaders headers = bearer(forgedToken(USER));
        ResponseEntity<String> response = get(ITEMS_PATH, headers);

        assertUnauthenticated(response);
    }

    @Test
    void pendingItemsList_withExpiredToken_returnsUnauthenticated() {
        HttpHeaders headers = bearer(token(USER, jwtSecret, Duration.ofHours(-1)));
        ResponseEntity<String> response = get(ITEMS_PATH, headers);

        assertUnauthenticated(response);
    }

    // ===================================================================================
    // 2) X-Ledger-Id 缺省 → 回退默认账本（需求 8.1、8.4）
    //    构造「默认账本 D（is_default=true）+ 另一非默认账本 O」，规则分别落两账本；
    //    不带账本头的读只应命中默认账本 D 的数据，不含 O 的数据（确定性回退）。
    // ===================================================================================

    @Test
    void rulesList_withoutLedgerHeader_fallsBackToDefaultLedger() {
        // 默认账本 D：一条规则；另一账本 O：另一条规则。
        Fixture d = fixture(USER, true);
        Long ruleInDefault = seedActiveRule(d, new BigDecimal("3000.00"));
        Fixture o = fixture(USER, false);
        seedActiveRule(o, new BigDecimal("999.00"));

        // 不带 X-Ledger-Id → 回退默认账本 D：只返回 D 的规则。
        ResponseEntity<String> response = get(RULES_PATH, bearer(token(USER, jwtSecret, Duration.ofHours(1))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> list = parseList(response);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").toString()).isEqualTo(ruleInDefault.toString());
        assertThat(new BigDecimal(list.get(0).get("amount").toString())).isEqualByComparingTo("3000.00");
    }

    @Test
    void pendingItemsList_withoutLedgerHeader_fallsBackToDefaultLedger() {
        // 默认账本 D：ACTIVE 每天规则（跨 06-13/14/15）；另一账本 O：也有一条规则。
        Fixture d = fixture(USER, true);
        seedActiveRule(d, new BigDecimal("50.00"));
        Fixture o = fixture(USER, false);
        seedActiveRule(o, new BigDecimal("77.00"));

        // 不带 X-Ledger-Id → 回退默认账本 D：懒生成只对 D 补齐 3 条 PENDING（13/14/15）。
        ResponseEntity<String> response = get(ITEMS_PATH, bearer(token(USER, jwtSecret, Duration.ofHours(1))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = parseList(response);
        assertThat(items).hasSize(3);
        // 全部归属默认账本 D 的规则，且金额为 D 侧的 50.00（非 O 侧 77.00）。
        assertThat(items).allSatisfy(item ->
                assertThat(new BigDecimal(item.get("amount").toString())).isEqualByComparingTo("50.00"));
        assertThat(items.stream().map(i -> i.get("occurrenceDate").toString()).toList())
                .containsExactly("2025-06-13", "2025-06-14", "2025-06-15");
    }

    // ===================================================================================
    // 3) 外部用户越权按 id 操作待确认项 → 404 NOT_FOUND（需求 8.5）
    //    7.2 只测「本人跨账本确认」；此处补「外部用户」以其自有账本头对本人待确认项确认 / 跳过。
    // ===================================================================================

    @Test
    void pendingItemConfirm_byForeignUser_returnsNotFound() {
        Fixture owner = fixture(USER, true);
        seedActiveRule(owner, new BigDecimal("50.00"));
        long itemId = firstPendingItemId(owner.ledgerId());
        // 外部用户有自己的默认账本（作为成员），以其身份 + 其账本头访问本人待确认项。
        Fixture stranger = fixture(STRANGER, true);

        ResponseEntity<String> response = post(ITEMS_PATH + "/" + itemId + "/confirm", null,
                memberHeaders(STRANGER, stranger.ledgerId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(response)).containsEntry("code", "NOT_FOUND");
        // 零副作用：不生成流水、owner 账户余额不变。
        assertThat(transactionRepository.count()).isZero();
        assertThat(balanceOf(owner.accountId())).isEqualByComparingTo("1000.00");
    }

    @Test
    void pendingItemSkip_byForeignUser_returnsNotFound() {
        Fixture owner = fixture(USER, true);
        seedActiveRule(owner, new BigDecimal("50.00"));
        long itemId = firstPendingItemId(owner.ledgerId());
        Fixture stranger = fixture(STRANGER, true);

        ResponseEntity<String> response = post(ITEMS_PATH + "/" + itemId + "/skip", null,
                memberHeaders(STRANGER, stranger.ledgerId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(response)).containsEntry("code", "NOT_FOUND");
        // 零副作用：目标项仍 PENDING（外部用户跳过失败不改状态）。
        assertThat(pendingItemRepository.findById(itemId).orElseThrow().getStatus().name())
                .isEqualTo("PENDING");
    }

    // ---------------------------------- 断言辅助 ----------------------------------

    /** 断言 401，且响应体为 {@code UNAUTHENTICATED}、不含规则 / 待确认项数据。 */
    private void assertUnauthenticated(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        Map<String, Object> json = parse(response);
        assertThat(json).containsEntry("code", "UNAUTHENTICATED");
        // 不泄漏任何规则 / 待确认项字段（需求 8.2）。
        assertThat(json).doesNotContainKeys("id", "ruleId", "amount", "status", "occurrenceDate");
    }

    // ---------------------------------- 数据播种 ----------------------------------

    /**
     * 建一个 owner 为 {@code userId} 的个人账本（{@code isDefault} 指定其是否默认）+ 成员行 + 一个分类 +
     * 一个参与该账本的账户（初始余额 1000.00）。
     */
    private Fixture fixture(long userId, boolean isDefault) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Ledger l = new Ledger();
        l.setUserId(userId);
        l.setName(isDefault ? "默认账本" : "其它账本");
        l.setType(Ledger.TYPE_PERSONAL);
        l.setSortOrder(0);
        l.setDefault(isDefault);
        l.setCreatedAt(now);
        l.setUpdatedAt(now);
        Ledger ledger = ledgerRepository.save(l);

        LedgerMember m = new LedgerMember();
        m.setLedgerId(ledger.getId());
        m.setUserId(userId);
        m.setRole(LedgerMember.ROLE_OWNER);
        m.setCreatedAt(now);
        memberRepository.save(m);

        Category c = new Category();
        c.setUserId(userId);
        c.setLedgerId(ledger.getId());
        c.setKind(CategoryKind.EXPENSE);
        c.setName("房租");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        Category cat = categoryRepository.save(c);

        Account a = new Account();
        a.setUserId(userId);
        a.setName("现金");
        a.setType(AccountType.CASH);
        a.setInitialBalance(new BigDecimal("1000.00"));
        a.setCurrentBalance(new BigDecimal("1000.00"));
        a.setSortOrder(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        Account acc = accountRepository.save(a);

        AccountLedger al = new AccountLedger();
        al.setAccountId(acc.getId());
        al.setLedgerId(ledger.getId());
        al.setVisibleToOthers(true);
        al.setShowBalance(true);
        al.setCreatedAt(now);
        accountLedgerRepository.save(al);

        return new Fixture(ledger.getId(), cat.getId(), acc.getId());
    }

    private record Fixture(Long ledgerId, Long categoryId, Long accountId) {
    }

    /**
     * 直接落库一条 ACTIVE 每天规则（绕过创建校验，聚焦鉴权 / 隔离契约）。开始日期与 {@code updated_at} 均取
     * {@link #START}，使生成下界 {@code max(startDate, updatedAt)} = 06-13，懒生成对 today=06-15 恰补齐 3 期。
     */
    private Long seedActiveRule(Fixture f, BigDecimal amount) {
        LocalDateTime startTs = START.atStartOfDay();
        RecurringRule rule = new RecurringRule();
        rule.setUserId(ownerOf(f.ledgerId()));
        rule.setLedgerId(f.ledgerId());
        rule.setType("expense");
        rule.setAmount(amount);
        rule.setCategoryId(f.categoryId());
        rule.setAccountId(f.accountId());
        rule.setNote("房租");
        rule.setFrequency(Frequency.DAILY);
        rule.setMonthEnd(false);
        rule.setStartDate(START);
        rule.setEndCondition(EndCondition.NEVER);
        rule.setStatus(RuleStatus.ACTIVE);
        rule.setCreatedAt(startTs);
        rule.setUpdatedAt(startTs);
        return ruleRepository.save(rule).getId();
    }

    private long ownerOf(Long ledgerId) {
        return ledgerRepository.findById(ledgerId).orElseThrow().getUserId();
    }

    /** 以 owner 身份触发懒生成并取该账本下最早到期的待确认项 id。 */
    private long firstPendingItemId(Long ledgerId) {
        long owner = ownerOf(ledgerId);
        ResponseEntity<String> response = get(ITEMS_PATH, memberHeaders(owner, ledgerId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> items = parseList(response);
        assertThat(items).isNotEmpty();
        return Long.parseLong(items.get(0).get("id").toString());
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> post(String path, Object body, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** 仅携带 Bearer 令牌与 JSON 头（不带 X-Ledger-Id，用于默认账本回退与失效令牌用例）。 */
    private HttpHeaders bearer(String rawToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(rawToken);
        return headers;
    }

    /** 已认证成员的请求头：Bearer 令牌 + X-Ledger-Id + JSON。 */
    private HttpHeaders memberHeaders(long userId, Long ledgerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(userId, jwtSecret, Duration.ofHours(1)));
        headers.set(com.damien.youyu.security.CurrentLedger.HEADER, String.valueOf(ledgerId));
        return headers;
    }

    /** 用真实 app.jwt.secret 签发的有效 / 已过期令牌（TTL 为负即已过期）。 */
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

    /** 用一个与真实 secret 无关的伪造密钥签发令牌，使签名校验失败（失效令牌）。 */
    private String forgedToken(long userId) {
        byte[] forged = "this-is-a-completely-bogus-signing-key-not-the-app-secret".getBytes(StandardCharsets.UTF_8);
        SecretKey key = Keys.hmacShaKeyFor(forged);
        Date issuedAt = new Date();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", "user")
                .issuedAt(issuedAt)
                .expiration(new Date(issuedAt.getTime() + Duration.ofHours(1).toMillis()))
                .signWith(key)
                .compact();
    }

    private Map<String, Object> parse(ResponseEntity<String> response) {
        String raw = response.getBody();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            throw new AssertionError("响应体不是合法 JSON 对象: " + raw, e);
        }
    }

    private List<Map<String, Object>> parseList(ResponseEntity<String> response) {
        String raw = response.getBody();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(raw, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new AssertionError("响应体不是合法 JSON 数组: " + raw, e);
        }
    }
}
