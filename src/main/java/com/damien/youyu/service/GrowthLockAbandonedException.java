package com.damien.youyu.service;

/**
 * 成长结算在 500ms 墙钟预算内始终未能取得目标用户成长档案行的行级写锁，故<b>放弃本次结算</b>。
 *
 * <p><b>为什么放弃是安全的降级</b>：能与本次结算争抢同一 {@code user_growth} 行写锁的对手，
 * 只有「同一用户的另一次结算」（记账提交后的 {@code afterCommit} 回调、或成长概览 GET 顺带的结算）。
 * 而结算本身是<b>幂等</b>的——事实源（记账、事件、邀请、预算）都在库里，谁先拿到锁谁就把终态算对，
 * 后到者本就会读到同样的事实并算出同样的终态。因此当另一次结算正持锁时直接放弃、把成长数据留给
 * 那一次去写，不会丢任何经验或徽章：下一次结算（下次记账或下次打开成长页）会自然补齐。</p>
 *
 * <p><b>为什么用异常而不是返回空</b>：本异常<b>必须穿出</b>
 * {@code GrowthSettlementService.settle}（{@code @Transactional(REQUIRES_NEW)}）方法体，
 * 让 Spring 回滚这次独立事务——否则会把一个已建档（ODKU）但未完成的半成品事务提交掉。
 * 它在结算的事务边界<b>之外</b>（{@code GrowthSettlementTrigger.settleQuietly} 或
 * 成长概览查询）被吞掉并记一条 WARN，记账/导入接口与概览响应都感知不到。</p>
 *
 * <p>这是 {@code RuntimeException}（非受检）：结算路径全程不 catch 具体异常，靠事务边界外的
 * 统一兜底吞掉，与其它运行时异常（行锁超时、连接获取失败）走同一条降级路径。</p>
 */
public class GrowthLockAbandonedException extends RuntimeException {

    private final Long userId;

    public GrowthLockAbandonedException(Long userId, Throwable cause) {
        super("成长结算未能在墙钟预算内取得用户 " + userId + " 的行级写锁，放弃本次结算（下次自愈）", cause);
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }
}
