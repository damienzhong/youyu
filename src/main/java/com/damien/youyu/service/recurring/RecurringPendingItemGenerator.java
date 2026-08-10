package com.damien.youyu.service.recurring;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.damien.youyu.domain.PendingStatus;
import com.damien.youyu.domain.RecurringPendingItem;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.repository.RecurringPendingItemRepository;

/**
 * 懒生成的<b>单期次写入协作组件</b>：把「为某规则某到期日插入一条 {@code PENDING} 待确认项」封装为一个
 * <strong>独立事务</strong>（{@link Propagation#REQUIRES_NEW}）单元，供
 * {@link RecurringPendingItemService#lazyGenerate} 逐期次调用。
 *
 * <h2>为什么是独立 bean + REQUIRES_NEW（JPA 陷阱规避）</h2>
 * <p>生成幂等由 {@code recurring_pending_items} 的唯一约束
 * {@code uk_recurring_pending_rule_date (rule_id, occurrence_date)} 构造性保证（需求 3.3、9.3）。
 * 并发 / 重复生成撞唯一键时 {@link org.springframework.dao.DataIntegrityViolationException} 在
 * <b>flush 时</b>抛出，而一次失败的 flush 会把<b>当前事务标记为 rollback-only</b>——若把「批量逐期次插入」
 * 放在同一个事务里，某一期次撞键就会毒化整个事务，导致同规则其余期次与其它规则的插入全部连坐回滚
 * （这是常见的 JPA 陷阱）。</p>
 *
 * <p>因此本组件将<b>每一条</b>插入放进各自的 {@code REQUIRES_NEW} 新事务，并以 {@code saveAndFlush}
 * 让唯一键冲突就地在该新事务内触发、就地回滚该新事务；异常抛回调用方
 * {@link RecurringPendingItemService}，由其<b>就地捕获静默</b>（视为该期次已生成，需求 3.4、9.4），
 * 外层流程与其余期次 / 其余规则的持久化上下文<b>不受任何污染</b>。REQUIRES_NEW 依赖 Spring 事务代理，
 * 故本方法<b>必须经独立 bean 的代理调用</b>（自调用会绕过代理使注解失效），这正是将其单列为一个组件
 * 而非 {@code RecurringPendingItemService} 私有方法的原因。</p>
 *
 * <p>本组件<b>只写</b> {@code recurring_pending_items} 一张表，绝不创建交易、绝不改动任何账户余额
 * （需求 3.2）。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@Component
public class RecurringPendingItemGenerator {

    private final RecurringPendingItemRepository pendingItemRepository;
    private final Clock clock;

    public RecurringPendingItemGenerator(RecurringPendingItemRepository pendingItemRepository,
            Clock clock) {
        this.pendingItemRepository = pendingItemRepository;
        this.clock = clock;
    }

    /**
     * 在<b>独立事务</b>内为 {@code rule} 的 {@code occurrenceDate} 期次插入一条 {@code PENDING} 待确认项，
     * 模板字段取自规则当下的模板快照（{@code type} / {@code amount} / {@code categoryId} /
     * {@code accountId} / {@code note}，需求 3.1）。
     *
     * <p>以 {@code saveAndFlush} 在本 {@code REQUIRES_NEW} 事务内即时刷库：撞唯一约束
     * {@code uk_recurring_pending_rule_date} 时抛
     * {@link org.springframework.dao.DataIntegrityViolationException}，仅回滚本新事务，由调用方静默处理
     * （需求 3.4、9.4）。不创建任何交易、不改任何账户余额（需求 3.2）。</p>
     *
     * @param rule           来源规则（提供归属账本与模板快照字段）
     * @param occurrenceDate 期次到期自然日（{@code Asia/Shanghai}）
     * @throws org.springframework.dao.DataIntegrityViolationException 撞唯一键（并发 / 重复生成）时抛出，
     *                                                                 由调用方就地捕获静默
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void generate(RecurringRule rule, LocalDate occurrenceDate) {
        LocalDateTime now = LocalDateTime.now(clock);
        RecurringPendingItem item = new RecurringPendingItem();
        item.setRuleId(rule.getId());
        // 冗余账本 id 取自规则，便于后续按账本隔离查询而不必回表规则。
        item.setLedgerId(rule.getLedgerId());
        item.setOccurrenceDate(occurrenceDate);
        item.setStatus(PendingStatus.PENDING);
        // 生成时刻的模板快照：规则后续被编辑不影响本项（需求 6.3、6.4）。
        item.setType(rule.getType());
        item.setAmount(rule.getAmount());
        item.setCategoryId(rule.getCategoryId());
        item.setAccountId(rule.getAccountId());
        item.setNote(rule.getNote());
        item.setCreatedAt(now);
        item.setUpdatedAt(now);
        // 在本 REQUIRES_NEW 事务内即时刷库：唯一键冲突就地抛出并只回滚本新事务。
        pendingItemRepository.saveAndFlush(item);
    }
}
