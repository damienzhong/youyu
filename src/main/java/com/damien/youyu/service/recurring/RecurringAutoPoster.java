package com.damien.youyu.service.recurring;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.Account;
import com.damien.youyu.domain.PendingStatus;
import com.damien.youyu.domain.RecurringPendingItem;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.domain.Transaction;
import com.damien.youyu.error.ApiException;
import com.damien.youyu.repository.CategoryRepository;
import com.damien.youyu.repository.RecurringPendingItemRepository;
import com.damien.youyu.service.LedgerAccountResolver;
import com.damien.youyu.service.TransactionService;

/**
 * 单期次<b>自动入账</b>协作组件（recurring-auto-post 核心，tasks 3.1）：把「为某 {@code AUTO} 规则某到期日
 * 直接走既有交易创建链路落库」封装为一个 {@link Propagation#REQUIRES_NEW} <b>独立事务</b>单元，供懒入账
 * （{@link RecurringPendingItemService} 改造后的分流，tasks 4.1）与每日定时任务
 * （{@link RecurringAutoPostScheduler}，tasks 5.1）逐期次调用。二者<b>共用本方法</b>与同一
 * {@code (rule_id, occurrence_date)} 幂等键，结果一致、至多一条流水（需求 4.3）。
 *
 * <h2>autoPost 流程（design.md「自动入账算法」）</h2>
 * <pre>
 * autoPost(rule, occurrenceDate):                                   // REQUIRES_NEW 独立事务
 *   A) placeholder = saveAndFlush(snapshot(rule, occurrenceDate, PENDING))  // 抢唯一键，构造性幂等
 *        撞 DataIntegrityViolationException → 向外传播（回滚本新事务），调用方视为「已处理」静默（需求 2.4、3.4）
 *   B) try 校验金额 / 分类属账本 / 账户可用
 *        校验不过（ApiException）→ 保留 placeholder 为 PENDING 并提交，返回 DEGRADED_TO_PENDING（需求 3.1、3.2、3.3）
 *   C) tx = TransactionService.create(..., occurrenceDate.atStartOfDay(), ...)   // 账户加锁 + 余额更新（需求 2.1、2.2、2.8）
 *   D) placeholder.status = CONFIRMED; placeholder.confirmedTransactionId = tx.id; save
 *   return AUTO_POSTED(tx)                                          // 供调用方在提交后发告知（需求 5）
 * </pre>
 *
 * <h2>构造性幂等与「已处理」为何靠异常而非返回值（JPA / REQUIRES_NEW 约束）</h2>
 * <p>幂等由 {@code recurring_pending_items} 唯一约束 {@code uk_recurring_pending_rule_date} 构造性保证
 * （需求 2.4、3.4）。步骤 A 的 {@code saveAndFlush} 在<b>本 REQUIRES_NEW 事务内</b>即时刷库，撞唯一键时抛
 * {@link org.springframework.dao.DataIntegrityViolationException}，并把当前事务标记为 rollback-only——
 * <b>无法在同一事务内捕获后正常提交</b>。因此本方法<b>不捕获</b>该异常，任其向外传播、由 Spring 回滚本新事务；
 * 调用方（懒入账 / 定时任务）就地捕获它并视为「该期次已被另一路径处理」静默结束（与既有
 * {@link RecurringPendingItemGenerator} + {@link RecurringPendingItemService#lazyGenerate} 同款处理）。
 * 故 {@link AutoPostResult} 只表达 {@code AUTO_POSTED} 与 {@code DEGRADED_TO_PENDING} 两种正常返回。</p>
 *
 * <h2>原子性（需求 2.3）与降级（需求 3）</h2>
 * <ul>
 *   <li><b>已入账原子：</b>步骤 A（占位）、C（建交易 + 账户加锁 + 余额更新）、D（占位升 {@code CONFIRMED}
 *       + 回填 {@code txId}）同处本 {@code REQUIRES_NEW} 事务，全部提交或全部回滚。步骤 C/D 抛任何非预期异常
 *       → 整个事务回滚（含步骤 A 的占位）→ 不生成流水、不改余额、不留 {@code CONFIRMED} 记录（需求 2.3），
 *       调用方就地隔离该期次失败（需求 3.5）。</li>
 *   <li><b>降级：</b>步骤 B 校验不过时，占位行本就是 {@code PENDING}，直接返回 {@code DEGRADED_TO_PENDING}
 *       使本事务正常提交，从而保留一条 {@code PENDING} 待确认项（携原模板快照），不建交易、不改余额
 *       （需求 3.1、3.2）。该项与普通待确认项在既有列表 / 确认 / 跳过 / 批量上完全一致（需求 3.3）。</li>
 * </ul>
 *
 * <p>本组件复用既有 {@link TransactionService#create}（与手动记账、用户确认入账同口径，需求 2.8）、
 * {@link RecurringTemplateValidator}（金额校验）、{@link CategoryRepository#findByIdAndLedgerId} 与
 * {@link LedgerAccountResolver#selectableAccounts}（目标可用性），金额一律 {@code BigDecimal} 2 位小数
 * （需求 6.5）。</p>
 *
 * <p>Feature: recurring-auto-post。</p>
 */
@Component
public class RecurringAutoPoster {

