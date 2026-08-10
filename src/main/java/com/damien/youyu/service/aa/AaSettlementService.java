package com.damien.youyu.service.aa;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.api.dto.AaSettlementResponse;
import com.damien.youyu.domain.AaSettlement;
import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionSplit;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AaSettlementRepository;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionSplitRepository;

/**
 * AA 账本净额 / 清算服务（读侧派生视图，关联需求 5.1–5.5）。
 *
 * <p>净额与清算方案<b>实时派生、不落库</b>（结清动作除外，见需求 6）。数据来源为账本内未撤销的
 * AA 支出（{@code aa_expense} + 其 {@code transaction_splits}）与未撤销结算（{@code aa_settlements}），
 * 计算全部下沉到纯函数 {@link AaMath}（以「分」为单位精确运算）：</p>
 * <ul>
 *   <li>{@link AaMath#netAmounts}：{@code net = Σ付款 − Σ应摊 − Σ收到结算 + Σ付出结算}，
 *       全体 Σnet = 0（Property 2 / 需求 5.1）。</li>
 *   <li>{@link AaMath#minimalSettlements}：债权最大 ↔ 债务最大贪心配对，转账笔数 ≤ 成员数 − 1
 *       （Property 3 / 需求 5.3）。</li>
 * </ul>
 *
 * <p>软删除的 AA 支出由 {@link Transaction} 的 {@code @SQLRestriction("deleted_at is null")} 自动排除，
 * 已撤销结算由 {@code reverted_at IS NULL} 过滤排除（需求 9.3：删除 / 撤销后重算）。本服务只读、无副作用。</p>
 */
@Service
public class AaSettlementService {

    /** 结算金额上限（DECIMAL(18,2)），与 {@code AaExpenseService} 保持一致。 */
    static final BigDecimal AMOUNT_MAX = new BigDecimal("9999999999999999.99");

    private final TransactionRepository transactionRepository;
    private final TransactionSplitRepository splitRepository;
    private final AaSettlementRepository settlementRepository;
    private final LedgerRepository ledgerRepository;
    private final LedgerMemberRepository memberRepository;
    private final AccountRepository accountRepository;
    private final Clock clock;

