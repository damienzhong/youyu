import { http } from '../utils/request'

/** 可选 ledgerId：在「全部」视图下按目标账本路由；缺省用全局当前账本。 */
function opts(ledgerId) {
  return ledgerId != null ? { ledgerId } : undefined
}

/** 列出当前账本项目（未归档优先）。 */
export function listProjects(ledgerId) {
  return http.get('/projects', opts(ledgerId))
}

/** 新建项目。 */
export function createProject(name, ledgerId) {
  return http.post('/projects', { name }, opts(ledgerId))
}

/** 重命名/归档切换。payload：{ name?, archived? } */
export function updateProject(id, payload, ledgerId) {
  return http.put(`/projects/${id}`, payload, opts(ledgerId))
}

/** 删除项目。 */
export function deleteProject(id, ledgerId) {
  return http.del(`/projects/${id}`, opts(ledgerId))
}
