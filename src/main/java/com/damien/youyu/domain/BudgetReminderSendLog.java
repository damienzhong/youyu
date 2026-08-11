package com.damien.youyu.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * 预算提醒发送记录，对应 {@code budget_reminder_send_logs} 表（迁移脚本 {@code V43__budget_reminder.sql}）。
 * 一行 = 一次预算提醒发送尝试的落表结果，记录收件人、账本、自然月、预算范围、级别、发送结果与微信错误码。
 *
 * <p><b>幂等由唯一约束 {@code uk_budget_reminder_send_logs_scope} 构造性保证：</b>
 * {@code (user_id, ledger_id, budget_month, scope_ref, level)} 同一收件人同一账本同一自然月同一预算范围
 * 同一级别至多一条发送记录，不依赖时序巧合。并发触发时后写者撞唯一键抛
 * {@code DataIntegrityViolationException}，由发送编排静默放弃本次。</p>
 *
 * <p><b>{@code scopeRef} 编码约定：</b>{@code 0} 表示月度总预算范围，大于 {@code 0} 表示分类 id。
 * 由于分类 id 恒大于 0，{@code 0} 与任何分类 id 不冲突，唯一键因此能把「总预算 WARN/OVER」与
 * 「每个分类 WARN/OVER」两两区分。</p>
 *
 * <p><b>主键 {@code id} 带 {@code @GeneratedValue}</b>：自增代理键、不承载业务语义。
 * {@code userId} / {@code ledgerId} 均为裸 {@link Long}，表上无任何外键（注销时由
 * {@code AccountDeletionService} 按 {@code user_id} 显式删除）。</p>
 */
@Entity
@Table(name = "budget_reminder_send_logs",
        uniqueConstraints = @UniqueConstraint(name = "uk_budget_reminder_send_logs_scope",
                columnNames = {"user_id", "ledger_id", "budget_month", "scope_ref", "level"}))
public class BudgetReminderSendLog {

    /** 自增主键；发送记录的代理键，不承载业务语义。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    /** 收件人用户 id。裸 id，无外键（注销时按 user_id 显式删除）。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 账本 id。裸 id，无外键。 */
    @Column(name = "ledger_id", nullable = false)
    private Long ledgerId;

    /** 预算自然月（{@code Asia/Shanghai}，格式 {@code YYYY-MM}）；唯一键组成部分。 */
    @Column(name = "budget_month", nullable = false, length = 7)
    private String budgetMonth;

    /** 预算范围：{@code 0} 表示月度总预算，大于 {@code 0} 表示分类 id；唯一键组成部分。 */
    @Column(name = "scope_ref", nullable = false)
    private long scopeRef;

    /** 预警级别：{@code WARN} 预警 / {@code OVER} 超支（区分大小写）；唯一键组成部分。 */
    @Column(name = "level", nullable = false, length = 8)
    private String level;

    /** 发送结果：{@code SENT} / {@code SKIPPED_NO_QUOTA} / {@code SKIPPED_NO_OPENID} / {@code FAILED}。 */
    @Column(name = "result", nullable = false, length = 24)
    private String result;

    /** 微信 errcode：{@code SENT} 为 0，{@code SKIPPED_*} 为空，{@code FAILED} 为微信码或空。 */
    @Column(name = "wx_errcode")
    private Integer wxErrcode;

    /** 发送尝试时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public BudgetReminderSendLog() {
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

    public String getBudgetMonth() {
        return budgetMonth;
    }

    public void setBudgetMonth(String budgetMonth) {
        this.budgetMonth = budgetMonth;
    }

    public long getScopeRef() {
        return scopeRef;
    }

    public void setScopeRef(long scopeRef) {
        this.scopeRef = scopeRef;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getResult() {
        return result;
    }

    public void setResult(String result) {
        this.result = result;
    }

    public Integer getWxErrcode() {
        return wxErrcode;
    }

    public void setWxErrcode(Integer wxErrcode) {
        this.wxErrcode = wxErrcode;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
