import { http } from '../utils/request'

/**
 * 预算提醒与账本无关：全部方法都带 noLedger: true，不发送 X-Ledger-Id 头（需求 7.4、10.9）。
 * 全部预算提醒请求收敛到本模块（对齐 api/reminder.js 的既有写法），不要在页面里另起 http 调用。
 * 独立于记账提醒（api/reminder.js）：各自的模板、额度、开关互不影响。
 */

/**
 * 预算提醒状态：{ enabled, remainingQuota }，对应后端 GET /api/budget-reminders。
 * 需登录态；只读，返回预算提醒开关与剩余订阅次数（无记录缺省 { enabled: true, remainingQuota: 0 }）。
 */
export function fetchBudgetReminderStatus() {
  return http.get('/budget-reminders', { noLedger: true })
}

/**
 * 更新预算提醒偏好：对应后端 PUT /api/budget-reminders/preference。
 * body：{ enabled }（布尔）；成功返回更新后的 { enabled, remainingQuota }。
 */
export function updateBudgetReminderPreference(enabled) {
  return http.put('/budget-reminders/preference', { enabled }, { noLedger: true })
}

/**
 * 上报预算提醒订阅授权：对应后端 POST /api/budget-reminders/quota:grant。
 * count 为本次经 wx.requestSubscribeMessage 对预算提醒模板点击「允许」的次数（1..5）；
 * 成功返回增加后的剩余订阅次数 { remainingQuota }。
 */
export function grantBudgetReminderQuota(count) {
  return http.post('/budget-reminders/quota:grant', { grantedCount: count }, { noLedger: true })
}
