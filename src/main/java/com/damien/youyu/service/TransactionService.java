package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.Category;
import com.damien.youyu.domain.CategoryKind;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.TransactionRepository;

/**
 * 交易服务：收支记账、余额调整、转账，以及事务性余额更新（关联需求 4、5、6）。
 *
 * <p>核心约束：</p>
 * <ul>
 *   <li>支出（{@code expense}）：对应账户 {@code current_balance -= amount}，归属账本 {@code ledgerId}。</li>
 *   <li>收入（{@code income}）：对应账户 {@code current_balance += amount}，归属账本 {@code ledgerId}。</li>
 *   <li>转账（{@code transfer}）：脱离账本（{@code ledger_id=null}），源/目标均为记账人本人账户，
 *       同一事务内 {@code source -= amount} 且 {@code destination += amount}，记入账户明细，不计入任何账本收支。</li>
 * </ul>
 *
 * <p>收支记账所用账户须在该账本对记账人可用（自己纳入的或他人暴露的），由 {@link LedgerAccountResolver}
 * 校验并加行级悲观写锁；账户余额为全局单一值（跨账本 + 转账汇总）。校验一律前置于任何余额变更之前，
 * 失败即拒绝且零副作用。涉及多账户按 id 升序加锁降低死锁风险。金额一律 {@link BigDecimal}。</p>
 */
@Service
public class TransactionService {

    /** 交易金额允许范围（DECIMAL(18,2)）。 */
    static final BigDecimal AMOUNT_MIN = new BigDecimal("0.01");
    static final BigDecimal AMOUNT_MAX = new BigDecimal("9999999999999999.99");

    static final int NOTE_MAX = 200;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final LedgerAccountResolver accountResolver;
    private final Clock clock;
    private final GrowthSettlementTrigger growthSettlementTrigger;

