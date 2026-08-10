import { describe, it, expect } from 'vitest'
/**
 * Feature: recurring-transactions, 任务 9.4：待确认项列表页纯逻辑单测。
 *
 * 前端测试基建只跑 src/utils 下不依赖 uni API 的纯逻辑（见 vitest.config.js），
 * 因此本文件覆盖 utils/recurring.js 为待确认项列表页抽出的纯函数：
 *   - pendingItemErrorLabel：错误码 → 中文提示
 *   - groupPendingItemsByDate：按到期日升序分组、组内保序
 *   - batchResultSummary / describeBatchFailures：批量结果摘要与逐条失败明细
 *   - buildConfirmOverrides：修改后确认仅收敛被改动字段
 */
import {
  pendingItemErrorLabel,
  groupPendingItemsByDate,
  batchResultSummary,
  describeBatchFailures,
  buildConfirmOverrides,
  pendingCountOf,
  pendingBadgeText
} from './recurring'

describe('pendingItemErrorLabel：错误码 → 中文提示', () => {
  it('映射任务约定的 5 个错误码', () => {
    expect(pendingItemErrorLabel('RECURRING_ITEM_ALREADY_PROCESSED')).toContain('已处理')
    expect(pendingItemErrorLabel('RECURRING_ITEM_TARGET_MISSING')).toContain('不存在')
    expect(pendingItemErrorLabel('NOT_FOUND')).toContain('无权访问')
    expect(pendingItemErrorLabel('AMOUNT_INVALID')).toContain('金额')
    expect(pendingItemErrorLabel('NOTE_TOO_LONG')).toContain('备注')
  })
  it('未识别码回退兜底或通用文案', () => {
    expect(pendingItemErrorLabel('WHATEVER', '自定义')).toBe('自定义')
    expect(pendingItemErrorLabel(undefined)).toBe('操作失败，请稍后重试')
  })
})

describe('groupPendingItemsByDate：按到期日升序分组', () => {
  it('按日期升序分组且组内保持传入顺序', () => {
    const items = [
      { id: 3, occurrenceDate: '2024-06-05' },
      { id: 4, occurrenceDate: '2024-06-05' },
      { id: 1, occurrenceDate: '2024-05-01' }
    ]
    const groups = groupPendingItemsByDate(items)
    expect(groups.map((g) => g.date)).toEqual(['2024-05-01', '2024-06-05'])
    expect(groups[1].items.map((i) => i.id)).toEqual([3, 4])
  })
  it('缺失到期日归入末组，空输入返回空数组', () => {
    expect(groupPendingItemsByDate(null)).toEqual([])
    const groups = groupPendingItemsByDate([
      { id: 1, occurrenceDate: null },
      { id: 2, occurrenceDate: '2024-01-01' }
    ])
    expect(groups[0].date).toBe('2024-01-01')
    expect(groups[groups.length - 1].date).toBe('')
  })
  it('截断到期日的时间部分为 YYYY-MM-DD', () => {
    const groups = groupPendingItemsByDate([{ id: 1, occurrenceDate: '2024-06-05T00:00:00' }])
    expect(groups[0].date).toBe('2024-06-05')
  })
})

describe('batchResultSummary / describeBatchFailures：批量结果', () => {
  it('全部成功 / 部分失败 / 全部失败摘要', () => {
    expect(batchResultSummary({ successCount: 3, failureCount: 0 })).toBe('全部 3 条已处理')
    expect(batchResultSummary({ successCount: 2, failureCount: 1 })).toBe('成功 2 · 失败 1')
    expect(batchResultSummary({ successCount: 0, failureCount: 3 })).toBe('3 条均未成功')
  })
  it('逐条失败明细带中文原因', () => {
    const detail = describeBatchFailures({
      failed: [
        { itemId: 7, errorCode: 'RECURRING_ITEM_ALREADY_PROCESSED' },
        { itemId: 8, errorCode: 'RECURRING_ITEM_TARGET_MISSING' }
      ]
    })
    expect(detail).toHaveLength(2)
    expect(detail[0]).toMatchObject({ itemId: 7, errorCode: 'RECURRING_ITEM_ALREADY_PROCESSED' })
    expect(detail[0].message).toContain('已处理')
    expect(detail[1].message).toContain('不存在')
  })
  it('无 failed 时返回空数组', () => {
    expect(describeBatchFailures({ successCount: 1 })).toEqual([])
    expect(describeBatchFailures(null)).toEqual([])
  })
})

