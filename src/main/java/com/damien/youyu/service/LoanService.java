package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.Loan;
import com.damien.youyu.domain.LoanDirection;
import com.damien.youyu.domain.LoanRepayment;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.LoanRepaymentRepository;
import com.damien.youyu.repository.LoanRepository;

/**
 * 借贷往来服务：借入(BORROW)/借出(LEND)款项 + 收款/还款子台账（部分还款）。
 *
 * <p><b>用户级隔离</b>：账户是独立于账本的用户级实体，借贷影响账户余额与净资产，故借贷同样按
 * {@code userId} 隔离（与账本无关）。所有读写按会话用户过滤，越权返回 {@code NOT_FOUND}。</p>
 *
 * <p><b>资金联动（{@code accountId} 非空时，均为永久增量，不随结清回滚）</b>：</p>
 * <ul>
 *   <li>借出(LEND)：创建 借出账户 −amount；每次收款 收款钱包 +r。</li>
 *   <li>借入(BORROW)：创建 存入账户 +amount；每次还款 还款账户 −r。</li>
 * </ul>
 *
 * <p>结清由累计收款/还款驱动：{@code repaidAmount >= amount} 即结清。剩余 = amount − repaidAmount。
 * 账户余额重算已纳入初始增量与还款增量。金额一律 {@link BigDecimal}。</p>
 */
@Service
public class LoanService {

    static final int COUNTERPARTY_MIN = 1;
    static final int COUNTERPARTY_MAX = 50;
    static final int NOTE_MAX = 200;

    static final BigDecimal AMOUNT_MIN = new BigDecimal("0.01");
    static final BigDecimal AMOUNT_MAX = new BigDecimal("9999999999999999.99");

    private final LoanRepository loanRepository;
    private final LoanRepaymentRepository repaymentRepository;
    private final AccountRepository accountRepository;
    private final Clock clock;

    public LoanService(LoanRepository loanRepository, LoanRepaymentRepository repaymentRepository,
            AccountRepository accountRepository, Clock clock) {
        this.loanRepository = loanRepository;
        this.repaymentRepository = repaymentRepository;
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    /** 列出本人全部借贷（未结清优先，其次发生时间倒序）。 */
    @Transactional(readOnly = true)
    public List<Loan> list(Long userId) {
        return loanRepository.findByUserIdOrderBySettledAscOccurredAtDescIdDesc(userId);
    }

    /** 定位单条借贷（越权 NOT_FOUND）。 */
    @Transactional(readOnly = true)
    public Loan get(Long userId, Long id) {
        return loanRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("借贷记录不存在"));
    }

    /** 某借贷的收款/还款明细（发生时间倒序）。 */
    @Transactional(readOnly = true)
    public List<LoanRepayment> repayments(Long userId, Long loanId) {
        get(userId, loanId); // 校验归属
        return repaymentRepository.findByLoanIdOrderByOccurredAtDescIdDesc(loanId);
    }

    /**
     * 某账户的借贷流水投影：借贷初始出/入账 + 收款/还款，均以该账户视角的方向增量给出（流入正、流出负）。
     * 供「账户流水」合并展示（借贷为用户级，不进入账本流水）。
     */
    @Transactional(readOnly = true)
    public List<com.damien.youyu.api.dto.AccountLoanEntryResponse> accountEntries(Long userId, Long accountId) {
        java.util.List<com.damien.youyu.api.dto.AccountLoanEntryResponse> out = new java.util.ArrayList<>();
        for (Loan l : loanRepository.findByUserIdAndAccountId(userId, accountId)) {
            out.add(new com.damien.youyu.api.dto.AccountLoanEntryResponse(
                    "INITIAL", l.getId(), l.getDirection().name(), l.getCounterparty(),
                    activeDelta(l.getDirection(), l.getAmount()), l.getOccurredAt(), l.getNote()));
        }
        for (LoanRepayment r : repaymentRepository.findByUserIdAndAccountId(userId, accountId)) {
            Loan l = loanRepository.findById(r.getLoanId()).orElse(null);
            if (l == null) {
                continue;
            }
            out.add(new com.damien.youyu.api.dto.AccountLoanEntryResponse(
                    "REPAYMENT", l.getId(), l.getDirection().name(), l.getCounterparty(),
                    activeDelta(l.getDirection(), r.getAmount()).negate(), r.getOccurredAt(), r.getNote()));
        }
        return out;
    }

