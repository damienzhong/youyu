package com.damien.youyu.repository;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.EmailCodePurpose;
import com.damien.youyu.domain.VerificationCode;

/**
 * 邮箱验证码仓库。
 *
 * <p>承载防刷四件套所需的查询：取当前有效码（校验/单次消费）、冷却存在性判定、
 * IP 计数（分钟/日限流）；以及按邮箱删除（注销释放身份 / 清理）。</p>
 *
 * @see VerificationCode
 * @see EmailCodePurpose
 */
@Repository
public interface VerificationCodeRepository extends JpaRepository<VerificationCode, Long> {

    /**
     * 取某邮箱在某用途下最新一条未消费的验证码（用于校验/单次消费）。
     * 按 id 倒序，保证拿到最近一次发码。
     */
    Optional<VerificationCode> findFirstByEmailAndPurposeAndConsumedFalseOrderByIdDesc(
            String email, EmailCodePurpose purpose);

    /**
     * 判定冷却：某邮箱在某用途下、自 {@code since} 之后是否已有发码记录。
     */
    boolean existsByEmailAndPurposeAndCreatedAtAfter(
            String email, EmailCodePurpose purpose, LocalDateTime since);

    /**
     * IP 限流计数：某来源 IP 自 {@code since} 之后的发码次数（分钟/日窗口）。
     */
    long countByIpAndCreatedAtAfter(String ip, LocalDateTime since);

    /**
     * 按邮箱删除全部验证码记录（注销释放身份 / 清理）。
     */
    @Modifying
    @Transactional
    void deleteByEmail(String email);
}
