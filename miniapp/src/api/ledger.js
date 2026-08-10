import { http } from '../utils/request'

/** 列出可访问账本（自己拥有 + 已加入的协作账本）；无则后端自动创建默认账本。 */
export function listLedgers() {
  return http.get('/ledgers')
}

/**
 * 新建账本。type：PERSONAL（个人，默认）/ COLLABORATIVE（家庭协作）/ AA（多人分摊，无月预算）。
 * accountIds：纳入该账本的账户 id 列表（本人账户）；为空/省略表示默认全选当前用户的全部账户。
 */
export function createLedger(name, type = 'PERSONAL', accountIds) {
  return http.post('/ledgers', { name, type, accountIds })
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

/**
 * 归档 AA 账本（仅 OWNER；仅 AA 账本可归档）。归档后账本只读、移入「已归档」分组，
 * 历史与导出保留、可随时解档（需求 8.3）。
 * 若账本仍有未结清净额，后端返回 409 AA_LEDGER_UNSETTLED，需二次确认后带 force=true 重试（需求 8.4）。
 * @param {number|string} id 账本 id
 * @param {boolean} force 是否强制归档（未结清时二次确认后传 true）
 */
export function archiveLedger(id, force = false) {
  const path = `/ledgers/${id}/archive${force ? '?force=true' : ''}`
  return http.post(path)
}

/** 解档 AA 账本（仅 OWNER），恢复其可编辑状态（需求 8.5）。 */
export function unarchiveLedger(id) {
  return http.post(`/ledgers/${id}/unarchive`)
}
