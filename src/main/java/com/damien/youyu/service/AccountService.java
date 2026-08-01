package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.AccountLedger;
import com.damien.youyu.domain.AccountType;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountLedgerRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 账户服务：账户的创建、列表、修改、删除，以及账户与账本的可见性关联管理（关联需求 1、3、4、9）。
 *
 * <p>账户是独立于账本的一等实体，始终归属某个用户（owner=userId）。归属边界为 owner：越权访问
 * （他人账户）一律返回 {@code NOT_FOUND}。账户拥有单一全局真实余额，跨账本 + 转账按账户汇总重算。
 * 账户在哪些账本可用、是否对协作成员可见/显示余额，由 {@code account_ledger}（{@link AccountLedger}）表达。
 * 金额一律 {@link BigDecimal}。</p>
 */
@Service
public class AccountService {

    static final int NAME_MIN = 1;
    static final int NAME_MAX = 50;

    static final BigDecimal BALANCE_MAX = new BigDecimal("9999999999999999.99");
    static final BigDecimal BALANCE_MIN = BALANCE_MAX.negate();

    private final AccountRepository accountRepository;
    private final AccountLedgerRepository accountLedgerRepository;
    private final TransactionRepository transactionRepository;
    private final Clock clock;

    public AccountService(
            AccountRepository accountRepository,
            AccountLedgerRepository accountLedgerRepository,
            TransactionRepository transactionRepository,
            Clock clock) {
        this.accountRepository = accountRepository;
        this.accountLedgerRepository = accountLedgerRepository;
        this.transactionRepository = transactionRepository;
        this.clock = clock;
    }

    // ---------------- 创建 ----------------

    @Transactional
    public Account create(Long userId, String rawName, String rawType,
            BigDecimal rawInitialBalance, Integer sortOrder) {
        return create(userId, rawName, rawType, rawInitialBalance, sortOrder, true, false, null, null, null);
    }

    @Transactional
    public Account create(Long userId, String rawName, String rawType,
            BigDecimal rawInitialBalance, Integer sortOrder,
            boolean includeInTotal, boolean hidden, String rawNote) {
        return create(userId, rawName, rawType, rawInitialBalance, sortOrder,
                includeInTotal, hidden, rawNote, null, null);
    }

    @Transactional
    public Account create(Long userId, String rawName, String rawType,
            BigDecimal rawInitialBalance, Integer sortOrder,
            boolean includeInTotal, boolean hidden, String rawNote, BigDecimal rawCreditLimit) {
        return create(userId, rawName, rawType, rawInitialBalance, sortOrder,
                includeInTotal, hidden, rawNote, rawCreditLimit, null);
    }

    /**
     * 创建账户（含扩展字段）：校验通过后 {@code current_balance = initial_balance}。
     * 若 {@code attachLedgerId} 非空，则同时把该账户纳入该账本（默认对他人可见、显示余额），
     * 使"新建账户后立即在当前账本记账"可用。
     *
     * @throws ApiException ACCOUNT_FIELD_INVALID（名称/类型/初始余额/备注任一非法，需求 3.3）
     */
    @Transactional
    public Account create(Long userId, String rawName, String rawType,
            BigDecimal rawInitialBalance, Integer sortOrder,
            boolean includeInTotal, boolean hidden, String rawNote, BigDecimal rawCreditLimit,
            Long attachLedgerId) {
        return create(userId, rawName, rawType, rawInitialBalance, sortOrder,
                includeInTotal, hidden, rawNote, rawCreditLimit, null, null, false, attachLedgerId);
    }

