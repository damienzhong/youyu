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
 * <p>核心约束：</p>
 * <ul>
 *   <li>创建校验（需求 3.1、3.3）：名称去空白后 1-50；类型属于受支持枚举
 *       （{@link AccountType}：CASH/BANK_CARD/ALIPAY/WECHAT/CREDIT_CARD）；初始余额在
 *       [-9,999,999,999,999,999.99, 9,999,999,999,999,999.99] 且最多两位小数。任一不满足即拒绝、
 *       不持久化，并返回指明具体无效字段的 {@code ACCOUNT_FIELD_INVALID}。创建时
 *       {@code current_balance} 初始化为初始余额。</li>
 *   <li>信用卡允许负余额（需求 3.4）：初始余额范围本身对称含负值，各类型均可取范围内的负值。</li>
 *   <li>列表按 {@code sort_order} 返回本人全部账户，无账户返回空列表（需求 3.5）。</li>
 *   <li>修改仅改名称/类型并保留余额（需求 3.6）。</li>
 *   <li>删除：仍关联交易的账户拒绝删除（{@code ACCOUNT_IN_USE}，需求 3.7）；无交易则删除（需求 3.8）。</li>
 * </ul>
 *
 * <p>所有操作均按会话 {@code userId} 隔离：写入以传入 userId 为准，读取/修改/删除他人账户
 * 一律返回 {@code NOT_FOUND}（需求 2.3、2.4）。金额一律 {@link BigDecimal}（需求 3.9）。</p>
 */
@Service
public class AccountService {

    /** 账户名称去空白后允许的长度区间（需求 3.1）。 */
    static final int NAME_MIN = 1;
    static final int NAME_MAX = 50;

    /** 初始余额允许范围（DECIMAL(18,2)，需求 3.1）。 */
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

    /**
     * 创建账户：校验通过后 {@code current_balance = initial_balance}。
     *
     * @throws ApiException ACCOUNT_FIELD_INVALID（名称/类型/初始余额任一非法，需求 3.3）
     */
    @Transactional
    public Account create(Long userId, String rawName, String rawType,
            BigDecimal rawInitialBalance, Integer sortOrder) {
        return create(userId, rawName, rawType, rawInitialBalance, sortOrder, true, false, null, null);
    }

    /** 向后兼容重载（不含信用额度）。 */
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
     * @param includeInTotal 余额是否计入净资产（默认 true）
     * @param hidden         是否隐藏账户（默认 false）
     * @param rawNote        账户备注（可选，<=200）
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
        // 需求 2.2：写入强制以会话 userId 为准。
        account.setUserId(userId);
        account.setName(name);
        account.setType(type);
        account.setInitialBalance(initialBalance);
        // 需求 3.1：current_balance 初始化为初始余额。
        account.setCurrentBalance(initialBalance);
        account.setSortOrder(sortOrder == null ? 0 : sortOrder);
        account.setIncludeInTotal(includeInTotal);
        account.setHidden(hidden);
        account.setNote(note);
        // 信用额度仅信用卡有意义，非信用卡忽略传入值。
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

    /**
     * 修改账户名称/类型并保留余额（需求 3.6）。
     *
     * @throws ApiException NOT_FOUND（账户不存在或不属于当前用户，需求 2.4）；
     *                      ACCOUNT_FIELD_INVALID（名称/类型非法，需求 3.3）
     */
    @Transactional
    public Account update(Long userId, Long id, String rawName, String rawType) {
        Account account = accountRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("账户不存在"));

        String name = validateName(rawName);
        AccountType type = validateType(rawType);

