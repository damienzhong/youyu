package com.damien.youyu.vision;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.config.VisionProperties;
import com.damien.youyu.domain.VisionUsage;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.VisionUsageRepository;

/**
 * 截图识别每日额度：识别前预占、识别后退回失败的张数。每个方法独立提交（REQUIRES_NEW），
 * 不把数据库事务挂在几十秒的模型调用上。
 */
@Service
public class VisionQuotaService {

    private final VisionUsageRepository repository;
    private final VisionProperties props;
    private final Clock clock;

    public VisionQuotaService(VisionUsageRepository repository, VisionProperties props, Clock clock) {
        this.repository = repository;
        this.props = props;
        this.clock = clock;
    }

    /** 今天还能识别几张。 */
    @Transactional(readOnly = true)
    public int remaining(Long userId) {
        int used = repository.findByUserIdAndUsageDate(userId, today()).map(VisionUsage::getUsed).orElse(0);
        return Math.max(0, props.getDailyLimit() - used);
    }

    /**
     * 预占 n 张额度；不足则抛 {@code VISION_QUOTA_EXCEEDED}（带剩余张数），不扣任何额度。
     *
     * @return 预占所在的自然日（退回时用同一天，避免跨零点退到第二天）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public LocalDate reserve(Long userId, int n) {
        LocalDate day = today();
        LocalDateTime now = LocalDateTime.now(clock);
        ensureRow(userId, day, now);
        if (repository.tryReserve(userId, day, n, props.getDailyLimit(), now) == 0) {
            throw ApiException.visionQuotaExceeded(remainingOn(userId, day));
        }
        return day;
    }

    /** 退回 n 张额度（识别失败 / 不是账单）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(Long userId, LocalDate day, int n) {
        if (n > 0) {
            repository.release(userId, day, n, LocalDateTime.now(clock));
        }
    }

    private int remainingOn(Long userId, LocalDate day) {
        int used = repository.findByUserIdAndUsageDate(userId, day).map(VisionUsage::getUsed).orElse(0);
        return Math.max(0, props.getDailyLimit() - used);
    }

    private void ensureRow(Long userId, LocalDate day, LocalDateTime now) {
        if (repository.findByUserIdAndUsageDate(userId, day).isPresent()) {
            return;
        }
        VisionUsage u = new VisionUsage();
        u.setUserId(userId);
        u.setUsageDate(day);
        u.setUsed(0);
        u.setCreatedAt(now);
        u.setUpdatedAt(now);
        try {
            repository.saveAndFlush(u);
        } catch (DataIntegrityViolationException e) {
            // 并发请求同时建当天的行：唯一键兜底，对方已建好即可
        }
    }

    private LocalDate today() {
        return LocalDate.now(clock);
    }
}
