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

import com.damien.youyu.domain.AaSettlement;
import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.repository.AaSettlementRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * {@link AaSettlementController} 的<b>控制器契约与安全边界</b>集成测试（任务 4.1，需求 5.2、5.4、5.5、9.4）。
 *
 * <p>照抄 {@link AaExpenseControllerTest} 的 {@code @SpringBootTest}(RANDOM_PORT) + {@code TestRestTemplate}
 * + 手工签发 JWT 范式。经真实 HTTP、真实 Spring Security 过滤链与 H2 持久化层，覆盖：</p>
 *
 * <ol>
 *   <li>GET 结算视图：200、每人净额（应收正 / 应付负、Σ=0）、建议转账（{@code from/to/amount}）、
 *       {@code allSettled}（需求 5.2、5.4）。</li>
 *   <li>非成员越权：404（不泄漏账本存在性，需求 9.4）。</li>
 *   <li>无令牌：401 {@code UNAUTHENTICATED}。</li>
 * </ol>
 *
 * <p>账本 / 成员 / 支出 / 结算直接以 Repository 播种，令牌用 app.jwt.secret 手工签发（JWT 过滤器无状态、
 * 不查库，签名有效即认证通过）。结算视图以<b>路径参数</b> {@code ledgerId} 指定账本。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AaSettlementControllerTest {

    private static final long ALICE = 2001L;
    private static final long BOB = 2002L;
    private static final long CAROL = 2003L;
    private static final long OUTSIDER = 2009L;

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
    private AaSettlementRepository settlementRepository;

    // ---------------- 1) GET 结算视图 ----------------

    @Test
    void settlement_returns200_withNetsAndSuggestedTransfers() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");
        // Alice 付 90，三人均分 → Alice 应收 60，Bob/Carol 各应付 30。
        createEvenExpense(ledger.getId(), cat.getId(), acc.getId(), "90.00");

        ResponseEntity<String> response = get(
                "/api/aa/" + ledger.getId() + "/settlement", memberHeaders(ALICE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = parse(response);
        assertThat(json).containsEntry("ledgerId", ledger.getId().intValue());
        assertThat(json).containsEntry("allSettled", false);

        // 每人净额（应收正 / 应付负），Σ = 0。
        Map<Long, BigDecimal> net = nets(json);
        assertThat(net.get(ALICE)).isEqualByComparingTo("60.00");
        assertThat(net.get(BOB)).isEqualByComparingTo("-30.00");
        assertThat(net.get(CAROL)).isEqualByComparingTo("-30.00");
        assertThat(net.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0.00");

        // 建议转账：两笔均指向 Alice，金额之和 = 总应付 60（需求 5.4）。
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transfers =
                (List<Map<String, Object>>) json.get("suggestedTransfers");
        assertThat(transfers).hasSize(2);
        BigDecimal transferSum = transfers.stream()
                .map(t -> new BigDecimal(t.get("amount").toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(transferSum).isEqualByComparingTo("60.00");
        assertThat(transfers).allSatisfy(t ->
                assertThat(Long.valueOf(t.get("toUserId").toString())).isEqualTo(ALICE));
    }

    @Test
    void settlement_fullySettled_returns200_allSettledTrue() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");
        createEvenExpense(ledger.getId(), cat.getId(), acc.getId(), "90.00");
        seedSettlement(ledger.getId(), BOB, ALICE, "30.00");
        seedSettlement(ledger.getId(), CAROL, ALICE, "30.00");

        ResponseEntity<String> response = get(
                "/api/aa/" + ledger.getId() + "/settlement", memberHeaders(ALICE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = parse(response);
        assertThat(json).containsEntry("allSettled", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> transfers =
                (List<Map<String, Object>>) json.get("suggestedTransfers");
        assertThat(transfers).isEmpty();
    }

    // ---------------- 2) 非成员越权 → 404（需求 9.4）----------------

    @Test
    void settlement_byNonMember_returnsNotFound() {
        Ledger ledger = seedAaLedger(false);

        ResponseEntity<String> response = get(
                "/api/aa/" + ledger.getId() + "/settlement", memberHeaders(OUTSIDER));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ---------------- 3) 无令牌 → 401 ----------------

    @Test
    void settlement_withoutToken_returnsUnauthenticated() {
        Ledger ledger = seedAaLedger(false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = get("/api/aa/" + ledger.getId() + "/settlement", headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(parse(response)).containsEntry("code", "UNAUTHENTICATED");
    }

    // ---------------- 4) POST 结清一条（任务 4.2 / 需求 6.1-6.4、6.6）----------------

    @Test
    void settle_asReceiver_returns201_creditsAccount_andRecordsSettlement() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");
        // Alice 付 90 三人均分 → Alice 应收 60（账户 300 → 210）。
        createEvenExpense(ledger.getId(), cat.getId(), acc.getId(), "90.00");

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fromUserId", BOB);
        body.put("amount", "30.00");
        body.put("myAccountId", acc.getId());
        ResponseEntity<String> response = postSettlement(ledger.getId(), ALICE, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> json = parse(response);
        assertThat(Long.valueOf(json.get("fromUserId").toString())).isEqualTo(BOB);
        assertThat(Long.valueOf(json.get("toUserId").toString())).isEqualTo(ALICE);
        assertThat(new BigDecimal(json.get("amount").toString())).isEqualByComparingTo("30.00");
        assertThat(Long.valueOf(json.get("toAccountId").toString())).isEqualTo(acc.getId());
        assertThat(json.get("fromAccountId")).isNull();

        // 账户 +30 → 240。
        assertThat(accountRepository.findById(acc.getId()).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("240.00");
        // 结算落库。
        assertThat(settlementRepository.findByLedgerId(ledger.getId())).hasSize(1);
        // 结算后 Alice 应收降到 30。
        Map<String, Object> view = parse(get(
                "/api/aa/" + ledger.getId() + "/settlement", memberHeaders(ALICE)));
        assertThat(nets(view).get(ALICE)).isEqualByComparingTo("30.00");
    }

    @Test
    void settle_amountExceedsOwed_returns400_invalid() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");
        createEvenExpense(ledger.getId(), cat.getId(), acc.getId(), "90.00");

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fromUserId", BOB);
        body.put("amount", "40.00"); // Bob 只欠 30
        body.put("myAccountId", acc.getId());
        ResponseEntity<String> response = postSettlement(ledger.getId(), ALICE, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(response)).containsEntry("code", "AA_SETTLEMENT_INVALID");
        assertThat(settlementRepository.findByLedgerId(ledger.getId())).isEmpty();
    }

    @Test
    void settle_byNonMember_returnsNotFound() {
        Ledger ledger = seedAaLedger(false);

        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fromUserId", BOB);
        body.put("amount", "30.00");
        body.put("myAccountId", 123456L);
        ResponseEntity<String> response = postSettlement(ledger.getId(), OUTSIDER, body);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void settle_withoutToken_returnsUnauthenticated() {
        Ledger ledger = seedAaLedger(false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(com.damien.youyu.security.CurrentLedger.HEADER, String.valueOf(ledger.getId()));
        Map<String, Object> body = Map.of("fromUserId", BOB, "amount", "30.00", "myAccountId", 1L);
        ResponseEntity<String> response = rest.exchange(url("/api/aa/settlements"), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(parse(response)).containsEntry("code", "UNAUTHENTICATED");
    }

    /** POST /api/aa/settlements：以指定成员令牌 + X-Ledger-Id 头结清一条。 */
    private ResponseEntity<String> postSettlement(Long ledgerId, long userId, Map<String, Object> body) {
        HttpHeaders headers = memberHeaders(userId);
        headers.set(com.damien.youyu.security.CurrentLedger.HEADER, String.valueOf(ledgerId));
        return rest.exchange(url("/api/aa/settlements"), HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    // ---------------- 5) POST 撤销一条（任务 4.3 / 需求 6.5）----------------

    @Test
    void revert_returns200_rollsBackAccount_andRestoresDebt() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");
        createEvenExpense(ledger.getId(), cat.getId(), acc.getId(), "90.00");
        // Alice 收款方结清 Bob 的 30：账户 210 → 240。
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fromUserId", BOB);
        body.put("amount", "30.00");
        body.put("myAccountId", acc.getId());
        Map<String, Object> created = parse(postSettlement(ledger.getId(), ALICE, body));
        Long settlementId = Long.valueOf(created.get("id").toString());
        assertThat(accountRepository.findById(acc.getId()).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("240.00");

        ResponseEntity<String> response = postRevert(ledger.getId(), ALICE, settlementId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 账户回滚 240 → 210。
        assertThat(accountRepository.findById(acc.getId()).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo("210.00");
        // 结算被标记撤销，净额计算忽略 → 债务恢复：Alice 应收回到 60。
        assertThat(settlementRepository.findById(settlementId).orElseThrow().getRevertedAt())
                .isNotNull();
        Map<String, Object> view = parse(get(
                "/api/aa/" + ledger.getId() + "/settlement", memberHeaders(ALICE)));
        assertThat(nets(view).get(ALICE)).isEqualByComparingTo("60.00");
    }

    @Test
    void revert_alreadyReverted_returns400_invalid() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");
        createEvenExpense(ledger.getId(), cat.getId(), acc.getId(), "90.00");
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fromUserId", BOB);
        body.put("amount", "30.00");
        body.put("myAccountId", acc.getId());
        Long settlementId = Long.valueOf(parse(postSettlement(ledger.getId(), ALICE, body))
                .get("id").toString());
        postRevert(ledger.getId(), ALICE, settlementId);

        ResponseEntity<String> response = postRevert(ledger.getId(), ALICE, settlementId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(response)).containsEntry("code", "AA_SETTLEMENT_INVALID");
    }

    @Test
    void revert_byNonSettlerCounterparty_returnsForbidden() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");
        createEvenExpense(ledger.getId(), cat.getId(), acc.getId(), "90.00");
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fromUserId", BOB);
        body.put("amount", "30.00");
        body.put("myAccountId", acc.getId());
        Long settlementId = Long.valueOf(parse(postSettlement(ledger.getId(), ALICE, body))
                .get("id").toString());

        // Bob 是另一方当事人但账户未被动过 → 无权撤销。
        ResponseEntity<String> response = postRevert(ledger.getId(), BOB, settlementId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(parse(response)).containsEntry("code", "LEDGER_FORBIDDEN");
        assertThat(settlementRepository.findById(settlementId).orElseThrow().getRevertedAt()).isNull();
    }

    @Test
    void revert_byNonMember_returnsNotFound() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");
        createEvenExpense(ledger.getId(), cat.getId(), acc.getId(), "90.00");
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fromUserId", BOB);
        body.put("amount", "30.00");
        body.put("myAccountId", acc.getId());
        Long settlementId = Long.valueOf(parse(postSettlement(ledger.getId(), ALICE, body))
                .get("id").toString());

        ResponseEntity<String> response = postRevert(ledger.getId(), OUTSIDER, settlementId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void revert_withoutToken_returnsUnauthenticated() {
        Ledger ledger = seedAaLedger(false);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(com.damien.youyu.security.CurrentLedger.HEADER, String.valueOf(ledger.getId()));
        ResponseEntity<String> response = rest.exchange(
                url("/api/aa/settlements/1/revert"), HttpMethod.POST,
                new HttpEntity<>(null, headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(parse(response)).containsEntry("code", "UNAUTHENTICATED");
    }

    /** POST /api/aa/settlements/{id}/revert：以指定成员令牌 + X-Ledger-Id 头撤销一条。 */
    private ResponseEntity<String> postRevert(Long ledgerId, long userId, Long settlementId) {
        HttpHeaders headers = memberHeaders(userId);
        headers.set(com.damien.youyu.security.CurrentLedger.HEADER, String.valueOf(ledgerId));
        return rest.exchange(url("/api/aa/settlements/" + settlementId + "/revert"),
                HttpMethod.POST, new HttpEntity<>(null, headers), String.class);
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

    /** 经 AA 记账接口创建一笔均分支出（Alice 付款），确保净额来源与生产链路一致。 */
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

    private AaSettlement seedSettlement(Long ledgerId, long from, long to, String amount) {
        AaSettlement s = new AaSettlement();
        s.setLedgerId(ledgerId);
        s.setFromUserId(from);
        s.setToUserId(to);
        s.setAmount(new BigDecimal(amount));
        s.setSettledBy(from);
        s.setSettledAt(LocalDateTime.now());
        return settlementRepository.save(s);
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** 已认证成员的请求头：Bearer 令牌 + JSON。 */
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
        List<Map<String, Object>> raw = (List<Map<String, Object>>) json.get("nets");
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
