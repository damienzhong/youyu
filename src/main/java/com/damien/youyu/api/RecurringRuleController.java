package com.damien.youyu.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.RecurringRuleRequest;
import com.damien.youyu.api.dto.RecurringRuleResponse;
import com.damien.youyu.domain.EndCondition;
import com.damien.youyu.domain.Frequency;
import com.damien.youyu.domain.RecurringRule;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.recurring.RecurringRuleService;

/**
 * 周期规则接口（关联需求 1.1、6.1、6.2、6.3、6.5、8.1、8.2、8.3）。
 *
 * <p>身份由 Spring Security 过滤链统一鉴权，当前用户由 {@link CurrentUser} 解析；当前账本由
 * {@link CurrentLedger} 依请求头 {@code X-Ledger-Id} 解析（缺省走默认账本、越权 / 不存在返回
 * {@code NOT_FOUND}，需求 8.1、8.3）。本控制器仅负责请求 / 响应装配与身份 / 账本解析——全部字段校验、
 * 归属判定与生命周期状态转换一律下沉到 {@link RecurringRuleService}（越权返回 {@code NOT_FOUND}，需求
 * 6.7、8.5），异常经 {@link com.damien.youyu.error.GlobalExceptionHandler} 统一成错误体。</p>
 *
 * <ul>
 *   <li>POST {@code /api/recurring/rules} 创建规则（201，含 id、初始 {@code ACTIVE}）。</li>
 *   <li>GET {@code /api/recurring/rules} 列出当前账本当前用户规则（含 {@code ACTIVE}/{@code PAUSED}）。</li>
 *   <li>GET {@code /api/recurring/rules/{id}} 规则详情（越权 {@code NOT_FOUND}）。</li>
 *   <li>PUT {@code /api/recurring/rules/{id}} 编辑（仅对之后新生成项生效，200）。</li>
 *   <li>DELETE {@code /api/recurring/rules/{id}} 删除（级联移除 {@code PENDING}，保留历史，204）。</li>
 *   <li>POST {@code /api/recurring/rules/{id}/pause} 暂停（{@code ACTIVE}→{@code PAUSED}，200）。</li>
 *   <li>POST {@code /api/recurring/rules/{id}/resume} 恢复（{@code PAUSED}→{@code ACTIVE}，200）。</li>
 * </ul>
 *
 * <p>{@code type} / {@code frequency} / {@code endCondition} 以原文字符串接收，由本控制器<b>宽松解析</b>
 * 为枚举：非法取值收敛为 {@code null} 交由服务层按需求 1.8（频率）/ 1.6（结束条件）映射为对应的周期记账
 * 错误码，避免被框架提前抛成 {@code REQUEST_BODY_INVALID}（另一套错误码 / 字段集）。</p>
 */
@RestController
@RequestMapping("/api/recurring/rules")
public class RecurringRuleController {

    private final RecurringRuleService recurringRuleService;
    private final CurrentLedger currentLedger;
    private final CurrentUser currentUser;

    public RecurringRuleController(RecurringRuleService recurringRuleService,
            CurrentLedger currentLedger, CurrentUser currentUser) {
        this.recurringRuleService = recurringRuleService;
        this.currentLedger = currentLedger;
        this.currentUser = currentUser;
    }

    /** 创建周期规则：成功返回 201 与该规则（含 id、初始 {@code ACTIVE}）。 */
    @PostMapping
    public ResponseEntity<RecurringRuleResponse> create(@RequestBody RecurringRuleRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        RecurringRule rule = recurringRuleService.create(
                userId,
                ledgerId,
                req.type(),
                req.amount(),
                req.categoryId(),
                req.accountId(),
                req.note(),
                parseFrequency(req.frequency()),
                req.weeklyDays(),
                req.monthDay(),
                req.monthEnd(),
                req.yearMonth(),
                req.yearDay(),
                req.startDate(),
                parseEndCondition(req.endCondition()),
                req.untilDate(),
                req.countN(),
                req.postMode());
        return ResponseEntity.status(HttpStatus.CREATED).body(RecurringRuleResponse.from(rule));
    }

    /** 列出当前账本当前用户的规则（含 {@code ACTIVE}/{@code PAUSED}）。 */
    @GetMapping
    public ResponseEntity<List<RecurringRuleResponse>> list() {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        List<RecurringRuleResponse> rules = recurringRuleService.list(userId, ledgerId).stream()
                .map(RecurringRuleResponse::from)
                .toList();
        return ResponseEntity.ok(rules);
    }

    /** 规则详情：不存在或越权（跨用户 / 跨账本）返回 {@code NOT_FOUND}。 */
    @GetMapping("/{id}")
    public ResponseEntity<RecurringRuleResponse> get(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        RecurringRule rule = recurringRuleService.get(userId, ledgerId, id);
        return ResponseEntity.ok(RecurringRuleResponse.from(rule));
    }

    /** 编辑规则：仅对之后新生成项生效，成功返回 200 与最新规则。 */
    @PutMapping("/{id}")
    public ResponseEntity<RecurringRuleResponse> update(
            @PathVariable Long id, @RequestBody RecurringRuleRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        RecurringRule rule = recurringRuleService.update(
                userId,
                ledgerId,
                id,
                req.type(),
                req.amount(),
                req.categoryId(),
                req.accountId(),
                req.note(),
                parseFrequency(req.frequency()),
                req.weeklyDays(),
                req.monthDay(),
                req.monthEnd(),
                req.yearMonth(),
                req.yearDay(),
                req.startDate(),
                parseEndCondition(req.endCondition()),
                req.untilDate(),
                req.countN(),
                req.postMode());
        return ResponseEntity.ok(RecurringRuleResponse.from(rule));
    }

    /** 删除规则：级联移除全部 {@code PENDING}，保留历史，成功返回 204。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        recurringRuleService.delete(userId, ledgerId, id);
        return ResponseEntity.noContent().build();
    }

    /** 暂停规则（{@code ACTIVE}→{@code PAUSED}）：既有 {@code PENDING} 不变，成功返回 200。 */
    @PostMapping("/{id}/pause")
    public ResponseEntity<RecurringRuleResponse> pause(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        RecurringRule rule = recurringRuleService.pause(userId, ledgerId, id);
        return ResponseEntity.ok(RecurringRuleResponse.from(rule));
    }

    /** 恢复规则（{@code PAUSED}→{@code ACTIVE}）：仅生成恢复当日及之后期次，成功返回 200。 */
    @PostMapping("/{id}/resume")
    public ResponseEntity<RecurringRuleResponse> resume(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        RecurringRule rule = recurringRuleService.resume(userId, ledgerId, id);
        return ResponseEntity.ok(RecurringRuleResponse.from(rule));
    }

    /**
     * 宽松解析频率枚举：{@code null} / 空白 / 非法取值一律收敛为 {@code null}，交由
     * {@link RecurringRuleService} 按需求 1.8 / 2.10 映射为 {@code RECURRING_FREQUENCY_INVALID}。
     */
    private static Frequency parseFrequency(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Frequency.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * 宽松解析结束条件枚举：{@code null} / 空白 / 非法取值一律收敛为 {@code null}，交由
     * {@link RecurringRuleService} 按需求 1.6 映射为 {@code RECURRING_END_CONDITION_INVALID}。
     */
    private static EndCondition parseEndCondition(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return EndCondition.valueOf(raw.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
