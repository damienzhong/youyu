package com.damien.youyu.service;

/**
 * 触发一次结算的来源，决定 {@link GrowthSettlementService#settle} 走哪一套节流（需求 9.15、10.14）。
 *
 * <p>两个来源对应两个<b>互不相干</b>的节流窗口：</p>
 * <ul>
 *   <li>{@link #RECORD}：新增有效记账交易的业务事务提交后触发（需求 9.1）。节流窗口 60 秒，
 *       判定条件是「{@code last_settled_at} 距今 &lt;60s <b>且</b> {@code last_record_date} 已等于
 *       结算日」——这本就要读一次成长档案行，故窗口状态读 {@code user_growth.last_settled_at} 列，
 *       不进内存（需求 9.15）。</li>
 *   <li>{@link #OVERVIEW}：已认证用户请求成长概览时触发（需求 9.1，先结算再返回）。节流窗口 10 秒，
 *       状态保存在应用实例进程内的内存里（{@link GrowthSettlementThrottle}），进程启动后该用户的
 *       首次请求必执行结算（需求 10.14）。该 10 秒节流<b>不影响</b>记账触发的结算。</li>
 * </ul>
 */
public enum TriggerSource {

    /** 记账提交后触发：60 秒节流，读 {@code user_growth.last_settled_at} 列判定（需求 9.15）。 */
    RECORD,

    /** 成长概览 GET 触发：10 秒节流，进程内内存判定（需求 10.14）。 */
    OVERVIEW
}
