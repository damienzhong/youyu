import { http } from '../utils/request'

/** 可选 ledgerId：在「全部」视图下按目标账本路由；缺省用全局当前账本。 */
function opts(ledgerId) {
  return ledgerId != null ? { ledgerId } : undefined
}

/** 列出当前账本商家。 */
export function listMerchants(ledgerId) {
  return http.get('/merchants', opts(ledgerId))
}

/** 新建商家（同名幂等复用）。 */
export function createMerchant(name, ledgerId) {
  return http.post('/merchants', { name }, opts(ledgerId))
}

/** 重命名商家。 */
export function renameMerchant(id, name, ledgerId) {
  return http.put(`/merchants/${id}`, { name }, opts(ledgerId))
}

/** 删除商家。 */
export function deleteMerchant(id, ledgerId) {
  return http.del(`/merchants/${id}`, opts(ledgerId))
}
