package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Loan;
import com.damien.youyu.domain.LoanDirection;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.LoanRepository;

/**
 * 借贷往来服务：借入(BORROW)/借出(LEND)款项的增删改、结清切换与未结汇总。
 *
 * <p>核心约束：</p>
 * <ul>
 *   <li>创建/修改校验：方向属于 {@link LoanDirection}；对方去空白后 1-50；本金在
 *       [0.01, 9,999,999,999,999,999.99] 且最多两位小数；发生时间必填；备注 <=200。
 *       任一不满足即拒绝、不持久化，返回指明字段的 {@code LOAN_FIELD_INVALID}。</li>
 *   <li>结清切换：settled=true 时记录 settled_at=now；置回未结清时清空 settled_at。</li>
 *   <li>汇总：借入/待还 = Σ 未结清 BORROW；借出/待收 = Σ 未结清 LEND。</li>
 * </ul>
 *
 * <p>借贷为独立台账，不参与账户余额与净资产计算。所有操作按会话 {@code userId} 隔离：
 * 读取/修改/删除他人记录一律返回 {@code NOT_FOUND}（需求 2.3、2.4）。金额一律 {@link BigDecimal}。</p>
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
    private final Clock clock;

    public LoanService(LoanRepository loanRepository, Clock clock) {
        this.loanRepository = loanRepository;
        this.clock = clock;
    }

    /** 列出本人全部借贷（未结清优先，其次发生时间倒序）。 */
    @Transactional(readOnly = true)
    public List<Loan> list(Long userId) {
        return loanRepository.findByUserIdOrderBySettledAscOccurredAtDescIdDesc(userId);
    }

    /** 某方向未结清金额合计（借入/待还、借出/待收），无记录返回 0.00。 */
    @Transactional(readOnly = true)
    public BigDecimal outstanding(Long userId, LoanDirection direction) {
        BigDecimal sum = loanRepository.sumOutstandingByDirection(userId, direction);
        return (sum == null ? BigDecimal.ZERO : sum).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 新建一笔借贷（默认未结清）。
     *
     * @throws ApiException LOAN_FIELD_INVALID（方向/对方/金额/发生时间/备注任一非法）
     */
    @Transactional
    public Loan create(Long userId, String rawDirection, String rawCounterparty,
            BigDecimal rawAmount, LocalDateTime occurredAt, String rawNote) {
        LoanDirection direction = validateDirection(rawDirection);
        String counterparty = validateCounterparty(rawCounterparty);
        BigDecimal amount = validateAmount(rawAmount);
        String note = validateNote(rawNote);

        LocalDateTime now = LocalDateTime.now(clock);
        // 发生时间缺省取当前时刻（与交易一致）。
        LocalDateTime when = occurredAt != null ? occurredAt : now;
        Loan loan = new Loan();
        loan.setUserId(userId);
        loan.setDirection(direction);
        loan.setCounterparty(counterparty);
        loan.setAmount(amount);
        loan.setOccurredAt(when);
        loan.setSettled(false);
        loan.setNote(note);
        loan.setCreatedAt(now);
        loan.setUpdatedAt(now);
        return loanRepository.save(loan);
    }

    /**
     * 修改一笔借贷的方向/对方/金额/发生时间/备注（不改结清状态）。
     *
     * @throws ApiException NOT_FOUND / LOAN_FIELD_INVALID
     */
    @Transactional
    public Loan update(Long userId, Long id, String rawDirection, String rawCounterparty,
            BigDecimal rawAmount, LocalDateTime occurredAt, String rawNote) {
        Loan loan = loanRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("借贷记录不存在"));

        loan.setDirection(validateDirection(rawDirection));
        loan.setCounterparty(validateCounterparty(rawCounterparty));
        loan.setAmount(validateAmount(rawAmount));
        loan.setOccurredAt(occurredAt != null ? occurredAt : loan.getOccurredAt());
        loan.setNote(validateNote(rawNote));
        loan.setUpdatedAt(LocalDateTime.now(clock));
        return loanRepository.save(loan);
    }

    /**
     * 切换结清状态：结清记录 settled_at=now，置回未结清清空 settled_at。
     *
     * @throws ApiException NOT_FOUND
     */
    @Transactional
    public Loan setSettled(Long userId, Long id, boolean settled) {
        Loan loan = loanRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("借贷记录不存在"));
        LocalDateTime now = LocalDateTime.now(clock);
        loan.setSettled(settled);
        loan.setSettledAt(settled ? now : null);
        loan.setUpdatedAt(now);
        return loanRepository.save(loan);
    }

    /**
     * 删除一笔借贷。
     *
     * @throws ApiException NOT_FOUND
     */
    @Transactional
    public void delete(Long userId, Long id) {
        Loan loan = loanRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("借贷记录不存在"));
        loanRepository.delete(loan);
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