    public AaSettlementService(
            TransactionRepository transactionRepository,
            TransactionSplitRepository splitRepository,
            AaSettlementRepository settlementRepository,
            LedgerRepository ledgerRepository,
            LedgerMemberRepository memberRepository,
            AccountRepository accountRepository,
            Clock clock) {
        this.transactionRepository = transactionRepository;
        this.splitRepository = splitRepository;
        this.settlementRepository = settlementRepository;
        this.ledgerRepository = ledgerRepository;
        this.memberRepository = memberRepository;
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    /**
     * 计算某 AA 账本的每人净额与最少转账清算建议（派生、只读）。
     *
     * <p>前置校验：账本存在、为 AA 类型、当前用户为成员（越权 / 不存在一律 NOT_FOUND，不泄漏存在性，
     * 需求 9.4）。归档（只读）账本仍可查看结算视图（需求 8.3：归档保留查看）。</p>
     *
     * @param userId   当前会话用户
     * @param ledgerId AA 账本 id（路径参数）
     * @return 每人净额（应收正 / 应付负）+ 建议转账（{@code from → to}，金额 2dp）+ 是否已全部结清
     * @throws ApiException NOT_FOUND（账本不存在 / 非 AA / 当前用户非成员）
     */
    @Transactional(readOnly = true)
    public AaSettlementResponse settlement(Long userId, Long ledgerId) {
        // 账本存在 + 为 AA 类型 + 当前用户为成员（越权返回 NOT_FOUND）。
        ledgerRepository.findById(ledgerId)
                .filter(Ledger::isAa)
                .orElseThrow(() -> ApiException.notFound("账本不存在"));
        List<Long> memberIds = orderedMemberIds(ledgerId);
        if (!new HashSet<>(memberIds).contains(userId)) {
            throw ApiException.notFound("账本不存在");
        }

        // 1) 净额（派生，含未撤销支出与结算）+ 最小化清算（贪心）。
        Map<Long, Long> netCents = computeNetCents(ledgerId);
        List<AaMath.Transfer> transfers = AaMath.minimalSettlements(netCents);

        // 2) 组装响应：净额按当前成员逐一列出（无活动记 0）；建议转账映射为 2dp 金额。
        List<AaSettlementResponse.MemberNet> nets = new ArrayList<>();
        boolean allSettled = true;
        for (Long memberId : memberIds) {
            long cents = netCents.getOrDefault(memberId, 0L);
            if (cents != 0L) {
                allSettled = false;
            }
            nets.add(new AaSettlementResponse.MemberNet(memberId, fromCents(cents)));
        }
        List<AaSettlementResponse.SuggestedTransfer> suggested = transfers.stream()
                .map(t -> new AaSettlementResponse.SuggestedTransfer(
                        t.fromUserId(), t.toUserId(), fromCents(t.amountCents())))
                .toList();

        return new AaSettlementResponse(ledgerId, allSettled, nets, suggested);
    }

    /**
     * 结清一条<b>涉及当前用户</b>的建议转账：对本人侧所选账户增减、落 {@code aa_settlements}、生成
     * 一条 {@code aa_settlement} 展示流水，单事务原子提交（需求 6.1-6.4、6.6，事务边界见 design.md）。
     *
     * <p>方向二选一（另一字段须为空）：</p>
     * <ul>
     *   <li>提供 {@code toUserId} → 本人为付款方（{@code from=本人 → to=toUserId}）：本人账户 {@code −amount}、
     *       应付 {@code −amount}（net 上升，需求 6.3）。</li>
     *   <li>提供 {@code fromUserId} → 本人为收款方（{@code from=fromUserId → to=本人}）：本人账户 {@code +amount}、
     *       应收 {@code −amount}（net 下降，需求 6.2）。</li>
     * </ul>
     *
     * <p>校验（任一不满足抛 {@code AA_SETTLEMENT_INVALID}，零副作用）：金额为正且 ≤2 位小数；恰好提供一方对手且
     * 该对手为本账本成员、非本人；依派生净额，付款方须应付（net&lt;0）、收款方须应收（net&gt;0），
     * 且金额 ≤ {@code min(应付, 应收)}（不超出应结，需求 6.6）。账户按本人加锁增减
     * （{@link AccountRepository#findForUpdateByIdAndUserId}），结算流水不计入消费（需求 6.4）。</p>
     *
     * @param userId      当前用户（结清人 settled_by，且必为转账一方）
     * @param ledgerId    AA 账本 id（按 {@code X-Ledger-Id} 隔离）
     * @param toUserId    收款成员（本人为付款方时提供；否则须为空）
     * @param fromUserId  付款成员（本人为收款方时提供；否则须为空）
     * @param rawAmount   结算金额（&gt;0，2 位小数）
     * @param myAccountId 本人侧所选账户 id（必填）
     * @return 已落库的 {@code aa_settlements} 记录
     * @throws ApiException NOT_FOUND（非 AA / 非成员 / 账户不存在）、AA_LEDGER_ARCHIVED（只读账本）、
     *                      FIELD_REQUIRED（myAccountId 缺失）、AA_SETTLEMENT_INVALID（金额 / 对象 / 方向 / 超额）
     */
    @Transactional
    public AaSettlement settle(Long userId, Long ledgerId, Long toUserId, Long fromUserId,
            BigDecimal rawAmount, Long myAccountId) {
        // 1) 账本存在 + 为 AA 类型 + 当前用户为成员（越权 NOT_FOUND，不泄漏存在性）+ 非只读。
        Ledger ledger = ledgerRepository.findById(ledgerId)
                .filter(Ledger::isAa)
                .orElseThrow(() -> ApiException.notFound("账本不存在"));
        Set<Long> members = new HashSet<>(orderedMemberIds(ledgerId));
        if (!members.contains(userId)) {
            throw ApiException.notFound("账本不存在");
        }
        if (ledger.isArchived()) {
            throw ApiException.aaLedgerArchived();
        }

        // 2) 金额：正数、2 位小数、上限内。
        BigDecimal amount = validateSettlementAmount(rawAmount);

        // 3) 方向：必须恰好提供一方对手；本人为另一方。
        boolean asPayer = toUserId != null && fromUserId == null;
        boolean asReceiver = fromUserId != null && toUserId == null;
        if (asPayer == asReceiver) { // 两者都给或都不给：非法
            throw ApiException.aaSettlementInvalid();
        }
        long from = asPayer ? userId : fromUserId;
        long to = asPayer ? toUserId : userId;
        long counterparty = asPayer ? to : from;
        if (counterparty == userId || !members.contains(counterparty)) {
            throw ApiException.aaSettlementInvalid();
        }

        // 4) 校验金额不超出派生净额：付款方须应付（net<0）、收款方须应收（net>0），
        //    amount ≤ min(应付, 应收)（需求 6.6）。
        Map<Long, Long> netCents = computeNetCents(ledgerId);
        long fromNet = netCents.getOrDefault(from, 0L);
        long toNet = netCents.getOrDefault(to, 0L);
        if (fromNet >= 0 || toNet <= 0) {
            throw ApiException.aaSettlementInvalid();
        }
        long amountCents = toCents(amount);
        long maxSettleCents = Math.min(-fromNet, toNet);
        if (amountCents > maxSettleCents) {
            throw ApiException.aaSettlementInvalid();
        }

        // 5) 锁本人侧账户并按方向增减（真实现金收付）。
        if (myAccountId == null) {
            throw ApiException.fieldRequired("myAccountId");
        }
        Account account = accountRepository.findForUpdateByIdAndUserId(myAccountId, userId)
                .orElseThrow(() -> ApiException.notFound("账户不存在"));
        LocalDateTime now = LocalDateTime.now(clock);
        Long fromAccountId = null;
        Long toAccountId = null;
        if (asReceiver) {
            account.setCurrentBalance(account.getCurrentBalance().add(amount));
            toAccountId = myAccountId;
        } else {
            account.setCurrentBalance(account.getCurrentBalance().subtract(amount));
            fromAccountId = myAccountId;
        }
        account.setUpdatedAt(now);
        accountRepository.save(account);

        // 6) 落 aa_settlements（本人侧账户 id 有值，另一侧待对方结清时补）。
        AaSettlement settlement = new AaSettlement();
        settlement.setLedgerId(ledgerId);
        settlement.setFromUserId(from);
        settlement.setToUserId(to);
        settlement.setAmount(amount);
        settlement.setFromAccountId(fromAccountId);
        settlement.setToAccountId(toAccountId);
        settlement.setSettledBy(userId);
        settlement.setSettledAt(now);
        AaSettlement saved = settlementRepository.save(settlement);

        // 7) 生成展示用结算流水（type=aa_settlement），供统一流水列表追溯；不计入消费统计（需求 6.4）。
        Transaction tx = new Transaction();
        tx.setLedgerId(ledgerId);
        tx.setCreatedBy(userId);
        tx.setPayerUserId(from);
        tx.setType(TransactionType.AA_SETTLEMENT);
        tx.setAmount(amount);
        tx.setAccountId(myAccountId);
        tx.setOccurredAt(now);
        tx.setCreatedAt(now);
        tx.setUpdatedAt(now);
        transactionRepository.save(tx);

        return saved;
    }

    /**
     * 撤销一条已落库的结算：软撤销（置 {@code reverted_at}）+ 回滚<b>本人侧</b>账户增减 + 作废由
     * {@link #settle} 生成的 {@code aa_settlement} 展示流水，单事务原子提交（需求 6.5，事务边界见
     * design.md「撤销结算 / 删除支出：单事务回滚对应账户与派生」）。
     *
     * <p><b>回滚账户（与 {@link #settle} 精确对称，Property 4 / 需求 6.5、7.1）：</b> {@code settle}
     * 只动了结清人（{@code settled_by}）本人侧账户，故撤销亦只回滚该侧，且必由该侧本人执行
     * （账户按本人加锁，{@link AccountRepository#findForUpdateByIdAndUserId}）：</p>
     * <ul>
     *   <li>当前用户为收款方（{@code to_user_id} 且 {@code to_account_id} 有值）：其账户 {@code −amount}
     *       （撤销原 {@code +amount} 入账）。</li>
     *   <li>当前用户为付款方（{@code from_user_id} 且 {@code from_account_id} 有值）：其账户 {@code +amount}
     *       （撤销原 {@code −amount} 出账）。</li>
     * </ul>
     *
     * <p><b>作废展示流水：</b> {@code settle} 生成的展示流水与 {@code aa_settlements} 无直接外键，故按
     * 「同账本 + {@code type=aa_settlement} + 记账人 = {@code settled_by} + 付款成员 = {@code from_user_id}
     * + 账户 = 本人侧结算账户 + 金额相等 + 发生时间 = {@code settled_at}」定位（{@code settle} 中该流水的
     * {@code occurred_at} 与结算 {@code settled_at} 同为一个 {@code now}，构成强匹配），按既有软删除口径置
     * {@code deleted_at}，使统一流水列表与聚合不再计入。未找到匹配（例如已被清理）时跳过、不阻断撤销。</p>
     *
     * <p><b>授权（9.2 风格）：</b> 结算的账户只落在结清人一侧，故仅结清人（既是转账一方、又是账户被动过的人）
     * 可撤销；非该侧的成员（含另一方当事人）判定为无权，抛 {@code LEDGER_FORBIDDEN}。非成员 / 非 AA /
     * 结算不属本账本一律 NOT_FOUND（不泄漏存在性，需求 9.4）。</p>
     *
     * <p><b>撤销后：</b> 净额计算按 {@code reverted_at IS NULL} 过滤，自动忽略本条，债务（net）随之恢复
     * （见 {@link #computeNetCents}）。</p>
     *
     * @param userId       当前用户（须为结清人本人 = 账户被动过的一方）
     * @param ledgerId     AA 账本 id（按 {@code X-Ledger-Id} 隔离）
     * @param settlementId 目标结算记录 id（路径参数）
     * @return 已置 {@code reverted_at} 的结算记录
     * @throws ApiException NOT_FOUND（非 AA / 非成员 / 结算不存在或不属本账本）、
     *                      AA_LEDGER_ARCHIVED（只读账本）、LEDGER_FORBIDDEN（非结清人无权撤销）、
     *                      AA_SETTLEMENT_INVALID（结算已撤销）
     */
    @Transactional
    public AaSettlement revert(Long userId, Long ledgerId, Long settlementId) {
        // 1) 账本存在 + 为 AA 类型 + 当前用户为成员（越权 NOT_FOUND，不泄漏存在性）+ 非只读。
        Ledger ledger = ledgerRepository.findById(ledgerId)
                .filter(Ledger::isAa)
                .orElseThrow(() -> ApiException.notFound("账本不存在"));
        if (!new HashSet<>(orderedMemberIds(ledgerId)).contains(userId)) {
            throw ApiException.notFound("账本不存在");
        }
        if (ledger.isArchived()) {
            throw ApiException.aaLedgerArchived();
        }

        // 2) 定位结算记录（须属本账本；越权 / 不存在 NOT_FOUND）。
        AaSettlement settlement = settlementRepository.findByIdAndLedgerId(settlementId, ledgerId)
                .orElseThrow(() -> ApiException.notFound("结算不存在"));

        // 3) 已撤销的结算不可再次撤销（幂等保护，零副作用）。
        if (settlement.isReverted()) {
            throw ApiException.aaSettlementInvalid();
        }

        // 4) 授权 + 判定本人侧：仅结清人（账户被动过的一方）可撤销，否则 LEDGER_FORBIDDEN。
        boolean asReceiver = userId.equals(settlement.getToUserId())
                && settlement.getToAccountId() != null;
        boolean asPayer = userId.equals(settlement.getFromUserId())
                && settlement.getFromAccountId() != null;
        if (!asReceiver && !asPayer) {
            throw ApiException.ledgerForbidden();
        }

        LocalDateTime now = LocalDateTime.now(clock);

        // 5) 回滚本人侧账户（与 settle 精确对称，无漂移）。
        Long myAccountId = asReceiver ? settlement.getToAccountId() : settlement.getFromAccountId();
        Account account = accountRepository.findForUpdateByIdAndUserId(myAccountId, userId)
                .orElseThrow(() -> ApiException.notFound("账户不存在"));
        if (asReceiver) {
            account.setCurrentBalance(account.getCurrentBalance().subtract(settlement.getAmount()));
        } else {
            account.setCurrentBalance(account.getCurrentBalance().add(settlement.getAmount()));
        }
        account.setUpdatedAt(now);
        accountRepository.save(account);

        // 6) 软撤销结算（净额计算按 reverted_at IS NULL 过滤，债务随之恢复）。
        settlement.setRevertedAt(now);
        AaSettlement saved = settlementRepository.save(settlement);

        // 7) 作废由 settle 生成的展示流水（软删除，保持统一流水/聚合一致）。
        voidDisplayTransaction(settlement, myAccountId, now);

        return saved;
    }

    /**
     * 派生某 AA 账本每人净额（以「分」为单位），供概览等其它只读视图复用（如
     * {@link com.damien.youyu.service.aa.AaLedgerService#overview}）。
     *
     * <p>口径与 {@link #settlement} 完全一致：汇集未撤销 AA 支出（{@code aa_expense} + 其分摊行）与
     * 未撤销结算，下沉 {@link AaMath#netAmounts}；软删除支出由 {@code @SQLRestriction} 自动排除、
     * 已撤销结算由 {@code reverted_at IS NULL} 过滤（Property 2 / 需求 5.1）。本方法只读、无副作用，
     * <b>不做</b>成员 / 账本类型校验（调用方负责），仅按 {@code ledgerId} 汇总。</p>
     *
     * @param ledgerId AA 账本 id
     * @return {@code userId → net（分，应收正 / 应付负）}；无活动的成员不在 map 中（视为 0）
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> netCentsByUser(Long ledgerId) {
        return computeNetCents(ledgerId);
    }

    // ---------------- 内部工具 ----------------

    /**
     * 定位并软删除由 {@link #settle} 生成的 {@code aa_settlement} 展示流水。与 {@code aa_settlements}
     * 无直接外键，按强匹配字段定位：同账本、{@code type=aa_settlement}、记账人 = {@code settled_by}、
     * 付款成员 = {@code from_user_id}、账户 = 本人侧结算账户、金额相等，优先 {@code occurred_at =
     * settled_at}（{@code settle} 中二者同为一个 {@code now}）。软删除行由 {@code @SQLRestriction}
     * 自动排除，故 {@code findByLedgerId} 只返回未删行；未找到匹配时跳过、不阻断撤销。
     */
    private void voidDisplayTransaction(AaSettlement settlement, Long accountId, LocalDateTime now) {
        List<Transaction> candidates = transactionRepository.findByLedgerId(settlement.getLedgerId()).stream()
                .filter(t -> t.getType() == TransactionType.AA_SETTLEMENT)
                .filter(t -> accountId.equals(t.getAccountId()))
                .filter(t -> settlement.getSettledBy().equals(t.getCreatedBy()))
                .filter(t -> settlement.getFromUserId().equals(t.getPayerUserId()))
                .filter(t -> t.getAmount() != null
                        && t.getAmount().compareTo(settlement.getAmount()) == 0)
                .sorted(Comparator.comparing(Transaction::getId))
                .toList();
        Transaction match = candidates.stream()
                .filter(t -> settlement.getSettledAt().equals(t.getOccurredAt()))
                .findFirst()
                .orElseGet(() -> candidates.stream().findFirst().orElse(null));
        if (match != null) {
            match.setDeletedAt(now);
            match.setUpdatedAt(now);
            transactionRepository.save(match);
        }
    }

    /**
     * 计算某账本每人净额（分）：汇集未撤销 AA 支出（{@code aa_expense} + 分摊行）与未撤销结算，
     * 下沉 {@link AaMath#netAmounts}。软删除支出由 {@code @SQLRestriction} 自动排除，
     * 已撤销结算由 {@code reverted_at IS NULL} 过滤（需求 9.3）。
     */
    private Map<Long, Long> computeNetCents(Long ledgerId) {
        List<Transaction> expenseTxs = transactionRepository.findByLedgerId(ledgerId).stream()
                .filter(t -> t.getType() == TransactionType.AA_EXPENSE)
                .toList();
        Map<Long, Map<Long, Long>> sharesByTx = loadShareCents(expenseTxs);
        List<AaMath.Expense> expenses = new ArrayList<>();
        for (Transaction tx : expenseTxs) {
            Map<Long, Long> shares = sharesByTx.getOrDefault(tx.getId(), Map.of());
            expenses.add(new AaMath.Expense(tx.getPayerUserId(), toCents(tx.getAmount()), shares));
        }
        List<AaMath.Transfer> settlements = new ArrayList<>();
        for (AaSettlement s : settlementRepository.findByLedgerIdAndRevertedAtIsNull(ledgerId)) {
            settlements.add(new AaMath.Transfer(s.getFromUserId(), s.getToUserId(), toCents(s.getAmount())));
        }
        return AaMath.netAmounts(expenses, settlements);
    }

    /** 校验结算金额：非空、正数、最多两位小数、上限内；否则 {@code AA_SETTLEMENT_INVALID}（零副作用）。 */
    private BigDecimal validateSettlementAmount(BigDecimal rawAmount) {
        if (rawAmount == null) {
            throw ApiException.aaSettlementInvalid();
        }
        BigDecimal normalized;
        try {
            normalized = rawAmount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw ApiException.aaSettlementInvalid();
        }
        if (normalized.signum() <= 0 || normalized.compareTo(AMOUNT_MAX) > 0) {
            throw ApiException.aaSettlementInvalid();
        }
        return normalized;
    }

    /** 当前账本成员 user_id（按 user_id 升序，保证净额列表顺序稳定）。 */
    private List<Long> orderedMemberIds(Long ledgerId) {
        return memberRepository.findByLedgerId(ledgerId).stream()
                .map(LedgerMember::getUserId)
                .sorted(Comparator.naturalOrder())
                .toList();
    }

    /** 批量读取各 AA 支出的分摊行，归并为 {@code txId → (participant → shareCents)}。 */
    private Map<Long, Map<Long, Long>> loadShareCents(List<Transaction> expenseTxs) {
        Map<Long, Map<Long, Long>> out = new LinkedHashMap<>();
        if (expenseTxs.isEmpty()) {
            return out;
        }
        Set<Long> txIds = new HashSet<>();
        for (Transaction tx : expenseTxs) {
            txIds.add(tx.getId());
        }
        for (TransactionSplit split : splitRepository.findByTransactionIdIn(txIds)) {
            out.computeIfAbsent(split.getTransactionId(), k -> new LinkedHashMap<>())
                    .put(split.getParticipantUserId(), toCents(split.getShareAmount()));
        }
        return out;
    }

    private static long toCents(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    private static BigDecimal fromCents(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
