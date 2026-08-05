package com.damien.youyu.service;

/**
 * 上报订阅授权响应：顶层<strong>恰好 1 项</strong>——增加后的剩余订阅次数（需求 5.2）。
 *
 * <p>{@code remainingQuota} 为该用户在本次授权上报之后的剩余订阅次数，取值恒 ∈ {@code [1,50]}
 * （累积上限 50，需求 5.3）。不含任何金额、账本名、邮箱与邀请码（需求 8.4）。</p>
 *
 * @param remainingQuota 上报后的剩余订阅次数，∈ {@code [1,50]}
 */
public record GrantResponse(int remainingQuota) {
}
