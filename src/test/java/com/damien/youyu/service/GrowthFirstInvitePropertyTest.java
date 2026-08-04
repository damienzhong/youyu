package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestPropertySource;

import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * 首次邀请经验的<b>一次性与只读性</b>回归锁（<b>Property 12：首次邀请经验的一次性与只读性</b>）。
 *
 * <p>对<i>任意</i>（首次结算前的在册邀请数、增长到的峰值在册数 [1,100]、增长过程中的结算次数、
 * 邀请人是否已有成长档案）组合，锁住 {@link GrowthSettlementService#settle} 在首次邀请侧的全部承诺：</p>
 *
 * <ul>
 *   <li><b>需求 6.1（一次授予）</b>：当 {@code status = REGISTERED} 的邀请关系条数 ≥1 且此前无
 *       {@code FIRST_INVITE} 事件时，首次结算写入<b>恰好 1 条</b> {@code FIRST_INVITE}
 *       （{@code event_key = 'FIRST_INVITE'}、{@code exp_amount = 80}）。</li>
 *   <li><b>需求 6.2（终身一次、不叠加）</b>：在册数从 1 增长到 100 期间，无论中间结算多少次，
 *       {@code FIRST_INVITE} 事件条数恒为 1、其经验合计恒为 80，绝不因邀请更多人而增加经验。</li>
 *   <li><b>需求 6.3（只增不减）</b>：把全部被邀请人置 {@code INVALID} 使在册数回落到 0 后再结算，
 *       已写入的 {@code FIRST_INVITE} 事件与那 80 经验保持不变、绝不撤销。</li>
 *   <li><b>需求 6.4（只读 {@code invite_relations}）</b>：<b>每一次结算</b>前后对
 *       {@code invite_relations} 表做逐行快照，断言行数与<b>全部列取值逐行相等</b>——结算只对该表执行
 *       读取语句，任何插入/更新/删除都会让快照比对立刻变红。</li>
 *   <li><b>需求 6.5（延迟到邀请人自己的结算）</b>：仅把邀请关系写入 {@code invite_relations}
 *       （模拟被邀请人注册那一刻）而<b>不</b>结算邀请人时，邀请人的 {@code growth_events} 与
 *       {@code user_growth} 均无任何行——邀请经验不在被邀请人的注册请求内写入，而是延迟到邀请人自己
 *       首次结算时补发。</li>
 * </ul>
 *
 * <h2>驱动方式：全栈 {@code @SpringBootTest} + 真实提交，不用测试级事务</h2>
 * <p>{@code settle} 带 {@code @Transactional(REQUIRES_NEW)}，只有让它真正<b>提交</b>才能在库里观察到
 * 写入。故本测试不加测试级 {@code @Transactional}（那会在方法结束时回滚、掩盖真实写入），而是直接调用
 * {@code settle} 并从库读回断言；清理不靠回滚，{@link #resetState()} 每次迭代前显式清库、用全局自增序号
 * {@link #SEQ} 保证 {@code inviterId} 与每个 {@code inviteeId} 全局唯一（双重隔离）。时钟用一个
 * {@code @Primary} {@link MutableClock}，固定在 {@code Asia/Shanghai}。</p>
 *
 * <p>邀请人无任何交易，故记账日历为空、{@code last_record_date} 恒为 NULL，{@code RECORD} 侧节流的
 * 「{@code last_record_date == 结算日}」条件永不成立，连续多次 {@code RECORD} 结算都会真正执行、不被跳过。
 * 被邀请人无需真实 {@code users} 行：{@code invite_relations} 与成长两表均无外键，结算只按 {@code inviter_id}
 * 计数在册关系。</p>
 *
 * <p>jqwik 属性方法不经 {@code SpringExtension}，依赖注入由 {@link TestContextManager} 在
 * {@link BeforeTry} 手工完成（静态上下文缓存复用，多次迭代只加载一次上下文）。用独立命名的内存库避免污染
 * 兄弟切片测试。</p>
 *
 * <p>Feature: growth-level-system, Property 12: 首次邀请经验的一次性与只读性</p>
 *
 * <p>Validates: Requirements 6.1, 6.2, 6.3, 6.4, 6.5</p>
 */
@SpringBootTest
@TestPropertySource(properties =
        "spring.datasource.url=jdbc:h2:mem:youyu-growth-prop12-it;DB_CLOSE_DELAY=-1;MODE=MySQL")
@Import(GrowthFirstInvitePropertyTest.ClockConfig.class)
class GrowthFirstInvitePropertyTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Instant BASE =
            LocalDateTime.of(2025, 6, 15, 8, 0).atZone(ZONE).toInstant();
    private static final MutableClock CLOCK = new MutableClock(BASE, ZONE);

    private static final String FIRST_INVITE_KEY = "FIRST_INVITE";

    /** 全局自增序号：保证每次迭代 inviterId / 每个 inviteeId 全局唯一（清理不靠回滚）。 */
    private static final AtomicLong SEQ = new AtomicLong(12_000_000L);

    @Autowired
    private GrowthSettlementService settlementService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeTry
    void resetState() throws Exception {
        new TestContextManager(GrowthFirstInvitePropertyTest.class).prepareTestInstance(this);
        CLOCK.reset(BASE);
        // 结算真实提交，清理不能靠回滚：每次迭代前硬删相关表（成长两表与 invite_relations 无外键）。
        jdbcTemplate.update("DELETE FROM growth_events");
        jdbcTemplate.update("DELETE FROM user_growth");
        jdbcTemplate.update("DELETE FROM invite_relations");
    }

    // ---------------- Property 12 ----------------

    /**
     * 对任意（增长峰值在册数、增长过程中的结算次数、邀请人是否已有成长档案）：{@code FIRST_INVITE} 一经
     * 写入即恒为 1 条 80 经验，增长到 100 不叠加、被邀请人全部注销回落到 0 不撤销；每一次结算都只读
     * {@code invite_relations}（逐行快照不变）；未结算前的邀请关系写入不为邀请人创建任何成长行。
     */
    @Property(tries = 25)
    void property12_firstInviteAwardedOnceReadOnlyAndIrrevocable(
            @ForAll @IntRange(min = 1, max = 100) int growTo,
            @ForAll @IntRange(min = 0, max = 4) int extraSettles,
            @ForAll boolean hasProfileBefore) {

        long inviterId = SEQ.getAndIncrement();

        // ── 阶段 0：可选地先建档（0 在册 → 结算创建档案但不写 FIRST_INVITE）──────────────────
        if (hasProfileBefore) {
            List<Map<String, Object>> before = snapshotInvites();
            settlementService.settle(inviterId, TriggerSource.RECORD);
            assertInvitesUnchanged(before);
            assertThat(firstInviteCount(inviterId))
                    .as("0 在册邀请时结算不得写入 FIRST_INVITE（需求 6.1）")
                    .isZero();
            assertThat(userGrowthRowCount(inviterId))
                    .as("hasProfileBefore=true 时结算应创建成长档案")
                    .isEqualTo(1);
        }

        // ── 需求 6.5：先写入 1 条在册邀请关系（模拟被邀请人注册那一刻），但<b>不</b>结算邀请人 ──────
        int registered = 0;
        addRegisteredInvitee(inviterId);
        registered++;
        if (!hasProfileBefore) {
            // 未建档时，未结算前邀请人的成长两表必须都为空：邀请经验延迟到邀请人自己的结算，
            // 不在被邀请人的注册请求内写入、也不创建成长档案（需求 6.5）。
            assertThat(growthEventRowCount(inviterId))
                    .as("未结算前不得为邀请人写入任何成长事件（需求 6.5）")
                    .isZero();
            assertThat(userGrowthRowCount(inviterId))
                    .as("未结算前不得为邀请人创建成长档案（需求 6.5）")
                    .isZero();
        }

        // ── 需求 6.1：在册数 0→1 后的首次结算写入恰好 1 条 FIRST_INVITE（80 经验）────────────────
        settleAndAssertInvitesUnchanged(inviterId);
        assertExactlyOneFirstInvite(inviterId);

        // ── 需求 6.2：在册数 1 → growTo 增长期间，穿插结算，FIRST_INVITE 恒为 1、经验恒为 80 ────────
        for (int round = 1; round <= extraSettles; round++) {
            int target = 1 + (int) Math.round((growTo - 1) * (round / (double) extraSettles));
            while (registered < target) {
                addRegisteredInvitee(inviterId);
                registered++;
            }
            settleAndAssertInvitesUnchanged(inviterId);
            assertExactlyOneFirstInvite(inviterId);
        }
        // 确保确实增长到了峰值 growTo。
        while (registered < growTo) {
            addRegisteredInvitee(inviterId);
            registered++;
        }
        assertThat(registeredCount(inviterId))
                .as("在册邀请数应增长到峰值 growTo")
                .isEqualTo(growTo);
        settleAndAssertInvitesUnchanged(inviterId);
        assertExactlyOneFirstInvite(inviterId);

        // ── 需求 6.3：把全部被邀请人置 INVALID，在册数回落到 0，再结算，FIRST_INVITE 不撤销 ─────────
        jdbcTemplate.update(
                "UPDATE invite_relations SET status = 'INVALID', updated_at = ? WHERE inviter_id = ?",
                LocalDateTime.now(CLOCK), inviterId);
        assertThat(registeredCount(inviterId))
                .as("全部被邀请人注销后在册数应回落到 0")
                .isZero();
        settleAndAssertInvitesUnchanged(inviterId);
        assertExactlyOneFirstInvite(inviterId);
    }

    // ---------------- 断言工具 ----------------

    /** 结算一次，并断言 {@code invite_relations} 在结算前后逐行不变（需求 6.4：只读该表）。 */
    private void settleAndAssertInvitesUnchanged(long inviterId) {
        List<Map<String, Object>> before = snapshotInvites();
        settlementService.settle(inviterId, TriggerSource.RECORD);
        assertInvitesUnchanged(before);
    }

    private void assertInvitesUnchanged(List<Map<String, Object>> before) {
        assertThat(snapshotInvites())
                .as("结算只对 invite_relations 执行读取语句：行数与全部列取值逐行不变（需求 6.4）")
                .isEqualTo(before);
    }

    /** 断言该邀请人恰有 1 条 FIRST_INVITE 事件、经验恒为 80（需求 6.1、6.2、6.3：一次性、不叠加、不撤销）。 */
    private void assertExactlyOneFirstInvite(long inviterId) {
        List<Map<String, Object>> events = jdbcTemplate.queryForList(
                "SELECT event_key, exp_amount FROM growth_events "
                        + "WHERE user_id = ? AND event_type = 'FIRST_INVITE'",
                inviterId);
        assertThat(events)
                .as("FIRST_INVITE 恒为终身一次性：恰好 1 条（需求 6.2）")
                .hasSize(1);
        assertThat((String) events.get(0).get("event_key"))
                .as("FIRST_INVITE 的 event_key 恒为 'FIRST_INVITE'（需求 6.1）")
                .isEqualTo(FIRST_INVITE_KEY);
        assertThat(((Number) events.get(0).get("exp_amount")).longValue())
                .as("FIRST_INVITE 的经验恒为 80，绝不叠加（需求 6.1、6.2）")
                .isEqualTo(80L);
    }

    // ---------------- 事实源播种与读回 ----------------

    /** 写入一条 {@code status = REGISTERED} 的邀请关系（inviteeId 全局唯一、无需真实 users 行）。 */
    private void addRegisteredInvitee(long inviterId) {
        long inviteeId = SEQ.getAndIncrement();
        LocalDateTime now = LocalDateTime.now(CLOCK);
        jdbcTemplate.update(
                "INSERT INTO invite_relations "
                        + "(inviter_id, invitee_id, register_time, status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'REGISTERED', ?, ?)",
                inviterId, inviteeId, now, now, now);
    }

    /** {@code invite_relations} 全部行的逐行快照（全部列，按 invite_id 升序），用于逐行比对不变。 */
    private List<Map<String, Object>> snapshotInvites() {
        return jdbcTemplate.queryForList("SELECT * FROM invite_relations ORDER BY invite_id ASC");
    }

    private long registeredCount(long inviterId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_relations WHERE inviter_id = ? AND status = 'REGISTERED'",
                Long.class, inviterId);
        return n == null ? 0L : n;
    }

    private long firstInviteCount(long inviterId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ? AND event_type = 'FIRST_INVITE'",
                Long.class, inviterId);
        return n == null ? 0L : n;
    }

    private long growthEventRowCount(long inviterId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM growth_events WHERE user_id = ?", Long.class, inviterId);
        return n == null ? 0L : n;
    }

    private long userGrowthRowCount(long inviterId) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_growth WHERE user_id = ?", Long.class, inviterId);
        return n == null ? 0L : n;
    }

    // ---------------- 基础设施 ----------------

    /** 提供 {@code @Primary} 可推进时钟（固定 Asia/Shanghai）。 */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return CLOCK;
        }
    }

    /** 可推进、可归位的时钟。 */
    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void reset(Instant to) {
            this.instant = to;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId z) {
            return new MutableClock(instant, z);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
