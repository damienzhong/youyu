package com.damien.youyu.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * 周期规则，对应 {@code recurring_rules} 表（迁移脚本 {@code V38__recurring_transactions.sql}）。
 * 一行 = 用户建立的一条固定记账规则 = 记账模板字段 + 频率配置 + 开始/结束条件 + 状态。
 *
 * <p><b>裸 id 关联，不建外键：</b>{@code userId} / {@code ledgerId} / {@code categoryId} /
 * {@code accountId} 均声明为裸 {@link Long}，不映射为 {@code @ManyToOne} 关联。表上刻意没有任何指向
 * {@code users} / {@code ledgers} / {@code categories} / {@code accounts} 的外键（需求 9.2）——归属与
 * 存在性由应用层校验，删除本表即可整块摘除而不牵动既有表。关联映射会诱导 {@code ddl-auto} 顺手补外键。</p>
 *
 * <p><b>频率子字段按 {@code frequency} 取值必填其一组</b>（应用层校验，见需求 1.8 / 2.10）：
 * {@code WEEKLY} 用 {@code weeklyDays}（稳定升序逗号串），{@code MONTHLY} 用 {@code monthDay} 或
 * {@code monthEnd}，{@code YEARLY} 用 {@code yearMonth} + {@code yearDay}。不建 CHECK 强约束以保持迁移可摘除。</p>
 *
 * <p>{@code @Table} 上声明与迁移脚本同名同列的两个索引：生产由 Flyway 建表
 * （{@code ddl-auto=validate}），而测试环境的 H2 表结构由 Hibernate 依本实体生成，故此处如实声明。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@Entity
@Table(name = "recurring_rules",
        indexes = {
                @Index(name = "idx_recurring_rules_ledger_status", columnList = "ledger_id, status"),
                @Index(name = "idx_recurring_rules_user", columnList = "user_id")
        })
public class RecurringRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 规则所有者 user_id。裸 id，无外键，无关联映射（原因见类级 Javadoc）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 归属账本 id（账本隔离）。裸 id，无外键。 */
    @Column(name = "ledger_id", nullable = false)
    private Long ledgerId;

    /** 模板类型：{@code expense} / {@code income}（不含 transfer）。 */
    @Column(name = "type", nullable = false, length = 16)
    private String type;

    /** 模板金额，0.01–999999999.99，保留 2 位小数（HALF_UP）。 */
    @Column(name = "amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    /** 模板分类 id（须属当前账本）。裸 id，无外键。 */
    @Column(name = "category_id", nullable = false)
    private Long categoryId;

    /** 模板账户 id（须为当前用户在当前账本可用账户）。裸 id，无外键。 */
    @Column(name = "account_id", nullable = false)
    private Long accountId;

    /** 模板备注，≤200，可空。 */
    @Column(name = "note", length = 200)
    private String note;

    /** 频率：{@code DAILY} / {@code WEEKLY} / {@code MONTHLY} / {@code YEARLY}，以枚举名存储。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "frequency", nullable = false, length = 16)
    private Frequency frequency;

    /** WEEKLY：星期几集合，稳定升序逗号串，如 {@code '1,3,5'}（1=周一..7=周日）。 */
    @Column(name = "weekly_days", length = 16)
    private String weeklyDays;

    /** MONTHLY：指定日 1–31（{@code monthEnd=false} 时必填）。 */
    @Column(name = "month_day")
    private Integer monthDay;

    /** MONTHLY：{@code true}=「月末」标记（此时忽略 {@code monthDay}）。 */
    @Column(name = "month_end", nullable = false)
    private boolean monthEnd;

    /** YEARLY：月 1–12。 */
    @Column(name = "year_month")
    private Integer yearMonth;

    /** YEARLY：日 1–31。 */
    @Column(name = "year_day")
    private Integer yearDay;

    /** 开始日期（{@code Asia/Shanghai} 自然日）；早于此日的期次不生成。 */
    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    /** 结束条件：{@code NEVER} / {@code UNTIL_DATE} / {@code COUNT}，以枚举名存储。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "end_condition", nullable = false, length = 16)
    private EndCondition endCondition;

    /** UNTIL_DATE：结束日期（不早于开始日期，含端点）。 */
    @Column(name = "until_date")
    private LocalDate untilDate;

    /** COUNT：总期次数 1–9999。 */
    @Column(name = "count_n")
    private Integer countN;

    /** 规则状态：{@code ACTIVE} / {@code PAUSED}，以枚举名存储。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private RuleStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public RecurringRule() {
        // JPA / 服务层构造
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getLedgerId() {
        return ledgerId;
    }

    public void setLedgerId(Long ledgerId) {
        this.ledgerId = ledgerId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public Frequency getFrequency() {
        return frequency;
    }

    public void setFrequency(Frequency frequency) {
        this.frequency = frequency;
    }

    public String getWeeklyDays() {
        return weeklyDays;
    }

    public void setWeeklyDays(String weeklyDays) {
        this.weeklyDays = weeklyDays;
    }

    public Integer getMonthDay() {
        return monthDay;
    }

    public void setMonthDay(Integer monthDay) {
        this.monthDay = monthDay;
    }

    public boolean isMonthEnd() {
        return monthEnd;
    }

    public void setMonthEnd(boolean monthEnd) {
        this.monthEnd = monthEnd;
    }

    public Integer getYearMonth() {
        return yearMonth;
    }

    public void setYearMonth(Integer yearMonth) {
        this.yearMonth = yearMonth;
    }

    public Integer getYearDay() {
        return yearDay;
    }

    public void setYearDay(Integer yearDay) {
        this.yearDay = yearDay;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public EndCondition getEndCondition() {
        return endCondition;
    }

    public void setEndCondition(EndCondition endCondition) {
        this.endCondition = endCondition;
    }

    public LocalDate getUntilDate() {
        return untilDate;
    }

    public void setUntilDate(LocalDate untilDate) {
        this.untilDate = untilDate;
    }

    public Integer getCountN() {
        return countN;
    }

    public void setCountN(Integer countN) {
        this.countN = countN;
    }

    public RuleStatus getStatus() {
        return status;
    }

    public void setStatus(RuleStatus status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