    /**
     * 创建账户（含信用卡账单/还款字段）：账单日/还款日仅信用卡有意义（1-28），
     * 非信用卡一律置空、还款提醒关闭。
     *
     * @throws ApiException ACCOUNT_FIELD_INVALID（名称/类型/初始余额/备注/账单日/还款日任一非法，需求 3.3）
     */
    @Transactional
    public Account create(Long userId, String rawName, String rawType,
            BigDecimal rawInitialBalance, Integer sortOrder,
            boolean includeInTotal, boolean hidden, String rawNote, BigDecimal rawCreditLimit,
            Integer rawBillDay, Integer rawRepayDay, boolean repayReminder,
            Long attachLedgerId) {
        String name = validateName(rawName);
        AccountType type = validateType(rawType);
        BigDecimal initialBalance = validateBalance(rawInitialBalance);
        String note = validateNote(rawNote);
        BigDecimal creditLimit = validateCreditLimit(rawCreditLimit);
        Integer billDay = validateCreditDay(rawBillDay, "billDay");
        Integer repayDay = validateCreditDay(rawRepayDay, "repayDay");

        LocalDateTime now = LocalDateTime.now(clock);
        Account account = new Account();
        account.setUserId(userId);
        account.setName(name);
        account.setType(type);
        account.setInitialBalance(initialBalance);
        account.setCurrentBalance(initialBalance);
        account.setSortOrder(sortOrder == null ? 0 : sortOrder);
        account.setIncludeInTotal(includeInTotal);
        account.setHidden(hidden);
        account.setNote(note);
        boolean credit = type.isCredit();
        account.setCreditLimit(credit ? creditLimit : null);
        account.setBillDay(credit ? billDay : null);
        account.setRepayDay(credit ? repayDay : null);
        account.setRepayReminder(credit && repayReminder);
        account.setCreatedAt(now);
        account.setUpdatedAt(now);
        Account saved = accountRepository.save(account);

        if (attachLedgerId != null) {
            attach(saved.getId(), attachLedgerId, true, true, now);
        }
        return saved;
    }

    // ---------------- 列表 ----------------

    /** 列出某用户拥有的全部账户，按 sort_order、id 升序；无账户返回空列表（需求 3.5）。 */
    @Transactional(readOnly = true)
    public List<Account> list(Long userId) {
        return accountRepository.findByUserIdOrderBySortOrderAscIdAsc(userId);
    }

    // ---------------- 信用卡还款提醒 ----------------

    /** 单条还款提醒视图：下一个还款日、剩余天数、待还金额（当前欠款）。 */
    public record RepayReminderView(
            Long accountId, String name, int repayDay,
            LocalDate nextRepayDate, int daysUntil, BigDecimal owed) {
    }

    /**
     * 汇总当前用户「已开启还款提醒」的信用卡的下一个还款日与待还金额，按剩余天数升序。
     * 仅纳入信贷类型、{@code repay_reminder=true} 且已设置 {@code repay_day} 的账户。
     * 待还金额取当前欠款（{@code current_balance} 为负时取其相反数，否则为 0）。
     */
    @Transactional(readOnly = true)
    public List<RepayReminderView> repayReminders(Long userId) {
        LocalDate today = LocalDate.now(clock);
        List<RepayReminderView> out = new ArrayList<>();
        for (Account a : accountRepository.findByUserIdOrderBySortOrderAscIdAsc(userId)) {
            if (!a.getType().isCredit() || !a.isRepayReminder() || a.getRepayDay() == null) {
                continue;
            }
            LocalDate next = nextOccurrence(today, a.getRepayDay());
            int days = (int) ChronoUnit.DAYS.between(today, next);
            BigDecimal owed = a.getCurrentBalance() != null && a.getCurrentBalance().signum() < 0
                    ? a.getCurrentBalance().negate() : BigDecimal.ZERO;
            out.add(new RepayReminderView(a.getId(), a.getName(), a.getRepayDay(), next, days, owed));
        }
        out.sort(Comparator.comparingInt(RepayReminderView::daysUntil));
        return out;
    }

    /** 从今天起某「每月第 day 日」的下一次出现（含今天）；day 已保证 1-28，跨月安全。 */
    private LocalDate nextOccurrence(LocalDate today, int day) {
        LocalDate thisMonth = today.withDayOfMonth(Math.min(day, today.lengthOfMonth()));
        if (!thisMonth.isBefore(today)) {
            return thisMonth;
        }
        LocalDate nm = today.plusMonths(1);
        return nm.withDayOfMonth(Math.min(day, nm.lengthOfMonth()));
    }

    // ---------------- 修改 ----------------

