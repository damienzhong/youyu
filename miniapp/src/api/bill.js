import { http } from '../utils/request'

/**
 * 批量导入账单流水。
 * @param {{accountId:number, defaultExpenseCategoryId:number, defaultIncomeCategoryId:number,
 *          entries:Array}} payload
 * 返回 { imported, skippedDuplicate, skippedInvalid }。
 */
export function importBills(payload) {
  return http.post('/imports/bills', payload)
}

/**
 * 批量推荐分类（规则 → 历史习惯 → 内置商户库 → 分类名关键字）。
 * @param {{type:'expense'|'income', counterparty:string, text:string}[]} items
 * 返回 { results: [{ categoryId:number|null, source:'RULE'|'HABIT'|'MERCHANT'|'KEYWORD'|'NONE' }] }，与 items 一一对应。
 */
export function classifyBills(items) {
  return http.post('/imports/bills/classify', { items })
}

/**
 * 保存「以后都这样记」的商户 → 分类规则（同账本同商户覆盖）。
 * @param {{type:'expense'|'income', counterparty:string, categoryId:number}[]} rules
 * 返回 { saved }。
 */
export function saveCategoryRules(rules) {
  return http.put('/category-rules', { rules })
}
