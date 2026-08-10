package com.damien.youyu.service.aa;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionSplit;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AaSettlementRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionSplitRepository;

/**
 * AA 账本记账服务：创建 AA 支出（{@code aa_expense}）及其分摊行（{@code transaction_splits}），
 * 并在同一事务内完成付款账户扣款（付款人为本人时）。
 *
 * <p>核心口径（关联需求 3、4、7）：</p>
 * <ul>
 *   <li>付款人 = 当前用户：须选付款账户，保存时按<b>实付全额</b>从该账户扣款（真实现金流出）。
 *       账户加锁复用 {@link AccountRepository#findForUpdateByIdAndUserId}，与转账 / 余额调整同口径。</li>
 *   <li>付款人 ≠ 当前用户（代记）：<b>不触动当前用户的任何账户</b>，仅落交易与分摊，形成应收 / 应付。</li>
 *   <li>分摊分配复用 {@link AaMath}：均分以「分」守恒 + 余数校正；自定义校验 Σ = 总额（否则
 *       {@code AA_SPLIT_MISMATCH}）。</li>
 *   <li>单事务原子：交易 + 分摊 + 账户扣款要么全部提交、要么整体回滚（需求 10.4）。</li>
 * </ul>
 */
@Service
public class AaExpenseService {

    /** 分摊方式：均分。 */
    public static final String SPLIT_EVEN = "even";
    /** 分摊方式：自定义金额。 */
    public static final String SPLIT_CUSTOM = "custom";

    /** 交易金额允许范围（DECIMAL(18,2)），与 {@code TransactionService} 保持一致。 */
    static final BigDecimal AMOUNT_MIN = new BigDecimal("0.01");
    static final BigDecimal AMOUNT_MAX = new BigDecimal("9999999999999999.99");
    static final int NOTE_MAX = 200;

    private final TransactionRepository transactionRepository;
    private final TransactionSplitRepository splitRepository;
    private final AccountRepository accountRepository;
    private final CategoryRepository categoryRepository;
    private final LedgerRepository ledgerRepository;
    private final LedgerMemberRepository memberRepository;
    private final AaSettlementRepository settlementRepository;
    private final Clock clock;

    public AaExpenseService(
            TransactionRepository transactionRepository,
            TransactionSplitRepository splitRepository,
            AccountRepository accountRepository,
            CategoryRepository categoryRepository,
            LedgerRepository ledgerRepository,
            LedgerMemberRepository memberRepository,
            AaSettlementRepository settlementRepository,
            Clock clock) {
        this.transactionRepository = transactionRepository;
        this.splitRepository = splitRepository;
        this.accountRepository = accountRepository;
        this.categoryRepository = categoryRepository;
        this.ledgerRepository = ledgerRepository;
        this.memberRepository = memberRepository;
        this.settlementRepository = settlementRepository;
        this.clock = clock;
    }

