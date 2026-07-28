import { http } from '../utils/request'

/** 预算总览。返回 { month, hasBudget, totalBudget, spent, remaining, usedPercent, status, health, allocated, unallocated, categories:[...] }。 */
export function budgetOverview(month) {
  return http.get(`/budgets?month=${encodeURIComponent(month)}`)
}

/** 设置/更新月度总预算。 */
export function setTotalBudget(month, amount) {
  return http.put(`/budgets?month=${encodeURIComponent(month)}`, { amount })
}

/** 设置/更新某分类预算。 */
export function setCategoryBudget(month, categoryId, amount) {
  return http.post(`/budgets/categories?month=${encodeURIComponent(month)}`, { categoryId, amount })
}

/** 删除某分类预算。 */
export function deleteCategoryBudget(month, categoryId) {
  return http.del(`/budgets/categories/${categoryId}?month=${encodeURIComponent(month)}`)
}

/** 沿用上月预算到本月。 */
export function copyPreviousBudget(month) {
  return http.post(`/budgets/copy-previous?month=${encodeURIComponent(month)}`)
}
