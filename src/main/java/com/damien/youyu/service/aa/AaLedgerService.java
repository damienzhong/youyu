package com.damien.youyu.service.aa;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.api.dto.AaOverviewResponse;
import com.damien.youyu.domain.AaSettlement;
import com.damien.youyu.domain.Ledger;
import com.damien.youyu.domain.LedgerMember;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.domain.TransactionSplit;
import com.damien.youyu.domain.TransactionType;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AaSettlementRepository;
import com.damien.youyu.repository.LedgerMemberRepository;
import com.damien.youyu.repository.LedgerRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.repository.TransactionSplitRepository;

/**
 * AA 账本账本级只读视图服务：概览（首页 hero 三口径 + 成员净额 + 流水，需求 2.1、4.4、5.1、7.1、7.2、8.1）。
 *
 * <p>净额计算复用 {@link AaSettlementService#netCentsByUser}（下沉 {@link AaMath}），保证与结算视图口径
 * 完全一致（Property 2 / 需求 5.1）。本服务只读、无副作用；成员校验与越权 NOT_FOUND（需求 9.4）在此完成，
 * 归档（只读）账本仍可查看（需求 8.3）。金额一律以「分」精确运算、以 {@link BigDecimal}（2 位小数）承载。</p>
 *
 * <h4>三口径定义（当前用户视角，见 {@link AaOverviewResponse.Calibers}）：</h4>
 * <ul>
 *   <li><b>账户已支出（accountPaid）</b> = Σ(自付款 AA 支出实付额) + Σ(作为付款方结算付出)
 *       − Σ(作为收款方结算收到)。等于当前用户账户余额因本账本产生的净下降，符合账户守恒
 *       （账户只反映真实进出账户的钱：付款扣款、结算收付，需求 7.1）。</li>
 *   <li><b>我的消费（myConsumption）</b> = Σ(各 AA 支出中当前用户 {@code share_amount})；应收 / 应付与
 *       结算均不计入（需求 4.4、7.2）。</li>
 *   <li><b>待收回（receivable）</b> = {@code max(net, 0)}（net &gt; 0 即别人尚欠他的应收，需求 5.1、5.2）。</li>
 * </ul>
 */
@Service
public class AaLedgerService {

    private final LedgerRepository ledgerRepository;
    private final LedgerMemberRepository memberRepository;
    private final TransactionRepository transactionRepository;
    private final TransactionSplitRepository splitRepository;
    private final AaSettlementRepository settlementRepository;
    private final AaSettlementService aaSettlementService;

    public AaLedgerService(
            LedgerRepository ledgerRepository,
            LedgerMemberRepository memberRepository,
            TransactionRepository transactionRepository,
            TransactionSplitRepository splitRepository,
            AaSettlementRepository settlementRepository,
            AaSettlementService aaSettlementService) {
        this.ledgerRepository = ledgerRepository;
        this.memberRepository = memberRepository;
        this.transactionRepository = transactionRepository;
        this.splitRepository = splitRepository;
        this.settlementRepository = settlementRepository;
        this.aaSettlementService = aaSettlementService;
    }

