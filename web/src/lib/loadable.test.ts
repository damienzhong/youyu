import { describe, it, expect } from 'vitest'
import { useLoadable } from '@/lib/loadable'

/**
 * 加载失败处理契约（需求 11.4 / 11.5）：
 *  - 成功加载后呈现最新数据；
 *  - 失败时保留上一次已加载的数据、给出错误提示、可重试；
 *  - 重试成功后清除错误并刷新数据。
 * 各数据页（首页/流水/报表/账户/分类/记一笔）内联实现了同一套行为，此处对共享契约做回归保护。
 */
describe('useLoadable (需求 11.5 加载失败处理)', () => {
  it('成功加载后设置 data / loaded，且无错误', async () => {
    const { loading, loaded, error, data, load } = useLoadable(async () => ['a', 'b'])
    await load()
    expect(data.value).toEqual(['a', 'b'])
    expect(loaded.value).toBe(true)
    expect(error.value).toBe('')
    expect(loading.value).toBe(false)
  })

  it('加载失败时保留上一次已加载的数据，仅设置错误提示', async () => {
    let shouldFail = false
    const { loaded, error, data, load } = useLoadable(async () => {
      if (shouldFail) throw new Error('网络异常')
      return { value: 42 }
    })

    // 第一次成功，拿到数据。
    await load()
    expect(data.value).toEqual({ value: 42 })
    expect(loaded.value).toBe(true)

    // 第二次失败：data 不被清空（保留旧数据），loaded 仍为 true，error 被设置。
    shouldFail = true
    await load()
    expect(data.value).toEqual({ value: 42 }) // 关键：旧数据保留
    expect(loaded.value).toBe(true)
    expect(error.value).toBe('网络异常')
  })

  it('提供重试入口，重试成功后清除错误并刷新数据', async () => {
    let attempt = 0
    const { error, data, retry } = useLoadable(async () => {
      attempt += 1
      if (attempt === 1) throw new Error('第一次失败')
      return `ok-${attempt}`
    })

    await retry() // 第一次失败
    expect(error.value).toBe('第一次失败')
    expect(data.value).toBeNull()

    await retry() // 重试成功
    expect(error.value).toBe('')
    expect(data.value).toBe('ok-2')
  })

  it('抛出的非 Error（无 message）使用兜底提示', async () => {
    const { error, data, load } = useLoadable(async () => {
      throw 'boom' // 非 Error
    }, '加载失败，请重试')
    await load()
    expect(error.value).toBe('加载失败，请重试')
    expect(data.value).toBeNull()
  })

  it('首次失败（从未成功过）时 loaded 保持 false，data 为 null', async () => {
    const { loaded, error, data, load } = useLoadable(async () => {
      throw new Error('初次加载失败')
    })
    await load()
    expect(loaded.value).toBe(false)
    expect(data.value).toBeNull()
    expect(error.value).toBe('初次加载失败')
  })
})
