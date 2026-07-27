import { http } from '../utils/request'

/**
 * 用微信一次性 code 换取本系统令牌。
 * 对应后端 POST /api/auth/wx-login，返回 { token, tokenType, user }。
 * 该接口无需登录态，故 auth: false。
 */
export function wxLogin(code) {
  return http.post('/auth/wx-login', { code }, { auth: false })
}

/** 获取当前登录用户信息，对应后端 GET /api/me。 */
export function fetchMe() {
  return http.get('/me')
}
