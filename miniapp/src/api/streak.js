import { http } from '../utils/request'

/**
 * 连续记账数据与账本无关：两个方法都带 noLedger: true，不发送 X-Ledger-Id 头（需求 6.11、9.12）。
 * 全部连续记账请求收敛到本模块（对齐 api/growth.js 与 api/achievement.js 的既有写法），
 * 不要在页面里另起 http 调用。
 */

/**
 * 连续记账概览：14 项字段，对应后端 GET /api/streak。
 * 需登录态；这是写入型 GET——服务端在该请求内顺带结算成长数据（复用与成长概览同一个 10 秒节流），
 * 因此不要给它加任何 HTTP 缓存，也不要在同一屏内重复调用。
 */
export function fetchStreakOverview() {
  return http.get('/streak', { noLedger: true })
}

/**
 * 历史区间分页：{ items, total }，对应后端 GET /api/streak/segments。
 * 需登录态；page 从 0 开始，size 为每页条数（默认 20）。
 * 本接口只读，不触发结算，故返回数据可能比连续记账概览旧，属预期行为。
 */
export function fetchStreakSegments(page = 0, size = 20) {
  return http.get(`/streak/segments?page=${page}&size=${size}`, { noLedger: true })
}
