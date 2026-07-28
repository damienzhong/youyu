import { http } from '../utils/request'

/**
 * 列出本人分类，返回 { expense: Node[], income: Node[] }。
 * Node: { id, name, parentId, children: Node[] }（两级）。
 */
export function listCategories() {
  return http.get('/categories')
}

/**
 * 创建分类。kind 须为大写 EXPENSE/INCOME（后端按枚举校验）；
 * parentId 为空创建父分类，指向父分类则创建子分类（子分类 kind 以父级为准）。
 */
export function createCategory({ kind, name, parentId = null }) {
  return http.post('/categories', { kind, name, parentId })
}

/** 重命名分类（仅改名称，保留 kind/父级/交易关联）。 */
export function renameCategory(id, name) {
  return http.put(`/categories/${id}`, { name })
}

/** 删除分类（无交易引用、无子分类才允许）。 */
export function deleteCategory(id) {
  return http.del(`/categories/${id}`)
}

/**
 * 把两级分类树拍平为可选项列表：父分类与子分类都可选，子分类带父级前缀便于区分。
 * @param {Array} nodes 某一 kind 下的顶级节点数组
 * @returns {{id:number,label:string}[]}
 */
export function flattenCategories(nodes) {
  const out = []
  for (const parent of nodes || []) {
    out.push({ id: parent.id, label: parent.name })
    for (const child of parent.children || []) {
      out.push({ id: child.id, label: `${parent.name} / ${child.name}` })
    }
  }
  return out
}

/**
 * 把分类树拍平为带 kind/parentId 的扁平列表，供账单导入的关键字匹配。
 * @param {{expense:Array,income:Array}} tree
 * @returns {{id:number,name:string,kind:'EXPENSE'|'INCOME',parentId:number|null}[]}
 */
export function flattenAll(tree) {
  const out = []
  for (const [key, kind] of [['expense', 'EXPENSE'], ['income', 'INCOME']]) {
    for (const parent of tree?.[key] || []) {
      out.push({ id: parent.id, name: parent.name, kind, parentId: null })
      for (const child of parent.children || []) {
        out.push({ id: child.id, name: child.name, kind, parentId: parent.id })
      }
    }
  }
  return out
}

/**
 * 把完整分类树（含 expense/income）映射为 { [id]: label }，供明细列表按 categoryId 显示名称。
 * @param {{expense:Array,income:Array}} tree
 */
export function buildCategoryLabelMap(tree) {
  const map = {}
  for (const kind of ['expense', 'income']) {
    for (const opt of flattenCategories(tree?.[kind])) {
      map[opt.id] = opt.label
    }
  }
  return map
}
