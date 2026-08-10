import { defineStore } from 'pinia'
import { STORAGE_KEYS } from '../utils/config'
import { setOnline } from '../utils/offline/netState'

/**
 * 网络态 store（Offline_Sync_System）：维护全局在线 / 离线状态与「仅 Wi-Fi 同步」偏好。
 *
 * - 初始化用 uni.getNetworkType（networkType==='none' 视为离线，其余在线）。
 * - 订阅 uni.onNetworkStatusChange 更新 online / networkType。
 * - 任一网络 API 调用异常一律默认按在线处理，绝不因网络感知失败阻断页面或记账（需求 1.5）。
 * - wifiOnly：从本地存储读「仅 Wi-Fi 下同步」开关；影响自动同步是否在蜂窝网络下暂缓（需求 8.4）。
 */
export const useNetStore = defineStore('net', {
  state: () => ({
    online: true,
    networkType: 'unknown',
    wifiOnly: uni.getStorageSync(STORAGE_KEYS.syncWifiOnly) === '1'
  }),

  getters: {
    isOnline: (state) => state.online,
    isWifi: (state) => state.networkType === 'wifi',
    /** 当前是否允许「自动」同步：在线，且（未开启仅 Wi-Fi 或当前确为 Wi-Fi）。 */
    autoSyncAllowed: (state) => state.online && (!state.wifiOnly || state.networkType === 'wifi')
  },

  actions: {
    /** 冷启动初始化：读当前网络类型并订阅变化。onChange 回调注入以便触发同步。 */
    init(onChange) {
      try {
        uni.getNetworkType({
          success: (res) => this.applyNetworkType(res && res.networkType),
          fail: () => { this.online = true }
        })
      } catch (e) {
        this.online = true
      }
      try {
        uni.onNetworkStatusChange((res) => {
          const wasOnline = this.online
          this.applyNetworkType(res && res.networkType, res && res.isConnected)
          if (typeof onChange === 'function') {
            try { onChange({ wasOnline, online: this.online, networkType: this.networkType }) } catch (e) {}
          }
        })
      } catch (e) {
        // 订阅失败不影响主流程
      }
    },

    /** 根据 networkType / isConnected 更新在线态，并同步解耦的 netState 标志（供 offlineHttp 读取）。 */
    applyNetworkType(networkType, isConnected) {
      this.networkType = networkType || 'unknown'
      if (typeof isConnected === 'boolean') {
        this.online = isConnected
      } else {
        this.online = !!networkType && networkType !== 'none'
      }
      setOnline(this.online)
    },

    /** 切换「仅 Wi-Fi 下同步」偏好并持久化。 */
    setWifiOnly(on) {
      this.wifiOnly = !!on
      try {
        uni.setStorageSync(STORAGE_KEYS.syncWifiOnly, on ? '1' : '')
      } catch (e) {}
    }
  }
})
