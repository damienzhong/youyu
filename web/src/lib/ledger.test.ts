import { describe, it, expect } from 'vitest'
import { ApiError } from '@/lib/http'
import { validateAmount, toEntryErrorMessage, AMOUNT_MIN, AMOUNT_MAX } from '@/lib/ledger'

describe('validateAmount', () => {
  it('accepts amounts within range with up to two decimals', () => {
    expect(validateAmount('0.01')).toBeNull()
    expect(validateAmount('23.50')).toBeNull()
    expect(validateAmount('100')).toBeNull()
    expect(validateAmount('999999999.99')).toBeNull()
  })

  it('rejects empty or lone-dot input as required', () => {
    expect(validateAmount('')).toBeTruthy()
    expect(validateAmount('   ')).toBeTruthy()
    expect(validateAmount('.')).toBeTruthy()
  })

  it('rejects more than two decimal places', () => {
    expect(validateAmount('1.234')).toBeTruthy()
  })

  it('rejects malformed numbers', () => {
    expect(validateAmount('1.2.3')).toBeTruthy()
    expect(validateAmount('abc')).toBeTruthy()
    expect(validateAmount('-5')).toBeTruthy()
  })

  it('enforces the lower bound 0.01', () => {
    expect(validateAmount('0')).toBeTruthy()
    expect(validateAmount('0.00')).toBeTruthy()
    expect(validateAmount(String(AMOUNT_MIN))).toBeNull()
  })

  it('enforces the upper bound 999,999,999.99', () => {
    expect(validateAmount('1000000000')).toBeTruthy()
    expect(validateAmount(String(AMOUNT_MAX))).toBeNull()
  })
})

describe('toEntryErrorMessage', () => {
  it('maps known backend error codes to specific hints', () => {
    expect(toEntryErrorMessage(new ApiError('AMOUNT_INVALID', 'x', 'amount', 400))).toContain('金额')
    expect(toEntryErrorMessage(new ApiError('TRANSFER_SAME_ACCOUNT', 'x', undefined, 400))).toContain('账户')
    expect(toEntryErrorMessage(new ApiError('FIELD_REQUIRED', 'x', undefined, 400))).toContain('必填')
    expect(toEntryErrorMessage(new ApiError('NOT_FOUND', 'x', undefined, 404))).toBeTruthy()
    expect(toEntryErrorMessage(new ApiError('NETWORK_ERROR', 'x', undefined))).toContain('网络')
  })

  it('falls back to a generic message for non-ApiError values', () => {
    expect(toEntryErrorMessage(new Error('boom'))).toBeTruthy()
  })
})

import {
  formatAmount,
  sumBalances,
  accountNameOf,
  categoryNameOf,
  dayKeyOf,
  timeLabelOf,
  currentMonth,
  type Account,
  type Category,
} from '@/lib/ledger'

const acc = (id: number, name: string, currentBalance: string): Account => ({
  id,
  name,
  type: 'CASH',
  currentBalance,
  sortOrder: 0,
})

describe('formatAmount', () => {
  it('formats with two decimals and thousands separators', () => {
    expect(formatAmount('1234.5')).toBe('1,234.50')
    expect(formatAmount('0')).toBe('0.00')
    expect(formatAmount(1000000)).toBe('1,000,000.00')
  })

  it('preserves negative values (e.g. credit card debt / negative net assets)', () => {
    expect(formatAmount('-50')).toBe('-50.00')
  })

  it('falls back to 0.00 for non-numeric input', () => {
    expect(formatAmount('abc')).toBe('0.00')
  })
})

describe('sumBalances', () => {
  it('sums account balances without floating point drift', () => {
    expect(sumBalances([acc(1, 'a', '0.10'), acc(2, 'b', '0.20')])).toBe('0.30')
    expect(sumBalances([acc(1, 'a', '100.00'), acc(2, 'b', '-30.50')])).toBe('69.50')
  })

  it('returns 0.00 for empty account list', () => {
    expect(sumBalances([])).toBe('0.00')
  })
})

describe('accountNameOf / categoryNameOf', () => {
  const accounts = [acc(1, '现金', '0'), acc(2, '工资卡', '0')]
  const categories: Category[] = [
    { id: 10, kind: 'EXPENSE', name: '餐饮', parentId: null },
    { id: 11, kind: 'EXPENSE', name: '外卖', parentId: 10 },
    { id: 20, kind: 'INCOME', name: '工资', parentId: null },
  ]

  it('resolves account names, with placeholders for null/unknown', () => {
    expect(accountNameOf(accounts, 1)).toBe('现金')
    expect(accountNameOf(accounts, null)).toBe('—')
    expect(accountNameOf(accounts, 999)).toBe('未知账户')
  })

  it('resolves category names as parent · child when nested', () => {
    expect(categoryNameOf(categories, 11)).toBe('餐饮 · 外卖')
    expect(categoryNameOf(categories, 10)).toBe('餐饮')
    expect(categoryNameOf(categories, 20)).toBe('工资')
    expect(categoryNameOf(categories, null)).toBe('')
  })
})

describe('dayKeyOf / timeLabelOf', () => {
  it('extracts local day key and HH:mm label', () => {
    expect(dayKeyOf('2025-06-01T12:30:00+08:00')).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    expect(timeLabelOf('2025-06-01T12:30:00+08:00')).toMatch(/^\d{2}:\d{2}$/)
  })

  it('degrades gracefully on malformed input', () => {
    expect(dayKeyOf('bogus')).toBe('bogus')
    expect(timeLabelOf('bogus')).toBe('')
  })
})

