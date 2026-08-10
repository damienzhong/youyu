package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.EndCondition;
import com.damien.youyu.domain.Frequency;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.RuleStatus;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.repository.RecurringRuleRepository;
import com.damien.youyu.repository.ReminderQuotaRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.wechat.WeChatAccessTokenProvider;
import com.damien.youyu.wechat.WeChatClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * 周期记账「提醒衔接」的集成测试（tasks 8.3；需求 7.1、7.2、7.4、7.6）。
 *
 * <p>沿用 {@link RecurringPendingItemControllerTest} 的 {@code @SpringBootTest}(RANDOM_PORT) +
 * {@code TestRestTemplate} + 手工签发 JWT + 固定时钟（{@code Asia/Shanghai} 的 2025-06-15）+ 真实
 * H2({@code MODE=MySQL}) 播种范式。经真实 HTTP、真实 Spring Security 过滤链、真实 {@code CurrentLedger}
 * （{@code X-Ledger-Id} 解析）触发 {@code GET /api/recurring/pending-items} →
 * {@link com.damien.youyu.api.RecurringPendingItemController#list()} 内在查询完成后调用
 * {@link com.damien.youyu.service.recurring.RecurringReminderNotifier#notifyIfPending}，验证提醒衔接：</p>
 *
 * <ol>
 *   <li>规则存在到期 PENDING 待确认项 + 所有者持有有效订阅额度 + 已绑定 openid → 恰发送一条订阅消息，
 *       且查询照常返回待确认项（需求 7.1）。</li>
 *   <li>各类故障（微信接口返回非零 errcode / 抛异常 / 超时归一为哨兵码）都不阻断主路径：GET 仍 200 且
 *       返回待确认项（需求 7.2、7.6）。</li>
 *   <li>无有效订阅额度 → 不发送，但查询仍正常返回待确认项（需求 7.4）。</li>
 * </ol>
 *
 * <p>微信侧 {@link WeChatClient} 与凭证网关 {@link WeChatAccessTokenProvider} 以 {@link MockitoBean} 替身
 * 注入，从而不外呼真实微信、不消耗凭证额度；订阅额度经真实 {@link ReminderQuotaRepository} 播种、收件
 * openid 经真实 {@link UserRepository} 播种（{@code users.wx_openid}）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-recurring-reminder-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
class RecurringReminderIntegrationTest {

    private static final String PATH = "/api/recurring/pending-items";
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 2025-06-15 00:00Z → today = 2025-06-15（Asia/Shanghai）。 */
    private static final Instant NOW = Instant.parse("2025-06-15T00:00:00Z");
    /** DAILY 规则开始日期：跨 3 个自然日（13/14/15）→ 懒生成恰产出 3 条 PENDING。 */
    private static final LocalDate START = LocalDate.of(2025, 6, 13);
    private static final String OPENID = "o-alice-openid";
    private static final String TOKEN = "tk-recurring-remind";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZONE);
        }
    }

    /** 微信订阅消息发送替身：只计数并按各测试打桩回值 / 抛异常，绝不外呼真实微信。 */
    @MockitoBean
    private WeChatClient weChatClient;
    /** 凭证网关替身：发送分支取 token 用。 */
    @MockitoBean
    private WeChatAccessTokenProvider accessTokenProvider;

    @LocalServerPort
    private int port;

    @Value("${app.jwt.secret}")
    private String jwtSecret;

    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private UserRepository userRepository;
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
    @Autowired
    private ReminderQuotaRepository quotaRepository;
    @Autowired
    private PlatformTransactionManager txManager;

    /** 账本所有者 / 提醒收件人；每个用例新建，故 id 唯一，避免提醒去重窗口跨用例串扰。 */
    private Long ownerId;
    private Long ledgerId;
    private Long accountId;
    private Long categoryId;
    private TransactionTemplate tx;

    @BeforeEach
    void reset() {
        tx = new TransactionTemplate(txManager);
        // 真实提交、不靠回滚：每个用例前硬清相关表。
        pendingItemRepository.deleteAll();
        ruleRepository.deleteAll();
        transactionRepository.deleteAll();
        accountLedgerRepository.deleteAll();
        accountRepository.deleteAll();
        categoryRepository.deleteAll();
        memberRepository.deleteAll();
        ledgerRepository.deleteAll();
        quotaRepository.deleteAll();
        userRepository.deleteAll();

        // 新建带 openid 的用户作为所有者；自增 id 唯一，兼作 JWT subject / 规则归属 / 账本 owner。
        ownerId = seedUser(OPENID);
        ledgerId = seedLedger(ownerId).getId();
        accountId = seedAccount(ownerId, "1000.00");
        linkAccountToLedger(accountId, ledgerId);
        categoryId = seedCategory(ownerId, ledgerId);
        seedDailyRule(new BigDecimal("50.00"), "房租");
    }

    // ---------- 1) 有额度 + openid → 发送一条，查询照常返回（需求 7.1）----------

    @Test
    void list_withQuotaAndOpenid_sendsExactlyOneReminder_andReturnsItems() {
        grantQuota(ownerId, 3);
        when(accessTokenProvider.getToken()).thenReturn(TOKEN);
        when(weChatClient.sendSubscribeMessage(anyString(), eq(OPENID), anyString())).thenReturn(0);

        List<Map<String, Object>> items = listItems();

        // 查询主路径照常：DAILY 跨 06-13/14/15 → 恰 3 条 PENDING（需求 7.1）。
        assertThat(items).hasSize(3);
        // 恰向所有者发送一条「存在待确认周期记账」提醒（需求 7.1）。
        verify(weChatClient, times(1)).sendSubscribeMessage(eq(TOKEN), eq(OPENID), anyString());
        // 发送成功后，共享订阅额度被真正扣减 1（3 → 2）：额度写在请求线程、主路径事务之外，须有自己的
        // 独立事务边界才能落库；否则会因无活动事务抛 TransactionRequiredException 被吞、额度从未扣减（需求 7.1）。
        assertThat(quotaRepository.findRemaining(ownerId)).contains(2);
    }

    // ---------- 2a) 微信返回非零 errcode → 不阻断主路径（需求 7.2、7.6）----------

    @Test
    void list_whenWeChatReturnsNonZero_doesNotBlockQuery() {
        grantQuota(ownerId, 3);
        when(accessTokenProvider.getToken()).thenReturn(TOKEN);
        // 非零 errcode（含超时归一的本地哨兵 -1、微信业务错误码等）：投递失败但不外抛。
        when(weChatClient.sendSubscribeMessage(anyString(), eq(OPENID), anyString())).thenReturn(40003);

        ResponseEntity<String> response = get(PATH, memberHeaders(ownerId, ledgerId));

        // 主路径不受影响：GET 仍 200 且返回全部待确认项（需求 7.2、7.6）。
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parseList(response)).hasSize(3);
        verify(weChatClient, times(1)).sendSubscribeMessage(anyString(), eq(OPENID), anyString());
    }

    // ---------- 2b) 微信调用抛异常 → 不阻断主路径（需求 7.2、7.6）----------

    @Test
    void list_whenWeChatThrows_doesNotBlockQuery() {
        grantQuota(ownerId, 3);
        when(accessTokenProvider.getToken()).thenReturn(TOKEN);
        when(weChatClient.sendSubscribeMessage(anyString(), eq(OPENID), anyString()))
                .thenThrow(new RuntimeException("boom: 微信接口不可用/超时"));

        ResponseEntity<String> response = get(PATH, memberHeaders(ownerId, ledgerId));

        // 提醒链路异常被就地吞掉，绝不冒泡到查询主路径（需求 7.2、7.6）。
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(parseList(response)).hasSize(3);
    }

    // ---------- 3) 无有效额度 → 不发送，但查询照常返回（需求 7.4）----------

    @Test
    void list_withoutQuota_doesNotSend_butReturnsItems() {
        // 不授予任何订阅额度（reminder_quota 无行 → 剩余折算为 0）。
        when(accessTokenProvider.getToken()).thenReturn(TOKEN);

        List<Map<String, Object>> items = listItems();

        // 懒生成与呈现照常（需求 7.4）。
        assertThat(items).hasSize(3);
        // 无有效额度 → 不发送提醒（需求 7.4）。
        verify(weChatClient, never()).sendSubscribeMessage(anyString(), anyString(), anyString());
    }

    // ---------------------------------- 数据播种 ----------------------------------

    private Long seedUser(String openid) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        User user = new User();
        user.setWxOpenid(openid);
        user.setPlan(Plan.FREE);
        user.setRole(Role.USER);
        user.setPlanStartedAt(now);
        user.setPlanExpiresAt(now.plusYears(1));
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        return userRepository.save(user).getId();
    }

    /** 在独立事务内授予订阅额度（{@code addCapped} 为 {@code @Modifying} 写，须有事务边界）。 */
    private void grantQuota(Long userId, int delta) {
        tx.executeWithoutResult(status ->
                quotaRepository.addCapped(userId, delta, LocalDateTime.ofInstant(NOW, ZONE)));
    }

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

    private Long seedCategory(long userId, long ledgerId) {
        LocalDateTime now = LocalDateTime.ofInstant(NOW, ZONE);
        Category c = new Category();
        c.setUserId(userId);
        c.setLedgerId(ledgerId);
        c.setParentId(null);
        c.setKind(CategoryKind.EXPENSE);
        c.setName("餐饮");
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return categoryRepository.save(c).getId();
    }

    /**
     * 直接落库一条 ACTIVE 每天规则（绕过创建校验，聚焦提醒衔接）。开始日期与 {@code updated_at} 均取
     * {@link #START}，使生成下界 {@code max(startDate, updatedAt)} = 06-13，懒生成对 today=06-15 恰补齐 3 期。
     */
    private RecurringRule seedDailyRule(BigDecimal amount, String note) {
        LocalDateTime startTs = START.atStartOfDay();
        RecurringRule rule = new RecurringRule();
        rule.setUserId(ownerId);
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

    // ---------------------------------- 请求辅助 ----------------------------------

    /** GET 列表并断言 200，解析为 List<Map>。 */
    private List<Map<String, Object>> listItems() {
        ResponseEntity<String> response = get(PATH, memberHeaders(ownerId, ledgerId));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parseList(response);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<String> get(String path, HttpHeaders headers) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(headers), String.class);
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
