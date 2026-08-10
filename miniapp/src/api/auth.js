import { http } from '../utils/request'

/**
 * 发送邮箱验证码，对应后端 POST /api/auth/send-code。
 * purpose ∈ 'LOGIN' | 'BIND' | 'DELETE'，缺省为 'LOGIN'。
 * 该接口无需登录态，故 auth: false。
 */
export function sendCode(email, purpose = 'LOGIN') {
  return http.post('/auth/send-code', { email, purpose }, { auth: false })
}

/**
 * 待绑定邀请码入参归一：去空白后为空（含 null / undefined）返回 null，
 * 由调用方据此决定「不携带 inviteCode 字段」。
 * 后端把「字段缺失 / null / 去空白为空」一律按 NO_CODE 处理，两种写法等价，
 * 这里选择直接省略字段，让请求体在无邀请码时与改造前逐字节相同。
 */
function pickInviteCode(inviteCode) {
  if (inviteCode === null || inviteCode === undefined) return null
  const trimmed = String(inviteCode).trim()
  return trimmed === '' ? null : trimmed
}

/**
 * 邮箱验证码登录（登录/注册合一），对应后端 POST /api/auth/email-login，
 * 返回 { token, tokenType, user, inviteBound, inviteUnboundReason }。首次登录自动建号。
 * inviteCode 为可选的待绑定邀请码，'' / 省略表示不携带。
 * 该接口无需登录态，故 auth: false。
 */
export function emailLogin(email, code, inviteCode) {
  const body = { email, code }
  const invite = pickInviteCode(inviteCode)
  if (invite) body.inviteCode = invite
  return http.post('/auth/email-login', body, { auth: false })
}

/**
 * 用微信一次性 code 换取本系统令牌。
 * 对应后端 POST /api/auth/wx-login，返回 { token, tokenType, user, inviteBound, inviteUnboundReason }。
 * inviteCode 为可选的待绑定邀请码，'' / 省略表示不携带。
 * 该接口无需登录态，故 auth: false。
 */
export function wxLogin(code, inviteCode) {
  const body = { code }
  const invite = pickInviteCode(inviteCode)
  if (invite) body.inviteCode = invite
  return http.post('/auth/wx-login', body, { auth: false })
}

/**
 * 绑定邮箱，对应后端 POST /api/me/bind-email，返回用户摘要。
 * 需登录态，需先通过 sendCode(email, 'BIND') 获取验证码。
 */
export function bindEmail(email, code) {
  return http.post('/me/bind-email', { email, code })
}

/**
 * 绑定微信，对应后端 POST /api/me/bind-wechat，返回用户摘要。
 * 需登录态，code 为微信一次性授权 code。
 */
export function bindWechat(code) {
  return http.post('/me/bind-wechat', { code })
}

/**
 * 解绑登录方式，对应后端 POST /api/me/unbind，返回用户摘要。
 * type ∈ 'email' | 'wechat'；需保留至少一种登录方式，否则后端拒绝。
 */
export function unbind(type) {
  return http.post('/me/unbind', { type })
}

/**
 * 注销账号，对应后端 POST /api/me/delete，成功返回 204（无响应体）。
 * 需二次验证：邮箱用户传 code（DELETE 验证码），微信用户传 wxCode（重新授权 code）。
 * @param {{ code?: string, wxCode?: string }} params
 */
export function deleteAccount({ code, wxCode } = {}) {
  return http.post('/me/delete', { code, wxCode })
}

/**
 * 修改昵称，对应后端 POST /api/me/nickname，返回用户摘要。
 * 需登录态；昵称去空白后长度需为 1-64，仅用于展示。
 */
export function updateNickname(nickname) {
  return http.post('/me/nickname', { nickname })
}

/**
 * 更新个性化资料（性别 / 头像颜色），对应后端 POST /api/me/profile，返回用户摘要。
 * 需登录态；payload 两字段均可选：省略=不改，空串=清空（性别→保密、头像色→默认）。
 * gender ∈ 'MALE' | 'FEMALE' | ''；avatarColor 为 '#RRGGBB'。
 */
export function updateProfile(payload) {
  return http.post('/me/profile', payload)
}

/** 获取当前登录用户信息，对应后端 GET /api/me。 */
export function fetchMe() {
  return http.get('/me')
}
