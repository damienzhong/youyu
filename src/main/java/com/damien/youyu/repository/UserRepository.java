package com.damien.youyu.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.User;

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
}