    public TransactionService(
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            LedgerAccountResolver accountResolver,
            Clock clock,
            GrowthSettlementTrigger growthSettlementTrigger) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.accountResolver = accountResolver;
        this.clock = clock;
        this.growthSettlementTrigger = growthSettlementTrigger;
    }

    // ---------------- 收支记账 ----------------

    @Transactional
    public Transaction create(Long userId, Long ledgerId, String rawType, BigDecimal rawAmount,
            Long accountId, Long categoryId, LocalDateTime occurredAt, String rawNote) {
        return create(userId, ledgerId, rawType, rawAmount, accountId, categoryId, occurredAt,
                rawNote, null, null, null);
    }

    @Transactional
    public Transaction create(Long userId, Long ledgerId, String rawType, BigDecimal rawAmount,
            Long accountId, Long categoryId, LocalDateTime occurredAt, String rawNote,
            Long createdByOverride) {
        return create(userId, ledgerId, rawType, rawAmount, accountId, categoryId, occurredAt,
                rawNote, createdByOverride, null, null);
    }

    /**
     * 创建一笔支出/收入交易，并在同一事务内事务性更新账户余额。转账请用 {@link #transfer}。
     *
     * @throws ApiException FIELD_REQUIRED / AMOUNT_INVALID / TRANSACTION_TYPE_INVALID / NOT_FOUND
     */
    @Transactional
    public Transaction create(Long userId, Long ledgerId, String rawType, BigDecimal rawAmount,
            Long accountId, Long categoryId, LocalDateTime occurredAt, String rawNote,
            Long createdByOverride, Long projectId, Long merchantId) {

        TransactionType type = validateIncomeExpenseType(rawType);
        BigDecimal amount = validateAmount(rawAmount);
        String note = validateNote(rawNote);
        if (accountId == null) {
            throw ApiException.fieldRequired("accountId");
        }
        if (categoryId == null) {
            throw ApiException.fieldRequired("categoryId");
        }
        validateCategoryExists(ledgerId, categoryId);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime when = occurredAt == null ? now : occurredAt;

        // 账户须在该账本对记账人可用；加锁并按方向更新全局余额。
        Account account = accountResolver.lockUsableAccount(userId, ledgerId, accountId);
        BigDecimal delta = type == TransactionType.INCOME ? amount : amount.negate();
        account.setCurrentBalance(account.getCurrentBalance().add(delta));
        account.setUpdatedAt(now);
        accountRepository.save(account);

        Transaction tx = new Transaction();
        tx.setLedgerId(ledgerId);
        tx.setCreatedBy(createdByOverride != null ? createdByOverride : userId);
        tx.setProjectId(projectId);
        tx.setMerchantId(merchantId);
        tx.setCreatedAt(now);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setNote(note);
        tx.setOccurredAt(when);
        tx.setUpdatedAt(now);
        tx.setAccountId(accountId);
        tx.setCategoryId(categoryId);
        Transaction saved = transactionRepository.save(tx);

        // 挂结算：这是唯一产生「有效记账交易」（type ∈ {expense,income} 且 ledger_id 非空）的路径，
        // 故成长结算只需挂在此处（需求 9.1、9.2、9.3、7.1）。
        // 归属键取 tx.getCreatedBy() 而非 userId：协作代记时记账人（createdByOverride）可能不是会话用户，
        // 经验应归属真正的记账人。
        // 「不触发路径天然满足」，无需额外判定：transfer 与 adjustBalance 各自建行且 ledger_id 为 null，
        // update / delete / restore / purge 不新增行，因此都不构成「新增有效记账交易」。
        growthSettlementTrigger.requestSettlement(saved.getCreatedBy());
        return saved;
    }

    // ---------------- 转账（脱离账本）----------------

    /**
     * 转账：账户之间的资金转移，脱离账本（{@code ledger_id=null}），记入账户明细，不计入任何账本收支。
     * 源/目标须均为记账人本人拥有的账户且不相等（需求 6）。
     *
     * @throws ApiException FIELD_REQUIRED / AMOUNT_INVALID / TRANSFER_SAME_ACCOUNT / NOT_FOUND
     */
    @Transactional
    public Transaction transfer(Long userId, Long sourceAccountId, Long destinationAccountId,
            BigDecimal rawAmount, LocalDateTime occurredAt, String rawNote) {
        BigDecimal amount = validateAmount(rawAmount);
        String note = validateNote(rawNote);
        if (sourceAccountId == null) {
            throw ApiException.fieldRequired("sourceAccountId");
        }
        if (destinationAccountId == null) {
            throw ApiException.fieldRequired("destinationAccountId");
        }
        if (sourceAccountId.equals(destinationAccountId)) {
            throw ApiException.transferSameAccount();
        }

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime when = occurredAt == null ? now : occurredAt;

        Map<Long, BigDecimal> deltas = new TreeMap<>();
        deltas.put(sourceAccountId, amount.negate());
        deltas.put(destinationAccountId, amount);
        Map<Long, Account> locked = lockOwnedAccounts(deltas.keySet(), userId);
        applyDeltas(locked, deltas, now);

        Transaction tx = new Transaction();
        tx.setLedgerId(null);
        tx.setCreatedBy(userId);
        tx.setCreatedAt(now);
        tx.setType(TransactionType.TRANSFER);
        tx.setAmount(amount);
        tx.setNote(note);
        tx.setOccurredAt(when);
        tx.setUpdatedAt(now);
        tx.setSourceAccountId(sourceAccountId);
        tx.setDestinationAccountId(destinationAccountId);
        return transactionRepository.save(tx);
    }

    /**
     * 编辑一笔已有转账（脱离账本）：同一事务内先回滚原转账对两端账户的影响，再应用新（已校验）转账的影响，
     * 就地更新该行而非新增。源/目标须均为记账人本人账户且不相等（需求 6）。目标不存在、非本人或非转账则
     * {@code NOT_FOUND}。修复：详情弹层「修改」把转账带入编辑态，旧路径仅有新建转账接口，导致保存后多出一笔。
     *
     * @throws ApiException FIELD_REQUIRED / AMOUNT_INVALID / TRANSFER_SAME_ACCOUNT / NOT_FOUND
     */
    @Transactional
    public Transaction updateTransfer(Long userId, Long id, Long sourceAccountId,
            Long destinationAccountId, BigDecimal rawAmount, LocalDateTime occurredAt, String rawNote) {
        Transaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("交易不存在"));
        // 仅本人的转账可经此路径编辑（转账脱离账本，归属只认记账人 createdBy）。
        if (tx.getType() != TransactionType.TRANSFER || !userId.equals(tx.getCreatedBy())) {
            throw ApiException.notFound("交易不存在");
        }
        BigDecimal amount = validateAmount(rawAmount);
        String note = validateNote(rawNote);
        if (sourceAccountId == null) {
            throw ApiException.fieldRequired("sourceAccountId");
        }
        if (destinationAccountId == null) {
            throw ApiException.fieldRequired("destinationAccountId");
        }
        if (sourceAccountId.equals(destinationAccountId)) {
            throw ApiException.transferSameAccount();
        }

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime when = occurredAt == null ? now : occurredAt;

        // 净增量 = 回滚原转账（两端取反）+ 应用新转账（源 -amount、目标 +amount），按账户合并后一次落地，
        // 兼容改动源/目标账户或金额的所有情形（含新旧账户交叉）。转账账户按本人拥有加锁（脱离账本）。
        Map<Long, BigDecimal> net = new TreeMap<>();
        rollbackDeltas(tx).forEach((acc, d) -> net.merge(acc, d, BigDecimal::add));
        net.merge(sourceAccountId, amount.negate(), BigDecimal::add);
        net.merge(destinationAccountId, amount, BigDecimal::add);

        Map<Long, Account> locked = lockOwnedAccounts(net.keySet(), userId);
        applyDeltas(locked, net, now);

        tx.setAmount(amount);
        tx.setNote(note);
        tx.setOccurredAt(when);
        tx.setUpdatedAt(now);
        tx.setSourceAccountId(sourceAccountId);
        tx.setDestinationAccountId(destinationAccountId);
        return transactionRepository.save(tx);
    }

    // ---------------- 余额调整 ----------------

    /** 系统「余额调整」分类名（补差流水归入该分类，便于报表识别与过滤）。 */
    public static final String ADJUST_CATEGORY_NAME = "余额调整";

    /**
     * 余额调整：把某账户当前余额校准到 {@code targetBalance}，用一笔补差流水（收入=调增 / 支出=调减）落地，
     * 归入系统「余额调整」分类（按需惰性创建）。目标与当前一致则不产生流水、返回 null。
     */
    @Transactional
    public Transaction adjustBalance(Long userId, Long ledgerId, Long accountId,
            BigDecimal rawTarget, LocalDateTime occurredAt, String rawNote) {
        if (accountId == null) {
            throw ApiException.fieldRequired("accountId");
        }
        // 校验账户在该账本可用并读取当前余额（不加锁，随后 create 内再加锁更新）。
        Account account = accountResolver.lockUsableAccount(userId, ledgerId, accountId);

        BigDecimal target = validateBalance(rawTarget);
        BigDecimal delta = target.subtract(account.getCurrentBalance()).setScale(2, RoundingMode.HALF_UP);
        if (delta.signum() == 0) {
            return null;
        }
        TransactionType type = delta.signum() > 0 ? TransactionType.INCOME : TransactionType.EXPENSE;
        CategoryKind kind = type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        // 需满足 ck_tx_fields：收支必须带分类。仍归入系统「余额调整」分类。
        Long categoryId = ensureAdjustCategory(userId, ledgerId, kind);
        String note = (rawNote == null || rawNote.isBlank()) ? null : rawNote.trim();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime when = occurredAt == null ? now : occurredAt;

        // 补差直接更新账户余额（账户为用户级资产）。
        account.setCurrentBalance(account.getCurrentBalance().add(delta));
        account.setUpdatedAt(now);
        accountRepository.save(account);

        // 落一笔「账户级」补差流水：脱离账本(ledger_id=null)，不计入任何账本收支/报表，仅在账户流水呈现
        // （与转账一致的账户级语义）。收支类型 + ledger_id 为空即「余额调整」，前端据此识别与展示。
        Transaction tx = new Transaction();
        tx.setLedgerId(null);
        tx.setCreatedBy(userId);
        tx.setCreatedAt(now);
        tx.setType(type);
        tx.setAmount(delta.abs());
        tx.setNote(note);
        tx.setOccurredAt(when);
        tx.setUpdatedAt(now);
        tx.setAccountId(accountId);
        tx.setCategoryId(categoryId);
        return transactionRepository.save(tx);
    }

    private Long ensureAdjustCategory(Long userId, Long ledgerId, CategoryKind kind) {
        return categoryRepository
                .findFirstByLedgerIdAndKindAndParentIdIsNullAndName(ledgerId, kind, ADJUST_CATEGORY_NAME)
                .map(Category::getId)
                .orElseGet(() -> {
                    LocalDateTime now = LocalDateTime.now(clock);
                    Category c = new Category();
                    c.setUserId(userId);
                    c.setLedgerId(ledgerId);
                    c.setParentId(null);
                    c.setKind(kind);
                    c.setName(ADJUST_CATEGORY_NAME);
                    c.setIcon(CategoryIcons.guess(ADJUST_CATEGORY_NAME, kind));
                    c.setCreatedAt(now);
                    c.setUpdatedAt(now);
                    return categoryRepository.save(c).getId();
                });
    }

    private BigDecimal validateBalance(BigDecimal rawBalance) {
        if (rawBalance == null) {
            throw ApiException.fieldRequired("balance");
        }
        BigDecimal normalized;
        try {
            normalized = rawBalance.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw ApiException.amountInvalid();
        }
        if (normalized.compareTo(AMOUNT_MAX.negate()) < 0 || normalized.compareTo(AMOUNT_MAX) > 0) {
            throw ApiException.amountInvalid();
        }
        return normalized;
    }

    // ---------------- 修改 ----------------

    @Transactional
    public Transaction update(Long userId, Long ledgerId, Long id, String rawType, BigDecimal rawAmount,
            Long accountId, Long categoryId, LocalDateTime occurredAt, String rawNote) {
        return update(userId, ledgerId, id, rawType, rawAmount, accountId, categoryId, occurredAt,
                rawNote, null, null);
    }

    /**
     * 修改一笔已有收支交易：同一事务内先回滚原交易对余额的影响，再应用新（已校验）交易的影响。
     * 目标交易不存在或不属于该账本则拒绝且不改余额。转账不经此路径。
     */
    @Transactional
    public Transaction update(Long userId, Long ledgerId, Long id, String rawType, BigDecimal rawAmount,
            Long accountId, Long categoryId, LocalDateTime occurredAt, String rawNote,
            Long projectId, Long merchantId) {

        Transaction tx = transactionRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("交易不存在"));

        TransactionType type = validateIncomeExpenseType(rawType);
        BigDecimal amount = validateAmount(rawAmount);
        String note = validateNote(rawNote);
        if (accountId == null) {
            throw ApiException.fieldRequired("accountId");
        }
        if (categoryId == null) {
            throw ApiException.fieldRequired("categoryId");
        }
        validateCategoryExists(ledgerId, categoryId);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime when = occurredAt == null ? now : occurredAt;

        // 回滚原影响（负增量）+ 应用新影响（正增量），按账户合并净增量。
        Map<Long, BigDecimal> net = new TreeMap<>();
        if (tx.getAccountId() != null) {
            BigDecimal old = tx.getType() == TransactionType.INCOME ? tx.getAmount() : tx.getAmount().negate();
            net.merge(tx.getAccountId(), old.negate(), BigDecimal::add);
        }
        BigDecimal delta = type == TransactionType.INCOME ? amount : amount.negate();
        net.merge(accountId, delta, BigDecimal::add);

        Map<Long, Account> locked = lockUsableAccounts(net.keySet(), userId, ledgerId);
        applyDeltas(locked, net, now);

        tx.setProjectId(projectId);
        tx.setMerchantId(merchantId);
        tx.setType(type);
        tx.setAmount(amount);
        tx.setNote(note);
        tx.setOccurredAt(when);
        tx.setUpdatedAt(now);
        tx.setAccountId(accountId);
        tx.setCategoryId(categoryId);
        tx.setSourceAccountId(null);
        tx.setDestinationAccountId(null);
        return transactionRepository.save(tx);
    }

    // ---------------- 删除 / 回收站 ----------------

    /**
     * 软删除一笔收支交易：同一事务内回滚原交易对余额的影响，再置 deleted_at 移入回收站。
     * 目标交易不存在或不属于该账本则拒绝且不改余额。
     */
    @Transactional
    public void delete(Long userId, Long ledgerId, Long id) {
        Transaction tx = requireOwnedTransaction(userId, ledgerId, id);

        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, BigDecimal> rollback = rollbackDeltas(tx);
        // 账本内交易：账户须在该账本可用；转账/余额调整（脱离账本）：账户按本人拥有加锁，
        // 与转账创建时的 lockOwnedAccounts 口径一致（其账户未必挂在当前账本，不能用账本可用性校验）。
        Map<Long, Account> locked = lockForRollback(tx, rollback.keySet(), userId, ledgerId);
        applyDeltas(locked, rollback, now);

        tx.setDeletedAt(now);
        tx.setUpdatedAt(now);
        transactionRepository.save(tx);
    }

    /** 回滚加锁：账本内交易按账本可用性加锁，账户级记录（脱离账本）按本人拥有加锁。 */
    private Map<Long, Account> lockForRollback(
            Transaction tx, Collection<Long> accountIds, Long userId, Long ledgerId) {
        return tx.getLedgerId() != null
                ? lockUsableAccounts(accountIds, userId, ledgerId)
                : lockOwnedAccounts(accountIds, userId);
    }

    /** 列出某账本回收站记录（已软删除），按删除时间倒序。 */
    @Transactional(readOnly = true)
    public java.util.List<Transaction> listDeleted(Long ledgerId) {
        return transactionRepository.findDeletedByLedgerId(ledgerId);
    }

    /**
     * 从回收站恢复一笔收支交易：重新应用其对余额的影响并清空 deleted_at。
     */
    @Transactional
    public Transaction restore(Long userId, Long ledgerId, Long id) {
        Transaction tx = transactionRepository.findRawByIdAndLedgerId(id, ledgerId)
                .filter(t -> t.getDeletedAt() != null)
                .orElseThrow(() -> ApiException.notFound("回收站记录不存在"));

        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, BigDecimal> deltas = applyDeltasOf(tx);
        Map<Long, Account> locked = lockUsableAccounts(deltas.keySet(), userId, ledgerId);
        applyDeltas(locked, deltas, now);

        tx.setDeletedAt(null);
        tx.setUpdatedAt(now);
        return transactionRepository.save(tx);
    }

    /** 彻底删除回收站中的一笔交易（物理删行）。余额已在软删时回滚，此处不再变动余额。 */
    @Transactional
    public void purge(Long ledgerId, Long id) {
        Transaction tx = transactionRepository.findRawByIdAndLedgerId(id, ledgerId)
                .filter(t -> t.getDeletedAt() != null)
                .orElseThrow(() -> ApiException.notFound("回收站记录不存在"));
        transactionRepository.delete(tx);
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Transaction> list(
            Long ledgerId, org.springframework.data.domain.Pageable pageable) {
        return transactionRepository.findByLedgerIdOrderByOccurredAtDescIdDesc(ledgerId, pageable);
    }

    @Transactional(readOnly = true)
    public java.util.List<Transaction> listByRange(Long ledgerId, LocalDateTime from, LocalDateTime to) {
        return transactionRepository
                .findByLedgerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
                        ledgerId, from, to);
    }

    @Transactional(readOnly = true)
    public java.util.List<Transaction> listByProject(Long ledgerId, Long projectId) {
        return transactionRepository.findByLedgerIdAndProjectIdOrderByOccurredAtDescIdDesc(ledgerId, projectId);
    }

    @Transactional(readOnly = true)
    public java.util.List<Transaction> listByMerchant(Long ledgerId, Long merchantId) {
        return transactionRepository.findByLedgerIdAndMerchantIdOrderByOccurredAtDescIdDesc(ledgerId, merchantId);
    }

    @Transactional(readOnly = true)
    public java.util.List<Transaction> listByTag(Long ledgerId, Long tagId) {
        return transactionRepository.findByLedgerIdAndTagId(ledgerId, tagId);
    }

    @Transactional(readOnly = true)
    public Transaction get(Long ledgerId, Long id) {
        return transactionRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("交易不存在"));
    }

    /**
     * 单条读取交易详情（供详情弹层）：
     * <ul>
     *   <li>账本内收支交易（{@code ledger_id} 非空）：须归属当前账本，与既有 {@link #get} 一致（多账本隔离）。</li>
     *   <li>转账 / 余额调整等「账户级」记录（{@code ledger_id} 为空，脱离账本）：按记账人
     *       （{@code createdBy}）归属校验，仅本人可读。</li>
     * </ul>
     * 二者均不匹配则 {@code NOT_FOUND}。修复：转账/余额调整在账户明细可见，但因 {@code ledger_id}
     * 为空，旧的按账本过滤读取必然 404（表现为「流水存在但详情报交易不存在」）。
     */
    @Transactional(readOnly = true)
    public Transaction getForUser(Long userId, Long ledgerId, Long id) {
        return requireOwnedTransaction(userId, ledgerId, id);
    }

    /**
     * 归属判定并返回目标交易：账本内交易（{@code ledger_id} 非空）须归属当前账本（多账本隔离）；
     * 转账 / 余额调整等账户级记录（{@code ledger_id} 为空、脱离账本）按记账人 {@code createdBy} 归属，
     * 仅本人可操作。均不匹配则 {@code NOT_FOUND}。供详情读取与删除等按 id 定位的写操作共用。
     */
    private Transaction requireOwnedTransaction(Long userId, Long ledgerId, Long id) {
        Transaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("交易不存在"));
        boolean owned = tx.getLedgerId() != null
                ? tx.getLedgerId().equals(ledgerId)
                : userId.equals(tx.getCreatedBy());
        if (!owned) {
            throw ApiException.notFound("交易不存在");
        }
        return tx;
    }

    /**
     * 账户明细（需求 5）：账户 owner 查看其全部流水（跨账本 + 转账）；协作账本内其他成员仅见该账户
     * 在当前账本内的流水。账户不存在或对该用户在此账本不可见则 {@code NOT_FOUND}。
     */
    @Transactional(readOnly = true)
    public java.util.List<Transaction> listAccountDetail(Long userId, Long ledgerId, Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> ApiException.notFound("账户不存在"));
        if (account.getUserId().equals(userId)) {
            return transactionRepository.findByAccountReferencedOrderByTime(accountId);
        }
        if (!accountResolver.visible(userId, ledgerId, account)) {
            throw ApiException.notFound("账户不存在");
        }
        return transactionRepository.findByLedgerIdAndAccountReferencedOrderByTime(ledgerId, accountId);
    }

    /**
     * 快速记账默认账户（需求 7）：优先返回该用户在此账本上一笔记账所用账户（若仍在可选集内），
     * 否则回退到可选集中排序第一的账户；可选集为空返回 null。
     */
    @Transactional(readOnly = true)
    public Account defaultAccountForEntry(Long userId, Long ledgerId) {
        java.util.List<Account> selectable = accountResolver.selectableAccounts(userId, ledgerId);
        if (selectable.isEmpty()) {
            return null;
        }
        Long lastAccountId = transactionRepository
                .findFirstByLedgerIdAndCreatedByOrderByCreatedAtDescIdDesc(ledgerId, userId)
                .map(Transaction::getAccountId)
                .orElse(null);
        if (lastAccountId != null) {
            for (Account a : selectable) {
                if (a.getId().equals(lastAccountId)) {
                    return a;
                }
            }
        }
        return selectable.get(0);
    }

    // ---------------- 校验 ----------------

    private TransactionType validateIncomeExpenseType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            throw ApiException.fieldRequired("type");
        }
        TransactionType type;
        try {
            type = TransactionType.fromCode(rawType.trim());
        } catch (IllegalArgumentException ex) {
            throw new ApiException("TRANSACTION_TYPE_INVALID",
                    org.springframework.http.HttpStatus.BAD_REQUEST, "不支持的交易类型", "type");
        }
        if (type == TransactionType.TRANSFER) {
            throw new ApiException("TRANSACTION_TYPE_INVALID",
                    org.springframework.http.HttpStatus.BAD_REQUEST, "转账请使用账户转账接口", "type");
        }
        return type;
    }

    private BigDecimal validateAmount(BigDecimal rawAmount) {
        if (rawAmount == null) {
            throw ApiException.fieldRequired("amount");
        }
        BigDecimal normalized;
        try {
            normalized = rawAmount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw ApiException.amountInvalid();
        }
        if (normalized.compareTo(AMOUNT_MIN) < 0 || normalized.compareTo(AMOUNT_MAX) > 0) {
            throw ApiException.amountInvalid();
        }
        return normalized;
    }

    private String validateNote(String rawNote) {
        if (rawNote == null) {
            return null;
        }
        if (rawNote.length() > NOTE_MAX) {
            throw new ApiException("NOTE_TOO_LONG",
                    org.springframework.http.HttpStatus.BAD_REQUEST, "备注最多 200 个字符", "note");
        }
        return rawNote;
    }

    private void validateCategoryExists(Long ledgerId, Long categoryId) {
        categoryRepository.findByIdAndLedgerId(categoryId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("分类不存在"));
    }

    // ---------------- 余额影响与账户加锁 ----------------

    /** 某收支/转账交易对各账户余额的"应用"增量（正=增、负=减）。 */
    private Map<Long, BigDecimal> applyDeltasOf(Transaction tx) {
        Map<Long, BigDecimal> deltas = new TreeMap<>();
        switch (tx.getType()) {
            case EXPENSE -> deltas.merge(tx.getAccountId(), tx.getAmount().negate(), BigDecimal::add);
            case INCOME -> deltas.merge(tx.getAccountId(), tx.getAmount(), BigDecimal::add);
            case TRANSFER -> {
                deltas.merge(tx.getSourceAccountId(), tx.getAmount().negate(), BigDecimal::add);
                deltas.merge(tx.getDestinationAccountId(), tx.getAmount(), BigDecimal::add);
            }
            default -> { }
        }
        return deltas;
    }

    /** 回滚某交易影响的增量（应用增量取反）。 */
    private Map<Long, BigDecimal> rollbackDeltas(Transaction tx) {
        Map<Long, BigDecimal> deltas = new TreeMap<>();
        applyDeltasOf(tx).forEach((acc, delta) -> deltas.merge(acc, delta.negate(), BigDecimal::add));
        return deltas;
    }

    /** 对给定账户按 id 升序在该账本内加锁校验（收支记账用）。 */
    private Map<Long, Account> lockUsableAccounts(Collection<Long> accountIds, Long userId, Long ledgerId) {
        Map<Long, Account> locked = new LinkedHashMap<>();
        accountIds.stream().sorted().forEach(accId ->
                locked.put(accId, accountResolver.lockUsableAccount(userId, ledgerId, accId)));
        return locked;
    }

    /** 对给定账户按 id 升序加锁校验（转账用，须为本人拥有）。 */
    private Map<Long, Account> lockOwnedAccounts(Collection<Long> accountIds, Long userId) {
        Map<Long, Account> locked = new LinkedHashMap<>();
        accountIds.stream().sorted().forEach(accId -> {
            Account account = accountRepository.findForUpdateByIdAndUserId(accId, userId)
                    .orElseThrow(() -> ApiException.notFound("账户不存在"));
            locked.put(accId, account);
        });
        return locked;
    }

    private void applyDeltas(Map<Long, Account> locked, Map<Long, BigDecimal> deltas, LocalDateTime now) {
        deltas.forEach((accId, delta) -> {
            Account account = locked.get(accId);
            account.setCurrentBalance(account.getCurrentBalance().add(delta));
            account.setUpdatedAt(now);
            accountRepository.save(account);
        });
    }
}
