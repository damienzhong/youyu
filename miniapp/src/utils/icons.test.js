/**
 * Feature: category-icons，任务 10：`miniapp/src/utils/icons.js` 单元测试。
 *
 * 覆盖：内置图标库规模、场景分组 key 的合法性与唯一性、hex 颜色校验、
 * 图标归一化回退、调色板与默认色常量。
 *
 * 注：`ICON_PATHS` 未从模块导出，故「分组 key 均存在于 ICON_PATHS」改为断言
 * 每个分组 key 均落在导出的 `ICON_KEY_SET`（即 `Object.keys(ICON_PATHS)`）内。
 *
 * Validates: Requirements 1.1, 1.3, 1.4
 */
import { describe, it, expect } from 'vitest'
import {
  ICON_KEY_SET,
  ICON_GROUPS,
  ICON_COLORS,
  DEFAULT_ICON_COLOR,
  isHexColor,
  resolveIcon
} from './icons'

describe('icons.js / 内置图标库规模', () => {
  it('ICON_KEY_SET 至少收录 120 枚图标 key', () => {
    expect(ICON_KEY_SET instanceof Set).toBe(true)
    expect(ICON_KEY_SET.size).toBeGreaterThanOrEqual(120)
  })
})

describe('icons.js / ICON_GROUPS 分组 key', () => {
  it('每个分组的每个 key 都存在于 ICON_KEY_SET（无悬空 key）', () => {
    for (const group of ICON_GROUPS) {
      expect(Array.isArray(group.keys)).toBe(true)
      expect(typeof group.label).toBe('string')
      for (const key of group.keys) {
        expect(ICON_KEY_SET.has(key)).toBe(true)
      }
    }
  })

  it('分组内与跨组均无重复 key', () => {
    const seen = new Set()
    for (const group of ICON_GROUPS) {
      for (const key of group.keys) {
        expect(seen.has(key)).toBe(false)
        seen.add(key)
      }
    }
  })
})

describe('icons.js / isHexColor', () => {
  it('合法 #RRGGBB 返回 true', () => {
    expect(isHexColor('#12a150')).toBe(true)
    expect(isHexColor('#FFFFFF')).toBe(true)
    expect(isHexColor('#000000')).toBe(true)
  })

  it('非法输入返回 false', () => {
    expect(isHexColor('#12a15')).toBe(false) // 少一位
    expect(isHexColor('12a150')).toBe(false) // 缺 #
    expect(isHexColor('#12a15g')).toBe(false) // 非 hex 字符
    expect(isHexColor('')).toBe(false)
    expect(isHexColor(null)).toBe(false)
    expect(isHexColor('#1234567')).toBe(false) // 多一位
  })
})

describe('icons.js / resolveIcon', () => {
  it('已知 icon key 原样返回', () => {
    expect(resolveIcon('food', '随便', 'expense')).toBe('food')
    expect(resolveIcon('salary', '随便', 'income')).toBe('salary')
  })

  it('未知 key 且名称无法命中时，按种类回退（支出→receipt，收入→income）', () => {
    expect(resolveIcon('zzz', 'qwerty', 'expense')).toBe('receipt')
    expect(resolveIcon('zzz', 'qwerty', 'income')).toBe('income')
  })
})

describe('icons.js / 颜色常量', () => {
  it('ICON_COLORS 至少 8 色', () => {
    expect(Array.isArray(ICON_COLORS)).toBe(true)
    expect(ICON_COLORS.length).toBeGreaterThanOrEqual(8)
  })

  it('DEFAULT_ICON_COLOR 为品牌绿 #12a150', () => {
    expect(DEFAULT_ICON_COLOR).toBe('#12a150')
  })
})
