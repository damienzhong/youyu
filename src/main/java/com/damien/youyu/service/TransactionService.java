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
 * 交易服务：交易创建、修改、删除与事务性余额更新（关联需求 4.1-4.11）。
 *
 * <p>核心约束：</p>
 * <ul>
 *   <li>支出（{@code expense}）：对应账户 {@code current_balance -= amount}（需求 4.1）。</li>
 *   <li>收入（{@code income}）：对应账户 {@code current_balance += amount}（需求 4.2）。</li>
 *   <li>转账（{@code transfer}）：单条记录建模（source/destination），同一事务内
 *       {@code source -= amount} 且 {@code destination += amount}；任一步失败整事务回滚，
 *       不落库、不留部分变更（需求 4.3、4.10）。</li>
 *   <li>修改（{@link #update}）：同一事务内先回滚原交易对余额的影响，再应用新（已校验）交易的影响；
 *       交易类型可变（如支出→转账），正确回滚旧形态并应用新形态（需求 4.6）。</li>
 *   <li>删除（{@link #delete}）：同一事务内回滚原交易对余额的影响，再删除交易行（需求 4.6）。</li>
 *   <li>修改/删除的目标交易不存在（或属于他人）：拒绝并返回 {@code NOT_FOUND}，且不改余额（需求 4.7、2.4）。</li>
 * </ul>
 *
 * <p>校验一律前置于任何余额变更之前，失败即拒绝且零副作用（需求 4.4、4.5、4.8、4.9）：</p>
 * <ul>
 *   <li>金额：非空（否则 {@code FIELD_REQUIRED}）；范围 [0.01, 9,999,999,999,999,999.99] 且最多两位小数
 *       （否则 {@code AMOUNT_INVALID}，需求 4.4）。</li>
 *   <li>必填字段：支出/收入需 amount + account + category；转账需 amount + source + destination
 *       （缺失返回 {@code FIELD_REQUIRED}，需求 4.8）。</li>
 *   <li>账户/分类存在性：引用的账户（账户/源/目标）与分类须存在且属于当前用户
 *       （否则 {@code NOT_FOUND}，需求 4.9）。</li>
 *   <li>转账源≠目标（否则 {@code TRANSFER_SAME_ACCOUNT}，需求 4.5）。</li>
 * </ul>
 *
 * <p>涉及账户在更新前加行级悲观写锁（{@link AccountRepository#findForUpdateByIdAndLedgerId}），
 * 避免并发记账下的丢失更新；对所有涉及账户（修改时含旧账户 + 新账户）按 id 升序加锁以降低死锁风险。
 * 方法整体 {@link Transactional}，异常触发回滚（需求 4.10）。金额一律 {@link BigDecimal}（需求 4.11）。</p>
 */
@Service
public class TransactionService {

    /** 交易金额允许范围（DECIMAL(18,2)，需求 4.4、4.11）。 */
    static final BigDecimal AMOUNT_MIN = new BigDecimal("0.01");
    static final BigDecimal AMOUNT_MAX = new BigDecimal("9999999999999999.99");

    /** 备注最大长度（需求 4.1-4.3）。 */
    static final int NOTE_MAX = 200;

    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final Clock clock;

    public TransactionService(
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            Clock clock) {
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.clock = clock;
    }

    /**
     * 创建一笔支出/收入/转账交易，并在同一事务内事务性更新相关账户余额。
     *
     * @param ledgerId               会话用户（需求 2.2 强制覆盖 user_id）
     * @param rawType              交易类型字符串：expense/income/transfer
     * @param rawAmount            金额（恒为正，最多两位小数）
     * @param accountId            支出/收入账户 id
     * @param categoryId           支出/收入分类 id
     * @param sourceAccountId      转账源账户 id
     * @param destinationAccountId 转账目标账户 id
     * @param occurredAt           交易时间（缺省取当前时间）
     * @param rawNote              备注（可选，<=200）
     * @throws ApiException FIELD_REQUIRED / AMOUNT_INVALID / TRANSFER_SAME_ACCOUNT / NOT_FOUND
     */
    @Transactional
    public Transaction create(
            Long userId,
            Long ledgerId,
            String rawType,
            BigDecimal rawAmount,
            Long accountId,
            Long categoryId,
            Long sourceAccountId,
            Long destinationAccountId,
            LocalDateTime occurredAt,
            String rawNote) {
        return create(AccountScope.independent(userId), ledgerId, rawType, rawAmount, accountId,
                categoryId, sourceAccountId, destinationAccountId, occurredAt, rawNote);
    }

    @Transactional
    public Transaction create(
            AccountScope scope,
            Long ledgerId,
            String rawType,
            BigDecimal rawAmount,
            Long accountId,
            Long categoryId,
            Long sourceAccountId,
            Long destinationAccountId,
            LocalDateTime occurredAt,
            String rawNote) {
        return create(scope, ledgerId, rawType, rawAmount, accountId, categoryId,
                sourceAccountId, destinationAccountId, occurredAt, rawNote, null);
    }

    /**
     * 创建交易的完整重载：支持协作代记（{@code createdByOverride} 指定记账人）。
     *
     * <p>{@code createdByOverride} 为 null 时以会话用户 {@code scope.userId()} 为记账人；
     * 非 null 时以其为记账人（调用方须已校验协作账本 + 成员归属）。</p>
     */
    @Transactional
    public Transaction create(
            AccountScope scope,
            Long ledgerId,
            String rawType,
            BigDecimal rawAmount,
            Long accountId,
            Long categoryId,
            Long sourceAccountId,
            Long destinationAccountId,
            LocalDateTime occurredAt,
            String rawNote,
            Long createdByOverride) {
        return create(scope, ledgerId, rawType, rawAmount, accountId, categoryId,
                sourceAccountId, destinationAccountId, occurredAt, rawNote, createdByOverride, null, null);
    }

    @Transactional
    public Transaction create(
            AccountScope scope,
            Long ledgerId,
            String rawType,
            BigDecimal rawAmount,
            Long accountId,
            Long categoryId,
            Long sourceAccountId,
            Long destinationAccountId,
            LocalDateTime occurredAt,
            String rawNote,
            Long createdByOverride,
            Long projectId) {
        return create(scope, ledgerId, rawType, rawAmount, accountId, categoryId,
                sourceAccountId, destinationAccountId, occurredAt, rawNote, createdByOverride,
                projectId, null);
    }

    /**
     * 创建交易的最全重载：附带协作代记（{@code createdByOverride}）、所属项目（{@code projectId}）与
     * 交易商家（{@code merchantId}），均可空。项目/商家归属校验由调用方（控制器）完成，此处仅落库。
     */
    @Transactional
    public Transaction create(
            AccountScope scope,
            Long ledgerId,
            String rawType,
            BigDecimal rawAmount,
            Long accountId,
            Long categoryId,
            Long sourceAccountId,
            Long destinationAccountId,
            LocalDateTime occurredAt,
            String rawNote,
            Long createdByOverride,
            Long projectId,
            Long merchantId) {

        // ---- 校验前置：任何余额变更前完成，失败即零副作用（需求 4.4、4.5、4.8、4.9）----
        TransactionType type = validateType(rawType);
        BigDecimal amount = validateAmount(rawAmount);
        String note = validateNote(rawNote);
        validateShapePresence(type, accountId, categoryId, sourceAccountId, destinationAccountId);
        validateCategoryExists(ledgerId, type, categoryId);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime when = occurredAt == null ? now : occurredAt;

        // 账户按作用域加锁校验（独立账本用户级 / 协作账本账本级）；交易归属账本 ledgerId。
        Map<Long, BigDecimal> deltas = effectDeltas(
                type, amount, accountId, sourceAccountId, destinationAccountId);
        Map<Long, Account> locked = lockAll(deltas.keySet(), scope);
        applyDeltas(locked, deltas, now);

        Transaction tx = new Transaction();
        tx.setLedgerId(ledgerId);
        tx.setCreatedBy(createdByOverride != null ? createdByOverride : scope.userId());
        tx.setProjectId(projectId);
        tx.setMerchantId(merchantId);
        tx.setCreatedAt(now);
        applyFields(tx, type, amount, note, when, accountId, categoryId, sourceAccountId,
                destinationAccountId, now);
        return transactionRepository.save(tx);
    }

    /** 系统「余额调整」分类名（补差流水归入该分类，便于报表识别与过滤）。 */
    public static final String ADJUST_CATEGORY_NAME = "余额调整";

    /**
     * 余额调整：把某账户当前余额校准到 {@code targetBalance}，用一笔补差流水（收入=调增 / 支出=调减）落地，
     * 归入系统「余额调整」分类（按需惰性创建）。目标与当前一致则不产生流水、返回 null。
     *
     * @throws ApiException NOT_FOUND（账户不存在）/ AMOUNT_INVALID（目标余额超出范围）
     */
    @Transactional
    public Transaction adjustBalance(
            AccountScope scope,
            Long ledgerId,
            Long accountId,
            BigDecimal rawTarget,
            LocalDateTime occurredAt,
            String rawNote) {
        if (accountId == null) {
            throw ApiException.fieldRequired("accountId");
        }
        // 校验账户归属（作用域），并读取当前余额。
        Account account = (scope.isCollaborative()
                ? accountRepository.findByIdAndLedgerId(accountId, scope.ledgerId())
                : accountRepository.findByIdAndUserIdAndLedgerIdIsNull(accountId, scope.userId()))
                .orElseThrow(() -> ApiException.notFound("账户不存在"));

        BigDecimal target = validateBalance(rawTarget);
        BigDecimal delta = target.subtract(account.getCurrentBalance()).setScale(2, RoundingMode.HALF_UP);
        if (delta.signum() == 0) {
            return null; // 无差额，不产生流水
        }
        TransactionType type = delta.signum() > 0 ? TransactionType.INCOME : TransactionType.EXPENSE;
        CategoryKind kind = type == TransactionType.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        Long categoryId = ensureAdjustCategory(scope.userId(), ledgerId, kind);
        String note = (rawNote == null || rawNote.isBlank()) ? ADJUST_CATEGORY_NAME : rawNote;
        // 复用创建管道（校验/加锁/余额更新一致）。
        return create(scope, ledgerId, type.getCode(), delta.abs(), accountId, categoryId,
                null, null, occurredAt, note);
    }

    /** 惰性获取/创建某账本某种类的系统「余额调整」顶级分类，返回其 id。 */
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
                    c.setCreatedAt(now);
                    c.setUpdatedAt(now);
                    return categoryRepository.save(c).getId();
                });
    }

    /** 余额目标值校验（DECIMAL(18,2)，可正可负）。 */
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

    /**
     * 修改一笔已有交易：同一事务内先回滚原交易对余额的影响，再应用新（已校验）交易的影响（需求 4.6）。
     *
     * <p>交易类型可变；对旧形态与新形态涉及的全部账户（合并去重）按 id 升序加锁，
     * 计算净增量后一次性应用，保证守恒与并发安全。目标交易不存在或属于他人则拒绝且不改余额（需求 4.7、2.4）。</p>
     *
     * @throws ApiException NOT_FOUND / FIELD_REQUIRED / AMOUNT_INVALID / TRANSFER_SAME_ACCOUNT
     */
    @Transactional
    public Transaction update(
            Long userId,
            Long ledgerId,
            Long id,
            String rawType,
            BigDecimal rawAmount,
            Long accountId,
            Long categoryId,
            Long sourceAccountId,
            Long destinationAccountId,
            LocalDateTime occurredAt,
            String rawNote) {
        return update(AccountScope.independent(userId), ledgerId, id, rawType, rawAmount, accountId,
                categoryId, sourceAccountId, destinationAccountId, occurredAt, rawNote);
    }

    @Transactional
    public Transaction update(
            AccountScope scope,
            Long ledgerId,
            Long id,
            String rawType,
            BigDecimal rawAmount,
            Long accountId,
            Long categoryId,
            Long sourceAccountId,
            Long destinationAccountId,
            LocalDateTime occurredAt,
            String rawNote) {
        return update(scope, ledgerId, id, rawType, rawAmount, accountId, categoryId,
                sourceAccountId, destinationAccountId, occurredAt, rawNote, null, null);
    }

    @Transactional
    public Transaction update(
            AccountScope scope,
            Long ledgerId,
            Long id,
            String rawType,
            BigDecimal rawAmount,
            Long accountId,
            Long categoryId,
            Long sourceAccountId,
            Long destinationAccountId,
            LocalDateTime occurredAt,
            String rawNote,
            Long projectId) {
        return update(scope, ledgerId, id, rawType, rawAmount, accountId, categoryId,
                sourceAccountId, destinationAccountId, occurredAt, rawNote, projectId, null);
    }

    /**
     * 修改交易的最全重载：附带所属项目（{@code projectId}）与交易商家（{@code merchantId}），
     * 均可空（null 表示清除）。项目/商家归属校验由调用方（控制器）完成，此处仅落库。
     */
    @Transactional
    public Transaction update(
            AccountScope scope,
            Long ledgerId,
            Long id,
            String rawType,
            BigDecimal rawAmount,
            Long accountId,
            Long categoryId,
            Long sourceAccountId,
            Long destinationAccountId,
            LocalDateTime occurredAt,
            String rawNote,
            Long projectId,
            Long merchantId) {

        // 需求 4.7 / 2.4：目标交易须存在且属于当前用户，否则拒绝且不改余额。
        Transaction tx = transactionRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("交易不存在"));

        // ---- 校验前置：任何余额变更前完成，失败即零副作用（需求 4.4、4.5、4.8、4.9）----
        TransactionType type = validateType(rawType);
        BigDecimal amount = validateAmount(rawAmount);
        String note = validateNote(rawNote);
        validateShapePresence(type, accountId, categoryId, sourceAccountId, destinationAccountId);
        validateCategoryExists(ledgerId, type, categoryId);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime when = occurredAt == null ? now : occurredAt;

        // 回滚原影响（负增量）+ 应用新影响（正增量），合并为按账户净增量。
        Map<Long, BigDecimal> net = new TreeMap<>();
        effectDeltas(tx.getType(), tx.getAmount(), tx.getAccountId(),
                tx.getSourceAccountId(), tx.getDestinationAccountId())
                .forEach((acc, delta) -> net.merge(acc, delta.negate(), BigDecimal::add));
        effectDeltas(type, amount, accountId, sourceAccountId, destinationAccountId)
                .forEach((acc, delta) -> net.merge(acc, delta, BigDecimal::add));

        // 需求 4.9 + 加锁：新形态引用的不存在账户在此触发 NOT_FOUND（净增量非零，必被锁定校验）。
        Map<Long, Account> locked = lockAll(net.keySet(), scope);
        applyDeltas(locked, net, now);

        tx.setProjectId(projectId);
        tx.setMerchantId(merchantId);
        applyFields(tx, type, amount, note, when, accountId, categoryId, sourceAccountId,
                destinationAccountId, now);
        return transactionRepository.save(tx);
    }

    /**
     * 删除一笔已有交易：同一事务内回滚原交易对余额的影响，再删除交易行（需求 4.6）。
     * 目标交易不存在或属于他人则拒绝且不改余额（需求 4.7、2.4）。
     *
     * @throws ApiException NOT_FOUND
     */
    @Transactional
    public void delete(Long userId, Long ledgerId, Long id) {
        delete(AccountScope.independent(userId), ledgerId, id);
    }

    @Transactional
    public void delete(AccountScope scope, Long ledgerId, Long id) {
        Transaction tx = transactionRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("交易不存在"));

        LocalDateTime now = LocalDateTime.now(clock);

        // 回滚原影响（负增量）。
        Map<Long, BigDecimal> rollback = new TreeMap<>();
        effectDeltas(tx.getType(), tx.getAmount(), tx.getAccountId(),
                tx.getSourceAccountId(), tx.getDestinationAccountId())
                .forEach((acc, delta) -> rollback.merge(acc, delta.negate(), BigDecimal::add));

        Map<Long, Account> locked = lockAll(rollback.keySet(), scope);
        applyDeltas(locked, rollback, now);

        // 软删除：回滚余额后置 deleted_at，移入回收站（可恢复/彻底删除），而非物理删行。
        tx.setDeletedAt(now);
        tx.setUpdatedAt(now);
        transactionRepository.save(tx);
    }

    /** 列出某账本回收站记录（已软删除），按删除时间倒序。 */
    @Transactional(readOnly = true)
    public java.util.List<Transaction> listDeleted(Long ledgerId) {
        return transactionRepository.findDeletedByLedgerId(ledgerId);
    }

    /**
     * 从回收站恢复一笔交易：重新应用其对余额的影响并清空 deleted_at（需求：可逆删除）。
     * 记录不存在或不在回收站则拒绝。引用的账户若已不存在则 NOT_FOUND。
     */
    @Transactional
    public Transaction restore(AccountScope scope, Long ledgerId, Long id) {
        Transaction tx = transactionRepository.findRawByIdAndLedgerId(id, ledgerId)
                .filter(t -> t.getDeletedAt() != null)
                .orElseThrow(() -> ApiException.notFound("回收站记录不存在"));

        LocalDateTime now = LocalDateTime.now(clock);
        Map<Long, BigDecimal> deltas = effectDeltas(tx.getType(), tx.getAmount(), tx.getAccountId(),
                tx.getSourceAccountId(), tx.getDestinationAccountId());
        Map<Long, Account> locked = lockAll(deltas.keySet(), scope);
        applyDeltas(locked, deltas, now);

        tx.setDeletedAt(null);
        tx.setUpdatedAt(now);
        return transactionRepository.save(tx);
    }

    /**
     * 彻底删除回收站中的一笔交易（物理删行）。余额已在软删时回滚，此处不再变动余额。
     * 记录不存在或不在回收站则拒绝。
     */
    @Transactional
    public void purge(Long ledgerId, Long id) {
        Transaction tx = transactionRepository.findRawByIdAndLedgerId(id, ledgerId)
                .filter(t -> t.getDeletedAt() != null)
                .orElseThrow(() -> ApiException.notFound("回收站记录不存在"));
        transactionRepository.delete(tx);
    }

    /** 分页列出本人交易，按 occurred_at、id 倒序（需求 2.3）。 */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Transaction> list(
            Long ledgerId, org.springframework.data.domain.Pageable pageable) {
        return transactionRepository.findByLedgerIdOrderByOccurredAtDescIdDesc(ledgerId, pageable);
    }

    /**
     * 列出本人在半开区间 [from, to) 内的交易，按时间倒序（首页「当月流水」用）。
     * 边界按 {@code Asia/Shanghai} 自然月：from=当月 1 日 00:00，to=次月 1 日 00:00。
     */
    @Transactional(readOnly = true)
    public java.util.List<Transaction> listByRange(Long ledgerId, LocalDateTime from, LocalDateTime to) {
        return transactionRepository
                .findByLedgerIdAndOccurredAtGreaterThanEqualAndOccurredAtLessThanOrderByOccurredAtDescIdDesc(
                        ledgerId, from, to);
    }

    /** 列出某账本某项目下的全部交易，按时间倒序。 */
    @Transactional(readOnly = true)
    public java.util.List<Transaction> listByProject(Long ledgerId, Long projectId) {
        return transactionRepository.findByLedgerIdAndProjectIdOrderByOccurredAtDescIdDesc(ledgerId, projectId);
    }

    /** 列出某账本某商家下的全部交易，按时间倒序。 */
    @Transactional(readOnly = true)
    public java.util.List<Transaction> listByMerchant(Long ledgerId, Long merchantId) {
        return transactionRepository.findByLedgerIdAndMerchantIdOrderByOccurredAtDescIdDesc(ledgerId, merchantId);
    }

    /** 列出某账本某标签下的全部交易，按时间倒序。 */
    @Transactional(readOnly = true)
    public java.util.List<Transaction> listByTag(Long ledgerId, Long tagId) {
        return transactionRepository.findByLedgerIdAndTagId(ledgerId, tagId);
    }

    /**
     * 单条读取本人交易（校验归属，需求 2.4）。
     *
     * @throws ApiException NOT_FOUND（交易不存在或不属于当前用户）
     */
    @Transactional(readOnly = true)
    public Transaction get(Long ledgerId, Long id) {
        return transactionRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("交易不存在"));
    }

    // ---------------- 校验 ----------------

    private TransactionType validateType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            throw ApiException.fieldRequired("type");
        }
        try {
            return TransactionType.fromCode(rawType.trim());
        } catch (IllegalArgumentException ex) {
            throw new ApiException("TRANSACTION_TYPE_INVALID",
                    org.springframework.http.HttpStatus.BAD_REQUEST, "不支持的交易类型", "type");
        }
    }

    private BigDecimal validateAmount(BigDecimal rawAmount) {
        if (rawAmount == null) {
            // 需求 4.8：金额缺失属必填缺失。
            throw ApiException.fieldRequired("amount");
        }
        // 需求 4.4：最多两位小数——可无损缩放到 2 位则合法，否则金额非法。
        BigDecimal normalized;
        try {
            normalized = rawAmount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw ApiException.amountInvalid();
        }
        // 需求 4.4：范围 [0.01, 9,999,999,999,999,999.99]。
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

    /** 校验交易形态的必填字段与转账源≠目标（需求 4.5、4.8）。 */
    private void validateShapePresence(
            TransactionType type, Long accountId, Long categoryId,
            Long sourceAccountId, Long destinationAccountId) {
        switch (type) {
            case EXPENSE, INCOME -> {
                if (accountId == null) {
                    throw ApiException.fieldRequired("accountId");
                }
                if (categoryId == null) {
                    throw ApiException.fieldRequired("categoryId");
                }
            }
            case TRANSFER -> {
                if (sourceAccountId == null) {
                    throw ApiException.fieldRequired("sourceAccountId");
                }
                if (destinationAccountId == null) {
                    throw ApiException.fieldRequired("destinationAccountId");
                }
                if (sourceAccountId.equals(destinationAccountId)) {
                    throw ApiException.transferSameAccount();
                }
            }
            default -> throw ApiException.fieldRequired("type");
        }
    }

    /** 支出/收入分类须存在且属于当前用户（需求 4.9）。 */
    private void validateCategoryExists(Long ledgerId, TransactionType type, Long categoryId) {
        if (type == TransactionType.EXPENSE || type == TransactionType.INCOME) {
            categoryRepository.findByIdAndLedgerId(categoryId, ledgerId)
                    .orElseThrow(() -> ApiException.notFound("分类不存在"));
        }
    }

    // ---------------- 余额影响与账户加锁 ----------------

    /**
     * 计算某交易形态对各账户余额的增量（正=增、负=减）。
     * 支出：账户 -amount；收入：账户 +amount；转账：源 -amount、目标 +amount。
     */
    private Map<Long, BigDecimal> effectDeltas(
            TransactionType type, BigDecimal amount, Long accountId,
            Long sourceAccountId, Long destinationAccountId) {
        Map<Long, BigDecimal> deltas = new LinkedHashMap<>();
        switch (type) {
            case EXPENSE -> deltas.merge(accountId, amount.negate(), BigDecimal::add);
            case INCOME -> deltas.merge(accountId, amount, BigDecimal::add);
            case TRANSFER -> {
                deltas.merge(sourceAccountId, amount.negate(), BigDecimal::add);
                deltas.merge(destinationAccountId, amount, BigDecimal::add);
            }
            default -> {
                // 不会发生：type 已校验。
            }
        }
        return deltas;
    }

    /**
     * 对给定账户 id 集合按 id 升序加行级悲观写锁并返回 id→账户映射；
     * 任一账户不存在或不属于当前用户即 NOT_FOUND（需求 4.9）。升序加锁降低死锁风险。
     */
    private Map<Long, Account> lockAll(Collection<Long> accountIds, AccountScope scope) {
        Map<Long, Account> locked = new LinkedHashMap<>();
        accountIds.stream().sorted().forEach(accId -> {
            Account account = (scope.isCollaborative()
                    ? accountRepository.findForUpdateByIdAndLedgerId(accId, scope.ledgerId())
                    : accountRepository.findForUpdateByIdAndUserIdAndLedgerIdIsNull(accId, scope.userId()))
                    .orElseThrow(() -> ApiException.notFound("账户不存在"));
            locked.put(accId, account);
        });
        return locked;
    }

    /** 将各账户净增量应用到已加锁的账户实体并持久化。 */
    private void applyDeltas(
            Map<Long, Account> locked, Map<Long, BigDecimal> deltas, LocalDateTime now) {
        deltas.forEach((accId, delta) -> {
            Account account = locked.get(accId);
            account.setCurrentBalance(account.getCurrentBalance().add(delta));
            account.setUpdatedAt(now);
            accountRepository.save(account);
        });
    }

    /**
     * 将交易的类型/金额/备注/时间与账户/分类字段写入实体；按类型清空不相关字段
     * （支出/收入清空 source/destination；转账清空 account/category），保证类型切换后形态干净。
     */
    private void applyFields(
            Transaction tx, TransactionType type, BigDecimal amount, String note,
            LocalDateTime when, Long accountId, Long categoryId,
            Long sourceAccountId, Long destinationAccountId, LocalDateTime now) {
        tx.setType(type);
        tx.setAmount(amount);
        tx.setNote(note);
        tx.setOccurredAt(when);
        tx.setUpdatedAt(now);
        if (type == TransactionType.TRANSFER) {
            tx.setAccountId(null);
            tx.setCategoryId(null);
            tx.setSourceAccountId(sourceAccountId);
            tx.setDestinationAccountId(destinationAccountId);
        } else {
            tx.setAccountId(accountId);
            tx.setCategoryId(categoryId);
            tx.setSourceAccountId(null);
            tx.setDestinationAccountId(null);
        }
    }
}
