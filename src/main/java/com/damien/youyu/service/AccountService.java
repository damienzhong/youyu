package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 账户服务：账户的创建、列表、修改与删除（关联需求 3.1-3.9）。
 *
 * <p>账户为<strong>用户级</strong>：同一用户的多个独立账本共享同一批真实账户，故按会话 {@code userId}
 * 隔离——写入以传入 userId 为准，读取/修改/删除他人账户一律返回 {@code NOT_FOUND}（需求 2.3、2.4）。
 * 账户余额跨账本按账户汇总（其被引用的全部流水）。金额一律 {@link BigDecimal}（需求 3.9）。</p>
 */
@Service
public class AccountService {

    static final int NAME_MIN = 1;
    static final int NAME_MAX = 50;

    static final BigDecimal BALANCE_MAX = new BigDecimal("9999999999999999.99");
    static final BigDecimal BALANCE_MIN = BALANCE_MAX.negate();

    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;
    private final Clock clock;

    public AccountService(
            AccountRepository accountRepository,
            TransactionRepository transactionRepository,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.clock = clock;
    }

    @Transactional
    public Account create(Long userId, String rawName, String rawType,
            BigDecimal rawInitialBalance, Integer sortOrder) {
        return create(userId, rawName, rawType, rawInitialBalance, sortOrder, true, false, null, null);
    }

    @Transactional
    public Account create(Long userId, String rawName, String rawType,
            BigDecimal rawInitialBalance, Integer sortOrder,
            boolean includeInTotal, boolean hidden, String rawNote) {
        return create(userId, rawName, rawType, rawInitialBalance, sortOrder,
                includeInTotal, hidden, rawNote, null);
    }

    /**
     * 创建账户（含扩展字段）：校验通过后 {@code current_balance = initial_balance}。
     *
     * @throws ApiException ACCOUNT_FIELD_INVALID（名称/类型/初始余额/备注任一非法，需求 3.3）
     */
    @Transactional
    public Account create(Long userId, String rawName, String rawType,
            BigDecimal rawInitialBalance, Integer sortOrder,
            boolean includeInTotal, boolean hidden, String rawNote, BigDecimal rawCreditLimit) {
        String name = validateName(rawName);
        AccountType type = validateType(rawType);
        BigDecimal initialBalance = validateBalance(rawInitialBalance);
        String note = validateNote(rawNote);
        BigDecimal creditLimit = validateCreditLimit(rawCreditLimit);

        LocalDateTime now = LocalDateTime.now(clock);
        Account account = new Account();
        // 账户为用户级：归属会话用户，ledger_id 为空（协作账本的账本级账户将来另行处理）。
        account.setUserId(userId);
        account.setLedgerId(null);
        account.setName(name);
        account.setType(type);
        account.setInitialBalance(initialBalance);
        account.setCurrentBalance(initialBalance);
        account.setSortOrder(sortOrder == null ? 0 : sortOrder);
        account.setIncludeInTotal(includeInTotal);
        account.setHidden(hidden);
        account.setNote(note);
        account.setCreditLimit(type == AccountType.CREDIT_CARD ? creditLimit : null);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        return accountRepository.save(account);
    }

    /** 列出本人全部账户，按 sort_order、id 升序；无账户返回空列表（需求 3.5）。 */
    @Transactional(readOnly = true)
    public List<Account> list(Long userId) {
        return accountRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
    }

    @Transactional
    public Account update(Long userId, Long id, String rawName, String rawType) {
        Account account = requireAccount(userId, id);
        account.setName(validateName(rawName));
        account.setType(validateType(rawType));
        account.setUpdatedAt(LocalDateTime.now(clock));
        return accountRepository.save(account);
    }

    @Transactional
    public Account update(Long userId, Long id, String rawName, String rawType,
            boolean includeInTotal, boolean hidden, String rawNote) {
        return update(userId, id, rawName, rawType, includeInTotal, hidden, rawNote, null);
    }

