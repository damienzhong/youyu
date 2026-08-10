/**
 * Feature: recurring-transactions, 任务 9.3：周期规则新建 / 编辑页表单纯逻辑单测。
 *
 * 前端测试基建只跑 src/utils 下不依赖 uni API 的纯逻辑（见 vitest.config.js），
 * 因此本文件覆盖 utils/recurring.js 为编辑页抽出的纯函数：
 *   - parseRuleAmount / normalizeWeeklyDays：金额与星期几集合规范化
 *   - validateRuleForm：提交前本地校验（类型 / 金额 / 分类 / 账户 / 备注 / 频率 / 结束条件）
 *   - buildRulePayload：表单态 → 后端 RecurringRuleRequest，按频率 / 结束条件只带对应子字段
 *   - mapRuleError：后端错误码 → 字段提示
 *   - ruleToForm：规则详情 → 编辑态表单初值
 * 页面选择器交互、AccountBadge 渲染、提交编排归手工验收。
 *
 * Validates: Requirements 1.1, 1.5, 1.6, 1.7, 1.8, 6.3
 */
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import {
  parseRuleAmount,
  normalizeWeeklyDays,
  validateRuleForm,
  buildRulePayload,
  mapRuleError,
  ruleToForm
} from './recurring'

// 合法基线表单：每月 5 日 · 支出，永不结束。
function baseForm(overrides = {}) {
  return {
    type: 'expense',
    amountText: '3000',
    categoryId: 10,
    accountId: 20,
    note: '',
    frequency: 'MONTHLY',
    weeklyDays: [],
    monthEnd: false,
    monthDay: 5,
    yearMonth: 1,
    yearDay: 1,
    startDate: '',
    endCondition: 'NEVER',
    untilDate: '',
    countN: '',
    ...overrides
  }
}

describe('parseRuleAmount：金额解析与范围（需求 1.3）', () => {
  it('合法金额（含端点）解析为数值', () => {
    expect(parseRuleAmount('0.01')).toBe(0.01)
    expect(parseRuleAmount('3000')).toBe(3000)
    expect(parseRuleAmount('999999999.99')).toBe(999999999.99)
    expect(parseRuleAmount('12.50')).toBe(12.5)
  })
  it('非法金额（空 / 越界 / 超两位小数 / 非数字）返回 null', () => {
    expect(parseRuleAmount('')).toBeNull()
    expect(parseRuleAmount('  ')).toBeNull()
    expect(parseRuleAmount('0')).toBeNull()
    expect(parseRuleAmount('0.001')).toBeNull()
    expect(parseRuleAmount('1000000000')).toBeNull()
    expect(parseRuleAmount('12.345')).toBeNull()
    expect(parseRuleAmount('abc')).toBeNull()
    expect(parseRuleAmount('-5')).toBeNull()
  })
})

describe('normalizeWeeklyDays：去重 / 过滤 / 升序', () => {
  it('去重、过滤 1–7 之外、升序', () => {
    expect(normalizeWeeklyDays([5, 1, 3, 1])).toEqual([1, 3, 5])
    expect(normalizeWeeklyDays([0, 8, 7, 2])).toEqual([2, 7])
    expect(normalizeWeeklyDays([])).toEqual([])
    expect(normalizeWeeklyDays(null)).toEqual([])
  })
})

describe('validateRuleForm：合法表单通过（需求 1.1）', () => {
  it('每月指定日基线表单通过', () => {
    expect(validateRuleForm(baseForm())).toEqual({ ok: true })
  })
  it('每天 / 每周 / 每月月末 / 每年 各频率合法均通过', () => {
    expect(validateRuleForm(baseForm({ frequency: 'DAILY' })).ok).toBe(true)
    expect(validateRuleForm(baseForm({ frequency: 'WEEKLY', weeklyDays: [1, 3] })).ok).toBe(true)
    expect(validateRuleForm(baseForm({ frequency: 'MONTHLY', monthEnd: true })).ok).toBe(true)
    expect(validateRuleForm(baseForm({ frequency: 'YEARLY', yearMonth: 2, yearDay: 29 })).ok).toBe(true)
  })
})

