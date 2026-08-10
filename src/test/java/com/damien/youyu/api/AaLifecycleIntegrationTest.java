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
import com.damien.youyu.repository.AaSettlementRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionSplitRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * AA 账本<b>生命周期端到端集成测试</b>（任务 5.5，需求 2.6、2.7、2.8、8.1、8.2、8.3、8.4、8.5）。
 *
 * <p>照抄 {@link LedgerControllerTest} / {@link AaSettlementControllerTest} 的 {@code @SpringBootTest}
 * (RANDOM_PORT) + {@code TestRestTemplate} + 手工签发 JWT 范式，经真实 HTTP、真实 Spring Security 过滤链
 * 与 H2 持久化，聚焦<b>跨多个端点顺序编排</b>的生命周期流程——刻意补齐单任务契约测试（5.1–5.4）尚未在
 * HTTP 层覆盖的集成缺口，不重复既有单点断言：</p>
 *
 * <ol>
 *   <li><b>未结清阻止退出 / 移除（需求 2.6、2.7、2.8）：</b>有未结净额的成员 HTTP 退出 / 被移除均 409
 *       {@code AA_MEMBER_UNSETTLED}；结清到净额 0 后可被移除，其历史流水 / 分摊保留；创建者永不可移除
 *       （{@code MEMBER_OWNER_IMMUTABLE}）。{@code LedgerServiceTest} 已在<b>服务层</b>覆盖，此处补
 *       {@code DELETE /api/ledgers/{id}/members/{userId}} 的<b>真实 HTTP + 结清后可移除</b>全链路。</li>
 *   <li><b>只读拒写全链路（需求 8.3、8.5）：</b>经 {@code POST .../archive} 归档后，所有 AA 写路径
 *       （记账 create/edit/delete、结算 settle、撤销 revert）HTTP 一律 409 {@code AA_LEDGER_ARCHIVED}；
 *       只读视图（overview/settlement）仍 200 可读；{@code POST .../unarchive} 解档后写操作恢复放行。
 *       {@code AaExpenseControllerTest} 仅覆盖「直接播种归档态 + 单个 create 被拒」，此处补<b>归档→全写路径
 *       被拒→解档→放行</b>的编排缺口。</li>
 *   <li><b>结清状态动态回退（需求 8.1、8.2）：</b>全部结清后 overview/settlement 的 {@code allSettled}
 *       为 {@code true}；再记一笔产生新债务后自动翻回 {@code false}（进行中）——经端点验证动态状态转移。</li>
 * </ol>
 *
 * <p><b>已被前序任务在 HTTP 层充分覆盖、故本类不重复：</b>未结清阻止归档（{@code force} 语义，需求 8.4）
 * 已由 {@link LedgerControllerTest#archive_aaLedger_unsettled_withoutForce_returns409} /
 * {@code ..._withForce_returns200} / {@code ..._allSettled_returns200} 覆盖；单个归档账本 create 被拒由
 * {@link AaExpenseControllerTest#create_onArchivedLedger_returnsConflictArchived} 覆盖；结算 / 撤销的账户
 * 守恒与越权由 {@link AaSettlementControllerTest} 覆盖。</p>
 *
 * <p>账本 / 成员 / 分类 / 账户直接以 Repository 播种，令牌用 app.jwt.secret 手工签发（JWT 过滤器无状态、
 * 不查库，签名有效即认证通过）。业务数据一律经真实 AA 端点产生，确保与生产链路一致。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AaLifecycleIntegrationTest {

    private static final long ALICE = 6001L;
    private static final long BOB = 6002L;
    private static final long CAROL = 6003L;

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
    private TransactionSplitRepository splitRepository;
    @Autowired
    private AaSettlementRepository settlementRepository;

    // =====================================================================================
    // 1) 未结清阻止退出 / 移除；结清后可移除、历史保留；创建者不可移除（需求 2.6、2.7、2.8）
    // =====================================================================================

    @Test
    void unsettledMember_removeByOwner_blocked_thenSettled_removable_historyPreserved() {
        Ledger ledger = seedAaLedger();
        Category cat = seedCategory(ledger.getId());
        Account aliceAcc = seedAccount(ALICE, "300.00");
        // Alice 付 90，三人均分（各 30）→ Bob/Carol 各应付 30（未结清）。
        Long expenseId = createEvenExpense(ledger.getId(), cat.getId(), aliceAcc.getId(), "90.00",
                List.of(ALICE, BOB, CAROL));

        // 1a) 未结清 → OWNER 移除 Bob 被拒 409 AA_MEMBER_UNSETTLED（需求 2.6）。
        ResponseEntity<String> blocked = removeMember(ledger.getId(), ALICE, BOB);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(blocked)).containsEntry("code", "AA_MEMBER_UNSETTLED");
        assertThat(memberRepository.existsByLedgerIdAndUserId(ledger.getId(), BOB)).isTrue();

        // 1b) 结清 Bob 的 30（Alice 作为收款方）→ Bob 净额归 0。
        Map<String, Object> settleBob = new java.util.HashMap<>();
        settleBob.put("fromUserId", BOB);
        settleBob.put("amount", "30.00");
        settleBob.put("myAccountId", aliceAcc.getId());
        assertThat(postSettlement(ledger.getId(), ALICE, settleBob).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // 1c) 净额 0 后 → 可被移除，204（需求 2.6）。
        ResponseEntity<String> removed = removeMember(ledger.getId(), ALICE, BOB);
        assertThat(removed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(memberRepository.existsByLedgerIdAndUserId(ledger.getId(), BOB)).isFalse();

        // 1d) 历史保留：该笔支出与 Bob 的分摊记录仍在（仅移出成员列表，不删历史，需求 2.7）。
        assertThat(transactionRepository.findByIdAndLedgerId(expenseId, ledger.getId())).isPresent();
        assertThat(splitRepository.findByTransactionId(expenseId))
                .anyMatch(s -> s.getParticipantUserId().equals(BOB));
    }

    @Test
    void unsettledMember_selfLeave_blocked() {
        // 需求 2.6：有未结净额的成员自行退出（DELETE 自己）同样被拒 409 AA_MEMBER_UNSETTLED。
        Ledger ledger = seedAaLedger();
        Category cat = seedCategory(ledger.getId());
        Account aliceAcc = seedAccount(ALICE, "300.00");
        createEvenExpense(ledger.getId(), cat.getId(), aliceAcc.getId(), "90.00",
                List.of(ALICE, BOB, CAROL));

        ResponseEntity<String> response = removeMember(ledger.getId(), BOB, BOB);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(response)).containsEntry("code", "AA_MEMBER_UNSETTLED");
        assertThat(memberRepository.existsByLedgerIdAndUserId(ledger.getId(), BOB)).isTrue();
    }

    @Test
    void owner_cannotBeRemoved_returnsConflict() {
        // 需求 2.8：创建者（OWNER）永不可被移除 / 退出，即便净额为 0（无任何活动）。
        Ledger ledger = seedAaLedger();

        ResponseEntity<String> response = removeMember(ledger.getId(), ALICE, ALICE);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(response)).containsEntry("code", "MEMBER_OWNER_IMMUTABLE");
        assertThat(memberRepository.findByLedgerIdAndUserId(ledger.getId(), ALICE).orElseThrow()
                .isOwner()).isTrue();
    }

    // =====================================================================================
    // 2) 只读拒写全链路：归档 → 全部写路径被拒 → 只读视图仍可读 → 解档恢复（需求 8.3、8.5）
    // =====================================================================================

    @Test
    void archived_allWritePathsRejected_readViewsAccessible_thenUnarchiveRestoresWrites() {
        Ledger ledger = seedAaLedger();
        Category cat = seedCategory(ledger.getId());
        Account aliceAcc = seedAccount(ALICE, "300.00");
        // Alice 付 90 三人均分 → 存在债务；先结清 Bob 的 30 拿到一条结算 id（供 revert 用），Carol 仍未结清。
        Long expenseId = createEvenExpense(ledger.getId(), cat.getId(), aliceAcc.getId(), "90.00",
                List.of(ALICE, BOB, CAROL));
        Map<String, Object> settleBob = new java.util.HashMap<>();
        settleBob.put("fromUserId", BOB);
        settleBob.put("amount", "30.00");
        settleBob.put("myAccountId", aliceAcc.getId());
        Long settlementId = Long.valueOf(
                parse(postSettlement(ledger.getId(), ALICE, settleBob)).get("id").toString());

        // 归档（未结清 → 需 force=true 二次确认）。
        ResponseEntity<String> archived = post(
                "/api/ledgers/" + ledger.getId() + "/archive?force=true", null, authHeaders(ALICE));
        assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(archived)).containsEntry("archived", true);

        // 2a) 记一笔（create）→ 409 AA_LEDGER_ARCHIVED。
        Map<String, Object> newExpense = Map.of(
                "amount", "30.00",
                "categoryId", cat.getId(),
                "payerUserId", ALICE,
                "payerAccountId", aliceAcc.getId(),
                "splitMode", "even",
                "participants", List.of(ALICE, BOB, CAROL));
        assertRejectedArchived(post("/api/aa/expenses", newExpense, ledgerHeaders(ALICE, ledger.getId())));

        // 2b) 编辑（edit）→ 409。
        Map<String, Object> editBody = Map.of(
                "amount", "60.00",
                "categoryId", cat.getId(),
                "payerUserId", ALICE,
                "payerAccountId", aliceAcc.getId(),
                "splitMode", "even",
                "participants", List.of(ALICE, BOB, CAROL));
        assertRejectedArchived(rest.exchange(url("/api/aa/expenses/" + expenseId), HttpMethod.PUT,
                new HttpEntity<>(editBody, ledgerHeaders(ALICE, ledger.getId())), String.class));

        // 2c) 删除（delete）→ 409。
        assertRejectedArchived(rest.exchange(url("/api/aa/expenses/" + expenseId), HttpMethod.DELETE,
                new HttpEntity<>(ledgerHeaders(ALICE, ledger.getId())), String.class));

        // 2d) 结算（settle Carol）→ 409。
        Map<String, Object> settleCarol = new java.util.HashMap<>();
        settleCarol.put("fromUserId", CAROL);
        settleCarol.put("amount", "30.00");
        settleCarol.put("myAccountId", aliceAcc.getId());
        assertRejectedArchived(postSettlement(ledger.getId(), ALICE, settleCarol));

        // 2e) 撤销结算（revert）→ 409。
        assertRejectedArchived(postRevert(ledger.getId(), ALICE, settlementId));

        // 2f) 只读视图仍可访问（需求 8.3：归档保留查看）。
        ResponseEntity<String> overview = get(
                "/api/aa/" + ledger.getId() + "/overview", authHeaders(ALICE));
        assertThat(overview.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(overview)).containsEntry("archived", true);
        assertThat(get("/api/aa/" + ledger.getId() + "/settlement", authHeaders(ALICE))
                .getStatusCode()).isEqualTo(HttpStatus.OK);

        // 2g) 解档后写操作恢复放行（需求 8.5）：结清 Carol 的 30 成功。
        ResponseEntity<String> unarchived = post(
                "/api/ledgers/" + ledger.getId() + "/unarchive", null, authHeaders(ALICE));
        assertThat(unarchived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(unarchived)).containsEntry("archived", false);
        assertThat(postSettlement(ledger.getId(), ALICE, settleCarol).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
    }

    // =====================================================================================
    // 3) 结清状态动态回退：全部结清 → allSettled=true → 再记一笔产生新债务 → 自动翻回 false（需求 8.1、8.2）
    // =====================================================================================

    @Test
    void allSettledFlag_flipsTrueWhenSettled_thenFalseAfterNewExpense() {
        Ledger ledger = seedAaLedger();
        Category cat = seedCategory(ledger.getId());
        Account aliceAcc = seedAccount(ALICE, "300.00");
        // Alice 付 60，Alice+Bob 均分（各 30）→ Bob 应付 30，Carol 不参与（net 0）。
        createEvenExpense(ledger.getId(), cat.getId(), aliceAcc.getId(), "60.00", List.of(ALICE, BOB));

        // 记账后有待结算：allSettled=false（需求 8.1）。
        assertThat(parse(get("/api/aa/" + ledger.getId() + "/settlement", authHeaders(ALICE))))
                .containsEntry("allSettled", false);
        assertThat(parse(get("/api/aa/" + ledger.getId() + "/overview", authHeaders(ALICE))))
                .containsEntry("allSettled", false);

        // 结清 Bob 的 30 → 全体净额归 0。
        Map<String, Object> settleBob = new java.util.HashMap<>();
        settleBob.put("fromUserId", BOB);
        settleBob.put("amount", "30.00");
        settleBob.put("myAccountId", aliceAcc.getId());
        assertThat(postSettlement(ledger.getId(), ALICE, settleBob).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        // 全部结清 → allSettled=true（需求 8.1）。
        assertThat(parse(get("/api/aa/" + ledger.getId() + "/settlement", authHeaders(ALICE))))
                .containsEntry("allSettled", true);
        assertThat(parse(get("/api/aa/" + ledger.getId() + "/overview", authHeaders(ALICE))))
                .containsEntry("allSettled", true);

        // 再记一笔产生新债务：Alice 付 40，Alice+Bob 均分（各 20）→ Bob 重新欠 20。
        createEvenExpense(ledger.getId(), cat.getId(), aliceAcc.getId(), "40.00", List.of(ALICE, BOB));

        // 状态自动翻回进行中：allSettled=false（需求 8.2）。
        assertThat(parse(get("/api/aa/" + ledger.getId() + "/settlement", authHeaders(ALICE))))
                .containsEntry("allSettled", false);
        assertThat(parse(get("/api/aa/" + ledger.getId() + "/overview", authHeaders(ALICE))))
                .containsEntry("allSettled", false);
    }

    // ---------------------------------- 断言辅助 ----------------------------------

    private void assertRejectedArchived(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(response)).containsEntry("code", "AA_LEDGER_ARCHIVED");
    }

    // ---------------------------------- 数据播种 ----------------------------------

    /** Alice(owner)、Bob、Carol 三人 AA 账本。 */
    private Ledger seedAaLedger() {
        LocalDateTime now = LocalDateTime.now();
        Ledger l = new Ledger();
        l.setUserId(ALICE);
        l.setName("旅行 AA");
        l.setType(Ledger.TYPE_AA);
        l.setSortOrder(0);
        l.setDefault(false);
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

    /** 经 AA 记账接口创建一笔均分支出（Alice 付款）并返回其 id，确保数据来源与生产链路一致。 */
    private Long createEvenExpense(Long ledgerId, Long categoryId, Long accountId, String amount,
            List<Long> participants) {
        Map<String, Object> body = Map.of(
                "amount", amount,
                "categoryId", categoryId,
                "payerUserId", ALICE,
                "payerAccountId", accountId,
                "splitMode", "even",
                "participants", participants);
        ResponseEntity<String> response = post("/api/aa/expenses", body,
                ledgerHeaders(ALICE, ledgerId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Long.valueOf(parse(response).get("id").toString());
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private ResponseEntity<String> removeMember(Long ledgerId, long asUser, long targetUserId) {
        return rest.exchange(url("/api/ledgers/" + ledgerId + "/members/" + targetUserId),
                HttpMethod.DELETE, new HttpEntity<>(authHeaders(asUser)), String.class);
    }

    private ResponseEntity<String> postSettlement(Long ledgerId, long userId, Map<String, Object> body) {
        return rest.exchange(url("/api/aa/settlements"), HttpMethod.POST,
                new HttpEntity<>(body, ledgerHeaders(userId, ledgerId)), String.class);
    }

    private ResponseEntity<String> postRevert(Long ledgerId, long userId, Long settlementId) {
        return rest.exchange(url("/api/aa/settlements/" + settlementId + "/revert"),
                HttpMethod.POST, new HttpEntity<>(null, ledgerHeaders(userId, ledgerId)), String.class);
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
