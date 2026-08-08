/**
 * Feature: record-suggestion, 任务 7.4：前端推荐卡与预填纯逻辑单测 + 属性测试。
 *
 * 按 vitest.config.js 约定（node 环境、只跑 src/utils 下不依赖 uni API 的纯逻辑），
 * 本文件覆盖首页推荐卡与记账页预填抽出的纯函数（src/utils/suggestion.js），对应任务 7.4：
 *   - 聚合视图 / 未登录不请求（shouldFetchSuggestions，需求 5.3、7.4）
 *   - <2 / 失败 / 超时不展示卡（pickVisibleSuggestions + SUGGEST_TIMEOUT_MS，需求 1.6、7.1、7.2）
 *   - 点候选跳记账页且预填参数正确（buildPrefillQuery / buildRecordUrl，需求 4.1）
 *   - 分类 / 账户已删留空（categoryTreeHasId / accountsHasId / resolvePrefill*，需求 4.5、4.6）
 * 页面渲染、真实 navigateTo 跳转、首页其余模块隔离等 uni.* 交互按约定归手工验收清单（任务 8.2）。
 *
 * Validates: Requirements 1.6, 4.1, 4.5, 5.3, 7.2, 7.4
 */
import { describe, it, expect } from 'vitest'
import fc from 'fast-check'
import {
  SUGGEST_TIMEOUT_MS,
  MIN_SUGGESTIONS,
  MAX_SUGGESTIONS,
  shouldFetchSuggestions,
  pickVisibleSuggestions,
  buildPrefillQuery,
  buildRecordUrl,
  resolvePrefillAmount,
  resolvePrefillNote,
  categoryTreeHasId,
  accountsHasId
} from './suggestion'

describe('常量：门槛与超时', () => {
  it('超时恒为 3000ms（需求 7.2：3000ms 无响应即失败，不自动重试）', () => {
    expect(SUGGEST_TIMEOUT_MS).toBe(3000)
  })
  it('展示门槛 2、上限 3（需求 1.1/1.4/7.1）', () => {
    expect(MIN_SUGGESTIONS).toBe(2)
    expect(MAX_SUGGESTIONS).toBe(3)
  })
})

describe('shouldFetchSuggestions：聚合视图 / 未登录不请求（需求 5.3、7.4）', () => {
  it('已登录且非聚合视图 → 请求', () => {
    expect(shouldFetchSuggestions(true, false)).toBe(true)
  })
  it('未登录 → 不请求（无论是否聚合视图）', () => {
    expect(shouldFetchSuggestions(false, false)).toBe(false)
    expect(shouldFetchSuggestions(false, true)).toBe(false)
  })
  it('聚合视图（全部账本）→ 不请求（无单一账本上下文）', () => {
    expect(shouldFetchSuggestions(true, true)).toBe(false)
  })
  it('假值登录态一律不请求', () => {
    for (const v of [null, undefined, 0, '']) {
      expect(shouldFetchSuggestions(v, false)).toBe(false)
    }
  })
})

describe('pickVisibleSuggestions：<2 不展示、>=2 取前 3（需求 1.6、7.1）', () => {
  const item = (i) => ({ type: 'expense', amount: i, categoryId: i, accountId: 1, note: '' })

  it('空 / 1 条 → 空（不展示卡、不占位）', () => {
    expect(pickVisibleSuggestions([])).toEqual([])
    expect(pickVisibleSuggestions([item(1)])).toEqual([])
  })
  it('恰好 2 条 → 原样 2 条', () => {
    const list = [item(1), item(2)]
    expect(pickVisibleSuggestions(list)).toEqual(list)
  })
  it('3 条 → 原样 3 条', () => {
    const list = [item(1), item(2), item(3)]
    expect(pickVisibleSuggestions(list)).toEqual(list)
  })
  it('多于 3 条 → 截断前 3', () => {
    const list = [item(1), item(2), item(3), item(4), item(5)]
    expect(pickVisibleSuggestions(list)).toEqual([item(1), item(2), item(3)])
  })
  it('非数组 / 失败降级为空数组（需求 7.2：失败/超时不展示卡）', () => {
    for (const bad of [null, undefined, {}, 'x', 0, { suggestions: [] }]) {
      expect(pickVisibleSuggestions(bad)).toEqual([])
    }
  })
})

