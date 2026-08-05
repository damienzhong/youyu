<script setup>
import { computed, getCurrentInstance, nextTick, onUnmounted, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShareAppMessage, onUnload } from '@dcloudio/uni-app'
import { useAuthStore } from '../../stores/auth'
import { fetchAchievements } from '../../api/achievement'
// AchievementUnlockModal 由 easycom 自动注册（components/AchievementUnlockModal/…），不需显式 import。
import {
  broadcastItem,
  broadcastVisible,
  closeBroadcastModal,
  enterAchievementPageFromBroadcast,
  releaseAchievementBroadcastOnLeave,
  startAchievementBroadcast
} from '../../utils/achievementBroadcast'
import {
  ACHIEVEMENT_TOTAL,
  HIGHLIGHT_MS,
  LIST_TIMEOUT_MS,
  TOAST_DURATION_MS,
  achievementProgressText,
  buildAchievementSharePayload,
  groupByCategory,
  resolveHighlightCode,
  savePendingAchievementCode,
  shouldRefresh,
  unlockedDateLabel
} from '../../utils/achievement'

/**
 * 我的成就页（需求 9.2~9.11、9.13、9.16、8.10~8.14），同时是成就分享卡片的落地页。
 *
 * 状态机照抄成长页的既有范式，一字不改地沿用两条守卫：
 * - `seq` 请求序号：重试 / 下拉刷新时丢弃迟到的旧响应，避免旧结果覆盖新结果。
 * - `withTimeout` 客户端超时（10000ms，需求 9.7）：底层请求仍会跑完，靠序号守卫忽略其迟到结果。
 *
 * 数据态三者互斥：`loading | ready | error`。
 *   LOADING：只有加载中指示，不展示失败文案、不展示任何成就项（需求 9.16）。
 *   ERROR：**只有**失败文案 + 重试胶囊，绝不渲染任何成就项与「已解锁数 / 总数」计数（需求 9.7）。
 *     理由与成长页一致：一个显示「0 / 16」的失败页会让用户以为成就被清空了，比明说加载失败糟糕得多。
 *   READY：按 groupByCategory 分组渲染，组标题取服务端下发的 category 中文名（需求 9.3）。
 * 第四个取值 `guest` 不是数据态，而是「未登录经分享卡片进入」的分支：它一条请求都不发
 *   （需求 8.13），只展示登录引导并把 code 暂存，因此不与上面三者争用同一块渲染区域。
 *
 * 三条硬性约束：
 * 1. 不展示成就编码、统计口径枚举取值与成就事件 id（需求 9.6）。模板里 code 只出现在
 *    `:id` 上供滚动定位用，不作为文本渲染。
 * 2. 已解锁 → 品牌绿图标 + 解锁日期、无进度文案；未解锁 → 灰度 #c7ccd2 + `current / target`、
 *    无解锁日期（需求 9.4、9.5）。这两条互斥判定全部下沉到 utils/achievement.js 的
 *    achievementProgressText / unlockedDateLabel，页面不重复判定，避免两处规则漂移。
 * 3. 复用既有 .page / .sect / .card / .row / .fail-card / .retry 样式类与品牌绿 #12a150、
 *    浅绿底 #e7f7ee、图标灰 #c7ccd2，不新增第二套颜色体系（需求 9.13）。
 */

const auth = useAuthStore()

const state = ref('loading') // loading | ready | error（+ guest：未登录分支，见上）
const achievements = ref([])
const unlockedCount = ref(0)
const total = ref(ACHIEVEMENT_TOTAL)

// 距上次成就清单请求发出的时刻（毫秒），供下拉刷新的 3000ms 节流判定（需求 9.10）。
let lastRequestAt = 0
// 请求序号：丢弃迟到响应（沿用成长页 / 邀请页写法）。
let seq = 0
// 本次启动带进来的原始 code 参数（未解码、未裁剪）；解析交给 resolveHighlightCode。
let launchCode = null

/** 客户端超时：底层请求仍会跑完，靠序号守卫忽略其迟到结果（需求 9.7）。 */
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

/**
 * 拉取成就清单。
 * @param {boolean} isRefresh true 表示下拉刷新（成功才整体替换取值、失败保留旧值、
 *   两种情况都结束下拉动效）；false 表示首屏加载或点重试（先切 LOADING，失败切 ERROR）。
 */
