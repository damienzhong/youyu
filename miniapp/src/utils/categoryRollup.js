/**
 * 分类占比报表的「父分类归并 + 子分类下钻」纯逻辑（单一事实源、可测试）。
 *
 * 背景：分类为两级（parentId 为空=父分类，非空=子分类），交易的 categoryId 可能指向父分类
 * 或子分类。后端 /reports/category 与「全部账本」客户端聚合都按叶子级 categoryId 平铺汇总，
 * 于是父分类和它的子分类会并排出现，不符合「先看一级分类、再看次级分类」的浏览习惯。
 *
 * 本函数把叶子级占比行按其「根父分类」归并成一级列表，并在每个父分类下保留可下钻的子项：
 *   - 子分类交易 → 归并进其父分类；
 *   - 直接记在父分类上的交易 → 归并进该父分类，并在下钻里以「未细分」单列（仅当该父分类还有
 *     子分类时才单列，避免与父分类行自身重复）；
 *   - 未分类 / 已删除（在分类树里找不到）→ 各自成为一个无子项的一级行。
 *
 * 金额一律用「分」为单位做整数累加，规避浮点漂移；对外仍以 2 位小数字符串输出，占比为 Number。
 */

/** 元字符串/数字 → 分（四舍五入到整数分）。非法值按 0 处理。 */
function toCents(value) {
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n)) return 0
  return Math.round(n * 100)
}

/** 分 → 元（2 位小数字符串），与后端 formatAmount 入参口径一致。 */
function centsToYuan(cents) {
  return (cents / 100).toFixed(2)
}

/** 占比（%）：部分 / 全部 × 100，保留 2 位小数；分母 ≤ 0 记 0。 */
function pct(partCents, totalCents) {
  if (totalCents <= 0) return 0
  return Number(((partCents / totalCents) * 100).toFixed(2))
}

/**
 * 把叶子级分类占比行按父分类归并。
 *
 * @param {Array<{categoryId:(number|null), categoryName:string, amount:(string|number), count:number}>} shares
 *        叶子级占比行（后端 categories 或聚合派生行），amount 为元。
 * @param {Array<{id:number, name:string, kind:string, parentId:(number|null)}>} flatCategories
 *        分类树拍平结果（api/category.js 的 flattenAll 输出），用于查父子关系与父分类名。
 * @param {(string|number)} total 该类别（支出/收入）总额（元），作为一级行占比分母。
 * @returns {Array<{categoryId:(number|null), categoryName:string, amount:string, count:number,
 *          percentage:number, children:Array<{categoryId:(number|null), categoryName:string,
 *          amount:string, count:number, percentage:number}>}>}
 *          一级行按金额降序、id 升序；children 为子项（含「未细分」），按金额降序，占比相对父分类。
 */
export function rollupCategoryShares(shares, flatCategories, total) {
  const parentOf = new Map() // id -> parentId(null=父分类/顶级)
  const nameOf = new Map() // id -> name
  for (const c of flatCategories || []) {
    if (c && c.id != null) {
      parentOf.set(c.id, c.parentId ?? null)
      nameOf.set(c.id, c.name)
    }
  }

  const totalCents = toCents(total)

  // rootId(null=未分类) -> { amountCents, count, name, children: Map<childKey, {..}> }
  const groups = new Map()
  const groupKey = (id) => (id == null ? '__none__' : String(id))

  for (const s of shares || []) {
    const cid = s.categoryId ?? null
    const pid = cid == null ? null : parentOf.get(cid) // undefined = 分类树里没有（已删除/未知）
    // 根父分类：子分类归到父；父分类/未知/未分类归到自身。
    const rootId = pid != null ? pid : cid
    const isDirect = pid == null // 直接记在根上（父分类本体、未知或未分类）
    const cents = toCents(s.amount)
    const count = Number(s.count) || 0

    const key = groupKey(rootId)
    let g = groups.get(key)
    if (!g) {
      // 父分类名优先取分类树；取不到（未知/已删除）回退该行自带名，再回退「未分类」。
      const gName =
        nameOf.get(rootId) || (isDirect ? s.categoryName : null) || '未分类'
      g = { rootId, amountCents: 0, count: 0, name: gName, direct: null, children: [] }
      groups.set(key, g)
    }
    g.amountCents += cents
    g.count += count
    if (isDirect) {
      // 直接记在根上的部分：累加到 direct 桶（可能多行，如未分类聚合）。
      if (!g.direct) g.direct = { amountCents: 0, count: 0 }
      g.direct.amountCents += cents
      g.direct.count += count
    } else {
      g.children.push({
        categoryId: cid,
        categoryName: s.categoryName || nameOf.get(cid) || '子分类',
        amountCents: cents,
        count
      })
    }
  }

  const result = []
  for (const g of groups.values()) {
    const children = []
    // 有真实子分类时，才把 direct 部分单列为「未细分」；纯父分类直接记账则不重复列子项。
    if (g.children.length && g.direct && g.direct.amountCents > 0) {
      children.push({
        categoryId: g.rootId,
        categoryName: '未细分',
        amountCents: g.direct.amountCents,
        count: g.direct.count
      })
    }
    for (const c of g.children) children.push(c)
    // 子项按金额降序，占比相对父分类总额。
    children.sort((a, b) => b.amountCents - a.amountCents)
    const childRows = children.map((c) => ({
      categoryId: c.categoryId,
      categoryName: c.categoryName,
      amount: centsToYuan(c.amountCents),
      count: c.count,
      percentage: pct(c.amountCents, g.amountCents)
    }))
    result.push({
      categoryId: g.rootId,
      categoryName: g.name,
      amount: centsToYuan(g.amountCents),
      count: g.count,
      percentage: pct(g.amountCents, totalCents),
      // 仅当存在 ≥1 个子项时才可下钻（纯直接记账的父分类不展开）。
      children: childRows
    })
  }

  // 一级行：金额降序、id 升序（未分类 rootId=null 视作 +∞ 排末尾，保证确定性）。
  result.sort((a, b) => {
    const d = toCents(b.amount) - toCents(a.amount)
    if (d !== 0) return d
    const ai = a.categoryId == null ? Number.MAX_SAFE_INTEGER : a.categoryId
    const bi = b.categoryId == null ? Number.MAX_SAFE_INTEGER : b.categoryId
    return ai - bi
  })
  return result
}