describe('validateRuleForm：模板字段非法（需求 1.2、1.4）', () => {
  it('类型非 expense/income → field=type', () => {
    expect(validateRuleForm(baseForm({ type: 'transfer' }))).toMatchObject({ ok: false, field: 'type' })
  })
  it('金额非法 → field=amount', () => {
    expect(validateRuleForm(baseForm({ amountText: '0' }))).toMatchObject({ ok: false, field: 'amount' })
    expect(validateRuleForm(baseForm({ amountText: '12.345' }))).toMatchObject({ ok: false, field: 'amount' })
  })
  it('缺分类 / 缺账户 → 对应 field', () => {
    expect(validateRuleForm(baseForm({ categoryId: null }))).toMatchObject({ ok: false, field: 'category' })
    expect(validateRuleForm(baseForm({ accountId: null }))).toMatchObject({ ok: false, field: 'account' })
  })
  it('备注超 200 → field=note', () => {
    expect(validateRuleForm(baseForm({ note: 'a'.repeat(201) }))).toMatchObject({ ok: false, field: 'note' })
  })
})

describe('validateRuleForm：频率配置非法（需求 1.8、2.10）', () => {
  it('WEEKLY 空集合 → field=frequency', () => {
    expect(validateRuleForm(baseForm({ frequency: 'WEEKLY', weeklyDays: [] }))).toMatchObject({ ok: false, field: 'frequency' })
  })
  it('MONTHLY 指定日越界 → field=frequency', () => {
    expect(validateRuleForm(baseForm({ frequency: 'MONTHLY', monthEnd: false, monthDay: 0 }))).toMatchObject({ ok: false, field: 'frequency' })
    expect(validateRuleForm(baseForm({ frequency: 'MONTHLY', monthEnd: false, monthDay: 32 }))).toMatchObject({ ok: false, field: 'frequency' })
  })
  it('YEARLY 月 / 日越界 → field=frequency', () => {
    expect(validateRuleForm(baseForm({ frequency: 'YEARLY', yearMonth: 13, yearDay: 1 }))).toMatchObject({ ok: false, field: 'frequency' })
    expect(validateRuleForm(baseForm({ frequency: 'YEARLY', yearMonth: 2, yearDay: 32 }))).toMatchObject({ ok: false, field: 'frequency' })
  })
})

describe('validateRuleForm：结束条件非法（需求 1.6、1.7）', () => {
  it('UNTIL_DATE 早于开始日期 → field=endCondition', () => {
    const f = baseForm({ startDate: '2025-06-01', endCondition: 'UNTIL_DATE', untilDate: '2025-05-01' })
    expect(validateRuleForm(f)).toMatchObject({ ok: false, field: 'endCondition' })
  })
  it('UNTIL_DATE 等于开始日期 → 通过', () => {
    const f = baseForm({ startDate: '2025-06-01', endCondition: 'UNTIL_DATE', untilDate: '2025-06-01' })
    expect(validateRuleForm(f).ok).toBe(true)
  })
  it('COUNT 越界 → field=endCondition，1 与 9999 端点通过', () => {
    expect(validateRuleForm(baseForm({ endCondition: 'COUNT', countN: 0 }))).toMatchObject({ ok: false, field: 'endCondition' })
    expect(validateRuleForm(baseForm({ endCondition: 'COUNT', countN: 10000 }))).toMatchObject({ ok: false, field: 'endCondition' })
    expect(validateRuleForm(baseForm({ endCondition: 'COUNT', countN: 1 })).ok).toBe(true)
    expect(validateRuleForm(baseForm({ endCondition: 'COUNT', countN: 9999 })).ok).toBe(true)
  })
})

describe('buildRulePayload：按频率 / 结束条件只带对应子字段', () => {
  it('MONTHLY 指定日：带 monthEnd=false + monthDay，不带 weekly/year 字段', () => {
    const p = buildRulePayload(baseForm({ monthDay: 5 }))
    expect(p).toMatchObject({
      type: 'expense', amount: '3000.00', categoryId: 10, accountId: 20,
      frequency: 'MONTHLY', monthEnd: false, monthDay: 5, endCondition: 'NEVER'
    })
    expect(p.weeklyDays).toBeUndefined()
    expect(p.yearMonth).toBeUndefined()
    expect(p.untilDate).toBeUndefined()
    expect(p.countN).toBeUndefined()
    expect(p.startDate).toBeUndefined()
    expect(p.note).toBeUndefined()
  })
  it('MONTHLY 月末：带 monthEnd=true，不带 monthDay', () => {
    const p = buildRulePayload(baseForm({ frequency: 'MONTHLY', monthEnd: true }))
    expect(p.monthEnd).toBe(true)
    expect(p.monthDay).toBeUndefined()
  })
  it('WEEKLY：带规范化 weeklyDays', () => {
    const p = buildRulePayload(baseForm({ frequency: 'WEEKLY', weeklyDays: [5, 1, 3, 1] }))
    expect(p.weeklyDays).toEqual([1, 3, 5])
    expect(p.monthDay).toBeUndefined()
  })
  it('YEARLY：带 yearMonth + yearDay', () => {
    const p = buildRulePayload(baseForm({ frequency: 'YEARLY', yearMonth: 2, yearDay: 29 }))
    expect(p).toMatchObject({ frequency: 'YEARLY', yearMonth: 2, yearDay: 29 })
  })
  it('金额规范化为两位小数字符串；备注去空白后带上', () => {
    const p = buildRulePayload(baseForm({ amountText: '12.5', note: '  房租  ' }))
    expect(p.amount).toBe('12.50')
    expect(p.note).toBe('房租')
  })
  it('UNTIL_DATE 带 untilDate；COUNT 带数值 countN；startDate 有值才带', () => {
    const p1 = buildRulePayload(baseForm({ startDate: '2025-06-01', endCondition: 'UNTIL_DATE', untilDate: '2025-12-31' }))
    expect(p1).toMatchObject({ startDate: '2025-06-01', endCondition: 'UNTIL_DATE', untilDate: '2025-12-31' })
    expect(p1.countN).toBeUndefined()
    const p2 = buildRulePayload(baseForm({ endCondition: 'COUNT', countN: '12' }))
    expect(p2.countN).toBe(12)
    expect(p2.untilDate).toBeUndefined()
  })
})