async function load(isRefresh = false) {
  const s = ++seq
  if (!isRefresh) state.value = 'loading'
  lastRequestAt = Date.now()
  try {
    const res = await withTimeout(fetchAchievements(), LIST_TIMEOUT_MS)
    if (s !== seq) return
    // 成功后以本次响应整体替换已展示的成就项与两个计数（需求 9.9）。
    achievements.value = Array.isArray(res?.achievements) ? res.achievements : []
    unlockedCount.value = countOf(res?.unlockedCount)
    total.value = countOf(res?.total) || ACHIEVEMENT_TOTAL
    state.value = 'ready'
    applyHighlight()
    // 播报挂载点 ③（需求 7.2）：清单请求成功后立即触发一次待播报查询与解锁播报，
    // 远快于 1000ms 上限。同步返回、不 await：播报的成败与快慢不影响本页任何展示。
    // 编排状态机、幂等守卫与游标推进都在 utils/achievementBroadcast.js 里。
    startAchievementBroadcast()
  } catch (e) {
    if (s !== seq) return
    // 首屏 / 重试失败或 10000ms 超时 → ERROR（不渲染任何成就项与计数，且不自动重发，需求 9.7）；
    // 下拉刷新失败 → 保留旧的 READY 取值，仅结束动效（需求 9.7 的「结束进行中的下拉刷新动效」）。
    if (!isRefresh) state.value = 'error'
  } finally {
    // 请求返回或超时后一律结束下拉动效，避免动效卡死（需求 9.9、9.10）。
    if (isRefresh) uni.stopPullDownRefresh()
  }
}

/** 计数字段兜底：非数字 / NaN / 负数一律折成 0，避免渲染出「-1 / 16」。 */
function countOf(n) {
  const v = Number(n)
  if (!Number.isFinite(v) || v <= 0) return 0
  return Math.floor(v)
}

onLoad((query) => {
  // 分享落地：原始 code 先留着，解码 + 裁剪 + 长度校验 + 清单比对统一由
  // resolveHighlightCode 在拿到清单响应后做一次（需求 8.10、8.12）。
  launchCode = query && query.code !== undefined && query.code !== null ? query.code : null

  if (!auth.isLoggedIn) {
    // 未登录经分享卡片进入：展示登录引导、一条清单请求都不发、把 code 暂存（需求 8.13、9.14）。
    // 暂存失败也照常展示登录引导，绝不阻断登录主路径。
    state.value = 'guest'
    if (launchCode !== null) savePendingAchievementCode(launchCode)
    return
  }
  load(false)
})

onPullDownRefresh(() => {
  // 不满 3000ms：不发请求、不提示错误，1000ms 内结束动效，页面取值一行不动（需求 9.10）。
  if (!shouldRefresh(lastRequestAt, Date.now())) {
    uni.stopPullDownRefresh()
    return
  }
  load(true)
})

