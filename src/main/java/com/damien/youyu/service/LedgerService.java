package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Ledger;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.LedgerRepository;

/**
 * 账本服务：账本的列出、创建、重命名，以及「默认账本」的惰性保障。
 *
 * <p>账本按 {@code userId} 归属用户。存量用户的默认账本由 Flyway 迁移(V8)创建；新注册用户不在注册流程
 * 中建账本，而是在首个已认证业务请求解析当前账本时，由 {@link #ensureDefaultLedger(Long)} 惰性创建，
 * 避免与鉴权流程耦合。</p>
 *
 * <p>本阶段(阶段一)仅提供账本自身的管理；业务数据按账本隔离的落地在阶段二。</p>
 */
@Service
public class LedgerService {

    static final int NAME_MAX = 50;
    private static final String DEFAULT_NAME = "默认账本";

    private final LedgerRepository ledgerRepository;
    private final Clock clock;

    public LedgerService(LedgerRepository ledgerRepository, Clock clock) {
        this.ledgerRepository = ledgerRepository;
        this.clock = clock;
    }

    /** 列出某用户全部账本；若一个都没有则先创建默认账本。 */
    @Transactional
    public List<Ledger> list(Long userId) {
        ensureDefaultLedger(userId);
        return ledgerRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
    }

    /**
     * 返回该用户的默认账本；不存在则创建（新用户首次访问的惰性初始化）。
     * 优先返回标记为默认的账本，否则返回排序第一的账本，仍无则新建默认账本。
     */
    @Transactional
    public Ledger ensureDefaultLedger(Long userId) {
        return ledgerRepository.findFirstByUserIdAndIsDefaultTrue(userId)
                .or(() -> ledgerRepository.findFirstByUserIdOrderBySortOrderAscIdAsc(userId))
                .orElseGet(() -> {
                    LocalDateTime now = LocalDateTime.now(clock);
                    Ledger ledger = new Ledger();
                    ledger.setUserId(userId);
                    ledger.setName(DEFAULT_NAME);
                    ledger.setSortOrder(0);
                    ledger.setDefault(true);
                    ledger.setCreatedAt(now);
                    ledger.setUpdatedAt(now);
                    return ledgerRepository.save(ledger);
                });
    }

    /**
     * 校验某账本属于当前用户并返回；不匹配抛 NOT_FOUND（越权/不存在不泄漏内容）。
     * 供请求边界解析 {@code X-Ledger-Id} 时校验归属。
     */
    @Transactional(readOnly = true)
    public Ledger requireOwned(Long userId, Long ledgerId) {
        return ledgerRepository.findByIdAndUserId(ledgerId, userId)
                .orElseThrow(() -> ApiException.notFound("账本不存在"));
    }

    /** 创建新账本。 */
    @Transactional
    public Ledger create(Long userId, String rawName) {
        String name = validateName(rawName);
        LocalDateTime now = LocalDateTime.now(clock);
        Ledger ledger = new Ledger();
        ledger.setUserId(userId);
        ledger.setName(name);
        ledger.setSortOrder(nextSortOrder(userId));
        ledger.setDefault(false);
        ledger.setCreatedAt(now);
        ledger.setUpdatedAt(now);
        return ledgerRepository.save(ledger);
    }

    /** 重命名账本。 */
    @Transactional
    public Ledger rename(Long userId, Long id, String rawName) {
        String name = validateName(rawName);
        Ledger ledger = requireOwned(userId, id);
        ledger.setName(name);
        ledger.setUpdatedAt(LocalDateTime.now(clock));
        return ledgerRepository.save(ledger);
    }

    private int nextSortOrder(Long userId) {
        return ledgerRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .mapToInt(Ledger::getSortOrder)
                .max()
                .orElse(-1) + 1;
    }

    private String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw ApiException.ledgerNameInvalid();
        }
        return name;
    }
}