describe('mapRuleError：错误码 → 字段提示', () => {
  it('专有码与复用码映射到对应字段', () => {
    expect(mapRuleError({ code: 'AMOUNT_INVALID' }).field).toBe('amount')
    expect(mapRuleError({ code: 'NOTE_TOO_LONG' }).field).toBe('note')
    expect(mapRuleError({ code: 'RECURRING_FREQUENCY_INVALID' }).field).toBe('frequency')
    expect(mapRuleError({ code: 'RECURRING_END_CONDITION_INVALID' }).field).toBe('endCondition')
    expect(mapRuleError({ code: 'RECURRING_RULE_INVALID', field: 'category' }).field).toBe('category')
  })
  it('未知码回退 message、field=null', () => {
    expect(mapRuleError({ code: 'WHATEVER', message: '出错了' })).toEqual({ field: null, message: '出错了' })
    expect(mapRuleError(null).field).toBeNull()
  })
})

describe('ruleToForm：规则详情 → 编辑态表单', () => {
  it('回填并把 monthEnd / 日期切片正确展开', () => {
    const rule = {
      type: 'income', amount: '200.00', categoryId: 3, accountId: 7, note: '工资',
      frequency: 'WEEKLY', weeklyDays: [3, 1], monthEnd: false, monthDay: 5,
      yearMonth: 6, yearDay: 15, startDate: '2025-01-01', endCondition: 'COUNT', countN: 12
    }
    const f = ruleToForm(rule)
    expect(f).toMatchObject({
      type: 'income', amountText: '200.00', categoryId: 3, accountId: 7, note: '工资',
      frequency: 'WEEKLY', weeklyDays: [1, 3], monthEnd: false,
      startDate: '2025-01-01', endCondition: 'COUNT', countN: 12
    })
  })
  it('空规则给出合理缺省不抛异常', () => {
    const f = ruleToForm(null)
    expect(f.type).toBe('expense')
    expect(f.endCondition).toBe('NEVER')
  })
})

describe('属性：合法表单构造的 payload 恒可被本地校验接受且金额为两位小数字符串', () => {
  it('for all 合法金额 / 频率 / 结束条件，validate 通过且 payload 金额规范', () => {
    fc.assert(
      fc.property(
        fc.integer({ min: 1, max: 99999999 }), // 元整数，落在范围内
        fc.constantFrom('DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'),
        fc.constantFrom('NEVER', 'UNTIL_DATE', 'COUNT'),
        (yuan, frequency, endCondition) => {
          const f = baseForm({
            amountText: String(yuan),
            frequency,
            weeklyDays: frequency === 'WEEKLY' ? [1, 4] : [],
            monthEnd: false,
            monthDay: 15,
            yearMonth: 3,
            yearDay: 20,
            startDate: '2025-01-01',
            endCondition,
            untilDate: endCondition === 'UNTIL_DATE' ? '2025-12-31' : '',
            countN: endCondition === 'COUNT' ? 10 : ''
          })
          const check = validateRuleForm(f)
          expect(check.ok).toBe(true)
          const p = buildRulePayload(f)
          expect(p.amount).toBe(yuan.toFixed(2))
          expect(p.frequency).toBe(frequency)
          expect(p.endCondition).toBe(endCondition)
        }
      ),
      { numRuns: 100 }
    )
  })
})
