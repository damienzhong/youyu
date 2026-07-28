import { http } from '../utils/request'

/** 列出可访问账本（自己拥有 + 已加入的协作账本）；无则后端自动创建默认账本。 */
export function listLedgers() {
  return http.get('/ledgers')
}

/** 新建账本。type：INDEPENDENT（独立，默认）/ COLLABORATIVE（协作）。 */
export function createLedger(name, type = 'INDEPENDENT') {
  return http.post('/ledgers', { name, type })
}

/** 重命名账本（仅 OWNER）。 */
export function renameLedger(id, name) {
  return http.put(`/ledgers/${id}`, { name })
}

/** 删除账本（仅 OWNER，级联清空其数据；至少保留一个自己拥有的账本）。 */
export function deleteLedger(id) {
  return http.del(`/ledgers/${id}`)
}

/** OWNER 为协作账本生成邀请码。 */
export function createInvite(id) {
  return http.post(`/ledgers/${id}/invite`)
}

/** 凭邀请码加入协作账本。 */
export function joinLedger(code) {
  return http.post('/ledgers/join', { code })
}

/** 列出账本成员（成员可见）。 */
export function listMembers(id) {
  return http.get(`/ledgers/${id}/members`)
}

/** 移除成员（OWNER 移除他人）或退出（成员移除自己）。 */
export function removeMember(id, memberUserId) {
  return http.del(`/ledgers/${id}/members/${memberUserId}`)
}