    private final RecurringPendingItemRepository pendingItemRepository;
    private final TransactionService transactionService;
    private final CategoryRepository categoryRepository;
    private final LedgerAccountResolver accountResolver;
    private final RecurringTemplateValidator templateValidator;
    private final Clock clock;

    public RecurringAutoPoster(
            RecurringPendingItemRepository pendingItemRepository,
            TransactionService transactionService,
            CategoryRepository categoryRepository,
            LedgerAccountResolver accountResolver,
            RecurringTemplateValidator templateValidator,
            Clock clock) {
        this.pendingItemRepository = pendingItemRepository;
        this.transactionService = transactionService;
        this.categoryRepository = categoryRepository;
        this.accountResolver = accountResolver;
        this.templateValidator = templateValidator;
        this.clock = clock;
    }

    /**
     * 在<b>独立事务</b>内对 {@code rule} 的 {@code occurrenceDate} 期次执行自动入账（或降级）。流程与语义
     * 见类级 Javadoc。记账时间恒为 {@code occurrenceDate} 的当地 00:00（{@code Asia/Shanghai}），与调用方
     * 实际执行时刻无关（需求 2.2）。
     *
     * @param rule           来源 {@code AUTO} 规则（提供归属、模板快照字段）
     * @param occurrenceDate 期次到期自然日（{@code Asia/Shanghai}）
     * @return {@link AutoPostResult#autoPosted(Transaction)}（已入账，携新流水）或
     *         {@link AutoPostResult#degradedToPending()}（目标失效 / 金额非法，降级为 PENDING）
     * @throws org.springframework.dao.DataIntegrityViolationException 占位撞唯一键（并发 / 重复触发同一期次）时抛出，
     *                                                                 由调用方就地捕获并视为「已处理」静默
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AutoPostResult autoPost(RecurringRule rule, LocalDate occurrenceDate) {
        LocalDateTime now = LocalDateTime.now(clock);

        // A) 占位抢唯一键（构造性幂等，需求 2.4、3.4）：写一条 PENDING 快照并即时刷库。
        //    撞 uk_recurring_pending_rule_date → DataIntegrityViolationException 向外传播，本事务回滚，
        //    调用方视为「该期次已被另一路径处理」静默（原因见类级 Javadoc）。
        RecurringPendingItem placeholder = new RecurringPendingItem();
        placeholder.setRuleId(rule.getId());
        placeholder.setLedgerId(rule.getLedgerId());
        placeholder.setOccurrenceDate(occurrenceDate);
        placeholder.setStatus(PendingStatus.PENDING);
        placeholder.setType(rule.getType());
        placeholder.setAmount(rule.getAmount());
        placeholder.setCategoryId(rule.getCategoryId());
        placeholder.setAccountId(rule.getAccountId());
        placeholder.setNote(rule.getNote());
        placeholder.setCreatedAt(now);
        placeholder.setUpdatedAt(now);
        placeholder = pendingItemRepository.saveAndFlush(placeholder);

        // B) 目标 / 金额校验（需求 3.1、3.2）：不过则保留占位为 PENDING（降级），不建交易、不改余额。
        BigDecimal amount;
        try {
            amount = templateValidator.validateAmount(rule.getAmount());
            templateValidator.validateNote(rule.getNote());
            validateTargetsPresent(rule.getUserId(), rule.getLedgerId(),
                    rule.getCategoryId(), rule.getAccountId());
        } catch (ApiException degrade) {
            // 降级为待确认：占位行本就是 PENDING，正常返回使本事务提交，保留该 PENDING 项（需求 3.1、3.2、3.3）。
            return AutoPostResult.degradedToPending();
        }

        // C) 走既有交易创建链路（账户加锁 + 单事务原子余额更新，需求 2.1、2.8）：记账时间 = 期次到期日 00:00（需求 2.2）。
        Transaction tx = transactionService.create(rule.getUserId(), rule.getLedgerId(),
                rule.getType(), amount, rule.getAccountId(), rule.getCategoryId(),
                occurrenceDate.atStartOfDay(), rule.getNote());

        // D) 占位升 CONFIRMED + 回填 confirmedTransactionId（与既有确认入账同款，需求 2.1）。
        placeholder.setStatus(PendingStatus.CONFIRMED);
        placeholder.setConfirmedTransactionId(tx.getId());
        placeholder.setUpdatedAt(now);
        pendingItemRepository.save(placeholder);
        return AutoPostResult.autoPosted(tx);
    }

    /**
     * 目标可用性校验（需求 3.1）：分类须属当前账本（同 {@link TransactionService} 口径），账户须在当前用户
     * 于当前账本的可选集内（复用 {@link LedgerAccountResolver#selectableAccounts}）。任一缺失即抛
     * {@link ApiException}（触发降级），{@code field} 指明缺失项。
     */
    private void validateTargetsPresent(Long userId, Long ledgerId, Long categoryId, Long accountId) {
        if (categoryId == null
                || categoryRepository.findByIdAndLedgerId(categoryId, ledgerId).isEmpty()) {
            throw ApiException.recurringItemTargetMissing("categoryId");
        }
        List<Account> selectable = accountResolver.selectableAccounts(userId, ledgerId);
        boolean usable = accountId != null
                && selectable.stream().anyMatch(a -> a.getId().equals(accountId));
        if (!usable) {
            throw ApiException.recurringItemTargetMissing("accountId");
        }
    }
}
