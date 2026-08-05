package com.damien.youyu.domain;

/**
 * 提醒发送尝试的结果枚举，对应 {@code reminder_send_logs.result} 列的四个取值。
 *
 * <p>数据库以枚举名(大写)存储，使用 {@code @Enumerated(EnumType.STRING)} 映射；
 * 枚举名即 {@code reminder_send_logs.result} 列中的取值，迁移脚本
 * {@code V35__custom_reminder.sql} 以 {@code VARCHAR(24)} 承载，长度足以容纳最长的
 * {@code SKIPPED_NO_QUOTA}。不得改名，否则历史发送记录的语义会与枚举脱节。</p>
 *
 * <ul>
 *   <li>{@link #SENT} 已发送：微信 {@code subscribeMessage.send} 返回零错误码，扣减一次订阅额度。</li>
 *   <li>{@link #SKIPPED_NO_QUOTA} 无额度跳过：剩余订阅次数为 0 或 {@code wx_openid} 为空，不调用微信、不扣额度。</li>
 *   <li>{@link #SKIPPED_STALE} 超窗口跳过：触发时刻已过且超出 10 分钟追补窗口，不发送、不扣额度。</li>
 *   <li>{@link #FAILED} 失败：微信返回非零错误码或调用抛异常，记录错误码、不扣额度。</li>
 * </ul>
 */
public enum ReminderSendResult {
    SENT,
    SKIPPED_NO_QUOTA,
    SKIPPED_STALE,
    FAILED
}
