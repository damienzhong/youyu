package com.damien.youyu.service.recurring;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.damien.youyu.error.ApiException;

/**
 * 周期记账模板字段的<b>共享校验器</b>：把与需求 1 对齐、且在「创建规则」（{@link RecurringRuleService}）与
 * 「修改后确认待确认项」（{@link RecurringPendingItemService#confirm}）两条路径上口径必须完全一致的
 * 金额 / 备注校验集中于此，避免两处各写一份导致边界（如金额上限 999,999,999.99、备注 200 字）悄然分叉。
 *
 * <p>只收敛<b>错误码与语义在两处完全相同</b>的两项：</p>
 * <ul>
 *   <li><b>金额</b>：非空、最多 2 位小数、0.01–999,999,999.99（含端点），否则复用既有
 *       {@code AMOUNT_INVALID}（需求 1.3、1.4、4.8）。小数位超限用 {@code UNNECESSARY} 精度探测，
 *       与 {@code TransactionService} 同款手法。</li>
 *   <li><b>备注</b>：可空；非空时长度 ≤200，超长复用既有 {@code NOTE_TOO_LONG}（需求 1.4、4.8）。</li>
 * </ul>
 *
 * <p>类型 / 分类 / 账户不在此收敛：创建路径校验失败为 {@code RECURRING_RULE_INVALID}，而确认路径的分类 /
 * 账户「在当前账本已不存在」语义为 {@code RECURRING_ITEM_TARGET_MISSING}（需求 4.6），错误码不同，
 * 故各自在所属服务内按语境判定。</p>
 *
 * <p>Feature: recurring-transactions。</p>
 */
@Component
public class RecurringTemplateValidator {

    /** 模板金额允许范围（需求 1.3）：0.01–999,999,999.99，最多 2 位小数。 */
    static final BigDecimal AMOUNT_MIN = new BigDecimal("0.01");
    static final BigDecimal AMOUNT_MAX = new BigDecimal("999999999.99");
    /** 备注最大长度（需求 1.4）。 */
    static final int NOTE_MAX = 200;

    /**
     * 金额校验：非空、最多 2 位小数、0.01–999,999,999.99（含端点）。缺失 / 小数位超限 / 越界一律复用
     * {@code AMOUNT_INVALID}（需求 1.3、1.4、4.8）。
     *
     * @param rawAmount 待校验金额
     * @return 规范化到 2 位小数的金额
     * @throws ApiException AMOUNT_INVALID
     */
    public BigDecimal validateAmount(BigDecimal rawAmount) {
        if (rawAmount == null) {
            throw ApiException.amountInvalid();
        }
        BigDecimal normalized;
        try {
            normalized = rawAmount.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw ApiException.amountInvalid();
        }
        if (normalized.compareTo(AMOUNT_MIN) < 0 || normalized.compareTo(AMOUNT_MAX) > 0) {
            throw ApiException.amountInvalid();
        }
        return normalized;
    }

    /**
     * 备注校验：可空；非空时长度 ≤200，超长复用 {@code NOTE_TOO_LONG}（需求 1.4、4.8）。
     *
     * @param rawNote 待校验备注（可为 {@code null}）
     * @return 原备注（{@code null} 原样返回）
     * @throws ApiException NOTE_TOO_LONG
     */
    public String validateNote(String rawNote) {
        if (rawNote == null) {
            return null;
        }
        if (rawNote.length() > NOTE_MAX) {
            throw new ApiException("NOTE_TOO_LONG", HttpStatus.BAD_REQUEST, "备注最多 200 个字符", "note");
        }
        return rawNote;
    }
}
