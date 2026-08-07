package com.damien.youyu.service;

import com.damien.youyu.error.ApiException;

/**
 * 分享卡片类型（Share_Card_System，需求 1.1、1.7、1.8、10.5、10.9）。
 *
 * <p>v1 恰好 6 种、类型键互不重复、<strong>区分大小写</strong>：{@code STREAK_MILESTONE}（连续记账里程碑）、
 * {@code MONTHLY_SUMMARY}（本月总结）、{@code ANNUAL_BILL}（年度账单）、{@code ACHIEVEMENT_BADGE}（获得徽章）、
 * {@code BUDGET_ACHIEVED}（预算达成）、{@code LEVEL_UP}（成长升级）。为内部只读辅助类型，不落库、非持久化实体。</p>
 *
 * <h2>账本语义分层（需求 1.7、1.8、9 项约定）</h2>
 *
 * <p>账本相关卡片（{@code MONTHLY_SUMMARY}/{@code ANNUAL_BILL}/{@code BUDGET_ACHIEVED}）按 {@code X-Ledger-Id}
 * 解析的当前账本隔离取数；账本无关卡片（{@code STREAK_MILESTONE}/{@code ACHIEVEMENT_BADGE}/{@code LEVEL_UP}）
 * 跨用户全部账本按用户维度取数，<strong>绝不因 {@code X-Ledger-Id} 缺失或不可访问被拒绝</strong>。
 * {@link #isLedgerScoped()} 是决定账本语义的路由判别式，须先于账本解析被识别（见 {@code ShareCardController}
 * 的固定错误优先级：鉴权 → cardType 路由 → 账本 → 参数）。</p>
 */
public enum ShareCardType {

    /** 连续记账里程碑（账本无关，需求 3）。 */
    STREAK_MILESTONE(false),

    /** 本月总结（账本相关，需求 4）。 */
    MONTHLY_SUMMARY(true),

    /** 年度账单（账本相关，需求 5）。 */
    ANNUAL_BILL(true),

    /** 获得徽章（账本无关，需求 6）。 */
    ACHIEVEMENT_BADGE(false),

    /** 预算达成（账本相关，需求 7）。 */
    BUDGET_ACHIEVED(true),

    /** 成长升级（账本无关，需求 8）。 */
    LEVEL_UP(false);

    private final boolean ledgerScoped;

    ShareCardType(boolean ledgerScoped) {
        this.ledgerScoped = ledgerScoped;
    }

    /**
     * 该卡片是否为账本相关卡片（需求 1.7、1.8）。
     *
     * @return {@code true} 表示账本相关（{@code MONTHLY_SUMMARY}/{@code ANNUAL_BILL}/{@code BUDGET_ACHIEVED}），
     *         须以当前账本隔离取数；{@code false} 表示账本无关，跨用户全部账本按用户维度取数、不读取 {@code X-Ledger-Id}
     */
    public boolean isLedgerScoped() {
        return ledgerScoped;
    }

    /**
     * 将请求 {@code type} 参数解析为卡片类型（区分大小写，需求 1.1、10.5）。
     *
     * <p>仅接受与枚举名<strong>逐字符相等</strong>的 6 种取值之一；非 6 种取值之一（含 {@code null}、空白、
     * 大小写不符）一律抛 {@link ApiException#reportParamInvalid(String, String)}
     * （复用既有错误码 {@code REPORT_PARAM_INVALID}，不新增错误码，需求 10.5、10.9、13.3）。</p>
     *
     * @param raw 原始 {@code type} 参数值
     * @return 对应的 {@link ShareCardType}
     * @throws ApiException {@code REPORT_PARAM_INVALID}（字段 {@code type}）当取值不属于 6 种卡片类型
     */
    public static ShareCardType parse(String raw) {
        if (raw != null) {
            for (ShareCardType type : values()) {
                if (type.name().equals(raw)) {
                    return type;
                }
            }
        }
        throw ApiException.reportParamInvalid("type",
                "卡片类型非法，仅支持 STREAK_MILESTONE/MONTHLY_SUMMARY/ANNUAL_BILL/"
                        + "ACHIEVEMENT_BADGE/BUDGET_ACHIEVED/LEVEL_UP");
    }
}
