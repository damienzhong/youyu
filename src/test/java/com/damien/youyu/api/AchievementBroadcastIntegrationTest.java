package com.damien.youyu.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.GrowthEvent;
import com.damien.youyu.domain.GrowthEventType;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.repository.GrowthEventRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.UserRepository;
import com.damien.youyu.repository.VerificationCodeRepository;
import com.damien.youyu.service.BadgeDef;
import com.damien.youyu.service.GrowthBadgeCatalog;

/**
 * 成就<b>播报流转</b>集成测试（任务 6.3，需求 5.4、5.5、5.7～5.11、5.16、5.17、5.18）。
 *
 * <p>全栈 {@code @SpringBootTest}(RANDOM_PORT)：经真实 HTTP、真实 Spring Security 过滤链、
 * 真实 Jackson 与 H2（{@code MODE=MySQL}）持久化层，覆盖播报的五条流转路径：</p>
 *
 * <ol>
 *   <li><b>解锁 → 播报 → 确认 → 空</b>（需求 5.2、5.4、5.7、5.16）：真实结算解锁两枚成就 →
 *       待播报非空且按成就事件 id 升序 → ack → 待播报为空且 {@code total} 为 0。</li>
 *   <li><b>多于 10 项的截断</b>（需求 5.4、5.5）：本次只返回 id 最小的 10 项、{@code total} 取
 *       <b>截断前</b>的全部条数（不是 {@code items.size()}），ack 后剩余项在后续请求里按 id 升序回来。</li>
 *   <li><b>查询不推进游标</b>（需求 5.17、5.14）：期间无新解锁、未 ack 时连续两次待播报请求返回
 *       相同的项、相同顺序、相同 {@code total}，且 {@code achievement_notices} 的行数与四列取值不变。</li>
 *   <li><b>ack 幂等</b>（需求 5.8、5.11、5.13）：无游标行时首次 ack 建行且 {@code created_at} 与
 *       {@code updated_at} 相等；随后传入 ≤ 当前游标的取值一律不改行、不报错、返回当前游标取值。</li>
 *   <li><b>8 个并发 ack</b>（需求 5.9、5.10）：终态等于全部合法取值与原值的最大者，行数终态为 1。</li>
 * </ol>
 *
 * <h2>H2 上可以断言什么，什么只能进 MySQL 人工清单</h2>
 *
 * <p>游标推进是<b>一条</b> {@code INSERT ... ON DUPLICATE KEY UPDATE} 语句，其中
 * {@code updated_at} 的 {@code CASE} 必须写在 {@code last_notified_event_id = GREATEST(...)}
 * <b>之前</b>——这条依赖来自 MySQL「ODKU 赋值列表按书写顺序从左到右求值、右侧读到左侧的新值」的语义。
 * 任务 1.5 已在 MySQL {@code 8.0.46} 上正反两面实测过（设计写法：{@code updated_at} 随推进而推进；
 * 两句调换的反例：{@code updated_at} 永久停在首次写入时刻）。<b>本类跑在 H2 上，因此刻意不断言那条
 * 与求值顺序相关的行为。</b>下面这条线划得很清：</p>
 *
 * <ul>
 *   <li><b>本类断言（与存储引擎的求值顺序无关，H2 与 MySQL 上同真）</b>：
 *     <ul>
 *       <li>任意 ack 序列后的 {@code last_notified_event_id} 等于「全部合法取值 ∪ 原值」的最大者
 *           ——这是 {@code GREATEST} 的语义，与两句赋值的先后无关；</li>
 *       <li>传入 ≤ 当前游标时四列<b>全部</b>不变（含 {@code updated_at}）：此时
 *           {@code GREATEST(旧, v≤旧) == 旧}，故 {@code ? > last_notified_event_id} 无论读到旧值还是
 *           读到「新值」都恒为假——<b>两种求值顺序下结论相同</b>，因此这条在 H2 上也是合法断言；</li>
 *       <li>首次 ack 建行时 {@code created_at == updated_at}：走的是纯 {@code INSERT} 分支（
 *           {@code VALUES (?, ?, ?, ?)} 里两列取同一个 {@code now}），完全不经过 ODKU 赋值列表；</li>
 *       <li>只读路径（待播报查询）不改 {@code achievement_notices} 的行数与四列取值：该路径一条写语句
 *           都不发；</li>
 *       <li>该用户在 {@code achievement_notices} 中的行数终态恒为 1：主键约束。</li>
 *     </ul>
 *   </li>
 *   <li><b>本类不断言（只属于 MySQL 人工清单，见任务 1.5 与 design.md「5. 播报游标」的实测结论块）</b>：
 *     <ul>
 *       <li>「真实推进时 {@code updated_at} 同时被推进到本次请求时刻」——它是否成立<b>只</b>取决于
 *           ODKU 赋值列表的求值顺序。H2 对该顺序不做承诺，在这里断言它要么锁死一个 H2 特有的实现细节，
 *           要么以一个与生产语句无关的理由挂掉；</li>
 *       <li>把两句顺序调换后 {@code updated_at} 不再推进的<b>反例</b>——同上，在 MySQL 上实测过，
 *           在 H2 上无从判定。</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>为免「什么都没变」这类断言在语句根本没执行成功时也能通过（{@code ack} 的数据库异常会被
 * {@code cursorDegraded} 就地降级成「返回当前游标、不报错」，需求 5.19），本类每条路径都<b>另外</b>
 * 断言一次真实推进确实把 {@code last_notified_event_id} 改了——那证明这条 ODKU 语句在 H2 上真的执行了，
 * 幂等断言因此不是空断言。</p>
 *
 * <h2>待播报事件的造法</h2>
 *
 * <p>第 1 条路径走<b>完整真实链路</b>：落有效记账交易 → {@code GET /api/achievements} 触发同步结算 →
 * 结算写入 {@code BADGE} 行。第 2～5 条路径需要 11 项以上待播报，而真实解锁 11 枚成就要同时凑够 1000 笔
 * 交易、365 天连续记账、3 个预算达成月、3 个储蓄月、协作成员与 10 笔旅行支出——那是任务 4.4 与 8.2 的
 * 职责。播报流转只读 {@code growth_events} 的 {@code BADGE} 行，因此这几条路径直接落<b>与结算写出的行
 * 逐列同形</b>的真实行（{@code event_type = 'BADGE'}、{@code event_key = 'BADGE:<清单编码>'}、
 * {@code exp_amount = 0}），编码取自 {@link GrowthBadgeCatalog#badges()}，按清单序号升序插入，故自增
 * {@code id} 的相对大小与展示序号一致（需求 2.6 后半句）——不是 mock，也不是伪造形状的数据。</p>
 *
 * <p>使用<b>独立命名</b>的内存库，避免污染其它共享内存库的切片测试。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:youyu-achievement-broadcast-it;DB_CLOSE_DELAY=-1;MODE=MySQL",
        // 本测试要建多个账号，全部请求同源自 127.0.0.1，故放宽发码 IP 限额（发码防刷在别处覆盖）。
        "app.auth.email-code.ip-per-minute=1000",
        "app.auth.email-code.ip-per-day=100000"
})
class AchievementBroadcastIntegrationTest {

    /** 单次待播报响应的项数上限（需求 5.4）。 */
    private static final int PENDING_PAGE_SIZE = 10;

    /** 并发 ack 的请求数（需求 5.10）。 */
    private static final int CONCURRENCY = 8;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private VerificationCodeRepository verificationCodeRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private GrowthEventRepository growthEventRepository;

    @Autowired
    private GrowthBadgeCatalog badgeCatalog;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    // ============ 1) 解锁 → 待播报非空且升序 → ack → 待播报为空且 total 0 ============

    /**
     * 完整真实链路（需求 5.2、5.4、5.7、5.16）：10 笔有效记账 → 结算解锁 {@code FIRST_RECORD} 与
     * {@code RECORD_10} → 待播报 2 项且按成就事件 id 升序 → ack 到最大 id → 待播报空列表 + {@code total} 0。
     *
     * <p>ack 之后额外从库读回游标：{@code last_notified_event_id} 确实变成了那个最大 id。这一条同时是
     * 「ODKU 语句在 H2 上真的执行了」的证据（见类级 Javadoc 末段）。</p>
     */
    @Test
    void unlockThenPending_ascendingByEventId_thenAckEmptiesPending() {
        String email = "ach_bc_flow@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        seedValidRecords(userId, 93_001L, 10);

        // 写入型 GET：触发一次同步结算，写入两枚 BADGE 行。
        assertThat(get("/api/achievements", token).getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> pending = body(get("/api/achievements/pending", token));
        List<Map<String, Object>> items = itemsOf(pending);
        assertThat(items).hasSize(2);
        assertThat(totalOf(pending)).isEqualTo(2L);
        assertThat(codesOf(items)).containsExactly("FIRST_RECORD", "RECORD_10");
        assertAscendingByEventId(items);

        long maxEventId = eventIdOf(items.get(items.size() - 1));

        // ack 到本次已播报的最大成就事件 id（需求 5.7）。
        Map<String, Object> ack = body(postAck(token, String.valueOf(maxEventId)));
        assertThat(cursorOf(ack)).isEqualTo(maxEventId);
        // 真实推进确实落库：游标行存在且取值为 maxEventId（证明 ODKU 语句在 H2 上真的执行了）。
        assertThat(noticeRowCount(userId)).isEqualTo(1);
        assertThat(cursorInDb(userId)).isEqualTo(maxEventId);

        // 播报完成、期间无新解锁 → 空列表 + total 0，且不报错（需求 5.16）。
        Map<String, Object> afterAck = body(get("/api/achievements/pending", token));
        assertThat(itemsOf(afterAck)).isEmpty();
        assertThat(totalOf(afterAck)).isZero();
    }

    // ============ 2) 多于 10 项：返回最小的 10 项、total 为截断前条数、剩余项后续返回 ============

    /**
     * 待播报 16 项（需求 5.4、5.5）：本次响应返回成就事件 id <b>最小的 10 项</b>、且按 id 升序；
     * {@code total} 取<b>截断前</b>的 16（不是本次返回的 10）；ack 到第 10 项后剩余 6 项在后续请求里
     * 按 id 升序返回、{@code total} 变为 6；再 ack 到最后一项则待播报清空。
     *
     * <p>{@code total} 是截断前条数这一条必须单独断言：把它写成 {@code items.size()} 时本用例的其它
     * 断言全都还能通过，而客户端会把「还有 16 项」显示成「还有 10 项」。</p>
     */
    @Test
    void moreThanTenPending_returnsTenSmallestIds_withPreTruncationTotal_andRemainderLater() {
        String email = "ach_bc_truncate@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        List<Long> seededIds = seedAllCatalogBadges(userId);
        int seeded = seededIds.size();
        assertThat(seeded).as("清单 16 枚，足以造出多于 10 项的待播报").isGreaterThan(PENDING_PAGE_SIZE);

        // 第一批：id 最小的 10 项，total 为截断前的 16（需求 5.5）。
        Map<String, Object> first = body(get("/api/achievements/pending", token));
        List<Map<String, Object>> firstItems = itemsOf(first);
        assertThat(firstItems).hasSize(PENDING_PAGE_SIZE);
        assertThat(totalOf(first)).isEqualTo(seeded);
        assertThat(eventIdsOf(firstItems)).containsExactlyElementsOf(seededIds.subList(0, PENDING_PAGE_SIZE));
        assertAscendingByEventId(firstItems);

        // 推进到本批最大 id。
        long firstBatchMaxId = seededIds.get(PENDING_PAGE_SIZE - 1);
        assertThat(cursorOf(body(postAck(token, String.valueOf(firstBatchMaxId))))).isEqualTo(firstBatchMaxId);
        assertThat(cursorInDb(userId)).isEqualTo(firstBatchMaxId);

        // 第二批：剩余 6 项按 id 升序回来，total 同步降为 6（需求 5.5 后半句）。
        Map<String, Object> second = body(get("/api/achievements/pending", token));
        List<Map<String, Object>> secondItems = itemsOf(second);
        assertThat(secondItems).hasSize(seeded - PENDING_PAGE_SIZE);
        assertThat(totalOf(second)).isEqualTo((long) (seeded - PENDING_PAGE_SIZE));
        assertThat(eventIdsOf(secondItems))
                .containsExactlyElementsOf(seededIds.subList(PENDING_PAGE_SIZE, seeded));
        assertAscendingByEventId(secondItems);
        // 两批不重不漏：第二批的最小 id 严格大于第一批的最大 id。
        assertThat(eventIdOf(secondItems.get(0))).isGreaterThan(firstBatchMaxId);

        // 推进到最后一项 → 清空（需求 5.16）。
        long lastId = seededIds.get(seeded - 1);
        assertThat(cursorOf(body(postAck(token, String.valueOf(lastId))))).isEqualTo(lastId);
        Map<String, Object> third = body(get("/api/achievements/pending", token));
        assertThat(itemsOf(third)).isEmpty();
        assertThat(totalOf(third)).isZero();
    }

    // ============ 3) 查询不推进游标：连续两次请求逐项相同、游标表逐列不变 ============

    /**
     * 待播报查询是纯只读（需求 5.17、5.14）：期间无新解锁且未 ack 时，连续两次请求返回相同的项、
     * 相同顺序与相同的 {@code total}；{@code achievement_notices} 的行数与<b>四列取值</b>在两次查询
     * 前后完全不变。
     *
     * <p>先 ack 一次把游标行造出来，才能逐列比对——没有行时「列取值不变」是句空话。四列取值的比对
     * 直接对整行 {@code Map} 做相等断言（含 {@code created_at} 与 {@code updated_at}），只读路径一条
     * 写语句都不发，因此这条断言在 H2 与 MySQL 上同真。</p>
     */
    @Test
    void repeatedPendingQueries_doNotAdvanceCursor_norTouchNoticeRow() {
        String email = "ach_bc_readonly@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        List<Long> seededIds = seedAllCatalogBadges(userId);

        // 造出游标行：推进到第 3 枚成就（其后仍有 13 项待播报，两次查询各返回 10 项）。
        long cursor = seededIds.get(2);
        assertThat(cursorOf(body(postAck(token, String.valueOf(cursor))))).isEqualTo(cursor);
        Map<String, Object> rowBefore = noticeRow(userId);
        long noticeCountBefore = noticeRowCount(userId);
        assertThat(noticeCountBefore).isEqualTo(1L);

        Map<String, Object> first = body(get("/api/achievements/pending", token));
        Map<String, Object> second = body(get("/api/achievements/pending", token));

        List<Map<String, Object>> firstItems = itemsOf(first);
        List<Map<String, Object>> secondItems = itemsOf(second);
        assertThat(firstItems).hasSize(PENDING_PAGE_SIZE);
        // 相同的项、相同的顺序（逐项整体相等，不只是 id 相等）、相同的 total。
        assertThat(secondItems).containsExactlyElementsOf(firstItems);
        assertThat(eventIdsOf(secondItems)).containsExactlyElementsOf(eventIdsOf(firstItems));
        assertThat(totalOf(second)).isEqualTo(totalOf(first));
        assertThat(totalOf(first)).isEqualTo((long) (seededIds.size() - 3));

        // 游标既没被查询推进，整行四列也一字未动。
        assertThat(noticeRowCount(userId)).isEqualTo(noticeCountBefore);
        assertThat(noticeRow(userId)).isEqualTo(rowBefore);
        assertThat(cursorInDb(userId)).isEqualTo(cursor);
    }

    // ============ 4) ack 幂等：首次建行两时刻相等；≤ 当前游标不改行、不报错、返回当前值 ============

    /**
     * ack 幂等（需求 5.8、5.11、5.13）：
     *
     * <ol>
     *   <li>无游标行时首次 ack <b>创建</b>该行，且 {@code created_at == updated_at}（同一服务端时刻）
     *       ——走的是纯 {@code INSERT} 分支，不经 ODKU 赋值列表，故 H2 上可断言；</li>
     *   <li>随后以「等于当前游标」「小于当前游标」「下界 0」三个取值各 ack 一次：均返回 200、
     *       响应里是<b>当前</b>游标取值（不是入参）、且整行<b>四列</b>逐列不变。
     *       {@code updated_at} 也在比对之内：传入 ≤ 当前游标时
     *       {@code GREATEST(旧, v) == 旧}，故 {@code ? > last_notified_event_id} 在两种求值顺序下都为假
     *       ——这条不依赖 MySQL 的 ODKU 求值顺序，H2 上同真（见类级 Javadoc）。</li>
     * </ol>
     *
     * <p>最后再做一次真实推进并断言游标确实变大：证明前面那几条「一字未动」不是因为语句压根没跑通
     * （数据库异常会被 {@code cursorDegraded} 降级成「返回当前游标、不报错」）。</p>
     */
    @Test
    void ackIsIdempotent_firstCreateHasEqualTimestamps_andNonAdvancingAckChangesNothing() {
        String email = "ach_bc_idempotent@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        List<Long> seededIds = seedAllCatalogBadges(userId);

        // 首次 ack 之前没有游标行（需求 5.3：按 0 计算待播报）。
        assertThat(noticeRowCount(userId)).isZero();
        assertThat(totalOf(body(get("/api/achievements/pending", token)))).isEqualTo(seededIds.size());

        // ① 首次 ack 建行，created_at 与 updated_at 为同一时刻（需求 5.11）。
        long cursor = seededIds.get(4);
        assertThat(cursorOf(body(postAck(token, String.valueOf(cursor))))).isEqualTo(cursor);
        assertThat(noticeRowCount(userId)).isEqualTo(1L);
        Map<String, Object> created = noticeRow(userId);
        assertThat(created.get("CREATED_AT"))
                .as("首次建行的 created_at 与 updated_at 取同一服务端时刻")
                .isEqualTo(created.get("UPDATED_AT"));
        assertThat(cursorInDb(userId)).isEqualTo(cursor);

        // ② 三种「不推进」的取值：不改行、不报错、返回当前游标取值（需求 5.8、5.13）。
        for (String repeated : List.of(String.valueOf(cursor), String.valueOf(seededIds.get(1)), "0")) {
            ResponseEntity<Map> response = postAck(token, repeated);
            assertThat(response.getStatusCode()).as("重复确认 " + repeated + " 不报错").isEqualTo(HttpStatus.OK);
            assertThat(cursorOf(body(response))).as("重复确认 " + repeated + " 返回当前游标取值")
                    .isEqualTo(cursor);
            assertThat(noticeRowCount(userId)).isEqualTo(1L);
            assertThat(noticeRow(userId)).as("重复确认 " + repeated + " 后整行四列不变").isEqualTo(created);
        }

        // ③ 再做一次真实推进：游标确实变大 → 上面的「四列不变」不是空断言。
        long advanced = seededIds.get(seededIds.size() - 1);
        assertThat(cursorOf(body(postAck(token, String.valueOf(advanced))))).isEqualTo(advanced);
        assertThat(cursorInDb(userId)).isEqualTo(advanced);
        assertThat(noticeRowCount(userId)).isEqualTo(1L);
    }

    // ============ 5) 8 个并发 ack：终态为全部合法取值与原值的最大者、行数终态为 1 ============

    /**
     * 8 个并发 ack（需求 5.9、5.10）：终态 {@code last_notified_event_id} 等于「这 8 个 {@code lastEventId}
     * 取值 ∪ 请求前游标取值」的最大者，该用户在 {@code achievement_notices} 中的行数终态为 1。
     *
     * <p>8 个取值刻意打乱下发顺序并混入小于当前游标的取值与下界 0，因此「终态取最大者」不可能靠
     * 「最后一个请求赢」凑巧成立——只有 {@code GREATEST} 才能同时满足单调不减与终态取最大值。
     * 这两条都是 {@code GREATEST} 的语义，与 ODKU 赋值列表的求值顺序无关，故在 H2 上是合法断言。</p>
     *
     * <p>请求前先 ack 一次把游标行造出来，使 8 个并发请求全部走 {@code ON DUPLICATE KEY UPDATE} 分支
     * ——需求 5.10 说的正是「终态等于这些请求的取值与<b>请求前游标取值</b>中的最大值」。
     * 「无游标行时多个并发请求同时抢着建行」属于唯一键冲突下的插入竞态，其行为取决于存储引擎，
     * 归 MySQL 人工清单与任务 8.4 的属性测试，本类不在 H2 上代它下结论。</p>
     */
    @Test
    void eightConcurrentAcks_settleOnMaxOfLegalValues_andKeepExactlyOneRow() throws Exception {
        String email = "ach_bc_concurrent@example.com";
        String token = registerAndLogin(email);
        long userId = userIdOf(email);
        List<Long> seededIds = seedAllCatalogBadges(userId);

        // 请求前游标：第 6 枚成就的事件 id。
        long original = seededIds.get(5);
        assertThat(cursorOf(body(postAck(token, String.valueOf(original))))).isEqualTo(original);

        // 8 个合法取值（全部 ≤ maxBadgeEventId）：含小于原值的、等于原值的、下界 0 与最大值。
        List<Long> values = List.of(
                seededIds.get(9),
                0L,
                seededIds.get(2),
                seededIds.get(seededIds.size() - 1),
                original,
                seededIds.get(11),
                seededIds.get(0),
                seededIds.get(7));
        assertThat(values).hasSize(CONCURRENCY);

        long expected = Math.max(original, values.stream().mapToLong(Long::longValue).max().orElseThrow());

        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            List<Callable<Long>> calls = new ArrayList<>(CONCURRENCY);
            for (Long value : values) {
                calls.add(() -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    ResponseEntity<Map> response = postAck(token, String.valueOf(value));
                    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                    return cursorOf(body(response));
                });
            }
            List<Future<Long>> futures = new ArrayList<>(CONCURRENCY);
            for (Callable<Long> call : calls) {
                futures.add(pool.submit(call));
            }
            startGate.countDown();
            for (Future<Long> future : futures) {
                long returned = future.get(30, TimeUnit.SECONDS);
                // 每个响应都落在 [原值, 终态] 内：单调不减（需求 5.9）。
                assertThat(returned).isBetween(original, expected);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(cursorInDb(userId)).as("终态等于全部合法取值与原值的最大者").isEqualTo(expected);
        assertThat(noticeRowCount(userId)).as("行数终态为 1").isEqualTo(1L);
    }

    // ---------------------------------- 断言辅助 ----------------------------------

    /** 断言待播报项按成就事件 id 严格升序（需求 5.4：先解锁的先播报）。 */
    private void assertAscendingByEventId(List<Map<String, Object>> items) {
        long previous = 0L;
        for (Map<String, Object> item : items) {
            long eventId = eventIdOf(item);
            assertThat(eventId).as("待播报项按成就事件 id 升序").isGreaterThan(previous);
            previous = eventId;
        }
    }

    // ---------------------------------- 游标表读取 ----------------------------------

    /** 直接读 {@code achievement_notices} 整行（四列），用于逐列比对。 */
    private Map<String, Object> noticeRow(long userId) {
        return jdbcTemplate.queryForMap(
                "SELECT user_id, last_notified_event_id, created_at, updated_at "
                        + "FROM achievement_notices WHERE user_id = ?", userId);
    }

    private long noticeRowCount(long userId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM achievement_notices WHERE user_id = ?", Long.class, userId);
        return count == null ? 0L : count;
    }

    private long cursorInDb(long userId) {
        Long cursor = jdbcTemplate.queryForObject(
                "SELECT last_notified_event_id FROM achievement_notices WHERE user_id = ?",
                Long.class, userId);
        return cursor == null ? 0L : cursor;
    }

    // ---------------------------------- 响应解析辅助 ----------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<Map> response) {
        return (Map<String, Object>) response.getBody();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> itemsOf(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("items");
    }

    private long totalOf(Map<String, Object> body) {
        return ((Number) body.get("total")).longValue();
    }

    private long cursorOf(Map<String, Object> ackBody) {
        return ((Number) ackBody.get("lastNotifiedEventId")).longValue();
    }

    private long eventIdOf(Map<String, Object> item) {
        return ((Number) item.get("eventId")).longValue();
    }

    private List<Long> eventIdsOf(List<Map<String, Object>> items) {
        return items.stream().map(this::eventIdOf).toList();
    }

    private List<String> codesOf(List<Map<String, Object>> items) {
        return items.stream().map(item -> (String) item.get("code")).toList();
    }

    // ---------------------------------- 请求辅助 ----------------------------------

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private ResponseEntity<Map> get(String path, String token) {
        return rest.exchange(url(path), HttpMethod.GET, new HttpEntity<>(bearer(token)), Map.class);
    }

    private ResponseEntity<Map> postAck(String token, String rawLastEventId) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange(url("/api/achievements/notices/ack"), HttpMethod.POST,
                new HttpEntity<>(Map.of("lastEventId", rawLastEventId), headers), Map.class);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    // ---------------------------------- 数据准备辅助 ----------------------------------

    /**
     * 按清单序号升序落全部 16 枚成就的 {@code BADGE} 行，返回其自增 id（升序）。
     *
     * <p>写出的行与结算写出的行逐列同形：{@code event_type = 'BADGE'}、
     * {@code event_key = 'BADGE:<编码>'}、{@code exp_amount = 0}，编码取自清单常量。
     * 按序号升序插入使自增 id 的相对大小与展示序号一致，播报顺序随之确定（需求 2.6 后半句）。</p>
     */
    private List<Long> seedAllCatalogBadges(long userId) {
        LocalDateTime now = LocalDateTime.now();
        List<Long> ids = new ArrayList<>();
        int index = 0;
        for (BadgeDef def : badgeCatalog.badges()) {
            GrowthEvent event = new GrowthEvent();
            event.setUserId(userId);
            event.setEventType(GrowthEventType.BADGE);
            event.setEventKey(GrowthBadgeCatalog.eventKeyOf(def.code()));
            event.setExpAmount(0);
            event.setCreatedAt(now.plusSeconds(index++));
            ids.add(growthEventRepository.saveAndFlush(event).getId());
        }
        // 自增 id 随插入顺序递增：后续「id 最小的 10 项」断言依赖这一点。
        assertThat(ids).isSorted();
        return ids;
    }

    /**
     * 落 {@code count} 笔「有效记账交易」（{@code created_by} = 用户、{@code deleted_at} 为 NULL、
     * {@code type = expense}、{@code ledger_id} 非 NULL），记账日均为当天。直接经仓储落库，
     * 不重复覆盖记账链路——本类验的是播报流转。
     */
    private void seedValidRecords(long userId, long ledgerId, int count) {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < count; i++) {
            Transaction tx = new Transaction();
            tx.setUserId(userId);
            tx.setLedgerId(ledgerId);
            tx.setCreatedBy(userId);
            tx.setType(TransactionType.EXPENSE);
            tx.setAmount(new BigDecimal("12.34"));
            tx.setAccountId(ledgerId);
            tx.setCategoryId(ledgerId);
            tx.setOccurredAt(now);
            tx.setCreatedAt(now);
            tx.setUpdatedAt(now);
            transactionRepository.save(tx);
        }
    }

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
}