    /**
     * 创建一笔 AA 支出并落分摊，同一事务内完成付款账户扣款（付款人为本人时）。
     *
     * @param userId        当前操作用户（记账人）
     * @param ledgerId      AA 账本 id
     * @param rawAmount     该笔总额（&gt;0，2 位小数）
     * @param categoryId    分类 id（须属于该账本）
     * @param payerUserId   付款人 user_id（为空默认当前用户）
     * @param payerAccountId 付款账户 id（付款人为本人时必填；非本人时忽略）
     * @param occurredAt    交易时间（为空取当前时间）
     * @param rawNote       备注（≤200）
     * @param splitMode     分摊方式：{@link #SPLIT_EVEN} 或 {@link #SPLIT_CUSTOM}
     * @param participants  参与分摊成员 user_id 列表（非空，去重后须均为成员）
     * @param customShares  自定义分摊（{@code splitMode=custom} 时必填：每位参与人一份，Σ=总额）
     * @return 已保存的 {@code aa_expense} 交易
     * @throws ApiException NOT_FOUND / AMOUNT_INVALID / FIELD_REQUIRED / AA_LEDGER_ARCHIVED /
     *                      AA_PARTICIPANT_INVALID / AA_SPLIT_MODE_INVALID / AA_SPLIT_MISMATCH
     */
    @Transactional
    public Transaction create(Long userId, Long ledgerId, BigDecimal rawAmount, Long categoryId,
            Long payerUserId, Long payerAccountId, LocalDateTime occurredAt, String rawNote,
            String splitMode, List<Long> participants, Map<Long, BigDecimal> customShares) {

        // 1) 账本存在 + 为 AA 类型 + 当前用户为成员（越权返回 NOT_FOUND，不泄漏存在性）+ 非只读。
        Ledger ledger = ledgerRepository.findById(ledgerId)
                .filter(Ledger::isAa)
                .orElseThrow(() -> ApiException.notFound("账本不存在"));
        Set<Long> members = memberIds(ledgerId);
        if (!members.contains(userId)) {
            throw ApiException.notFound("账本不存在");
        }
        if (ledger.isArchived()) {
            throw ApiException.aaLedgerArchived();
        }

        // 2) 基础字段校验。
        BigDecimal amount = validateAmount(rawAmount);
        String note = validateNote(rawNote);
        if (categoryId == null) {
            throw ApiException.fieldRequired("categoryId");
        }
        categoryRepository.findByIdAndLedgerId(categoryId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("分类不存在"));

        // 3) 付款人（默认当前用户）与参与人均须为成员。
        Long payer = payerUserId == null ? userId : payerUserId;
        if (!members.contains(payer)) {
            throw ApiException.aaParticipantInvalid("payerUserId");
        }
        List<Long> orderedParticipants = distinctParticipants(participants, members);

        // 4) 分摊分配（以「分」守恒）。
        long totalCents = toCents(amount);
        Map<Long, Long> shareCents = allocate(splitMode, totalCents, orderedParticipants, customShares);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime when = occurredAt == null ? now : occurredAt;

        // 5) 付款人为本人 → 锁其账户并按实付全额扣款；付款人非本人 → 不触本人账户。
        Long accountId = null;
        if (payer.equals(userId)) {
            if (payerAccountId == null) {
                throw ApiException.fieldRequired("payerAccountId");
            }
            Account account = accountRepository.findForUpdateByIdAndUserId(payerAccountId, userId)
                    .orElseThrow(() -> ApiException.notFound("账户不存在"));
            account.setCurrentBalance(account.getCurrentBalance().subtract(amount));
            account.setUpdatedAt(now);
            accountRepository.save(account);
            accountId = payerAccountId;
        }

        // 6) 落 aa_expense 交易。付款账户仅本人付款时记录（account_id）。
        Transaction tx = new Transaction();
        tx.setLedgerId(ledgerId);
        tx.setCreatedBy(userId);
        tx.setPayerUserId(payer);
        tx.setType(TransactionType.AA_EXPENSE);
        tx.setAmount(amount);
        tx.setAccountId(accountId);
        tx.setCategoryId(categoryId);
        tx.setNote(note);
        tx.setOccurredAt(when);
        tx.setCreatedAt(now);
        tx.setUpdatedAt(now);
        Transaction saved = transactionRepository.save(tx);

        // 7) 落分摊行（Σ share_amount = 总额，以「分」守恒）。
        for (Long participant : orderedParticipants) {
            TransactionSplit split = new TransactionSplit();
            split.setTransactionId(saved.getId());
            split.setParticipantUserId(participant);
            split.setShareAmount(fromCents(shareCents.get(participant)));
            split.setCreatedAt(now);
            splitRepository.save(split);
        }
        return saved;
    }

