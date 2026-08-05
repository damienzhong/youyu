package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * 结算集成测试（任务 4.4，需求 2.6、4.11、4.12、4.14、4.15、4.16、1.11）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)：注入真实的 {@link GrowthSettlementService} 与其全部
 * 协作者（日历、预算、储蓄月、徽章清单、等级曲线、节流器、{@code JdbcTemplate}），对真实 H2
 * （{@code MODE=MySQL}）读写。<b>不用测试级事务包裹</b>——{@code settle} 带
 * {@code @Transactional(REQUIRES_NEW)}，只有让它真正提交才能在库里观察到结算终态；故每个用例都是
 * 「直插事实源 → 调用 {@code settle} → 从库读回断言」，并在 {@link #cleanup()} 里硬删相关表。
 * 事实源一律经 {@link JdbcTemplate} 直插而不走记账接口：这样「本次是第几次结算」是确定的，
 * 「一次结算内」这个前提才可断言（记账接口会在 {@code afterCommit} 里自己触发一次结算）。</p>
 *
 * <h2>本类不覆盖 Clock：日期一律相对 {@code LocalDate.now()}</h2>
 *
 * <p>与 {@code GrowthSettlementMainPathIntegrationTest} 不同，本类<b>刻意不注入可推进时钟</b>：
 * 最后一个用例要走真实 HTTP 的记账接口（含 JWT 签发与验证、邮箱验证码），把进程时钟挪到过去会让
 * 令牌与验证码的有效期判定与真实时钟错开，故全部构造改为「相对今天」表达。需要「第二次结算不被
 * 60 秒记账节流窗口跳过」时，用的是节流条件的<b>另一半</b>：把事实源的记账日放在<b>昨天</b>，
 * 于是 {@code last_record_date != 结算日} 恒成立，节流条件（窗口内 <b>且</b> 今天已记过账）永不满足。</p>
 *
 * <h2>七组断言</h2>
 * <ol>
 *   <li><b>3 个储蓄月与 {@code SAVING_MASTER} 同一次结算落库</b>——任务 4.3 组装顺序的<b>回归锁</b>，
 *       见 {@link #threeSavingMonths_singleSettlementWritesThreeRowsAndSavingMasterBadge()} 的方法
 *       Javadoc 里「为什么顺序改了这条断言就会红」的推演（需求 2.6）。</li>
 *   <li><b>笔数 0 → 1200</b>：一次结算写入 {@code FIRST_RECORD} / {@code RECORD_10} / {@code RECORD_100}
 *       / {@code RECORD_500} / {@code RECORD_1000} 五枚 {@code BADGE}，且 {@code id} 序与展示序号一致
 *       （需求 2.6 后半句）。</li>
 *   <li><b>连续天数 0 → 400</b>：四枚 {@code STREAK_*} 一枚不漏，{@code id} 序同样与展示序号一致。</li>
 *   <li><b>新增读查询恒为 3 条</b>：查询计数拦截器在「1 个账本 / 1 个分类 / 1 笔交易」与
 *       「20 个账本 / 200 个分类 / 10000 笔交易」两种规模下都断言恰好 3 条（需求 4.11）。</li>
 *   <li><b>单次结算写入事件数 ≤ {@link GrowthSettlementService#MAX_PENDING_EVENTS}</b>（需求 4.12）。</li>
 *   <li><b>解锁成就不动经验与等级</b>：第二次结算新解锁 4 枚成就 + 3 条储蓄月，{@code exp} 与
 *       {@code level} 与写入前逐项相等（需求 1.11）。</li>
 *   <li><b>故障注入</b>：{@code countTravelExpenses} 抛异常 → {@code REQUIRES_NEW} 整体回滚、三表零
 *       部分写入、{@code [GROWTH_SETTLE_FAILED]} WARN、记账接口状态码与字段集不变且不含成就字段、
 *       下一次结算补齐（需求 4.14、4.15、4.16）。</li>
 * </ol>
 *
 * <h2>查询计数与故障注入这两件事分别怎么做</h2>
 * <ul>
 *   <li><b>计数</b>：经 {@code hibernate.session_factory.statement_inspector} 注册
 *       {@link NewReadQueryInspector}，它只对本 spec 新增的三条读查询的<b>独有片段</b>计数
 *       （储蓄月的 {@code MONTH(occurred_at)}、旅行的 {@code 旅行} 字面量、协作的
 *       {@code ledger_members} + {@code ledgers} 双表）。选 {@code StatementInspector} 而不去包裹
 *       {@code DataSource}（沿用 {@code GrowthBackfillPropertyTest} 的做法）：它只看 Hibernate/JPA
 *       发出的 SQL，测试自己播种用的 {@code JdbcTemplate} 原生 SQL、以及结算内部建档与批量写事件走的
 *       {@code JdbcTemplate}，天然都不被计入。</li>
 *   <li><b>故障</b>：{@link FaultConfig} 用一个 {@code @Primary} 的 JDK 动态代理包住真实
 *       {@code TransactionRepository}，默认全部方法透明委托，仅当 {@link #TRAVEL_QUERY_FAULT} 置位时让
 *       {@code countTravelExpenses} 抛异常。<b>刻意不用 Mockito 对结算服务做 spy</b>——对带
 *       {@code @Transactional} 的类做 spy 会绕过 Spring 的事务代理、令 {@code REQUIRES_NEW} 失效，
 *       而「整体回滚」正是本组断言要验的东西；把故障下沉到仓储层则结算仍走真实事务代理。</li>
 * </ul>
 *
 * <p>使用独立命名的内存库，避免污染其它共享内存库的切片测试。</p>
 *
 * <p>Validates: Requirements 2.6, 4.11, 4.12, 4.14, 4.15, 4.16, 1.11</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-settle-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 计数型装饰器：只拦截本 spec 新增的三条读查询（见类级 Javadoc「查询计数」）。
        "spring.jpa.properties.hibernate.session_factory.statement_inspector="
                + "com.damien.youyu.service.AchievementSettlementIntegrationTest$NewReadQueryInspector"
})
@Import(AchievementSettlementIntegrationTest.FaultConfig.class)
class AchievementSettlementIntegrationTest {

    /** 16 枚成就的编码与<b>展示顺序</b>（需求 1.1 表格的独立副本，用于把「id 序 == 展示序号」钉死）。 */
    private static final List<String> CATALOG_CODES = List.of(
            "FIRST_RECORD",
            "STREAK_7", "STREAK_30", "STREAK_100", "STREAK_365",
            "RECORD_10", "RECORD_100", "RECORD_500", "RECORD_1000", "DAYS_100",
            "INVITE_1", "COLLAB_1",
            "BUDGET_MET", "BUDGET_MASTER", "SAVING_MASTER", "TRAVEL_MASTER");

    /** 记账接口响应字段集（需求 4.15：判定失败时与判定成功时逐项相同的那一份）。 */
    private static final Set<String> RECORD_KEYS = Set.of(
            "id", "ledgerId", "createdBy", "type", "amount", "accountId", "categoryId",
            "sourceAccountId", "destinationAccountId", "occurredAt", "note",
            "projectId", "merchantId", "tagIds");

    /**
     * 记账响应里绝不允许出现的成就 / 播报 / 徽章字段名（需求 4.15）。
     *
     * <p>按<b>原始 JSON 文本</b>比对而不是按解析后的键集合：嵌套一层的泄漏不会改变顶层键集合。
     * {@code level} / {@code exp} 写成<b>带引号的键形式</b>：裸子串 {@code exp} 会被 {@code type} 的
     * 合法取值 {@code "expense"} 命中，那样的断言不是更严而是恒假。</p>
     */
    private static final List<String> RECORD_FORBIDDEN_MARKERS = List.of(
            "achievement", "Achievement", "badge", "Badge", "unlock", "Unlock",
            "saving", "Saving", "notice", "Notice", "\"level\"", "\"exp\"", "\"badges\"");

    /** 交易直插语句：列顺序与 {@link #seedTransaction} 的参数顺序一致。 */
    private static final String INSERT_TX_SQL =
            "INSERT INTO transactions "
                    + "(user_id, ledger_id, created_by, type, amount, account_id, category_id, "
                    + "occurred_at, created_at, updated_at, deleted_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)";

    /** 置位后让 {@code countTravelExpenses} 抛异常（见 {@link FaultConfig}）。 */
    private static final AtomicBoolean TRAVEL_QUERY_FAULT = new AtomicBoolean(false);

    @LocalServerPort
    private int port;

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private TestRestTemplate rest;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private VerificationCodeRepository verificationCodeRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void cleanup() {
        TRAVEL_QUERY_FAULT.set(false);
        NewReadQueryInspector.reset();
        // 结算真实提交，清理不能靠事务回滚：每个用例前硬删事实源与三张成长/成就表。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM achievement_notices");
        jdbcTemplate.update("DELETE FROM transactions");
        jdbcTemplate.update("DELETE FROM ledger_members");
        jdbcTemplate.update("DELETE FROM ledgers");
        jdbcTemplate.update("DELETE FROM categories");
    }

    // ============ 1) 3 个储蓄月与 SAVING_MASTER 同一次结算落库（需求 2.6）============

    /**
     * 3 个回看月都是储蓄月 → <b>同一次</b>结算写入 3 条 {@code SAVING_MONTH} 与
     * {@code BADGE:SAVING_MASTER}（需求 2.6）。
     *
     * <h2>这是任务 4.3「{@code BADGE} 必须排在 {@code SAVING_MONTH} 之后组装」的回归锁</h2>
     *
     * <p>本用例只调用 {@code settle} <b>一次</b>，且用例开始时该用户在 {@code growth_events} 里没有任何行
     * ——这两个前提合起来使断言在顺序被改坏时<b>必然</b>失败，推演如下：</p>
     * <ol>
     *   <li>结算第 ③ 步的 {@code savingMonthCount} =「已落库事件键里 {@code SAVING_MONTH:} 前缀的条数」
     *       + 「本次新判定的月份数」。本用例的前者恒为 0（库里没有历史行），故 {@code facts} 里的
     *       {@code savingMonthCount} 完全来自后者的 3。</li>
     *   <li>若有人把 {@code BADGE} 的组装挪到 {@code SAVING_MONTH} 之前，{@code qualified(facts)} 读到的
     *       仍是同一个 {@code facts}——但那份 {@code facts} 之所以是 3，正是因为第 ③ 步显式加了
     *       {@code savingMonths.size()}。真正会漏发的改法是「把新判定的月份数从 {@code facts} 里去掉、
     *       改为在 {@code SAVING_MONTH} 落库<b>之后</b>重新前缀计数」——那时组装顺序就成了唯一依据：
     *       {@code BADGE} 若先组装，{@code SAVING_MONTH_COUNT} 读到 0 &lt; 门槛 3，
     *       {@code BADGE:SAVING_MASTER} 这一行本次<b>不会</b>出现，下面
     *       {@code assertThat(badgeCodesOf(userId)).containsExactly(...)} 立刻变红。</li>
     *   <li>为了把这条依赖钉得更死，本用例还断言 {@code BADGE:SAVING_MASTER} 的 {@code id} <b>大于</b>
     *       三条 {@code SAVING_MONTH} 的 {@code id}：批量插入按 {@code pending} 的顺序发出、{@code id}
     *       自增，故这条断言直接观测「谁先进 {@code pending}」这个组装顺序本身，
     *       而不只是观测它的后果。<b>已实测</b>：把第 ④ 步里 {@code BADGE} 的循环挪到
     *       {@code SAVING_MONTH} 之前（其余一字不改）后重跑本用例，就是这条 id 断言变红
     *       （{@code BADGE:SAVING_MASTER} 的 id 落在三条储蓄月事件之前）；改回即恢复绿。</li>
     * </ol>
     *
     * <p>另外三项：三条 {@code SAVING_MONTH} 的 {@code event_key} 按月份<b>升序</b>落库（键长恒 20，
     * 需求 4.2）、四行的 {@code exp_amount} 全为 0（需求 4.18、4.19），四行的 {@code created_at} 相等
     * （单次结算只读一次时钟）。</p>
     */
    @Test
    void threeSavingMonths_singleSettlementWritesThreeRowsAndSavingMasterBadge() {
        long userId = 810_001L;
        long ledgerId = 910_001L;
        LocalDate today = LocalDate.now();
        List<String> months = lookbackMonths(today);

        // 每个回看月：收入 1000.00、支出 100.00 → 结余 900.00 ≥ 储蓄门槛 200.00（取值远离边界，
        // 边界与舍入由 GrowthSavingMonthEvaluatorTest 覆盖，本用例只关心「同一次结算」这件事）。
        for (String month : months) {
            LocalDate mid = YearMonth.parse(month).atDay(15);
            seedTransaction(userId, ledgerId, "income", "1000.00", mid.atTime(10, 0), today, ref(userId));
            seedTransaction(userId, ledgerId, "expense", "100.00", mid.atTime(11, 0), today, ref(userId));
        }

        SettleOutcome outcome = settlementService.settle(userId, TriggerSource.RECORD);

        assertThat(outcome).isEqualTo(SettleOutcome.SETTLED);
        List<Map<String, Object>> events = eventsOf(userId);

        // 三条 SAVING_MONTH 按月份升序落库，键长恒 20（需求 4.2）。
        List<String> savingKeys = keysOfType(events, GrowthEventType.SAVING_MONTH);
        assertThat(savingKeys)
                .as("3 个回看月各一条 SAVING_MONTH，按月份升序")
                .containsExactlyElementsOf(months.stream().map(m -> "SAVING_MONTH:" + m).toList());
        assertThat(savingKeys).allSatisfy(key -> assertThat(key).hasSize(20));

        // 同一次结算内 BADGE:SAVING_MASTER 也落库——组装顺序的回归锁（需求 2.6）。
        assertThat(badgeCodesOf(events))
                .as("一次结算内解锁的成就恰好是 FIRST_RECORD 与 SAVING_MASTER（第 3 个储蓄月同批解锁）")
                .containsExactly("FIRST_RECORD", "SAVING_MASTER");

        long savingMasterId = idOfKey(events, "BADGE:SAVING_MASTER");
        long maxSavingMonthId = savingKeys.stream().mapToLong(key -> idOfKey(events, key)).max().orElseThrow();
        assertThat(savingMasterId)
                .as("BADGE 排在 SAVING_MONTH 之后组装：其 id 严格大于三条储蓄月事件的 id（任务 4.3）")
                .isGreaterThan(maxSavingMonthId);

        // 两类新事件的 exp_amount 恒为 0，且四行共享同一个 created_at（单次结算只读一次时钟）。
        assertThat(expAmountsOfType(events, GrowthEventType.SAVING_MONTH)).containsOnly(0);
        assertThat(expAmountsOfType(events, GrowthEventType.BADGE)).containsOnly(0);
        Timestamp savingMonthCreatedAt = (Timestamp) rowOfKey(events, savingKeys.get(0)).get("created_at");
        assertThat(rowOfKey(events, "BADGE:SAVING_MASTER").get("created_at")).isEqualTo(savingMonthCreatedAt);
    }

    // ============ 2) 笔数 0 → 1200：五枚 BADGE 的 id 序与展示序号一致（需求 2.6）============

    /**
     * 累计笔数从 0 一跃到 1200 → 一次结算写入 {@code FIRST_RECORD} / {@code RECORD_10} /
     * {@code RECORD_100} / {@code RECORD_500} / {@code RECORD_1000} 五枚 {@code BADGE}
     * （跨门槛不漏发低门槛，需求 2.6），且 {@code id} 的相对大小与展示序号一致（需求 2.6 后半句）。
     *
     * <p>1200 笔全部落在<b>同一个记账日</b>（昨天）：于是 {@code MAX_STREAK} 与 {@code TOTAL_DAYS} 都是 1，
     * 除这五枚之外没有任何成就的条件成立，{@code containsExactly} 因而同时锁住三件事——五枚一枚不漏、
     * 没有多解锁任何一枚、五枚的 {@code id} 升序恰好等于清单序号顺序。</p>
     */
    @Test
    void recordCountJumpsFromZeroTo1200_singleSettlementWritesFiveBadgesInDisplayOrder() {
        long userId = 820_001L;
        long ledgerId = 920_001L;
        LocalDate yesterday = LocalDate.now().minusDays(1);

        seedExpenses(userId, ledgerId, yesterday, 1200, ref(userId));
        assertThat(validRecordCount(userId)).as("笔数确实从 0 跃到 1200").isEqualTo(1200L);

        SettleOutcome outcome = settlementService.settle(userId, TriggerSource.RECORD);

        assertThat(outcome).isEqualTo(SettleOutcome.SETTLED);
        List<String> expected = List.of("FIRST_RECORD", "RECORD_10", "RECORD_100", "RECORD_500", "RECORD_1000");
        List<Map<String, Object>> events = eventsOf(userId);
        assertThat(badgeCodesOf(events))
                .as("五枚 RECORD_COUNT 口径的成就同批解锁，且按 id 升序即展示序号顺序（需求 2.6）")
                .containsExactly(expected.toArray(String[]::new));
        assertDisplayOrder(events, expected);
    }

    // ============ 3) 连续天数 0 → 400：四枚 STREAK_* 一枚不漏 ============

    /**
     * 历史最长连续天数从 0 一跃到 400 → 一次结算内四枚 {@code STREAK_*} 成就一枚不漏（需求 2.6）。
     *
     * <p>连续 400 天各记一笔（记账日历按 {@code created_at}），全部落在追补窗口（1000 天）之内，
     * 故一次结算即补齐 400 个 {@code DAILY_RECORD}、{@code max_streak_days} 落到 400。同批解锁的成就
     * 除四枚 {@code STREAK_*} 之外还有 {@code FIRST_RECORD} / {@code RECORD_10} / {@code RECORD_100}
     * （笔数 400）与 {@code DAYS_100}（累计天数 400），{@code containsExactly} 一并锁住集合与 id 序。</p>
     */
    @Test
    void streakJumpsFromZeroTo400_singleSettlementWritesAllFourStreakBadges() {
        long userId = 830_001L;
        long ledgerId = 930_001L;
        LocalDate yesterday = LocalDate.now().minusDays(1);

        List<Object[]> batch = new ArrayList<>(400);
        for (int back = 0; back < 400; back++) {
            LocalDate day = yesterday.minusDays(back);
            batch.add(txRow(userId, ledgerId, "expense", "1.00", day.atTime(12, 0), day, ref(userId)));
        }
        jdbcTemplate.batchUpdate(INSERT_TX_SQL, batch);

        SettleOutcome outcome = settlementService.settle(userId, TriggerSource.RECORD);

        assertThat(outcome).isEqualTo(SettleOutcome.SETTLED);
        assertThat(profileMaxStreakDays(userId)).as("连续天数确实跃到 400").isEqualTo(400);

        List<String> expected = List.of("FIRST_RECORD", "STREAK_7", "STREAK_30", "STREAK_100", "STREAK_365",
                "RECORD_10", "RECORD_100", "DAYS_100");
        List<Map<String, Object>> events = eventsOf(userId);
        assertThat(badgeCodesOf(events))
                .as("四枚 STREAK_* 一枚不漏，且同批解锁的 id 序即展示序号顺序")
                .containsExactly(expected.toArray(String[]::new));
        assertDisplayOrder(events, expected);
    }

    // ============ 4) 新增读查询恒为 3 条（需求 4.11）============

    /**
     * 本 spec 新增的读查询在单次结算内恒为 <b>3 条</b>，且条数不随账本数（1 → 20）、分类数（1 → 200）、
     * 交易笔数（1 → 10000）增长（需求 4.11）。
     *
     * <p>三条各计一次：储蓄月的「月份 × 类型」分组合计、协作成员数、旅行记账笔数。计数由
     * {@link NewReadQueryInspector} 在 Hibernate 层完成（见类级 Javadoc）；断言分成「逐条各 1 次」与
     * 「合计 3 条」两层，前者能指出是哪一条退化成了 N+1，后者锁住总量。</p>
     */
    @ParameterizedTest(name = "账本 {0} 个 / 分类 {1} 个 / 交易 {2} 笔")
    @CsvSource({"1, 1, 1", "20, 200, 10000"})
    void newlyAddedReadQueries_areExactlyThreePerSettlement_regardlessOfDataScale(
            int ledgerCount, int categoryCount, int txCount) {
        long userId = 840_000L + ledgerCount;
        long collaboratorId = userId + 500L;
        LocalDate yesterday = LocalDate.now().minusDays(1);

        List<Long> ledgerIds = new ArrayList<>(ledgerCount);
        for (int i = 0; i < ledgerCount; i++) {
            long ledgerId = insertLedger(userId, "账本" + i);
            ledgerIds.add(ledgerId);
            // 每个自有账本上挂一个他人的 EDITOR 成员行：协作成员数随账本数增长，但查询条数不许增长。
            insertMember(ledgerId, collaboratorId, LedgerMember.ROLE_EDITOR);
        }
        long firstLedgerId = ledgerIds.get(0);
        // 分类里恰有一个叫「旅行」，其余是同层的普通分类：旅行查询在两种规模下都真的命中行。
        long travelCategoryId = insertCategory(userId, firstLedgerId, "EXPENSE", "旅行", null);
        for (int i = 1; i < categoryCount; i++) {
            insertCategory(userId, firstLedgerId, "EXPENSE", "分类" + i, null);
        }
        seedExpenses(userId, firstLedgerId, yesterday, txCount, travelCategoryId);

        NewReadQueryInspector.reset();
        SettleOutcome outcome = settlementService.settle(userId, TriggerSource.RECORD);

        assertThat(outcome).isEqualTo(SettleOutcome.SETTLED);
        assertThat(NewReadQueryInspector.savingMonthCount())
                .as("储蓄月的月份 × 类型分组合计恒 1 条（不按月循环）；已发出的匹配 SQL=%s",
                        NewReadQueryInspector.matched()).isEqualTo(1);
        assertThat(NewReadQueryInspector.collabCount())
                .as("协作成员数恒 1 条（不按账本循环）；已发出的匹配 SQL=%s",
                        NewReadQueryInspector.matched()).isEqualTo(1);
        assertThat(NewReadQueryInspector.travelCount())
                .as("旅行记账笔数恒 1 条（不按分类或交易循环）；已发出的匹配 SQL=%s",
                        NewReadQueryInspector.matched()).isEqualTo(1);
        assertThat(NewReadQueryInspector.total())
                .as("单次结算新增读查询合计恒 3 条（需求 4.11）").isEqualTo(3);
    }

    // ============ 5) 单次结算写入事件数 ≤ 1026（需求 4.12）============

    /**
     * 单次结算写入的成长事件条数不超过 {@link GrowthSettlementService#MAX_PENDING_EVENTS}（需求 4.12）。
     *
     * <p>构造一次<b>接近上界</b>的结算：1500 个不同记账日各一笔支出（追补窗口 1000 天 → 恰好写满 1000 条
     * {@code DAILY_RECORD}），外加 3 个回看月各一笔收入使三个储蓄月同时成立。于是本次写入
     * 1000 {@code DAILY_RECORD} + 1 {@code FIRST_RECORD} + 2 {@code STREAK} + 3 {@code SAVING_MONTH}
     * + 11 {@code BADGE} = 1017 条，仍在 1026 之内。收入的记账日取该月 15 日（落在 1500 天跨度内、
     * 但在追补窗口之外），故不额外增加 {@code DAILY_RECORD}。</p>
     *
     * <p>11 枚 {@code BADGE}：{@code FIRST_RECORD}、四枚 {@code STREAK_*}（连续 1000 天）、
     * {@code RECORD_10/100/500/1000}（1503 笔）、{@code DAYS_100}（1000 天）、{@code SAVING_MASTER}。</p>
     */
    @Test
    void singleSettlement_writesAtMostMaxPendingEvents() {
        long userId = 850_001L;
        long ledgerId = 950_001L;
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);

        List<Object[]> batch = new ArrayList<>(1500);
        for (int back = 0; back < 1500; back++) {
            LocalDate day = yesterday.minusDays(back);
            batch.add(txRow(userId, ledgerId, "expense", "1.00", day.atTime(12, 0), day, ref(userId)));
        }
        jdbcTemplate.batchUpdate(INSERT_TX_SQL, batch);
        for (String month : lookbackMonths(today)) {
            LocalDate mid = YearMonth.parse(month).atDay(15);
            seedTransaction(userId, ledgerId, "income", "1000.00", mid.atTime(10, 0), mid, ref(userId));
        }

        SettleOutcome outcome = settlementService.settle(userId, TriggerSource.RECORD);

        assertThat(outcome).isEqualTo(SettleOutcome.SETTLED);
        long written = growthEventCount(userId);
        assertThat(countOfType(userId, GrowthEventType.DAILY_RECORD))
                .as("追补窗口 1000 天 → 恰好写满 1000 条 DAILY_RECORD").isEqualTo(1000L);
        assertThat(written)
                .as("1000 DAILY_RECORD + 1 FIRST_RECORD + 2 STREAK + 3 SAVING_MONTH + 11 BADGE")
                .isEqualTo(1017L);
        assertThat(written)
                .as("单次结算写入事件数不超过上界 %d（需求 4.12）", GrowthSettlementService.MAX_PENDING_EVENTS)
                .isLessThanOrEqualTo(GrowthSettlementService.MAX_PENDING_EVENTS);
    }

    // ============ 6) 解锁成就不动经验与等级（需求 1.11）============

    /**
     * 解锁任意数量成就后 {@code exp} 与 {@code level} 与写入前逐项相等（需求 1.11）。
     *
     * <p>两次结算：第一次把带正经验的事件（{@code DAILY_RECORD} + {@code FIRST_RECORD}）全部落地并记下
     * {@code exp} / {@code level}；随后只补充<b>只会解锁成就</b>的事实源——旅行支出 12 笔
     * （{@code TRAVEL_MASTER}）、他人以 {@code EDITOR} 加入自有账本（{@code COLLAB_1}）、
     * 3 个回看月的收支（3 条 {@code SAVING_MONTH} + {@code SAVING_MASTER}）——第二次结算于是新写入
     * 4 枚 {@code BADGE} 与 3 条 {@code SAVING_MONTH}，全部 {@code exp_amount} 为 0，
     * 两个物化列必须一动不动。</p>
     *
     * <p>两处刻意的构造：① 新交易的<b>记账日仍取昨天</b>（{@code created_at}），使追补起点（昨天 + 1 天
     * = 今天）之后无任何记账日，第二次结算不会写入新的 {@code DAILY_RECORD}——否则经验会因日历而变，
     * 本断言就测不到「成就不给经验」这件事；② 也正因为记账日不是今天，
     * {@code last_record_date != 结算日}，60 秒记账节流窗口的<b>两个条件</b>不会同时成立，
     * 第二次结算必定真实执行而非被跳过。</p>
     */
    @Test
    void unlockingAchievements_leavesExpAndLevelIdentical() {
        long userId = 860_001L;
        long collaboratorId = 860_999L;
        LocalDate today = LocalDate.now();
        LocalDate yesterday = today.minusDays(1);
        long ledgerId = insertLedger(userId, "自有账本");

        // 阶段一：昨天 5 笔支出 → DAILY_RECORD(5) + FIRST_RECORD(10)，exp = 15、level = 2。
        seedExpenses(userId, ledgerId, yesterday, 5, ref(userId));
        assertThat(settlementService.settle(userId, TriggerSource.RECORD)).isEqualTo(SettleOutcome.SETTLED);

        long expBefore = profileExp(userId);
        int levelBefore = profileLevel(userId);
        assertThat(expBefore).isEqualTo(15L);
        assertThat(levelBefore).isEqualTo(2);
        assertThat(badgeCodesOf(eventsOf(userId))).containsExactly("FIRST_RECORD");

        // 阶段二：只补充「只会解锁成就」的事实源，记账日一律仍取昨天（不新增记账日）。
        long travelCategoryId = insertCategory(userId, ledgerId, "EXPENSE", "旅行", null);
        seedExpenses(userId, ledgerId, yesterday, 12, travelCategoryId);          // TRAVEL_MASTER（≥10）
        insertMember(ledgerId, collaboratorId, LedgerMember.ROLE_EDITOR);         // COLLAB_1
        for (String month : lookbackMonths(today)) {                              // 3 条 SAVING_MONTH
            LocalDate mid = YearMonth.parse(month).atDay(15);
            seedTransaction(userId, ledgerId, "income", "1000.00", mid.atTime(10, 0), yesterday, ref(userId));
            seedTransaction(userId, ledgerId, "expense", "100.00", mid.atTime(11, 0), yesterday, ref(userId));
        }

        assertThat(settlementService.settle(userId, TriggerSource.RECORD))
                .as("第二次结算真实执行（记账日不是今天，节流条件不成立）").isEqualTo(SettleOutcome.SETTLED);

        List<Map<String, Object>> events = eventsOf(userId);
        assertThat(badgeCodesOf(events))
                .as("新解锁 RECORD_10 / COLLAB_1 / SAVING_MASTER / TRAVEL_MASTER 四枚")
                .containsExactly("FIRST_RECORD", "RECORD_10", "COLLAB_1", "SAVING_MASTER", "TRAVEL_MASTER");
        assertThat(keysOfType(events, GrowthEventType.SAVING_MONTH)).hasSize(3);
        assertThat(expAmountsOfType(events, GrowthEventType.SAVING_MONTH)).containsOnly(0);
        assertThat(expAmountsOfType(events, GrowthEventType.BADGE)).containsOnly(0);
        assertThat(keysOfType(events, GrowthEventType.DAILY_RECORD))
                .as("第二次结算不新增记账日历行").containsExactly("DAILY_RECORD:" + yesterday);

        assertThat(profileExp(userId)).as("解锁成就不改变经验（需求 1.11）").isEqualTo(expBefore);
        assertThat(profileLevel(userId)).as("解锁成就不改变等级（需求 1.11）").isEqualTo(levelBefore);
        assertThat(expSumOf(userId)).as("exp 恒等于事件 exp_amount 之和").isEqualTo(expBefore);
    }

    // ============ 7) 故障注入：整体回滚、记账不受影响、下次自愈（需求 4.14、4.15、4.16）============

    /**
     * 让 {@code countTravelExpenses} 抛异常 → 本次结算的 {@code REQUIRES_NEW} 事务<b>整体回滚</b>：
     * {@code growth_events} / {@code user_growth} / {@code achievement_notices} 三表零部分写入、
     * 触发器记一条 {@code [GROWTH_SETTLE_FAILED]} WARN、记账接口的状态码与响应字段集与判定成功时逐项
     * 相同且不含任何成就字段、随后的一次结算把缺的事件补齐（需求 4.14、4.15、4.16）。
     *
     * <p>本用例走真实 HTTP 的记账接口（真实过滤链 + JWT + {@code afterCommit} 触发的结算），因为
     * 「记账接口的状态码与响应字段集不变」只能在接口层观察。故障点下沉到仓储层（见类级 Javadoc
     * 「故障注入」）：结算服务本身仍是真实 bean、仍被 Spring 的事务代理包裹，
     * 因此「整体回滚」这条断言测的是真实事务语义。</p>
     *
     * <p><b>「三表零行」是比「逐列不变」更强的回滚证据</b>：结算第 ② 步会先用 ODKU 给
     * {@code user_growth} 建档，而故障发生在第 ③ 步——若 {@code REQUIRES_NEW} 的回滚失效（例如有人把它
     * 改成 {@code REQUIRED}，或在 {@code settle} 内部 {@code catch} 掉异常），那行档案就会留在库里，
     * 下面 {@code userGrowthCount(userId)} 立刻非零。</p>
     */
    @Test
    void travelQueryFault_rollsBackWholeSettlement_keepsRecordApiIntact_andHealsOnNextSettlement()
            throws Exception {
        String email = "ach_settle_fault@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        long accountId = createAccount(token, "现金", "CASH", "1000.00");
        long categoryId = createCategory(token, "EXPENSE", "餐饮");
        LocalDate today = LocalDate.now();

        Logger triggerLogger = (Logger) LoggerFactory.getLogger(GrowthSettlementTrigger.class);
        ListAppender<ILoggingEvent> logAppender = new ListAppender<>();
        logAppender.start();
        triggerLogger.addAppender(logAppender);
        try {
            TRAVEL_QUERY_FAULT.set(true);

            ResponseEntity<String> faulted = postRecord(token, "50.00", accountId, categoryId);

            // 记账接口：状态码与字段集与判定成功时相同，且响应不含任何成就 / 播报 / 徽章字段（需求 4.15）。
            assertThat(faulted.getStatusCode()).as("结算失败不改变记账状态码（需求 4.15）")
                    .isEqualTo(HttpStatus.CREATED);
            Map<String, Object> faultedBody = parse(faulted.getBody());
            assertThat(faultedBody.keySet()).as("结算失败不改变记账响应字段集（需求 4.15）")
                    .containsExactlyInAnyOrderElementsOf(RECORD_KEYS);
            for (String marker : RECORD_FORBIDDEN_MARKERS) {
                assertThat(faulted.getBody()).as("记账响应不含成就字段：" + marker).doesNotContain(marker);
            }

            // 记账本身已提交，成长/成就三表零部分写入（需求 4.14）。
            assertThat(validRecordCount(userId)).as("记账结果已提交，不受结算失败影响").isEqualTo(1L);
            assertThat(growthEventCount(userId)).as("growth_events 零部分写入").isZero();
            assertThat(userGrowthCount(userId)).as("user_growth 连建档行都被回滚").isZero();
            assertThat(noticeCount(userId)).as("achievement_notices 零部分写入").isZero();

            // 一条 [GROWTH_SETTLE_FAILED] WARN，含用户 id、不含金额 / 邮箱（需求 4.14）。
            List<ILoggingEvent> warns = logAppender.list.stream()
                    .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.WARN)
                    .toList();
            assertThat(warns).as("结算失败记一条 WARN").isNotEmpty();
            assertThat(warns).anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("[GROWTH_SETTLE_FAILED]");
                assertThat(event.getFormattedMessage()).contains("userId=" + userId);
                assertThat(event.getFormattedMessage()).doesNotContain(email).doesNotContain("50.00");
            });

            // 故障解除后再次触发结算：补齐上次未写入的事件（需求 4.16）。
            TRAVEL_QUERY_FAULT.set(false);
            ResponseEntity<String> healed = postRecord(token, "60.00", accountId, categoryId);

            assertThat(healed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(parse(healed.getBody()).keySet())
                    .as("判定成功与判定失败两次记账的响应字段集逐项相同（需求 4.15）")
                    .containsExactlyInAnyOrderElementsOf(faultedBody.keySet());
            assertThat(eventKeysOf(userId))
                    .as("下一次结算补齐上次未写入的事件（需求 4.16）")
                    .contains("DAILY_RECORD:" + today, "FIRST_RECORD", "BADGE:FIRST_RECORD");
            assertThat(profileExp(userId)).as("补齐后经验按 DAILY_RECORD(5) + FIRST_RECORD(10) 计")
                    .isEqualTo(15L);
        } finally {
            triggerLogger.detachAppender(logAppender);
            logAppender.stop();
        }
    }

    // ---------------------------------- 断言辅助 ----------------------------------

    /**
     * 断言一批 {@code BADGE} 事件的 {@code id} 升序与它们在成就清单里的展示序号升序一致（需求 2.6 后半句）。
     *
     * <p>两侧各自独立取值：一侧读库里的 {@code id}，另一侧读 {@link #CATALOG_CODES}（清单表格的独立副本），
     * 因此断言不会因为「实现与被测共用同一个顺序来源」而恒真。</p>
     */
    private void assertDisplayOrder(List<Map<String, Object>> events, List<String> codesInIdOrder) {
        List<Integer> displayIndexes = codesInIdOrder.stream().map(CATALOG_CODES::indexOf).toList();
        assertThat(displayIndexes).as("展示序号严格升序").isSorted();
        List<Long> ids = codesInIdOrder.stream().map(code -> idOfKey(events, "BADGE:" + code)).toList();
        assertThat(ids).as("同批 BADGE 事件的 id 严格升序，与展示序号顺序一致").isSorted();
    }

    // ---------------------------------- 库读取辅助 ----------------------------------

    /** 该用户的全部成长事件，按 {@code id} 升序（{@code id} 序即写入序）。 */
    private List<Map<String, Object>> eventsOf(long userId) {
        return jdbcTemplate.queryForList(
                "SELECT id, event_type, event_key, exp_amount, created_at "
                        + "FROM growth_events WHERE user_id = ? ORDER BY id", userId);
    }

    private List<String> eventKeysOf(long userId) {
        return eventsOf(userId).stream().map(row -> (String) row.get("event_key")).toList();
    }

    private List<String> keysOfType(List<Map<String, Object>> events, String eventType) {
        return events.stream()
                .filter(row -> eventType.equals(row.get("event_type")))
                .map(row -> (String) row.get("event_key"))
                .toList();
    }

    /** 已解锁成就编码，按事件 {@code id} 升序（即写入顺序）。 */
    private List<String> badgeCodesOf(List<Map<String, Object>> events) {
        return keysOfType(events, GrowthEventType.BADGE).stream()
                .map(key -> key.substring("BADGE:".length()))
                .toList();
    }

    private List<Integer> expAmountsOfType(List<Map<String, Object>> events, String eventType) {
        return events.stream()
                .filter(row -> eventType.equals(row.get("event_type")))
                .map(row -> ((Number) row.get("exp_amount")).intValue())
                .toList();
    }

    private Map<String, Object> rowOfKey(List<Map<String, Object>> events, String eventKey) {
        return events.stream()
                .filter(row -> eventKey.equals(row.get("event_key")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("事件键不存在: " + eventKey));
    }

    private long idOfKey(List<Map<String, Object>> events, String eventKey) {
        return ((Number) rowOfKey(events, eventKey).get("id")).longValue();
    }

    private long growthEventCount(long userId) {
        return count("SELECT COUNT(*) FROM growth_events WHERE user_id = ?", userId);
    }

    private long countOfType(long userId, String eventType) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ? AND event_type = ?",
                Long.class, userId, eventType);
        return n == null ? 0L : n;
    }

    private long userGrowthCount(long userId) {
        return count("SELECT COUNT(*) FROM user_growth WHERE user_id = ?", userId);
    }

    private long noticeCount(long userId) {
        return count("SELECT COUNT(*) FROM achievement_notices WHERE user_id = ?", userId);
    }

    private long validRecordCount(long userId) {
        return count("SELECT COUNT(*) FROM transactions WHERE created_by = ? AND deleted_at IS NULL "
                + "AND type IN ('expense','income') AND ledger_id IS NOT NULL", userId);
    }

    private long profileExp(long userId) {
        Long exp = jdbcTemplate.queryForObject("SELECT exp FROM user_growth WHERE user_id = ?",
                Long.class, userId);
        return exp == null ? 0L : exp;
    }

    private int profileLevel(long userId) {
        Integer level = jdbcTemplate.queryForObject("SELECT level FROM user_growth WHERE user_id = ?",
                Integer.class, userId);
        return level == null ? 0 : level;
    }

    private int profileMaxStreakDays(long userId) {
        Integer days = jdbcTemplate.queryForObject(
                "SELECT max_streak_days FROM user_growth WHERE user_id = ?", Integer.class, userId);
        return days == null ? 0 : days;
    }

    private long expSumOf(long userId) {
        return count("SELECT COALESCE(SUM(exp_amount), 0) FROM growth_events WHERE user_id = ?", userId);
    }

    private long count(String sql, Object... args) {
        Long n = jdbcTemplate.queryForObject(sql, Long.class, args);
        return n == null ? 0L : n;
    }

    // ---------------------------------- 数据播种辅助 ----------------------------------

    /** 结算日所属月的前 3 / 2 / 1 个自然月，<b>升序</b>的 {@code YYYY-MM}（与需求 4.1 的回看窗口一致）。 */
    private static List<String> lookbackMonths(LocalDate settleDate) {
        YearMonth settleMonth = YearMonth.from(settleDate);
        List<String> months = new ArrayList<>(3);
        for (int back = 3; back >= 1; back--) {
            months.add(settleMonth.minusMonths(back).toString());
        }
        return months;
    }

    /**
     * 「绝不可能是真实主键」且按用户隔离的 {@code account_id} / {@code category_id} 占位取值。
     *
     * <p>与真实分类主键撞号会让「旅行」判定误命中：本类多个用例在同一内存库里跑，分类表里确有真实行。</p>
     */
    private static long ref(long userId) {
        return 900_000_000L + userId;
    }

    /** 一条「有效记账交易」的参数行：记账日由 {@code recordDay}（即 {@code created_at}）决定。 */
    private static Object[] txRow(long userId, long ledgerId, String type, String amount,
                                 LocalDateTime occurredAt, LocalDate recordDay, long categoryId) {
        Timestamp createdAt = Timestamp.valueOf(recordDay.atTime(12, 0));
        return new Object[] {userId, ledgerId, userId, type, new BigDecimal(amount),
                ref(userId), categoryId, Timestamp.valueOf(occurredAt), createdAt, createdAt};
    }

    private void seedTransaction(long userId, long ledgerId, String type, String amount,
                                 LocalDateTime occurredAt, LocalDate recordDay, long categoryId) {
        jdbcTemplate.update(INSERT_TX_SQL,
                txRow(userId, ledgerId, type, amount, occurredAt, recordDay, categoryId));
    }

    /** 同一记账日上批量直插 {@code count} 笔 {@code 1.00} 支出（走 batch，万笔量级仍在秒级）。 */
    private void seedExpenses(long userId, long ledgerId, LocalDate day, int count, long categoryId) {
        List<Object[]> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            batch.add(txRow(userId, ledgerId, "expense", "1.00", day.atTime(12, 0), day, categoryId));
        }
        jdbcTemplate.batchUpdate(INSERT_TX_SQL, batch);
    }

    private long insertLedger(long userId, String name) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbcTemplate.update("INSERT INTO ledgers "
                        + "(user_id, name, type, sort_order, is_default, created_at, updated_at) "
                        + "VALUES (?, ?, 'PERSONAL', 0, FALSE, ?, ?)",
                userId, name, Timestamp.valueOf(now), Timestamp.valueOf(now));
        return count("SELECT MAX(id) FROM ledgers WHERE user_id = ? AND name = ?", userId, name);
    }

    private long insertCategory(long userId, long ledgerId, String kind, String name, Long parentId) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbcTemplate.update("INSERT INTO categories "
                        + "(user_id, ledger_id, parent_id, kind, name, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                userId, ledgerId, parentId, kind, name, Timestamp.valueOf(now), Timestamp.valueOf(now));
        return count("SELECT MAX(id) FROM categories WHERE user_id = ? AND name = ?", userId, name);
    }

    private void insertMember(long ledgerId, long userId, String role) {
        jdbcTemplate.update("INSERT INTO ledger_members (ledger_id, user_id, role, created_at) "
                        + "VALUES (?, ?, ?, ?)",
                ledgerId, userId, role, Timestamp.valueOf(LocalDateTime.now().withNano(0)));
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private HttpHeaders authJson(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parse(String rawJson) throws Exception {
        return objectMapper.readValue(rawJson, Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    /** 记账：取<b>原始 JSON 文本</b>，因为「不含成就字段」只能按文本比对。 */
    private ResponseEntity<String> postRecord(String token, String amount, long accountId, long categoryId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "expense");
        payload.put("amount", amount);
        payload.put("accountId", accountId);
        payload.put("categoryId", categoryId);
        payload.put("occurredAt", LocalDateTime.now().withNano(0).toString());
        payload.put("note", "记一笔");
        return rest.exchange(url("/api/transactions"), HttpMethod.POST,
                new HttpEntity<>(payload, authJson(token)), String.class);
    }

    private long createAccount(String token, String name, String type, String initialBalance) {
        ResponseEntity<Map> resp = rest.exchange(url("/api/accounts"), HttpMethod.POST,
                new HttpEntity<>(Map.of("name", name, "type", type,
                        "initialBalance", initialBalance, "sortOrder", 0), authJson(token)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) body(resp).get("id")).longValue();
    }

    private long createCategory(String token, String kind, String name) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("kind", kind);
        payload.put("name", name);
        payload.put("parentId", null);
        ResponseEntity<Map> resp = rest.exchange(url("/api/categories"), HttpMethod.POST,
                new HttpEntity<>(payload, authJson(token)), Map.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) body(resp).get("id")).longValue();
    }

    // ---------------------------------- 账号辅助 ----------------------------------

    private String registerAndLogin(String email) {
        verificationCodeRepository.deleteByEmail(email);
        ResponseEntity<Void> send = rest.postForEntity(url("/api/auth/send-code"),
                Map.of("email", email, "purpose", "LOGIN"), Void.class);
        assertThat(send.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String code = verificationCodeRepository
                .findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(email, EmailCodePurpose.LOGIN)
                .orElseThrow(() -> new AssertionError("验证码未生成: " + email))
                .getCode();
        ResponseEntity<Map> login = rest.postForEntity(url("/api/auth/email-login"),
                Map.of("email", email, "code", code), Map.class);
        assertThat(login.getStatusCode()).isEqualTo(HttpStatus.OK);
        String token = (String) body(login).get("token");
        assertThat(token).isNotBlank();
        return token;
    }

    private long userIdOf(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new AssertionError("用户未建立: " + email))
                .getId();
    }

    // ---------------------------------- 测试基础设施 ----------------------------------

    /**
     * Hibernate {@link StatementInspector}：只对本 spec 新增的三条读查询计数，各按其<b>独有片段</b>识别。
     *
     * <ul>
     *   <li>储蓄月：{@code MONTH(occurred_at)}——只有那条按「年 × 月 × 类型」分组的合计用到它
     *       （预算侧按 {@code ledger_id} 分组、累计侧按 {@code type} 分组，都不含此片段）。</li>
     *   <li>旅行：{@code 旅行} 字面量——全库只有这一条查询把汉字写进 SQL。</li>
     *   <li>协作：同时出现 {@code ledger_members} 与 {@code ledgers} 两张表名——只有那条 JPQL
     *       会把成员表与账本表连在一起（{@code ledger_members} 不含 {@code ledgers} 子串）。</li>
     * </ul>
     *
     * <p>由 Hibernate 依类名反射实例化，故必须是 {@code public static} 且带公有无参构造；
     * 计数器为静态，供测试线程读取。{@link #matched()} 把已命中的 SQL 原文带进失败信息，
     * 免得「条数不对」时还要重跑一遍才知道多出来的是哪一条。</p>
     */
    public static final class NewReadQueryInspector implements StatementInspector {

        private static final AtomicInteger SAVING_MONTH_QUERIES = new AtomicInteger();
        private static final AtomicInteger COLLAB_QUERIES = new AtomicInteger();
        private static final AtomicInteger TRAVEL_QUERIES = new AtomicInteger();
        private static final List<String> MATCHED = new CopyOnWriteArrayList<>();

        public NewReadQueryInspector() {
            // Hibernate 反射实例化所需的公有无参构造。
        }

        @Override
        public String inspect(String sql) {
            if (sql == null) {
                return sql;
            }
            String lower = sql.toLowerCase(Locale.ROOT);
            if (lower.contains("month(occurred_at)")) {
                SAVING_MONTH_QUERIES.incrementAndGet();
                MATCHED.add(sql);
            } else if (sql.contains("旅行")) {
                TRAVEL_QUERIES.incrementAndGet();
                MATCHED.add(sql);
            } else if (lower.contains("ledger_members") && lower.contains("ledgers")) {
                COLLAB_QUERIES.incrementAndGet();
                MATCHED.add(sql);
            }
            return sql;
        }

        static void reset() {
            SAVING_MONTH_QUERIES.set(0);
            COLLAB_QUERIES.set(0);
            TRAVEL_QUERIES.set(0);
            MATCHED.clear();
        }

        static int savingMonthCount() {
            return SAVING_MONTH_QUERIES.get();
        }

        static int collabCount() {
            return COLLAB_QUERIES.get();
        }

        static int travelCount() {
            return TRAVEL_QUERIES.get();
        }

        static int total() {
            return savingMonthCount() + collabCount() + travelCount();
        }

        static List<String> matched() {
            return List.copyOf(MATCHED);
        }
    }

    /**
     * 可注入故障的 {@code TransactionRepository}：{@code @Primary} 的 JDK 动态代理，默认全部方法透明
     * 委托给真实仓储，仅当 {@link #TRAVEL_QUERY_FAULT} 置位时让 {@code countTravelExpenses} 抛异常。
     *
     * <p>故障刻意下沉到仓储层而不是对 {@link GrowthSettlementService} 做 Mockito spy：对带
     * {@code @Transactional} 的类做 spy 会绕过 Spring 的事务代理、令 {@code REQUIRES_NEW} 失效，
     * 而「结算整体回滚」正是这组断言要验的东西。经代理注入故障后，结算服务仍是真实 bean、
     * 仍走真实事务代理，异常从第 ③ 步穿出、事务回滚，与生产路径逐条一致。</p>
     */
    @TestConfiguration
    static class FaultConfig {
        @Bean
        @Primary
        TransactionRepository probeTransactionRepository(
                @Qualifier("transactionRepository") TransactionRepository real) {
            return (TransactionRepository) Proxy.newProxyInstance(
                    TransactionRepository.class.getClassLoader(),
                    new Class<?>[] {TransactionRepository.class},
                    (proxy, method, args) -> {
                        if ("countTravelExpenses".equals(method.getName()) && TRAVEL_QUERY_FAULT.get()) {
                            throw new IllegalStateException("注入：旅行记账笔数查询失败");
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    });
        }
    }
}