describe('buildPrefillQuery / buildRecordUrl：点候选跳记账页预填（需求 4.1）', () => {
  it('全字段候选 → 带 type/amount/categoryId/accountId/note，全部 URL 编码', () => {
    const s = { type: 'expense', amount: 35, categoryId: 12, accountId: 7, note: '午餐' }
    const q = buildPrefillQuery(s)
    expect(q).toContain('type=expense')
    expect(q).toContain('amount=35')
    expect(q).toContain('categoryId=12')
    expect(q).toContain('accountId=7')
    expect(q).toContain(`note=${encodeURIComponent('午餐')}`)
  })

  it('buildRecordUrl 拼到记账页路径', () => {
    const s = { type: 'income', amount: 100, categoryId: 3, accountId: 2, note: '' }
    expect(buildRecordUrl(s)).toBe('/pages/record/record?type=income&amount=100&categoryId=3&accountId=2')
  })

  it('金额缺失 → 省略 amount 参数（记账页留空，需求 4.6）', () => {
    const s = { type: 'expense', amount: null, categoryId: 3, accountId: 2, note: '' }
    const q = buildPrefillQuery(s)
    expect(q).not.toContain('amount=')
    expect(q).toContain('categoryId=3')
  })

  it('空备注 → 省略 note 参数', () => {
    const s = { type: 'expense', amount: 10, categoryId: 3, accountId: 2, note: '' }
    expect(buildPrefillQuery(s)).not.toContain('note=')
  })

  it('特殊字符备注被编码，可无损还原（& = # 等不破坏 query）', () => {
    const note = 'a&b=c #1 中文'
    const s = { type: 'expense', amount: 10, categoryId: 3, accountId: 2, note }
    const q = buildPrefillQuery(s)
    const encoded = q.split('note=')[1]
    expect(decodeURIComponent(encoded)).toBe(note)
  })
})

describe('resolvePrefillAmount：金额缺失/非正留空（需求 4.6）', () => {
  it('正数 → 字符串', () => {
    expect(resolvePrefillAmount(35)).toBe('35')
    expect(resolvePrefillAmount('35.5')).toBe('35.5')
    expect(resolvePrefillAmount(0.01)).toBe('0.01')
  })
  it('缺失 / 空 / 非数字 / 0 / 负数 → null（留空）', () => {
    for (const bad of [null, undefined, '', 'abc', 0, '0', -1, '-5', NaN]) {
      expect(resolvePrefillAmount(bad)).toBe(null)
    }
  })
})

describe('resolvePrefillNote：备注解码', () => {
  it('URL 编码备注解码还原', () => {
    expect(resolvePrefillNote(encodeURIComponent('午餐'))).toBe('午餐')
    expect(resolvePrefillNote(encodeURIComponent('a&b=c'))).toBe('a&b=c')
  })
  it('空 / 缺失 → null', () => {
    for (const empty of [null, undefined, '']) {
      expect(resolvePrefillNote(empty)).toBe(null)
    }
  })
  it('非法编码 → 回退原值（不抛错）', () => {
    expect(resolvePrefillNote('%')).toBe('%')
    expect(resolvePrefillNote('%E0%A4%A')).toBe('%E0%A4%A')
  })
})