    /**
     * 编辑一笔 AA 支出：<b>回滚旧效果 + 按新参数重建</b>，单事务内完成（需求 9.2a、9.3）。
     *
     * <p>先撤销原付款账户扣款并清除原分摊，再按新金额 / 分类 / 付款人 / 账户 / 分摊重算并落库。
     * 付款账户口径与 {@link #create} 一致：仅当<b>新付款人为当前用户</b>时按新实付额扣其所选账户，
     * 否则不触当前用户账户。净额为派生，重建分摊 + 账户即完成重算。</p>
     *
     * <p>前置校验：账本存在且为 AA、当前用户为成员（越权 NOT_FOUND）、非只读（{@code AA_LEDGER_ARCHIVED}）、
     * 目标为本账本内的 {@code aa_expense}（否则 NOT_FOUND）、当前用户为账本创建者或该笔记账人
     * （否则 {@code LEDGER_FORBIDDEN}，需求 9.2）、账本无未撤销结算（否则 {@code AA_EXPENSE_SETTLED}，需求 9.2b）。</p>
     *
     * @return 已更新的 {@code aa_expense} 交易
     * @throws ApiException NOT_FOUND / AMOUNT_INVALID / FIELD_REQUIRED / AA_LEDGER_ARCHIVED /
     *                      LEDGER_FORBIDDEN / AA_EXPENSE_SETTLED / AA_PARTICIPANT_INVALID /
     *                      AA_SPLIT_MODE_INVALID / AA_SPLIT_MISMATCH
     */
    @Transactional
    public Transaction update(Long userId, Long ledgerId, Long expenseId, BigDecimal rawAmount,
            Long categoryId, Long payerUserId, Long payerAccountId, LocalDateTime occurredAt,
            String rawNote, String splitMode, List<Long> participants,
            Map<Long, BigDecimal> customShares) {

        Ledger ledger = requireWritableAaLedger(userId, ledgerId);
        Set<Long> members = memberIds(ledgerId);
        Transaction tx = requireAaExpense(ledgerId, expenseId);
        requireOwnerOrCreator(ledger, userId, tx);
        requireNoActiveSettlement(ledgerId);

        // 1) 校验新参数（与 create 同口径）。
        BigDecimal amount = validateAmount(rawAmount);
        String note = validateNote(rawNote);
        if (categoryId == null) {
            throw ApiException.fieldRequired("categoryId");
        }
        categoryRepository.findByIdAndLedgerId(categoryId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("分类不存在"));
        Long payer = payerUserId == null ? userId : payerUserId;
        if (!members.contains(payer)) {
            throw ApiException.aaParticipantInvalid("payerUserId");
        }
        List<Long> orderedParticipants = distinctParticipants(participants, members);
        long totalCents = toCents(amount);
        Map<Long, Long> shareCents = allocate(splitMode, totalCents, orderedParticipants, customShares);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime when = occurredAt == null ? now : occurredAt;

        // 2) 回滚原付款账户扣款（若原付款人为本人记录了付款账户），再按新付款人扣款。
        rollbackPayerAccount(tx, now);
        Long accountId = null;
        if (payer.equals(userId)) {
            if (payerAccountId == null) {
                throw ApiException.fieldRequired("payerAccountId");
            }
            Account account = accountRepository.findForUpdateByIdAndUserId(payerAccountId, userId)
                    .orElseThrow(() -> ApiException.notFound("账户不存在"));
            account.setCurrentBalance(account.getCurrentBalance().subtract(amount));
            account.setUpdatedAt(now);
            accountRepository.save(account);
            accountId = payerAccountId;
        }

        // 3) 更新交易主体（createdBy / type / ledgerId 不变）。
        tx.setPayerUserId(payer);
        tx.setAmount(amount);
        tx.setAccountId(accountId);
        tx.setCategoryId(categoryId);
        tx.setNote(note);
        tx.setOccurredAt(when);
        tx.setUpdatedAt(now);
        Transaction saved = transactionRepository.save(tx);

        // 4) 重建分摊行（先删旧、后插新，保证唯一键 (transaction_id, participant_user_id) 不冲突）。
        splitRepository.deleteByTransactionId(saved.getId());
        for (Long participant : orderedParticipants) {
            TransactionSplit split = new TransactionSplit();
            split.setTransactionId(saved.getId());
            split.setParticipantUserId(participant);
            split.setShareAmount(fromCents(shareCents.get(participant)));
            split.setCreatedAt(now);
            splitRepository.save(split);
        }
        return saved;
    }

