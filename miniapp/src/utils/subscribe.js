/**
 * 订阅授权统一编排（retention-nudges）。
 *
 * 把「批量请求 wx.requestSubscribeMessage → 解析回调 → 按模板上报 accept」收敛一处，
 * 供提醒设置页与各高意愿时刻共用，避免各页各写一份、逻辑漂移。
 *
 * 微信硬约束：wx.requestSubscribeMessage 必须在用户点击手势的回调内调用，不能在页面加载/onShow 静默发起。
 * 本模块只「请求授权 + 上报」，绝不发送订阅消息、不触发任何后端副作用（除既有授权上报接口）。
 *
 * 纯函数（pickAcceptedTemplates / isAlwaysKeep / 模板映射）不依赖 uni API，供 vitest + fast-check 覆盖；
 * requestSubscribe 涉及 wx.* 交互，归手工验收。
 */
import { WX_REMINDER_TEMPLATE_ID, WX_BUDGET_REMINDER_TEMPLATE_ID } from './config'
import { grantReminderQuota } from '../api/reminder'
import { grantBudgetReminderQuota } from '../api/budgetReminder'

/** 微信一次授权对单模板至多得到一个 accept（一次一条），故按模板上报的 grantedCount 恒为 1。 */
export const GRANT_PER_TEMPLATE = 1

/**
 * 模板 id → 授权上报函数的映射（未知模板无映射、被忽略、不上报）。
 * 复用既有两个授权上报接口，不新增第二套通道。
 * 空模板 id（未配置构建期变量）不进入映射，避免把空串误当模板。
 */
export const TEMPLATE_REPORTERS = (() => {
  const map = {}
  if (WX_REMINDER_TEMPLATE_ID) {
    map[WX_REMINDER_TEMPLATE_ID] = (count) => grantReminderQuota(count)
  }
  if (WX_BUDGET_REMINDER_TEMPLATE_ID) {
    map[WX_BUDGET_REMINDER_TEMPLATE_ID] = (count) => grantBudgetReminderQuota(count)
  }
  return map
})()

/**
 * 从 wx.requestSubscribeMessage 回调结果中筛出「结果为 accept 且属于本次请求集」的模板 id。
 * 纯函数：输出 ⊆ requestedTmplIds；reject/ban/未请求的模板一律排除。
 *
 * @param {object} res 回调结果，形如 { [tmplId]: 'accept'|'reject'|'ban', errMsg? }
 * @param {string[]} requestedTmplIds 本次请求的模板 id 集
 * @returns {string[]} 被允许且属于请求集的模板 id（去重、保持请求集顺序）
 */
export function pickAcceptedTemplates(res, requestedTmplIds) {
  if (!res || !Array.isArray(requestedTmplIds)) return []
  const seen = new Set()
  const accepted = []
  for (const id of requestedTmplIds) {
    if (id == null || seen.has(id)) continue
    seen.add(id)
    if (res[id] === 'accept') accepted.push(id)
  }
  return accepted
}

/**
 * 解析 wx.getSetting({ withSubscriptions: true }) 的 subscriptionsSetting，判定某模板是否「总是保持已允许」。
 * 纯函数、安全默认：主开关关闭 / 结构缺失 / 该模板项缺失 / 取值非 'accept' 一律返回 false（按普通授权处理）。
 *
 * @param {object} subscriptionsSetting 形如 { mainSwitch: boolean, itemSettings: { [tmplId]: 'accept'|'reject'|'ban' } }
 * @param {string} tmplId 目标模板 id
 * @returns {boolean} 仅当该模板明确处于「总是保持已允许」时为真
 */
export function isAlwaysKeep(subscriptionsSetting, tmplId) {
  if (!subscriptionsSetting || !tmplId) return false
  if (subscriptionsSetting.mainSwitch !== true) return false
  const items = subscriptionsSetting.itemSettings
  if (!items || typeof items !== 'object') return false
  return items[tmplId] === 'accept'
}

/**
 * 在用户点击回调内发起批量订阅授权，并按模板上报 accept（uni.*，须由点击触发）。
 *
 * - 非微信环境（无 wx.requestSubscribeMessage）→ 直接返回 { accepted: [] }，不报错。
 * - tmplIds 过滤空值、去重、至多 3 个（微信上限）。
 * - 回调里对 pickAcceptedTemplates 得到的每个模板调 TEMPLATE_REPORTERS[id](GRANT_PER_TEMPLATE) 上报；
 *   reject/ban 不报；单模板上报失败只吞掉不影响其它；全程 try/catch 静默、不抛异常。
 *
 * @param {string[]} tmplIds 期望申请的模板 id 集
 * @returns {Promise<{accepted: string[]}>} 本次被允许并已上报的模板 id
 */
export function requestSubscribe(tmplIds) {
  const ids = Array.from(new Set((tmplIds || []).filter((x) => !!x))).slice(0, 3)
  if (ids.length === 0
      || typeof wx === 'undefined'
      || typeof wx.requestSubscribeMessage !== 'function') {
    return Promise.resolve({ accepted: [] })
  }
  return new Promise((resolve) => {
    try {
      wx.requestSubscribeMessage({
        tmplIds: ids,
        success: async (res) => {
          const accepted = pickAcceptedTemplates(res, ids)
          for (const id of accepted) {
            const report = TEMPLATE_REPORTERS[id]
            if (!report) continue
            try {
              await report(GRANT_PER_TEMPLATE)
            } catch (e) {
              // 单模板上报失败只吞掉，不影响其它模板与页面（需求 2.5）。
            }
          }
          resolve({ accepted })
        },
        fail: () => resolve({ accepted: [] })
      })
    } catch (e) {
      resolve({ accepted: [] })
    }
  })
}
