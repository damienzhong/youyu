package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
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
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * AA 账本<b>全流程端到端联调</b>集成测试（任务 8.1，关联需求全部）。
 *
 * <p>照抄 {@link AaLifecycleIntegrationTest} / {@link AaExpenseControllerTest} 的
 * {@code @SpringBootTest}(RANDOM_PORT) + {@code TestRestTemplate} + 手工签发 JWT 范式，经真实 HTTP、
 * 真实 Spring Security 过滤链、真实 {@code CurrentLedger}（{@code X-Ledger-Id} 解析）与 H2 持久化，
 * 串起<b>一整条主链路</b>，验证跨端点顺序编排在真实链路成立：</p>
 *
 * <ol>
 *   <li><b>建 AA 账本</b>：{@code POST /api/ledgers}（{@code type=AA}）→ 创建者 Alice 为 OWNER 成员
 *       （需求 1.1）。</li>
 *   <li><b>邀请加入</b>：OWNER {@code POST /api/ledgers/{id}/invite} 生成邀请码 → Bob、Carol 各自
 *       {@code POST /api/ledgers/join} 加入为成员（需求 2.1、2.2、2.3）；{@code GET .../members} 验证三人在册。</li>
 *   <li><b>多笔记账</b>：本人付 / 他人付、均分 / 自定义混合（需求 3.1-3.7、4.1-4.5）——每笔校验账户扣款
 *       （本人付扣实付全额、他人付不动本人账户，需求 3.2、3.7、7.1）与分摊守恒（Σ=总额，Property 1）。</li>
 *   <li><b>概览三口径</b>：{@code GET /api/aa/{id}/overview} 验证账户已支出 / 我的消费 / 待收回三口径
 *       与每人净额（应收正 / 应付负、Σ=0，需求 4.4、5.1、7.1、7.2）。</li>
 *   <li><b>结算清零</b>：{@code GET /api/aa/{id}/settlement} 取最少转账建议（笔数 ≤ n−1，需求 5.3），
 *       逐条由债务方 {@code POST /api/aa/settlements} 结清 → 账户按方向增减、全体净额归 0、
 *       {@code allSettled=true}（需求 5.4、6.1-6.6、8.1）。</li>
 *   <li><b>归档只读</b>：{@code POST /api/ledgers/{id}/archive} 归档（已结清无需 force）→ 写操作
 *       （记账）一律 409 {@code AA_LEDGER_ARCHIVED}，只读视图仍 200 可读（需求 8.3、9.5）。</li>
 * </ol>
 *
 * <p>账本 / 成员 / 邀请 / 记账 / 结算 / 归档全部经真实 AA 端点驱动，确保与生产链路一致；账户与分类以
 * Repository 播种（账户 / 分类创建非本任务焦点），令牌用 {@code app.jwt.secret} 手工签发（JWT 过滤器
 * 无状态、不查库，签名有效即认证通过）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AaEndToEndFlowIntegrationTest {

    private static final long ALICE = 7001L;
    private static final long BOB = 7002L;
    private static final long CAROL = 7003L;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    void fullFlow_createInviteJoin_multipleExpenses_overview_settleToZero_archive() {
        // ============================================================================
        // 1) 建 AA 账本：Alice 经 POST /api/ledgers 创建 type=AA 账本，成为 OWNER 成员（需求 1.1）。
        // ============================================================================
        Map<String, Object> createBody = new HashMap<>();
        createBody.put("name", "毕业旅行 AA");
        createBody.put("type", "AA");
        ResponseEntity<String> created = post("/api/ledgers", createBody, authHeaders(ALICE));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> ledgerJson = parse(created);
        assertThat(ledgerJson).containsEntry("type", "AA");
        assertThat(ledgerJson).containsEntry("role", "OWNER");
        assertThat(ledgerJson).containsEntry("archived", false);
        Long ledgerId = Long.valueOf(ledgerJson.get("id").toString());

        // ============================================================================
        // 2) 邀请加入：OWNER 生成邀请码，Bob / Carol 各自凭码加入为成员（需求 2.1、2.2、2.3）。
        // ============================================================================
        ResponseEntity<String> invited = post("/api/ledgers/" + ledgerId + "/invite", null,
                authHeaders(ALICE));
        assertThat(invited.getStatusCode()).isEqualTo(HttpStatus.OK);
        String inviteCode = parse(invited).get("code").toString();
        assertThat(inviteCode).isNotBlank();

        joinLedger(inviteCode, BOB);
        joinLedger(inviteCode, CAROL);

        // 成员列表验证三人在册（Alice OWNER，Bob / Carol EDITOR）。
        List<Map<String, Object>> members = parseList(
                get("/api/ledgers/" + ledgerId + "/members", authHeaders(ALICE)));
        assertThat(members).hasSize(3);
        assertThat(members.stream().map(m -> Long.valueOf(m.get("userId").toString())).toList())
                .containsExactlyInAnyOrder(ALICE, BOB, CAROL);

        // 账户播种（账户创建非本任务焦点）；分类由 createExpense 惰性播种并复用。
        Account aliceAcc = seedAccount(ALICE, "300.00");
        Account bobAcc = seedAccount(BOB, "200.00");
        Account carolAcc = seedAccount(CAROL, "100.00");

        // ============================================================================
        // 3) 多笔记账（本人付 / 他人付、均分 / 自定义混合），逐笔校验账户扣款与分摊守恒。
        // ============================================================================
        // 3a) 本人付 + 均分：Alice 付 90，三人均分（各 30）。Alice 账户 300 → 210。
        Map<String, Object> exp1 = createExpense(ledgerId, ALICE, aliceAcc.getId(), "90.00", ALICE,
                "even", List.of(ALICE, BOB, CAROL), null, "聚餐");
        assertThat(exp1).containsEntry("payerUserId", (int) ALICE);
        assertThat(exp1.get("accountId")).isEqualTo(aliceAcc.getId().intValue());
        assertSplits(exp1, "90.00", Map.of(ALICE, "30.00", BOB, "30.00", CAROL, "30.00"));
        assertThat(balanceOf(aliceAcc.getId())).isEqualByComparingTo("210.00");

        // 3b) 他人付 + 均分：Bob 付 60，三人均分（各 20）。Bob 账户 200 → 140。
        //     Bob 记自己的付款；付款人为本人（Bob）故扣 Bob 账户，不动 Alice / Carol 账户。
        Map<String, Object> exp2 = createExpense(ledgerId, BOB, bobAcc.getId(), "60.00", BOB,
                "even", List.of(ALICE, BOB, CAROL), null, null);
        assertThat(exp2).containsEntry("payerUserId", (int) BOB);
        assertSplits(exp2, "60.00", Map.of(ALICE, "20.00", BOB, "20.00", CAROL, "20.00"));
        assertThat(balanceOf(bobAcc.getId())).isEqualByComparingTo("140.00");
        // 他人付款不触本人账户（需求 3.7、7.1）：Alice / Carol 账户不变。
        assertThat(balanceOf(aliceAcc.getId())).isEqualByComparingTo("210.00");
        assertThat(balanceOf(carolAcc.getId())).isEqualByComparingTo("100.00");

        // 3c) 本人付 + 自定义：Alice 付 60，自定义 A=10 / B=20 / C=30（Σ=60）。Alice 账户 210 → 150。
        Map<String, Object> exp3 = createExpense(ledgerId, ALICE, aliceAcc.getId(), "60.00", ALICE,
                "custom", List.of(ALICE, BOB, CAROL),
                List.of(
                        Map.of("userId", ALICE, "amount", "10.00"),
                        Map.of("userId", BOB, "amount", "20.00"),
                        Map.of("userId", CAROL, "amount", "30.00")),
                null);
        assertSplits(exp3, "60.00", Map.of(ALICE, "10.00", BOB, "20.00", CAROL, "30.00"));
        assertThat(balanceOf(aliceAcc.getId())).isEqualByComparingTo("150.00");

        // ============================================================================
        // 4) 概览三口径 + 每人净额（Alice 视角）。
        //    paid:  A=150, B=60,  C=0
        //    consumed: A=60, B=70, C=80
        //    net:   A=+90, B=-10, C=-80 （Σ=0）
        // ============================================================================
        Map<String, Object> overview = parse(get("/api/aa/" + ledgerId + "/overview", authHeaders(ALICE)));
        assertThat(overview).containsEntry("allSettled", false);
        Map<String, Object> calibers = asMap(overview.get("calibers"));
        // 账户已支出 = 自付款实付额 90 + 60 = 150（Bob 付的那笔不计入 Alice）。
        assertThat(new BigDecimal(calibers.get("accountPaid").toString())).isEqualByComparingTo("150.00");
        // 我的消费 = 自身分摊 30 + 20 + 10 = 60。
        assertThat(new BigDecimal(calibers.get("myConsumption").toString())).isEqualByComparingTo("60.00");
        // 待收回 = max(net, 0) = 90。
        assertThat(new BigDecimal(calibers.get("receivable").toString())).isEqualByComparingTo("90.00");

        Map<Long, BigDecimal> overviewNets = netsFrom(overview, "memberNets");
        assertThat(overviewNets.get(ALICE)).isEqualByComparingTo("90.00");
        assertThat(overviewNets.get(BOB)).isEqualByComparingTo("-10.00");
        assertThat(overviewNets.get(CAROL)).isEqualByComparingTo("-80.00");
        assertThat(overviewNets.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("0.00");

        // ============================================================================
        // 5) 结算清零：取建议转账（笔数 ≤ n−1），逐条由债务方结清，直至全体净额 0。
        //    建议：C→A 80、B→A 10（贪心，债务最大 ↔ 债权最大）。
        // ============================================================================
        Map<String, Object> settlementView = parse(
                get("/api/aa/" + ledgerId + "/settlement", authHeaders(ALICE)));
        assertThat(settlementView).containsEntry("allSettled", false);
        List<Map<String, Object>> transfers = asList(settlementView.get("suggestedTransfers"));
        assertThat(transfers).hasSizeLessThanOrEqualTo(2); // ≤ 成员数 − 1（需求 5.3）
        // 建议金额之和 = 总应付额 90。
        BigDecimal transferSum = transfers.stream()
                .map(t -> new BigDecimal(t.get("amount").toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(transferSum).isEqualByComparingTo("90.00");
        // 所有建议均指向债权人 Alice。
        assertThat(transfers.stream().map(t -> Long.valueOf(t.get("toUserId").toString())).toList())
                .allMatch(id -> id.equals(ALICE));

        // 逐条由债务方（fromUserId）作为付款方结清（本人账户 −amount、双方净额递减）。
        for (Map<String, Object> transfer : transfers) {
            long debtor = Long.parseLong(transfer.get("fromUserId").toString());
            long creditor = Long.parseLong(transfer.get("toUserId").toString());
            String amount = transfer.get("amount").toString();
            Long debtorAccountId = debtor == BOB ? bobAcc.getId() : carolAcc.getId();

            Map<String, Object> settleBody = new HashMap<>();
            settleBody.put("toUserId", creditor);
            settleBody.put("amount", amount);
            settleBody.put("myAccountId", debtorAccountId);
            ResponseEntity<String> settled = rest.exchange(url("/api/aa/settlements"), HttpMethod.POST,
                    new HttpEntity<>(settleBody, ledgerHeaders(debtor, ledgerId)), String.class);
            assertThat(settled.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Map<String, Object> settleJson = parse(settled);
            assertThat(settleJson).containsEntry("fromUserId", (int) debtor);
            assertThat(settleJson).containsEntry("toUserId", (int) creditor);
        }

        // 账户按方向增减（债务方本人侧扣款；债权人 Alice 侧未由其结清故账户不变）。
        assertThat(balanceOf(carolAcc.getId())).isEqualByComparingTo("20.00");  // 100 − 80
        assertThat(balanceOf(bobAcc.getId())).isEqualByComparingTo("130.00");   // 140 − 10
        assertThat(balanceOf(aliceAcc.getId())).isEqualByComparingTo("150.00"); // 收款方不代扣，账户不变

        // 全体净额归 0 + 已全部结清（需求 8.1）。
        Map<String, Object> settledView = parse(
                get("/api/aa/" + ledgerId + "/settlement", authHeaders(ALICE)));
        assertThat(settledView).containsEntry("allSettled", true);
        assertThat(asList(settledView.get("suggestedTransfers"))).isEmpty();
        Map<Long, BigDecimal> finalNets = netsFrom(settledView, "nets");
        assertThat(finalNets.values()).allSatisfy(n -> assertThat(n).isEqualByComparingTo("0.00"));

        // ============================================================================
        // 6) 归档：已结清 → 无需 force。归档后账本只读——写操作被拒、只读视图仍可访问（需求 8.3、9.5）。
        // ============================================================================
        ResponseEntity<String> archived = post("/api/ledgers/" + ledgerId + "/archive", null,
                authHeaders(ALICE));
        assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(archived)).containsEntry("archived", true);

        // 归档后记一笔被拒 409 AA_LEDGER_ARCHIVED（需求 9.5）。
        Map<String, Object> blockedExpense = Map.of(
                "amount", "30.00",
                "categoryId", seedOrReuseCategoryId(ledgerId),
                "payerUserId", ALICE,
                "payerAccountId", aliceAcc.getId(),
                "splitMode", "even",
                "participants", List.of(ALICE, BOB, CAROL));
        ResponseEntity<String> blocked = post("/api/aa/expenses", blockedExpense,
                ledgerHeaders(ALICE, ledgerId));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(blocked)).containsEntry("code", "AA_LEDGER_ARCHIVED");

        // 只读视图仍可访问（需求 8.3：归档保留查看）。
        ResponseEntity<String> archivedOverview = get("/api/aa/" + ledgerId + "/overview",
                authHeaders(ALICE));
        assertThat(archivedOverview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(archivedOverview)).containsEntry("archived", true);
        assertThat(get("/api/aa/" + ledgerId + "/settlement", authHeaders(ALICE)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------- 流程辅助 ----------------------------------

    /** 凭邀请码加入账本，断言成功并回显该账本。 */
    private void joinLedger(String code, long userId) {
        ResponseEntity<String> joined = post("/api/ledgers/join", Map.of("code", code),
                authHeaders(userId));
        assertThat(joined.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * 经 {@code POST /api/aa/expenses} 记一笔 AA 支出，断言 201 并回显该笔（含分摊明细）。
     *
     * @param payerAccountId 付款账户（付款人为本人时使用；他人付款时服务端会忽略并置空）
     * @param customShares   自定义分摊列表（{@code splitMode=custom} 时必填，否则传 {@code null}）
     */
    private Map<String, Object> createExpense(Long ledgerId, long asUser, Long payerAccountId,
            String amount, long payerUserId, String splitMode, List<Long> participants,
            List<Map<String, Object>> customShares, String note) {
        Map<String, Object> body = new HashMap<>();
        body.put("amount", amount);
        body.put("categoryId", seedOrReuseCategoryId(ledgerId));
        body.put("payerUserId", payerUserId);
        body.put("payerAccountId", payerAccountId);
        body.put("splitMode", splitMode);
        body.put("participants", participants);
        if (customShares != null) {
            body.put("customShares", customShares);
        }
        if (note != null) {
            body.put("note", note);
        }
        ResponseEntity<String> response = post("/api/aa/expenses", body, ledgerHeaders(asUser, ledgerId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return parse(response);
    }

    /** 断言一笔支出的分摊守恒（Σ=总额）且各参与人份额与期望一致（Property 1 / 需求 4.5）。 */
    private void assertSplits(Map<String, Object> expenseJson, String expectedTotal,
            Map<Long, String> expectedShares) {
        List<Map<String, Object>> splits = asList(expenseJson.get("splits"));
        assertThat(splits).hasSize(expectedShares.size());
        BigDecimal sum = BigDecimal.ZERO;
        Map<Long, BigDecimal> byUser = new HashMap<>();
        for (Map<String, Object> s : splits) {
            BigDecimal share = new BigDecimal(s.get("amount").toString());
            byUser.put(Long.valueOf(s.get("userId").toString()), share);
            sum = sum.add(share);
        }
        assertThat(sum).isEqualByComparingTo(expectedTotal);
        expectedShares.forEach((userId, expected) ->
                assertThat(byUser.get(userId)).isEqualByComparingTo(expected));
    }

    // ---------------------------------- 数据播种 ----------------------------------

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

    private Long reusedCategoryId;

    /** 惰性建一个本账本的分类并复用，避免每笔重复播种。 */
    private Long seedOrReuseCategoryId(Long ledgerId) {
        if (reusedCategoryId == null) {
            reusedCategoryId = seedCategory(ledgerId).getId();
        }
        return reusedCategoryId;
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

    /** 已认证请求头：Bearer 令牌 + JSON。 */
    private HttpHeaders authHeaders(long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(userId, jwtSecret, Duration.ofHours(1)));
        return headers;
    }

    /** 已认证请求头 + X-Ledger-Id（AA 记账 / 结算需按账本隔离）。 */
    private HttpHeaders ledgerHeaders(long userId, Long ledgerId) {
        HttpHeaders headers = authHeaders(userId);
        headers.set(com.damien.youyu.security.CurrentLedger.HEADER, String.valueOf(ledgerId));
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

    // ---------------------------------- JSON 解析 ----------------------------------

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
        try {
            return MAPPER.readValue(raw, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            throw new AssertionError("响应体不是合法 JSON 数组: " + raw, e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object node) {
        return (Map<String, Object>) node;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> asList(Object node) {
        return (List<Map<String, Object>>) node;
    }

    /** 从响应体的净额数组（{@code memberNets} / {@code nets}）解析为 {@code userId → net}。 */
    private Map<Long, BigDecimal> netsFrom(Map<String, Object> json, String field) {
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> n : asList(json.get(field))) {
            out.put(Long.valueOf(n.get("userId").toString()), new BigDecimal(n.get("net").toString()));
        }
        return out;
    }
}