    /**
     * 删除一笔未涉及结算的 AA 支出：回滚付款账户扣款、清除分摊行，单事务内完成（需求 9.2a、9.3）。
     *
     * <p>净额为派生量，删除分摊 + 回滚账户即完成重算。交易本身按既有软删除口径置
     * {@code deleted_at}（从常规查询与净额计算中排除），保持与全站回收站语义一致。</p>
     *
     * <p>前置校验同 {@link #update}：AA 账本 + 成员（NOT_FOUND）、非只读（{@code AA_LEDGER_ARCHIVED}）、
     * 目标为本账本 {@code aa_expense}（NOT_FOUND）、创建者或记账人（{@code LEDGER_FORBIDDEN}）、
     * 账本无未撤销结算（否则 {@code AA_EXPENSE_SETTLED}，需求 9.2b）。</p>
     *
     * @throws ApiException NOT_FOUND / AA_LEDGER_ARCHIVED / LEDGER_FORBIDDEN / AA_EXPENSE_SETTLED
     */
    @Transactional
    public void delete(Long userId, Long ledgerId, Long expenseId) {
        Ledger ledger = requireWritableAaLedger(userId, ledgerId);
        Transaction tx = requireAaExpense(ledgerId, expenseId);
        requireOwnerOrCreator(ledger, userId, tx);
        requireNoActiveSettlement(ledgerId);

        LocalDateTime now = LocalDateTime.now(clock);
        rollbackPayerAccount(tx, now);

        splitRepository.deleteByTransactionId(tx.getId());
        tx.setDeletedAt(now);
        tx.setUpdatedAt(now);
        transactionRepository.save(tx);
    }

    /**
     * 读取某笔 AA 支出的全部分摊行（按 id 升序，与写入顺序一致），供接口层构建响应。只读、无副作用。
     */
    @Transactional(readOnly = true)
    public List<TransactionSplit> splitsOf(Long transactionId) {
        return splitRepository.findByTransactionId(transactionId);
    }

    // ---------------- 内部工具 ----------------

    private Set<Long> memberIds(Long ledgerId) {
        Set<Long> ids = new HashSet<>();
        for (LedgerMember m : memberRepository.findByLedgerId(ledgerId)) {
            ids.add(m.getUserId());
        }
        return ids;
    }

    /**
     * 定位可写 AA 账本：须存在、为 AA 类型、当前用户为成员（越权返回 NOT_FOUND，不泄漏存在性）、
     * 且未归档（只读账本写操作抛 {@code AA_LEDGER_ARCHIVED}）。
     */
    private Ledger requireWritableAaLedger(Long userId, Long ledgerId) {
        Ledger ledger = ledgerRepository.findById(ledgerId)
                .filter(Ledger::isAa)
                .orElseThrow(() -> ApiException.notFound("账本不存在"));
        if (!memberIds(ledgerId).contains(userId)) {
            throw ApiException.notFound("账本不存在");
        }
        if (ledger.isArchived()) {
            throw ApiException.aaLedgerArchived();
        }
        return ledger;
    }

    /** 定位本账本内的 AA 支出交易；不存在 / 非 AA 支出一律 NOT_FOUND（不泄漏存在性）。 */
    private Transaction requireAaExpense(Long ledgerId, Long expenseId) {
        if (expenseId == null) {
            throw ApiException.notFound("支出不存在");
        }
        return transactionRepository.findByIdAndLedgerId(expenseId, ledgerId)
                .filter(t -> t.getType() == TransactionType.AA_EXPENSE)
                .orElseThrow(() -> ApiException.notFound("支出不存在"));
    }

