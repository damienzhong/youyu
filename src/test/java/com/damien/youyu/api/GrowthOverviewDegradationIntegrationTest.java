package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserGrowthRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.security.JwtService;
import com.damien.youyu.service.GrowthSettlementService;
import com.damien.youyu.service.SettleOutcome;
import com.damien.youyu.service.TriggerSource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 成长概览的<b>降级返回</b>集成测试（任务 6.6，需求 9.10、9.11、10.11、10.14）。
 *
 * <p>走 {@code @SpringBootTest} + MockMvc：真实控制器、真实过滤链、真实
 * {@link com.damien.youyu.service.GrowthQueryService} 与 H2（{@code MODE=MySQL}）持久化层。
 * 唯一的测试替身是 {@link CountingSettlementService}——一个 {@code @Primary} 的
 * {@link GrowthSettlementService} 子类，默认<b>委托</b>给真实（被事务代理包裹的）结算 bean，只在委托
 * 前后<b>计数</b>、<b>记录结算结果</b>，并可按需在委托前抛出注入异常。它<b>不替换</b>真实结算逻辑，
 * 因此「概览路径会真的尝试结算」这条事实仍受检验；同时它让本测试能：① 令结算<b>抛异常</b>观察降级
 * （需求 9.10、9.11）；② 观察结算被 10 秒窗口<b>节流跳过</b>时的响应字段集（需求 10.14）；③ 断言明细
 * 接口<b>零结算</b>（需求 10.11）。</p>
 *
 * <h2>三条被锁住的性质</h2>
 * <ol>
 *   <li><b>结算失败 + 无档案的降级</b>（需求 9.11）：令结算抛异常且该用户从未建档，概览仍返回
 *       {@code 200}，等级 1 / 经验 0 / 三项天数 0 / 16 枚未点亮，<b>但累计笔数与金额是真实值</b>
 *       （它们来自交易事实源的实时聚合，与档案无关）。同时确认结算失败没有留下任何档案行
 *       （异常穿出使 {@code REQUIRES_NEW} 事务回滚，无部分写入）。</li>
 *   <li><b>结算被节流跳过</b>（需求 10.14）：同一用户 10 秒内的第二次概览请求，其结算返回
 *       {@link SettleOutcome#SKIPPED_THROTTLED}；断言此响应的<b>字段集</b>与执行结算时<b>完全相同</b>、
 *       {@code 200}、不返回任何错误码（结算节流对客户端不可见，需求 10.14、10.15）。</li>
 *   <li><b>明细接口零结算</b>（需求 10.11）：{@code GET /api/growth/events} 全程<b>不调用</b>
 *       {@code settle}——用计数装饰器断言调用数为 {@code 0}。</li>
 * </ol>
 *
 * <p>时钟用默认系统时钟（不覆盖 {@code TimeConfig}）：10 秒概览节流窗口由「同一测试方法内两次相隔
 * 毫秒级的请求」自然落入，无需推进时钟。节流器是进程内单例、状态跨方法留存且无清理入口
 * （需求 10.14），故每个测试方法都用<b>各自独立的 {@code userId}</b> 隔离，方法间互不影响。</p>
 *
 * <p>结算真实提交，清理不能靠事务回滚：{@link #cleanup()} 每个用例前硬删三张表并重置装饰器。
 * 使用独立命名的内存库，避免污染其它共享内存库的切片测试。</p>
 *
 * <p>Validates: Requirements 9.10, 9.11, 10.11, 10.14</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-degrade-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Import(GrowthOverviewDegradationIntegrationTest.CountingConfig.class)
class GrowthOverviewDegradationIntegrationTest {

    private static final LocalDateTime SEED_AT = LocalDateTime.of(2025, 6, 15, 8, 0, 0);

    /** 概览响应的 15 个顶层字段（需求 10.1、10.13）：字段集相等断言的期望值。 */
    private static final List<String> OVERVIEW_FIELDS = List.of(
            "level", "exp", "currentLevelExp", "nextLevelExp", "expInCurrentLevel", "expToNextLevel",
            "maxLevel", "maxLevelReached", "totalRecordCount", "totalExpense", "totalIncome",
            "totalRecordDays", "currentStreakDays", "maxStreakDays", "badges");

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private UserGrowthRepository userGrowthRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private CountingSettlementService counting;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanup() {
        // 结算真实提交，清理不能靠回滚：每个用例前硬删三张表。两表均无外键，删除顺序无约束。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM users");
        counting.reset();
    }

    // ==================== 1) 结算失败 + 无档案：降级返回真实累计（需求 9.10、9.11） ====================

    /**
     * 结算抛异常且该用户从未建档：概览返回 {@code 200}，等级 1 / 经验 0 / 三项天数 0 / 16 枚未点亮，
     * 但<b>累计笔数与金额是真实值</b>（需求 9.10、9.11）。
     *
     * <p>预置 5 笔有效记账交易（3 笔支出合计 35.50、2 笔收入合计 150.00），使累计统计的真实值非零；
     * 令结算抛异常（{@code REQUIRES_NEW} 事务因异常穿出而回滚，从不建档）。断言：档案取值全部走无档案
     * 降级分支（Lv1 / 0 经验 / 三项天数 0 / 徽章全灭），而累计笔数=5、支出=35.50、收入=150.00 全为真实值。
     * 最后确认 {@code user_growth} 对该用户零行——结算失败没有留下任何部分写入。</p>
     */
    @Test
    void settlementFailsAndNoProfile_degradesButReturnsRealCumulativeStats() throws Exception {
        long ledgerId = 91_001L;
        User user = seedUser("degrade-noprofile");
        long userId = user.getId();
        String token = jwtService.generateToken(user);

        // 5 笔有效记账：3 支出（10.00 + 20.00 + 5.50 = 35.50）、2 收入（100.00 + 50.00 = 150.00）。
        seedValidRecord(userId, ledgerId, new BigDecimal("10.00"), TransactionType.EXPENSE);
        seedValidRecord(userId, ledgerId, new BigDecimal("20.00"), TransactionType.EXPENSE);
        seedValidRecord(userId, ledgerId, new BigDecimal("5.50"), TransactionType.EXPENSE);
        seedValidRecord(userId, ledgerId, new BigDecimal("100.00"), TransactionType.INCOME);
        seedValidRecord(userId, ledgerId, new BigDecimal("50.00"), TransactionType.INCOME);

        // 令概览触发的结算抛异常：GrowthQueryService 在事务边界外吞掉，照常降级返回。
        counting.throwOnSettle(new IllegalStateException("注入：结算失败"));

        MvcResult result = getOverview(token);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);

        JsonNode body = objectMapper.readTree(bodyOf(result));
        // 无档案降级：等级 1 / 经验 0 / 三项天数 0（需求 9.11）。
        assertThat(body.get("level").asInt()).isEqualTo(1);
        assertThat(body.get("exp").asLong()).isZero();
        assertThat(body.get("totalRecordDays").asInt()).isZero();
        assertThat(body.get("currentStreakDays").asInt()).isZero();
        assertThat(body.get("maxStreakDays").asInt()).isZero();

        // 16 枚徽章且全部未点亮（需求 9.11；achievement-system 需求 12.2、12.10）。
        JsonNode badges = body.get("badges");
        assertThat(badges.isArray()).isTrue();
        assertThat(badges).hasSize(16);
        for (JsonNode badge : badges) {
            assertThat(badge.get("unlocked").asBoolean()).as("徽章 %s 应未点亮", badge.get("code").asText()).isFalse();
            assertThat(badge.get("unlockedAt").isNull()).as("徽章 %s 解锁时刻应为空", badge.get("code").asText()).isTrue();
        }

        // 累计笔数与金额为真实值（需求 9.11：来自交易事实源，与档案无关）。
        assertThat(body.get("totalRecordCount").asLong()).isEqualTo(5L);
        assertThat(new BigDecimal(body.get("totalExpense").asText())).isEqualByComparingTo("35.50");
        assertThat(new BigDecimal(body.get("totalIncome").asText())).isEqualByComparingTo("150.00");

        // 结算确实被尝试了一次（降级不是靠「根本没结算」蒙混），且失败未留下任何档案行。
        assertThat(counting.settleCalls()).isEqualTo(1);
        assertThat(userGrowthRepository.findById(userId)).isEmpty();
    }

    // ==================== 2) 结算被 10 秒节流：字段集与执行结算时相同（需求 10.14） ====================

    /**
     * 同一用户 10 秒内的第二次概览请求，其结算被节流跳过（{@link SettleOutcome#SKIPPED_THROTTLED}）：
     * 响应<b>字段集与执行结算时完全相同</b>、{@code 200}、不返回任何错误码（需求 10.14、10.15）。
     *
     * <p>第一次请求让结算<b>真实执行</b>并 {@code markSettled}；毫秒级后的第二次请求落在 10 秒窗口内、
     * 被节流跳过。断言两次都是 {@code 200}、装饰器记录的两次结算结果分别为 {@code SETTLED} 与
     * {@code SKIPPED_THROTTLED}（直接证明第二次走了节流分支），两次响应的顶层字段集<b>逐一相等</b>且
     * 均为需求 10.1 的 15 项，两个响应体都不含 {@code code}（无错误码，结算节流对客户端不可见）。</p>
     */
    @Test
    void secondOverviewWithin10s_isThrottledButFieldSetUnchanged() throws Exception {
        long ledgerId = 91_002L;
        User user = seedUser("throttle");
        long userId = user.getId();
        String token = jwtService.generateToken(user);
        seedValidRecord(userId, ledgerId, new BigDecimal("42.00"), TransactionType.EXPENSE);

        // 第一次：结算真实执行（SETTLED）并标记节流窗口。
        MvcResult first = getOverview(token);
        // 第二次（毫秒级之后，落在 10 秒窗口内）：结算被节流跳过（SKIPPED_THROTTLED）。
        MvcResult second = getOverview(token);

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(second.getResponse().getStatus()).isEqualTo(200);

        // 直接证明第二次走了节流分支：两次结算结果分别为 SETTLED、SKIPPED_THROTTLED。
        assertThat(counting.outcomes()).containsExactly(SettleOutcome.SETTLED, SettleOutcome.SKIPPED_THROTTLED);

        JsonNode firstBody = objectMapper.readTree(bodyOf(first));
        JsonNode secondBody = objectMapper.readTree(bodyOf(second));

        // 字段集与执行结算时相同（需求 10.14），且恰好为需求 10.1 的 15 项（需求 10.13）。
        assertThat(fieldNames(secondBody))
                .containsExactlyInAnyOrderElementsOf(fieldNames(firstBody))
                .containsExactlyInAnyOrderElementsOf(OVERVIEW_FIELDS);

        // 结算节流不返回错误、不新增错误码：响应体不含错误体的 code 字段（需求 10.14、10.15）。
        assertThat(firstBody.has("code")).isFalse();
        assertThat(secondBody.has("code")).isFalse();
    }

    // ==================== 3) 明细接口零结算（需求 10.11） ====================

    /**
     * {@code GET /api/growth/events} 全程<b>不触发结算</b>（需求 10.11）：用计数装饰器断言 {@code settle}
     * 被调用 {@code 0} 次。
     *
     * <p>明细接口只读事件表、允许比概览旧，刻意不结算。请求返回 {@code 200}，且装饰器的结算计数恒为 0
     * ——与概览路径（会尝试结算）形成对照。</p>
     */
    @Test
    void listEvents_triggersZeroSettlement() throws Exception {
        long ledgerId = 91_003L;
        User user = seedUser("events-nosettle");
        long userId = user.getId();
        String token = jwtService.generateToken(user);
        seedValidRecord(userId, ledgerId, new BigDecimal("7.00"), TransactionType.EXPENSE);

        MvcResult result = mockMvc.perform(get("/api/growth/events")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        // 明细接口零结算（需求 10.11）。
        assertThat(counting.settleCalls()).isZero();
    }

    // ---------------------------------- 辅助 ----------------------------------

    private MvcResult getOverview(String token) throws Exception {
        return mockMvc.perform(get("/api/growth")
                        .header("Authorization", "Bearer " + token)
                        .accept(MediaType.APPLICATION_JSON))
                .andReturn();
    }

    private static String bodyOf(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new java.util.ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    /**
     * 写入一行真实用户（id 由数据库 IDENTITY 生成）。令牌须由该已保存用户签发、且用户须在 users 表中
     * 存在，否则控制器的「令牌用户仍存在」校验会返回 401（需求 10.6、10.7）。
     */
    private User seedUser(String tag) {
        User u = new User();
        u.setEmail("growth-degrade-" + tag + "@example.com");
        u.setNickname(tag);
        u.setInviteCode(inviteCodeOf(tag));
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(SEED_AT);
        u.setPlanExpiresAt(SEED_AT.plusDays(365));
        u.setCreatedAt(SEED_AT);
        u.setUpdatedAt(SEED_AT);
        return userRepository.save(u);
    }

    /** 8 位邀请码，带本类专属前缀 {@code G6}，避免与兄弟测试共用同一内存库时撞唯一约束。 */
    private static String inviteCodeOf(String tag) {
        String suffix = Integer.toString(Math.abs(tag.hashCode()), 36).toUpperCase(java.util.Locale.ROOT);
        String base = "G6" + suffix;
        if (base.length() > 8) {
            return base.substring(base.length() - 8);
        }
        return base + "0".repeat(8 - base.length());
    }

    /**
     * 提交一笔「有效记账交易」（{@code created_by} = 用户、{@code deleted_at} 为 NULL、
     * {@code type ∈ {expense,income}}、{@code ledger_id} 非 NULL），使累计统计能读到真实值。
     */
    private void seedValidRecord(long userId, long ledgerId, BigDecimal amount, TransactionType type) {
        Transaction tx = new Transaction();
        tx.setUserId(userId);
        tx.setLedgerId(ledgerId);
        tx.setCreatedBy(userId);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setAccountId(ledgerId);
        tx.setCategoryId(ledgerId);
        tx.setOccurredAt(SEED_AT);
        tx.setCreatedAt(SEED_AT);
        tx.setUpdatedAt(SEED_AT);
        transactionRepository.save(tx);
    }

    // ---------------------------------- 测试基础设施 ----------------------------------

    /**
     * 计数并可注入故障的 {@link GrowthSettlementService}：默认委托给真实（被事务代理包裹的）bean，
     * {@code REQUIRES_NEW} 因而照常生效。它<b>不是</b> Mockito 替身，也不替换真实结算——只在委托前后
     * 记录调用次数、结算结果，并可在委托前抛出注入异常。构造时给父类传 {@code null}：本类覆盖了
     * {@code settle} 并只委托给 {@code delegate}，父类字段永不被触及。
     */
    static class CountingSettlementService extends GrowthSettlementService {

        private final GrowthSettlementService delegate;
        private final AtomicInteger settleCalls = new AtomicInteger();
        private final List<SettleOutcome> outcomes = new CopyOnWriteArrayList<>();
        private volatile RuntimeException toThrow;

        CountingSettlementService(GrowthSettlementService delegate) {
            // 13 个 null：构造参数在 achievement-system 任务 4.1 从 11 个扩到 13 个
            // （新增 LedgerMemberRepository 与 GrowthSavingMonthEvaluator）。本桩全部方法都转发给
            // delegate，父类字段一个都不用，因此逐个传 null。
            super(null, null, null, null, null, null, null, null, null, null, null, null, null);
            this.delegate = delegate;
        }

        @Override
        public SettleOutcome settle(Long userId, TriggerSource source) {
            settleCalls.incrementAndGet();
            RuntimeException injected = this.toThrow;
            if (injected != null) {
                throw injected;
            }
            SettleOutcome outcome = delegate.settle(userId, source);   // 经事务代理 → REQUIRES_NEW 生效
            outcomes.add(outcome);
            return outcome;
        }

        void reset() {
            settleCalls.set(0);
            outcomes.clear();
            toThrow = null;
        }

        void throwOnSettle(RuntimeException e) {
            this.toThrow = e;
        }

        int settleCalls() {
            return settleCalls.get();
        }

        List<SettleOutcome> outcomes() {
            return List.copyOf(outcomes);
        }
    }

    @TestConfiguration
    static class CountingConfig {
        @Bean
        @Primary
        CountingSettlementService countingSettlementService(
                @Qualifier("growthSettlementService") GrowthSettlementService real) {
            return new CountingSettlementService(real);
        }
    }
}
