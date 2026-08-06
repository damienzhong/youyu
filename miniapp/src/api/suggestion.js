import { http } from '../utils/request'

/** 可选 ledgerId：在「全部」视图下按目标账本路由；缺省用全局当前账本。 */
function opts(ledgerId) {
  return ledgerId != null ? { ledgerId } : undefined
}

/** 当前账本的记账推荐（至多 3 条；少于 2 条时后端返回空列表）。 */
export function fetchSuggestions(ledgerId) {
  return http.get('/transactions/suggestions', opts(ledgerId))
}