        account.setName(name);
        account.setType(type);
        // 需求 3.6：保留 current_balance 与 initial_balance 不变。
        account.setUpdatedAt(LocalDateTime.now(clock));
        return accountRepository.save(account);
    }

    /**
     * 修改账户名称/类型及扩展字段（计入总资产/隐藏/备注），保留余额（需求 3.6）。
     *
     * @throws ApiException NOT_FOUND / ACCOUNT_FIELD_INVALID
     */
    @Transactional
    public Account update(Long userId, Long id, String rawName, String rawType,
            boolean includeInTotal, boolean hidden, String rawNote) {
        return update(userId, id, rawName, rawType, includeInTotal, hidden, rawNote, null);
    }

    /**
     * 修改账户名称/类型及扩展字段（计入总资产/隐藏/备注/信用额度），保留余额（需求 3.6）。
     *
     * @throws ApiException NOT_FOUND / ACCOUNT_FIELD_INVALID
     */
    @Transactional
    public Account update(Long userId, Long id, String rawName, String rawType,
            boolean includeInTotal, boolean hidden, String rawNote, BigDecimal rawCreditLimit) {
        Account account = accountRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("账户不存在"));

        String name = validateName(rawName);
        AccountType type = validateType(rawType);
        String note = validateNote(rawNote);
        BigDecimal creditLimit = validateCreditLimit(rawCreditLimit);

        account.setName(name);
        account.setType(type);
        account.setIncludeInTotal(includeInTotal);
        account.setHidden(hidden);
        account.setNote(note);
        // 信用额度仅信用卡有意义；改成非信用卡则清空。
        account.setCreditLimit(type == AccountType.CREDIT_CARD ? creditLimit : null);
        // 需求 3.6：保留 current_balance 与 initial_balance 不变。
        account.setUpdatedAt(LocalDateTime.now(clock));
        return accountRepository.save(account);
    }

    /**
     * 删除账户：仅当无任何交易引用（作为账户/源/目标）时删除，否则拒绝。
     *
     * @throws ApiException NOT_FOUND（账户不存在或不属于当前用户，需求 2.4）；
     *                      ACCOUNT_IN_USE（存在关联交易，需求 3.7）
     */
    @Transactional
    public void delete(Long userId, Long id) {
        Account account = accountRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("账户不存在"));

        if (transactionRepository.existsByUserIdAndAccountReferenced(userId, id)) {
            // 需求 3.7：有交易的账户禁止删除，账户与余额保持不变。
            throw ApiException.accountInUse();
        }
        // 需求 3.8：无交易的账户可删除。
        accountRepository.delete(account);
    }

    /**
     * 由初始余额与全量流水聚合重算某账户应有余额（需求 4.13、Property 1）。
     *
     * <p>重算公式（余额守恒不变式）：</p>
     * <pre>
     *   recomputed == initial_balance
     *                 + Σ收入(以该账户为 account)
     *                 − Σ支出(以该账户为 account)
     *                 + Σ转账(以该账户为 destination，流入)
     *                 − Σ转账(以该账户为 source，流出)
     * </pre>
     *
     * <p>在正常运作下，重算结果应恒等于随流水事务性更新的 {@code current_balance}。本方法用于：
     * (a) 属性测试验证守恒；(b) 内部对账/自愈；(c) 导入还原后一致性校验。聚合以 {@link BigDecimal}
     * 精确求和，无匹配流水时按 0 处理，结果统一缩放到 2 位小数（DECIMAL(18,2)）。</p>
     *
     * <p>按会话 {@code userId} 隔离：账户不存在或不属于当前用户返回 {@code NOT_FOUND}（需求 2.4）。</p>
     *
     * @throws ApiException NOT_FOUND（账户不存在或不属于当前用户）
     */
    @Transactional(readOnly = true)
    public BigDecimal recomputeBalance(Long userId, Long accountId) {
        Account account = accountRepository.findByIdAndUserId(accountId, userId)
                .orElseThrow(() -> ApiException.notFound("账户不存在"));

        BigDecimal income = zeroIfNull(
                transactionRepository.sumAmountByUserIdAndAccountIdAndType(
                        userId, accountId, TransactionType.INCOME));
        BigDecimal expense = zeroIfNull(
                transactionRepository.sumAmountByUserIdAndAccountIdAndType(
                        userId, accountId, TransactionType.EXPENSE));
        BigDecimal transferIn = zeroIfNull(
                transactionRepository.sumTransferInByUserIdAndAccountId(userId, accountId));
        BigDecimal transferOut = zeroIfNull(
                transactionRepository.sumTransferOutByUserIdAndAccountId(userId, accountId));

        return account.getInitialBalance()
                .add(income)
                .subtract(expense)
                .add(transferIn)
                .subtract(transferOut)
                .setScale(2, RoundingMode.HALF_UP);
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

    /** 校验信用额度（可空）：非负、最多两位小数、不超过金额上限。 */
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
        // 最多两位小数：无需舍入即可缩放到 2 位则合法，否则拒绝（需求 3.1）。
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
