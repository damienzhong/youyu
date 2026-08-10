package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

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

import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerInvite;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.LedgerInviteRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * {@link LedgerController} 邀请 / 加入 / 成员列表的<b>控制器契约与安全边界</b>集成测试（任务 5.2，
 * 需求 2.1–2.5）。照抄 {@link AaLedgerControllerTest} 的 {@code @SpringBootTest}(RANDOM_PORT) +
 * {@code TestRestTemplate} + 手工签发 JWT 范式，经真实 HTTP、真实 Spring Security 过滤链与 H2 覆盖：</p>
 *
 * <ol>
 *   <li>AA 账本可生成邀请码（需求 2.1）。</li>
 *   <li>已登录用户可凭码加入 AA 账本、登记为 EDITOR 成员（需求 2.2、2.3）。</li>
 *   <li>未登录（无令牌）加入被拒 401（需求 2.2 强制登录）。</li>
 *   <li>个人账本邀请被拒 400（仅协作 / AA 支持成员）。</li>
 *   <li>成员列表返回昵称 / 头像种子 / 创建者标识（需求 2.5）。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LedgerControllerTest {

    private static final long ALICE = 5001L;
    private static final long BOB = 5002L;

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
    private LedgerInviteRepository inviteRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private com.damien.youyu.repository.AccountRepository accountRepository;
    @Autowired
    private com.damien.youyu.repository.CategoryRepository categoryRepository;
    @Autowired
    private com.damien.youyu.service.aa.AaExpenseService aaExpenseService;

    // ---------------- 1) AA 账本可生成邀请码（需求 2.1）----------------

    @Test
    void invite_onAaLedger_returns200_withCode() {
        Ledger ledger = seedLedger(Ledger.TYPE_AA);

        ResponseEntity<String> response = post(
                "/api/ledgers/" + ledger.getId() + "/invite", null, headers(ALICE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(response).get("code")).isNotNull();
    }

    // ---------------- 2) 已登录加入 AA 账本 → EDITOR 成员（需求 2.2、2.3）----------------

    @Test
    void join_aaLedger_authenticated_registersEditorMember() {
        Ledger ledger = seedLedger(Ledger.TYPE_AA);
        LedgerInvite invite = seedInvite(ledger.getId());

        ResponseEntity<String> response = post(
                "/api/ledgers/join", Map.of("code", invite.getCode()), headers(BOB));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // 登记为成员，对应真实 user_id，角色 EDITOR（需求 2.3、2.4）。
        LedgerMember bob = memberRepository.findByLedgerIdAndUserId(ledger.getId(), BOB).orElseThrow();
        assertThat(bob.getRole()).isEqualTo(LedgerMember.ROLE_EDITOR);
    }

    // ---------------- 3) 未登录加入 → 401（需求 2.2 强制登录）----------------

    @Test
    void join_withoutToken_returnsUnauthenticated() {
        Ledger ledger = seedLedger(Ledger.TYPE_AA);
        LedgerInvite invite = seedInvite(ledger.getId());

        HttpHeaders noAuth = new HttpHeaders();
        noAuth.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> response = post(
                "/api/ledgers/join", Map.of("code", invite.getCode()), noAuth);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(parse(response)).containsEntry("code", "UNAUTHENTICATED");
        // 未产生任何成员登记。
        assertThat(memberRepository.existsByLedgerIdAndUserId(ledger.getId(), BOB)).isFalse();
    }

    // ---------------- 4) 个人账本邀请被拒（仅协作 / AA 支持成员）----------------

    @Test
    void invite_onPersonalLedger_rejected() {
        Ledger ledger = seedLedger(Ledger.TYPE_PERSONAL);

        ResponseEntity<String> response = post(
                "/api/ledgers/" + ledger.getId() + "/invite", null, headers(ALICE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(response)).containsEntry("code", "LEDGER_NOT_COLLABORATIVE");
    }

    // ---------------- 5) 成员列表：昵称 / 头像种子 / 创建者标识（需求 2.5）----------------

    @Test
    void members_includeNicknameAvatarAndOwnerFlag() {
        long ownerId = seedUser("阿丽");
        long editorId = seedUser("小明");
        Ledger ledger = seedLedgerOwnedBy(Ledger.TYPE_AA, ownerId);
        member(ledger.getId(), editorId, LedgerMember.ROLE_EDITOR);

        ResponseEntity<String> response = get(
                "/api/ledgers/" + ledger.getId() + "/members", headers(ownerId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> members = parseList(response);
        assertThat(members).hasSize(2);

        Map<String, Object> owner = members.stream()
                .filter(m -> Long.valueOf(m.get("userId").toString()).equals(ownerId))
                .findFirst().orElseThrow();
        assertThat(owner).containsEntry("displayName", "阿丽");
        assertThat(owner).containsEntry("avatarSeed", "阿");
        assertThat(owner).containsEntry("role", "OWNER");
        assertThat(owner).containsEntry("owner", true);

        Map<String, Object> editor = members.stream()
                .filter(m -> Long.valueOf(m.get("userId").toString()).equals(editorId))
                .findFirst().orElseThrow();
        assertThat(editor).containsEntry("displayName", "小明");
        assertThat(editor).containsEntry("avatarSeed", "小");
        assertThat(editor).containsEntry("role", "EDITOR");
        assertThat(editor).containsEntry("owner", false);
    }

    // ---------------- 6) 归档 / 解档（需求 8.3-8.5）----------------

    @Test
    void archive_aaLedger_allSettled_returns200_archivedTrue() {
        // 需求 8.3：全部结清（无活动）的 AA 账本可直接归档，响应携带 archived=true。
        Ledger ledger = seedLedger(Ledger.TYPE_AA);

        ResponseEntity<String> response = post(
                "/api/ledgers/" + ledger.getId() + "/archive", null, headers(ALICE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(response)).containsEntry("archived", true);
        assertThat(ledgerRepository.findById(ledger.getId()).orElseThrow().isArchived()).isTrue();
    }

    @Test
    void unarchive_aaLedger_returns200_archivedFalse() {
        // 需求 8.5：解档恢复可编辑，响应 archived=false。
        Ledger ledger = seedLedger(Ledger.TYPE_AA);
        post("/api/ledgers/" + ledger.getId() + "/archive", null, headers(ALICE));

        ResponseEntity<String> response = post(
                "/api/ledgers/" + ledger.getId() + "/unarchive", null, headers(ALICE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(response)).containsEntry("archived", false);
        assertThat(ledgerRepository.findById(ledger.getId()).orElseThrow().isArchived()).isFalse();
    }

    @Test
    void archive_aaLedger_unsettled_withoutForce_returns409() {
        // 需求 8.4：仍有未结清净额时未带 force 归档被拒 409 AA_LEDGER_UNSETTLED。
        Ledger ledger = seedLedger(Ledger.TYPE_AA);
        member(ledger.getId(), BOB, LedgerMember.ROLE_EDITOR);
        seedUnsettledExpense(ledger.getId());

        ResponseEntity<String> response = post(
                "/api/ledgers/" + ledger.getId() + "/archive", null, headers(ALICE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(parse(response)).containsEntry("code", "AA_LEDGER_UNSETTLED");
        assertThat(ledgerRepository.findById(ledger.getId()).orElseThrow().isArchived()).isFalse();
    }

    @Test
    void archive_aaLedger_unsettled_withForce_returns200() {
        // 需求 8.4：二次确认 ?force=true 后未结清账本可归档。
        Ledger ledger = seedLedger(Ledger.TYPE_AA);
        member(ledger.getId(), BOB, LedgerMember.ROLE_EDITOR);
        seedUnsettledExpense(ledger.getId());

        ResponseEntity<String> response = post(
                "/api/ledgers/" + ledger.getId() + "/archive?force=true", null, headers(ALICE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(response)).containsEntry("archived", true);
    }

    @Test
    void archive_nonOwnerEditor_returns403() {
        // OWNER-only：EDITOR 归档被拒 403 LEDGER_FORBIDDEN。
        Ledger ledger = seedLedger(Ledger.TYPE_AA);
        member(ledger.getId(), BOB, LedgerMember.ROLE_EDITOR);

        ResponseEntity<String> response = post(
                "/api/ledgers/" + ledger.getId() + "/archive", null, headers(BOB));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(parse(response)).containsEntry("code", "LEDGER_FORBIDDEN");
    }

    @Test
    void archive_nonMember_returns404() {
        // 越权（非成员）→ 404，不泄漏存在性。
        Ledger ledger = seedLedger(Ledger.TYPE_AA);

        ResponseEntity<String> response = post(
                "/api/ledgers/" + ledger.getId() + "/archive", null, headers(BOB));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void archive_nonAaLedger_returns400() {
        // 仅 AA 账本支持归档：协作账本归档被拒 400 AA_ARCHIVE_NOT_SUPPORTED。
        Ledger ledger = seedLedger(Ledger.TYPE_COLLABORATIVE);

        ResponseEntity<String> response = post(
                "/api/ledgers/" + ledger.getId() + "/archive", null, headers(ALICE));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(parse(response)).containsEntry("code", "AA_ARCHIVE_NOT_SUPPORTED");
    }

    // ---------------------------------- 数据播种 ----------------------------------

    /**
     * 在指定 AA 账本内制造未结清净额：Alice 付 90、Alice+Bob 均分（各 45）→ Bob net=-45。
     * 经真实 {@link com.damien.youyu.service.aa.AaExpenseService} 落库（含账户扣款与分摊）。
     */
    private void seedUnsettledExpense(Long ledgerId) {
        LocalDateTime now = LocalDateTime.now();
        com.damien.youyu.domain.Account acc = new com.damien.youyu.domain.Account();
        acc.setUserId(ALICE);
        acc.setName("现金");
        acc.setType(com.damien.youyu.domain.AccountType.CASH);
        acc.setInitialBalance(new java.math.BigDecimal("300.00"));
        acc.setCurrentBalance(new java.math.BigDecimal("300.00"));
        acc.setSortOrder(0);
        acc.setCreatedAt(now);
        acc.setUpdatedAt(now);
        acc = accountRepository.save(acc);

        com.damien.youyu.domain.Category cat = new com.damien.youyu.domain.Category();
        cat.setLedgerId(ledgerId);
        cat.setKind(com.damien.youyu.domain.CategoryKind.EXPENSE);
        cat.setName("餐饮");
        cat.setCreatedAt(now);
        cat.setUpdatedAt(now);
        cat = categoryRepository.save(cat);

        aaExpenseService.create(ALICE, ledgerId, new java.math.BigDecimal("90.00"), cat.getId(),
                ALICE, acc.getId(), null, "聚餐",
                com.damien.youyu.service.aa.AaExpenseService.SPLIT_EVEN, List.of(ALICE, BOB), null);
    }


    private Ledger seedLedger(String type) {
        return seedLedgerOwnedBy(type, ALICE);
    }

    private Ledger seedLedgerOwnedBy(String type, long ownerId) {
        LocalDateTime now = LocalDateTime.now();
        Ledger l = new Ledger();
        l.setUserId(ownerId);
        l.setName("账本");
        l.setType(type);
        l.setSortOrder(0);
        l.setDefault(false);
        l.setCreatedAt(now);
        l.setUpdatedAt(now);
        Ledger saved = ledgerRepository.save(l);
        member(saved.getId(), ownerId, LedgerMember.ROLE_OWNER);
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

    private LedgerInvite seedInvite(Long ledgerId) {
        LocalDateTime now = LocalDateTime.now();
        LedgerInvite invite = new LedgerInvite();
        invite.setCode("AACODE" + ledgerId);
        invite.setLedgerId(ledgerId);
        invite.setCreatedBy(ALICE);
        invite.setExpiresAt(now.plusDays(7));
        invite.setCreatedAt(now);
        return inviteRepository.save(invite);
    }

    private long seedUser(String nickname) {
        LocalDateTime now = LocalDateTime.now();
        User u = new User();
        u.setNickname(nickname);
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.save(u).getId();
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

    private HttpHeaders headers(long userId) {
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
}
