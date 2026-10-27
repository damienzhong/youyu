package com.damien.youyu.repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.VisionUsage;

/** 截图识别每日用量仓库。 */
@Repository
public interface VisionUsageRepository extends JpaRepository<VisionUsage, Long> {

    Optional<VisionUsage> findByUserIdAndUsageDate(Long userId, LocalDate usageDate);

    /**
     * 条件预占额度：仅当 {@code used + n <= limit} 时加 n，返回受影响行数（1 成功 / 0 额度不足）。
     * 单条 UPDATE 由数据库保证原子性，并发请求不会超额。
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE VisionUsage v SET v.used = v.used + :n, v.updatedAt = :now "
            + "WHERE v.userId = :userId AND v.usageDate = :day AND v.used + :n <= :limit")
    int tryReserve(@Param("userId") Long userId, @Param("day") LocalDate day, @Param("n") int n,
            @Param("limit") int limit, @Param("now") LocalDateTime now);

    /** 退回额度（识别失败 / 不是账单），不低于 0。 */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE VisionUsage v SET v.used = CASE WHEN v.used >= :n THEN v.used - :n ELSE 0 END, "
            + "v.updatedAt = :now WHERE v.userId = :userId AND v.usageDate = :day")
    int release(@Param("userId") Long userId, @Param("day") LocalDate day, @Param("n") int n,
            @Param("now") LocalDateTime now);

    /** 删除某用户的全部用量（注销级联硬删）。 */
    void deleteByUserId(Long userId);
}
