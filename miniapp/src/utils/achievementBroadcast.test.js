import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { STORAGE_KEYS } from './config'
import { TOAST_DURATION_MS, TOAST_GAP_MS } from './achievement'

/**
 * 播报编排状态机的单元测试（任务 9.7 / 需求 7.1~7.3、7.5、7.6、7.9~7.11、7.14~7.16）。
 *
 * 这里测的是**编排**而非纯算术：待播报请求的收发、幂等守卫、Toast 时序与游标推进，
 * 因此需要 uni 与 api 两个替身。`planBroadcast` / `ackCursorOf` 的输入空间由
 * achievement.planBroadcast-ackCursorOf.test.js（Property 8）覆盖，本文件不重复。
 *
 * 每个用例前 `vi.resetModules()` 重新 import 一次被测模块：`broadcasting` / `phase` 是
 * 模块级状态（需求 7.14 要求全局唯一守卫），不重置会让用例之间互相污染。
 */

const api = {
  fetchPendingAchievements: vi.fn(),
  ackAchievementNotices: vi.fn()
}

vi.mock('../api/achievement', () => ({
  fetchPendingAchievements: (...args) => api.fetchPendingAchievements(...args),
  ackAchievementNotices: (...args) => api.ackAchievementNotices(...args)
}))

let toasts = []
let navigations = []

function installUniMock(token = 'token-1') {
  toasts = []
  navigations = []
  globalThis.uni = {
    getStorageSync: (key) => (key === STORAGE_KEYS.token ? token : ''),
    showToast: (opts) => toasts.push(opts),
    navigateTo: (opts) => navigations.push(opts)
  }
  globalThis.getCurrentPages = () => [{ route: 'pages/growth/growth' }]
}

function item(id, name) {
  return { code: 'C' + id, name, description: 'd' + id, category: '起步', unlockedAt: '2025-06-01T12:00:00', eventId: id }
}

/** 载入一份全新的被测模块（模块级状态随之复位）。 */
async function loadModule() {
  vi.resetModules()
  return import('./achievementBroadcast')
}

