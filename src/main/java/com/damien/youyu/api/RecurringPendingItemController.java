package com.damien.youyu.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.damien.youyu.api.dto.RecurringBatchRequest;
import com.damien.youyu.api.dto.RecurringBatchResultResponse;
import com.damien.youyu.api.dto.RecurringItemConfirmRequest;
import com.damien.youyu.api.dto.RecurringPendingItemResponse;
import com.damien.youyu.domain.RecurringPendingItem;
import com.damien.youyu.security.CurrentLedger;
import com.damien.youyu.security.CurrentUser;
import com.damien.youyu.service.recurring.RecurringBatchResult;
import com.damien.youyu.service.recurring.RecurringPendingItemService;
import com.damien.youyu.service.recurring.RecurringReminderNotifier;

/**
 * 周期记账待确认项接口 {@code /api/recurring/pending-items}（tasks 7.2；需求 4.1、4.4、5.1、5.4、5.5、5.6、
 * 8.1、8.2、8.3）。
 *
 * <p>身份由 Spring Security 过滤链统一鉴权（无 / 失效令牌 → {@code UNAUTHENTICATED}，需求 8.2）；当前用户由
 * {@link CurrentUser} 解析，当前账本由 {@link CurrentLedger} 依请求头 {@code X-Ledger-Id} 解析（缺省走默认账本，
 * 越权 / 不存在 → {@code NOT_FOUND}，需求 8.1、8.3）。本控制器<b>只做请求 / 响应装配</b>：懒生成、查询、确认 /
 * 修改后确认、跳过、批量的全部校验、归属判定（跨租户 → {@code NOT_FOUND}）、状态机（已处理 →
 * {@code RECURRING_ITEM_ALREADY_PROCESSED}、目标缺失 → {@code RECURRING_ITEM_TARGET_MISSING}）与事务边界均
 * 下沉到 {@link RecurringPendingItemService}，异常经
 * {@link com.damien.youyu.error.GlobalExceptionHandler} 统一成错误体。</p>
 *
 * <ul>
 *   <li>{@code GET /api/recurring/pending-items}：先触发懒生成（需求 3.7），再返回当前账本 {@code PENDING}
 *       列表（确定性排序，需求 5.1、5.2、5.3）。</li>
 *   <li>{@code POST /api/recurring/pending-items/{id}/confirm}：确认入账，可携带覆盖字段实现「修改后确认」
 *       （需求 4.1、4.3），返回已确认的项。</li>
 *   <li>{@code POST /api/recurring/pending-items/{id}/skip}：跳过本期，返回已跳过的项（需求 4.4）。</li>
 *   <li>{@code POST /api/recurring/pending-items/batch-confirm}：批量确认，逐条独立事务，返回逐条结果与计数
 *       （需求 5.4、5.6）。</li>
 *   <li>{@code POST /api/recurring/pending-items/batch-skip}：批量跳过，同上（需求 5.5、5.6）。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/recurring/pending-items")
public class RecurringPendingItemController {

    private final RecurringPendingItemService pendingItemService;
    private final RecurringReminderNotifier reminderNotifier;
    private final CurrentLedger currentLedger;
    private final CurrentUser currentUser;

    public RecurringPendingItemController(RecurringPendingItemService pendingItemService,
            RecurringReminderNotifier reminderNotifier,
            CurrentLedger currentLedger, CurrentUser currentUser) {
        this.pendingItemService = pendingItemService;
        this.reminderNotifier = reminderNotifier;
        this.currentLedger = currentLedger;
        this.currentUser = currentUser;
    }

    /**
     * 待确认项列表：先触发懒生成让展示反映最新到期情况（需求 3.7），再返回当前账本状态为 {@code PENDING}
     * 的项（确定性排序，无则空列表，需求 5.1、5.2、5.3）。
     */
    @GetMapping
    public ResponseEntity<List<RecurringPendingItemResponse>> list() {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        List<RecurringPendingItem> items = pendingItemService.queryPendingItems(ledgerId);
        List<RecurringPendingItemResponse> body = items.stream()
                .map(RecurringPendingItemResponse::from)
                .toList();
        // 提醒衔接（需求 7.1、7.6）：懒生成 + 查询已完成、此处已在所有主路径事务边界之外（本方法与
        // queryPendingItems 均非 @Transactional）。存在 PENDING 待确认项时向所有者发一条提醒；发送在
        // 请求线程内同步进行，但 notifyIfPending 内部吞掉一切故障、绝不影响本查询的成功/失败与返回结果。
        reminderNotifier.notifyIfPending(userId, ledgerId, !items.isEmpty());
        return ResponseEntity.ok(body);
    }

    /**
     * 确认入账（含修改后确认）：请求体可携带覆盖字段（{@code amount}/{@code categoryId}/{@code accountId}/
     * {@code note}/{@code occurredAt}），任一非空即覆盖该项快照，缺省沿用快照（需求 4.1、4.3）。成功返回
     * 已确认的项（{@code status=CONFIRMED}，{@code confirmedTransactionId} 指向真实流水）。
     */
    @PostMapping("/{id}/confirm")
    public ResponseEntity<RecurringPendingItemResponse> confirm(
            @PathVariable Long id,
            @RequestBody(required = false) RecurringItemConfirmRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        RecurringItemConfirmRequest overrides = req == null
                ? new RecurringItemConfirmRequest(null, null, null, null, null)
                : req;
        RecurringPendingItem confirmed = pendingItemService.confirm(
                userId, ledgerId, id,
                overrides.amount(),
                overrides.categoryId(),
                overrides.accountId(),
                overrides.note(),
                overrides.occurredAt());
        return ResponseEntity.ok(RecurringPendingItemResponse.from(confirmed));
    }

    /** 跳过本期（PENDING → SKIPPED，不生成流水、不改余额）：成功返回已跳过的项（需求 4.4）。 */
    @PostMapping("/{id}/skip")
    public ResponseEntity<RecurringPendingItemResponse> skip(@PathVariable Long id) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        RecurringPendingItem skipped = pendingItemService.skip(userId, ledgerId, id);
        return ResponseEntity.ok(RecurringPendingItemResponse.from(skipped));
    }

    /**
     * 批量确认：逐条在各自独立事务内确认入账，返回逐条结果与成功 / 失败计数；某条失败仅记为失败不影响
     * 其余（需求 5.4、5.6）。{@code ids} 缺省 / 为空返回空批量结果。
     */
    @PostMapping("/batch-confirm")
    public ResponseEntity<RecurringBatchResultResponse> batchConfirm(
            @RequestBody(required = false) RecurringBatchRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        List<Long> ids = req == null ? null : req.ids();
        RecurringBatchResult result = pendingItemService.batchConfirm(userId, ledgerId, ids);
        return ResponseEntity.ok(RecurringBatchResultResponse.from(result));
    }

    /**
     * 批量跳过：逐条在各自独立事务内跳过，仅其中 {@code PENDING} 置 {@code SKIPPED}，已处理条目记为失败
     * 而不影响其余（需求 5.5、5.6）。{@code ids} 缺省 / 为空返回空批量结果。
     */
    @PostMapping("/batch-skip")
    public ResponseEntity<RecurringBatchResultResponse> batchSkip(
            @RequestBody(required = false) RecurringBatchRequest req) {
        Long ledgerId = currentLedger.requireLedgerId();
        Long userId = currentUser.requireUserId();
        List<Long> ids = req == null ? null : req.ids();
        RecurringBatchResult result = pendingItemService.batchSkip(userId, ledgerId, ids);
        return ResponseEntity.ok(RecurringBatchResultResponse.from(result));
    }
}
