import { ref } from 'vue'
import { STORAGE_KEYS } from './config'
import { ackAchievementNotices, fetchPendingAchievements } from '../api/achievement'
import {
  ACHIEVEMENT_PAGE_PATH,
  PENDING_TIMEOUT_MS,
  TOAST_DURATION_MS,
  TOAST_GAP_MS,
  ackCursorOf,
  planBroadcast
} from './achievement'

/**
 * 解锁播报的编排（需求 7.1~7.3、7.5、7.6、7.9~7.12、7.14~7.16）。
 *
 * 本模块是 design.md「播报编排的状态机」的落点：
 *
 * ```
 * IDLE
 *  ├─ 未登录 → 不发请求，保持 IDLE（需求 7.15）
 *  └─ start() → REQUESTING（broadcasting = true）
 *       ├─ 失败 / 3000ms 超时 → 静默放弃，页面一字不动 → IDLE（需求 7.3）
 *       ├─ items 为空 → IDLE
 *       └─ items 非空 → MODAL（planBroadcast）
 *            ├─ 关闭 / 遮罩点击 → 组件内 300ms 收起后 → TOASTING（需求 7.8）
 *            └─ 进入成就页 → 收起并 navigateTo，放弃未展示的 Toast → ACK（需求 7.16）
 *       TOASTING：依次 showToast(1500ms)，间隔 300ms → ACK
 *       ACK：ackCursorOf(已展示项) → POST；失败不重试、不提示（需求 7.10）→ IDLE
 * ```
 *
 * 三条设计约束：
 *
 * 1. **决策全部来自 utils/achievement.js 的纯函数**（`planBroadcast` / `ackCursorOf`）。
 *    本模块只做副作用：发请求、开关弹层、`uni.showToast`、跳转、计时。
 *    「单次至多 3 项」与「游标只取已展示项的最大事件 id」两条不变式因此只有一处实现，
 *    也才谈得上被 Property 8 锁住——在 `.vue` 里重新推导一遍等于把防线拆了。
 *
 * 2. **模块级状态而非页面级状态**（需求 7.14）。`broadcasting` 是全局唯一的幂等守卫，
 *    「播报进行中」= 自待播报请求发出时刻起、至游标推进请求发出或本次播报被放弃时刻止；
 *    进行中再次触发（换页面也算）一律直接丢弃后一次请求，绝不叠加第 2 个弹层。
 *    弹层的可见性与当前项同样是模块级 ref：三处挂载点共用同一份状态，
 *    页面只负责把它绑到 `<AchievementUnlockModal>` 上。
 *
 * 3. **绝不阻断主路径**（需求 7.3、7.10、7.12）。`startAchievementBroadcast()` 同步返回、
 *    不返回 Promise，调用方无从 await：记账成功后的页面返回、列表刷新与余额刷新
 *    照常先发起，播报请求的成败与快慢与它们无关。全部失败分支静默降级，一条提示都不出。
 *
 * 登录态判定读的是 `STORAGE_KEYS.token`（与 stores/auth 的 `isLoggedIn` 同一个数据源，
 * 那个 getter 就是 `!!state.token`、state 又初始化自同一个 key），刻意不引入 pinia：
 * 本模块会在页面之外被调用，不该要求调用时机落在某个 setup 里。
 */

/** 弹层是否展示（模块级，三处挂载点共用；页面把它绑到 `:visible` 上）。 */
export const broadcastVisible = ref(false)

/** 弹层当前播报的那一枚待播报项（PendingAchievementItem），无播报时为 null。 */
export const broadcastItem = ref(null)

/** 允许承载解锁弹层的三处挂载点（需求 7.1、7.2 的三处触发页面）。 */
const MODAL_HOST_ROUTES = ['pages/record/record', 'pages/growth/growth', 'pages/achievement/achievement']
const ACHIEVEMENT_ROUTE = 'pages/achievement/achievement'

/** 幂等守卫（需求 7.14）：进行中再次触发直接丢弃。 */
let broadcasting = false
/** IDLE | REQUESTING | MODAL | CLOSING | TOASTING | ACK */
let phase = 'IDLE'
/** 本次**已展示**的项：ack 游标的唯一依据（需求 7.11）。 */
let shownItems = []
/** 尚未展示的 Toast 项（至多 2 项，来自 planBroadcast）。 */
let toastQueue = []
/** 本次播报挂出的全部定时器，放弃时统一清掉，避免跨播报串台。 */
let timers = []