    /**
     * 某 AA 账本的概览（派生、只读）：当前用户三口径 + 每人净额 + 合并流水。
     *
     * <p>前置校验：账本存在、为 AA 类型、当前用户为成员（越权 / 不存在一律 NOT_FOUND，不泄漏存在性，
     * 需求 9.4）。归档（只读）账本仍可查看（需求 8.3）。</p>
     *
     * @param userId   当前会话用户
     * @param ledgerId AA 账本 id（路径参数）
     * @return 三口径（账户已支出 / 我的消费 / 待收回）+ 成员净额（应收正 / 应付负）+ 流水（支出 + 未撤销结算）
     * @throws ApiException NOT_FOUND（账本不存在 / 非 AA / 当前用户非成员）
     */
    @Transactional(readOnly = true)
    public AaOverviewResponse overview(Long userId, Long ledgerId) {
        // 1) 账本存在 + 为 AA 类型 + 当前用户为成员（越权返回 NOT_FOUND）。归档账本仍可查看。
        Ledger ledger = ledgerRepository.findById(ledgerId)
                .filter(Ledger::isAa)
                .orElseThrow(() -> ApiException.notFound("账本不存在"));
        List<Long> memberIds = memberRepository.findByLedgerId(ledgerId).stream()
                .map(LedgerMember::getUserId)
                .sorted(Comparator.naturalOrder())
                .toList();
        if (!new HashSet<>(memberIds).contains(userId)) {
            throw ApiException.notFound("账本不存在");
        }

        // 2) 每人净额（复用结算服务的净额派生，口径一致）。
        Map<Long, Long> netCents = aaSettlementService.netCentsByUser(ledgerId);
        boolean allSettled = true;
        List<AaOverviewResponse.MemberNet> memberNets = new ArrayList<>();
        for (Long memberId : memberIds) {
            long cents = netCents.getOrDefault(memberId, 0L);
            if (cents != 0L) {
                allSettled = false;
            }
            memberNets.add(new AaOverviewResponse.MemberNet(memberId, fromCents(cents)));
        }

        // 3) 加载本账本 AA 支出与其分摊行；预取当前用户在各笔的分摊额（判定「我摊」）。
        List<Transaction> expenses = transactionRepository.findByLedgerId(ledgerId).stream()
                .filter(t -> t.getType() == TransactionType.AA_EXPENSE)
                .toList();
        Map<Long, Long> myShareCentsByTx = myShareCentsByTx(expenses, userId);

        // 4) 三口径（当前用户视角）。
        long accountPaidCents = 0L;
        long myConsumptionCents = 0L;
        for (Transaction tx : expenses) {
            // 账户已支出：自付款（payer=本人）实付额从账户真实流出（付款人非本人不触本人账户）。
            if (userId.equals(tx.getPayerUserId()) && tx.getAccountId() != null) {
                accountPaidCents += toCents(tx.getAmount());
            }
            // 我的消费：当前用户在该笔的自身分摊份额（非参与人不计）。
            myConsumptionCents += myShareCentsByTx.getOrDefault(tx.getId(), 0L);
        }
        List<AaSettlement> activeSettlements =
                settlementRepository.findByLedgerIdAndRevertedAtIsNull(ledgerId);
        for (AaSettlement s : activeSettlements) {
            // 结算现金流：作为付款方本人账户流出（+），作为收款方本人账户流入（−），净额并入账户已支出。
            if (userId.equals(s.getFromUserId()) && s.getFromAccountId() != null) {
                accountPaidCents += toCents(s.getAmount());
            }
            if (userId.equals(s.getToUserId()) && s.getToAccountId() != null) {
                accountPaidCents -= toCents(s.getAmount());
            }
        }
        long receivableCents = Math.max(netCents.getOrDefault(userId, 0L), 0L);
        AaOverviewResponse.Calibers calibers = new AaOverviewResponse.Calibers(
                fromCents(accountPaidCents), fromCents(myConsumptionCents), fromCents(receivableCents));

        // 5) 流水：AA 支出 + 未撤销结算，合并后按发生时间倒序（同刻按 id 倒序）。
        List<AaOverviewResponse.TransactionItem> items = new ArrayList<>();
        for (Transaction tx : expenses) {
            // 参与人（含份额为 0 者）标「我摊」金额；非参与人为 null。
            BigDecimal myShare = myShareCentsByTx.containsKey(tx.getId())
                    ? fromCents(myShareCentsByTx.get(tx.getId()))
                    : null;
            items.add(new AaOverviewResponse.TransactionItem(
                    tx.getId(), TransactionType.AA_EXPENSE.getCode(), tx.getAmount(), tx.getOccurredAt(),
                    tx.getCategoryId(), tx.getNote(), tx.getPayerUserId(), myShare, null, null));
        }
        for (AaSettlement s : activeSettlements) {
            items.add(new AaOverviewResponse.TransactionItem(
                    s.getId(), TransactionType.AA_SETTLEMENT.getCode(), s.getAmount(), s.getSettledAt(),
                    null, null, null, null, s.getFromUserId(), s.getToUserId()));
        }
        items.sort(Comparator.comparing(AaOverviewResponse.TransactionItem::occurredAt)
                .reversed()
                .thenComparing(Comparator.comparing(AaOverviewResponse.TransactionItem::id).reversed()));

        return new AaOverviewResponse(ledgerId, ledger.isArchived(), allSettled, calibers, memberNets, items);
    }

    // ---------------- 内部工具 ----------------

    /**
     * 预取当前用户在各 AA 支出的分摊额（分），归并为 {@code txId → myShareCents}。
     * 仅收录当前用户为参与人的分摊行；非参与人的笔不在 map 中（据此区分「我摊」与「未参与」）。
     */
    private Map<Long, Long> myShareCentsByTx(List<Transaction> expenses, Long userId) {
        Map<Long, Long> out = new HashMap<>();
        if (expenses.isEmpty()) {
            return out;
        }
        Set<Long> txIds = new HashSet<>();
        for (Transaction tx : expenses) {
            txIds.add(tx.getId());
        }
        for (TransactionSplit split : splitRepository.findByTransactionIdIn(txIds)) {
            if (userId.equals(split.getParticipantUserId())) {
                out.put(split.getTransactionId(), toCents(split.getShareAmount()));
            }
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
