// API 基地址：优先取构建期注入的 VITE_API_BASE，未配置时回退到本地后端。
// 小程序端需在微信公众平台配置 request 合法域名（须 HTTPS）。
export const API_BASE = import.meta.env.VITE_API_BASE || 'http://127.0.0.1:8090/api'

// 记账提醒的微信一次性订阅消息模板 id：供 wx.requestSubscribeMessage 授权用。
// 与后端 app.wechat.subscribe.reminder-template-id 对应的同一模板；构建期由 VITE_WX_REMINDER_TMPL_ID 注入。
export const WX_REMINDER_TEMPLATE_ID = import.meta.env.VITE_WX_REMINDER_TMPL_ID || ''

// 本地存储键，集中管理避免散落魔法字符串。
export const STORAGE_KEYS = {
  token: 'youyu_token',
  user: 'youyu_user',
  ledgerId: 'youyu_ledger_id',
  // 待绑定邀请码（未登录时从邀请链接暂存，登录/注册成功后清除）
  pendingInviteCode: 'youyu_pending_invite_code',
  // 待绑定邀请码的写入时刻，用于 7 天有效期判定
  pendingInviteCodeAt: 'youyu_pending_invite_code_at',
  // 待高亮成就编码（未登录时从成就分享卡片暂存，登录后带进成就页并清除）
  pendingAchievementCode: 'youyu_pending_achievement_code',
  // 主动退出登录标记：置位后小程序端不再自动静默登录（避免退出后被立刻登回）；任一次成功登录时清除。
  signedOut: 'youyu_signed_out',
  // 用户选择的主题 id（utils/theme.js 的 THEMES.id），缺省为默认主题。
  themeId: 'youyu_theme_id',
  // 是否已看过首次欢迎页并同意协议：置位后冷启动直达登录，不再展示欢迎页。
  welcomed: 'youyu_welcomed'
}
