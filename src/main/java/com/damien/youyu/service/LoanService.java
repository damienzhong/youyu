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
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.LoanRepository;

/**
 * 借贷往来服务：借入(BORROW)/借出(LEND)款项的增删改、结清切换与未结汇总。
 *
 * <p>核心约束：</p>
 * <ul>
 *   <li>创建/修改校验：方向属于 {@link LoanDirection}；对方去空白后 1-50；本金在
 *       [0.01, 9,999,999,999,999,999.99] 且最多两位小数；发生时间必填；备注 <=200。</li>
 *   <li>结清切换：settled=true 时记录 settled_at=now；置回未结清时清空 settled_at。</li>
 *   <li>汇总：借入/待还 = Σ 未结清 BORROW；借出/待收 = Σ 未结清 LEND。</li>
 * </ul>
 *
 * <p><b>资金联动（{@code accountId} 非空时）</b>：借出(LEND)从该账户出账（余额 −amount），
 * 借入(BORROW)入该账户（余额 +amount）；结清或删除未结记录时回补相反增量。未结待收/待还是否
 * 计入净资产由 {@code includeInTotal} 控制（前端在资产页据此调整净资产，避免与账户余额重复计算）。
 * 账户余额重算（{@code AccountService.recompute}）已纳入未结借贷净增量，保证与实时增量一致。</p>
 *
 * <p>所有操作按会话 {@code ledgerId} 隔离：读取/修改/删除他人记录一律返回 {@code NOT_FOUND}。
 * 金额一律 {@link BigDecimal}。</p>
 */
@Service
public class LoanService {

    static final int COUNTERPARTY_MIN = 1;
    static final int COUNTERPARTY_MAX = 50;
    static final int NOTE_MAX = 200;

    /** 本金允许范围（DECIMAL(18,2)，恒为正）。 */
    static final BigDecimal AMOUNT_MIN = new BigDecimal("0.01");
    static final BigDecimal AMOUNT_MAX = new BigDecimal("9999999999999999.99");

    private final LoanRepository loanRepository;
    private final LedgerAccountResolver accountResolver;
    private final AccountRepository accountRepository;
    private final Clock clock;

    public LoanService(LoanRepository loanRepository, LedgerAccountResolver accountResolver,
            AccountRepository accountRepository, Clock clock) {
        this.loanRepository = loanRepository;
        this.accountResolver = accountResolver;
        this.accountRepository = accountRepository;
        this.clock = clock;
    }

    /** 列出本人全部借贷（未结清优先，其次发生时间倒序）。 */
    @Transactional(readOnly = true)
    public List<Loan> list(Long ledgerId) {
        return loanRepository.findByLedgerIdOrderBySettledAscOccurredAtDescIdDesc(ledgerId);
    }

