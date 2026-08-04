import { http } from '../utils/request'

/**
 * 成长数据与账本无关：两个方法都带 noLedger: true，不发送 X-Ledger-Id 头（需求 13.13、10.12）。
 * 全部成长请求收敛到本模块（对齐 api/invite.js 的既有写法），不要在页面里另起 http 调用。
 */

/**
 * 成长概览：15 项字段（含 9 枚徽章），对应后端 GET /api/growth。
 * 需登录态；这是本项目唯一的写入型 GET——服务端在该请求内顺带结算成长数据
 * （补发经验事件并回写等级、经验与天数），因此不要给它加任何 HTTP 缓存，
 * 也不要在同一屏内重复调用（服务端对概览侧结算另有 10 秒节流）。
 */
export function fetchGrowthOverview() {
  return http.get('/growth', { noLedger: true })
}

/**
 * 经验明细分页：{ items, total }，对应后端 GET /api/growth/events。
 * 需登录态；page 从 0 开始，size 为每页条数。
 * 本接口只读，不触发结算，故返回数据可能比成长概览旧，属预期行为。
 */
export function fetchGrowthEvents(page = 0, size = 20) {
  return http.get(`/growth/events?page=${page}&size=${size}`, { noLedger: true })
}