/** ERROR 态重试：切 LOADING 后重试胶囊随 ERROR 卡一起卸载，因此返回前不可能被再次触发（需求 9.8）。 */
function retry() {
  if (state.value === 'loading') return
  load(false)
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

// ---- 分组渲染（仅在 state === 'ready' 时使用）----

/**
 * 组的顺序取分类在响应中的首现顺序、组内项的顺序取响应中的相对顺序，
 * 两者都由 groupByCategory 保序（服务端清单已保证「起步 / 坚持 / 积累 / 协作 / 主题」，
 * 前端不重排、也不在本地维护第二份分类定义，需求 9.3）。
 */
const groups = computed(() => groupByCategory(achievements.value))

// ---- 分享落地高亮（需求 8.11、8.12）----

const highlightCode = ref('')
let highlightTimer = null

/**
 * 命中则 1000ms 内滚动到该项并高亮，3000ms 后恢复默认样式；
 * 不命中（无 code / 空白 / 超 64 字符 / 不在清单内）展示无高亮的默认页且不提示错误。
 *
 * 与该项是否已解锁无关（需求 8.11 后半句）：未解锁项被分享出去也照样滚动 + 高亮。
 */
function applyHighlight() {
  const code = resolveHighlightCode(launchCode, achievements.value)
  // 一次性消费：清单已经拿到手，命中与否都已判定完毕。留着它会让后续每次下拉刷新
  // 都把页面拽回同一项并再高亮一遍，那不是需求 8.11 要的「落地时」行为。
  launchCode = null
  clearHighlightTimer()
  highlightCode.value = code || ''
  if (!code) return

  // 等列表渲染完再查节点位置；pageScrollTo 在任一平台失败都静默放过——
  // 高亮已经生效，滚不动至多是用户要自己划一下，绝不因此弹错误提示。
  nextTick(() => {
    try {
      uni.pageScrollTo({ selector: `#a-${code}`, duration: 300, fail: () => {} })
    } catch (e) {
      /* 平台不支持 selector 时忽略 */
    }
  })

  highlightTimer = setTimeout(() => {
    highlightCode.value = ''
    highlightTimer = null
  }, HIGHLIGHT_MS)
}

function clearHighlightTimer() {
  if (highlightTimer) {
    clearTimeout(highlightTimer)
    highlightTimer = null
  }
}

onUnmounted(clearHighlightTimer)

// ---- 解锁播报挂载点 ③（需求 7.2、7.13、7.16、8.1）----
// 弹层的可见性与当前项是 utils/achievementBroadcast.js 的模块级状态，三处挂载点共用同一份，
// 因此这里只做绑定与事件转发，一行编排逻辑都不重复。

/**
 * 弹层内「分享给好友」（需求 7.13、8.1）：弹层保持展示，只记下转发目标。
 * 真正的转发面板由弹层里 `open-type="share"` 的 button 唤起，最终落到本页的
 * onShareAppMessage —— 它优先读 `res.target.dataset.code`，pendingShareCode 是兜底。
 */
function onBroadcastShare(item) {
  pendingShareCode.value = String((item && item.code) || '')
}

/**
 * 弹层内「保存卡片」（需求 8.1 后半句）：复用本页既有的 saveCard，一份 canvas 实现两处入口。
 * 待播报项没有 `unlocked` 字段（它本身即已解锁），因此先在清单里按编码取那一项；
 * 取不到（清单尚未刷新到这一枚）就补上 `unlocked: true` 再交给 saveCard，
 * 否则会被它的未解锁守卫挡成「该成就尚未解锁」。
 */
function onBroadcastSave(item) {
  if (!item) return
  const a = findByCode(item.code) || { ...item, unlocked: true }
  saveCard(a)
}

onUnload(() => {
  // 弹层若正展示在本页而本页要卸载：放弃本次播报并释放守卫，不发游标推进请求，
  // 这些成就留在待播报集合内，下次触发照常播报（需求 7.11 的至少一次语义）。
  releaseAchievementBroadcastOnLeave()
})

// ==========================================================================
// 成就分享与成就卡片（需求 8.1~8.9、8.15、8.16）
// 本区块是全仓第一处 canvas 绘图：`createCanvasContext` / `canvasToTempFilePath`
// 在本次改动之前零命中，因此没有既有范本可抄。能沿用的只有 pages/invite/invite.vue
// 里 saveImageToPhotosAlbum 的收尾与授权失败处理（成功 / 拒绝授权 / 其它失败三分支
// 与那里逐字一致），canvas 部分是新写的。
//
// ⚠️ 环境可验证性说明（写给后续维护者，也是任务 10.2 手工清单的依据）：
// 以下三点在当前开发环境（node + vitest + H5 构建）里**无法验证**，必须真机 / 微信
// 开发者工具确认，任务 10.2 已列入清单：
//   1. `<canvas type="2d">` 的基础库支持度与 `createSelectorQuery().fields({node:true})`
//      能否取到 node —— H5 与低版本基础库上取不到 node，本实现在这种情况下降级为
//      「保存失败，请稍后重试」而不是抛错崩页（见 queryCanvasNode 的 NO_CANVAS 分支）。
//   2. 2x / 3x 屏下卡片是否模糊 —— 靠 pixelRatio 放大位图 + ctx.scale 抵消，
//      放大倍数与真机像素密度的实际关系只能在真机上看。
//   3. 相册授权的三条路径（首次询问 / 拒绝后再触发 / 系统设置里改回来）——
//      `uni.getSetting` / `uni.authorize` / `uni.openSetting` 在 H5 上都不存在，
//      因此这里对「拿不到 getSetting」的环境一律直接尝试保存、由保存分支给提示。
// ==========================================================================

/** 成就卡片的逻辑尺寸（CSS px）；真实位图按屏幕像素密度放大，见 drawCard。 */
const CARD_W = 560
const CARD_H = 720
const cardCssSize = `width:${CARD_W}px;height:${CARD_H}px`

/**
 * 从绘制开始起算的保存耗时上界（需求 8.8）。
 *
 * 刻意从「开始绘制」起算而不是从「用户点按」起算，与 design.md 的六步流程第 ⑥ 步一致：
 * 点按到开始绘制之间可能夹着一次系统授权弹窗，那段时间完全取决于用户什么时候点「允许」，
 * 把它算进 3000ms 会让首次授权几乎必然超时——用户点了允许却被告知保存失败，是更糟的结果。
 */
const CARD_SAVE_TIMEOUT_MS = 3000

/** 幂等守卫（需求 8.15）：绘制或写相册尚未结束时再次触发一律丢弃，不发起第二次绘制、不叠加提示。 */
const saving = ref(false)

const instance = getCurrentInstance()

function toast(title, icon = 'none') {
  uni.showToast({ title, icon, duration: TOAST_DURATION_MS })
}

/** 未解锁提示（需求 8.2）：不绘制、不写相册、不转发。 */
function notUnlockedToast() {
  toast('该成就尚未解锁')
}

function isUnlocked(a) {
  return !!a && a.unlocked === true
}

function findByCode(code) {
  const key = code === null || code === undefined ? '' : String(code)
  if (!key) return null
  return achievements.value.find((a) => a && String(a.code) === key) || null
}

/**
 * 未解锁项整行可点（需求 8.2 的提示分支）：已解锁项的两个操作按钮自己处理点击，
 * 因此这里只对未解锁项出提示，不做任何跳转。
 */
function onAchievementTap(a) {
  if (!isUnlocked(a)) notUnlockedToast()
}

// ---- 分享给好友（需求 8.1、8.3）----

// 本次点按的分享目标编码。open-type="share" 的按钮在微信端会同时触发 @click 与
// onShareAppMessage，两条路径都能拿到目标：dataset 是首选（微信明确保证 res.target），
// 这个 ref 是 dataset 缺失时的兜底。
const pendingShareCode = ref('')

function shareAchievement(a) {
  if (!isUnlocked(a)) {
    // 已解锁项才渲染分享按钮，这里只是防御：模板改动或平台差异导致按钮误现时不转发。
    notUnlockedToast()
    return
  }
  pendingShareCode.value = String(a.code || '')
}

onShareAppMessage((res) => {
  const fromDataset = res && res.target && res.target.dataset ? res.target.dataset.code : ''
  const target = findByCode(fromDataset || pendingShareCode.value)
  pendingShareCode.value = ''
  // 未解锁项与「右上角菜单直接转发」（无具体成就）都降级为不带 code 的成就页路径，
  // 标题仍含产品名「有余」且长度 ≤30 —— 构造逻辑全在纯函数里，页面只做回调壳。
  const payload = buildAchievementSharePayload(isUnlocked(target) ? target : null)
  return { title: payload.title, path: payload.path }
})

// ---- 保存成就卡片到相册（需求 8.4~8.8、8.15、8.16）----

/**
 * design.md 六步流程的入口：
 * ① 离屏 canvas 已在模板里 → ② createSelectorQuery 取 node + getContext('2d')
 * → ③ 绘制四项 → ④ canvasToTempFilePath → ⑤ saveImageToPhotosAlbum → ⑥ 3000ms 兜底。
 * 授权判定排在 ② 之前，使「此前已拒绝过」的情形一次绘制都不发起（需求 8.16）。
 */
async function saveCard(a) {
  if (!isUnlocked(a)) {
    notUnlockedToast()
    return
  }
  if (saving.value) return // 需求 8.15
  saving.value = true
  try {
    const authorized = await ensureAlbumAuth()
    if (!authorized) return // 提示已在 ensureAlbumAuth 内给出，停留当前页、清单取值不变
    await drawAndSave(a)
  } catch (e) {
    const reason = (e && (e.reason || e.code)) || ''
    // 拒绝授权与其它失败分开提示，与 invite 页保存二维码的分支一致。
    toast(reason === 'DENIED' ? '需要相册权限才能保存' : '保存失败，请稍后重试')
  } finally {
    // 无论成功、失败还是超时都释放守卫，使用户可以再次触发（需求 8.8 末句）。
    saving.value = false
  }
}

/**
 * 相册写入授权（需求 8.7、8.16）。
 * - 未询问过（undefined）→ 先发起一次授权请求；被拒 → 提示需要授权、返回 false。
 * - 此前已拒绝（false）→ 提示 + 「打开设置」，**不发起绘制**。
 * - 已授予（true）→ 直接放行。
 * - 环境没有 getSetting（H5）→ 放行，由保存分支给提示；不能因为查不到授权状态就拒绝保存。
 */
async function ensureAlbumAuth() {
  const authSetting = await getAlbumSetting()
  if (authSetting === null) return true
  const granted = authSetting['scope.writePhotosAlbum']
  if (granted === true) return true
  if (granted === false) {
    promptOpenSetting()
    return false
  }
  const ok = await requestAlbumAuth()
  if (!ok) toast('需要相册权限才能保存')
  return ok
}

function getAlbumSetting() {
  return new Promise((resolve) => {
    if (typeof uni.getSetting !== 'function') return resolve(null)
    try {
      uni.getSetting({
        success: (r) => resolve((r && r.authSetting) || {}),
        fail: () => resolve(null)
      })
    } catch (e) {
      resolve(null)
    }
  })
}

function requestAlbumAuth() {
  return new Promise((resolve) => {
    if (typeof uni.authorize !== 'function') return resolve(true)
    try {
      uni.authorize({
        scope: 'scope.writePhotosAlbum',
        success: () => resolve(true),
        fail: () => resolve(false)
      })
    } catch (e) {
      resolve(false)
    }
  })
}

/** 需求 8.16：提示文案与「打开系统设置」的操作同时给出；用户不去设置就停在当前页。 */
function promptOpenSetting() {
  uni.showModal({
    title: '需要相册权限',
    content: '需要相册权限才能保存成就卡片，可在系统设置里开启',
    confirmText: '打开设置',
    cancelText: '暂不',
    success: (r) => {
      if (r && r.confirm && typeof uni.openSetting === 'function') {
        uni.openSetting({ fail: () => {} })
      }
    },
    fail: () => {}
  })
}

/**
 * 绘制 → 导出 → 写相册。3000ms 从本函数进入时起算（需求 8.8）。
 *
 * 超时后**不写相册**：withTimeout 只负责让 await 提前失败，底层 canvas 回调仍可能迟到，
 * 因此额外用 expired 标志在写相册之前再挡一次——否则一次「已超时并提示失败」的保存
 * 仍可能在几百毫秒后偷偷写进相册，用户会收到两条互相矛盾的结果。
 */
async function drawAndSave(a) {
  let expired = false
  const timer = setTimeout(() => {
    expired = true
  }, CARD_SAVE_TIMEOUT_MS)
  try {
    const filePath = await withTimeout(renderCardToTempFile(a), CARD_SAVE_TIMEOUT_MS)
    if (expired) throw { reason: 'TIMEOUT' }
    await saveToAlbum(filePath)
    uni.showToast({ title: '已保存到相册', icon: 'success', duration: TOAST_DURATION_MS })
  } finally {
    clearTimeout(timer)
  }
}

async function renderCardToTempFile(a) {
  const node = await queryCanvasNode()
  drawCard(node, a)
  return canvasToTempFile(node)
}

/** ② 取离屏 canvas 的 node（`type="2d"` 的取法：fields({ node: true })）。 */
function queryCanvasNode() {
  return new Promise((resolve, reject) => {
    let query
    try {
      query = uni.createSelectorQuery()
      // 作用域限定到本页面实例；缺少 in() 或实例时退回全局查询（H5 上没有 in 的语义）。
      if (instance && instance.proxy && typeof query.in === 'function') query = query.in(instance.proxy)
    } catch (e) {
      return reject({ reason: 'NO_CANVAS' })
    }
    try {
      query
        .select('#achv-card')
        .fields({ node: true, size: true })
        .exec((res) => {
          const node = res && res[0] && res[0].node
          // 拿不到 node 的环境（H5、过低的基础库）一律走失败提示，不抛异常炸页面。
          if (node && typeof node.getContext === 'function') resolve(node)
          else reject({ reason: 'NO_CANVAS' })
        })
    } catch (e) {
      reject({ reason: 'NO_CANVAS' })
    }
  })
}

/** 屏幕像素密度：位图按它放大再用 ctx.scale 抵消，避免 2x / 3x 屏上卡片发虚。 */
function pixelRatio() {
  try {
    const info = typeof uni.getWindowInfo === 'function' ? uni.getWindowInfo() : uni.getSystemInfoSync()
    const r = Number(info && info.pixelRatio)
    // 上限 3：再高也看不出差别，位图面积却继续平方增长。
    return Number.isFinite(r) && r > 0 ? Math.min(r, 3) : 2
  } catch (e) {
    return 2
  }
}

/**
 * ③ 绘制。卡片上**恰好四项**：成就展示名称、成就描述、精确到自然日的解锁日期、产品名「有余」
 * （需求 8.4）。金额、邮箱、邀请码、账本名称与任何其它用户的标识一律不画（需求 8.5）——
 * 本函数的入参只有一枚成就视图，页面也不向它传入别的数据源，这条约束因此是结构性的。
 */
function drawCard(node, a) {
  const ctx = node.getContext('2d')
  if (!ctx) throw { reason: 'NO_CANVAS' }
  const dpr = pixelRatio()
  node.width = CARD_W * dpr
  node.height = CARD_H * dpr
  // 设置 width / height 会重置变换矩阵，因此 scale 必须写在赋值之后。
  ctx.scale(dpr, dpr)

  // 品牌绿渐变底（与页面计数条同一组绿，不新增第二套颜色体系）。
  const g = ctx.createLinearGradient(0, 0, CARD_W, CARD_H)
  g.addColorStop(0, '#22c55e')
  g.addColorStop(1, '#0f8a45')
  ctx.fillStyle = g
  ctx.fillRect(0, 0, CARD_W, CARD_H)

  // 白色内卡
  ctx.fillStyle = '#ffffff'
  roundRect(ctx, 40, 96, CARD_W - 80, CARD_H - 192, 28)
  ctx.fill()

  // 徽章底圆（纯装饰）
  ctx.beginPath()
  ctx.arc(CARD_W / 2, 232, 56, 0, Math.PI * 2)
  ctx.fillStyle = '#e7f7ee'
  ctx.fill()

  ctx.textAlign = 'center'

  // 第 1 项：成就展示名称
  ctx.fillStyle = '#25292e'
  ctx.font = '600 44px sans-serif'
  ctx.fillText(String(a.name || '新成就').slice(0, 12), CARD_W / 2, 360)

  // 第 2 项：成就描述（按测量宽度折行，至多 3 行，超出以 … 收尾）
  ctx.fillStyle = '#6b7280'
  ctx.font = '26px sans-serif'
  const lines = wrapText(ctx, String(a.description || ''), CARD_W - 160, 3)
  lines.forEach((line, i) => ctx.fillText(line, CARD_W / 2, 424 + i * 40))

  // 第 3 项：精确到自然日的解锁日期（复用页面同一个纯函数，日期口径只有一处定义）
  const date = unlockedDateLabel(a)
  if (date) {
    ctx.fillStyle = '#12a150'
    ctx.font = '600 28px sans-serif'
    ctx.fillText(`${date} 解锁`, CARD_W / 2, 566)
  }

  // 第 4 项：产品名
  ctx.fillStyle = '#ffffff'
  ctx.font = '600 32px sans-serif'
  ctx.fillText('有余', CARD_W / 2, CARD_H - 52)
}

function roundRect(ctx, x, y, w, h, r) {
  ctx.beginPath()
  ctx.moveTo(x + r, y)
  ctx.arcTo(x + w, y, x + w, y + h, r)
  ctx.arcTo(x + w, y + h, x, y + h, r)
  ctx.arcTo(x, y + h, x, y, r)
  ctx.arcTo(x, y, x + w, y, r)
  ctx.closePath()
}

/**
 * 逐字符折行：中文没有空格可断，按 measureText 累加宽度是唯一可靠的办法。
 * 超过 maxLines 时在末行尾部加 …，绝不让文本溢出卡片边界。
 */
function wrapText(ctx, text, maxWidth, maxLines) {
  const chars = Array.from(text)
  const lines = []
  let line = ''
  for (const ch of chars) {
    const next = line + ch
    if (line && measure(ctx, next) > maxWidth) {
      lines.push(line)
      line = ch
      if (lines.length === maxLines) break
    } else {
      line = next
    }
  }
  if (lines.length < maxLines && line) lines.push(line)
  if (lines.length === maxLines && chars.length > lines.join('').length) {
    lines[maxLines - 1] = lines[maxLines - 1].slice(0, -1) + '…'
  }
  return lines
}

/** measureText 在个别环境下可能缺失，退化为按字号估宽，宁可略宽也不抛错。 */
function measure(ctx, text) {
  try {
    const m = ctx.measureText(text)
    const w = Number(m && m.width)
    if (Number.isFinite(w) && w > 0) return w
  } catch (e) {
    /* 落到估算 */
  }
  return Array.from(text).length * 26
}

/** ④ 导出临时文件。`type="2d"` 必须传 canvas 节点本身，不能只传 canvasId。 */
function canvasToTempFile(node) {
  return new Promise((resolve, reject) => {
    try {
      uni.canvasToTempFilePath({
        canvas: node,
        canvasId: 'achv-card',
        success: (r) => {
          const p = r && r.tempFilePath
          if (p) resolve(p)
          else reject({ reason: 'EXPORT_FAILED' })
        },
        fail: () => reject({ reason: 'EXPORT_FAILED' })
      })
    } catch (e) {
      reject({ reason: 'EXPORT_FAILED' })
    }
  })
}

/** ⑤ 写相册。拒绝授权与其它失败分开，收尾与 invite 页保存二维码一致。 */
function saveToAlbum(filePath) {
  return new Promise((resolve, reject) => {
    try {
      uni.saveImageToPhotosAlbum({
        filePath,
        success: () => resolve(true),
        fail: (err) => {
          const msg = String((err && (err.errMsg || err.message)) || '')
          reject({ reason: /deny|auth/i.test(msg) ? 'DENIED' : 'SAVE_FAILED' })
        }
      })
    } catch (e) {
      reject({ reason: 'SAVE_FAILED' })
    }
  })
}
</script>

<template>
  <view class="page">
    <!-- LOADING：加载中指示，不展示失败文案与任何成就项（需求 9.16） -->
    <view v-if="state === 'loading'" class="fail-card">
      <text class="f-d">正在加载成就数据…</text>
    </view>

    <!-- ERROR：只有失败文案 + 重试胶囊，绝不渲染任何成就项与计数（需求 9.7） -->
    <view v-else-if="state === 'error'" class="fail-card">
      <AppIcon name="warning" :size="52" color="#c7ccd2" />
      <text class="f-t">成就数据加载失败</text>
      <text class="f-d">网络不太顺畅，稍后再试一次</text>
      <text class="retry" @click="retry">重试</text>
    </view>

    <!-- GUEST：未登录经分享卡片进入，只有登录引导，不发任何请求（需求 8.13） -->
    <view v-else-if="state === 'guest'" class="fail-card">
      <AppIcon name="badge" :size="52" color="#12a150" />
      <text class="f-t">登录后查看你的成就</text>
      <text class="f-d">记账、坚持、储蓄都会变成成就，登录即可继续</text>
      <text class="retry" @click="goLogin">去登录</text>
    </view>

    <!-- READY：计数条 + 按分类分组的 16 枚成就 -->
    <template v-else>
      <view class="sum">
        <text class="sum-v">{{ unlockedCount }} / {{ total }}</text>
        <text class="sum-k">已解锁成就</text>
      </view>

      <template v-for="g in groups" :key="g.category">
        <!-- 组标题：服务端下发的分类中文展示名，前端不本地映射（需求 9.3） -->
        <view class="sect">{{ g.category }}</view>
        <view class="card">
          <!--
            成就项与它的两个操作必须落在同一个 v-for 作用域里：`.acts` 的 v-if 读的是循环变量 a，
            放在循环外面拿不到它（编译成 _ctx.a → undefined），两个入口会一枚都不渲染。
          -->
          <template v-for="a in g.items" :key="a.code">
            <!-- :id 只用于分享落地滚动定位，成就编码不作为文本渲染（需求 9.6） -->
            <view
              :id="'a-' + a.code"
              class="row"
              :class="{ locked: !a.unlocked, hl: highlightCode === a.code }"
              @click="onAchievementTap(a)"
            >
              <view class="r-ic" :class="a.unlocked ? 't-green' : ''">
                <AppIcon name="badge" :size="36" :color="a.unlocked ? '#12a150' : '#c7ccd2'" />
              </view>
              <view class="r-m">
                <text class="r-n">{{ a.name }}</text>
                <text class="r-d">{{ a.description }}</text>
              </view>
              <!-- 已解锁：解锁日期，无进度文案（需求 9.4）；未解锁：current / target，无日期（需求 9.5） -->
              <text v-if="a.unlocked" class="r-v v-on">{{ unlockedDateLabel(a) }}</text>
              <text v-else class="r-v">{{ achievementProgressText(a) }}</text>
            </view>
            <!--
              分享与保存两个操作只出现在已解锁项上（需求 8.1、8.2）。
              分享必须是 open-type="share" 的 button：小程序里只有它能唤起转发面板，
              普通 view 的点击拿不到转发能力。data-code 供 onShareAppMessage 从
              res.target.dataset 认出是哪一枚成就。
            -->
            <view v-if="a.unlocked" class="acts">
              <button class="act" open-type="share" :data-code="a.code" @click="shareAchievement(a)">
                分享给好友
              </button>
              <text class="act" @click="saveCard(a)">保存卡片到相册</text>
            </view>
          </template>
        </view>
      </template>

      <view style="height: 60rpx"></view>
    </template>

    <!--
      解锁弹层（挂载点 ③，需求 7.4）：可见性与当前项来自 utils/achievementBroadcast.js 的模块级状态，
      放在三态之外——它与本页数据态无关，ERROR / GUEST 态下若还有播报在跑也照样能展示。
    -->
    <AchievementUnlockModal
      :visible="broadcastVisible"
      :achievement="broadcastItem"
      @update:visible="closeBroadcastModal"
      @enter="enterAchievementPageFromBroadcast"
      @share="onBroadcastShare"
      @save="onBroadcastSave"
    />

    <!--
      离屏成就卡片画布（design.md 六步流程的第 ① 步）：定位到屏幕外，不参与页面布局，
      也不随三态切换而卸载——保存时必须能立刻查到这个节点。
      `type="2d"` 与 `id` 是新版 canvas 的取节点方式；`canvas-id` 保留给旧接口做兼容。
    -->
    <canvas
      id="achv-card"
      canvas-id="achv-card"
      type="2d"
      class="card-canvas"
      :style="cardCssSize"
    ></canvas>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f2f4f6;
  padding: 24rpx 24rpx 0;
}
/* 失败 / 加载 / 登录引导卡（复用成长页既有观感） */
.fail-card {
  background: #fff;
  border-radius: 24rpx;
  padding: 52rpx 30rpx;
  text-align: center;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
}
.fail-card .f-t {
  font-size: 30rpx;
  font-weight: 700;
  color: #25292e;
}
.fail-card .f-d {
  font-size: 24rpx;
  color: #9aa2ad;
  line-height: 1.6;
}
.retry {
  margin-top: 10rpx;
  font-size: 26rpx;
  color: #12a150;
  font-weight: 600;
  border: 1rpx solid #12a150;
  border-radius: 999rpx;
  padding: 8rpx 32rpx;
}
/* 计数条 */
.sum {
  background: linear-gradient(135deg, #22c55e, #0f8a45 72%);
  border-radius: 28rpx;
  padding: 36rpx 34rpx;
  color: #fff;
  box-shadow: 0 20rpx 40rpx rgba(18, 161, 80, 0.28);
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.sum-v {
  font-size: 58rpx;
  font-weight: 800;
  line-height: 1.1;
}
.sum-k {
  font-size: 24rpx;
  opacity: 0.92;
}
/* 分组标题与卡片（复用成长页 / 我的页既有样式类） */
.sect {
  font-size: 24rpx;
  color: #9aa2ad;
  padding: 30rpx 8rpx 12rpx;
}
.card {
  background: #fff;
  border-radius: 20rpx;
  overflow: hidden;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
}
.row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 26rpx 28rpx;
  border-top: 1rpx solid #eef0f2;
  transition: background-color 0.3s ease;
}
.card .row:first-child {
  border-top: none;
}
.r-ic {
  width: 60rpx;
  height: 60rpx;
  border-radius: 16rpx;
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}
.r-ic.t-green {
  background: #e7f7ee;
}
.r-m {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.r-n {
  font-size: 30rpx;
  color: #25292e;
  font-weight: 600;
}
.row.locked .r-n {
  color: #9aa2ad;
  font-weight: 500;
}
.r-d {
  font-size: 23rpx;
  color: #9aa2ad;
  line-height: 1.5;
}
.r-v {
  font-size: 23rpx;
  color: #9aa2ad;
  flex: 0 0 auto;
  margin-left: 4rpx;
}
.r-v.v-on {
  color: #12a150;
}
/* 分享落地高亮：浅绿底 + 品牌绿描边，3000ms 后恢复默认样式（需求 8.11） */
.row.hl {
  background: #e7f7ee;
  box-shadow: inset 0 0 0 2rpx #12a150;
}
/* 已解锁项的两个操作：分享给好友 / 保存卡片到相册（需求 8.1） */
.acts {
  display: flex;
  justify-content: flex-end;
  gap: 16rpx;
  padding: 0 28rpx 24rpx;
}
/* button 与 text 共用一套胶囊样式，故把 button 的默认样式全部抹平 */
.act {
  margin: 0;
  padding: 8rpx 24rpx;
  font-size: 23rpx;
  line-height: 1.6;
  color: #12a150;
  background: #e7f7ee;
  border: none;
  border-radius: 999rpx;
  font-weight: 600;
}
.act::after {
  border: none;
}
/* 离屏画布：移出可视区域，不占布局位置（需求 8.4 的绘制载体） */
.card-canvas {
  position: fixed;
  left: -9999rpx;
  top: 0;
}
</style>
