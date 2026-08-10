/**
 * 离线记账幂等键 / 临时 id / 乐观记录构造（Offline_Sync_System 纯内核之一）。
 *
 * 纯 ES 模块，不依赖页面 / store / 网络；只用于生成本地标识与展示占位记录，
 * 因而可在 node 下用 vitest 直接测。
 *
 * - clientToken：随创建请求发往后端的客户端幂等键（后端按「用户 + clientToken」去重）。
 * - localId：离线记录乐观上屏时的本地占位 id，同步成功后由服务端真实 id 替换。
 * - 乐观记录：带 __local / __pending 标记，供列表渲染「待同步」态。
 */

/** 生成一段随机 hex（不依赖 crypto，兼容小程序运行时）。 */
function randomHex(len = 24) {
  let s = ''
  while (s.length < len) {
    s += Math.random().toString(16).slice(2)
  }
  return s.slice(0, len)
}

/**
 * 生成客户端幂等键，形如 `ct_<48hex>`。
 * 时间戳前缀 + 随机段，进一步降低碰撞概率并便于排序调试。
 */
export function newClientToken() {
  return `ct_${Date.now().toString(36)}_${randomHex(24)}`
}

/** 生成本地占位 id，形如 `local_<24hex>`。 */
export function newLocalId() {
  return `local_${randomHex(24)}`
}

/** 判断一个 id 是否为离线临时 id。 */
export function isLocalId(id) {
  return typeof id === 'string' && id.startsWith('local_')
}

/**
 * 构造用于乐观上屏的展示记录。
 * 保留 payload 的记账字段（type/amount/accountId/categoryId/occurredAt/note 等），
 * 并附加本地标识与「待同步」标记；不改动传入的 payload。
 *
 * @param {object} payload 记账请求体（至少含 type、amount）
 * @param {{clientToken:string, localId:string}} ids
 * @returns {object} 乐观记录
 */
export function buildOptimisticTx(payload, { clientToken, localId } = {}) {
  const p = payload || {}
  return {
    ...p,
    id: localId,
    localId,
    clientToken,
    __local: true,
    __pending: true,
    createdAt: p.occurredAt || new Date().toISOString()
  }
}
