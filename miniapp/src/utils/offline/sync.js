/**
 * 同步编排（Offline_Sync_System 运行时入口）：把 syncEngine + Outbox + offlineHttp.replay + 状态 store
 * 串起来，并在合适时机触发。仅在 pinia 就绪的运行时调用（App onShow / 网络恢复 / 同步中心手动）。
 *
 * 触发策略：
 * - 自动触发（网络恢复 / App 前台）：受「仅 Wi-Fi 下同步」偏好约束（蜂窝下暂缓）。
 * - 手动触发（同步中心「立即同步」）：不受 Wi-Fi 偏好约束，但仍要求在线。
 *
 * 同步完成后通过 uni.$emit('offline:sync-done') 通知页面刷新列表（用服务端记录替换本地临时记录）。
 */
import { createSyncEngine } from './syncEngine'
import { offlineHttp } from '../request'
import * as outbox from './outbox'
import { useNetStore } from '../../stores/net'
import { useSyncStore } from '../../stores/sync'

let engine

function getEngine() {
  if (!engine) {
    engine = createSyncEngine({
      outbox,
      replay: (item) => offlineHttp.replay(item),
      isNetworkError: (err) => offlineHttp.isNetworkError(err)
    })
  }
  return engine
}

/**
 * 触发一次同步。
 * @param {{manual?:boolean}} opts manual=true 为用户手动触发（绕过 Wi-Fi 偏好）
 * @returns {Promise<{skipped?:boolean, synced?:number, failed?:number, stopped?:boolean}>}
 */
export async function runSync(opts = {}) {
  const manual = !!opts.manual
  let net
  let sync
  try {
    net = useNetStore()
    sync = useSyncStore()
  } catch (e) {
    // store 未就绪（极早期调用）：安全跳过
    return { skipped: true }
  }

  if (!net.online) return { skipped: true }
  if (!manual && !net.autoSyncAllowed) return { skipped: true } // 仅 Wi-Fi 且当前蜂窝：自动暂缓

  const total = outbox.pendingCount()
  if (total === 0) {
    sync.refresh()
    return { skipped: true, synced: 0 }
  }

  sync.beginSync(total)
  const eng = getEngine()
  let res
  try {
    res = await eng.sync()
  } finally {
    sync.endSync()
  }
  try {
    uni.$emit('offline:sync-done', res)
  } catch (e) {}
  return res
}

/** 供同步中心展示：刷新计数。 */
export function refreshSyncState() {
  try {
    useSyncStore().refresh()
  } catch (e) {}
}
