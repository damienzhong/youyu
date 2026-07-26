/**
 * HTTP 客户端与拦截器（axios）。
 *
 * 后端「有余」统一错误响应体（见 design.md「统一错误响应格式」）：
 *   { code: string, message: string, field?: string }
 * 成功响应直接返回业务数据（2xx，data 为响应体）。
 *
 * 拦截器职责：
 *  - 请求：注入 `Authorization: Bearer <token>`（令牌取自会话存储）。
 *  - 响应：把后端统一错误体归一化为 ApiError 抛出，供上层统一提示；
 *          401（未认证/令牌失效）时清除本地令牌并跳转登录页。
 */
import axios, { type AxiosError, type AxiosRequestConfig } from 'axios'

/** 令牌在 localStorage 的键名（内存 + localStorage 双持有，见 design 鉴权设计）。 */
export const TOKEN_KEY = 'youyu_token'

/** 后端统一错误体结构。 */
export interface ApiErrorBody {
  code: string
  message: string
  field?: string
}

/** 归一化后的业务错误，携带错误码与出错字段，便于逐字段提示。 */
export class ApiError extends Error {
  constructor(
    public readonly code: string,
    message: string,
    public readonly field?: string,
    public readonly status?: number,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

let inMemoryToken: string | null = null

/** 读取当前令牌：优先内存，回退 localStorage（秒开/刷新后恢复）。 */
export function getToken(): string | null {
  if (inMemoryToken) return inMemoryToken
  inMemoryToken = localStorage.getItem(TOKEN_KEY)
  return inMemoryToken
}

/** 写入/清除令牌，同步内存与 localStorage。 */
export function setToken(token: string | null): void {
  inMemoryToken = token
  if (token) localStorage.setItem(TOKEN_KEY, token)
  else localStorage.removeItem(TOKEN_KEY)
}

const http = axios.create({
  baseURL: '/api',
  timeout: 15_000,
})

// === 请求拦截：注入 Bearer 令牌 ===
http.interceptors.request.use((cfg) => {
  cfg.headers = cfg.headers ?? {}
  const token = getToken()
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

// === 响应拦截：成功解包 data；失败归一化错误、401 登出 ===
http.interceptors.response.use(
  (res) => res.data,
  (err: AxiosError<ApiErrorBody>) => {
    const status = err.response?.status
    const body = err.response?.data

    // 未认证/令牌失效：清令牌并跳登录（当前不在登录页时）。
    if (status === 401) {
      setToken(null)
      const path = typeof window !== 'undefined' ? window.location.pathname : '/'
      if (!path.startsWith('/login') && !path.startsWith('/register')) {
        window.location.href = '/login'
      }
      return Promise.reject(
        new ApiError(body?.code ?? 'UNAUTHENTICATED', body?.message ?? '登录已失效，请重新登录', body?.field, 401),
      )
    }

    // 后端统一错误体。
    if (body && typeof body === 'object' && 'code' in body) {
      return Promise.reject(new ApiError(body.code, body.message ?? '请求失败', body.field, status))
    }

    // 网络/超时等未知错误。
    return Promise.reject(new ApiError('NETWORK_ERROR', err.message || '网络异常，请稍后重试', undefined, status))
  },
)

export type GET<T> = (cfg?: AxiosRequestConfig) => Promise<T>
export type POST<T, B = unknown> = (body?: B, cfg?: AxiosRequestConfig) => Promise<T>

export default http