    /** 某方向未结清剩余合计（借入/待还、借出/待收），无记录返回 0.00。 */
    @Transactional(readOnly = true)
    public BigDecimal outstanding(Long userId, LoanDirection direction) {
        BigDecimal sum = loanRepository.sumOutstandingByDirection(userId, direction);
        return (sum == null ? BigDecimal.ZERO : sum).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 新建一笔借贷（默认未结清、未收/还）。若关联账户，则按方向即时变动账户余额。
     */
    @Transactional
    public Loan create(Long userId, String rawDirection, String rawCounterparty,
            BigDecimal rawAmount, Long accountId, LocalDateTime occurredAt, LocalDateTime dueDate,
            boolean includeInTotal, String rawNote) {
        LoanDirection direction = validateDirection(rawDirection);
        String counterparty = validateCounterparty(rawCounterparty);
        BigDecimal amount = validateAmount(rawAmount);
        String note = validateNote(rawNote);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime when = occurredAt != null ? occurredAt : now;
        Loan loan = new Loan();
        loan.setUserId(userId);
        loan.setDirection(direction);
        loan.setCounterparty(counterparty);
        loan.setAmount(amount);
        loan.setRepaidAmount(BigDecimal.ZERO);
        loan.setAccountId(accountId);
        loan.setOccurredAt(when);
        loan.setDueDate(dueDate);
        loan.setIncludeInTotal(includeInTotal);
        loan.setSettled(false);
        loan.setNote(note);
        loan.setCreatedAt(now);
        loan.setUpdatedAt(now);
        Loan saved = loanRepository.save(loan);

        applyToAccount(userId, accountId, activeDelta(direction, amount), now);
        return saved;
    }

    /**
     * 修改借贷：回补旧初始增量后按新值施加；金额不得小于已收/已还累计。
     */
    @Transactional
    public Loan update(Long userId, Long id, String rawDirection, String rawCounterparty,
            BigDecimal rawAmount, Long accountId, LocalDateTime occurredAt, LocalDateTime dueDate,
            boolean includeInTotal, String rawNote) {
        Loan loan = get(userId, id);

        LoanDirection newDirection = validateDirection(rawDirection);
        String counterparty = validateCounterparty(rawCounterparty);
        BigDecimal newAmount = validateAmount(rawAmount);
        String note = validateNote(rawNote);
        if (newAmount.compareTo(loan.getRepaidAmount()) < 0) {
            throw ApiException.loanFieldInvalid("amount", "金额不能小于已收/已还金额");
        }
        LocalDateTime now = LocalDateTime.now(clock);

        applyToAccount(userId, loan.getAccountId(),
                activeDelta(loan.getDirection(), loan.getAmount()).negate(), now);

        loan.setDirection(newDirection);
        loan.setCounterparty(counterparty);
        loan.setAmount(newAmount);
        loan.setAccountId(accountId);
        loan.setOccurredAt(occurredAt != null ? occurredAt : loan.getOccurredAt());
        loan.setDueDate(dueDate);
        loan.setIncludeInTotal(includeInTotal);
        loan.setNote(note);
        refreshSettled(loan, now);
        loan.setUpdatedAt(now);
        Loan saved = loanRepository.save(loan);

        applyToAccount(userId, accountId, activeDelta(newDirection, newAmount), now);
        return saved;
    }

    /**
     * 删除借贷：回补初始增量与全部收款/还款增量，再级联删除子台账。
     */
    @Transactional
    public void delete(Long userId, Long id) {
        Loan loan = get(userId, id);
        LocalDateTime now = LocalDateTime.now(clock);
        applyToAccount(userId, loan.getAccountId(),
                activeDelta(loan.getDirection(), loan.getAmount()).negate(), now);
        for (LoanRepayment r : repaymentRepository.findByLoanIdOrderByOccurredAtDescIdDesc(id)) {
            applyToAccount(userId, r.getAccountId(), activeDelta(loan.getDirection(), r.getAmount()), now);
        }
        repaymentRepository.deleteByLoanId(id);
        loanRepository.delete(loan);
    }

    // ---------------- 收款 / 还款 ----------------

    /**
     * 新增一笔收款(借出)/还款(借入)。金额不得超过剩余；关联账户即时入账/出账；累计达本金即结清。
     */
    @Transactional
    public LoanRepayment addRepayment(Long userId, Long loanId, BigDecimal rawAmount,
            Long accountId, LocalDateTime occurredAt, String rawNote) {
        Loan loan = get(userId, loanId);
        BigDecimal amount = validateAmount(rawAmount);
        BigDecimal remaining = loan.getAmount().subtract(loan.getRepaidAmount());
        if (amount.compareTo(remaining) > 0) {
            throw ApiException.loanFieldInvalid("amount",
                    loan.getDirection() == LoanDirection.LEND ? "收款金额超过剩余待收" : "还款金额超过剩余待还");
        }
        String note = validateNote(rawNote);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime when = occurredAt != null ? occurredAt : now;

        applyToAccount(userId, accountId, activeDelta(loan.getDirection(), amount).negate(), now);

        LoanRepayment r = new LoanRepayment();
        r.setLoanId(loanId);
        r.setUserId(userId);
        r.setAmount(amount);
        r.setAccountId(accountId);
        r.setOccurredAt(when);
        r.setNote(note);
        r.setCreatedAt(now);
        LoanRepayment saved = repaymentRepository.save(r);

        loan.setRepaidAmount(loan.getRepaidAmount().add(amount));
        refreshSettled(loan, now);
        loan.setUpdatedAt(now);
        loanRepository.save(loan);
        return saved;
    }

    /** 删除一笔收款/还款：回补账户增量并回退累计。 */
    @Transactional
    public void deleteRepayment(Long userId, Long repaymentId) {
        LoanRepayment r = repaymentRepository.findByIdAndUserId(repaymentId, userId)
                .orElseThrow(() -> ApiException.notFound("收款/还款记录不存在"));
        Loan loan = get(userId, r.getLoanId());
        LocalDateTime now = LocalDateTime.now(clock);

        applyToAccount(userId, r.getAccountId(), activeDelta(loan.getDirection(), r.getAmount()), now);

        loan.setRepaidAmount(loan.getRepaidAmount().subtract(r.getAmount()));
        if (loan.getRepaidAmount().signum() < 0) {
            loan.setRepaidAmount(BigDecimal.ZERO);
        }
        refreshSettled(loan, now);
        loan.setUpdatedAt(now);
        loanRepository.save(loan);
        repaymentRepository.delete(r);
    }

    // ---------------- 内部 ----------------

    /** 结清态由累计收/还是否达本金推导。 */
    private void refreshSettled(Loan loan, LocalDateTime now) {
        boolean settled = loan.getRepaidAmount().compareTo(loan.getAmount()) >= 0;
        loan.setSettled(settled);
        loan.setSettledAt(settled ? now : null);
    }

    /** 初始出/入账增量：借入 +amount（入账）、借出 −amount（出账）。 */
    private BigDecimal activeDelta(LoanDirection direction, BigDecimal amount) {
        return direction == LoanDirection.BORROW ? amount : amount.negate();
    }

    /**
     * 对本人账户施加余额增量并加行级锁（accountId 为空或增量为 0 时跳过）。
     * 借贷为用户级，账户只需归属本人（不绑定账本）。
     */
    private void applyToAccount(Long userId, Long accountId, BigDecimal delta, LocalDateTime now) {
        if (accountId == null || delta.signum() == 0) {
            return;
        }
        Account account = accountRepository.findForUpdateById(accountId)
                .orElseThrow(() -> ApiException.notFound("账户不存在"));
        if (!account.getUserId().equals(userId)) {
            throw ApiException.notFound("账户不存在");
        }
        account.setCurrentBalance(account.getCurrentBalance().add(delta));
        account.setUpdatedAt(now);
        accountRepository.save(account);
    }

    // ---------------- 校验 ----------------

    private LoanDirection validateDirection(String rawDirection) {
        if (rawDirection == null || rawDirection.isBlank()) {
            throw ApiException.loanFieldInvalid("direction", "借贷方向不能为空");
        }
        try {
            return LoanDirection.valueOf(rawDirection.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw ApiException.loanFieldInvalid("direction", "不支持的借贷方向");
        }
    }

    private String validateCounterparty(String rawCounterparty) {
        String cp = rawCounterparty == null ? "" : rawCounterparty.trim();
        if (cp.length() < COUNTERPARTY_MIN || cp.length() > COUNTERPARTY_MAX) {
            throw ApiException.loanFieldInvalid("counterparty", "对方名称长度需为 1 到 50 个字符");
        }
        return cp;
    }

    private BigDecimal validateAmount(BigDecimal rawAmount) {
        if (rawAmount == null) {
            throw ApiException.loanFieldInvalid("amount", "金额不能为空");
        }
        BigDecimal normalized;
        try {
            normalized = rawAmount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw ApiException.loanFieldInvalid("amount", "金额最多两位小数");
        }
        if (normalized.compareTo(AMOUNT_MIN) < 0 || normalized.compareTo(AMOUNT_MAX) > 0) {
            throw ApiException.loanFieldInvalid("amount",
                    "金额需在 0.01 至 9,999,999,999,999,999.99 之间");
        }
        return normalized;
    }

    private String validateNote(String rawNote) {
        if (rawNote == null) {
            return null;
        }
        String note = rawNote.trim();
        if (note.isEmpty()) {
            return null;
        }
        if (note.length() > NOTE_MAX) {
            throw ApiException.loanFieldInvalid("note", "备注最多 200 个字符");
        }
        return note;
    }
}