describe('播报编排状态机', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    api.fetchPendingAchievements.mockReset()
    api.ackAchievementNotices.mockReset()
    api.ackAchievementNotices.mockResolvedValue({ lastNotifiedEventId: 0 })
    installUniMock()
  })

  afterEach(() => {
    vi.useRealTimers()
    delete globalThis.uni
    delete globalThis.getCurrentPages
  })

  it('未登录时不发起待播报请求、不展示弹层（需求 7.15）', async () => {
    installUniMock('')
    const b = await loadModule()

    expect(b.startAchievementBroadcast()).toBe(false)
    expect(api.fetchPendingAchievements).not.toHaveBeenCalled()
    expect(b.broadcastVisible.value).toBe(false)
    expect(b.isBroadcasting()).toBe(false)
  })

  it('播报进行中再次触发被直接丢弃，只发一次请求（需求 7.14）', async () => {
    api.fetchPendingAchievements.mockReturnValue(new Promise(() => {}))
    const b = await loadModule()

    expect(b.startAchievementBroadcast()).toBe(true)
    expect(b.startAchievementBroadcast()).toBe(false)
    expect(b.startAchievementBroadcast()).toBe(false)
    expect(api.fetchPendingAchievements).toHaveBeenCalledTimes(1)
  })

  it('待播报请求失败时静默放弃：不提示、不推进游标、守卫释放（需求 7.3）', async () => {
    api.fetchPendingAchievements.mockRejectedValue({ code: 'NETWORK_ERROR' })
    const b = await loadModule()

    b.startAchievementBroadcast()
    await vi.advanceTimersByTimeAsync(0)

    expect(b.broadcastVisible.value).toBe(false)
    expect(toasts).toHaveLength(0)
    expect(api.ackAchievementNotices).not.toHaveBeenCalled()
    expect(b.isBroadcasting()).toBe(false)
  })

  it('待播报请求 3000ms 无响应时静默放弃且不重试（需求 7.3）', async () => {
    api.fetchPendingAchievements.mockReturnValue(new Promise(() => {}))
    const b = await loadModule()

    b.startAchievementBroadcast()
    await vi.advanceTimersByTimeAsync(2999)
    expect(b.isBroadcasting()).toBe(true)

    await vi.advanceTimersByTimeAsync(1)
    expect(b.isBroadcasting()).toBe(false)
    expect(toasts).toHaveLength(0)
    expect(api.fetchPendingAchievements).toHaveBeenCalledTimes(1)
    expect(api.ackAchievementNotices).not.toHaveBeenCalled()
  })

  it('待播报列表为空时不展示弹层、不推进游标（需求 7.9）', async () => {
    api.fetchPendingAchievements.mockResolvedValue({ items: [], total: 0 })
    const b = await loadModule()

    b.startAchievementBroadcast()
    await vi.advanceTimersByTimeAsync(0)

    expect(b.broadcastVisible.value).toBe(false)
    expect(api.ackAchievementNotices).not.toHaveBeenCalled()
    expect(b.isBroadcasting()).toBe(false)
  })

  it('4 项待播报只播 3 项，游标取已展示项的最大事件 id（需求 7.5、7.6、7.9、7.11）', async () => {
    api.fetchPendingAchievements.mockResolvedValue({
      items: [item(11, '开张'), item(12, '坚持一周'), item(13, '记账十笔'), item(14, '旅行达人')],
      total: 4
    })
    const b = await loadModule()

    b.startAchievementBroadcast()
    await vi.advanceTimersByTimeAsync(0)
    // 第 1 项走弹层
    expect(b.broadcastVisible.value).toBe(true)
    expect(b.broadcastItem.value.eventId).toBe(11)
    expect(toasts).toHaveLength(0)

    // 弹层收起 → 第 2 项 Toast
    b.closeBroadcastModal()
    await vi.advanceTimersByTimeAsync(0)
    expect(toasts).toHaveLength(1)
    expect(toasts[0].duration).toBe(TOAST_DURATION_MS)
    expect(toasts[0].title).toContain('坚持一周')

    // 1500 + 300 后才播第 3 项
    await vi.advanceTimersByTimeAsync(TOAST_DURATION_MS + TOAST_GAP_MS - 1)
    expect(toasts).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(toasts).toHaveLength(2)
    expect(toasts[1].title).toContain('记账十笔')

    // 第 3 项播完 → 推进游标，取值为已展示的第 3 项（13），第 4 项留在待播报集合内
    await vi.advanceTimersByTimeAsync(TOAST_DURATION_MS + TOAST_GAP_MS)
    expect(toasts).toHaveLength(2)
    expect(api.ackAchievementNotices).toHaveBeenCalledTimes(1)
    expect(api.ackAchievementNotices).toHaveBeenCalledWith(13)
    expect(b.isBroadcasting()).toBe(false)
  })

  it('进入成就页：跳转、放弃未展示的 Toast、游标只取弹层那一项（需求 7.16）', async () => {
    api.fetchPendingAchievements.mockResolvedValue({
      items: [item(21, '开张'), item(22, '坚持一周'), item(23, '记账十笔')],
      total: 3
    })
    const b = await loadModule()

    b.startAchievementBroadcast()
    await vi.advanceTimersByTimeAsync(0)
    // 组件的 leave() 先抛 update:visible=false、同一 tick 再抛 enter
    b.closeBroadcastModal()
    b.enterAchievementPageFromBroadcast()
    await vi.advanceTimersByTimeAsync(5000)

    expect(navigations).toHaveLength(1)
    expect(navigations[0].url).toBe('/pages/achievement/achievement?code=C21')
    expect(toasts).toHaveLength(0)
    expect(api.ackAchievementNotices).toHaveBeenCalledWith(21)
    expect(b.broadcastVisible.value).toBe(false)
    expect(b.isBroadcasting()).toBe(false)
  })

  it('游标推进失败不重试、不提示、不抛出（需求 7.10）', async () => {
    api.fetchPendingAchievements.mockResolvedValue({ items: [item(31, '开张')], total: 1 })
    api.ackAchievementNotices.mockRejectedValue({ code: 'NETWORK_ERROR' })
    const b = await loadModule()

    b.startAchievementBroadcast()
    await vi.advanceTimersByTimeAsync(0)
    b.closeBroadcastModal()
    await vi.advanceTimersByTimeAsync(5000)

    expect(api.ackAchievementNotices).toHaveBeenCalledTimes(1)
    expect(toasts).toHaveLength(0)
    expect(b.isBroadcasting()).toBe(false)
  })

  it('弹层所在页面卸载：放弃本次播报、不推进游标、守卫释放（需求 7.11）', async () => {
    api.fetchPendingAchievements.mockResolvedValue({ items: [item(41, '开张'), item(42, '坚持一周')], total: 2 })
    const b = await loadModule()

    b.startAchievementBroadcast()
    await vi.advanceTimersByTimeAsync(0)
    expect(b.broadcastVisible.value).toBe(true)

    b.releaseAchievementBroadcastOnLeave()
    await vi.advanceTimersByTimeAsync(5000)

    expect(b.broadcastVisible.value).toBe(false)
    expect(toasts).toHaveLength(0)
    expect(api.ackAchievementNotices).not.toHaveBeenCalled()
    expect(b.isBroadcasting()).toBe(false)
    // 放弃之后可以再次触发
    expect(b.startAchievementBroadcast()).toBe(true)
  })

  it('响应回来时已不在挂载点页面：静默放弃且不推进游标（需求 7.11、7.12）', async () => {
    api.fetchPendingAchievements.mockResolvedValue({ items: [item(51, '开张')], total: 1 })
    const b = await loadModule()

    b.startAchievementBroadcast()
    // 记账成功后页面已返回首页（返回不等播报，需求 7.12）
    globalThis.getCurrentPages = () => [{ route: 'pages/index/index' }]
    await vi.advanceTimersByTimeAsync(0)

    expect(b.broadcastVisible.value).toBe(false)
    expect(api.ackAchievementNotices).not.toHaveBeenCalled()
    expect(b.isBroadcasting()).toBe(false)
  })
})
