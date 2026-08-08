/**
 * Feature: category-icons，任务 13：`CategoryIcon` 回退纯逻辑测试（vitest）。
 *
 * 为避免在无 DOM 环境渲染 .vue 组件的复杂度，这里复刻 `CategoryIcon.vue` 的两行核心派生逻辑：
 *   - bg  = isHexColor(color) ? color : DEFAULT_ICON_COLOR
 *   - key = resolveIcon(icon, name, kind)
 * 并断言：对任意 (icon, color, kind) 组合，bg 始终是合法 hex、key 始终是合法内置 key，且过程不抛异常。
 *
 * Validates: Requirements 1.4, 4.2, 4.3
 */
import { describe, it, expect } from 'vitest'
import {
  resolveIcon,
  isHexColor,
  DEFAULT_ICON_COLOR,
  ICON_KEY_SET
} from './icons'

// 复刻组件派生逻辑（与 CategoryIcon.vue 的 resolvedKey / bg 一致）
function deriveBg(color) {
  return isHexColor(color) ? color : DEFAULT_ICON_COLOR
}
function deriveKey(icon, name, kind) {
  return resolveIcon(icon, name, kind)
}

describe('CategoryIcon 回退逻辑 / 组合遍历', () => {
  const icons = [null, '', 'zzz', 'coffee']
  const colors = [null, '', 'red', '#12a150', '#ABCDEF']
  const kinds = ['expense', 'income']
  const names = ['', '午餐', 'xxx']

  it('任意 (icon, color, kind, name) 组合 → 合法背景色 + 合法图标 key，不抛异常', () => {
    for (const icon of icons) {
      for (const color of colors) {
        for (const kind of kinds) {
          for (const name of names) {
            let bg
            let key
            expect(() => {
              bg = deriveBg(color)
              key = deriveKey(icon, name, kind)
            }).not.toThrow()

            // bg 始终是合法 hex（用 isHexColor 复验）
            expect(isHexColor(bg)).toBe(true)
            // key 始终落在内置 key 白名单内
            expect(ICON_KEY_SET.has(key)).toBe(true)
          }
        }
      }
    }
  })
})

describe('CategoryIcon 回退逻辑 / 明确用例', () => {
  it("color='#12a150' → bg 原样", () => {
    expect(deriveBg('#12a150')).toBe('#12a150')
  })

  it("合法大写 hex color='#ABCDEF' → bg 原样", () => {
    expect(deriveBg('#ABCDEF')).toBe('#ABCDEF')
  })

  it("非法 color='red' → bg=默认色 #12a150", () => {
    expect(deriveBg('red')).toBe('#12a150')
  })

  it('color=null → bg=默认色 #12a150', () => {
    expect(deriveBg(null)).toBe('#12a150')
  })

  it("color='' → bg=默认色 #12a150", () => {
    expect(deriveBg('')).toBe('#12a150')
  })

  it("icon='coffee' → key='coffee'（已知 key 原样）", () => {
    expect(deriveKey('coffee', '随便', 'expense')).toBe('coffee')
  })

  it("icon='zzz', name='外卖'（命中餐饮关键字）→ key='food'", () => {
    // 注：guessIcon 的餐饮规则为 /餐饮|吃|饭|外卖|美食|聚餐|零食|饮|咖啡|奶茶/，
    // 需命中完整关键字；单字「餐」（如「午餐」）不匹配，会走种类兜底 receipt。
    expect(deriveKey('zzz', '外卖', 'expense')).toBe('food')
  })

  it("icon='zzz', name='午餐'（不含餐饮关键字）→ 种类兜底 key='receipt'", () => {
    expect(deriveKey('zzz', '午餐', 'expense')).toBe('receipt')
  })

  it("icon='zzz', name='xxx', kind='income' → key='income'（种类兜底）", () => {
    expect(deriveKey('zzz', 'xxx', 'income')).toBe('income')
  })

  it("icon='zzz', name='xxx', kind='expense' → key='receipt'（种类兜底）", () => {
    expect(deriveKey('zzz', 'xxx', 'expense')).toBe('receipt')
  })
})
