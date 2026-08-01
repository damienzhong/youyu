package com.damien.youyu.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 账户实体，对应 {@code accounts} 表。
 *
 * <p>金额一律使用 {@link BigDecimal}（DECIMAL(18,2)），严禁 double/float。
 * {@code current_balance} 随流水事务性更新，并可由 {@code initial_balance} + 全量流水重算校验。
 * 账户是独立于账本的一等实体，始终归属某个用户（{@code userId} 即 owner）；账户在哪些账本可用、
 * 是否对协作成员可见/显示余额，由 {@code account_ledger} 关联表表达（见 {@code AccountLedger}）。</p>
 */
@Entity
@Table(name = "accounts")
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 归属用户（owner）：账户独立于账本，始终归属某个用户。 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 账户名称，去空白后 1-50。 */
    @Column(name = "name", nullable = false, length = 50)
    private String name;

    /** 账户类型枚举。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private AccountType type;

    /** 初始余额，用于重算校验。 */
    @Column(name = "initial_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal initialBalance;

    /** 当前余额，随流水更新（信用卡允许为负）。 */
    @Column(name = "current_balance", nullable = false, precision = 18, scale = 2)
    private BigDecimal currentBalance;

    /** 列表排序，默认 0。 */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    /** 余额是否计入净资产（默认计入）。列 tinyint(1)，MySQL 驱动按 BIT 映射，与 Hibernate boolean 默认一致。 */
    @Column(name = "include_in_total", nullable = false)
    private boolean includeInTotal = true;

    /** 是否隐藏账户：记账选择账户时不展示（历史流水保留）。 */
    @Column(name = "hidden", nullable = false)
    private boolean hidden = false;

    /** 账户备注，<=200。 */
    @Column(name = "note", length = 200)
    private String note;

    /** 信用卡授信额度（可空，仅信用卡有意义）：可用余额 = credit_limit + current_balance。 */
    @Column(name = "credit_limit", precision = 18, scale = 2)
    private BigDecimal creditLimit;

    /** 账单日（1-28，可空，仅信用卡有意义）。 */
    @Column(name = "bill_day")
    private Integer billDay;

    /** 还款日（1-28，可空，仅信用卡有意义）。 */
    @Column(name = "repay_day")
    private Integer repayDay;

    /** 还款提醒：开启后还款日在记账日历高亮/提醒（仅信用卡有意义）。 */
    @Column(name = "repay_reminder", nullable = false)
    private boolean repayReminder = false;

    /** 提前提醒天数：还款日前多少天开始提醒（1-28，可空，仅信用卡有意义，默认 3）。 */
    @Column(name = "repay_remind_days")
    private Integer repayRemindDays;

    /** 发卡银行名称（可空，储蓄卡/信用卡有意义），如“招商银行”。 */
    @Column(name = "issuing_bank", length = 40)
    private String issuingBank;

    /** 卡号 / 尾号（可空，建议仅存后四位）。 */
    @Column(name = "card_no", length = 30)
    private String cardNo;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public Account() {
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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public AccountType getType() {
        return type;
    }

    public void setType(AccountType type) {
        this.type = type;
    }

    public BigDecimal getInitialBalance() {
        return initialBalance;
    }

    public void setInitialBalance(BigDecimal initialBalance) {
        this.initialBalance = initialBalance;
    }

    public BigDecimal getCurrentBalance() {
        return currentBalance;
    }

    public void setCurrentBalance(BigDecimal currentBalance) {
        this.currentBalance = currentBalance;
    }

    public String getIssuingBank() {
        return issuingBank;
    }

    public void setIssuingBank(String issuingBank) {
        this.issuingBank = issuingBank;
    }

    public String getCardNo() {
        return cardNo;
    }

    public void setCardNo(String cardNo) {
        this.cardNo = cardNo;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isIncludeInTotal() {
        return includeInTotal;
    }

    public void setIncludeInTotal(boolean includeInTotal) {
        this.includeInTotal = includeInTotal;
    }

    public boolean isHidden() {
        return hidden;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public void setCreditLimit(BigDecimal creditLimit) {
        this.creditLimit = creditLimit;
    }

    public Integer getBillDay() {
        return billDay;
    }

    public void setBillDay(Integer billDay) {
        this.billDay = billDay;
    }

    public Integer getRepayDay() {
        return repayDay;
    }

    public void setRepayDay(Integer repayDay) {
        this.repayDay = repayDay;
    }

    public boolean isRepayReminder() {
        return repayReminder;
    }

    public void setRepayReminder(boolean repayReminder) {
        this.repayReminder = repayReminder;
    }

    public Integer getRepayRemindDays() {
        return repayRemindDays;
    }

    public void setRepayRemindDays(Integer repayRemindDays) {
        this.repayRemindDays = repayRemindDays;
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