describe('currentMonth', () => {
  it('returns a YYYY-MM string', () => {
    expect(currentMonth()).toMatch(/^\d{4}-\d{2}$/)
  })
})

import { shiftMonth, monthRange, monthLabel, shortMonthLabel } from '@/lib/ledger'

describe('shiftMonth', () => {
  it('shifts within the same year', () => {
    expect(shiftMonth('2025-06', -1)).toBe('2025-05')
    expect(shiftMonth('2025-06', 2)).toBe('2025-08')
  })

  it('crosses year boundaries in both directions', () => {
    expect(shiftMonth('2025-01', -1)).toBe('2024-12')
    expect(shiftMonth('2025-12', 1)).toBe('2026-01')
    expect(shiftMonth('2025-03', -11)).toBe('2024-04') // 近 12 个月区间起点
  })

  it('returns input unchanged for malformed month', () => {
    expect(shiftMonth('bogus', 1)).toBe('bogus')
  })
})

describe('monthRange', () => {
  it('produces first and last day of a 31-day month', () => {
    expect(monthRange('2025-01')).toEqual({ from: '2025-01-01', to: '2025-01-31' })
  })

  it('handles 30-day months and February (non-leap / leap)', () => {
    expect(monthRange('2025-04')).toEqual({ from: '2025-04-01', to: '2025-04-30' })
    expect(monthRange('2025-02')).toEqual({ from: '2025-02-01', to: '2025-02-28' })
    expect(monthRange('2024-02')).toEqual({ from: '2024-02-01', to: '2024-02-29' })
  })
})

describe('monthLabel / shortMonthLabel', () => {
  it('formats human-readable labels', () => {
    expect(monthLabel('2025-06')).toBe('2025年6月')
    expect(shortMonthLabel('2025-06')).toBe('6月')
    expect(shortMonthLabel('2025-12')).toBe('12月')
  })
})

import {
  validateAccountName,
  validateInitialBalance,
  validateCategoryName,
  toAccountErrorMessage,
  toCategoryErrorMessage,
  BALANCE_MAX,
  BALANCE_MIN,
} from '@/lib/ledger'

describe('validateAccountName / validateCategoryName', () => {
  it('accepts 1–50 chars after trimming', () => {
    expect(validateAccountName('现金')).toBeNull()
    expect(validateAccountName('  招商银行  ')).toBeNull()
    expect(validateCategoryName('餐饮')).toBeNull()
    expect(validateAccountName('a'.repeat(50))).toBeNull()
  })

  it('rejects empty (after trim) and over-50-char names', () => {
    expect(validateAccountName('')).toBeTruthy()
    expect(validateAccountName('   ')).toBeTruthy()
    expect(validateAccountName('a'.repeat(51))).toBeTruthy()
    expect(validateCategoryName('')).toBeTruthy()
    expect(validateCategoryName('a'.repeat(51))).toBeTruthy()
  })
})

describe('validateInitialBalance', () => {
  it('accepts values within range with up to two decimals, including negatives (credit card)', () => {
    expect(validateInitialBalance('0')).toBeNull()
    expect(validateInitialBalance('0.00')).toBeNull()
    expect(validateInitialBalance('100.50')).toBeNull()
    expect(validateInitialBalance('-2000.00')).toBeNull()
  })

  it('rejects empty, lone sign/dot, and more than two decimals', () => {
    expect(validateInitialBalance('')).toBeTruthy()
    expect(validateInitialBalance('-')).toBeTruthy()
    expect(validateInitialBalance('.')).toBeTruthy()
    expect(validateInitialBalance('1.234')).toBeTruthy()
    expect(validateInitialBalance('abc')).toBeTruthy()
  })

  it('enforces the DECIMAL(18,2) bounds', () => {
    expect(validateInitialBalance(String(BALANCE_MAX))).toBeNull()
    expect(validateInitialBalance(String(BALANCE_MIN))).toBeNull()
  })
})

describe('toAccountErrorMessage', () => {
  it('maps account error codes to friendly hints', () => {
    expect(toAccountErrorMessage(new ApiError('ACCOUNT_IN_USE', 'x', undefined, 409))).toContain('交易记录')
    expect(toAccountErrorMessage(new ApiError('ACCOUNT_FIELD_INVALID', '账户信息不合法', 'name', 400))).toBeTruthy()
    expect(toAccountErrorMessage(new ApiError('NOT_FOUND', 'x', undefined, 404))).toContain('账户')
    expect(toAccountErrorMessage(new Error('boom'))).toBeTruthy()
  })
})

describe('toCategoryErrorMessage', () => {
  it('maps category error codes to friendly hints', () => {
    expect(toCategoryErrorMessage(new ApiError('CATEGORY_DEPTH_EXCEEDED', 'x', undefined, 400))).toContain('两级')
    expect(toCategoryErrorMessage(new ApiError('CATEGORY_NAME_DUPLICATE', 'x', undefined, 409))).toContain('同名')
    expect(toCategoryErrorMessage(new ApiError('CATEGORY_IN_USE', 'x', undefined, 409))).toContain('删除')
    expect(toCategoryErrorMessage(new ApiError('CATEGORY_NAME_INVALID', 'x', undefined, 400))).toBeTruthy()
    expect(toCategoryErrorMessage(new Error('boom'))).toBeTruthy()
  })
})
