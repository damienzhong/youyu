/**
 * Feature: custom-reminder, 任务 11.3：提醒设置页纯逻辑（本地校验 + 额度 + 超时常量）单测。
 *
 * 前端测试基建只跑 src/utils 下不依赖 uni API 的纯逻辑（见 vitest.config.js），
 * 因此本文件覆盖 utils/reminder.js 的可测纯函数与常量，对应任务 11.3 列出的可自动化行为：
 *   - 本地校验拦截非法提交（validateReminderForm / isValidTime / isValidFrequency，需求 10.3、10.4）
 *   - 额度 0 提示的取值兜底（normalizeQuota，需求 10.7）
 *   - 3000ms 超时不重试的时长常量（REMINDER_TIMEOUT_MS，需求 10.9）
 * 页面渲染、未登录不发请求、授权拒绝不上报等 uni.* 交互按 vitest.config.js 约定归手工验收清单。
 *
 * Validates: Requirements 10.3, 10.5, 10.7, 10.9
 */
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import {
  REMINDER_TIMEOUT_MS,
  REMINDER_MAX,
  QUOTA_MAX,
  FREQUENCY_OPTIONS,
  frequencyLabel,
  isValidFrequency,
  isValidTime,
  validateReminderForm,
  normalizeQuota
} from './reminder'

describe('常量：超时与上限', () => {
  it('单请求超时恒为 3000ms（需求 10.9：3000ms 无响应即失败，不自动重试）', () => {
    expect(REMINDER_TIMEOUT_MS).toBe(3000)
  })

  it('提醒条数上限 10、额度累积上限 50（对齐后端需求 1.6 / 5.3）', () => {
    expect(REMINDER_MAX).toBe(10)
    expect(QUOTA_MAX).toBe(50)
  })

  it('频率三选一：值与后端枚举一一对应，区分大小写', () => {
    expect(FREQUENCY_OPTIONS.map((o) => o.value)).toEqual(['DAILY', 'WEEKDAY', 'WEEKEND'])
  })
})

describe('isValidFrequency：频率合法性（区分大小写）', () => {
  it('接受三个合法枚举值', () => {
    expect(isValidFrequency('DAILY')).toBe(true)
    expect(isValidFrequency('WEEKDAY')).toBe(true)
    expect(isValidFrequency('WEEKEND')).toBe(true)
  })

  it('拒绝小写/未知/空/缺失（区分大小写）', () => {
    for (const bad of ['daily', 'weekday', 'weekend', 'Daily', 'MONTHLY', '', ' DAILY', null, undefined, 0]) {
      expect(isValidFrequency(bad)).toBe(false)
    }
  })
})

describe('isValidTime：HH:mm 本地校验（需求 10.3）', () => {
  it('接受零填充的合法时刻（含边界 00:00 / 23:59）', () => {
    for (const ok of ['00:00', '23:59', '09:05', '08:00', '19:30', '12:59', '00:59', '23:00']) {
      expect(isValidTime(ok)).toBe(true)
    }
  })

  it('拒绝越界/非零填充/含秒/空/缺失', () => {
    for (const bad of [
      '24:00', // 小时越界
      '23:60', // 分钟越界
      '08:60', // 分钟越界
      '8:00', // 未零填充
      '08:5', // 分钟未两位
      '8:5', // 均未两位
      '08:00:00', // 含秒
      '0800', // 无冒号
      '', // 空
      'ab:cd', // 非数字
      null,
      undefined
    ]) {
      expect(isValidTime(bad)).toBe(false)
    }
  })
})

describe('validateReminderForm：提交前整体校验与错误字段归属（需求 10.3、10.4）', () => {
  it('频率与时间均合法 → ok', () => {
    expect(validateReminderForm({ frequency: 'DAILY', remindTime: '21:00' })).toEqual({
      ok: true,
      field: null
    })
  })

  it('频率非法优先返回 frequency（即便时间也非法）', () => {
    expect(validateReminderForm({ frequency: 'daily', remindTime: '24:99' })).toEqual({
      ok: false,
      field: 'frequency'
    })
  })

  it('频率合法但时间非法 → remindTime', () => {
    expect(validateReminderForm({ frequency: 'WEEKDAY', remindTime: '8:00' })).toEqual({
      ok: false,
      field: 'remindTime'
    })
  })

  it('频率未选（空）→ frequency', () => {
    expect(validateReminderForm({ frequency: '', remindTime: '09:00' })).toEqual({
      ok: false,
      field: 'frequency'
    })
  })
})

