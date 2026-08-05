import { http } from '../utils/request'

/**
 * 自定义提醒与账本无关：全部方法都带 noLedger: true，不发送 X-Ledger-Id 头（需求 8.5、10.11）。
 * 全部提醒请求收敛到本模块（对齐 api/streak.js 的既有写法），不要在页面里另起 http 调用。
 */

/**
 * 提醒列表：{ reminders: [...], remainingQuota }，对应后端 GET /api/reminders。
 * 需登录态；只读，每项含 reminderId/frequency/remindTime/enabled 四项，并返回剩余订阅次数。
 */
export function fetchReminders() {
  return http.get('/reminders', { noLedger: true })
}

/**
 * 创建提醒：对应后端 POST /api/reminders。
 * body：{ frequency, remindTime, enabled? }；成功返回创建后的提醒项。
 */
export function createReminder(body) {
  return http.post('/reminders', body, { noLedger: true })
}

/**
 * 更新提醒：对应后端 PUT /api/reminders/{id}。
 * body：{ frequency?, remindTime?, enabled? }，仅提交字段被更新；成功返回更新后的提醒项。
 */
export function updateReminder(id, body) {
  return http.put(`/reminders/${id}`, body, { noLedger: true })
}

/**
 * 删除提醒：对应后端 DELETE /api/reminders/{id}，成功无返回体。
 */
export function deleteReminder(id) {
  return http.del(`/reminders/${id}`, { noLedger: true })
}

/**
 * 上报订阅授权：对应后端 POST /api/reminders/quota:grant。
 * count 为本次经 wx.requestSubscribeMessage 点击「允许」的次数（1..5）；成功返回增加后的剩余订阅次数。
 */
export function grantReminderQuota(count) {
  return http.post('/reminders/quota:grant', { grantedCount: count }, { noLedger: true })
}
