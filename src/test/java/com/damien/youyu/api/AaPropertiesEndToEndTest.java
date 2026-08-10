package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
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
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionSplitRepository;
import com.damien.youyu.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 任务 8.2：<b>在真实链路上校验 design.md「Correctness Properties」Property 1–6，并验证金额闭合</b>。
 *
 * <p>本类补齐 {@link AaEndToEndAcceptanceTest}（任务 8.1）之外的真实 HTTP 覆盖缺口——<b>撤销结算（revert）</b>
 * 与「<b>全部账本</b>」聚合的特性隔离，均经真实端点（{@code @SpringBootTest} RANDOM_PORT + {@code TestRestTemplate}
 * + 真实 Spring Security 过滤链 + H2 持久化）驱动，不直接操纵服务 / 仓储写业务数据。属性在真实链路各阶段
 * （记账后 → 结清后 → 撤销后）逐一断言，确保 P1–6 在生产同款链路上成立、金额端到端闭合。</p>
 *
 * <h4>各属性覆盖点（本类 = 真实 HTTP 链路；括注同属性已覆盖处）：</h4>
 * <ul>
 *   <li><b>P1 分摊守恒</b>：每笔支出经 {@code POST /api/aa/expenses} 建成后，其 splits Σ = 总额。
 *       （纯核心 {@code AaMathPropertyTest}；HTTP 记账 {@code AaEndToEndAcceptanceTest}）</li>
 *   <li><b>P2 净额闭合</b>：Σ 成员 net = 0，在<b>记账后、结清后、撤销后</b>三阶段均成立。
 *       （纯核心 {@code AaMathPropertyTest} / {@code AaSettlementConservationPropertyTest}；
 *       服务+DB {@code AaSettlementConservationIntegrationTest}）<b>撤销后阶段为本类新增 HTTP 覆盖。</b></li>
 *   <li><b>P3 清算可清零</b>：{@code GET .../settlement} 取建议，经 {@code POST /api/aa/settlements} 逐条执行后
 *       全体 net=0，且结算笔数 ≤ 成员数−1。（纯核心 / 服务+DB 已覆盖；本类为真实 HTTP 执行链路）</li>
 *   <li><b>P4 账户守恒</b>：每人「账户初始 − 当前」= 其 overview.accountPaid，在记账 / 结清 / <b>撤销</b>各阶段
 *       精确成立（真实现金闭合）。（服务+DB {@code AaSettlementConservationIntegrationTest}）
 *       <b>撤销后账户闭合为本类新增 HTTP 覆盖。</b></li>
 *   <li><b>P5 消费口径隔离</b>：overview.myConsumption = Σ 自身 splits，且<b>不因结算 / 撤销而变化</b>；
 *       账户余额只反映真实现金。（HTTP {@code AaEndToEndAcceptanceTest}）本类补充「结算 / 撤销前后消费不变」。</li>
 *   <li><b>P6 特性隔离</b>：为同一用户另建<b>非 AA（个人）账本</b>并记一笔普通支出，经真实聚合端点
 *       {@code GET /api/all/transactions} 断言：结果<b>纳入</b>非 AA 账本流水、<b>排除</b> AA 账本全部流水
 *       （aa_expense / aa_settlement）。（服务级 {@code AggregateServiceTest}）<b>本类为真实 HTTP 聚合链路覆盖。</b></li>
 * </ul>
 *
 * <p>仅用户 / 账户 / 分类以 Repository 播种（注册主体 / 本人资产 / 账本内分类）；账本、成员、支出、结算、
 * 撤销一律经真实 AA 端点产生。令牌用 app.jwt.secret 手工签发（JWT 过滤器无状态、不查库）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AaPropertiesEndToEndTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 固定发生时间：让 AA 支出与非 AA 支出落在同一自然月，令 P6 的「排除」断言非平凡。 */
    private static final String OCCURRED_AA = "2025-06-15T10:00:00";
    private static final String OCCURRED_NON_AA = "2025-06-15T09:00:00";
    private static final String QUERY_MONTH = "2025-06";

    @LocalServerPort
    private int port;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private TransactionSplitRepository splitRepository;

    @Test
    void properties1to6_holdOnRealHttpLink_withSettleAndRevert_andAggregateIsolation() {
        // ============================ 播种：注册用户 + 本人账户 ============================
        long alice = seedUser("Alice").getId();
        long bob = seedUser("Bob").getId();
        long carol = seedUser("Carol").getId();
        final BigDecimal init = new BigDecimal("1000.00");
        Account aliceAcc = seedAccount(alice, init);
        Account bobAcc = seedAccount(bob, init);
        Account carolAcc = seedAccount(carol, init);
        Map<Long, Account> accByUser = Map.of(alice, aliceAcc, bob, bobAcc, carol, carolAcc);

        // ============================ 建 AA 账本 + 邀请加入（真实端点） ============================
        Long ledgerId = createAaLedger(alice, "东京旅行 AA");
        String code = invite(alice, ledgerId);
        join(bob, code);
        join(carol, code);
        List<Long> members = List.of(alice, bob, carol);
        Long categoryId = seedCategory(ledgerId).getId();

        // ============================ 多笔记账（本人/他人付 × 均分/自定义） ============================
        // E1 Alice 付 90 均分（各 30）→ Alice 账户 −90。
        Long e1 = createExpense(alice, ledgerId, Map.of(
                "amount", "90.00", "categoryId", categoryId, "occurredAt", OCCURRED_AA,
                "payerUserId", alice, "payerAccountId", aliceAcc.getId(),
                "splitMode", "even", "participants", members));
        // E2 Bob 付 120 自定义 A20/B40/C60 → Bob 账户 −120。
        Long e2 = createExpense(bob, ledgerId, Map.of(
                "amount", "120.00", "categoryId", categoryId, "occurredAt", OCCURRED_AA,
                "payerUserId", bob, "payerAccountId", bobAcc.getId(),
                "splitMode", "custom", "participants", members,
                "customShares", List.of(share(alice, "20.00"), share(bob, "40.00"), share(carol, "60.00"))));
        // E3 Alice 代记付款人=Carol 30 均分（各 10）→ 不触任何本人账户。
        Long e3 = createExpense(alice, ledgerId, Map.of(
                "amount", "30.00", "categoryId", categoryId, "occurredAt", OCCURRED_AA,
                "payerUserId", carol,
                "splitMode", "even", "participants", members));

        // -------- P1 分摊守恒：每笔 splits Σ = 总额（以真实落库分摊校验，需求 3.3/3.4/4.5） --------
        assertSplitSum(e1, "90.00");
        assertSplitSum(e2, "120.00");
        assertSplitSum(e3, "30.00");

        // 期望净额：Alice +30、Bob +40、Carol −70（Σ=0）。期望消费：Alice 60 / Bob 80 / Carol 100。
        Map<Long, String> expectedConsumption = Map.of(alice, "60.00", bob, "80.00", carol, "100.00");

        // -------- 记账后：P2（Σnet=0）、P4（账户闭合）、P5（消费=Σ自摊、账户只反映真实现金） --------
        assertNetsSumToZero(alice, ledgerId, members);
        assertMemberNet(alice, ledgerId, alice, "30.00");
        assertMemberNet(alice, ledgerId, bob, "40.00");
        assertMemberNet(alice, ledgerId, carol, "-70.00");
        for (long u : members) {
            assertConsumptionEquals(u, ledgerId, expectedConsumption.get(u));
            assertAccountClosure(u, ledgerId, accByUser.get(u).getId(), init);
        }
        // 账户余额只反映真实现金流出（本人付款扣款；代记不动账户）。
        assertBalance(aliceAcc.getId(), "910.00");
        assertBalance(bobAcc.getId(), "880.00");
        assertBalance(carolAcc.getId(), "1000.00");

        // ============================ P3：取建议 + 逐条执行（真实 settle 端点） ============================
        Map<String, Object> settlementView = parse(get("/api/aa/" + ledgerId + "/settlement", auth(alice)));
        assertThat(settlementView).containsEntry("allSettled", false);
        List<Map<String, Object>> transfers = suggestedTransfers(settlementView);
        // 笔数 ≤ 成员数 − 1；金额之和 = 总应付 70（需求 5.3/5.4）。
        assertThat(transfers.size()).isLessThanOrEqualTo(members.size() - 1);
        assertThat(sum(transfers.stream().map(t -> money(t.get("amount"))).toList()))
                .isEqualByComparingTo("70.00");

        // 逐条以「付款方（from）」身份用本方账户结清，收集结算 id 供后续撤销。
        List<long[]> settlements = new ArrayList<>(); // [settlementId, fromUserId, toUserId]
        List<BigDecimal> settledAmounts = new ArrayList<>();
        for (Map<String, Object> t : transfers) {
            long from = Long.parseLong(t.get("fromUserId").toString());
            long to = Long.parseLong(t.get("toUserId").toString());
            BigDecimal amount = money(t.get("amount"));
            Map<String, Object> body = new java.util.HashMap<>();
            body.put("toUserId", to);
            body.put("amount", amount.toPlainString());
            body.put("myAccountId", accByUser.get(from).getId());
            ResponseEntity<String> resp = post("/api/aa/settlements", body, ledgerAuth(from, ledgerId));
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Map<String, Object> s = parse(resp);
            settlements.add(new long[] {
                    Long.parseLong(s.get("id").toString()), from, to });
            settledAmounts.add(amount);
        }

        // -------- 结清后：P3（全体 net=0 + allSettled）、P2（Σnet=0）、P4（账户闭合）、P5（消费不变） --------
        Map<String, Object> settledView = parse(get("/api/aa/" + ledgerId + "/settlement", auth(bob)));
        assertThat(settledView).containsEntry("allSettled", true);
        assertThat(suggestedTransfers(settledView)).isEmpty();
        Map<Long, BigDecimal> netsAfterSettle = nets(settledView);
        for (long u : members) {
            assertThat(netsAfterSettle.get(u)).as("net after settle for %d", u).isEqualByComparingTo("0.00");
        }
        assertThat(sum(netsAfterSettle.values())).isEqualByComparingTo("0.00");
        for (long u : members) {
            assertConsumptionEquals(u, ledgerId, expectedConsumption.get(u)); // P5：结算不改变消费
            assertAccountClosure(u, ledgerId, accByUser.get(u).getId(), init); // P4：结清后账户仍闭合
        }

        // ============================ 撤销一条结算（真实 revert 端点，本类核心补充） ============================
        long[] toRevert = settlements.get(0);
        long revertId = toRevert[0];
        long revFrom = toRevert[1];
        BigDecimal revAmount = settledAmounts.get(0);
        BigDecimal revFromBalanceBefore = balance(accByUser.get(revFrom).getId());

        ResponseEntity<String> reverted = post(
                "/api/aa/settlements/" + revertId + "/revert", null, ledgerAuth(revFrom, ledgerId));
        assertThat(reverted.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 撤销精确回滚结算方账户：付款方结清被撤销 → 账户 +amount（抵消原 −amount）。
        assertThat(balance(accByUser.get(revFrom).getId()))
                .isEqualByComparingTo(revFromBalanceBefore.add(revAmount));

        // -------- 撤销后：P2（Σnet=0 仍成立）、P4（账户仍闭合）、P5（消费仍不变） --------
        Map<String, Object> afterRevertView = parse(get("/api/aa/" + ledgerId + "/settlement", auth(alice)));
        assertThat(afterRevertView).containsEntry("allSettled", false); // 被撤销的债务重新出现
        Map<Long, BigDecimal> netsAfterRevert = nets(afterRevertView);
        assertThat(sum(netsAfterRevert.values())).as("Σnet=0 after revert").isEqualByComparingTo("0.00");
        // 被撤销转账的双方债务精确恢复（其余成员保持已结清）。
        long revTo = toRevert[2];
        assertThat(netsAfterRevert.get(revFrom)).isEqualByComparingTo(revAmount.negate());
        assertThat(netsAfterRevert.get(revTo)).isEqualByComparingTo(revAmount);
        for (long u : members) {
            assertConsumptionEquals(u, ledgerId, expectedConsumption.get(u)); // P5：撤销不改变消费
            assertAccountClosure(u, ledgerId, accByUser.get(u).getId(), init); // P4：撤销后账户仍闭合
        }

        // ============================ P6：非 AA 账本纳入聚合、AA 账本被排除（真实聚合端点） ============================
        // 为 Alice 另建个人账本并经真实交易端点记一笔普通支出。
        Long personalLedgerId = createPersonalLedger(alice, "个人账本");
        Long personalCategoryId = seedCategory(personalLedgerId).getId();
        Map<String, Object> normalExpense = new java.util.HashMap<>();
        normalExpense.put("type", "expense");
        normalExpense.put("amount", "55.00");
        normalExpense.put("accountId", aliceAcc.getId());
        normalExpense.put("categoryId", personalCategoryId);
        normalExpense.put("occurredAt", OCCURRED_NON_AA);
        ResponseEntity<String> normalCreated = post(
                "/api/transactions", normalExpense, ledgerAuth(alice, personalLedgerId));
        assertThat(normalCreated.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // GET /api/all/transactions?month=2025-06：应纳入个人账本这一笔、排除 AA 账本全部流水。
        List<Map<String, Object>> aggregated = parseList(
                get("/api/all/transactions?month=" + QUERY_MONTH, auth(alice)));
        // 非 AA 账本的普通支出被纳入。
        assertThat(aggregated).anySatisfy(t ->
                assertThat(Long.parseLong(t.get("ledgerId").toString())).isEqualTo(personalLedgerId));
        // AA 账本整本被排除：无任一条来自 AA 账本，且不含 aa_expense / aa_settlement 类型。
        assertThat(aggregated).noneSatisfy(t ->
                assertThat(Long.parseLong(t.get("ledgerId").toString())).isEqualTo(ledgerId));
        assertThat(aggregated).allSatisfy(t -> {
            Object type = t.get("type");
            assertThat(type == null ? "" : type.toString())
                    .doesNotContain("aa_expense").doesNotContain("aa_settlement");
        });
    }

    // ---------------------------------- 属性断言辅助 ----------------------------------

    /** P2：全体成员净额之和 = 0（从 overview 的 memberNets 读取）。 */
    private void assertNetsSumToZero(long asUser, Long ledgerId, List<Long> members) {
        Map<Long, BigDecimal> memberNets = memberNets(overview(asUser, ledgerId));
        assertThat(memberNets.keySet()).containsAll(members);
        assertThat(sum(memberNets.values())).as("Σ member nets = 0").isEqualByComparingTo("0.00");
    }

    private void assertMemberNet(long asUser, Long ledgerId, long member, String expected) {
        assertThat(memberNets(overview(asUser, ledgerId)).get(member)).isEqualByComparingTo(expected);
    }

    /** P5：某用户 overview.myConsumption = Σ 其自身各笔分摊额，且账户口径独立。 */
    private void assertConsumptionEquals(long user, Long ledgerId, String expected) {
        Map<String, Object> cal = caliber(overview(user, ledgerId));
        assertThat(money(cal.get("myConsumption"))).as("myConsumption user %d", user)
                .isEqualByComparingTo(expected);
    }

    /** P4：账户「初始 − 当前」= overview.accountPaid（真实现金净流出，账户守恒）。 */
    private void assertAccountClosure(long user, Long ledgerId, Long accountId, BigDecimal initial) {
        BigDecimal current = balance(accountId);
        BigDecimal netOut = initial.subtract(current);
        assertThat(netOut).as("account closure user %d", user)
                .isEqualByComparingTo(money(caliber(overview(user, ledgerId)).get("accountPaid")));
    }

    private void assertSplitSum(Long txId, String expectedTotal) {
        BigDecimal sum = splitRepository.findByTransactionId(txId).stream()
                .map(com.damien.youyu.domain.TransactionSplit::getShareAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(expectedTotal);
    }

    private void assertBalance(Long accountId, String expected) {
        assertThat(balance(accountId)).isEqualByComparingTo(expected);
    }

    private BigDecimal balance(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    private Map<String, Object> overview(long asUser, Long ledgerId) {
        return parse(get("/api/aa/" + ledgerId + "/overview", auth(asUser)));
    }

    // ---------------------------------- 真实端点调用辅助 ----------------------------------

    private Long createAaLedger(long owner, String name) {
        ResponseEntity<String> created = post("/api/ledgers",
                Map.of("name", name, "type", "AA"), auth(owner));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Long.valueOf(parse(created).get("id").toString());
    }

    private Long createPersonalLedger(long owner, String name) {
        ResponseEntity<String> created = post("/api/ledgers",
                Map.of("name", name, "type", "PERSONAL"), auth(owner));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Long.valueOf(parse(created).get("id").toString());
    }

    private String invite(long owner, Long ledgerId) {
        ResponseEntity<String> invite = post("/api/ledgers/" + ledgerId + "/invite", null, auth(owner));
        assertThat(invite.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parse(invite).get("code").toString();
    }

    private void join(long user, String code) {
        assertThat(post("/api/ledgers/join", Map.of("code", code), auth(user)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private Long createExpense(long asUser, Long ledgerId, Map<String, Object> body) {
        ResponseEntity<String> response = post("/api/aa/expenses", body, ledgerAuth(asUser, ledgerId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Long.valueOf(parse(response).get("id").toString());
    }

    private Map<String, Object> share(long userId, String amount) {
        return Map.of("userId", userId, "amount", amount);
    }

    // ---------------------------------- 数据播种 ----------------------------------

    private User seedUser(String nickname) {
        LocalDateTime now = LocalDateTime.now();
        User u = new User();
        u.setNickname(nickname);
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.save(u);
    }

    private Account seedAccount(long userId, BigDecimal balance) {
        LocalDateTime now = LocalDateTime.now();
        Account a = new Account();
        a.setUserId(userId);
        a.setName("现金");
        a.setType(AccountType.CASH);
        a.setInitialBalance(balance);
        a.setCurrentBalance(balance);
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

    // ---------------------------------- HTTP / 解析辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> post(String path, Object body, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private HttpHeaders auth(long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(userId, jwtSecret, Duration.ofHours(1)));
        return headers;
    }

    private HttpHeaders ledgerAuth(long userId, Long ledgerId) {
        HttpHeaders headers = auth(userId);
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

    private static BigDecimal money(Object raw) {
        return new BigDecimal(raw.toString());
    }

    private static BigDecimal sum(java.util.Collection<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> caliber(Map<String, Object> overview) {
        return (Map<String, Object>) overview.get("calibers");
    }

    @SuppressWarnings("unchecked")
    private Map<Long, BigDecimal> memberNets(Map<String, Object> overview) {
        List<Map<String, Object>> raw = (List<Map<String, Object>>) overview.get("memberNets");
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> n : raw) {
            out.put(Long.valueOf(n.get("userId").toString()), money(n.get("net")));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, BigDecimal> nets(Map<String, Object> settlementView) {
        List<Map<String, Object>> raw = (List<Map<String, Object>>) settlementView.get("nets");
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> n : raw) {
            out.put(Long.valueOf(n.get("userId").toString()), money(n.get("net")));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> suggestedTransfers(Map<String, Object> settlementView) {
        return (List<Map<String, Object>>) settlementView.get("suggestedTransfers");
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
