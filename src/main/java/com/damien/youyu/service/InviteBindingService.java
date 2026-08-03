package com.damien.youyu.service;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.LocalDateTime;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.InviteStatus;
import com.damien.youyu.domain.User;
import com.damien.youyu.repository.UserRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * 注册时的邀请关系绑定：在调用方（登录）事务内尝试插入恰好 1 条邀请关系并返回绑定结果。
 *
 * <p>本服务只做这一件事，且必须复用调用方事务（{@link Propagation#MANDATORY}）：新建用户与其
 * 邀请关系要么一起提交、要么一起回滚（需求 5.2）。绑定时机唯一——邀请关系只在
 * {@code users} 表新插入一行的那一刻建立，老用户带码登录一律不绑定。</p>
 *
 * <h2>三条禁令（改动前请先读完，每条都会造成登录接口回归）</h2>
 * <ol>
 *   <li><b>插入不得改回 {@code inviteRelationRepository.save()}。</b>唯一约束冲突若经 Hibernate
 *       发出，会在 <em>flush</em> 时爆发：JPA 规范规定 flush 失败即把事务标记为回滚，且此后持久化
 *       上下文已被污染（失败的实体仍在上下文里，任何后续 flush 都会重放该插入），继续提交会得到
 *       {@code RollbackException} / {@code UnexpectedRollbackException}，整个登录一起挂掉。
 *       走 {@link JdbcTemplate} 让失败语句<b>从不经过 EntityManager</b>，冲突就只是一次普通的
 *       JDBC 错误。任务 5.2 的回归锁测试正是为这条禁令而写：改回 {@code save()} 它必然失败。</li>
 *   <li><b>唯一冲突异常不得穿出本方法。</b>Spring 的事务切面只在异常<em>穿出</em>被通知方法时把
 *       参与中的同一物理事务标记 rollback-only，外层提交时抛 {@code UnexpectedRollbackException}
 *       连坐登录。因此 {@link DuplicateKeyException} 必须在方法体内消化为
 *       {@link UnboundReason#ALREADY_BOUND}（需求 5.10、6.8）。
 *       <em>唯一冲突以外</em>的数据库故障则刻意抛出，让整个登录事务回滚、不签发令牌（需求 5.7）——
 *       所以捕获处<b>只捕 {@code DuplicateKeyException}</b>，绝不放宽到
 *       {@code DataIntegrityViolationException}，否则 CHECK 约束违例、非空违例这类真实缺陷会被静默吞掉。</li>
 *   <li><b>保存点之后不得触发 Hibernate 自动 flush。</b>任何 JPA 查询都会先自动 flush，若发生在
 *       保存点之后，等于把 Hibernate 的待办语句拉进保存点范围，{@code rollback(sp)} 会连本该保留的
 *       写入一起撤销。故 {@link UserRepository#findByInviteCode} 一律在建立保存点<b>之前</b>执行，
 *       保存点之后只剩一条 {@link JdbcTemplate} 的 INSERT。</li>
 * </ol>
 *
 * <p>另外两点实现约束：{@code findByInviteCode} 兼作 {@code inviter_id} 的存在性校验——
 * 查得到行就说明该用户存在，这是替代外键的应用层防线（需求 9.19），因此<b>不允许</b>用任何
 * 「缓存的码 → id 映射」跳过这次查询；对 {@code invite_relations} 最多执行 1 次插入尝试，
 * 失败不重试（需求 5.12）。</p>
 */
@Service
public class InviteBindingService {

    private static final Logger log = LoggerFactory.getLogger(InviteBindingService.class);

    /** 邀请码输入字段接受的原始取值长度上限（需求 5.1、5.6）。 */
    static final int MAX_RAW_CODE_LENGTH = 64;

    /** 保存点名称，仅用于日志与排查；同一事务内只会存在一个。 */
    static final String SAVEPOINT_NAME = "sp_invite_bind";

    /** 刻意手写列清单：绕开 Hibernate，也不依赖实体映射的列顺序。 */
    private static final String INSERT_SQL =
            "INSERT INTO invite_relations "
                    + "(inviter_id, invitee_id, register_time, status, created_at, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";

    @PersistenceContext
    private EntityManager entityManager;

    private final UserRepository userRepository;
    private final InviteCodeGenerator inviteCodeGenerator;
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public InviteBindingService(
            UserRepository userRepository,
            InviteCodeGenerator inviteCodeGenerator,
            JdbcTemplate jdbcTemplate,
            DataSource dataSource) {
        this.userRepository = userRepository;
        this.inviteCodeGenerator = inviteCodeGenerator;
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    /**
     * 在调用方事务内尝试绑定邀请关系（需求 5.2、5.11、6.10）。
     *
     * <p>判定链按固定优先级自上而下早返回，取首个成立者作为唯一未绑定原因：</p>
     * <ol>
     *   <li>{@link UnboundReason#NO_CODE}：规整后为空串（字段缺失 / NULL / 全空白）。</li>
     *   <li>{@link UnboundReason#NOT_NEW_USER}：本次未建号。<b>先于</b>格式校验，故老用户带畸形码
     *       登录得到的是 {@code NOT_NEW_USER}（需求 5.3、6.6）。</li>
     *   <li>{@link UnboundReason#CODE_NOT_FOUND}：原始长度 &gt; 64、格式非法，或该码在
     *       {@code users.invite_code} 中不存在（需求 5.5、5.6、9.19）。</li>
     *   <li>{@link UnboundReason#SELF_INVITE}：持有者就是新建用户本人（需求 6.2）。</li>
     *   <li>{@link UnboundReason#ALREADY_BOUND}：插入时 {@code invitee_id} 唯一约束冲突，
     *       已在保存点处消化（需求 5.10、6.3、6.8）。</li>
     * </ol>
     *
     * <p>前四种情形一律不对 {@code invite_relations} 执行任何语句，也不回滚登录事务：
     * 邀请码问题绝不阻断注册主路径。</p>
     *
     * @param newUser       本次新建的用户；{@code null} 表示本次未建号
     * @param isNewUser     本次请求是否在 {@code users} 表新插入了一行
     * @param rawInviteCode 请求携带的邀请码原始取值，可为 {@code null}
     * @param now           服务端时刻，必须与 {@code newUser.createdAt} 为同一个取值（需求 5.8）
     * @return 绑定结果；唯一约束冲突已消化为 {@code ALREADY_BOUND}，不抛出
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public InviteBindResult bindOnRegister(User newUser, boolean isNewUser,
                                           String rawInviteCode, LocalDateTime now) {
        // 1) 未携带邀请码（需求 5.1）。
        String code = inviteCodeGenerator.normalize(rawInviteCode);
        if (code.isEmpty()) {
            return InviteBindResult.ofUnbound(UnboundReason.NO_CODE);
        }

        // 2) 本次未建号：一律不绑定，且优先于格式校验（需求 5.3、6.6）。
        if (!isNewUser || newUser == null || newUser.getId() == null) {
            return InviteBindResult.ofUnbound(UnboundReason.NOT_NEW_USER);
        }

        // 3) 原始取值超长或格式非法：不进入插入、不回滚事务（需求 5.6）。
        if (rawInviteCode.length() > MAX_RAW_CODE_LENGTH || !inviteCodeGenerator.isWellFormed(code)) {
            return InviteBindResult.ofUnbound(UnboundReason.CODE_NOT_FOUND);
        }

        // 4) 查邀请人。这次查询同时是 inviter_id 的存在性校验（替代外键的应用层防线，需求 9.19），
        //    且必须落在保存点之前——它会触发 Hibernate 自动 flush（见类注释禁令 3）。
        User inviter = userRepository.findByInviteCode(code).orElse(null);
        if (inviter == null) {
            return InviteBindResult.ofUnbound(UnboundReason.CODE_NOT_FOUND);
        }

        // 5) 自邀（需求 6.2）。
        if (inviter.getId().equals(newUser.getId())) {
            return InviteBindResult.ofUnbound(UnboundReason.SELF_INVITE);
        }

        // 6) 先 flush，把 users 的 INSERT 及一切待办语句落到连接上，保证保存点之后只剩
        //    「插入邀请关系」这一条语句可回滚。
        entityManager.flush();

        return insertWithinSavepoint(inviter.getId(), newUser.getId(), now);
    }

    /**
     * 在事务保存点的保护下执行唯一一条插入语句（需求 5.10、5.12、6.8）。
     *
     * <p>连接取自 {@link DataSourceUtils}，与 {@link JdbcTemplate} 和当前 Hibernate 会话是
     * <b>同一个事务绑定连接</b>，保存点因此覆盖这条 INSERT。成功即 {@code RELEASE SAVEPOINT}；
     * 唯一约束冲突则 {@code ROLLBACK TO SAVEPOINT}，事务继续存活并照常提交登录。</p>
     *
     * <p>保存点自身操作（建立 / 回滚 / 释放）失败按「唯一冲突以外的数据库故障」处理：抛出
     * {@link IllegalStateException} 让整个登录事务回滚、不签发令牌（需求 5.7）。</p>
     */
    private InviteBindResult insertWithinSavepoint(Long inviterId, Long inviteeId, LocalDateTime now) {
        Connection conn = DataSourceUtils.getConnection(dataSource);
        try {
            Savepoint savepoint = conn.setSavepoint(SAVEPOINT_NAME);
            try {
                jdbcTemplate.update(INSERT_SQL,
                        inviterId, inviteeId, now, InviteStatus.REGISTERED.name(), now, now);
            } catch (DuplicateKeyException dup) {
                // 只回滚到保存点，事务继续存活；不重试，不放宽到 DataIntegrityViolationException。
                conn.rollback(savepoint);
                // INFO 而非 WARN：这是被显式建模的正常结果。在「绑定时机唯一」下本分支近乎不可达，
                // 留一条日志便于事后核对它是否真的不会出现。
                log.info("邀请关系已存在，本次以 ALREADY_BOUND 完成登录：inviteeId={}, inviterId={}",
                        inviteeId, inviterId);
                return InviteBindResult.ofUnbound(UnboundReason.ALREADY_BOUND);
            }
            conn.releaseSavepoint(savepoint);
            return InviteBindResult.ofBound();
        } catch (SQLException e) {
            throw new IllegalStateException("邀请关系保存点操作失败", e);
        } finally {
            // 事务绑定连接下为 no-op，仅为对称与将来无事务误用时的安全。
            DataSourceUtils.releaseConnection(conn, dataSource);
        }
    }
}