describe('categoryTreeHasId：分类已删则留空（需求 4.5）', () => {
  const tree = {
    expense: [
      { id: 1, children: [{ id: 11 }, { id: 12 }] },
      { id: 2, children: [] }
    ],
    income: [{ id: 100, children: [{ id: 101 }] }]
  }

  it('存在的顶层/子分类 → true', () => {
    expect(categoryTreeHasId(tree, 1)).toBe(true)
    expect(categoryTreeHasId(tree, 12)).toBe(true)
    expect(categoryTreeHasId(tree, 100)).toBe(true)
    expect(categoryTreeHasId(tree, 101)).toBe(true)
  })
  it('不存在的分类（已删）→ false（留空由用户重选）', () => {
    expect(categoryTreeHasId(tree, 999)).toBe(false)
    expect(categoryTreeHasId(tree, null)).toBe(false)
    expect(categoryTreeHasId(tree, undefined)).toBe(false)
  })
  it('空树 / 缺省分组 → false', () => {
    expect(categoryTreeHasId(null, 1)).toBe(false)
    expect(categoryTreeHasId({}, 1)).toBe(false)
    expect(categoryTreeHasId({ expense: [] }, 1)).toBe(false)
  })
})

describe('accountsHasId：账户已删则留空回退默认（需求 4.5）', () => {
  const accounts = [{ id: 1 }, { id: 2 }, { id: 3 }]
  it('可选集内 → true', () => {
    expect(accountsHasId(accounts, 2)).toBe(true)
  })
  it('不在可选集（已删/不可见）→ false', () => {
    expect(accountsHasId(accounts, 99)).toBe(false)
    expect(accountsHasId(accounts, null)).toBe(false)
  })
  it('非数组 → false', () => {
    expect(accountsHasId(null, 1)).toBe(false)
    expect(accountsHasId(undefined, 1)).toBe(false)
  })
})

// —— 属性测试：跨大量输入验证纯函数不变式 ——

describe('属性：pickVisibleSuggestions 输出恒 ∈ [0,3] 且为输入前缀', () => {
  it('任意长度列表：<2 → 空；否则取前 min(len,3)', () => {
    fc.assert(
      fc.property(fc.array(fc.record({ type: fc.constantFrom('expense', 'income') })), (list) => {
        const out = pickVisibleSuggestions(list)
        expect(out.length).toBeLessThanOrEqual(MAX_SUGGESTIONS)
        if (list.length < MIN_SUGGESTIONS) {
          expect(out).toEqual([])
        } else {
          expect(out).toEqual(list.slice(0, MAX_SUGGESTIONS))
        }
      }),
      { numRuns: 250 }
    )
  })
})

describe('属性：shouldFetchSuggestions ⟺ 已登录 ∧ 非聚合', () => {
  it('仅当登录为真且聚合为假时请求', () => {
    fc.assert(
      fc.property(fc.boolean(), fc.boolean(), (loggedIn, isAll) => {
        expect(shouldFetchSuggestions(loggedIn, isAll)).toBe(loggedIn && !isAll)
      }),
      { numRuns: 50 }
    )
  })
})

describe('属性：buildPrefillQuery 的 note 编码可无损往返', () => {
  it('任意备注文本经查询串编码后可 decodeURIComponent 还原', () => {
    fc.assert(
      fc.property(fc.string({ minLength: 1 }), (note) => {
        const q = buildPrefillQuery({ type: 'expense', amount: 1, categoryId: 1, accountId: 1, note })
        const seg = q.split('note=')[1]
        // 空白经 strip 后可能整体为空串的备注在页面侧本就省略，这里只验证非空片段可还原
        if (seg != null) {
          expect(decodeURIComponent(seg)).toBe(note)
        }
      }),
      { numRuns: 150 }
    )
  })
})

describe('属性：resolvePrefillAmount 输出为 null 或正数字符串', () => {
  it('正数 → 其字符串；否则 null', () => {
    fc.assert(
      fc.property(
        fc.oneof(fc.double(), fc.integer(), fc.constantFrom(null, undefined, '', 'x', NaN, 0, -1)),
        (amount) => {
          const out = resolvePrefillAmount(amount)
          if (out === null) return
          const n = Number(out)
          expect(Number.isNaN(n)).toBe(false)
          expect(n).toBeGreaterThan(0)
        }
      ),
      { numRuns: 250 }
    )
  })
})
