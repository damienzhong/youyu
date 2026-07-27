import { describe, it, expect } from 'vitest'
import { parseCsvLine, detectSource, parseBillText, matchCategory } from './billImport'
import type { Category } from './ledger'

const CATS: Category[] = [
  { id: 1, kind: 'EXPENSE', name: '餐饮', parentId: null },
  { id: 2, kind: 'EXPENSE', name: '交通', parentId: null },
  { id: 3, kind: 'INCOME', name: '工资', parentId: null },
]

describe('parseCsvLine', () => {
  it('splits plain fields', () => {
    expect(parseCsvLine('a,b,c')).toEqual(['a', 'b', 'c'])
  })
  it('respects quoted commas and escaped quotes', () => {
    expect(parseCsvLine('a,"b,c","d""e"')).toEqual(['a', 'b,c', 'd"e'])
  })
})

describe('detectSource', () => {
  it('detects wechat', () => {
    expect(detectSource('微信支付账单明细\n...')).toBe('wechat')
  })
  it('detects alipay', () => {
    expect(detectSource('支付宝交易记录明细\n...')).toBe('alipay')
  })
  it('returns null for unknown', () => {
    expect(detectSource('some random csv')).toBeNull()
  })
})

describe('matchCategory', () => {
  it('matches by substring of category name', () => {
    expect(matchCategory('餐饮美食 美团 午餐', 'EXPENSE', CATS)?.id).toBe(1)
    expect(matchCategory('工资 XX公司', 'INCOME', CATS)?.id).toBe(3)
  })
  it('returns null when nothing matches', () => {
    expect(matchCategory('医院 挂号', 'EXPENSE', CATS)).toBeNull()
  })
})

const ALIPAY = `支付宝交易记录明细
账号:[xxx]
交易时间,交易分类,交易对方,商品说明,收/支,金额,收/付款方式,交易状态,交易订单号,商家订单号,备注
2026-06-28 12:30:00,餐饮美食,美团,午餐,支出,38.00,余额,交易成功,ALI123,MER1,
2026-06-25 10:00:00,收入,XX公司,工资,收入,21000.00,,交易成功,ALI456,MER2,
2026-06-20 09:00:00,,余额宝,转出,不计收支,100.00,,交易成功,ALI789,,
`

const WECHAT = `微信支付账单明细
微信昵称:[xxx]
交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
2026-06-28 12:30:00,商户消费,美团,午餐,支出,¥38.00,零钱,支付成功,WX123,M1,
2026-06-20 08:00:00,转账,朋友,/,/,¥50.00,零钱,已收钱,WX999,,
`

describe('parseBillText - alipay', () => {
  const r = parseBillText(ALIPAY, 'alipay', CATS)
  it('parses expense + income and skips neutral', () => {
    expect(r.entries).toHaveLength(2)
    expect(r.neutralCount).toBe(1)
    expect(r.invalidCount).toBe(0)
  })
  it('normalizes amount, time, external id, category', () => {
    const exp = r.entries.find((e) => e.type === 'expense')!
    expect(exp.amount).toBe('38.00')
    expect(exp.occurredAt).toBe('2026-06-28T12:30:00')
    expect(exp.externalId).toBe('alipay:ALI123')
    expect(exp.categoryId).toBe(1) // 餐饮
    expect(exp.note).toBe('美团 · 午餐')
    const inc = r.entries.find((e) => e.type === 'income')!
    expect(inc.categoryId).toBe(3) // 工资
  })
  it('computes totals and date range', () => {
    expect(r.expenseTotal).toBe('38.00')
    expect(r.incomeTotal).toBe('21000.00')
    // 日期区间仅覆盖已导入（非中性）条目：06-20 为不计收支被跳过。
    expect(r.from).toBe('2026-06-25')
    expect(r.to).toBe('2026-06-28')
  })
})

describe('parseBillText - wechat', () => {
  const r = parseBillText(WECHAT, 'wechat', CATS)
  it('strips ¥ and skips neutral "/" rows', () => {
    expect(r.entries).toHaveLength(1)
    expect(r.neutralCount).toBe(1)
    expect(r.entries[0]!.amount).toBe('38.00')
    expect(r.entries[0]!.externalId).toBe('wechat:WX123')
  })
})