    @Transactional
    public Account update(Long userId, Long id, String rawName, String rawType) {
        return update(userId, id, rawName, rawType, true, false, null, null);
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
        return update(userId, id, rawName, rawType, includeInTotal, hidden, rawNote, rawCreditLimit,
                null, null, false);
    }

    /** 修改账户（含信用卡账单/还款字段）：非信用卡类型置空账单/还款日并关闭提醒。 */
    @Transactional
    public Account update(Long userId, Long id, String rawName, String rawType,
            boolean includeInTotal, boolean hidden, String rawNote, BigDecimal rawCreditLimit,
            Integer rawBillDay, Integer rawRepayDay, boolean repayReminder) {
        Account account = requireAccount(userId, id);

        AccountType type = validateType(rawType);
        account.setName(validateName(rawName));
        account.setType(type);
        account.setIncludeInTotal(includeInTotal);
        account.setHidden(hidden);
        account.setNote(validateNote(rawNote));
        boolean credit = type.isCredit();
        account.setCreditLimit(credit ? validateCreditLimit(rawCreditLimit) : null);
        account.setBillDay(credit ? validateCreditDay(rawBillDay, "billDay") : null);
        account.setRepayDay(credit ? validateCreditDay(rawRepayDay, "repayDay") : null);
        account.setRepayReminder(credit && repayReminder);
        account.setUpdatedAt(LocalDateTime.now(clock));
        return accountRepository.save(account);
    }

    // ---------------- 删除 ----------------

    /**
     * 删除账户：仅当无任何交易引用（作为账户/源/目标）时删除，否则拒绝（需求 1.5）。
     * 删除成功级联清除该账户的全部账本关联行（需求 1.6）。
     */
    @Transactional
    public void delete(Long userId, Long id) {
        Account account = requireAccount(userId, id);
        if (transactionRepository.existsByAccountReferenced(id)) {
            throw ApiException.accountInUse();
        }
        accountLedgerRepository.deleteByAccountId(id);
        accountRepository.delete(account);
    }

    // ---------------- 账户/账本可见性关联 ----------------

    /** 把账户纳入某账本（幂等：已存在则更新标志）。仅账户 owner 可操作。 */
    @Transactional
    public AccountLedger attachToLedger(Long userId, Long accountId, Long ledgerId,
            boolean visibleToOthers, boolean showBalance) {
        requireAccount(userId, accountId);
        return attach(accountId, ledgerId, visibleToOthers, showBalance, LocalDateTime.now(clock));
    }

    /**
     * 保存账户的发卡银行 / 卡号（可空即清除）。独立于 create/update 的字段流，
     * 便于在不改动既有多重载签名的前提下扩展账户元信息。
     */
    @Transactional
    public Account saveBankInfo(Long userId, Long accountId, String issuingBank, String cardNo) {
        Account account = requireAccount(userId, accountId);
        account.setIssuingBank(trimOrNull(issuingBank, 40));
        account.setCardNo(trimOrNull(cardNo, 30));
        account.setUpdatedAt(LocalDateTime.now(clock));
        return accountRepository.save(account);
    }