describe('buildConfirmOverrides：修改后确认仅收敛被改动字段', () => {
  const item = {
    occurrenceDate: '2024-06-05',
    amount: '3000.00',
    categoryId: 10,
    accountId: 20,
    note: '房租'
  }

  it('未改动任何字段返回空对象', () => {
    const form = { amountText: '3000', categoryId: 10, accountId: 20, note: '房租', occurredDate: '2024-06-05' }
    expect(buildConfirmOverrides(form, item)).toEqual({})
  })

  it('改金额 → 2 位小数字符串', () => {
    const form = { amountText: '3500.5', categoryId: 10, accountId: 20, note: '房租', occurredDate: '2024-06-05' }
    expect(buildConfirmOverrides(form, item)).toEqual({ amount: '3500.50' })
  })

  it('改分类 / 账户 / 备注 / 记账日期', () => {
    const form = { amountText: '3000', categoryId: 11, accountId: 22, note: '六月房租', occurredDate: '2024-06-06' }
    expect(buildConfirmOverrides(form, item)).toEqual({
      categoryId: 11,
      accountId: 22,
      note: '六月房租',
      occurredAt: '2024-06-06T00:00:00'
    })
  })

  it('清空备注收敛为空串', () => {
    const form = { amountText: '3000', categoryId: 10, accountId: 20, note: '', occurredDate: '2024-06-05' }
    expect(buildConfirmOverrides(form, item)).toEqual({ note: '' })
  })

  it('金额文本非法则不带 amount', () => {
    const form = { amountText: 'abc', categoryId: 10, accountId: 20, note: '房租', occurredDate: '2024-06-05' }
    expect(buildConfirmOverrides(form, item)).toEqual({})
  })
})

describe('pendingCountOf：待确认列表 → 当前账本 PENDING 期数（任务 9.5）', () => {
  it('计入非空且带 id 的条目', () => {
    expect(pendingCountOf([{ id: 1 }, { id: 2 }, { id: 3 }])).toBe(3)
  })
  it('过滤 null / 无 id 的脏数据', () => {
    expect(pendingCountOf([{ id: 1 }, null, {}, { id: 2 }])).toBe(2)
  })
  it('空数组返回 0', () => {
    expect(pendingCountOf([])).toBe(0)
  })
  it('非数组安全降级为 0（隐藏角标）', () => {
    expect(pendingCountOf(null)).toBe(0)
    expect(pendingCountOf(undefined)).toBe(0)
    expect(pendingCountOf({})).toBe(0)
    expect(pendingCountOf('3')).toBe(0)
  })
})

describe('pendingBadgeText：角标展示文案（任务 9.5）', () => {
  it('0 或非正数返回空串（隐藏角标）', () => {
    expect(pendingBadgeText(0)).toBe('')
    expect(pendingBadgeText(-1)).toBe('')
  })
  it('1–99 显示数字串', () => {
    expect(pendingBadgeText(1)).toBe('1')
    expect(pendingBadgeText(99)).toBe('99')
  })
  it('超过 99 显示 99+', () => {
    expect(pendingBadgeText(100)).toBe('99+')
    expect(pendingBadgeText(1000)).toBe('99+')
  })
  it('非数字安全降级为空串', () => {
    expect(pendingBadgeText(undefined)).toBe('')
    expect(pendingBadgeText(NaN)).toBe('')
    expect(pendingBadgeText('abc')).toBe('')
  })
})
