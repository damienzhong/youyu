import { http } from '../utils/request'

/**
 * 列出本人分类，返回 { expense: Node[], income: Node[] }。
 * Node: { id, name, parentId, children: Node[] }（两级）。
 */
export function listCategories(ledgerId) {
  return http.get('/categories', ledgerId != null ? { ledgerId } : undefined)
}

/**
 * 创建分类。kind 须为大写 EXPENSE/INCOME（后端按枚举校验）；
 * parentId 为空创建父分类，指向父分类则创建子分类（子分类 kind 以父级为准）。
 * icon（图标 key）、iconColor（背景色 hex #RRGGBB）均为可选，由后端净化：
 * 非白名单图标由名称推断兜底，非法配色回退默认色（需求 2.3、2.4、3.3、3.4、5.1-5.3）。
 */
export function createCategory({ kind, name, parentId = null, icon = null, iconColor = null }) {
  return http.post('/categories', { kind, name, parentId, icon, iconColor })
}

/**
 * 更新分类名称/图标/背景色（保留 kind/父级/交易关联）。
 * icon 传 null 表示不改图标；iconColor 传 null 表示不改配色，非法配色由后端净化。
 */
export function renameCategory(id, name, icon = null, iconColor = null) {
  return http.put(`/categories/${id}`, { name, icon, iconColor })
}

/** 删除分类（无交易引用、无子分类才允许）。 */
export function deleteCategory(id) {
  return http.del(`/categories/${id}`)
}

/** 给当前账本补齐默认分类（仅当为空时，幂等），供新手引导使用。 */
export function seedDefaultCategories() {
  return http.post('/categories/seed-defaults')
}

/**
 * 把两级分类树拍平为可选项列表：父分类与子分类都可选，子分类带父级前缀便于区分。
 * @param {Array} nodes 某一 kind 下的顶级节点数组
 * @returns {{id:number,label:string}[]}
 */
export function flattenCategories(nodes) {
  const out = []
  for (const parent of nodes || []) {
    out.push({ id: parent.id, label: parent.name, icon: parent.icon, iconColor: parent.iconColor, name: parent.name })
    for (const child of parent.children || []) {
      out.push({
        id: child.id,
        label: `${parent.name} / ${child.name}`,
        icon: child.icon,
        iconColor: child.iconColor,
        name: child.name
      })
    }
  }
  return out
}

/**
 * 把完整分类树映射为 { [id]: iconKey }，供明细/报表等按 categoryId 渲染统一图标。
 * 无 icon 的分类留空，由展示层按名称兜底推断。
 * @param {{expense:Array,income:Array}} tree
 */
export function buildCategoryIconMap(tree) {
  const map = {}
  for (const kind of ['expense', 'income']) {
    for (const opt of flattenCategories(tree?.[kind])) {
      map[opt.id] = opt.icon || null
    }
  }
  return map
}

/**
 * 把完整分类树映射为 { [id]: iconColor }，供明细/报表等按 categoryId 渲染彩色磁贴背景色。
 * 无 iconColor 的分类留空，由展示层（CategoryIcon）按默认色兜底。
 * @param {{expense:Array,income:Array}} tree
 */
export function buildCategoryColorMap(tree) {
  const map = {}
  for (const kind of ['expense', 'income']) {
    for (const opt of flattenCategories(tree?.[kind])) {
      map[opt.id] = opt.iconColor || null
    }
  }
  return map
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
