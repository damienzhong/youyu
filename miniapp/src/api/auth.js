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
 * 邮箱验证码登录（登录/注册合一），对应后端 POST /api/auth/email-login，
 * 返回 { token, tokenType, user }。首次登录自动建号。
 * 该接口无需登录态，故 auth: false。
 */
export function emailLogin(email, code) {
  return http.post('/auth/email-login', { email, code }, { auth: false })
}

/**
 * 用微信一次性 code 换取本系统令牌。
 * 对应后端 POST /api/auth/wx-login，返回 { token, tokenType, user }。
 * 该接口无需登录态，故 auth: false。
 */
export function wxLogin(code) {
  return http.post('/auth/wx-login', { code }, { auth: false })
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

/** 获取当前登录用户信息，对应后端 GET /api/me。 */
export function fetchMe() {
  return http.get('/me')
}
