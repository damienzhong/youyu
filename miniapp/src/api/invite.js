import { http } from '../utils/request'

/**
 * 邀请数据与账本无关，全部方法都带 noLedger: true，不发送 X-Ledger-Id 头。
 * 公开查询（无需登录态）另带 auth: false。
 */

/**
 * 邀请信息：{ inviteCode, inviteLink, invitedCount }，对应后端 GET /api/invite。
 * 需登录态；首次访问时后端为当前用户补发邀请码。
 */
export function fetchInviteInfo() {
  return http.get('/invite', { noLedger: true })
}

/**
 * 邀请二维码：{ imageBase64 }（不含 data URI 前缀），对应后端 GET /api/invite/qrcode。
 * 需登录态；生成失败时后端返回 INVITE_QRCODE_FAILED，前端降级为仅展示链接。
 */
export function fetchInviteQrCode() {
  return http.get('/invite/qrcode', { noLedger: true })
}

/**
 * 被邀请人列表：{ items, total, invitedCount }，对应后端 GET /api/invite/invitees。
 * 需登录态；page 从 0 开始，size 为每页条数。
 */
export function fetchInvitees(page = 0, size = 20) {
  return http.get(`/invite/invitees?page=${page}&size=${size}`, { noLedger: true })
}

/**
 * 邀请人展示信息：{ nickname }，对应后端 GET /api/invite/inviter?code=。
 * 落地页在未登录时也要展示邀请人，故 auth: false。
 */
export function fetchInviterBrief(code) {
  return http.get(`/invite/inviter?code=${encodeURIComponent(code)}`, {
    auth: false,
    noLedger: true
  })
}
