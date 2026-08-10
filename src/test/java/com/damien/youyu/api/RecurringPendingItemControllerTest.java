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
 * {@link RecurringPendingItemController} 的<b>控制器契约与安全边界</b>集成测试（tasks 7.2，
 * 需求 4.1、4.4、5.1、5.4、5.5、5.6、8.1、8.2、8.3）。
 *
 * <p>照抄 {@link AaExpenseControllerTest} 的 {@code @SpringBootTest}(RANDOM_PORT) + {@code TestRestTemplate}
 * + 手工签发 JWT 范式，并沿用 {@link com.damien.youyu.service.recurring.RecurringConfirmTest} 的固定时钟
 * （{@code Asia/Shanghai} 的 2025-06-15）与真实 H2({@code MODE=MySQL}) 播种（账户 + {@code account_ledger}
 * 链接 + 分类 + ACTIVE 规则）。经真实 HTTP、真实 Spring Security 过滤链、真实 {@code CurrentLedger}
 * （{@code X-Ledger-Id} 解析）与真实 {@code TransactionService} 记账链路，覆盖：</p>
 *
 * <ol>
 *   <li>无令牌：401 {@code UNAUTHENTICATED}（需求 8.2）。</li>
 *   <li>GET 查询：先懒生成，再返回当前账本 PENDING 列表（按到期日升序，每项含快照字段，需求 5.1、5.2）。</li>
 *   <li>确认：走真实记账链路生成流水、按支出方向扣款、置 CONFIRMED 并回填流水 id（需求 4.1）。</li>
 *   <li>跳过：置 SKIPPED、不生成流水、不改余额（需求 4.4）。</li>
 *   <li>批量确认 / 批量跳过：逐条独立处理，返回逐条结果与计数；已处理条目记为
 *       {@code RECURRING_ITEM_ALREADY_PROCESSED} 失败而不影响其余（需求 5.4、5.5、5.6）。</li>
 *   <li>跨账本确认：目标项归属另一账本 → 404 {@code NOT_FOUND}（需求 8.3、8.5）。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-pending-ctrl-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringPendingItemControllerTest {

    private static final String PATH = "/api/recurring/pending-items";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 00:00Z → today = 2025-06-15（Asia/Shanghai）。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2025, 6, 15);
    /** DAILY 规则开始日期：跨 3 个自然日（13/14/15）→ 懒生成恰产出 3 条 PENDING。 */
    private static final LocalDate START = LocalDate.of(2025, 6, 13);

    private static final long ALICE = 5001L;
    private static final long BOB = 5002L;

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

    private Long ledgerId;
    private Long accountId;
    private Long categoryId;

    @BeforeEach
    void reset() {
        // 真实提交、不靠回滚：每个用例前硬清相关表。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountLedgerRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();
        memberRepository.deleteAll();
        ledgerRepository.deleteAll();

        ledgerId = seedLedger(ALICE).getId();
        accountId = seedAccount(ALICE, "1000.00");
        linkAccountToLedger(accountId, ledgerId);
        categoryId = seedCategory(ledgerId);
    }

    // ---------------- 1) 无令牌 → 401（需求 8.2）----------------

    @Test
    void list_withoutToken_returnsUnauthenticated() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(com.damien.youyu.security.CurrentLedger.HEADER, String.valueOf(ledgerId));

        ResponseEntity<String> response = get(PATH, headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(parse(response)).containsEntry("code", "UNAUTHENTICATED");
    }

    // ---------------- 2) GET：先懒生成再返回 PENDING 列表（需求 5.1、5.2）----------------

    @Test
    void list_triggersLazyGeneration_returnsPendingItemsSortedByOccurrence() {
        seedDailyRule(new BigDecimal("50.00"), "房租");

        List<Map<String, Object>> items = listItems();

        // DAILY 跨 06-13/14/15 → 恰 3 条 PENDING。
        assertThat(items).hasSize(3);
        // 每项携带来源规则 id、期次到期日与模板快照字段（需求 5.1）。
        Map<String, Object> first = items.get(0);
        assertThat(first.get("id")).isNotNull();
        assertThat(first.get("ruleId")).isNotNull();
        assertThat(first).containsEntry("status", "PENDING");
        assertThat(first).containsEntry("type", "expense");
        assertThat(first).containsEntry("categoryId", categoryId.intValue());
        assertThat(first).containsEntry("accountId", accountId.intValue());
        assertThat(new BigDecimal(first.get("amount").toString())).isEqualByComparingTo("50.00");
        // 按到期日升序（需求 5.2）：13 → 14 → 15。
        assertThat(items.stream().map(i -> i.get("occurrenceDate").toString()).toList())
                .containsExactly("2025-06-13", "2025-06-14", "2025-06-15");
    }

    // ---------------- 3) 确认：真实记账链路 + 扣款 + 置 CONFIRMED（需求 4.1）----------------

    @Test
    void confirm_createsTransaction_updatesBalance_marksConfirmed() {
        seedDailyRule(new BigDecimal("50.00"), "房租");
        List<Map<String, Object>> items = listItems();
        long itemId = idOf(items.get(0));

        ResponseEntity<String> response = post(PATH + "/" + itemId + "/confirm", null,
                memberHeaders(ALICE, ledgerId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> json = parse(response);
        assertThat(json).containsEntry("status", "CONFIRMED");
        assertThat(json.get("confirmedTransactionId")).isNotNull();
        // 恰一条真实流水，账户按支出方向扣 50（1000 - 50 = 950）。
        assertThat(transactionRepository.count()).isEqualTo(1);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("950.00");
    }

    // ---------------- 4) 跳过：置 SKIPPED、零副作用（需求 4.4）----------------

    @Test
    void skip_marksSkipped_withoutTransactionOrBalanceChange() {
        seedDailyRule(new BigDecimal("50.00"), "房租");
        List<Map<String, Object>> items = listItems();
        long itemId = idOf(items.get(0));

        ResponseEntity<String> response = post(PATH + "/" + itemId + "/skip", null,
                memberHeaders(ALICE, ledgerId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parse(response)).containsEntry("status", "SKIPPED");
        // 不生成流水、不改余额（需求 4.4）。
        assertThat(transactionRepository.count()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("1000.00");
    }

    // ---------------- 5) 批量确认 / 批量跳过：逐条结果与计数（需求 5.4、5.5、5.6）----------------

    @Test
    void batchConfirmThenBatchSkip_returnPerItemResults() {
        seedDailyRule(new BigDecimal("50.00"), "房租");
        List<Map<String, Object>> items = listItems();
        long id1 = idOf(items.get(0));
        long id2 = idOf(items.get(1));
        long id3 = idOf(items.get(2));

        // 批量确认 id1、id2 → 两条成功。
        ResponseEntity<String> confirmResp = post(PATH + "/batch-confirm",
                Map.of("ids", List.of(id1, id2)), memberHeaders(ALICE, ledgerId));
        assertThat(confirmResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> confirmJson = parse(confirmResp);
        assertThat(confirmJson).containsEntry("successCount", 2);
        assertThat(confirmJson).containsEntry("failureCount", 0);
        @SuppressWarnings("unchecked")
        List<Object> succeeded = (List<Object>) confirmJson.get("succeededIds");
        assertThat(succeeded).containsExactlyInAnyOrder((int) id1, (int) id2);
        // 两条确认 → 两条流水，账户扣 100（1000 - 100 = 900）。
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("900.00");

        // 批量跳过 id2（已确认）、id3（PENDING）→ id3 成功、id2 记为已处理失败。
        ResponseEntity<String> skipResp = post(PATH + "/batch-skip",
                Map.of("ids", List.of(id2, id3)), memberHeaders(ALICE, ledgerId));
        assertThat(skipResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> skipJson = parse(skipResp);
        assertThat(skipJson).containsEntry("successCount", 1);
        assertThat(skipJson).containsEntry("failureCount", 1);
        @SuppressWarnings("unchecked")
        List<Object> skipSucceeded = (List<Object>) skipJson.get("succeededIds");
        assertThat(skipSucceeded).containsExactly((int) id3);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) skipJson.get("failed");
        assertThat(failed).hasSize(1);
        assertThat(failed.get(0)).containsEntry("itemId", (int) id2);
        assertThat(failed.get(0)).containsEntry("errorCode", "RECURRING_ITEM_ALREADY_PROCESSED");
        // 跳过不动账户 / 流水：仍为两条流水、余额 900。
        assertThat(transactionRepository.count()).isEqualTo(2);
        assertThat(balanceOf(accountId)).isEqualByComparingTo("900.00");
    }

    // ---------------- 6) 跨账本确认 → 404 NOT_FOUND（需求 8.3、8.5）----------------

    @Test
    void confirm_crossLedger_returnsNotFound() {
        seedDailyRule(new BigDecimal("50.00"), "房租");
        List<Map<String, Object>> items = listItems();
        long itemId = idOf(items.get(0));
        // ALICE 另有一个账本 L2（其成员），但目标项归属 L1。
        Long otherLedgerId = seedLedger(ALICE).getId();

        ResponseEntity<String> response = post(PATH + "/" + itemId + "/confirm", null,
                memberHeaders(ALICE, otherLedgerId));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(parse(response)).containsEntry("code", "NOT_FOUND");
        // 零副作用：目标项仍 PENDING、无流水、余额不变。
        assertThat(transactionRepository.count()).isZero();
        assertThat(balanceOf(accountId)).isEqualByComparingTo("1000.00");
    }

    // ---------------------------------- 数据播种 ----------------------------------

    private Ledger seedLedger(long ownerId) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Ledger l = new Ledger();
        l.setUserId(ownerId);
        l.setName("个人账本");
        l.setType(Ledger.TYPE_PERSONAL);
        l.setSortOrder(0);
        l.setDefault(false);
        l.setCreatedAt(now);
        l.setUpdatedAt(now);
        Ledger saved = ledgerRepository.save(l);
        LedgerMember m = new LedgerMember();
        m.setLedgerId(saved.getId());
        m.setUserId(ownerId);
        m.setRole(LedgerMember.ROLE_OWNER);
        m.setCreatedAt(now);
        memberRepository.save(m);
        return saved;
    }

    private Long seedAccount(long userId, String balance) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Account a = new Account();
        a.setUserId(userId);
        a.setName("现金");
        a.setType(AccountType.CASH);
        a.setInitialBalance(new BigDecimal(balance));
        a.setCurrentBalance(new BigDecimal(balance));
        a.setSortOrder(0);
        a.setCreatedAt(now);
        a.setUpdatedAt(now);
        return accountRepository.save(a).getId();
    }

    private void linkAccountToLedger(Long accountId, long ledgerId) {
        AccountLedger link = new AccountLedger();
        link.setAccountId(accountId);
        link.setLedgerId(ledgerId);
        link.setVisibleToOthers(true);
        link.setShowBalance(true);
        link.setCreatedAt(LocalDateTime.ofInstant(NOW, ZONE));
        accountLedgerRepository.save(link);
    }

    private Long seedCategory(long ledgerId) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Category c = new Category();
        c.setUserId(ALICE);
        c.setLedgerId(ledgerId);
        c.setParentId(null);
        c.setKind(CategoryKind.EXPENSE);
        c.setName("餐饮");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c).getId();
    }

    /**
     * 直接落库一条 ACTIVE 每天规则（绕过创建校验，聚焦控制器契约）。开始日期与 {@code updated_at} 均取
     * {@link #START}，使生成下界 {@code max(startDate, updatedAt)} = 06-13，懒生成对 today=06-15 恰补齐 3 期。
     */
    private RecurringRule seedDailyRule(BigDecimal amount, String note) {
        LocalDateTime startTs = START.atStartOfDay();
        RecurringRule rule = new RecurringRule();
        rule.setUserId(ALICE);
        rule.setLedgerId(ledgerId);
        rule.setType("expense");
        rule.setAmount(amount);
        rule.setCategoryId(categoryId);
        rule.setAccountId(accountId);
        rule.setNote(note);
        rule.setFrequency(Frequency.DAILY);
        rule.setMonthEnd(false);
        rule.setStartDate(START);
        rule.setEndCondition(EndCondition.NEVER);
        rule.setStatus(RuleStatus.ACTIVE);
        rule.setCreatedAt(startTs);
        rule.setUpdatedAt(startTs);
        return ruleRepository.save(rule);
    }

    private BigDecimal balanceOf(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow().getCurrentBalance();
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    /** GET 列表并断言 200，解析为 List<Map>。 */
    private List<Map<String, Object>> listItems() {
        ResponseEntity<String> response = get(PATH, memberHeaders(ALICE, ledgerId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parseList(response);
    }

    private long idOf(Map<String, Object> item) {
        return Long.parseLong(item.get("id").toString());
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

    /** 已认证成员的请求头：Bearer 令牌 + X-Ledger-Id + JSON。 */
    private HttpHeaders memberHeaders(long userId, Long ledgerId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token(userId, jwtSecret, Duration.ofHours(1)));
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
