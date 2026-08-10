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
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * {@link AaLedgerController} 的<b>控制器契约与安全边界</b>集成测试（任务 5.1，需求 2.1、4.4、5.1、
 * 7.1、7.2、8.1、9.4）。照抄 {@link AaSettlementControllerTest} 的 {@code @SpringBootTest}(RANDOM_PORT)
 * + {@code TestRestTemplate} + 手工签发 JWT 范式，经真实 HTTP、真实 Spring Security 过滤链与 H2 覆盖：</p>
 *
 * <ol>
 *   <li>GET 概览：200、三口径（账户已支出 / 我的消费 / 待收回）、成员净额（Σ=0）、流水（付款人 / 我摊）。</li>
 *   <li>非成员越权：404（不泄漏账本存在性，需求 9.4）。</li>
 *   <li>无令牌：401 {@code UNAUTHENTICATED}。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AaLedgerControllerTest {

    private static final long ALICE = 3001L;
    private static final long BOB = 3002L;
    private static final long CAROL = 3003L;
    private static final long OUTSIDER = 3009L;

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

    // ---------------- 1) GET 概览 ----------------

    @Test
    void overview_returns200_withCalibersNetsAndFlow() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");
        // Alice 付 90，三人均分 → Alice 账户已支出 90、消费 30、待收回 60。
        createEvenExpense(ledger.getId(), cat.getId(), acc.getId(), "90.00");

        ResponseEntity<String> response = get(
                "/api/aa/" + ledger.getId() + "/overview", memberHeaders(ALICE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = parse(response);
        assertThat(json).containsEntry("ledgerId", ledger.getId().intValue());
        assertThat(json).containsEntry("archived", false);
        assertThat(json).containsEntry("allSettled", false);

        @SuppressWarnings("unchecked")
        Map<String, Object> calibers = (Map<String, Object>) json.get("calibers");
        assertThat(new BigDecimal(calibers.get("accountPaid").toString())).isEqualByComparingTo("90.00");
        assertThat(new BigDecimal(calibers.get("myConsumption").toString())).isEqualByComparingTo("30.00");
        assertThat(new BigDecimal(calibers.get("receivable").toString())).isEqualByComparingTo("60.00");

        // 成员净额 Σ=0。
        Map<Long, BigDecimal> net = nets(json);
        assertThat(net.get(ALICE)).isEqualByComparingTo("60.00");
        assertThat(net.get(BOB)).isEqualByComparingTo("-30.00");
        assertThat(net.get(CAROL)).isEqualByComparingTo("-30.00");
        assertThat(net.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0.00");

        // 流水：一条 aa_expense，标注付款人 Alice 与我摊 30。
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> txs = (List<Map<String, Object>>) json.get("transactions");
        assertThat(txs).hasSize(1);
        Map<String, Object> expense = txs.get(0);
        assertThat(expense).containsEntry("type", "aa_expense");
        assertThat(Long.valueOf(expense.get("payerUserId").toString())).isEqualTo(ALICE);
        assertThat(new BigDecimal(expense.get("myShare").toString())).isEqualByComparingTo("30.00");
    }

    // ---------------- 1b) AA 账本无预算入口（需求 1.3）----------------

    @Test
    void budgetOverview_onAaLedger_returnsBudgetNotSupported() {
        Ledger ledger = seedAaLedger(false);

        HttpHeaders headers = memberHeaders(ALICE);
        headers.set(com.damien.youyu.security.CurrentLedger.HEADER, String.valueOf(ledger.getId()));
        ResponseEntity<String> response = get("/api/budgets", headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(response)).containsEntry("code", "BUDGET_NOT_SUPPORTED");
    }

    // ---------------- 2) 非成员越权 → 404（需求 9.4）----------------

    @Test
    void overview_byNonMember_returnsNotFound() {
        Ledger ledger = seedAaLedger(false);

        ResponseEntity<String> response = get(
                "/api/aa/" + ledger.getId() + "/overview", memberHeaders(OUTSIDER));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------- 3) 无令牌 → 401 ----------------

    @Test
    void overview_withoutToken_returnsUnauthenticated() {
        Ledger ledger = seedAaLedger(false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = get("/api/aa/" + ledger.getId() + "/overview", headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(parse(response)).containsEntry("code", "UNAUTHENTICATED");
    }

    // ---------------------------------- 数据播种 ----------------------------------

    private Ledger seedAaLedger(boolean archived) {
        LocalDateTime now = LocalDateTime.now();
        Ledger l = new Ledger();
        l.setUserId(ALICE);
        l.setName("旅行 AA");
        l.setType(Ledger.TYPE_AA);
        l.setSortOrder(0);
        l.setDefault(false);
        if (archived) {
            l.setArchivedAt(now);
        }
        l.setCreatedAt(now);
        l.setUpdatedAt(now);
        Ledger saved = ledgerRepository.save(l);
        member(saved.getId(), ALICE, LedgerMember.ROLE_OWNER);
        member(saved.getId(), BOB, LedgerMember.ROLE_EDITOR);
        member(saved.getId(), CAROL, LedgerMember.ROLE_EDITOR);
        return saved;
    }

    private void member(Long ledgerId, long userId, String role) {
        LedgerMember m = new LedgerMember();
        m.setLedgerId(ledgerId);
        m.setUserId(userId);
        m.setRole(role);
        m.setCreatedAt(LocalDateTime.now());
        memberRepository.save(m);
    }

    private Category seedCategory(Long ledgerId) {
        LocalDateTime now = LocalDateTime.now();
        Category c = new Category();
        c.setLedgerId(ledgerId);
        c.setKind(CategoryKind.EXPENSE);
        c.setName("餐饮");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c);
    }

    private Account seedAccount(long userId, String balance) {
        LocalDateTime now = LocalDateTime.now();
        Account a = new Account();
        a.setUserId(userId);
        a.setName("现金");
        a.setType(AccountType.CASH);
        a.setInitialBalance(new BigDecimal(balance));
        a.setCurrentBalance(new BigDecimal(balance));
        a.setSortOrder(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return accountRepository.save(a);
    }

    /** 经 AA 记账接口创建一笔均分支出（Alice 付款），确保数据来源与生产链路一致。 */
    private void createEvenExpense(Long ledgerId, Long categoryId, Long accountId, String amount) {
        Map<String, Object> body = Map.of(
                "amount", amount,
                "categoryId", categoryId,
                "payerUserId", ALICE,
                "payerAccountId", accountId,
                "splitMode", "even",
                "participants", List.of(ALICE, BOB, CAROL));
        HttpHeaders headers = memberHeaders(ALICE);
        headers.set(com.damien.youyu.security.CurrentLedger.HEADER, String.valueOf(ledgerId));
        ResponseEntity<String> response = rest.exchange(url("/api/aa/expenses"), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private HttpHeaders memberHeaders(long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(userId, jwtSecret, Duration.ofHours(1)));
        return headers;
    }

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

    @SuppressWarnings("unchecked")
    private Map<Long, BigDecimal> nets(Map<String, Object> json) {
        List<Map<String, Object>> raw = (List<Map<String, Object>>) json.get("memberNets");
        Map<Long, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> n : raw) {
            out.put(Long.valueOf(n.get("userId").toString()), new BigDecimal(n.get("net").toString()));
        }
        return out;
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
}
