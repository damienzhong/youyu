package com.damien.youyu.api.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 截图识别结果。只是预览，不写账本；用户确认后由前端走 {@code POST /api/imports/bills} 入账。
 *
 * @param shots     每张截图的结果，顺序与请求一致
 * @param remaining 今天还能识别的张数（已扣除本次计入额度的张数）
 * @param dailyLimit 每日额度
 */
public record ScreenshotRecognizeResponse(List<Shot> shots, int remaining, int dailyLimit) {

    /**
     * @param status            OK（识别出流水）/ NOT_BILL（不是账单截图）/ FAILED（识别服务调用失败）；
     *                          后两者不计入额度
     * @param screen            截图类型：PAYMENT_RESULT / BILL_LIST / ORDER_DETAIL / RECEIPT / OTHER_BILL / NOT_BILL
     * @param app               截图来源 App（模型识别的原文，如「微信」「支付宝」）
     * @param payAccountText    截图上的付款方式原文（如「零钱」「招商银行信用卡(1234)」）
     * @param suggestedAccountId 按付款方式匹配到的用户账户（匹配不上为 null）
     * @param dropped           金额看不清而丢弃的行数（提示用户手动补）
     */
    public record Shot(String status, String screen, String app, String payAccountText,
            Long suggestedAccountId, List<Entry> entries, int dropped) {
    }

    /**
     * 一笔识别结果。
     *
     * @param direction       EXPENSE / INCOME / REFUND / TRANSFER（后两者默认不直接入账，由前端处理）
     * @param amount          金额（恒为正，两位小数）
     * @param occurredAt      交易时间；截图上看不出时间为 null（前端按当前时间兜底并提示核对）
     * @param dateOnly        只看得出日期、看不出时分
     * @param amountUncertain 金额需要用户核对
     * @param note            入账备注，形如「商户 · 商品」（与账单导入一致，便于以后按习惯分类）
     * @param categoryId      推荐分类（null 时用默认分类）
     * @param categorySource  RULE / HABIT / MERCHANT / AI / KEYWORD / NONE
     * @param duplicate       账本里疑似已有的同一笔（金额相同、时间相差 10 分钟内）；没有为 null
     */
    public record Entry(String direction, BigDecimal amount, LocalDateTime occurredAt, boolean dateOnly,
            boolean amountUncertain, String merchant, String item, String note,
            Long categoryId, String categorySource, Duplicate duplicate) {
    }

    /** 账本里疑似重复的那一笔。 */
    public record Duplicate(Long id, LocalDateTime occurredAt, BigDecimal amount, String note) {
    }
}
