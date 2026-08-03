// API 基地址：优先取构建期注入的 VITE_API_BASE，未配置时回退到本地后端。
// 小程序端需在微信公众平台配置 request 合法域名（须 HTTPS）。
export const API_BASE = import.meta.env.VITE_API_BASE || 'http://127.0.0.1:8090/api'

// 本地存储键，集中管理避免散落魔法字符串。
export const STORAGE_KEYS = {
  token: 'youyu_token',
  user: 'youyu_user',
  ledgerId: 'youyu_ledger_id',
  // 待绑定邀请码（未登录时从邀请链接暂存，登录/注册成功后清除）
  pendingInviteCode: 'youyu_pending_invite_code',
  // 待绑定邀请码的写入时刻，用于 7 天有效期判定
  pendingInviteCodeAt: 'youyu_pending_invite_code_at'
}
