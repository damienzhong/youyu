package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.RecurringRuleRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * {@link RecurringRuleController} 的<b>控制器契约与安全边界</b>集成测试（任务 7.1，需求 1.1、6.1、6.2、
 * 6.3、6.5、8.1、8.2、8.3）。
 *
 * <p>照抄 {@link AaExpenseControllerTest} 的 {@code @SpringBootTest}(RANDOM_PORT) + {@code TestRestTemplate}
 * + 手工签发 JWT 范式，经真实 HTTP、真实 Spring Security 过滤链、真实 {@code CurrentLedger}
 * （{@code X-Ledger-Id} 解析）与 H2 持久化层，覆盖：</p>
 *
 * <ol>
 *   <li>无令牌：401 {@code UNAUTHENTICATED}（需求 8.2）。</li>
 *   <li>POST 创建：201、回显 id / 初始 {@code ACTIVE} / 模板 / 频率子字段（需求 1.1）。</li>
 *   <li>GET 列表：返回当前账本当前用户规则（需求 8.1）。</li>
 *   <li>GET 详情：200 回显该规则。</li>
 *   <li>POST pause / resume：状态 {@code ACTIVE}↔{@code PAUSED}（需求 6.1、6.2）。</li>
 *   <li>DELETE：204、其后详情 404（需求 6.5）。</li>
 *   <li>跨用户越权：404 {@code NOT_FOUND}（需求 8.3、6.7）。</li>
 *   <li>{@code X-Ledger-Id} 账本隔离：换账本后列表为空、按 id 详情 404（需求 8.1、8.3）。</li>
 * </ol>
 *
 * <p>因周期规则接口尚无账本 / 账户创建配套接口，本测试直接以 Repository 播种账本、成员、分类与账户链接，
 * 并用 app.jwt.secret 手工签发被测用户的令牌（JWT 过滤器无状态、不查库，签名有效即认证通过）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RecurringRuleControllerTest {

    private static final String PATH = "/api/recurring/rules";
    private static final long ALICE = 3001L;
    private static final long BOB = 3002L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    private RecurringRuleRepository ruleRepository;

    // ---------------- 1) 无令牌 → 401 ----------------

    @Test
    void create_withoutToken_returnsUnauthenticated() {
        Fixture f = fixture(ALICE);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(com.damien.youyu.security.CurrentLedger.HEADER, String.valueOf(f.ledgerId()));

        ResponseEntity<String> response = post(PATH, monthlyBody(f, "3000.00"), headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(parse(response)).containsEntry("code", "UNAUTHENTICATED");
    }

    // ---------------- 2) POST 创建 ----------------

    @Test
    void create_valid_returns201_withActiveRuleAndFields() {
        Fixture f = fixture(ALICE);

        ResponseEntity<String> response = post(PATH, monthlyBody(f, "3000.00"),
                headers(ALICE, f.ledgerId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> json = parse(response);
        assertThat(json.get("id")).isNotNull();
        assertThat(json).containsEntry("type", "expense");
        assertThat(json).containsEntry("status", "ACTIVE");
        assertThat(json).containsEntry("frequency", "MONTHLY");
        assertThat(json).containsEntry("monthDay", 5);
        assertThat(json).containsEntry("endCondition", "NEVER");
        assertThat(json).containsEntry("startDate", "2025-07-05");
        assertThat(new BigDecimal(json.get("amount").toString())).isEqualByComparingTo("3000.00");
    }

    // ---------------- 3) GET 列表 ----------------

    @Test
    void list_returnsCurrentUserCurrentLedgerRules() {
        Fixture f = fixture(ALICE);
        Long id = createRuleId(f, "3000.00");

        ResponseEntity<String> response = get(PATH, headers(ALICE, f.ledgerId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> list = parseList(response);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).get("id").toString()).isEqualTo(id.toString());
    }

    // ---------------- 4) GET 详情 ----------------

    @Test
    void get_returnsRuleById() {
        Fixture f = fixture(ALICE);
        Long id = createRuleId(f, "3000.00");

        ResponseEntity<String> response = get(PATH + "/" + id, headers(ALICE, f.ledgerId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = parse(response);
        assertThat(json.get("id").toString()).isEqualTo(id.toString());
        assertThat(json).containsEntry("status", "ACTIVE");
    }

    // ---------------- 5) pause / resume ----------------

    @Test
    void pauseThenResume_togglesStatus() {
        Fixture f = fixture(ALICE);
        Long id = createRuleId(f, "3000.00");

        ResponseEntity<String> paused = post(PATH + "/" + id + "/pause", null,
                headers(ALICE, f.ledgerId()));
        assertThat(paused.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(paused)).containsEntry("status", "PAUSED");

        ResponseEntity<String> resumed = post(PATH + "/" + id + "/resume", null,
                headers(ALICE, f.ledgerId()));
        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(resumed)).containsEntry("status", "ACTIVE");
    }

    // ---------------- 6) DELETE ----------------

    @Test
    void delete_returns204_thenGetIsNotFound() {
        Fixture f = fixture(ALICE);
        Long id = createRuleId(f, "3000.00");

        ResponseEntity<String> deleted = delete(PATH + "/" + id, headers(ALICE, f.ledgerId()));
        assertThat(deleted.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> afterGet = get(PATH + "/" + id, headers(ALICE, f.ledgerId()));
        assertThat(afterGet.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(afterGet)).containsEntry("code", "NOT_FOUND");
    }

    // ---------------- 7) 跨用户越权 → 404 ----------------

    @Test
    void get_byOtherUser_returnsNotFound() {
        Fixture alice = fixture(ALICE);
        Long id = createRuleId(alice, "3000.00");
        // Bob 有自己的账本（作为成员），以其身份 + 其账本头访问 Alice 的规则 id。
        Fixture bob = fixture(BOB);

        ResponseEntity<String> response = get(PATH + "/" + id, headers(BOB, bob.ledgerId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(response)).containsEntry("code", "NOT_FOUND");
    }

    // ---------------- 8) X-Ledger-Id 账本隔离 ----------------

    @Test
    void ledgerIsolation_ruleNotVisibleFromAnotherLedger() {
        // Alice 的账本 L1 建规则；Alice 的另一个账本 L2 看不到该规则。
        Fixture l1 = fixture(ALICE);
        Long id = createRuleId(l1, "3000.00");
        Fixture l2 = fixture(ALICE);

        // 列表：切到 L2 为空。
        ResponseEntity<String> list = get(PATH, headers(ALICE, l2.ledgerId()));
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parseList(list)).isEmpty();

        // 详情：以 L2 账本头按 id 访问 L1 的规则 → 404。
        ResponseEntity<String> detail = get(PATH + "/" + id, headers(ALICE, l2.ledgerId()));
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(detail)).containsEntry("code", "NOT_FOUND");
    }

    // ---------------------------------- 数据播种 ----------------------------------

    /** 建一个 owner 为 {@code userId} 的个人账本 + 成员行 + 一个分类 + 一个参与该账本的账户。 */
    private Fixture fixture(long userId) {
        LocalDateTime now = LocalDateTime.now();
        Ledger l = new Ledger();
        l.setUserId(userId);
        l.setName("个人");
        l.setType(Ledger.TYPE_PERSONAL);
        l.setSortOrder(0);
        l.setDefault(false);
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
        al.setShowBalance(false);
        al.setCreatedAt(now);
        accountLedgerRepository.save(al);

        return new Fixture(ledger.getId(), cat.getId(), acc.getId());
    }

    private record Fixture(Long ledgerId, Long categoryId, Long accountId) {
    }

    /** 一个 MONTHLY（每月 5 日）支出规则请求体。 */
    private Map<String, Object> monthlyBody(Fixture f, String amount) {
        return Map.of(
                "amount", amount,
                "categoryId", f.categoryId(),
                "accountId", f.accountId(),
                "type", "expense",
                "note", "房租",
                "frequency", "MONTHLY",
                "monthDay", 5,
                "startDate", "2025-07-05",
                "endCondition", "NEVER");
    }

    /** 经接口创建一条规则并返回其 id。 */
    private Long createRuleId(Fixture f, String amount) {
        ResponseEntity<String> response = post(PATH, monthlyBody(f, amount), headers(ALICE, f.ledgerId()));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Long.valueOf(parse(response).get("id").toString());
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> post(String path, Object body, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> delete(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }

    /** 已认证用户的请求头：Bearer 令牌 + X-Ledger-Id + JSON。 */
    private HttpHeaders headers(long userId, Long ledgerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(userId, jwtSecret, Duration.ofHours(1)));
        headers.set(com.damien.youyu.security.CurrentLedger.HEADER, String.valueOf(ledgerId));
        return headers;
    }

    /** 自行签发令牌（与 {@link AaExpenseControllerTest} 同款）。 */
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