function later(fn, ms) {
  const t = setTimeout(fn, ms)
  timers.push(t)
  return t
}

function clearTimers() {
  for (const t of timers) clearTimeout(t)
  timers = []
}

/** 与 stores/auth 的 isLoggedIn 同源（需求 7.15）；存储不可用时按未登录处理，不抛出。 */
function isLoggedIn() {
  try {
    return !!uni.getStorageSync(STORAGE_KEYS.token)
  } catch (e) {
    return false
  }
}

/** 当前页面路由；取不到（H5 早期、测试环境）返回 null，此时不据此拦截。 */
function currentRoute() {
  try {
    if (typeof getCurrentPages !== 'function') return null
    const pages = getCurrentPages()
    if (!pages || !pages.length) return null
    const route = pages[pages.length - 1].route || pages[pages.length - 1].__route__
    return route ? String(route).replace(/^\//, '') : null
  } catch (e) {
    return null
  }
}

/**
 * 客户端超时（需求 7.3、7.10）：底层请求仍会跑完，这里只让 await 提前失败。
 * 与成长页 / 成就页的同名函数逐字一致，刻意不抽公共件——三处各自只有 12 行，
 * 抽出来反而要在 utils/achievement.js 里放一个带 Promise 的非纯函数。
 */
function withTimeout(promise, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject({ code: 'TIMEOUT', message: '请求超时' }), ms)
    promise.then(
      (v) => {
        clearTimeout(timer)
        resolve(v)
      },
      (e) => {
        clearTimeout(timer)
        reject(e)
      }
    )
  })
}

/** 回到 IDLE 并释放守卫；不发 ack、不提示（需求 7.3 的静默放弃收尾）。 */
function reset() {
  clearTimers()
  phase = 'IDLE'
  shownItems = []
  toastQueue = []
  broadcastVisible.value = false
  broadcastItem.value = null
  broadcasting = false
}

/**
 * 触发一次播报（需求 7.1、7.2 的三处挂载点都调它）。
 *
 * 同步返回 boolean（true = 本次已发起），**不返回 Promise**：
 * 调用方因此无法 await，记账结果展示、页面返回、列表与余额刷新一律不等它（需求 7.12）。
 * 返回 false 的三种情形：进行中被丢弃（需求 7.14）、未登录（需求 7.15）。
 */
export function startAchievementBroadcast() {
  if (broadcasting) return false
  if (!isLoggedIn()) return false
  broadcasting = true
  phase = 'REQUESTING'
  shownItems = []
  toastQueue = []
  requestPending()
  return true
}

/** REQUESTING：失败 / 超时 / 空列表 / 当前页不承载弹层，四种情形一律静默放弃。 */
async function requestPending() {
  let res = null
  try {
    res = await withTimeout(fetchPendingAchievements(), PENDING_TIMEOUT_MS)
  } catch (e) {
    // 需求 7.3：不重试、不提示、页面展示内容与跳转行为不变。
    reset()
    return
  }
  if (phase !== 'REQUESTING') return // 期间被放弃（页面卸载等），响应直接丢弃

  const plan = planBroadcast(res && res.items)
  if (!plan.modal) {
    reset()
    return
  }
  // 弹层只能挂在三处挂载点上。记账成功后页面通常已经返回（需求 7.12 要求返回不等播报），
  // 此时把弹层「展示」到一个已卸载的页面上，用户什么也看不到、守卫却会一直挂着，
  // 因此这里改为静默放弃且**不发 ack**——这些成就留在待播报集合内，
  // 用户下次打开成长页或成就页时照常播报（至少一次语义，需求 7.10、7.11）。
  const route = currentRoute()
  if (route !== null && !MODAL_HOST_ROUTES.includes(route)) {
    reset()
    return
  }

  phase = 'MODAL'
  shownItems = [plan.modal]
  toastQueue = plan.toasts.slice()
  broadcastItem.value = plan.modal
  broadcastVisible.value = true
}

