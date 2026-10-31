package com.damien.youyu.vision;

import java.time.LocalDate;
import java.util.List;

/**
 * 截图识别的提示词。模型只负责照抄截图上的字并给出收支方向，金额 / 日期由 {@link ScreenshotParser} 解析校验。
 */
public final class ScreenshotPrompt {

    private ScreenshotPrompt() {
    }

    /**
     * @param today          今天（用于让模型把「今天」「10月21日」这类分组标题和行内时间拼完整）
     * @param categoryLabels 用户自己的支出 / 收入分类（形如「餐饮/外卖」），模型只能从中挑
     */
    public static String build(LocalDate today, List<String> expenseLabels, List<String> incomeLabels) {
        return """
                你是记账 App 的账单截图识别器。今天是 %s。请识别这张截图里的每一笔交易，只输出一个 JSON 对象：
                {"screen":"PAYMENT_RESULT|BILL_LIST|ORDER_DETAIL|RECEIPT|OTHER_BILL|NOT_BILL",
                 "app":"截图来自哪个App，如 微信/支付宝/招商银行/美团/淘宝，看不出填空",
                 "payAccount":"付款方式原文，如 零钱/余额宝/花呗/招商银行信用卡(1234)，看不出填空",
                 "entries":[{"time":"时间原文","amount":"金额原文","direction":"expense|income|refund|transfer",
                   "merchant":"商户或交易对方","item":"商品说明","billCategory":"App自带的分类原文",
                   "status":"交易状态原文，如 交易成功/交易关闭/退款成功，没有填空",
                   "category":"从下面分类表里挑一个最合适的，挑不准填空","uncertain":false}]}

                规则：
                1. 只照抄截图上实际印着的字，绝不编造；看不出的字段填空字符串。界面是英文也照样判断 app，例如英文版微信账单。
                   merchant 照抄该行标题整行原文（如「扫二维码付款-给张三」整行照抄，不要拆分、不要删字）；被截断的保留省略号。
                   截图最底部被切掉一半、看不全时间或金额的那一行不要输出。
                2. amount 照抄金额原文，保留正负号和小数，例如 "-23.00"、"+5000.00"、"18.70"。金额被遮挡、模糊或拿不准时 uncertain 填 true。
                3. time：列表页的日期常写在分组标题（如「今天」「昨天」「10月21日」），请和该行的时分拼在一起，例如 "10月21日 12:05"、"昨天 09:12"；截图上印了年份就带上年份。
                4. direction：花钱是 expense；收到钱（工资、收款、利息）是 income；退款是 refund；
                   信用卡还款、转到自己的其他账户、余额宝/零钱通转入转出、买入理财基金是 transfer。给别人转账算 expense。
                5. 支付成功页、订单详情、购物小票通常只有一笔，金额取实付金额（不是原价、不是优惠金额）；小票只记合计这一笔，不要拆商品明细。
                6. 页面顶部的月度合计、筛选栏、广告、余额不是交易，不要输出。
                7. 截图里没有任何交易（聊天记录、风景、普通网页等）时输出 {"screen":"NOT_BILL","app":"","payAccount":"","entries":[]}。
                8. category 只能从下表原样挑一个，不要自己造分类名：
                   支出分类：%s
                   收入分类：%s
                9. 只输出 JSON，不要任何解释文字。
                """.formatted(today, join(expenseLabels), join(incomeLabels));
    }

    private static String join(List<String> labels) {
        if (labels == null || labels.isEmpty()) {
            return "（无）";
        }
        return String.join("、", labels.size() > 120 ? labels.subList(0, 120) : labels);
    }
}
