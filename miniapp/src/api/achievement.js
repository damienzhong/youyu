import { http } from '../utils/request'

/**
 * 成就数据与账本无关：三个方法都带 noLedger: true，不发送 X-Ledger-Id 头（需求 6.11、9.12）。
 * 全部成就请求收敛到本模块（对齐 api/growth.js 与 api/invite.js 的既有写法），
 * 不要在页面里另起 http 调用。
 */

/**
 * 成就清单：{ achievements, unlockedCount, total }，对应后端 GET /api/achievements。
 * 需登录态；与成长概览同为写入型 GET——服务端在该请求内顺带结算成长数据，
 * 因此不要给它加任何 HTTP 缓存，也不要在同一屏内重复调用（服务端另有节流）。
 */
export function fetchAchievements() {
  return http.get('/achievements', { noLedger: true })
}

/**
 * 待播报成就：{ items, total }，对应后端 GET /api/achievements/pending。
 * 需登录态；本接口只读，不触发结算、不推进播报游标，
 * items 至多 10 项而 total 是截断前的全部待播报条数。
 */
export function fetchPendingAchievements() {
  return http.get('/achievements/pending', { noLedger: true })
}

/**
 * 推进播报游标：{ lastNotifiedEventId }，对应后端 POST /api/achievements/notices/ack。
 * 需登录态；lastEventId 必须是「本次已实际展示」的成就事件 id 的最大值，
 * 未展示的项要留在待播报集合内（需求 7.11）。
 * 服务端以 String 接收该字段，故这里显式转成字符串再放进请求体。
 */
export function ackAchievementNotices(lastEventId) {
  return http.post(
    '/achievements/notices/ack',
    { lastEventId: String(lastEventId) },
    { noLedger: true }
  )
}