/**
 * 弹层已收起（组件在 300ms 收起动画结束后抛 `update:visible = false`，需求 7.8）。
 *
 * 推迟一个 tick 再进 TOASTING：组件的 `leave()` 里先抛 `update:visible`、紧接着才抛 `enter`，
 * 「进入成就页」因此会在同一 tick 内两个事件都到齐。晚一个 tick 就能让 enter 分支先把
 * Toast 队列清空（需求 7.16），不必让组件多抛一个关闭原因。
 */
export function closeBroadcastModal() {
  broadcastVisible.value = false
  if (phase !== 'MODAL') return
  phase = 'CLOSING'
  later(() => {
    if (phase === 'CLOSING') startToasting()
  }, 0)
}

/**
 * 弹层内「进入成就页」（需求 7.16）：收起弹层、打开成就页、放弃尚未展示的 Toast，
 * 并按需求 7.9 用**已展示项**的最大事件 id 推进游标。
 */
export function enterAchievementPageFromBroadcast() {
  if (phase !== 'MODAL' && phase !== 'CLOSING') return
  clearTimers()
  toastQueue = [] // 放弃本次尚未展示的 Toast 项（需求 7.16）
  broadcastVisible.value = false
  // 已经在成就页上就不再压一层同路由页面（弹层可以挂在成就页上，见 MODAL_HOST_ROUTES）。
  if (currentRoute() !== ACHIEVEMENT_ROUTE) {
    // 带上刚播报的编码，落地即高亮那一项（沿用分享落地的同一条参数通路，需求 8.11）。
    const code = String((broadcastItem.value && broadcastItem.value.code) || '')
    const url = code
      ? `${ACHIEVEMENT_PAGE_PATH}?code=${encodeURIComponent(code)}`
      : ACHIEVEMENT_PAGE_PATH
    try {
      uni.navigateTo({ url, fail: () => {} })
    } catch (e) {
      /* 跳转失败不影响游标推进 */
    }
  }
  sendAck()
}

/** TOASTING：第 2–3 项依次 `showToast`，展示 1500ms、相邻间隔 300ms（需求 7.5）。 */
function startToasting() {
  phase = 'TOASTING'
  playNextToast()
}

function playNextToast() {
  if (phase !== 'TOASTING') return
  const next = toastQueue.shift()
  if (!next) {
    // 全部播完 → 1000ms 内发 ack（需求 7.9）；这里是同步发起，远快于上限。
    sendAck()
    return
  }
  shownItems.push(next)
  try {
    uni.showToast({
      title: `成就解锁：${String((next && next.name) || '新成就')}`,
      icon: 'none',
      duration: TOAST_DURATION_MS
    })
  } catch (e) {
    /* Toast 失败不影响后续项与游标推进 */
  }
  later(playNextToast, TOAST_DURATION_MS + TOAST_GAP_MS)
}

/**
 * ACK：游标取 `ackCursorOf(已展示项)`（需求 7.9、7.11）；未展示任何项则不发请求。
 *
 * 守卫在**请求发出的那一刻**释放（需求 7.14 对「播报进行中」的定义），因此紧随其后的
 * 一次触发能正常开始——它可能拿回同一批项（ack 尚未落库），这正是「至少一次」的代价，
 * 与漏播相比是刻意选择的那一侧。失败与超时都不重试、不提示（需求 7.10）。
 */
function sendAck() {
  clearTimers()
  phase = 'ACK'
  // 游标必须在 reset() 清空 shownItems 之前算出来。
  const cursor = ackCursorOf(shownItems)
  reset()
  if (cursor === null) return // 需求 7.9：未展示任何项不发起游标推进请求
  withTimeout(ackAchievementNotices(cursor), PENDING_TIMEOUT_MS).catch(() => {})
}

/**
 * 页面卸载时释放守卫（`onUnload` 里调）。
 *
 * 只处理「弹层正展示在这个即将消失的页面上」这一种情形：不发 ack、不提示，
 * 让这些成就留在待播报集合内，下次触发再播（需求 7.11 的至少一次语义）。
 * REQUESTING 阶段刻意不处理——响应回来时 `requestPending` 会自己做挂载点判定；
 * TOASTING 阶段也不处理——`uni.showToast` 不依附页面，已展示的项该照常推进游标。
 */
export function releaseAchievementBroadcastOnLeave() {
  if (phase === 'MODAL' || phase === 'CLOSING') reset()
}

/** 仅供测试与调试：当前是否处于「播报进行中」。 */
export function isBroadcasting() {
  return broadcasting
}
