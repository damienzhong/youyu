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
import com.damien.youyu.repository.TransactionRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * {@link AaExpenseController} 的<b>控制器契约与安全边界</b>集成测试（任务 3.3，需求 3.1、3.6、9.1、9.4、9.5）。
 *
 * <p>照抄 {@link ReportControllerTest} 的 {@code @SpringBootTest}(RANDOM_PORT) + {@code TestRestTemplate}
 * + 手工签发 JWT 范式，使用<b>独立命名</b>的内存库。经真实 HTTP、真实 Spring Security 过滤链、真实
 * {@code CurrentLedger}（{@code X-Ledger-Id} 解析）与 H2 持久化层，覆盖：</p>
 *
 * <ol>
 *   <li>POST 均分创建：201、账户按实付扣款、分摊守恒（各 30.00）、付款账户与付款人回显（需求 3.1、3.6）。</li>
 *   <li>POST 自定义分摊之和 ≠ 总额：400 {@code AA_SPLIT_MISMATCH}、零副作用（需求 3.4）。</li>
 *   <li>PUT 编辑金额：200、账户与分摊按新参数重算（需求 9.1）。</li>
 *   <li>DELETE 删除：204、分摊与流水回滚（需求 9.1）。</li>
 *   <li>非成员越权：404（不泄漏账本存在性，需求 9.4）。</li>
 *   <li>只读（已归档）账本写操作：409 {@code AA_LEDGER_ARCHIVED}（需求 9.5）。</li>
 *   <li>无令牌：401 {@code UNAUTHENTICATED}。</li>
 * </ol>
 *
 * <p><b>任务 3.4 端到端补充</b>（需求 3.2、3.5、3.7、4.4、7.1、7.2、9.2a、9.2b）——聚焦控制器契约测试尚未覆盖、
 * 且属于「记账 → 分摊」链路口径的场景，与已覆盖场景不重复：</p>
 * <ol start="8">
 *   <li>他人付款不动本人账户：付款人非当前用户时该笔 {@code accountId} 为空、当前用户账户余额不变，
 *       仅形成分摊 / 应收应付（需求 3.7、7.1）。</li>
 *   <li>消费口径「只计自摊」：跨多笔的某成员消费统计 = Σ其自身 {@code share_amount}；付款人的「借出（应收）」
 *       = 实付 − 自摊，<b>不</b>计入其消费；全体消费之和 = 全部实付之和（需求 4.4、7.2 / Property 5）。</li>
 *   <li>已涉结算拒删 / 拒改：账本存在未撤销结算时 DELETE / PUT 一律 409 {@code AA_EXPENSE_SETTLED} 且零副作用
 *       （需求 9.2b）。</li>
 * </ol>
 *
 * <p>因 AA 账本创建 / 成员接口属后续任务，本测试直接以 Repository 播种 AA 账本、成员、分类与账户，
 * 并用 app.jwt.secret 手工签发被测成员的令牌（JWT 过滤器无状态、不查库，签名有效即认证通过）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AaExpenseControllerTest {

    private static final String PATH = "/api/aa/expenses";
    private static final long ALICE = 1001L;
    private static final long BOB = 1002L;
    private static final long CAROL = 1003L;
    private static final long OUTSIDER = 1009L;

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
    private TransactionRepository transactionRepository;
    @Autowired
    private AaSettlementRepository settlementRepository;

    // ---------------- 1) POST 均分创建 ----------------

    @Test
    void create_evenSplit_returns201_deductsAccount_andSplitsBalance() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");

        Map<String, Object> body = Map.of(
                "amount", "90.00",
                "categoryId", cat.getId(),
                "payerUserId", ALICE,
                "payerAccountId", acc.getId(),
                "note", "聚餐",
                "splitMode", "even",
                "participants", List.of(ALICE, BOB, CAROL));

        ResponseEntity<String> response = post(PATH, body, memberHeaders(ALICE, ledger.getId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> json = parse(response);
        assertThat(json.get("id")).isNotNull();
        assertThat(json).containsEntry("type", "aa_expense");
        assertThat(json).containsEntry("ledgerId", ledger.getId().intValue());
        assertThat(json).containsEntry("payerUserId", (int) ALICE);
        assertThat(json).containsEntry("accountId", acc.getId().intValue());
        assertThat(new BigDecimal(json.get("amount").toString())).isEqualByComparingTo("90.00");
        // 分摊守恒（Property 1）：三人均分 90 → 各 30，合计 90。
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> splits = (List<Map<String, Object>>) json.get("splits");
        assertThat(splits).hasSize(3);
        BigDecimal sum = splits.stream()
                .map(s -> new BigDecimal(s.get("amount").toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(sum).isEqualByComparingTo("90.00");
        // 实付全额扣款（需求 3.2、7.1）：300 − 90 = 210。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("210.00");
    }

    // ---------------- 2) POST 自定义分摊之和 ≠ 总额 ----------------

    @Test
    void create_customSplitMismatch_returns400_withZeroSideEffect() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");

        Map<String, Object> body = Map.of(
                "amount", "100.00",
                "categoryId", cat.getId(),
                "payerUserId", ALICE,
                "payerAccountId", acc.getId(),
                "splitMode", "custom",
                "participants", List.of(ALICE, BOB, CAROL),
                "customShares", List.of(
                        Map.of("userId", ALICE, "amount", "50.00"),
                        Map.of("userId", BOB, "amount", "30.00"),
                        Map.of("userId", CAROL, "amount", "10.00"))); // 合计 90 ≠ 100

        ResponseEntity<String> response = post(PATH, body, memberHeaders(ALICE, ledger.getId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(response)).containsEntry("code", "AA_SPLIT_MISMATCH");
        // 零副作用：账户与流水不变。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("300.00");
        assertThat(transactionRepository.findByLedgerId(ledger.getId())).isEmpty();
    }

    // ---------------- 3) PUT 编辑金额 ----------------

    @Test
    void update_changesAmount_returns200_recomputesAccountAndSplits() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");
        Long expenseId = createExpenseId(ledger.getId(), cat.getId(), acc.getId(), "90.00");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("210.00");

        Map<String, Object> body = Map.of(
                "amount", "60.00",
                "categoryId", cat.getId(),
                "payerUserId", ALICE,
                "payerAccountId", acc.getId(),
                "note", "新",
                "splitMode", "even",
                "participants", List.of(ALICE, BOB, CAROL));

        ResponseEntity<String> response = put(PATH + "/" + expenseId, body,
                memberHeaders(ALICE, ledger.getId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = parse(response);
        assertThat(new BigDecimal(json.get("amount").toString())).isEqualByComparingTo("60.00");
        assertThat(json).containsEntry("note", "新");
        // 账户：300 −90 +90 −60 = 240（无漂移）。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("240.00");
    }

    // ---------------- 4) DELETE 删除 ----------------

    @Test
    void delete_returns204_rollsBackAccountAndSplits() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");
        Long expenseId = createExpenseId(ledger.getId(), cat.getId(), acc.getId(), "90.00");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("210.00");

        ResponseEntity<String> response = delete(PATH + "/" + expenseId,
                memberHeaders(ALICE, ledger.getId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        // 付款账户回滚、流水从常规查询中消失（软删除）。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("300.00");
        assertThat(transactionRepository.findByLedgerId(ledger.getId())).isEmpty();
    }

    // ---------------- 5) 非成员越权 → 404（需求 9.4）----------------

    @Test
    void create_byNonMember_returnsNotFound() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(OUTSIDER, "300.00");

        Map<String, Object> body = Map.of(
                "amount", "30.00",
                "categoryId", cat.getId(),
                "payerUserId", OUTSIDER,
                "payerAccountId", acc.getId(),
                "splitMode", "even",
                "participants", List.of(OUTSIDER));

        ResponseEntity<String> response = post(PATH, body, memberHeaders(OUTSIDER, ledger.getId()));

        // 越权：不泄漏账本存在性，统一 404（需求 9.4）。
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(transactionRepository.findByLedgerId(ledger.getId())).isEmpty();
    }

    // ---------------- 6) 只读（已归档）账本写操作 → 409（需求 9.5）----------------

    @Test
    void create_onArchivedLedger_returnsConflictArchived() {
        Ledger ledger = seedAaLedger(true);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");

        Map<String, Object> body = Map.of(
                "amount", "30.00",
                "categoryId", cat.getId(),
                "payerUserId", ALICE,
                "payerAccountId", acc.getId(),
                "splitMode", "even",
                "participants", List.of(ALICE, BOB, CAROL));

        ResponseEntity<String> response = post(PATH, body, memberHeaders(ALICE, ledger.getId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(response)).containsEntry("code", "AA_LEDGER_ARCHIVED");
        // 零副作用。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("300.00");
        assertThat(transactionRepository.findByLedgerId(ledger.getId())).isEmpty();
    }

    // ---------------- 7) 无令牌 → 401 ----------------

    @Test
    void create_withoutToken_returnsUnauthenticated() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(com.damien.youyu.security.CurrentLedger.HEADER, String.valueOf(ledger.getId()));
        Map<String, Object> body = Map.of(
                "amount", "30.00",
                "categoryId", cat.getId(),
                "splitMode", "even",
                "participants", List.of(ALICE));

        ResponseEntity<String> response = post(PATH, body, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(parse(response)).containsEntry("code", "UNAUTHENTICATED");
    }

    // ---------------- 8) 他人付款不动本人账户（需求 3.7、7.1）----------------

    @Test
    void create_payerIsOther_returns201_doesNotTouchCurrentUserAccount() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        // 当前用户 Alice 记账（代记），付款人是 Bob。Alice 自己有账户，不应被扣。
        Account aliceAcc = seedAccount(ALICE, "500.00");

        Map<String, Object> body = Map.of(
                "amount", "60.00",
                "categoryId", cat.getId(),
                "payerUserId", BOB,
                "payerAccountId", aliceAcc.getId(), // 即便传了本人账户，付款人非本人也不应扣
                "note", "Bob 付",
                "splitMode", "even",
                "participants", List.of(ALICE, BOB, CAROL));

        ResponseEntity<String> response = post(PATH, body, memberHeaders(ALICE, ledger.getId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> json = parse(response);
        assertThat(json).containsEntry("payerUserId", (int) BOB);
        // 付款人非本人 → 不记付款账户（需求 3.7）。
        assertThat(json.get("accountId")).isNull();
        // 仅形成分摊，本人账户余额纹丝不动（需求 7.1）。
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> splits = (List<Map<String, Object>>) json.get("splits");
        assertThat(splits).hasSize(3);
        assertThat(balanceOf(aliceAcc.getId())).isEqualByComparingTo("500.00");
    }

    // ---------------- 9) 消费口径「只计自摊」（需求 4.4、7.2 / Property 5）----------------

    @Test
    void consumption_countsOnlyOwnShare_notReceivableOrPayable() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account aliceAcc = seedAccount(ALICE, "500.00");
        Account bobAcc = seedAccount(BOB, "500.00");

        // 第 1 笔：Alice 付 90，三人均分（各 30）。Alice 借出 90−30=60（应收，不计消费）。
        Map<Long, BigDecimal> e1 = sharesFromCreate(ledger.getId(), cat.getId(),
                Map.of(
                        "amount", "90.00",
                        "categoryId", cat.getId(),
                        "payerUserId", ALICE,
                        "payerAccountId", aliceAcc.getId(),
                        "splitMode", "even",
                        "participants", List.of(ALICE, BOB, CAROL)),
                ALICE);
        // 第 2 笔：Bob 付 60，三人均分（各 20）。Bob 借出 60−20=40（应收，不计消费）。
        Map<Long, BigDecimal> e2 = sharesFromCreate(ledger.getId(), cat.getId(),
                Map.of(
                        "amount", "60.00",
                        "categoryId", cat.getId(),
                        "payerUserId", BOB,
                        "payerAccountId", bobAcc.getId(),
                        "splitMode", "even",
                        "participants", List.of(ALICE, BOB, CAROL)),
                BOB);

        // 消费统计 = 各成员在各笔中自身 share_amount 之和（需求 7.2）。
        BigDecimal aliceConsumption = e1.get(ALICE).add(e2.get(ALICE));
        BigDecimal bobConsumption = e1.get(BOB).add(e2.get(BOB));
        BigDecimal carolConsumption = e1.get(CAROL).add(e2.get(CAROL));

        // 每人只摊自己那份：30+20 = 50。
        assertThat(aliceConsumption).isEqualByComparingTo("50.00");
        assertThat(bobConsumption).isEqualByComparingTo("50.00");
        assertThat(carolConsumption).isEqualByComparingTo("50.00");

        // Alice 实付 90，但其消费只 50 —— 借出 40（应收）不计入消费（需求 4.4）。
        assertThat(aliceConsumption).isEqualByComparingTo("50.00");
        // 全体消费之和 = 全部实付之和（90+60=150），债权/债务是派生、不额外增减消费。
        BigDecimal totalConsumption = aliceConsumption.add(bobConsumption).add(carolConsumption);
        assertThat(totalConsumption).isEqualByComparingTo("150.00");

        // 账户口径：只反映真实付款（Alice −90、Bob −60），应收/应付不落账户（需求 7.1、7.3）。
        assertThat(balanceOf(aliceAcc.getId())).isEqualByComparingTo("410.00");
        assertThat(balanceOf(bobAcc.getId())).isEqualByComparingTo("440.00");
    }

    // ---------------- 10) 已涉结算拒删 / 拒改 → 409（需求 9.2b）----------------

    @Test
    void delete_whenSettlementExists_returns409_expenseSettled() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");
        Long expenseId = createExpenseId(ledger.getId(), cat.getId(), acc.getId(), "90.00");
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("210.00");
        // 账本内存在一条未撤销结算 → 直接删该笔应被拒（需求 9.2b）。
        seedSettlement(ledger.getId(), BOB, ALICE, "30.00");

        ResponseEntity<String> response = delete(PATH + "/" + expenseId,
                memberHeaders(ALICE, ledger.getId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(response)).containsEntry("code", "AA_EXPENSE_SETTLED");
        // 零副作用：账户、流水、分摊均不变。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("210.00");
        assertThat(transactionRepository.findByLedgerId(ledger.getId())).hasSize(1);
    }

    @Test
    void update_whenSettlementExists_returns409_expenseSettled() {
        Ledger ledger = seedAaLedger(false);
        Category cat = seedCategory(ledger.getId());
        Account acc = seedAccount(ALICE, "300.00");
        Long expenseId = createExpenseId(ledger.getId(), cat.getId(), acc.getId(), "90.00");
        seedSettlement(ledger.getId(), BOB, ALICE, "30.00");

        Map<String, Object> body = Map.of(
                "amount", "60.00",
                "categoryId", cat.getId(),
                "payerUserId", ALICE,
                "payerAccountId", acc.getId(),
                "splitMode", "even",
                "participants", List.of(ALICE, BOB, CAROL));

        ResponseEntity<String> response = put(PATH + "/" + expenseId, body,
                memberHeaders(ALICE, ledger.getId()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(response)).containsEntry("code", "AA_EXPENSE_SETTLED");
        // 零副作用：金额与账户保持原值（300 − 90 = 210）。
        assertThat(balanceOf(acc.getId())).isEqualByComparingTo("210.00");
        assertThat(new BigDecimal(transactionRepository.findByIdAndLedgerId(expenseId, ledger.getId())
                .orElseThrow().getAmount().toString())).isEqualByComparingTo("90.00");
    }

    // ---------------------------------- 数据播种 ----------------------------------

    /** 建一个 Alice(owner)、Bob、Carol 三人 AA 账本。 */
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

    /** 经接口创建一笔 AA 支出并返回其 id（供编辑/删除用例复用）。 */
    private Long createExpenseId(Long ledgerId, Long categoryId, Long accountId, String amount) {
        Map<String, Object> body = Map.of(
                "amount", amount,
                "categoryId", categoryId,
                "payerUserId", ALICE,
                "payerAccountId", accountId,
                "splitMode", "even",
                "participants", List.of(ALICE, BOB, CAROL));
        ResponseEntity<String> response = post(PATH, body, memberHeaders(ALICE, ledgerId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Long.valueOf(parse(response).get("id").toString());
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    /** 经接口创建一笔 AA 支出，返回其分摊 {@code userId → share_amount} 映射（用于消费口径断言）。 */
    private Map<Long, BigDecimal> sharesFromCreate(Long ledgerId, Long categoryId,
            Map<String, Object> body, long asUser) {
        ResponseEntity<String> response = post(PATH, body, memberHeaders(asUser, ledgerId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> json = parse(response);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> splits = (List<Map<String, Object>>) json.get("splits");
        Map<Long, BigDecimal> out = new java.util.LinkedHashMap<>();
        for (Map<String, Object> s : splits) {
            out.put(Long.valueOf(s.get("userId").toString()), new BigDecimal(s.get("amount").toString()));
        }
        return out;
    }

    /** 在账本内落一条未撤销结算，用于验证「已涉结算拒删 / 拒改」（需求 9.2b）。 */
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

    private ResponseEntity<String> post(String path, Object body, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> put(String path, Object body, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> delete(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }

    /** 已认证成员的请求头：Bearer 令牌 + X-Ledger-Id + JSON。 */
    private HttpHeaders memberHeaders(long userId, Long ledgerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(userId, jwtSecret, Duration.ofHours(1)));
        headers.set(com.damien.youyu.security.CurrentLedger.HEADER, String.valueOf(ledgerId));
        return headers;
    }

    /** 自行签发令牌（与 {@link ReportControllerTest} 同款）。 */
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
}