    /** 某方向未结清金额合计（借入/待还、借出/待收），无记录返回 0.00。 */
    @Transactional(readOnly = true)
    public BigDecimal outstanding(Long ledgerId, LoanDirection direction) {
        BigDecimal sum = loanRepository.sumOutstandingByDirection(ledgerId, direction);
        return (sum == null ? BigDecimal.ZERO : sum).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 新建一笔借贷（默认未结清）。若关联账户，则按方向即时变动账户余额。
     *
     * @throws ApiException LOAN_FIELD_INVALID（字段非法）/ NOT_FOUND（账户不可用）
     */
    @Transactional
    public Loan create(Long userId, Long ledgerId, String rawDirection, String rawCounterparty,
            BigDecimal rawAmount, Long accountId, LocalDateTime occurredAt, LocalDateTime dueDate,
            boolean includeInTotal, String rawNote) {
        LoanDirection direction = validateDirection(rawDirection);
        String counterparty = validateCounterparty(rawCounterparty);
        BigDecimal amount = validateAmount(rawAmount);
        String note = validateNote(rawNote);

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime when = occurredAt != null ? occurredAt : now;
        Loan loan = new Loan();
        loan.setLedgerId(ledgerId);
        loan.setDirection(direction);
        loan.setCounterparty(counterparty);
        loan.setAmount(amount);
        loan.setAccountId(accountId);
        loan.setOccurredAt(when);
        loan.setDueDate(dueDate);
        loan.setIncludeInTotal(includeInTotal);
        loan.setSettled(false);
        loan.setNote(note);
        loan.setCreatedAt(now);
        loan.setUpdatedAt(now);
        Loan saved = loanRepository.save(loan);

        // 未结新记录即时变动账户余额。
        applyToAccount(userId, ledgerId, accountId, activeDelta(direction, amount), now);
        return saved;
    }

    /**
     * 修改一笔借贷（不改结清状态）：先回补旧的余额影响，再按新值施加（仅未结清时联动余额）。
     *
     * @throws ApiException NOT_FOUND / LOAN_FIELD_INVALID
     */
    @Transactional
    public Loan update(Long userId, Long ledgerId, Long id, String rawDirection, String rawCounterparty,
            BigDecimal rawAmount, Long accountId, LocalDateTime occurredAt, LocalDateTime dueDate,
            boolean includeInTotal, String rawNote) {
        Loan loan = loanRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("借贷记录不存在"));

        LoanDirection newDirection = validateDirection(rawDirection);
        String counterparty = validateCounterparty(rawCounterparty);
        BigDecimal newAmount = validateAmount(rawAmount);
        String note = validateNote(rawNote);
        LocalDateTime now = LocalDateTime.now(clock);

        // 仅未结清记录参与余额联动：先按旧值回补，再按新值施加。
        if (!loan.isSettled()) {
            applyToAccount(userId, ledgerId, loan.getAccountId(),
                    activeDelta(loan.getDirection(), loan.getAmount()).negate(), now);
        }

        loan.setDirection(newDirection);
        loan.setCounterparty(counterparty);
        loan.setAmount(newAmount);
        loan.setAccountId(accountId);
        loan.setOccurredAt(occurredAt != null ? occurredAt : loan.getOccurredAt());
        loan.setDueDate(dueDate);
        loan.setIncludeInTotal(includeInTotal);
        loan.setNote(note);
        loan.setUpdatedAt(now);
        Loan saved = loanRepository.save(loan);

        if (!loan.isSettled()) {
            applyToAccount(userId, ledgerId, accountId, activeDelta(newDirection, newAmount), now);
        }
        return saved;
    }

    /**
     * 切换结清状态：结清记录 settled_at=now 并回补账户余额；置回未结清清空 settled_at 并重新施加。
     *
     * @throws ApiException NOT_FOUND
     */
    @Transactional
    public Loan setSettled(Long userId, Long ledgerId, Long id, boolean settled) {
        Loan loan = loanRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("借贷记录不存在"));
        LocalDateTime now = LocalDateTime.now(clock);

        if (settled && !loan.isSettled()) {
            // 结清：借出收回 / 借入还清，回补相反增量。
            applyToAccount(userId, ledgerId, loan.getAccountId(),
                    activeDelta(loan.getDirection(), loan.getAmount()).negate(), now);
        } else if (!settled && loan.isSettled()) {
            // 恢复未结清：重新施加增量。
            applyToAccount(userId, ledgerId, loan.getAccountId(),
                    activeDelta(loan.getDirection(), loan.getAmount()), now);
        }

        loan.setSettled(settled);
        loan.setSettledAt(settled ? now : null);
        loan.setUpdatedAt(now);
        return loanRepository.save(loan);
    }

    /**
     * 删除一笔借贷：若为未结清且关联账户，删除前回补账户余额。
     *
     * @throws ApiException NOT_FOUND
     */
    @Transactional
    public void delete(Long userId, Long ledgerId, Long id) {
        Loan loan = loanRepository.findByIdAndLedgerId(id, ledgerId)
                .orElseThrow(() -> ApiException.notFound("借贷记录不存在"));
        if (!loan.isSettled()) {
            applyToAccount(userId, ledgerId, loan.getAccountId(),
                    activeDelta(loan.getDirection(), loan.getAmount()).negate(),
                    LocalDateTime.now(clock));
        }
        loanRepository.delete(loan);
    }

    // ---------------- 余额联动 ----------------

    /** 未结借贷对关联账户余额的增量：借入 +amount（入账）、借出 −amount（出账）。 */
    private BigDecimal activeDelta(LoanDirection direction, BigDecimal amount) {
        return direction == LoanDirection.BORROW ? amount : amount.negate();
    }

    /** 对关联账户施加余额增量（accountId 为空或增量为 0 时跳过）。账户须在该账本对本人可用。 */
    private void applyToAccount(Long userId, Long ledgerId, Long accountId, BigDecimal delta,
            LocalDateTime now) {
        if (accountId == null || delta.signum() == 0) {
            return;
        }
        Account account = accountResolver.lockUsableAccount(userId, ledgerId, accountId);
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
