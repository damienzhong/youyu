package com.damien.youyu.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.damien.youyu.domain.PendingStatus;
import com.damien.youyu.domain.RecurringPendingItem;

/**
 * 待确认生成项仓库（{@code recurring_pending_items}，一行 = 某规则某期次的一条待确认建议）。
 *
 * <p>生成项的写入走继承来的 {@code save()}：{@link RecurringPendingItem} 的 {@code @Id} 是自增代理键。
 * 生成幂等由唯一约束 {@code uk_recurring_pending_rule_date (rule_id, occurrence_date)} 构造性保证
 * （需求 3.3、9.3）——并发 / 重复生成时后写者撞唯一键抛 {@code DataIntegrityViolationException}，
 * 由懒生成静默放弃本条（需求 3.4、9.4）。{@link #existsByRuleIdAndOccurrenceDate} 只作生成前的幂等先行预检。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@Repository
public interface RecurringPendingItemRepository extends JpaRepository<RecurringPendingItem, Long> {

    /**
     * 生成幂等先行预检（需求 3.1、3.3）：该规则该期次到期日是否已存在任一状态
     * （{@code PENDING}/{@code CONFIRMED}/{@code SKIPPED}）的记录。已存在则懒生成跳过该期次。
     * 库侧兜底由唯一约束 {@code uk_recurring_pending_rule_date} 承担。
     */
    boolean existsByRuleIdAndOccurrenceDate(Long ruleId, LocalDate occurrenceDate);

    /**
     * 待确认项查询（需求 5.1、5.2、8.4）：当前账本下指定状态的生成项，按 {@code occurrence_date} 升序、
     * 再按项 id 升序（可复现）。传入 {@code PendingStatus.PENDING} 即得当前账本待确认列表；命中索引
     * {@code idx_recurring_pending_ledger_status_date (ledger_id, status, occurrence_date)}。
     *
     * <p>需求 5.2 要求的「到期日升序 → 规则创建时间升序 → 项 id 升序」中「规则创建时间」维度由服务层在
     * 装配时补充，本仓库先按到期日与项 id 提供稳定的基础顺序。</p>
     */
    List<RecurringPendingItem> findByLedgerIdAndStatusOrderByOccurrenceDateAscIdAsc(Long ledgerId,
            PendingStatus status);

    /**
     * 删除规则级联移除（需求 6.5）：删除该规则全部处于 {@code PENDING} 状态的待确认项，
     * 保留其 {@code CONFIRMED} 历史流水引用与 {@code SKIPPED} 期次记录。
     *
     * <p>无匹配行时影响行数 0 即视为成功，删除前不做存在性预查询。</p>
     *
     * <p>{@code flushAutomatically=true} 保证删除前先冲刷挂起的插入 / 更新，{@code clearAutomatically=true}
     * 在批量删除后清空一级缓存——批量 JPQL 删除绕过持久化上下文，不清缓存会让删除后同事务内的
     * {@code findById} 命中已被物理删除却仍受管的陈旧实体。清缓存后同事务后续查询一律回库，语义正确。</p>
     *
     * @return 实际影响行数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from RecurringPendingItem p where p.ruleId = :ruleId and p.status = :status")
    int deleteByRuleIdAndStatus(@Param("ruleId") Long ruleId, @Param("status") PendingStatus status);

    /**
     * 确认入账的<b>乐观并发闸门</b>（需求 4.9）：对 {@code id} 指向的待确认项，<b>当且仅当</b>其当前状态仍为
     * {@link PendingStatus#PENDING} 时，原子地置为 {@link PendingStatus#CONFIRMED} 并刷新 {@code updated_at}，
     * 返回受影响行数。
     *
     * <p>并发 / 重复确认时，数据库对该行的写锁使多个事务串行化：仅首个事务的
     * {@code WHERE status = PENDING} 命中并置 {@code CONFIRMED}（返回 1），其余事务在其提交后再评估条件时
     * 已非 {@code PENDING}（返回 0）。调用方据此对返回 0 的落败者抛
     * {@code RECURRING_ITEM_ALREADY_PROCESSED}，从而保证「至多生成一条流水、至多更新一次余额」——因为
     * 本闸门先于 {@code TransactionService.create} 执行，落败者根本不进入建交易 / 改余额（需求 4.9）。</p>
     *
     * <p>{@code flushAutomatically=true} 保证闸门前挂起的写入先落库；{@code clearAutomatically=true} 在批量
     * JPQL 更新后清一级缓存，使同事务内后续 {@code findById} 回库读到刚置 {@code CONFIRMED} 的最新状态，
     * 而非受管的陈旧实体。</p>
     *
     * @param id  待确认项 id
     * @param now 置 {@code CONFIRMED} 的时间戳（{@code updated_at}）
     * @return 受影响行数：{@code 1}=本次抢到确认权（原为 {@code PENDING}）；{@code 0}=已被处理（并发落败或已处理）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RecurringPendingItem p set p.status = com.damien.youyu.domain.PendingStatus.CONFIRMED, "
            + "p.updatedAt = :now "
            + "where p.id = :id and p.status = com.damien.youyu.domain.PendingStatus.PENDING")
    int markConfirmedIfPending(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);

    /**
     * 跳过本期的<b>乐观并发闸门</b>（需求 4.4、4.5）：对 {@code id} 指向的待确认项，<b>当且仅当</b>其当前状态仍为
     * {@link PendingStatus#PENDING} 时，原子地置为 {@link PendingStatus#SKIPPED} 并刷新 {@code updated_at}，
     * 返回受影响行数。跳过<b>不生成任何流水、不改变任何账户余额</b>（需求 4.4），因此本条件更新即完成整个跳过动作。
     *
     * <p>并发 / 重复的跳过 / 确认时，数据库对该行的写锁使多个事务串行化：仅首个命中
     * {@code WHERE status = PENDING} 的事务置 {@code SKIPPED}（返回 1），其余（含并发确认的落败者）返回 0。
     * 调用方据此对返回 0 者抛 {@code RECURRING_ITEM_ALREADY_PROCESSED}，与
     * {@link #markConfirmedIfPending} 一起保证「PENDING → 终态」的状态迁移在并发下至多发生一次（需求 4.5、4.9）。</p>
     *
     * <p>{@code flushAutomatically=true} 保证闸门前挂起的写入先落库；{@code clearAutomatically=true} 在批量
     * JPQL 更新后清一级缓存，使同事务内后续 {@code findById} 回库读到刚置 {@code SKIPPED} 的最新状态。</p>
     *
     * @param id  待确认项 id
     * @param now 置 {@code SKIPPED} 的时间戳（{@code updated_at}）
     * @return 受影响行数：{@code 1}=本次抢到跳过权（原为 {@code PENDING}）；{@code 0}=已被处理（并发落败或已处理）
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update RecurringPendingItem p set p.status = com.damien.youyu.domain.PendingStatus.SKIPPED, "
            + "p.updatedAt = :now "
            + "where p.id = :id and p.status = com.damien.youyu.domain.PendingStatus.PENDING")
    int markSkippedIfPending(@Param("id") Long id, @Param("now") java.time.LocalDateTime now);
}