describe('normalizeQuota：剩余订阅次数兜底（需求 10.7）', () => {
  it('非数字 / NaN / 负数 / 0 折成 0（额度 0 触发再授权提示）', () => {
    for (const zero of [null, undefined, NaN, -1, -100, 0, 'x', {}, '']) {
      expect(normalizeQuota(zero)).toBe(0)
    }
  })

  it('正常整数原样返回，小数向下取整', () => {
    expect(normalizeQuota(1)).toBe(1)
    expect(normalizeQuota(7)).toBe(7)
    expect(normalizeQuota(3.9)).toBe(3)
    expect(normalizeQuota('5')).toBe(5)
  })

  it('超过上限夹到 50', () => {
    expect(normalizeQuota(50)).toBe(50)
    expect(normalizeQuota(51)).toBe(50)
    expect(normalizeQuota(9999)).toBe(50)
  })
})

describe('frequencyLabel：枚举 → 中文标签', () => {
  it('三个合法值映射到中文', () => {
    expect(frequencyLabel('DAILY')).toBe('每天')
    expect(frequencyLabel('WEEKDAY')).toBe('工作日')
    expect(frequencyLabel('WEEKEND')).toBe('周末')
  })

  it('未知值回退原值、空值回退空串（不渲染 undefined）', () => {
    expect(frequencyLabel('MONTHLY')).toBe('MONTHLY')
    expect(frequencyLabel(null)).toBe('')
    expect(frequencyLabel(undefined)).toBe('')
  })
})

// —— 属性测试：跨大量输入验证纯函数的不变式 ——

/** 由时/分组装零填充 HH:mm。 */
function hhmm(h, m) {
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
}

describe('属性：isValidTime 与合法时刻空间一致', () => {
  it('全部合法 (h∈0..23, m∈0..59) 的零填充 HH:mm 恒被接受', () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 23 }), fc.integer({ min: 0, max: 59 }), (h, m) => {
        expect(isValidTime(hhmm(h, m))).toBe(true)
      }),
      { numRuns: 500 }
    )
  })

  it('小时越界 (>=24) 恒被拒', () => {
    fc.assert(
      fc.property(fc.integer({ min: 24, max: 99 }), fc.integer({ min: 0, max: 59 }), (h, m) => {
        expect(isValidTime(hhmm(h, m))).toBe(false)
      }),
      { numRuns: 300 }
    )
  })

  it('分钟越界 (>=60) 恒被拒', () => {
    fc.assert(
      fc.property(fc.integer({ min: 0, max: 23 }), fc.integer({ min: 60, max: 99 }), (h, m) => {
        expect(isValidTime(hhmm(h, m))).toBe(false)
      }),
      { numRuns: 300 }
    )
  })
})

describe('属性：validateReminderForm 只在频率+时间均合法时放行', () => {
  const freqSpace = fc.oneof(
    fc.constantFrom('DAILY', 'WEEKDAY', 'WEEKEND'),
    fc.constantFrom('daily', 'MONTHLY', '', 'DAILY ')
  )
  const timeSpace = fc.oneof(
    fc.tuple(fc.integer({ min: 0, max: 23 }), fc.integer({ min: 0, max: 59 })).map(([h, m]) => hhmm(h, m)),
    fc.constantFrom('24:00', '08:60', '8:00', '', '0800', 'ab:cd')
  )

  it('ok ⟺ (频率合法 ∧ 时间合法)，且失败时 field 指向首个不合法项', () => {
    fc.assert(
      fc.property(freqSpace, timeSpace, (frequency, remindTime) => {
        const res = validateReminderForm({ frequency, remindTime })
        const freqOk = isValidFrequency(frequency)
        const timeOk = isValidTime(remindTime)
        expect(res.ok).toBe(freqOk && timeOk)
        if (!freqOk) expect(res.field).toBe('frequency')
        else if (!timeOk) expect(res.field).toBe('remindTime')
        else expect(res.field).toBe(null)
      }),
      { numRuns: 600 }
    )
  })
})

describe('属性：normalizeQuota 恒落在 [0, QUOTA_MAX] 且为整数', () => {
  it('任意输入的输出恒 ∈ [0,50] 且为整数', () => {
    fc.assert(
      fc.property(
        fc.oneof(fc.integer(), fc.double(), fc.constantFrom(NaN, Infinity, -Infinity, null, undefined, 'x', '', {})),
        (n) => {
          const v = normalizeQuota(n)
          expect(Number.isInteger(v)).toBe(true)
          expect(v).toBeGreaterThanOrEqual(0)
          expect(v).toBeLessThanOrEqual(QUOTA_MAX)
        }
      ),
      { numRuns: 500 }
    )
  })
})