    /** 编辑 / 删除权限：仅账本创建者（owner）或该笔记账人（created_by）可操作（需求 9.2）。 */
    private void requireOwnerOrCreator(Ledger ledger, Long userId, Transaction tx) {
        boolean isOwner = ledger.getUserId().equals(userId);
        boolean isCreator = userId.equals(tx.getCreatedBy());
        if (!isOwner && !isCreator) {
            throw ApiException.ledgerForbidden();
        }
    }

    /**
     * 账本存在未撤销结算时，拒绝删除 / 编辑任一支出（需求 9.2b）。MVP 结算为账本级净额清算，
     * 不绑定具体某笔，故只要有未撤销结算即须先撤销再改动支出。
     */
    private void requireNoActiveSettlement(Long ledgerId) {
        if (!settlementRepository.findByLedgerIdAndRevertedAtIsNull(ledgerId).isEmpty()) {
            throw ApiException.aaExpenseSettled();
        }
    }

    /**
     * 回滚 AA 支出的付款账户扣款：付款人为本人记录了 {@code account_id} 时，把实付额加回原付款账户
     * （账户属付款人，按 payer 加锁）。付款人非本人（{@code account_id} 为空）无账户变动，直接返回。
     */
    private void rollbackPayerAccount(Transaction tx, LocalDateTime now) {
        if (tx.getAccountId() == null) {
            return;
        }
        Account account = accountRepository.findForUpdateByIdAndUserId(tx.getAccountId(), tx.getPayerUserId())
                .orElseThrow(() -> ApiException.notFound("账户不存在"));
        account.setCurrentBalance(account.getCurrentBalance().add(tx.getAmount()));
        account.setUpdatedAt(now);
        accountRepository.save(account);
    }

    /** 参与人去重（保序），校验非空且均为成员。 */
    private List<Long> distinctParticipants(List<Long> participants, Set<Long> members) {
        if (participants == null || participants.isEmpty()) {
            throw ApiException.fieldRequired("participants");
        }
        List<Long> ordered = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long p : participants) {
            if (p == null) {
                throw ApiException.aaParticipantInvalid("participants");
            }
            if (!members.contains(p)) {
                throw ApiException.aaParticipantInvalid("participants");
            }
            if (seen.add(p)) {
                ordered.add(p);
            }
        }
        return ordered;
    }

    /**
     * 按分摊方式分配各参与人份额（分）。
     * 均分：{@link AaMath#splitEven}（余数校正，Σ=总额）；自定义：校验每人有值且 Σ=总额。
     */
    private Map<Long, Long> allocate(String splitMode, long totalCents,
            List<Long> participants, Map<Long, BigDecimal> customShares) {
        Map<Long, Long> out = new java.util.LinkedHashMap<>();
        if (SPLIT_EVEN.equals(splitMode)) {
            long[] parts = AaMath.splitEven(totalCents, participants.size());
            for (int i = 0; i < participants.size(); i++) {
                out.put(participants.get(i), parts[i]);
            }
            return out;
        }
        if (SPLIT_CUSTOM.equals(splitMode)) {
            if (customShares == null) {
                throw ApiException.aaSplitMismatch();
            }
            List<Long> cents = new ArrayList<>();
            for (Long p : participants) {
                BigDecimal share = customShares.get(p);
                if (share == null) {
                    throw ApiException.aaSplitMismatch();
                }
                long c = toCents(validateShare(share));
                out.put(p, c);
                cents.add(c);
            }
            if (!AaMath.isValidCustomSplit(totalCents, cents)) {
                throw ApiException.aaSplitMismatch();
            }
            return out;
        }
        throw ApiException.aaSplitModeInvalid();
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

    /** 校验单份自定义分摊：非空、2 位小数、非负、上限内。允许 0（某参与人本笔不摊）。 */
    private BigDecimal validateShare(BigDecimal rawShare) {
        BigDecimal normalized;
        try {
            normalized = rawShare.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw ApiException.aaSplitMismatch();
        }
        if (normalized.signum() < 0 || normalized.compareTo(AMOUNT_MAX) > 0) {
            throw ApiException.aaSplitMismatch();
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

    private static long toCents(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    private static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
