import { http } from '../utils/request'

/**
 * 周期记账接口。周期规则与其生成的待确认项按当前账本（X-Ledger-Id）隔离，
 * 沿用 utils/request 依当前账本自动附带账本头（与 api/aa.js 同口径，无需显式传 ledgerId）。
 * 金额一律以字符串承载（2 位小数）；越权 / 不存在由后端返回 NOT_FOUND。
 *
 * 全部周期记账请求收敛到本模块，不要在页面里另起 http 调用。
 */

// ── 周期规则（/api/recurring/rules）──────────────────────────────

/**
 * 创建周期规则：对应后端 POST /api/recurring/rules。成功 201 返回规则（含 id、初始状态 ACTIVE）。
 * payload：{ type: 'expense'|'income', amount, categoryId, accountId, note?,
 *   frequency: 'DAILY'|'WEEKLY'|'MONTHLY'|'YEARLY',
 *   weekdays?:[1..7], dayOfMonth?:1..31|'LAST', month?, dayOfYear?,
 *   startDate?, endCondition: 'NEVER'|'UNTIL_DATE'|'COUNT', untilDate?, countN? }
 */
export function createRecurringRule(payload) {
  return http.post('/recurring/rules', payload)
}

/** 规则列表：对应后端 GET /api/recurring/rules，返回当前账本当前用户的规则（含 ACTIVE/PAUSED）。 */
export function fetchRecurringRules() {
  return http.get('/recurring/rules')
}

/** 规则详情：对应后端 GET /api/recurring/rules/{id}；越权 / 不存在返回 NOT_FOUND。 */
export function fetchRecurringRule(id) {
  return http.get(`/recurring/rules/${id}`)
}

/**
 * 编辑规则：对应后端 PUT /api/recurring/rules/{id}。
 * 编辑仅对之后新生成的待确认项生效，既有 PENDING 保留其生成时的模板快照。payload 同创建。
 */
export function updateRecurringRule(id, payload) {
  return http.put(`/recurring/rules/${id}`, payload)
}

/**
 * 删除规则：对应后端 DELETE /api/recurring/rules/{id}。
 * 级联移除其全部 PENDING 待确认项，保留已 CONFIRMED 历史流水与已 SKIPPED 期次记录。
 */
export function deleteRecurringRule(id) {
  return http.del(`/recurring/rules/${id}`)
}

/** 暂停规则（ACTIVE→PAUSED）：对应后端 POST /api/recurring/rules/{id}/pause；既有 PENDING 保持不变。 */
export function pauseRecurringRule(id) {
  return http.post(`/recurring/rules/${id}/pause`)
}

/** 恢复规则（PAUSED→ACTIVE）：对应后端 POST /api/recurring/rules/{id}/resume；仅生成恢复当日及之后期次。 */
export function resumeRecurringRule(id) {
  return http.post(`/recurring/rules/${id}/resume`)
}

// ── 待确认项（/api/recurring/pending-items）───────────────────────

/**
 * 待确认项列表：对应后端 GET /api/recurring/pending-items。
 * 后端先触发懒生成再返回当前账本 PENDING 列表（按到期日升序，可复现）；无待确认项返回空列表不报错。
 */
export function fetchRecurringPendingItems() {
  return http.get('/recurring/pending-items')
}

/**
 * 确认入账：对应后端 POST /api/recurring/pending-items/{id}/confirm，走既有交易创建链路。
 * overrides 可选，用于「修改后确认」：{ amount?, categoryId?, accountId?, note?, occurredAt? }；
 * 不传则以待确认项快照字段入账。
 */
export function confirmRecurringPendingItem(id, overrides) {
  return http.post(`/recurring/pending-items/${id}/confirm`, overrides || {})
}

/** 跳过本期（PENDING→SKIPPED）：对应后端 POST /api/recurring/pending-items/{id}/skip，不生成流水、不改余额。 */
export function skipRecurringPendingItem(id) {
  return http.post(`/recurring/pending-items/${id}/skip`)
}

/**
 * 批量确认：对应后端 POST /api/recurring/pending-items/batch-confirm。
 * body：{ ids:[...] }；逐条各自独立事务处理，返回逐条结果与成功 / 失败计数，部分失败可逐条判定。
 */
export function batchConfirmRecurringPendingItems(ids) {
  return http.post('/recurring/pending-items/batch-confirm', { ids })
}

/**
 * 批量跳过：对应后端 POST /api/recurring/pending-items/batch-skip。
 * body：{ ids:[...] }；仅将其中 PENDING 置 SKIPPED，已处理条目在结果中标记失败而不影响其余。
 */
export function batchSkipRecurringPendingItems(ids) {
  return http.post('/recurring/pending-items/batch-skip', { ids })
}
