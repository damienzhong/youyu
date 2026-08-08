package com.damien.youyu.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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
import com.damien.youyu.repository.UserRepository;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.lifecycle.BeforeTry;

/**
 * 保存点方案的回归锁（Property 6）：唯一约束冲突经保存点消化，登录事务照常提交。
 *
 * <p><b>这个测试是「插入不得经过 Hibernate」这条隐形实现约束的唯一防线。</b>
 * {@link InviteBindingService} 用 {@link JdbcTemplate} + JDBC 保存点插入邀请关系，
 * 而不是 {@code inviteRelationRepository.save()}。<b>若有人把插入改回
 * {@code repository.save()}（或任何经 {@code EntityManager} 的持久化调用），本测试必然失败：</b>
 * 唯一约束冲突会在 Hibernate flush 时爆发，JPA 规范规定 flush 失败即把事务标记为回滚，
 * 且此后持久化上下文已被污染（失败的实体仍在上下文里，提交时的 flush 会重放该插入），
 * 于是本测试驱动的 {@link TransactionTemplate#execute} 会以
 * {@code RollbackException}/{@code UnexpectedRollbackException} 抛出——「提交成功、新用户行存在」
 * 这两条断言无从满足。同理，若有人把 {@code DuplicateKeyException} 放行到方法外（让它穿出
 * {@code @Transactional} 代理边界），Spring 会把同一物理事务标记 rollback-only，提交同样失败。</p>
 *
 * <h2>测试层级选择</h2>
 * <p>必须是真实数据库上的真实事务：本属性断言的全部内容（保存点建立/回滚、唯一索引冲突被翻译为
 * {@link org.springframework.dao.DuplicateKeyException}、回滚到保存点后事务仍可提交）都只在真实
 * JDBC 连接上才成立，任何测试替身都会把被测机制本身替换掉。故走 {@code @DataJpaTest} + H2
 * （{@code MODE=MySQL}，表由实体生成，{@code uk_invite_relations_invitee} 由
 * {@link com.damien.youyu.domain.InviteRelation} 的 {@code @Table} 声明），只额外导入被测服务
 * 与它依赖的邀请码组件。</p>
 *
 * <p>jqwik 的属性方法不经 JUnit Jupiter 引擎，Spring 的 {@code SpringExtension} 因此不生效，
 * 依赖注入改由 {@link TestContextManager} 在 {@link BeforeTry} 中手工完成（上下文由 Spring 的
 * 静态上下文缓存复用，200 次迭代只加载一次）。</p>
 *
 * <h2>生成维度</h2>
 * <ul>
 *   <li>邀请码原始取值的变形（原样 / 小写 / 混合大小写 / 前后空白）——覆盖 {@code normalize} 之后仍应命中同一邀请人。</li>
 *   <li>预置冲突行的 {@code status}（{@code REGISTERED} / {@code INVALID}）与 {@code register_time} 偏移——冲突行的快照必须逐列不变。</li>
 *   <li>邀请人来自更早的已提交事务，或与新用户同处一个物理事务——后者验证保存点之前的写入不被回滚。</li>
 *   <li>「冲突 + 其它待写数据」：0–3 条其它邀请关系（保存点之前经 JDBC 写入）+ 1 条尚未 flush 的实体更新
 *       （新用户昵称，由 {@code bindOnRegister} 内的 flush 落库）——验证保存点范围不过大。</li>
 * </ul>
 *
 * <p>Feature: invite-system, Property 6: 唯一约束冲突经保存点消化，登录事务照常提交</p>
 *
 * <p>Validates: Requirements 5.10, 6.8</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ InviteBindingService.class, InviteCodeGenerator.class })
class InviteSavepointPropertyTest {

    private static final LocalDateTime BASE = LocalDateTime.of(2025, 6, 1, 12, 0, 0);

    /** 同一个 H2 库跨迭代复用，用序号保证 email / 邀请码全局唯一。 */
    private static final AtomicLong SEQ = new AtomicLong();

    private static final String COUNT_ALL_SQL = "SELECT COUNT(*) FROM invite_relations";
    private static final String INSERT_RELATION_SQL =
            "INSERT INTO invite_relations "
                    + "(inviter_id, invitee_id, register_time, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";
    private static final String SELECT_RELATION_SQL =
            "SELECT invite_id, inviter_id, register_time, status, created_at, updated_at "
                    + "FROM invite_relations WHERE invitee_id = ?";

    @Autowired
    private InviteBindingService bindingService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeTry
    void injectSpringBeans() throws Exception {
        new TestContextManager(InviteSavepointPropertyTest.class).prepareTestInstance(this);
        // 默认传播行为 REQUIRED：为 bindOnRegister 的 MANDATORY 提供外层物理事务。
        tx = new TransactionTemplate(transactionManager);
    }

    // ---------------- 生成器 ----------------

    /** 邀请码原始取值的变形方式：0 原样 / 1 小写 / 2 混合大小写 / 3 首尾空格 / 4 首尾制表与换行。 */
    @Provide
    Arbitrary<Integer> codeMangles() {
        return Arbitraries.integers().between(0, 4);
    }

    /** 预置冲突行的状态：两种取值都不该被本次插入尝试改写。 */
    @Provide
    Arbitrary<InviteStatus> relationStatuses() {
        return Arbitraries.of(InviteStatus.REGISTERED, InviteStatus.INVALID);
    }

    // ---------------- Property 6 ----------------

    /**
     * Feature: invite-system, Property 6: 唯一约束冲突经保存点消化，登录事务照常提交
     *
     * <p>对任意会触发 {@code invitee_id} 唯一约束冲突的注册请求：{@code bindOnRegister} 返回
     * {@code ALREADY_BOUND} 而不抛出，外层（登录）事务照常提交；提交后新建的 {@code users} 行与其
     * 非空 {@code invite_code} 存在，{@code invite_relations} 的行数与调用前相同，已存在那一行的
     * {@code inviter_id}/{@code register_time}/{@code status}（连同 {@code invite_id}/审计列）
     * 逐列快照相等；保存点之前的其它写入（其它邀请关系行、尚未 flush 的实体更新）全部保留。</p>
     *
     * <p><b>反向断言（防回归）：若把 {@code jdbcTemplate.update(...)} 改回
     * {@code inviteRelationRepository.save(...)}，本测试必然失败</b>——见类注释。</p>
     *
     * <p>Validates: Requirements 5.10, 6.8</p>
     */
    @Property(tries = 25)
    void property6_uniqueConflictAbsorbedBySavepoint(
            @ForAll("codeMangles") int mangle,
            @ForAll("relationStatuses") InviteStatus preexistingStatus,
            @ForAll @IntRange(min = 0, max = 3) int extraPreSavepointRelations,
            @ForAll @IntRange(min = 0, max = 5000) int registerTimeOffsetMinutes,
            @ForAll boolean inviterFromEarlierTransaction) {

        long seq = SEQ.incrementAndGet();
        LocalDateTime now = BASE.plusSeconds(seq);
        String inviterCode = codeOf(seq * 100);
        String inviteeCode = codeOf(seq * 100 + 1);
        String pendingNickname = "pending-" + seq;

        // 邀请人可以来自更早的已提交事务，也可以与新用户同处一个物理事务（后者是保存点范围的关键用例）。
        Long earlierInviterId = inviterFromEarlierTransaction
                ? tx.execute(s -> persistUser(seq, "inviter", inviterCode, now).getId())
                : null;

        Outcome outcome = tx.execute(status -> {
            Long inviterId = earlierInviterId != null
                    ? earlierInviterId
                    : persistUser(seq, "inviter", inviterCode, now).getId();

            // 本次「新建」的用户：IDENTITY 主键，save 即刻发出 INSERT，id 可用。
            User newUser = persistUser(seq, "invitee", inviteeCode, now);
            // 一条尚未 flush 的实体更新：应由 bindOnRegister 内的 flush 在保存点之前落库，
            // 因而不在保存点范围内，回滚到保存点时必须存活。
            newUser.setNickname(pendingNickname);

            // 预置冲突行：invitee_id 等于新用户 id，邀请人是另一个「先到者」。
            Long ghostInviterId = persistUser(seq, "ghost", codeOf(seq * 100 + 2), now).getId();
            insertRelation(ghostInviterId, newUser.getId(),
                    now.plusMinutes(registerTimeOffsetMinutes), preexistingStatus, now);

            // 冲突 + 其它待写数据：保存点之前写入的其它邀请关系行。
            List<Long> extraInviteeIds = new ArrayList<>();
            for (int i = 0; i < extraPreSavepointRelations; i++) {
                Long extraInvitee = persistUser(seq, "extra" + i, codeOf(seq * 100 + 10 + i), now).getId();
                insertRelation(inviterId, extraInvitee, now.plusMinutes(i), InviteStatus.REGISTERED, now);
                extraInviteeIds.add(extraInvitee);
            }

            long countBefore = countRelations();
            Map<String, Object> conflictRowBefore = relationOf(newUser.getId());

            InviteBindResult result = bindingService.bindOnRegister(
                    newUser, true, mangleCode(inviterCode, mangle), now);

            return new Outcome(result, inviterId, newUser.getId(), countBefore,
                    conflictRowBefore, extraInviteeIds);
        });

        assertThat(outcome).isNotNull();

        // 1) 冲突被消化为 ALREADY_BOUND，且未抛出（tx.execute 正常返回即已提交）。
        assertThat(outcome.result().bound()).isFalse();
        assertThat(outcome.result().reason()).isEqualTo(UnboundReason.ALREADY_BOUND);

        // 2) 提交成功：新用户行与其非空 invite_code 存在；保存点之前的实体更新（昵称）保留。
        Map<String, Object> newUserRow = jdbcTemplate.queryForMap(
                "SELECT invite_code, nickname FROM users WHERE id = ?", outcome.newUserId());
        assertThat(newUserRow.get("INVITE_CODE")).isEqualTo(inviteeCode);
        assertThat(newUserRow.get("NICKNAME")).isEqualTo(pendingNickname);
        // 同一事务内创建的邀请人也照常提交。
        assertThat(userRepository.findById(outcome.inviterId())).isPresent();

        // 3) invite_relations 行数不变，且该 invitee 仍恰好一行。
        assertThat(countRelations()).isEqualTo(outcome.relationCountBefore());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM invite_relations WHERE invitee_id = ?",
                Long.class, outcome.newUserId())).isEqualTo(1L);

        // 4) 已存在那一行逐列快照相等（含 inviter_id / register_time / status 与审计列）。
        assertThat(relationOf(outcome.newUserId())).isEqualTo(outcome.conflictRowBefore());

        // 5) 保存点范围不过大：保存点之前写入的其它邀请关系行全部存活。
        for (Long extraInviteeId : outcome.extraInviteeIds()) {
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM invite_relations WHERE invitee_id = ?",
                    Long.class, extraInviteeId)).isEqualTo(1L);
        }
    }

    // ---------------- 测试基础设施 ----------------

    private record Outcome(InviteBindResult result, Long inviterId, Long newUserId,
                           long relationCountBefore, Map<String, Object> conflictRowBefore,
                           List<Long> extraInviteeIds) {
    }

    /**
     * 把 n 编码成 8 位邀请码：前两位固定为本类专属前缀 {@code P6}，后 6 位取字母表 32 进制。
     *
     * <p>类专属前缀是<b>必需的</b>而非装饰：全部切片测试共用同一个内存 H2
     * （{@code jdbc:h2:mem:youyu;DB_CLOSE_DELAY=-1}）且 Spring 上下文缓存跨测试类复用，
     * {@code create-drop} 因此不会在类之间清库；各兄弟属性测试的序号又都从 0 开始，
     * 若共用同一段码空间，{@code users.invite_code} 上的 {@code uk_users_invite_code}
     * 会在跨类复用同一上下文时随机爆掉。同理 {@link #persistUser} 的邮箱一律带
     * {@code p6-} 前缀，与兄弟测试的邮箱空间不相交。</p>
     */
    private static String codeOf(long n) {
        char[] out = new char[InviteCodeGenerator.LENGTH];
        out[0] = 'P';
        out[1] = '6';
        long v = n;
        for (int i = InviteCodeGenerator.LENGTH - 1; i >= 2; i--) {
            out[i] = InviteCodeGenerator.ALPHABET.charAt((int) (v % InviteCodeGenerator.ALPHABET.length()));
            v /= InviteCodeGenerator.ALPHABET.length();
        }
        return new String(out);
    }

    /** 请求携带的原始取值变形：规整（trim + 大写）后仍应命中同一邀请人。 */
    private static String mangleCode(String code, int mangle) {
        return switch (mangle) {
            case 1 -> code.toLowerCase(java.util.Locale.ROOT);
            case 2 -> code.substring(0, 4).toLowerCase(java.util.Locale.ROOT) + code.substring(4);
            case 3 -> "  " + code + " ";
            case 4 -> "\t" + code + " \n";
            default -> code;
        };
    }

    private User persistUser(long seq, String tag, String inviteCode, LocalDateTime now) {
        User u = new User();
        // p6- 前缀：库跨测试类共用，邮箱唯一约束同样需要类专属命名空间（见 codeOf 的说明）。
        u.setEmail("p6-" + tag + "-" + seq + "@example.com");
        u.setNickname("p6-" + tag + "-" + seq);
        u.setInviteCode(inviteCode);
        u.setPlan(Plan.FREE);
        u.setRole(Role.USER);
        u.setPlanStartedAt(now);
        u.setPlanExpiresAt(now.plusDays(365));
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        return userRepository.save(u);
    }

    /** 刻意走 JDBC 而非仓储：预置数据不该污染持久化上下文，也不该在提交时被重放。 */
    private void insertRelation(Long inviterId, Long inviteeId, LocalDateTime registerTime,
            InviteStatus status, LocalDateTime auditTime) {
        jdbcTemplate.update(INSERT_RELATION_SQL,
                inviterId, inviteeId, registerTime, status.name(), auditTime, auditTime);
    }

    private long countRelations() {
        Long count = jdbcTemplate.queryForObject(COUNT_ALL_SQL, Long.class);
        return count == null ? 0L : count;
    }

    private Map<String, Object> relationOf(Long inviteeId) {
        return jdbcTemplate.queryForMap(SELECT_RELATION_SQL, inviteeId);
    }
}
