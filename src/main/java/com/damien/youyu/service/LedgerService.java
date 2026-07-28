package com.damien.youyu.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Ledger;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.BudgetRepository;
import com.damien.youyu.repository.CategoryBudgetRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.LoanRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 账本服务：账本的列出、创建、重命名、删除，以及「默认账本」的惰性保障。
 *
 * <p>账本按 {@code userId} 归属用户。存量用户的默认账本由 Flyway 迁移(V8)创建；新注册用户在首个已认证
 * 业务请求解析当前账本时由 {@link #ensureDefaultLedger(Long)} 惰性创建（并预置默认分类），避免与鉴权耦合。</p>
 */
@Service
public class LedgerService {

    static final int NAME_MAX = 50;
    private static final String DEFAULT_NAME = "默认账本";

    private final LedgerRepository ledgerRepository;
    private final CategoryRepository categoryRepository;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final BudgetRepository budgetRepository;
    private final CategoryBudgetRepository categoryBudgetRepository;
    private final LoanRepository loanRepository;
    private final Clock clock;

    public LedgerService(
            LedgerRepository ledgerRepository,
            CategoryRepository categoryRepository,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            BudgetRepository budgetRepository,
            CategoryBudgetRepository categoryBudgetRepository,
            LoanRepository loanRepository,
            Clock clock) {
        this.ledgerRepository = ledgerRepository;
        this.categoryRepository = categoryRepository;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.budgetRepository = budgetRepository;
        this.categoryBudgetRepository = categoryBudgetRepository;
        this.loanRepository = loanRepository;
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
     */
    @Transactional
    public Ledger ensureDefaultLedger(Long userId) {
        return ledgerRepository.findFirstByUserIdAndIsDefaultTrue(userId)
                .or(() -> ledgerRepository.findFirstByUserIdOrderBySortOrderAscIdAsc(userId))
                .orElseGet(() -> createLedger(userId, DEFAULT_NAME, 0, true));
    }

    /** 校验某账本属于当前用户并返回；不匹配抛 NOT_FOUND。 */
    @Transactional(readOnly = true)
    public Ledger requireOwned(Long userId, Long ledgerId) {
        return ledgerRepository.findByIdAndUserId(ledgerId, userId)
                .orElseThrow(() -> ApiException.notFound("账本不存在"));
    }

    /** 创建新账本。 */
    @Transactional
    public Ledger create(Long userId, String rawName) {
        String name = validateName(rawName);
        return createLedger(userId, name, nextSortOrder(userId), false);
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

    /**
     * 删除账本并级联清除其全部业务数据。至少保留一个账本；删除默认账本时把默认标记转移到剩余账本之一。
     */
    @Transactional
    public void delete(Long userId, Long id) {
        Ledger ledger = requireOwned(userId, id);
        if (ledgerRepository.countByUserId(userId) <= 1) {
            throw ApiException.ledgerLastOne();
        }
        // 级联清除该账本的业务数据（顺序无外键约束依赖，但先清引用方更稳妥）。
        transactionRepository.deleteByLedgerId(id);
        categoryBudgetRepository.deleteByLedgerId(id);
        budgetRepository.deleteByLedgerId(id);
        loanRepository.deleteByLedgerId(id);
        categoryRepository.deleteByLedgerId(id);
        accountRepository.deleteByLedgerId(id);
        ledgerRepository.delete(ledger);

        // 若删的是默认账本，把默认标记转移到剩余排序第一的账本。
        if (ledger.isDefault()) {
            ledgerRepository.findFirstByUserIdOrderBySortOrderAscIdAsc(userId).ifPresent(next -> {
                next.setDefault(true);
                next.setUpdatedAt(LocalDateTime.now(clock));
                ledgerRepository.save(next);
            });
        }
    }

    private Ledger createLedger(Long userId, String name, int sortOrder, boolean isDefault) {
        LocalDateTime now = LocalDateTime.now(clock);
        Ledger ledger = new Ledger();
        ledger.setUserId(userId);
        ledger.setName(name);
        ledger.setSortOrder(sortOrder);
        ledger.setDefault(isDefault);
        ledger.setCreatedAt(now);
        ledger.setUpdatedAt(now);
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