    private static String trimOrNull(String s, int max) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return null;
        }
        return t.length() > max ? t.substring(0, max) : t;
    }

    /**
     * 某用户全部账户的账本参与关联（批量）：用于资产页展示每个账户"参与了哪些账本"。
     * 仅返回该用户拥有的账户的关联行；账本名称/类型由前端解析。
     */
    @Transactional(readOnly = true)
    public List<AccountLedger> ledgerLinksOfUser(Long userId) {
        List<Long> accountIds = accountRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .map(Account::getId)
                .toList();
        if (accountIds.isEmpty()) {
            return List.of();
        }
        return accountLedgerRepository.findByAccountIdIn(accountIds);
    }

    /** 更新账户在某账本的可见性标志。仅账户 owner 可操作；关联不存在返回 NOT_FOUND。 */
    @Transactional
    public AccountLedger updateVisibility(Long userId, Long accountId, Long ledgerId,
            boolean visibleToOthers, boolean showBalance) {
        requireAccount(userId, accountId);
        AccountLedger link = accountLedgerRepository.findByAccountIdAndLedgerId(accountId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("账户未纳入该账本"));
        link.setVisibleToOthers(visibleToOthers);
        link.setShowBalance(showBalance);
        return accountLedgerRepository.save(link);
    }

    /**
     * 取消账户在某账本的参与（未来不可选）。历史流水与余额影响保留。
     * 仅账户 owner 可操作。
     *
     * @return 该账户在此账本是否已有历史流水（供调用方提示）
     */
    @Transactional
    public boolean detachFromLedger(Long userId, Long accountId, Long ledgerId) {
        requireAccount(userId, accountId);
        boolean hasHistory = transactionRepository.existsByLedgerIdAndAccountReferenced(ledgerId, accountId);
        accountLedgerRepository.findByAccountIdAndLedgerId(accountId, ledgerId)
                .ifPresent(accountLedgerRepository::delete);
        return hasHistory;
    }

    // ---------------- 转交 ----------------

    /**
     * 把账户 owner 从当前用户转交给另一用户，保留余额、历史流水与账本关联行（需求 9）。
     * 仅当前 owner 可操作。
     */
    @Transactional
    public Account transferOwnership(Long userId, Long accountId, Long newOwnerUserId) {
        Account account = requireAccount(userId, accountId);
        account.setUserId(newOwnerUserId);
        account.setUpdatedAt(LocalDateTime.now(clock));
        return accountRepository.save(account);
    }

    // ---------------- 余额重算 ----------------

    /**
     * 由初始余额与全量流水聚合重算某账户应有余额（跨账本 + 转账按账户汇总，需求 1.4、Property 1）。
     */
    @Transactional(readOnly = true)
    public BigDecimal recomputeBalance(Long userId, Long accountId) {
        Account account = requireAccount(userId, accountId);
        return recompute(account);
    }

    /**
     * 按 accountId 重算并写回余额（owner 无关，供账本删除后重算受影响账户，需求 8.2）。
     * 账户不存在则忽略。
     */
    @Transactional
    public void recomputeAndSave(Long accountId) {
        accountRepository.findById(accountId).ifPresent(account -> {
            account.setCurrentBalance(recompute(account));
            account.setUpdatedAt(LocalDateTime.now(clock));
            accountRepository.save(account);
        });
    }

    private BigDecimal recompute(Account account) {
        Long accountId = account.getId();
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

    /** 定位账户并校验归属（owner）；不匹配返回 NOT_FOUND。 */
    public Account requireAccount(Long userId, Long id) {
        return accountRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("账户不存在"));
    }

    /** 读取账户在某账本的可见性关联（owner 视角）；未纳入返回空。仅账户 owner 可查询。 */
    @Transactional(readOnly = true)
    public java.util.Optional<AccountLedger> visibilityOf(Long userId, Long accountId, Long ledgerId) {
        requireAccount(userId, accountId);
        return accountLedgerRepository.findByAccountIdAndLedgerId(accountId, ledgerId);
    }

    /** 建立或更新账户在账本的关联行（幂等）。 */
    private AccountLedger attach(Long accountId, Long ledgerId,
            boolean visibleToOthers, boolean showBalance, LocalDateTime now) {
        AccountLedger link = accountLedgerRepository.findByAccountIdAndLedgerId(accountId, ledgerId)
                .orElseGet(() -> {
                    AccountLedger al = new AccountLedger();
                    al.setAccountId(accountId);
                    al.setLedgerId(ledgerId);
                    al.setCreatedAt(now);
                    return al;
                });
        link.setVisibleToOthers(visibleToOthers);
        link.setShowBalance(showBalance);
        return accountLedgerRepository.save(link);
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

    /** 校验账单日/还款日：null 放行（未设置）；否则须为 1-28（规避大小月/闰月边界）。 */
    private Integer validateCreditDay(Integer day, String field) {
        if (day == null) {
            return null;
        }
        if (day < 1 || day > 28) {
            throw ApiException.accountFieldInvalid(field, "日期需为 1 到 28");
        }
        return day;
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