    /** 修改账户名称/类型及扩展字段（计入总资产/隐藏/备注/信用额度），保留余额（需求 3.6）。 */
    @Transactional
    public Account update(Long userId, Long id, String rawName, String rawType,
            boolean includeInTotal, boolean hidden, String rawNote, BigDecimal rawCreditLimit) {
        Account account = requireAccount(userId, id);

        AccountType type = validateType(rawType);
        account.setName(validateName(rawName));
        account.setType(type);
        account.setIncludeInTotal(includeInTotal);
        account.setHidden(hidden);
        account.setNote(validateNote(rawNote));
        account.setCreditLimit(type == AccountType.CREDIT_CARD ? validateCreditLimit(rawCreditLimit) : null);
        account.setUpdatedAt(LocalDateTime.now(clock));
        return accountRepository.save(account);
    }

    /**
     * 删除账户：仅当无任何交易引用（作为账户/源/目标）时删除，否则拒绝（需求 3.7/3.8）。
     */
    @Transactional
    public void delete(Long userId, Long id) {
        Account account = requireAccount(userId, id);
        if (transactionRepository.existsByAccountReferenced(id)) {
            throw ApiException.accountInUse();
        }
        accountRepository.delete(account);
    }

    /**
     * 由初始余额与全量流水聚合重算某账户应有余额（跨账本按账户汇总，需求 4.13、Property 1）。
     */
    @Transactional(readOnly = true)
    public BigDecimal recomputeBalance(Long userId, Long accountId) {
        Account account = requireAccount(userId, accountId);

        BigDecimal income = zeroIfNull(
                transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.INCOME));
        BigDecimal expense = zeroIfNull(
                transactionRepository.sumAmountByAccountIdAndType(accountId, TransactionType.EXPENSE));
        BigDecimal transferIn = zeroIfNull(transactionRepository.sumTransferInByAccountId(accountId));
        BigDecimal transferOut = zeroIfNull(transactionRepository.sumTransferOutByAccountId(accountId));

        return account.getInitialBalance()
                .add(income)
                .subtract(expense)
                .add(transferIn)
                .subtract(transferOut)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private Account requireAccount(Long userId, Long id) {
        return accountRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("账户不存在"));
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    // ---------------- 校验 ----------------

    private String validateName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.length() < NAME_MIN || name.length() > NAME_MAX) {
            throw ApiException.accountFieldInvalid("name", "账户名称长度需为 1 到 50 个字符");
        }
        return name;
    }

    private AccountType validateType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            throw ApiException.accountFieldInvalid("type", "账户类型不能为空");
        }
        try {
            return AccountType.valueOf(rawType.trim());
        } catch (IllegalArgumentException ex) {
            throw ApiException.accountFieldInvalid("type", "不支持的账户类型");
        }
    }

    private String validateNote(String rawNote) {
        if (rawNote == null) {
            return null;
        }
        String note = rawNote.trim();
        if (note.isEmpty()) {
            return null;
        }
        if (note.length() > 200) {
            throw ApiException.accountFieldInvalid("note", "备注最多 200 个字符");
        }
        return note;
    }

    private BigDecimal validateCreditLimit(BigDecimal rawCreditLimit) {
        if (rawCreditLimit == null) {
            return null;
        }
        BigDecimal normalized;
        try {
            normalized = rawCreditLimit.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw ApiException.accountFieldInvalid("creditLimit", "授信额度最多两位小数");
        }
        if (normalized.signum() < 0 || normalized.compareTo(BALANCE_MAX) > 0) {
            throw ApiException.accountFieldInvalid("creditLimit",
                    "授信额度需为非负且不超过 9,999,999,999,999,999.99");
        }
        return normalized;
    }

    private BigDecimal validateBalance(BigDecimal rawBalance) {
        if (rawBalance == null) {
            throw ApiException.accountFieldInvalid("initialBalance", "初始余额不能为空");
        }
        BigDecimal normalized;
        try {
            normalized = rawBalance.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw ApiException.accountFieldInvalid("initialBalance", "初始余额最多两位小数");
        }
        if (normalized.compareTo(BALANCE_MIN) < 0 || normalized.compareTo(BALANCE_MAX) > 0) {
            throw ApiException.accountFieldInvalid("initialBalance",
                    "初始余额需在 -9,999,999,999,999,999.99 至 9,999,999,999,999,999.99 之间");
        }
        return normalized;
    }
}
