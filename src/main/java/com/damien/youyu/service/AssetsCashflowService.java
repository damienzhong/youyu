package com.damien.youyu.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.repository.AccountRepository;
import com.damien.youyu.repository.TransactionRepository;
import com.damien.youyu.service.CashflowClassifier.CashflowContribution;

/**
 * 资产现金流（Assets_Cashflow_System）只读聚合服务：按<b>账户维度</b>汇总当前用户在某自然月的
 * 实际流出、实际流入与净流入，并附带「今日」流出/流入。
 *
 * <p>口径独立于「账本」tab 收支与 {@code AggregateService} 的全部账本聚合，刻意<b>含 AA 实付、
 * 排除账户间转账</b>：只聚合 {@code account_id} 落在「本人拥有账户 id 集合」内、当月、未软删的交易，
 * 逐笔交由 {@link CashflowClassifier#classify} 归类累加。该口径与「逐笔真实改变本人账户余额」一致，
 * 天然满足「只计本人账户变动」（{@code account_id} 属本人）并排除 AA 他人实付（{@code account_id}
 * 为空或他人账户）。</p>
 *
 * <p>纯只读增量：只读取 {@code transactions}、{@code accounts}，不新增表 / 迁移、不写任何数据，
 * 不改动 {@code AggregateService} 与既有收支 / 净资产口径。金额全程 {@link BigDecimal}
 * （{@code DECIMAL(18,2)} 语义），时区固定 {@code Asia/Shanghai}（由注入 {@link Clock} 承载，
 * 不依赖 JVM / 数据库会话 / 操作系统默认时区）。</p>
 */
@Service
public class AssetsCashflowService {

    /** 金额输出精度：两位小数，HALF_UP（{@code DECIMAL(18,2)} 语义，需求 1.13、2.6）。 */
    private static final int SCALE = 2;

    private final Clock clock;
    private final AccountRepository accountRepository;
    private final TransactionRepository transactionRepository;

    public AssetsCashflowService(
            Clock clock,
            AccountRepository accountRepository,
            TransactionRepository transactionRepository) {
        this.clock = clock;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
    }

    /**
     * 账户维度现金流内部结果值对象；金额均为两位小数、HALF_UP 的 {@link BigDecimal}。
     * DTO（两位小数字符串）转换留给 controller 层（任务 3.1/3.2），本服务不做转换。
     *
     * @param outflow      选定自然月的实际流出
     * @param inflow       选定自然月的实际流入
     * @param netInflow    净流入 = 流入 − 流出（可为负，即净流出）
     * @param todayOutflow 今日实际流出（历史月为 {@code 0.00}）
     * @param todayInflow  今日实际流入（历史月为 {@code 0.00}）
     */
    public record CashflowResult(
            BigDecimal outflow,
            BigDecimal inflow,
            BigDecimal netInflow,
            BigDecimal todayOutflow,
            BigDecimal todayInflow) {
    }

    /**
     * 计算当前用户在选定自然月的账户维度现金流。
     *
     * @param userId 令牌解析出的当前用户 id（唯一数据归属依据）
     * @param month  选定自然月（{@code Asia/Shanghai} 口径）
     * @return 该月流出 / 流入 / 净流入与今日流出 / 流入；无计入交易时五项均为 {@code 0.00}
     */
    @Transactional(readOnly = true)
    public CashflowResult cashflow(Long userId, YearMonth month) {
        // 1. 取本人账户 id 集合；空集直接返回全 0.00（无账户即无任何账户变动，需求 2.7）。
        Set<Long> accountIds = accountRepository.findByUserIdOrderBySortOrderAscIdAsc(userId).stream()
                .map(Account::getId)
                .collect(Collectors.toSet());
        if (accountIds.isEmpty()) {
            return zero();
        }

        // 2. Asia/Shanghai 自然月半开区间 [当月 1 日 00:00, 次月 1 日 00:00)（需求 1.12）。
        LocalDateTime from = month.atDay(1).atStartOfDay();
        LocalDateTime to = month.plusMonths(1).atDay(1).atStartOfDay();

        // 3. 查当月、account_id ∈ 本人账户、未软删（@SQLRestriction 自动排除）的交易（需求 1.9、1.11）。
        List<Transaction> transactions = transactionRepository
                .findByAccountIdInAndOccurredAtGreaterThanEqualAndOccurredAtLessThan(accountIds, from, to);

        // 6. 今日子集：仅当选定月 == 当前月时今日值可能非零；否则今日不落在该历史月内（需求 2.3、2.4）。
        boolean isCurrentMonth = month.equals(YearMonth.now(clock));
        LocalDate today = LocalDate.now(clock);

        BigDecimal outflow = BigDecimal.ZERO;
        BigDecimal inflow = BigDecimal.ZERO;
        BigDecimal todayOutflow = BigDecimal.ZERO;
        BigDecimal todayInflow = BigDecimal.ZERO;

        // 4. 逐笔归类累加（transfer 由 classify 归为 NONE、贡献 0，天然排除，需求 1.7）。
        for (Transaction tx : transactions) {
            CashflowContribution contribution = CashflowClassifier.classify(
                    tx.getType(), tx.getAmount(), tx.getPayerUserId(), tx.getCreatedBy());
            BigDecimal txOutflow = contribution.outflow();
            BigDecimal txInflow = contribution.inflow();
            outflow = outflow.add(txOutflow);
            inflow = inflow.add(txInflow);

            // 今日子集：occurredAt 落在今日（Asia/Shanghai）的交易同法累加。
            if (isCurrentMonth && tx.getOccurredAt() != null
                    && tx.getOccurredAt().toLocalDate().isEqual(today)) {
                todayOutflow = todayOutflow.add(txOutflow);
                todayInflow = todayInflow.add(txInflow);
            }
        }

        // 5. 净流入 = 流入 − 流出（可为负）；7. 全程 BigDecimal，输出 setScale(2, HALF_UP)。
        BigDecimal netInflow = inflow.subtract(outflow);
        return new CashflowResult(
                scale(outflow),
                scale(inflow),
                scale(netInflow),
                scale(todayOutflow),
                scale(todayInflow));
    }

    /** 五项均为 {@code 0.00} 的空结果（需求 2.7）。 */
    private static CashflowResult zero() {
        BigDecimal z = scale(BigDecimal.ZERO);
        return new CashflowResult(z, z, z, z, z);
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
