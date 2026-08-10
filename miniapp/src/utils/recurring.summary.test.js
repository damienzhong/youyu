/**
 * Feature: recurring-transactions, 任务 9.2：周期规则列表页频率摘要纯逻辑单测。
 *
 * 前端测试基建只跑 src/utils 下不依赖 uni API 的纯逻辑（见 vitest.config.js），
 * 因此本文件覆盖 utils/recurring.js 的频率摘要渲染：
 *   - DAILY / WEEKLY（星期几集合）/ MONTHLY（指定日或月末）/ YEARLY（月+日）的中文频率标签
 *   - 收支方向标签、完整摘要（频率 · 方向 ¥金额）、结束条件与状态标签
 * 列表渲染、暂停 / 恢复 / 删除等 uni.* 交互归手工验收。
 *
 * Validates: Requirements 6.1, 6.2, 6.5
 */
import { describe, it, expect } from 'vitest'
import {
  weekdayLabel,
  frequencyLabel,
  directionLabel,
  ruleSummary,
  endConditionLabel,
  statusLabel
} from './recurring'

describe('frequencyLabel：各频率中文摘要', () => {
  it('DAILY → 每天', () => {
    expect(frequencyLabel({ frequency: 'DAILY' })).toBe('每天')
  })

  it('WEEKLY → 每周 + 升序去重的星期几集合（1=周一..7=周日）', () => {
    expect(frequencyLabel({ frequency: 'WEEKLY', weeklyDays: [5, 1, 3, 1] })).toBe('每周 周一、周三、周五')
    expect(frequencyLabel({ frequency: 'WEEKLY', weeklyDays: [7] })).toBe('每周 周日')
  })

  it('WEEKLY 集合为空 / 越界过滤 → 每周', () => {
    expect(frequencyLabel({ frequency: 'WEEKLY', weeklyDays: [] })).toBe('每周')
    expect(frequencyLabel({ frequency: 'WEEKLY', weeklyDays: [0, 8] })).toBe('每周')
  })

  it('MONTHLY 指定日 → 每月 D 日；月末标记 → 每月 月末', () => {
    expect(frequencyLabel({ frequency: 'MONTHLY', monthDay: 5, monthEnd: false })).toBe('每月 5 日')
    expect(frequencyLabel({ frequency: 'MONTHLY', monthDay: 31, monthEnd: true })).toBe('每月 月末')
  })

  it('YEARLY → 每年 M月D日', () => {
    expect(frequencyLabel({ frequency: 'YEARLY', yearMonth: 2, yearDay: 29 })).toBe('每年 2月29日')
  })

  it('缺失频率 / 未知频率 → 周期（不抛异常）', () => {
    expect(frequencyLabel(null)).toBe('周期')
    expect(frequencyLabel({})).toBe('周期')
    expect(frequencyLabel({ frequency: 'WHATEVER' })).toBe('周期')
  })
})

describe('weekdayLabel', () => {
  it('1..7 → 周一..周日，越界 → 空串', () => {
    expect(weekdayLabel(1)).toBe('周一')
    expect(weekdayLabel(7)).toBe('周日')
    expect(weekdayLabel(0)).toBe('')
    expect(weekdayLabel(8)).toBe('')
  })
})

describe('directionLabel', () => {
  it('income → 收入，其余（含 expense）→ 支出', () => {
    expect(directionLabel('income')).toBe('收入')
    expect(directionLabel('expense')).toBe('支出')
  })
})

describe('ruleSummary：频率 · 方向 ¥金额', () => {
  it('每月 5 日 · 支出 ¥3,000.00', () => {
    expect(ruleSummary({ frequency: 'MONTHLY', monthDay: 5, monthEnd: false, type: 'expense', amount: '3000' }))
      .toBe('每月 5 日 · 支出 ¥3,000.00')
  })

  it('每周星期几 · 收入 ¥金额', () => {
    expect(ruleSummary({ frequency: 'WEEKLY', weeklyDays: [1, 5], type: 'income', amount: 200 }))
      .toBe('每周 周一、周五 · 收入 ¥200.00')
  })
})

describe('endConditionLabel / statusLabel', () => {
  it('结束条件：NEVER / UNTIL_DATE / COUNT', () => {
    expect(endConditionLabel({ endCondition: 'NEVER' })).toBe('长期有效')
    expect(endConditionLabel({ endCondition: 'UNTIL_DATE', untilDate: '2025-12-31' })).toBe('至 2025-12-31')
    expect(endConditionLabel({ endCondition: 'COUNT', countN: 12 })).toBe('共 12 次')
  })

  it('状态：ACTIVE → 启用中，PAUSED → 已暂停', () => {
    expect(statusLabel('ACTIVE')).toBe('启用中')
    expect(statusLabel('PAUSED')).toBe('已暂停')
  })
})
