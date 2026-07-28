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
