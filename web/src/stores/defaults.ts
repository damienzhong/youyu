/**
 * 快速记账默认值缓存（Pinia）。
 *
 * 为达成「3 秒 / 3 次点击」，QuickEntry 需要秒开即带默认账户。
 * 这里缓存「上次记账所用账户」，离线/秒开时先用本地缓存，
 * 服务端返回后再校准（见 design「快速记账流程」需求 6.1/6.2/6.5）。
 * 缓存持久化到 localStorage，跨会话保留。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'

const LAST_ACCOUNT_KEY = 'youyu_last_account_id'

function readLastAccount(): number | null {
  const raw = localStorage.getItem(LAST_ACCOUNT_KEY)
  if (!raw) return null
  const n = Number(raw)
  return Number.isFinite(n) ? n : null
}

export const useDefaultsStore = defineStore('defaults', () => {
  const lastAccountId = ref<number | null>(readLastAccount())

  /** 记账成功后记住本次账户，作为下次默认。 */
  function rememberAccount(accountId: number) {
    lastAccountId.value = accountId
    localStorage.setItem(LAST_ACCOUNT_KEY, String(accountId))
  }

  function clear() {
    lastAccountId.value = null
    localStorage.removeItem(LAST_ACCOUNT_KEY)
  }

  return { lastAccountId, rememberAccount, clear }
})
