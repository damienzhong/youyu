/**
 * 鉴权相关 API 与错误映射。
 *
 * 对接后端 Auth 模块（见 design.md「Auth 模块」）：
 *  - POST /auth/register {username, password} -> 201（无响应体或含用户摘要）
 *  - POST /auth/login    {username, password} -> { token, user }
 *  - GET  /me            -> 当前用户摘要
 *
 * 统一错误体 { code, message, field }，前端把错误码映射为友好中文提示，
 * 并尽量归位到具体表单字段（账号/口令），便于逐字段反馈（需求 1.1/1.5）。
 */
import http, { ApiError } from '@/lib/http'
import type { CurrentUser } from '@/stores/session'

export interface Credentials {
  username: string
  password: string
}

export interface LoginResult {
  token: string
  user: CurrentUser
}

/** 登录：成功返回令牌与用户摘要。 */
export function login(credentials: Credentials): Promise<LoginResult> {
  return http.post<unknown, LoginResult>('/auth/login', credentials)
}

/** 注册：部分后端会在注册后直接返回令牌以支持自动登录，故令牌可选。 */
export function register(credentials: Credentials): Promise<{ token?: string; user?: CurrentUser }> {
  return http.post<unknown, { token?: string; user?: CurrentUser }>('/auth/register', credentials)
}

/** 拉取当前登录用户摘要（用于刷新后恢复会话）。 */
export function fetchMe(): Promise<CurrentUser> {
  return http.get<unknown, CurrentUser>('/me')
}

/** 表单字段标识，用于把错误归位到具体输入框。 */
export type AuthField = 'username' | 'password' | 'form'

export interface AuthErrorFeedback {
  field: AuthField
  message: string
}

/**
 * 把后端错误码映射为友好中文提示与归属字段。
 * 后端也会返回 message，但前端集中映射可保证文案一致、并决定错误挂到哪个字段。
 */
export function toAuthFeedback(err: unknown): AuthErrorFeedback {
  if (!(err instanceof ApiError)) {
    return { field: 'form', message: '操作失败，请稍后重试' }
  }
  switch (err.code) {
    case 'USERNAME_TAKEN':
      return { field: 'username', message: '该账号已被占用，换一个试试' }
    case 'BAD_CREDENTIALS':
      return { field: 'form', message: '账号或密码错误' }
    case 'ACCOUNT_LOCKED':
      return { field: 'form', message: '登录失败次数过多，账号已被临时锁定，请 15 分钟后再试' }
    case 'PASSWORD_WEAK':
      return { field: 'password', message: '密码长度需为 8–64 个字符' }
    case 'FIELD_REQUIRED': {
      const field: AuthField = err.field === 'username' || err.field === 'password' ? err.field : 'form'
      const label = field === 'password' ? '密码' : field === 'username' ? '账号' : '必填项'
      return { field, message: `${label}不能为空` }
    }
    case 'UNAUTHENTICATED':
      return { field: 'form', message: '登录已失效，请重新登录' }
    case 'NETWORK_ERROR':
      return { field: 'form', message: '网络异常，请检查网络后重试' }
    default:
      return { field: 'form', message: err.message || '操作失败，请稍后重试' }
  }
}

/**
 * 前端表单校验（对齐需求 1.1/1.3）：
 *  - 账号：去首尾空白后长度 1–64
 *  - 口令：长度 8–64
 * 返回按字段归类的错误信息；无错误时返回空对象。
 */
export function validateCredentials(rawUsername: string, password: string): Partial<Record<AuthField, string>> {
  const errors: Partial<Record<AuthField, string>> = {}
  const username = rawUsername.trim()
  if (username.length < 1) {
    errors.username = '请输入账号'
  } else if (username.length > 64) {
    errors.username = '账号长度不能超过 64 个字符'
  }
  if (password.length < 8 || password.length > 64) {
    errors.password = '密码长度需为 8–64 个字符'
  }
  return errors
}
