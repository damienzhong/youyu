package com.damien.youyu.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 微信订阅消息模板配置，对应 {@code wechat_subscribe_templates} 表（迁移脚本
 * {@code V45__wechat_subscribe_template.sql}）。每种业务类型至多一行：{@code bizType} 为主键，
 * 取值 {@code REMINDER}（记账提醒）/ {@code BUDGET}（预算超支通知）；{@code templateId} 为微信后台
 * 申请到的模板 id；{@code enabled} 为启用开关（{@code false} 视为未配置，发送安全降级）。
 *
 * <p><b>主键刻意不加 {@code @GeneratedValue}</b>：{@code biz_type} 是业务枚举字符串，由运维/迁移脚本
 * 显式写入，不是数据库生成的代理键。模板 id 迁到数据库配置后，运营调整模板不再需要改环境变量并重启，
 * 由 {@code SubscribeTemplateProvider} 只读查库获取（与 {@link BudgetReminderSetting} 同一「应用赋值主键」取舍）。</p>
 */
@Entity
@Table(name = "wechat_subscribe_templates")
public class WechatSubscribeTemplate {

    /** 业务类型，即主键：{@code REMINDER}（记账提醒）/ {@code BUDGET}（预算超支通知）；刻意不加 {@code @GeneratedValue}。 */
    @Id
    @Column(name = "biz_type", nullable = false, length = 32)
    private String bizType;

    /** 微信订阅消息模板 id（微信后台申请）。 */
    @Column(name = "template_id", nullable = false, length = 128)
    private String templateId;

    /** 是否启用：{@code true} 启用；{@code false} 停用（停用时视为未配置，发送安全降级）。 */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** 备注（模板名称 / 微信后台模板编号等，便于运维辨识）；可空。 */
    @Column(name = "remark", length = 255)
    private String remark;

    /** 建档时间。 */
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    /** 最后一次更新时间。 */
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public WechatSubscribeTemplate() {
        // JPA / 服务层构造
    }

    public String getBizType() {
        return bizType;
    }

    public void setBizType(String bizType) {
        this.bizType = bizType;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
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
