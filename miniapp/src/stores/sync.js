import { defineStore } from 'pinia'
import { STORAGE_KEYS } from '../utils/config'
import * as outbox from '../utils/offline/outbox'

/**
 * 同步状态 store（Offline_Sync_System）：对外暴露待同步 / 需处理数量、上次同步时间、
 * 同步进行中与进度，驱动全局横幅（NetBanner）与同步中心页。
 *
 * 计数从 Outbox 读取；读元信息一律容错（安全默认 0 / null），绝不崩溃（需求 8.6）。
 */
export const useSyncStore = defineStore('sync', {
  state: () => ({
    pendingCount: 0,
    failedCount: 0,
    lastSyncAt: readLastSyncAt(),
    syncing: false,
    // 本轮进度：{ done, total }
    progress: { done: 0, total: 0 }
  }),

  getters: {
    /** 是否有需要用户处理的失败项。 */
    hasFailed: (state) => state.failedCount > 0,
    /** 是否有任何待同步 / 失败项（用于横幅是否展示）。 */
    hasOutbox: (state) => state.pendingCount + state.failedCount > 0
  },

  actions: {
    /** 从 Outbox 刷新计数（任一读写后调用）。 */
    refresh() {
      try {
        this.pendingCount = outbox.pendingCount()
        this.failedCount = outbox.failedCount()
      } catch (e) {
        this.pendingCount = 0
        this.failedCount = 0
      }
    },

    /** 标记一轮同步开始。 */
    beginSync(total) {
      this.syncing = true
      this.progress = { done: 0, total: total || 0 }
    },

    /** 推进进度。 */
    tick() {
      this.progress = { done: this.progress.done + 1, total: this.progress.total }
    },

    /** 标记一轮同步结束，记录上次同步时间并刷新计数。 */
    endSync() {
      this.syncing = false
      const now = Date.now()
      this.lastSyncAt = now
      try {
        uni.setStorageSync(STORAGE_KEYS.syncLastAt, now)
      } catch (e) {}
      this.refresh()
    }
  }
})

function readLastSyncAt() {
  try {
    const v = uni.getStorageSync(STORAGE_KEYS.syncLastAt)
    return v ? Number(v) : null
  } catch (e) {
    return null
  }
}
