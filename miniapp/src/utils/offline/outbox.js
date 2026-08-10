/**
 * 写队列 / 收件箱（Outbox，Offline_Sync_System 纯内核之一）：本地存储的待同步写操作有序列表。
 *
 * 设计约束：
 * - FIFO：list() 顺序恒等于入队顺序；出队只移除对应 clientToken 项，不扰动其余顺序。
 * - 每条 Outbox_Item 携带足够重放所需的全部字段（clientToken、localId、ledgerId、payload），
 *   不依赖入队后仍在内存中的临时状态。
 * - clientToken / localId 在一条项的生命周期内稳定不变（重试不重新生成）。
 * - 所有存储读写 try/catch 容错；写入失败明确失败（不留半条脏数据）。
 *
 * 仅依赖 uni 同步存储，可在 node 下用 vitest 直接测（mock 全局 uni）。
 *
 * Outbox_Status：'PENDING' | 'SYNCING' | 'FAILED'（成功后从队列移除，不保留常驻 SYNCED）。
 */

const OUTBOX_KEY = 'youyu_outbox'

/** 读取整个队列（异常 / 非数组时返回 []）。 */
export function list() {
  try {
    const v = uni.getStorageSync(OUTBOX_KEY)
    return Array.isArray(v) ? v : []
  } catch (e) {
    return []
  }
}

/** 覆盖写入整个队列；异常时抛出（供上层感知写失败）。 */
function writeAll(items) {
  uni.setStorageSync(OUTBOX_KEY, items)
}

/**
 * 追加一条待同步项到队尾（FIFO）。
 * @param {{clientToken:string, localId:string, ledgerId:*, payload:object}} item
 * @returns {object} 入队后的完整项
 * @throws 存储写入失败时抛出，调用方据此提示保存失败且不产生乐观记录
 */
export function enqueue(item) {
  const full = {
    clientToken: item.clientToken,
    localId: item.localId,
    ledgerId: item.ledgerId ?? null,
    payload: item.payload,
    status: 'PENDING',
    retryCount: 0,
    failReason: null,
    enqueuedAt: Date.now()
  }
  const items = list()
  items.push(full)
  writeAll(items) // 失败则抛出，队列保持原样（不留半条脏数据）
  return full
}

/** 按 clientToken 更新某项字段（内部工具）。找不到则无操作，返回是否命中。 */
function patch(clientToken, updater) {
  const items = list()
  const idx = items.findIndex((it) => it.clientToken === clientToken)
  if (idx < 0) return false
  items[idx] = { ...items[idx], ...updater(items[idx]) }
  try {
    writeAll(items)
    return true
  } catch (e) {
    return false
  }
}

/** 置为同步中。 */
export function markSyncing(clientToken) {
  return patch(clientToken, () => ({ status: 'SYNCING' }))
}

/** 置为同步失败并记录原因（retryCount +1）。 */
export function markFailed(clientToken, reason) {
  return patch(clientToken, (it) => ({
    status: 'FAILED',
    failReason: reason == null ? '同步失败' : String(reason),
    retryCount: (it.retryCount || 0) + 1
  }))
}

/** 置回待同步（网络错误中断时用；不改 retryCount）。 */
export function markPending(clientToken) {
  return patch(clientToken, () => ({ status: 'PENDING' }))
}

/** 用户手动重试：置回 PENDING、清空失败原因（复用原 clientToken，不改 retryCount 的历史累计）。 */
export function retry(clientToken) {
  return patch(clientToken, () => ({ status: 'PENDING', failReason: null }))
}

/** 按 clientToken 移除一项（同步成功 / 用户删除）；不扰动其余顺序。返回是否命中。 */
export function removeByToken(clientToken) {
  const items = list()
  const next = items.filter((it) => it.clientToken !== clientToken)
  if (next.length === items.length) return false
  try {
    writeAll(next)
    return true
  } catch (e) {
    return false
  }
}

/** 队列长度。 */
export function count() {
  return list().length
}

/** 失败项数量。 */
export function failedCount() {
  return list().filter((it) => it.status === 'FAILED').length
}

/** 待同步（PENDING）项数量。 */
export function pendingCount() {
  return list().filter((it) => it.status === 'PENDING').length
}

/** 清空整个队列（谨慎使用，仅测试 / 显式重置）。 */
export function clearAll() {
  try {
    uni.removeStorageSync(OUTBOX_KEY)
    return true
  } catch (e) {
    return false
  }
}
