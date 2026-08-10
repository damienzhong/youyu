/**
 * 同步引擎（Offline_Sync_System 纯内核之一）：串行重放 Outbox 待同步项。
 *
 * 完全依赖注入（outbox / replay / isNetworkError / onSynced），不 import 具体存储或网络实现，
 * 从而可在 node 下用 vitest 直接测。
 *
 * 串行语义（Requirements 5.4、5.5、5.6、7.1、7.4）：
 * - 按入队顺序逐项处理状态为 PENDING 的项：markSyncing → replay。
 * - 成功：removeByToken 出队，回调 onSynced(item, serverTx) 供上层用服务端记录替换本地临时记录。
 * - 网络错误（isNetworkError）：markPending 保留、**停止本轮**（不再处理后续项），等待下次触发。
 * - 业务错误（其它）：markFailed 记录原因、**继续**处理后续项（不阻塞）。
 * - 防重入：同一时刻至多一个同步循环（running 标志）。
 *
 * 「无限自动重试」由设计本身规避：业务错误立即转 FAILED（不自动重试），网络错误停止本轮，
 * 因此不存在对同一必然失败项的自动重试风暴；FAILED 项仅由用户手动 retry() 置回 PENDING 后才会再被处理。
 */

/** 默认的网络错误判定：后端统一错误体里 code 为 NETWORK_ERROR。 */
export function defaultIsNetworkError(err) {
  return !!err && err.code === 'NETWORK_ERROR'
}

/**
 * @param {object} deps
 * @param {object} deps.outbox   队列模块（list/markSyncing/markFailed/markPending/removeByToken）
 * @param {(item:object)=>Promise<object>} deps.replay  重放单项，成功 resolve 服务端记录，失败 reject 错误
 * @param {(err:any)=>boolean} [deps.isNetworkError]     网络错误判定
 * @param {(item:object, serverTx:object)=>void} [deps.onSynced]  单项成功回调
 */
export function createSyncEngine({ outbox, replay, isNetworkError = defaultIsNetworkError, onSynced } = {}) {
  let running = false

  async function sync() {
    if (running) return { skipped: true, synced: 0, failed: 0, stopped: false }
    running = true
    let synced = 0
    let failed = 0
    let stopped = false
    try {
      const pending = outbox.list().filter((it) => it.status === 'PENDING')
      for (const item of pending) {
        outbox.markSyncing(item.clientToken)
        try {
          const serverTx = await replay(item)
          outbox.removeByToken(item.clientToken)
          synced++
          if (typeof onSynced === 'function') {
            try {
              onSynced(item, serverTx)
            } catch (cbErr) {
              // 回调异常不影响同步主流程
            }
          }
        } catch (err) {
          if (isNetworkError(err)) {
            outbox.markPending(item.clientToken)
            stopped = true
            break
          }
          outbox.markFailed(item.clientToken, err && err.message)
          failed++
        }
      }
    } finally {
      running = false
    }
    return { synced, failed, stopped }
  }

  return { sync, isRunning: () => running }
}
