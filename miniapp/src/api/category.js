import { http } from '../utils/request'

/**
 * 列出本人分类，返回 { expense: Node[], income: Node[] }。
 * Node: { id, name, parentId, children: Node[] }（两级）。
 */
export function listCategories() {
  return http.get('/categories')
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
