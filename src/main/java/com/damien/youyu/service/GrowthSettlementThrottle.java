package com.damien.youyu.service;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 成长结算的概览侧节流器（需求 10.14）。
 *
 * <p><b>两侧节流的分工（刻意不对称）</b>：</p>
 * <table border="1">
 *   <caption>结算节流的两个窗口</caption>
 *   <tr><th>触发侧</th><th>窗口</th><th>状态存放位置</th></tr>
 *   <tr><td>成长概览 GET（需求 10.14）</td><td>10 秒</td><td><b>本类：应用实例进程内的内存</b></td></tr>
 *   <tr><td>记账提交后的结算（需求 9.15）</td><td>60 秒</td>
 *       <td><b>不在本类</b>：读 {@code user_growth.last_settled_at} 列</td></tr>
 * </table>
 *
 * <p>记账侧的 60 秒窗口<b>刻意不放进内存</b>：它的判定条件包含「{@code last_record_date}
 * 已等于结算日」，本就必须读一次成长档案行，顺手读同一行的 {@code last_settled_at}
 * 比再维护一份内存状态更简单，也天然跨实例一致（多实例部署时内存窗口会各自独立放行）。
 * 概览侧则被需求 10.14 明确规定为「保存在应用实例进程内的内存中、进程启动后该用户的首次请求执行结算」，
 * 故只能用内存。两者互不相干：本类的 10 秒窗口不影响记账触发的结算，反之亦然。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li><b>进程启动后首次请求必放行</b>：映射表初始为空，查不到记录即返回 {@code false}。</li>
 *   <li><b>统计维度是 {@code userId}</b>：不同用户互不影响。</li>
 *   <li><b>窗口取半开区间</b>：距上次结算不足 {@link #OVERVIEW_WINDOW_MILLIS} 毫秒才算「最近已结算」，
 *       恰好满窗口即放行。</li>
 *   <li><b>时钟统一</b>：一律用注入的 {@link Clock}（{@code TimeConfig} 提供，固定
 *       {@code Asia/Shanghai}），不得改用 {@code System.currentTimeMillis()}，
 *       否则测试无法用固定时钟精确驱动窗口边界。</li>
 *   <li><b>映射表有界</b>：键数达 {@link #MAX_KEYS} 时先清理一遍已滑出窗口的条目
 *       （对齐 {@code InviteRateLimiter} 的做法）。单条只存一个 {@code Long} 时刻，
 *       清理后仍超限也不再额外驱逐——极端情况下多占几 MB 内存，好过为了省内存把活跃用户误踢出窗口。</li>
 * </ul>
 */
@Component
public class GrowthSettlementThrottle {

    /** 概览侧窗口：10 秒（需求 10.14）。 */
    static final long OVERVIEW_WINDOW_MILLIS = 10_000L;

    /** 映射表键数上限：达到该数量时先回收已滑出窗口的条目。 */
    static final int MAX_KEYS = 10_000;

    private final Clock clock;

    /** 概览侧最近一次结算时刻：key = userId，value = 毫秒时刻。 */
    private final ConcurrentHashMap<Long, Long> overviewLastSettledAt = new ConcurrentHashMap<>();

    public GrowthSettlementThrottle(Clock clock) {
        this.clock = clock;
    }

    /**
     * 概览侧节流判定：同一 {@code userId} 在最近 10 秒内已由概览请求驱动过结算则返回 {@code true}。
     *
     * <p>本方法只读不写：放行后由调用方在结算真正执行后调用 {@link #markSettled(Long)}。</p>
     *
     * @param userId 令牌所标识的用户 id，可空
     * @return 应跳过本次结算返回 {@code true}；应执行结算返回 {@code false}
     *         （进程启动后该用户的首次请求恒返回 {@code false}）
     */
    public boolean overviewRecentlySettled(Long userId) {
        if (userId == null) {
            return false;
        }
        Long last = overviewLastSettledAt.get(userId);
        return last != null && clock.millis() - last < OVERVIEW_WINDOW_MILLIS;
    }

    /**
     * 记录一次结算时刻，只用于概览侧的 10 秒窗口。
     *
     * <p>记账侧的 60 秒窗口读 {@code user_growth.last_settled_at} 列，与本方法无关。</p>
     *
     * @param userId 令牌所标识的用户 id，为空时不记录
     */
    public void markSettled(Long userId) {
        if (userId == null) {
            return;
        }
        long now = clock.millis();
        if (overviewLastSettledAt.size() >= MAX_KEYS) {
            purgeExpired(now);
        }
        overviewLastSettledAt.put(userId, now);
    }

    /**
     * 回收时刻已滑出窗口的条目。
     *
     * <p>一律用 {@code remove(key, value)} 的原子两参数形式，避免误删刚被别的线程写入的新时刻。</p>
     */
    private void purgeExpired(long now) {
        for (Map.Entry<Long, Long> entry : overviewLastSettledAt.entrySet()) {
            if (now - entry.getValue() >= OVERVIEW_WINDOW_MILLIS) {
                overviewLastSettledAt.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    /** 仅供测试观察键的回收情况。 */
    int overviewKeyCount() {
        return overviewLastSettledAt.size();
    }
}
