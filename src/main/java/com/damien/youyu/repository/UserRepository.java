package com.damien.youyu.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.User;

/**
 * 用户仓库。
 *
 * <p>用户是多租户隔离的根实体，其自身按账号标识(username)或主键定位；
 * 归属其名下的业务数据(Account/Category/Transaction)则在各自仓库中固定携带 user_id 过滤。</p>
 */
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    /** 按账号标识查找用户（登录/注册占用校验用）。 */
    Optional<User> findByUsername(String username);

    /** 账号标识是否已被占用。 */
    boolean existsByUsername(String username);
}
