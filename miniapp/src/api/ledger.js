import { http } from '../utils/request'

/** 列出本人账本（无则后端自动创建默认账本）。 */
export function listLedgers() {
  return http.get('/ledgers')
}

/** 新建账本。 */
export function createLedger(name) {
  return http.post('/ledgers', { name })
}

/** 重命名账本。 */
export function renameLedger(id, name) {
  return http.put(`/ledgers/${id}`, { name })
}

/** 删除账本（级联清空其数据；至少保留一个）。 */
export function deleteLedger(id) {
  return http.del(`/ledgers/${id}`)
}
