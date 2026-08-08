package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestContextManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.domain.Plan;
import com.damien.youyu.domain.Role;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.InviteRelationRepository;
import com.damien.youyu.repository.UserRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.Example;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tuple;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * 关系行的取值不变式（Property 7）。
 *
 * <p>两条互补的断言：</p>
 * <ul>
 *   <li><b>需求 6.7</b>：{@code invite_relations} 中每一行的 {@code inviter_id} 与
 *       {@code invitee_id} 取值不相等，且任何插入或更新语句都不会使这两列相等——自邀请求在
 *       {@link UnboundReason#SELF_INVITE} 处提前返回（一条语句都不发），唯一的更新路径
 *       {@link InviteRelationRepository#markInvalidByInviteeId} 只碰 {@code status} 与
 *       {@code updated_at}。</li>
 *   <li><b>需求 6.5</b>：同一 {@code inviter_id} 可以有任意多行，且「为该 inviter 插入第 n+1 行」
 *       的成功与已有行数 n 无关（代码里没有任何按行数拒绝的分支，见下）。</li>
 * </ul>
 *
 * <h2>测试层级选择：为什么必须是真实持久化（{@code @DataJpaTest} + H2）</h2>
 * <p>本属性断言的是<b>落库取值</b>：一行的两个 id 是否相等、同一 inviter 名下能否不断追加行，
 * 只有在真实表上累积多行之后才有意义。把 {@link InviteBindingService} 的那条 JDBC 插入或
 * {@code uk_invite_relations_invitee} 唯一索引换成测试替身，等于把被测载荷删掉，属性会永真。
 * 故走 {@code @DataJpaTest} + H2（{@code MODE=MySQL}，表由
 * {@link com.damien.youyu.domain.InviteRelation} 生成），只额外导入被测服务与它依赖的邀请码组件。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，{@code SpringExtension} 因此不生效，依赖注入改由
 * {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（上下文由 Spring 静态上下文缓存复用，
 * 200 次迭代只加载一次）。也因此没有测试事务回滚：<b>这正是本属性需要的</b>——各次迭代写入的行一直
 * 留在同一张表里，「同一 inviter 名下的行数」与「全表无自指行」的断言范围随迭代不断变大。
 * 不变式扫描以本类第一次迭代前的 {@code max(invite_id)} 为下界，只覆盖本类写入的行，
 * 不受共享 H2 库里其它测试类遗留数据的影响。</p>
 *
 * <h2>生成维度</h2>
 * <ul>
 *   <li>操作序列长度 1–8，元素取自四种操作：真实注册并绑定（每步 1–5 人）、自邀尝试、
 *       批量预置同一 inviter 名下的历史行（1 / 7 / 25 / 150 行，加权）、把某行置 {@code INVALID}。</li>
 *   <li>邀请人池 1–3 人，配合按序号编码的邀请码（跨迭代不重复）；邀请码原始取值另做大小写/空白变形，
 *       覆盖规整后仍应命中同一邀请人。</li>
 *   <li>预置历史行的 {@code invitee_id} 刻意取<b>悬空 id</b>（该列无外键，见需求 9.6），
 *       既省掉上万次建号，也顺带覆盖「悬空 id 不影响插入」。</li>
 * </ul>
 *
 * <h2>10000 行这个数字为什么单独一个例子，而不是每次迭代都跑</h2>
 * <p>需求 6.5 点名「已有 10000 行时仍允许插入」。每次迭代都铺 10000 行是 200 万行写入，
 * 换来的信息量却极小：被测路径里<b>不存在任何按行数判定的分支</b>——{@code bindOnRegister} 只做
 * 「规整 → 建号判定 → 格式 → 查邀请人 → 自邀 → 一条 INSERT」，行数既不参与判定也不参与 SQL。
 * 因此属性里把「同一 inviter 多行」压到几十至几百行的实用规模（足以覆盖累积追加、复合索引与
 * 唯一索引的真实行为），10000 这个具体数字由 {@link #example_tenThousandExistingRowsDoNotBlockInsert()}
 * 单跑一次覆盖（历史行走 JDBC 批量插入，秒级完成）。真正的规模化风险是 MySQL 上的索引与执行计划，
 * 那属于任务 1.4 的真库验证清单，不是 H2 能回答的问题。</p>
 *
 * <p>Feature: invite-system, Property 7: 关系行的取值不变式</p>
 *
 * <p>Validates: Requirements 6.5, 6.7</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ InviteBindingService.class, InviteCodeGenerator.class })
class InviteRelationValueInvariantPropertyTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2025, 7, 1, 9, 0, 0);

    /** 同一个 H2 库跨迭代复用：用序号保证 email / 邀请码全局唯一。 */
    private static final AtomicLong SEQ = new AtomicLong();

    /**
     * 邀请码编码基数：本类专用的高位区间，避免与共享 H2 库里其它测试类（同样按序号编码邀请码）
     * 撞上 {@code uk_users_invite_code}。字母表 32 进制 8 位的上限是 32^8 ≈ 1.1e12。
     */
    private static final long CODE_BASE = 500_000_000_000L;

    /** 预置历史行用的悬空 {@code invitee_id} 起点（该列无外键，见需求 9.6）。 */
    private static final AtomicLong DANGLING_ID = new AtomicLong(800_000_000L);

    /** 不变式扫描的下界：本类第一次迭代前的 {@code max(invite_id)}，用于隔离其它测试类的遗留行。 */
    private static final AtomicLong SCAN_FLOOR = new AtomicLong(-1L);

    private static final String INSERT_RELATION_SQL =
            "INSERT INTO invite_relations "
                    + "(inviter_id, invitee_id, register_time, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";
    private static final String SELECT_ROW_SQL =
            "SELECT invite_id, inviter_id, invitee_id, register_time, status, created_at, updated_at "
                    + "FROM invite_relations WHERE invitee_id = ?";

    @Autowired
    private InviteBindingService bindingService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private InviteRelationRepository relationRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeTry
    void injectSpringBeans() throws Exception {
        new TestContextManager(InviteRelationValueInvariantPropertyTest.class).prepareTestInstance(this);
        // 默认传播行为 REQUIRED：为 bindOnRegister 的 MANDATORY 提供外层（登录）物理事务。
        tx = new TransactionTemplate(transactionManager);
        SCAN_FLOOR.compareAndSet(-1L, maxInviteId());
    }

    // ---------------- 生成器 ----------------

    enum Kind {
        /** 真实注册并绑定：新建 users 行 + bindOnRegister，同一 inviter 名下追加若干行。 */
        INVITE_NEW,
        /** 自邀尝试：新用户携带自己的邀请码，必须在 SELF_INVITE 处提前返回、一条语句都不发。 */
        SELF_INVITE,
        /** 批量预置同一 inviter 名下的历史行（悬空 invitee_id），把行数推高。 */
        SEED_HISTORY,
        /** 把某行置 INVALID：唯一的更新路径，只允许改动 status 与 updated_at。 */
        MARK_INVALID
    }

    /**
     * 一步操作。
     *
     * @param selector 邀请人 / 目标行的选择子（取模到池内）
     * @param count    {@link Kind#INVITE_NEW} 每步注册的人数
     * @param bulk     {@link Kind#SEED_HISTORY} 每步预置的历史行数
     * @param mangle   邀请码原始取值的变形方式
     */
    record Step(Kind kind, int selector, int count, int bulk, int mangle) {
    }

    @Provide
    Arbitrary<List<Step>> operationSequences() {
        Arbitrary<Step> step = Combinators.combine(
                        Arbitraries.frequency(
                                Tuple.of(5, Kind.INVITE_NEW),
                                Tuple.of(2, Kind.SELF_INVITE),
                                Tuple.of(2, Kind.SEED_HISTORY),
                                Tuple.of(2, Kind.MARK_INVALID)),
                        Arbitraries.integers().between(0, 7),
                        Arbitraries.integers().between(1, 5),
                        // 加权：多数步骤只加几行，少数步骤一次把行数推到 150。
                        Arbitraries.frequency(
                                Tuple.of(4, 1), Tuple.of(3, 7), Tuple.of(2, 25), Tuple.of(1, 150)),
                        Arbitraries.integers().between(0, 4))
                .as(Step::new);
        return step.list().ofMinSize(1).ofMaxSize(8);
    }

    // ---------------- Property 7 ----------------

    /**
     * Feature: invite-system, Property 7: 关系行的取值不变式
     *
     * <p>对任意由「注册并绑定 / 自邀尝试 / 批量预置历史行 / 置 {@code INVALID}」构成的操作序列，
     * 在<b>每一步之后</b>：</p>
     * <ul>
     *   <li>本类写入的全部行中不存在 {@code inviter_id = invitee_id} 的行（需求 6.7）；</li>
     *   <li>自邀请求以 {@code SELF_INVITE} 返回，且 {@code invite_relations} 行数与全部列取值不变
     *       ——没有任何「使两列相等」的插入被发出（需求 6.7）；</li>
     *   <li>置 {@code INVALID} 只改动 {@code status} 与 {@code updated_at}，
     *       {@code inviter_id}/{@code invitee_id} 原样保留、依然不相等（需求 6.7 的更新侧）；</li>
     *   <li>同一 {@code inviter_id} 名下已有 n 行时，为其插入第 n+1 行仍然成功：{@code bindOnRegister}
     *       返回已绑定，{@link InviteRelationRepository#countByInviterId} 恰好 +1，且新行的
     *       {@code inviter_id} 等于该邀请人、{@code invitee_id} 等于本次新建用户（需求 6.5）。</li>
     * </ul>
     *
     * <p>Validates: Requirements 6.5, 6.7</p>
     */
    @Property(tries = 25)
    void property7_relationRowValueInvariants(
            @ForAll("operationSequences") List<Step> steps,
            @ForAll @IntRange(min = 1, max = 3) int inviterPoolSize) {

        List<Inviter> inviters = new ArrayList<>();
        for (int i = 0; i < inviterPoolSize; i++) {
            inviters.add(createInviter());
        }
        // 本次迭代建立的邀请关系（invitee_id），供 MARK_INVALID 选取目标行。
        List<Long> boundInviteeIds = new ArrayList<>();

        assertNoSelfRelation("邀请人池建立后");

        for (Step step : steps) {
            switch (step.kind()) {
                case INVITE_NEW -> inviteNewUsers(inviters, step, boundInviteeIds);
                case SELF_INVITE -> attemptSelfInvite(step);
                case SEED_HISTORY -> seedHistoryRows(inviters, step);
                case MARK_INVALID -> markInvalid(boundInviteeIds, step);
            }
            assertNoSelfRelation("操作 " + step.kind() + " 之后");
        }
    }

    // ---------------- 操作 ----------------

    /**
     * 需求 6.5：同一 inviter 名下已有多少行都不影响下一行插入成功。每插一行都逐一比对
     * {@code countByInviterId} 的增量与新行的两列取值。
     */
    private void inviteNewUsers(List<Inviter> inviters, Step step, List<Long> boundInviteeIds) {
        Inviter inviter = pick(inviters, step.selector());
        for (int i = 0; i < step.count(); i++) {
            long before = relationRepository.countByInviterId(inviter.id());
            Registered registered = register(inviter.code(), step.mangle());

            assertThat(registered.result().bound())
                    .as("已有 %d 行时插入第 %d 行仍应成功（需求 6.5）", before, before + 1)
                    .isTrue();
            assertThat(registered.result().reason()).isNull();
            assertThat(relationRepository.countByInviterId(inviter.id()))
                    .as("该 inviter 名下行数恰好 +1").isEqualTo(before + 1);

            Map<String, Object> row = rowOf(registered.userId());
            assertThat(row.get("INVITER_ID")).isEqualTo(inviter.id());
            assertThat(row.get("INVITEE_ID")).isEqualTo(registered.userId());
            assertThat(row.get("INVITER_ID"))
                    .as("需求 6.7：新行的两列取值不得相等").isNotEqualTo(row.get("INVITEE_ID"));

            boundInviteeIds.add(registered.userId());
        }
    }

    /**
     * 需求 6.7 的插入侧：自邀在判定链里提前返回，对 {@code invite_relations} 一条语句都不发，
     * 因此行数与全部列取值不变，更不可能出现 {@code inviter_id = invitee_id} 的行。
     */
    private void attemptSelfInvite(Step step) {
        long countBefore = countRelations();
        long seq = SEQ.incrementAndGet();
        LocalDateTime now = BASE.plusSeconds(seq);
        String ownCode = encode(CODE_BASE + seq);

        Registered registered = tx.execute(s -> {
            User self = persistUser(seq, "self", ownCode, now);
            // 携带自己的邀请码（含大小写/空白变形：规整后仍指向自己）。
            InviteBindResult result =
                    bindingService.bindOnRegister(self, true, mangle(ownCode, step.mangle()), now);
            return new Registered(self.getId(), result);
        });

        assertThat(registered).isNotNull();
        assertThat(registered.result().bound()).isFalse();
        assertThat(registered.result().reason()).isEqualTo(UnboundReason.SELF_INVITE);
        assertThat(countRelations()).as("自邀不得写入任何行").isEqualTo(countBefore);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_relations WHERE invitee_id = ?",
                Long.class, registered.userId())).isZero();
    }

    /** 把某 inviter 名下的行数批量推高：{@code invitee_id} 取悬空 id，省掉建号开销。 */
    private void seedHistoryRows(List<Inviter> inviters, Step step) {
        Inviter inviter = pick(inviters, step.selector());
        long before = relationRepository.countByInviterId(inviter.id());
        seedDanglingRows(inviter.id(), step.bulk());
        assertThat(relationRepository.countByInviterId(inviter.id()))
                .as("预置 %d 行后行数恰好 +%d", step.bulk(), step.bulk())
                .isEqualTo(before + step.bulk());
    }

    /** 唯一的更新路径：只允许改动 {@code status} 与 {@code updated_at}（需求 6.7 的更新侧）。 */
    private void markInvalid(List<Long> boundInviteeIds, Step step) {
        Long inviteeId = pick(boundInviteeIds, step.selector());
        if (inviteeId == null) {
            return;
        }
        Map<String, Object> before = rowOf(inviteeId);
        LocalDateTime now = BASE.plusSeconds(SEQ.incrementAndGet());

        Integer affected = tx.execute(s -> relationRepository.markInvalidByInviteeId(inviteeId, now));
        assertThat(affected).as("唯一索引保证影响行数 ≤ 1").isLessThanOrEqualTo(1);

        Map<String, Object> after = rowOf(inviteeId);
        assertThat(after.get("INVITE_ID")).isEqualTo(before.get("INVITE_ID"));
        assertThat(after.get("INVITER_ID")).isEqualTo(before.get("INVITER_ID"));
        assertThat(after.get("INVITEE_ID")).isEqualTo(before.get("INVITEE_ID"));
        assertThat(after.get("REGISTER_TIME")).isEqualTo(before.get("REGISTER_TIME"));
        assertThat(after.get("CREATED_AT")).isEqualTo(before.get("CREATED_AT"));
        assertThat(after.get("STATUS")).isEqualTo(InviteStatus.INVALID.name());
        assertThat(after.get("INVITER_ID"))
                .as("更新后两列取值依然不相等").isNotEqualTo(after.get("INVITEE_ID"));
    }

    // ---------------- 规模化例子（需求 6.5 点名的 10000 行） ----------------

    /**
     * 需求 6.5 的规模化例子：同一 {@code inviter_id} 名下已有 10000 行时，为其插入第 10001 行仍然成功。
     *
     * <p>刻意只跑<b>一次</b>而不进属性：被测路径里没有任何按行数判定的分支，10000 这个数字与 150
     * 走的是同一条语句，重复 200 次只是把 200 万行写入摊到 CI 上（详见类注释）。历史行走 JDBC 批量
     * 插入、{@code invitee_id} 取悬空 id，因此这一个例子秒级完成。</p>
     *
     * <p>Validates: Requirements 6.5</p>
     */
    @Example
    void example_tenThousandExistingRowsDoNotBlockInsert() {
        Inviter inviter = createInviter();
        seedDanglingRows(inviter.id(), 10_000);
        assertThat(relationRepository.countByInviterId(inviter.id())).isEqualTo(10_000L);

        Registered registered = register(inviter.code(), 0);

        assertThat(registered.result().bound())
                .as("已有 10000 行时仍应允许插入新行（需求 6.5）").isTrue();
        assertThat(relationRepository.countByInviterId(inviter.id())).isEqualTo(10_001L);

        Map<String, Object> row = rowOf(registered.userId());
        assertThat(row.get("INVITER_ID")).isEqualTo(inviter.id());
        assertThat(row.get("INVITEE_ID")).isEqualTo(registered.userId());
        assertNoSelfRelation("10000 行规模化例子之后");
    }

    // ---------------- 断言 ----------------

    /** 需求 6.7：本类写入的全部行中不存在 {@code inviter_id = invitee_id} 的行。 */
    private void assertNoSelfRelation(String label) {
        Long selfRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_relations WHERE inviter_id = invitee_id AND invite_id > ?",
                Long.class, SCAN_FLOOR.get());
        assertThat(selfRows).as(label + "：不得存在 inviter_id = invitee_id 的行").isZero();
    }

    // ---------------- 测试基础设施 ----------------

    private record Inviter(Long id, String code) {
    }

    private record Registered(Long userId, InviteBindResult result) {
    }

    /** 建立一名邀请人（已提交），返回其 id 与邀请码。 */
    private Inviter createInviter() {
        long seq = SEQ.incrementAndGet();
        String code = encode(CODE_BASE + seq);
        LocalDateTime now = BASE.plusSeconds(seq);
        Long id = tx.execute(s -> persistUser(seq, "inviter", code, now).getId());
        return new Inviter(id, code);
    }

    /** 走真实注册路径：同一物理事务内新建 users 行并调用 {@code bindOnRegister}。 */
    private Registered register(String inviterCode, int mangle) {
        long seq = SEQ.incrementAndGet();
        LocalDateTime now = BASE.plusSeconds(seq);
        Registered registered = tx.execute(s -> {
            User invitee = persistUser(seq, "invitee", encode(CODE_BASE + seq), now);
            InviteBindResult result =
                    bindingService.bindOnRegister(invitee, true, mangle(inviterCode, mangle), now);
            return new Registered(invitee.getId(), result);
        });
        assertThat(registered).isNotNull();
        return registered;
    }

    /** 预置历史行：{@code invitee_id} 取悬空 id（该列无外键，见需求 9.6），状态两种取值交替。 */
    private void seedDanglingRows(Long inviterId, int rows) {
        List<Object[]> batch = new ArrayList<>(rows);
        for (int i = 0; i < rows; i++) {
            long inviteeId = DANGLING_ID.incrementAndGet();
            LocalDateTime registerTime = BASE.plusMinutes(i);
            String status = (i % 4 == 3 ? InviteStatus.INVALID : InviteStatus.REGISTERED).name();
            batch.add(new Object[] { inviterId, inviteeId, registerTime, status, BASE, BASE });
        }
        // 分块批量插入：10000 行的例子也是秒级。
        int chunk = 1000;
        for (int from = 0; from < batch.size(); from += chunk) {
            jdbcTemplate.batchUpdate(INSERT_RELATION_SQL,
                    batch.subList(from, Math.min(from + chunk, batch.size())));
        }
    }

    private User persistUser(long seq, String tag, String inviteCode, LocalDateTime now) {
        User u = new User();
        u.setEmail("p7-" + tag + "-" + seq + "@example.com");
        u.setNickname("p7-" + tag + "-" + seq);
        u.setInviteCode(inviteCode);
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.save(u);
    }

    private Map<String, Object> rowOf(Long inviteeId) {
        return jdbcTemplate.queryForMap(SELECT_ROW_SQL, inviteeId);
    }

    private long countRelations() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM invite_relations", Long.class);
        return count == null ? 0L : count;
    }

    private long maxInviteId() {
        Long max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(invite_id), 0) FROM invite_relations", Long.class);
        return max == null ? 0L : max;
    }

    private static <T> T pick(List<T> pool, int selector) {
        return pool.isEmpty() ? null : pool.get(Math.floorMod(selector, pool.size()));
    }

    /** 邀请码原始取值的变形：规整（trim + 大写）后仍应命中同一邀请人。 */
    private static String mangle(String code, int mangle) {
        return switch (mangle) {
            case 1 -> code.toLowerCase(Locale.ROOT);
            case 2 -> code.substring(0, 4).toLowerCase(Locale.ROOT) + code.substring(4);
            case 3 -> "  " + code + " ";
            case 4 -> "\t" + code + " \n";
            default -> code;
        };
    }

    /**
     * 把 n 编码成 8 位邀请码：前两位固定为本类专属前缀 {@code R7}，后 6 位取字母表 32 进制。
     *
     * <p>类专属前缀是<b>必需的</b>而非装饰：全部切片测试共用同一个内存 H2
     * （{@code jdbc:h2:mem:youyu;DB_CLOSE_DELAY=-1}）且 Spring 上下文缓存跨测试类复用，
     * {@code create-drop} 因此不会在类之间清库；各兄弟属性测试的序号又都从 0 开始，
     * 若共用同一段码空间，{@code uk_users_invite_code} 会随机爆掉。
     * 同理 {@link #persistUser} 的邮箱一律带 {@code p7-} 前缀。</p>
     */
    private static String encode(long n) {
        char[] out = new char[InviteCodeGenerator.LENGTH];
        out[0] = 'R';
        out[1] = '7';
        long v = n;
        for (int i = InviteCodeGenerator.LENGTH - 1; i >= 2; i--) {
            out[i] = InviteCodeGenerator.ALPHABET
                    .charAt((int) (v % InviteCodeGenerator.ALPHABET.length()));
            v /= InviteCodeGenerator.ALPHABET.length();
        }
        return new String(out);
    }
}
