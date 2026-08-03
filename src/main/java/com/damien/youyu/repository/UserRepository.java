package com.damien.youyu.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.User;

import jakarta.persistence.LockModeType;

/**
 * 用户仓库。
 *
 * <p>用户是多租户隔离的根实体，其自身按登录身份(email / wx_openid)或主键定位；
 * 归属其名下的业务数据(Account/Category/Transaction)则在各自仓库中固定携带 user_id 过滤。</p>
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /** 按邮箱查找用户（邮箱验证码登录/注册、绑定占用校验用）。 */
    Optional<User> findByEmail(String email);

    /** 邮箱是否已被占用。 */
    boolean existsByEmail(String email);

    /** 按微信 openid 查找用户（微信授权登录用）。 */
    Optional<User> findByWxOpenid(String wxOpenid);

    /**
     * 按邀请码查找邀请人（登录时绑定邀请关系、公开的邀请人展示信息查询用）。
     *
     * <p>邀请码全局唯一（{@code uk_users_invite_code}），且随 {@code users} 行删除而释放，
     * 因此查不到既可能是码不存在、也可能是原持有者已注销，两种情形对外表现一致（需求 1.3、8.9）。</p>
     */
    Optional<User> findByInviteCode(String inviteCode);

    /** 邀请码是否已被占用，供邀请码生成器逐次探测候选（需求 1.6）。 */
    boolean existsByInviteCode(String inviteCode);

    /**
     * 按主键定位用户并加行级悲观写锁，供邀请码惰性补齐时使用：
     * 同一用户并发请求邀请信息时，只有持锁者生成并写入，其余请求读到已生成的码（需求 1.12）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findForUpdateById(@Param("id") Long id);
}
