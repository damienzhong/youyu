package com.damien.youyu.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 用户实体，对应 {@code users} 表。
 *
 * <p>用户是多租户隔离的根：其余业务实体(Account/Category/Transaction)均通过
 * {@code user_id} 归属到某个用户。{@code plan/plan_started_at/plan_expires_at/role}
 * 本期仅预留存储，不做功能门控。</p>
 */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 邮箱身份（登录凭证），全局唯一、可空。纯微信用户可为空。 */
    @Column(name = "email", length = 255, unique = true)
    private String email;

    /** 昵称，仅用于展示，可空、可重复、可修改，不用于登录鉴权。 */
    @Column(name = "nickname", length = 64)
    private String nickname;

    /**
     * 个人邀请码，8 位、全局唯一、终身不变，无修改与重置操作。
     *
     * <p>建号时随 {@code users} 的插入一并写入；存量用户迁移后为 NULL，首次请求邀请信息时
     * 惰性补齐。随 {@code users} 行删除而释放，后续新用户可重新抽到同一取值。</p>
     */
    @Column(name = "invite_code", length = 8, unique = true)
    private String inviteCode;

    /** 微信小程序 openid（同一小程序内唯一），微信用户的稳定标识。 */
    @Column(name = "wx_openid", length = 64, unique = true)
    private String wxOpenid;

    /** 微信开放平台 unionid（多端/公众号打通用），可为空。 */
    @Column(name = "wx_unionid", length = 64)
    private String wxUnionid;

    /** 套餐：free/pro/lifetime。 */
    @Convert(converter = PlanConverter.class)
    @Column(name = "plan", nullable = false, length = 16)
    private Plan plan = Plan.FREE;

    /** 注册时刻。 */
    @Column(name = "plan_started_at", nullable = false)
    private LocalDateTime planStartedAt;

    /** plan_started_at + 365 天。 */
    @Column(name = "plan_expires_at", nullable = false)
    private LocalDateTime planExpiresAt;

    /** 角色：user/admin。 */
    @Convert(converter = RoleConverter.class)
    @Column(name = "role", nullable = false, length = 16)
    private Role role = Role.USER;

    /** 性别：'MALE' / 'FEMALE'；NULL 表示保密。仅展示，不做任何功能门控。 */
    @Column(name = "gender", length = 8)
    private String gender;

    /** 头像颜色（十六进制，如 #0ea5e9）：用户自选，用于家庭账本中区分记账人。NULL 时前端回退品牌绿。 */
    @Column(name = "avatar_color", length = 16)
    private String avatarColor;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public User() {
        // JPA / 服务层构造
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getInviteCode() {
        return inviteCode;
    }

    public void setInviteCode(String inviteCode) {
        this.inviteCode = inviteCode;
    }

    public String getWxOpenid() {
        return wxOpenid;
    }

    public void setWxOpenid(String wxOpenid) {
        this.wxOpenid = wxOpenid;
    }

    public String getWxUnionid() {
        return wxUnionid;
    }

    public void setWxUnionid(String wxUnionid) {
        this.wxUnionid = wxUnionid;
    }

    public Plan getPlan() {
        return plan;
    }

    public void setPlan(Plan plan) {
        this.plan = plan;
    }

    public LocalDateTime getPlanStartedAt() {
        return planStartedAt;
    }

    public void setPlanStartedAt(LocalDateTime planStartedAt) {
        this.planStartedAt = planStartedAt;
    }

    public LocalDateTime getPlanExpiresAt() {
        return planExpiresAt;
    }

    public void setPlanExpiresAt(LocalDateTime planExpiresAt) {
        this.planExpiresAt = planExpiresAt;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getAvatarColor() {
        return avatarColor;
    }

    public void setAvatarColor(String avatarColor) {
        this.avatarColor = avatarColor;
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
