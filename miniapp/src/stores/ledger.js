import { defineStore } from 'pinia'
import { STORAGE_KEYS } from '../utils/config'
import { listLedgers } from '../api/ledger'

/**
 * 账本状态：持有账本列表与当前账本 id。当前账本 id 落地本地存储，
 * 请求封装(utils/request.js)据此带上 X-Ledger-Id 头实现按账本隔离。
 */
export const useLedgerStore = defineStore('ledger', {
  state: () => ({
    ledgers: [],
    currentLedgerId: uni.getStorageSync(STORAGE_KEYS.ledgerId) || null
  }),

  getters: {
    current: (state) =>
      state.ledgers.find((l) => l.id === state.currentLedgerId) || state.ledgers[0] || null,
    currentName() {
      return this.current?.name || '默认账本'
    }
  },

  actions: {
    /** 拉取账本列表；若当前账本未设置或已不存在，回退到默认/第一个。 */
    async load() {
      const list = await listLedgers()
      this.ledgers = list
      const exists = list.some((l) => l.id === this.currentLedgerId)
      if (!exists) {
        const def = list.find((l) => l.isDefault) || list[0]
        this.setCurrent(def ? def.id : null)
      }
      return list
    },

    setCurrent(id) {
      this.currentLedgerId = id
      if (id) uni.setStorageSync(STORAGE_KEYS.ledgerId, id)
      else uni.removeStorageSync(STORAGE_KEYS.ledgerId)
    },

    clear() {
      this.ledgers = []
      this.setCurrent(null)
    }
  }
})
