/**
 * 留存轻触达的节流决策（retention-nudges）。
 *
 * 决策为纯函数（只接收「记忆状态 + 当前时间」返回布尔），供 vitest + fast-check 覆盖；
 * 记忆读写为 uni.getStorageSync/setStorageSync 的薄封装，异常吞掉返回默认，不抛不阻断页面。
 */
import { STORAGE_KEYS } from './config'

const DAY_MS = 24 * 60 * 60 * 1000

/** 添加入口引导两次自动展示的最短间隔天数。 */
export const ADD_GUIDE_MIN_INTERVAL_DAYS = 7

/** 高意愿授权入口在用户明确拒绝后的冷却天数。 */
export const GRANT_PROMPT_REJECT_COOLDOWN_DAYS = 7

/**
 * 是否展示添加入口引导（纯函数，需求 1.3、1.4）。
 * 已永久关闭 → false；从未展示过 → true；否则距上次展示达最短间隔才为 true。
 *
 * @param {{dismissed?: boolean, lastShownAt?: number}} state 本地记忆
 * @param {number} nowMs 当前时间戳(ms)
 */
export function shouldShowAddGuide(state, nowMs) {
  const s = state || {}
  if (s.dismissed === true) return false
  const last = Number(s.lastShownAt)
  if (!Number.isFinite(last) || last <= 0) return true
  return nowMs - last >= ADD_GUIDE_MIN_INTERVAL_DAYS * DAY_MS
}

/**
 * 某高意愿时刻是否展示订阅授权入口（纯函数，需求 2.6）。
 * 用户已关闭 → false；距上次明确拒绝未过冷却 → false；否则 → true。
 *
 * @param {{dismissed?: boolean, lastRejectAt?: number}} state 本地记忆
 * @param {number} nowMs 当前时间戳(ms)
 */
export function shouldShowGrantPrompt(state, nowMs) {
  const s = state || {}
  if (s.dismissed === true) return false
  const last = Number(s.lastRejectAt)
  if (!Number.isFinite(last) || last <= 0) return true
  return nowMs - last >= GRANT_PROMPT_REJECT_COOLDOWN_DAYS * DAY_MS
}

/**
 * 读取节流记忆（薄封装）：解析失败 / 无值一律返回 {}，不抛异常。
 * @param {string} key STORAGE_KEYS 中的键
 */
export function readNudgeState(key) {
  try {
    const raw = uni.getStorageSync(key)
    if (!raw) return {}
    if (typeof raw === 'object') return raw
    const parsed = JSON.parse(raw)
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch (e) {
    return {}
  }
}

/**
 * 合并写入节流记忆（薄封装）：读旧值 → 浅合并 patch → 写回；异常吞掉、返回合并后的值。
 * @param {string} key STORAGE_KEYS 中的键
 * @param {object} patch 要合并的字段
 */
export function writeNudgeState(key, patch) {
  const next = { ...readNudgeState(key), ...(patch || {}) }
  try {
    uni.setStorageSync(key, next)
  } catch (e) {
    // 存储失败静默降级：仍返回合并值供本次调用使用（至多按默认节流处理）。
  }
  return next
}

/** 便捷键常量（避免调用方散落魔法字符串）。 */
export const ADD_GUIDE_KEY = STORAGE_KEYS.addGuideState
export const GRANT_PROMPT_KEY = STORAGE_KEYS.grantPromptState
