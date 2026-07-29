import { http } from '../utils/request'

/** 可选 ledgerId：在「全部」视图下按目标账本路由；缺省用全局当前账本。 */
function opts(ledgerId) {
  return ledgerId != null ? { ledgerId } : undefined
}

/** 列出当前账本标签。 */
export function listTags(ledgerId) {
  return http.get('/tags', opts(ledgerId))
}

/** 新建标签（同名幂等复用）。 */
export function createTag(name, ledgerId) {
  return http.post('/tags', { name }, opts(ledgerId))
}

/** 重命名标签。 */
export function renameTag(id, name, ledgerId) {
  return http.put(`/tags/${id}`, { name }, opts(ledgerId))
}

/** 删除标签（连带清除交易关联）。 */
export function deleteTag(id, ledgerId) {
  return http.del(`/tags/${id}`, opts(ledgerId))
}
