import { http } from '../utils/request'

/**
 * 用微信一次性 code 换取本系统令牌。
 * 对应后端 POST /api/auth/wx-login，返回 { token, tokenType, user }。
 * 该接口无需登录态，故 auth: false。
 */
export function wxLogin(code) {
  return http.post('/auth/wx-login', { code }, { auth: false })
}

/**
 * 账号密码登录，对应后端 POST /api/auth/login，返回 { token, tokenType, user }。
 * 主要用于无微信环境（浏览器/H5）下的联调与备用登录。
 */
export function passwordLogin(username, password) {
  return http.post('/auth/login', { username, password }, { auth: false })
}

/**
 * 账号密码注册，对应后端 POST /api/auth/register，返回用户摘要（不含 token）。
 * 注册成功后仍需调用登录换取 token。
 */
export function register(username, password) {
  return http.post('/auth/register', { username, password }, { auth: false })
}

/** 获取当前登录用户信息，对应后端 GET /api/me。 */
export function fetchMe() {
  return http.get('/me')
}
