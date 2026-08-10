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
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionSplitRepository;
import com.damien.youyu.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * AA 账本<b>端到端验收</b>测试（任务 8.1，需求「全部」端到端联调与验收）。
 *
 * <p>照抄 {@link AaLifecycleIntegrationTest} / {@link AaSettlementControllerTest} 的
 * {@code @SpringBootTest}(RANDOM_PORT) + {@code TestRestTemplate} + 手工签发 JWT 范式，经真实 HTTP、
 * 真实 Spring Security 过滤链与 H2 持久化，走通<b>整条 AA 生命周期</b>——刻意<b>只用真实端点</b>产生业务
 * 数据（不直接播种账本 / 成员 / 支出 / 结算），验证任务 3–6 建成的接口串起来端到端自洽、金额闭合：</p>
 *
 * <ol>
 *   <li><b>建 AA</b>：{@code POST /api/ledgers}（type=AA）创建，创建者登记为 owner（需求 1.1）。</li>
 *   <li><b>邀请加入</b>：owner {@code POST /api/ledgers/{id}/invite} 出码，其余注册用户
 *       {@code POST /api/ledgers/join} 加入为成员（需求 2.1-2.3）。</li>
 *   <li><b>多笔记账</b>：{@code POST /api/aa/expenses} 覆盖 本人付 / 他人付（代记）× 均分 / 自定义；
 *       断言仅本人付款扣本人账户、他人代记不触账户、分摊持久化（需求 3.2、3.5、3.7、4.5）。</li>
 *   <li><b>概览三口径</b>：{@code GET /api/aa/{id}/overview} 断言 账户已支出 / 我的消费 / 待收回
 *       三口径正确、成员净额 Σ=0、{@code allSettled=false}（需求 4.4、5.1、7.1、7.2、8.1）。</li>
 *   <li><b>结算清零</b>：{@code GET /api/aa/{id}/settlement} 取最少转账建议；各方经
 *       {@code POST /api/aa/settlements} 用本方账户逐条结清（付款方 / 收款方两侧都覆盖），把全体净额
 *       驱动到 0（需求 5.3、5.4、6.1-6.4、6.6）。</li>
 *   <li><b>结清后</b>：再取 overview/settlement 断言 {@code allSettled=true}、账户余额反映真实现金流动
 *       （需求 6.2、6.3、8.1）。</li>
 *   <li><b>归档</b>：{@code POST /api/ledgers/{id}/archive} 归档；断言 {@code archived=true} 且后续写
 *       （记账）被拒 {@code AA_LEDGER_ARCHIVED}（需求 8.3、9.5）。</li>
 * </ol>
 *
 * <p><b>金额闭合贯穿全程（真实链路上验证 Property 1、2、4、5）：</b></p>
 * <ul>
 *   <li>每笔分摊 Σ = 总额（Property 1 / 需求 4.5）。</li>
 *   <li>全体成员净额 Σ = 0（Property 2 / 需求 5.1），记账后、结清后均成立。</li>
 *   <li>每个成员账户「初始 − 当前」= 其 overview 的 accountPaid（Property 4 / 需求 7.1）——账户只反映
 *       真实进出（本人付款扣款 + 本人侧结算收付），是端到端现金闭合的强不变量。</li>
 *   <li>每个成员 myConsumption = 其自身各笔分摊之和；Σ 全体消费 = Σ 全部支出总额（Property 5 / 需求 4.4、7.2）。</li>
 * </ul>
 *
 * <p>仅用户 / 账户 / 分类以 Repository 播种（用户为注册主体、账户为本人资产、分类归属账本）；令牌用
 * app.jwt.secret 手工签发（JWT 过滤器无状态、不查库，签名有效即认证通过）。账本、成员、支出、结算一律经
 * 真实 AA 端点产生，确保与生产链路一致。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AaEndToEndAcceptanceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    private LedgerMemberRepository memberRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private TransactionSplitRepository splitRepository;

    @Test
    void fullAaLifecycle_create_invite_expenses_overview_settle_archive() {
        // ============================ 播种：注册用户 + 本人账户 ============================
        long alice = seedUser("Alice").getId();
        long bob = seedUser("Bob").getId();
        long carol = seedUser("Carol").getId();
        Account aliceAcc = seedAccount(alice, "1000.00");
        Account bobAcc = seedAccount(bob, "1000.00");
        Account carolAcc = seedAccount(carol, "1000.00");

        // ============================ 1) 建 AA 账本（POST /api/ledgers, type=AA）============================
        ResponseEntity<String> created = post("/api/ledgers",
                Map.of("name", "东京旅行 AA", "type", "AA"), authHeaders(alice));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> ledgerJson = parse(created);
        Long ledgerId = Long.valueOf(ledgerJson.get("id").toString());
        // 创建者即 owner（需求 1.1）。
        assertThat(memberRepository.findByLedgerIdAndUserId(ledgerId, alice).orElseThrow().isOwner())
                .isTrue();

        // ============================ 2) 邀请 + 加入（invite / join）============================
        ResponseEntity<String> invite = post("/api/ledgers/" + ledgerId + "/invite", null,
                authHeaders(alice));
        assertThat(invite.getStatusCode()).isEqualTo(HttpStatus.OK);
        String code = parse(invite).get("code").toString();

        // Bob、Carol 以已登录注册用户身份凭同一邀请码加入 → 成为 EDITOR 成员（需求 2.2、2.3）。
        assertThat(post("/api/ledgers/join", Map.of("code", code), authHeaders(bob)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(post("/api/ledgers/join", Map.of("code", code), authHeaders(carol)).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // 成员列表：三人齐备（GET /api/ledgers/{id}/members）。
        List<Map<String, Object>> members = parseList(
                get("/api/ledgers/" + ledgerId + "/members", authHeaders(alice)));
        assertThat(members).hasSize(3);
        assertThat(members.stream().map(m -> Long.valueOf(m.get("userId").toString())).toList())
                .containsExactlyInAnyOrder(alice, bob, carol);

        // 记账需要一个归属本账本的支出分类。
        Long categoryId = seedCategory(ledgerId).getId();

        // ============================ 3) 多笔记账（本人/他人付 × 均分/自定义）============================
        // E1 本人付 + 均分：Alice 记 & 付 90，三人均分（各 30）→ Alice 账户 −90。
        Long e1 = createExpense(alice, ledgerId, Map.of(
                "amount", "90.00", "categoryId", categoryId,
                "payerUserId", alice, "payerAccountId", aliceAcc.getId(),
                "splitMode", "even", "participants", List.of(alice, bob, carol)));

        // E2 本人付 + 自定义：Bob 记 & 付 120，自定义 A20/B40/C60 → Bob 账户 −120。
        Long e2 = createExpense(bob, ledgerId, Map.of(
                "amount", "120.00", "categoryId", categoryId,
                "payerUserId", bob, "payerAccountId", bobAcc.getId(),
                "splitMode", "custom", "participants", List.of(alice, bob, carol),
                "customShares", List.of(
                        share(alice, "20.00"), share(bob, "40.00"), share(carol, "60.00"))));

        // E3 他人付（代记）+ 均分：Alice 代记付款人=Carol 30，三人均分（各 10）→ 不触任何本人账户。
        Long e3 = createExpense(alice, ledgerId, Map.of(
                "amount", "30.00", "categoryId", categoryId,
                "payerUserId", carol,
                "splitMode", "even", "participants", List.of(alice, bob, carol)));

        // 3a) 账户扣款只发生在「本人付款」：E1 扣 Alice、E2 扣 Bob；E3 代记不触任何账户。
        assertBalance(aliceAcc.getId(), "910.00"); // 1000 − 90
        assertBalance(bobAcc.getId(), "880.00");   // 1000 − 120
        assertBalance(carolAcc.getId(), "1000.00"); // E3 代记付款人=Carol 但非会话用户，账户不动

        // 3b) 他人代记（E3）付款账户为空（需求 3.7）。
        assertThat(transactionRepository.findByIdAndLedgerId(e3, ledgerId).orElseThrow().getAccountId())
                .isNull();

        // 3c) 分摊持久化且 Σ 分摊 = 总额（Property 1 / 需求 4.5）。
        assertSplitSum(e1, "90.00");
        assertSplitSum(e2, "120.00");
        assertSplitSum(e3, "30.00");
        // 自定义分摊按输入精确落库。
        assertThat(shareOf(e2, alice)).isEqualByComparingTo("20.00");
        assertThat(shareOf(e2, bob)).isEqualByComparingTo("40.00");
        assertThat(shareOf(e2, carol)).isEqualByComparingTo("60.00");

        // ============================ 4) 概览三口径（overview）============================
        // 期望净额：Alice = 90付 − 60摊 = +30；Bob = 120 − 80 = +40；Carol = 30 − 100 = −70（Σ=0）。
        Map<String, Object> aliceOverview = parse(get("/api/aa/" + ledgerId + "/overview",
                authHeaders(alice)));
        assertThat(aliceOverview).containsEntry("allSettled", false);
        Map<String, Object> aliceCal = caliber(aliceOverview);
        // Alice：账户已支出 90（仅 E1 本人付），我的消费 30+20+10=60，待收回 max(+30,0)=30。
        assertThat(money(aliceCal.get("accountPaid"))).isEqualByComparingTo("90.00");
        assertThat(money(aliceCal.get("myConsumption"))).isEqualByComparingTo("60.00");
        assertThat(money(aliceCal.get("receivable"))).isEqualByComparingTo("30.00");

        Map<String, Object> bobCal = caliber(parse(get("/api/aa/" + ledgerId + "/overview",
                authHeaders(bob))));
        // Bob：账户已支出 120（E2 本人付），我的消费 30+40+10=80，待收回 max(+40,0)=40。
        assertThat(money(bobCal.get("accountPaid"))).isEqualByComparingTo("120.00");
        assertThat(money(bobCal.get("myConsumption"))).isEqualByComparingTo("80.00");
        assertThat(money(bobCal.get("receivable"))).isEqualByComparingTo("40.00");

        Map<String, Object> carolOverview = parse(get("/api/aa/" + ledgerId + "/overview",
                authHeaders(carol)));
        Map<String, Object> carolCal = caliber(carolOverview);
        // Carol：账户已支出 0（E3 代记不计入账户），我的消费 30+60+10=100，待收回 max(−70,0)=0。
        assertThat(money(carolCal.get("accountPaid"))).isEqualByComparingTo("0.00");
        assertThat(money(carolCal.get("myConsumption"))).isEqualByComparingTo("100.00");
        assertThat(money(carolCal.get("receivable"))).isEqualByComparingTo("0.00");

        // 成员净额 Σ = 0（Property 2 / 需求 5.1）。
        Map<Long, BigDecimal> netsBefore = memberNets(carolOverview);
        assertThat(netsBefore.get(alice)).isEqualByComparingTo("30.00");
        assertThat(netsBefore.get(bob)).isEqualByComparingTo("40.00");
        assertThat(netsBefore.get(carol)).isEqualByComparingTo("-70.00");
        assertThat(sum(netsBefore.values())).isEqualByComparingTo("0.00");

        // 消费闭合：Σ 全体 myConsumption = Σ 全部支出总额（Property 5）。
        assertThat(money(aliceCal.get("myConsumption"))
                .add(money(bobCal.get("myConsumption")))
                .add(money(carolCal.get("myConsumption"))))
                .isEqualByComparingTo("240.00"); // 90 + 120 + 30

        // 账户守恒：每人「初始 − 当前」= 其 accountPaid（真实现金闭合，Property 4 / 需求 7.1）。
        assertAccountClosure(aliceAcc.getId(), "1000.00", aliceCal);
        assertAccountClosure(bobAcc.getId(), "1000.00", bobCal);
        assertAccountClosure(carolAcc.getId(), "1000.00", carolCal);

        // ============================ 5) 结算：最少转账建议 + 逐条结清 ============================
        Map<String, Object> settlementView = parse(get("/api/aa/" + ledgerId + "/settlement",
                authHeaders(alice)));
        assertThat(settlementView).containsEntry("allSettled", false);
        List<Map<String, Object>> transfers = suggestedTransfers(settlementView);
        // 债权 Alice 30 + Bob 40 ↔ 债务 Carol 70：转账笔数 ≤ n−1 = 2，金额之和 = 总应付 70（需求 5.3、5.4）。
        assertThat(transfers.size()).isLessThanOrEqualTo(2);
        assertThat(transfers.stream()
                .map(t -> money(t.get("amount")))
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .isEqualByComparingTo("70.00");
        // 建议转账全部由债务人 Carol 付出（净额结构唯一确定的方向）。
        assertThat(transfers).allSatisfy(t ->
                assertThat(Long.valueOf(t.get("fromUserId").toString())).isEqualTo(carol));

        // 逐条结清（覆盖付款方 / 收款方两条路径，各以本方账户增减）：
        // (a) 债务 Carol → 债权 Bob 40：由付款方 Carol 结清（本人账户 −40，需求 6.3）。
        ResponseEntity<String> settle1 = post("/api/aa/settlements",
                settleAsPayer(bob, "40.00", carolAcc.getId()), ledgerHeaders(carol, ledgerId));
        assertThat(settle1.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> s1 = parse(settle1);
        assertThat(Long.valueOf(s1.get("fromUserId").toString())).isEqualTo(carol);
        assertThat(Long.valueOf(s1.get("toUserId").toString())).isEqualTo(bob);
        assertThat(Long.valueOf(s1.get("fromAccountId").toString())).isEqualTo(carolAcc.getId());
        assertThat(s1.get("toAccountId")).isNull();
        assertBalance(carolAcc.getId(), "960.00"); // 1000 − 40

        // (b) 剩余 债务 Carol → 债权 Alice 30：由收款方 Alice 结清（本人账户 +30，需求 6.2）。
        ResponseEntity<String> settle2 = post("/api/aa/settlements",
                settleAsReceiver(carol, "30.00", aliceAcc.getId()), ledgerHeaders(alice, ledgerId));
        assertThat(settle2.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> s2 = parse(settle2);
        assertThat(Long.valueOf(s2.get("fromUserId").toString())).isEqualTo(carol);
        assertThat(Long.valueOf(s2.get("toUserId").toString())).isEqualTo(alice);
        assertThat(Long.valueOf(s2.get("toAccountId").toString())).isEqualTo(aliceAcc.getId());
        assertThat(s2.get("fromAccountId")).isNull();
        assertBalance(aliceAcc.getId(), "940.00"); // 910 + 30

        // ============================ 6) 结清后：全部结清 + 账户反映真实现金流动 ============================
        Map<String, Object> settledView = parse(get("/api/aa/" + ledgerId + "/settlement",
                authHeaders(bob)));
        assertThat(settledView).containsEntry("allSettled", true);
        assertThat(suggestedTransfers(settledView)).isEmpty();
        // 全体净额归 0（Σ 恒为 0 且各自为 0）。
        Map<Long, BigDecimal> netsAfter = nets(settledView);
        assertThat(netsAfter.get(alice)).isEqualByComparingTo("0.00");
        assertThat(netsAfter.get(bob)).isEqualByComparingTo("0.00");
        assertThat(netsAfter.get(carol)).isEqualByComparingTo("0.00");
        assertThat(sum(netsAfter.values())).isEqualByComparingTo("0.00");

        // overview 亦回到已结清。
        Map<String, Object> aliceOverview2 = parse(get("/api/aa/" + ledgerId + "/overview",
                authHeaders(alice)));
        assertThat(aliceOverview2).containsEntry("allSettled", true);

        // 账户守恒在「记账 + 结清」后仍成立：每人「初始 − 当前」= 结清后的 accountPaid（Property 4 / 需求 7.1）。
        Map<String, Object> aliceCal2 = caliber(aliceOverview2);
        Map<String, Object> bobCal2 = caliber(parse(get("/api/aa/" + ledgerId + "/overview",
                authHeaders(bob))));
        Map<String, Object> carolCal2 = caliber(parse(get("/api/aa/" + ledgerId + "/overview",
                authHeaders(carol))));
        // Alice：90 付 − 30 收（收款方结清）= 60；账户 1000 → 940。
        assertThat(money(aliceCal2.get("accountPaid"))).isEqualByComparingTo("60.00");
        assertAccountClosure(aliceAcc.getId(), "1000.00", aliceCal2);
        // Bob：120 付（结算未动其账户）= 120；账户 1000 → 880。
        assertThat(money(bobCal2.get("accountPaid"))).isEqualByComparingTo("120.00");
        assertAccountClosure(bobAcc.getId(), "1000.00", bobCal2);
        // Carol：0 付 + 40 付出（付款方结清）= 40；账户 1000 → 960。
        assertThat(money(carolCal2.get("accountPaid"))).isEqualByComparingTo("40.00");
        assertAccountClosure(carolAcc.getId(), "1000.00", carolCal2);

        // ============================ 7) 归档 + 只读拒写 ============================
        // 全部已结清 → 归档无需 force（需求 8.3、8.4）。
        ResponseEntity<String> archived = post("/api/ledgers/" + ledgerId + "/archive", null,
                authHeaders(alice));
        assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(archived)).containsEntry("archived", true);

        // 归档后写操作（记账）被拒 409 AA_LEDGER_ARCHIVED（需求 8.3、9.5）。
        ResponseEntity<String> rejected = post("/api/aa/expenses", Map.of(
                "amount", "10.00", "categoryId", categoryId,
                "payerUserId", alice, "payerAccountId", aliceAcc.getId(),
                "splitMode", "even", "participants", List.of(alice, bob, carol)),
                ledgerHeaders(alice, ledgerId));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(rejected)).containsEntry("code", "AA_LEDGER_ARCHIVED");
        // 拒写后账户余额纹丝不动（归档写零副作用）。
        assertBalance(aliceAcc.getId(), "940.00");

        // 只读视图仍可访问（归档保留查看，需求 8.3）。
        assertThat(get("/api/aa/" + ledgerId + "/overview", authHeaders(alice)).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // ---------------------------------- 断言辅助 ----------------------------------

    private void assertBalance(Long accountId, String expected) {
        assertThat(accountRepository.findById(accountId).orElseThrow().getCurrentBalance())
                .isEqualByComparingTo(expected);
    }

    /** 账户守恒：账户「初始 − 当前」应等于其 overview 的 accountPaid（真实现金净流出）。 */
    private void assertAccountClosure(Long accountId, String initial, Map<String, Object> caliber) {
        BigDecimal current = accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
        BigDecimal netOut = new BigDecimal(initial).subtract(current);
        assertThat(netOut).isEqualByComparingTo(money(caliber.get("accountPaid")));
    }

    private void assertSplitSum(Long txId, String expectedTotal) {
        BigDecimal sum = splitRepository.findByTransactionId(txId).stream()
                .map(com.damien.youyu.domain.TransactionSplit::getShareAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo(expectedTotal);
    }

    private BigDecimal shareOf(Long txId, long userId) {
        return splitRepository.findByTransactionId(txId).stream()
                .filter(s -> s.getParticipantUserId().equals(userId))
                .map(com.damien.youyu.domain.TransactionSplit::getShareAmount)
                .findFirst()
                .orElseThrow();
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

    // ---------------------------------- 请求辅助 ----------------------------------

    private Long createExpense(long asUser, Long ledgerId, Map<String, Object> body) {
        ResponseEntity<String> response = post("/api/aa/expenses", body,
                ledgerHeaders(asUser, ledgerId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Long.valueOf(parse(response).get("id").toString());
    }

    private Map<String, Object> share(long userId, String amount) {
        return Map.of("userId", userId, "amount", amount);
    }

    private Map<String, Object> settleAsPayer(long toUserId, String amount, Long myAccountId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("toUserId", toUserId);
        body.put("amount", amount);
        body.put("myAccountId", myAccountId);
        return body;
    }

    private Map<String, Object> settleAsReceiver(long fromUserId, String amount, Long myAccountId) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("fromUserId", fromUserId);
        body.put("amount", amount);
        body.put("myAccountId", myAccountId);
        return body;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> post(String path, Object body, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private HttpHeaders authHeaders(long userId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(userId, jwtSecret, Duration.ofHours(1)));
        return headers;
    }

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

    // ---------------------------------- 解析辅助 ----------------------------------

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
        Map<Long, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> n : raw) {
            out.put(Long.valueOf(n.get("userId").toString()), money(n.get("net")));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, BigDecimal> nets(Map<String, Object> settlementView) {
        List<Map<String, Object>> raw = (List<Map<String, Object>>) settlementView.get("nets");
        Map<Long, BigDecimal> out = new java.util.LinkedHashMap<>();
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
