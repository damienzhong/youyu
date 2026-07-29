import { http } from '../utils/request'

/** 可选 ledgerId：在「全部」视图下按目标账本路由；缺省用全局当前账本。 */
function opts(ledgerId) {
  return ledgerId != null ? { ledgerId } : undefined
}

/** 列出当前账本的记账模板。 */
export function listTemplates(ledgerId) {
  return http.get('/templates', opts(ledgerId))
}

/** 新建记账模板。payload：{ name, type, amount?, accountId?, categoryId?, sourceAccountId?, destinationAccountId?, note? } */
export function createTemplate(payload, ledgerId) {
  return http.post('/templates', payload, opts(ledgerId))
}

/** 删除记账模板。 */
export function deleteTemplate(id, ledgerId) {
  return http.del(`/templates/${id}`, opts(ledgerId))
}
