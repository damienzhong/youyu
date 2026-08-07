<script setup>
import { computed, ref } from 'vue'
import { onLoad, onShow, onShareAppMessage } from '@dcloudio/uni-app'
import { useAuthStore } from '../../stores/auth'
import { useLedgerStore } from '../../stores/ledger'
import { shareCard } from '../../api/shareCard'
import {
  SHARE_CARD_TIMEOUT_MS,
  DEFAULT_LOGO_MAX_AREA_RATIO,
  isLedgerScoped,
  resolveCardState,
  cardToDisplay,
  computeCardLayout
} from '../../utils/shareCard'

/**
 * 分享卡片渲染页（share-card 需求 1.9、2.1、2.5、2.6、11、12.6）。
 *
 * 从报表页（MONTHLY_SUMMARY / ANNUAL_BILL / BUDGET_ACHIEVED）、连续记账 / 成长 / 成就页
 * （STREAK_MILESTONE / LEVEL_UP / ACHIEVEMENT_BADGE）以 cardType（+ 可选周期/编码）跳入。
 * 账本相关卡片在「全部账本」聚合视图下不提供入口（需求 1.9、11.8）。
 *
 * 职责与既有范式复用：
 * - 数据加载与静默降级：复用 utils/shareCard.js 的 resolveCardState / raceWithTimeout
 *   （5000ms 超时静默降级，不弹阻断性弹窗、不阻断当前页其余交互，需求 11.9）；请求期间
 *   切换账本/周期使响应过期 → 丢弃不覆盖（seq 序号守卫）。
 * - 卡片渲染：复用 utils/digest.js 的 canvas 绘制范式（createCanvasContext + ctx.draw +
 *   canvasToTempFilePath），绘制六元素：文字头像、昵称、标签（有则显示）、一句 AI 文案、
 *   核心数据（主视觉）、小尺寸品牌 Logo（按 computeCardLayout 置于一角、面积 ≤ 5%、不置视觉
 *   中心）；不绘制任何促销/下载引导/二维码（需求 2.1、2.5、2.6、11.1）。
 * - 保存/转发：复用 report.vue / 成就页范式——canvasToTempFilePath 出图、saveImageToPhotosAlbum
 *   保存、showShareImageMenu / onShareAppMessage 转发（需求 11.2、11.3）。
 * - 相册授权：未授予先请求授权，拒绝 → 展示需授权提示、不写入、停留当前页、不进入错误态；
 *   写入成功 → 展示成功提示并停留（需求 11.4、11.5）。
 * - 出图/保存 3000ms 超时 → 结束本次操作、展示失败提示、停留当前页、允许再次触发（需求 11.6）。
 * - available=false 不提供出图/保存/分享入口，触发相关入口 → 展示「暂不可用」、不发起 canvas
 *   绘制/写相册/转发（需求 11.7）。
 * - 未登录 → 不请求不展示、展示登录入口（需求 11.8）。
 *
 * 隐私（需求 12.6）：canvas 只从 cardToDisplay 白名单展示项绘制（头像种子、昵称、标签、
 * 一句 AI 文案、按类型选取的核心数值、品牌名），绝不引用邮箱/令牌/其它账本数据。
 */

const auth = useAuthStore()
const ledgerStore = useLedgerStore()

/** 6 种合法卡片类型（区分大小写）；非法类型不请求、不展示（需求 1.1）。 */
const VALID_CARD_TYPES = new Set([
  'STREAK_MILESTONE',
  'MONTHLY_SUMMARY',
  'ANNUAL_BILL',
  'ACHIEVEMENT_BADGE',
  'BUDGET_ACHIEVED',
  'LEVEL_UP'
])

/** 卡片类型 → 页面标题（仅展示用，不影响取数）。 */
const CARD_TITLES = {
  STREAK_MILESTONE: '连续记账里程碑',
  MONTHLY_SUMMARY: '本月总结',
  ANNUAL_BILL: '年度账单',
  ACHIEVEMENT_BADGE: '获得徽章',
  BUDGET_ACHIEVED: '预算达成',
  LEVEL_UP: '成长升级'
}

// 卡片逻辑尺寸（与离屏 canvas 一致）。
const CARD_W = 600
const CARD_H = 800
const POSTER_CANVAS_ID = 'shareCard'
const posterCssSize = `width:${CARD_W}px;height:${CARD_H}px`
// Logo 面积占比上限（与后端 ShareCardProperties.logoMaxAreaRatio 默认值一致，需求 2.5）。
const LOGO_MAX_AREA_RATIO = DEFAULT_LOGO_MAX_AREA_RATIO

/**
 * 出图/保存从触发起算的耗时上界（需求 11.6）：3000ms 内渲染与相册写入未全部完成即
 * 结束本次操作、提示失败、停留当前页、允许再次触发。
 */
const CARD_OP_TIMEOUT_MS = 3000

// 页面状态：loading | ready（可用可出图）| unavailable（卡片不可用）| hidden（静默降级/聚合视图）| guest（未登录）
const pageState = ref('loading')
const card = ref(null)
const cardVisible = ref(false)
// 幂等守卫（需求 11.6 末句）：出图/保存/转发进行中再次触发一律丢弃。
const busy = ref(false)

// 本次卡片类型与可选周期/标识参数（来自页面跳入 query）。
const cardType = ref('')
const cardParams = ref({})
// 请求序号：请求期间切换账本/周期时丢弃迟到的旧响应（需求 11.9）。
let seq = 0

const isLoggedIn = computed(() => auth.isLoggedIn)
const pageTitle = computed(() => CARD_TITLES[cardType.value] || '分享卡片')
// 白名单展示项（cardToDisplay 只抽取可展示字段，绝不引用邮箱/令牌/其它账本数据，需求 12.6）。
const display = computed(() => (card.value ? cardToDisplay(card.value) : null))

function toast(title, icon = 'none') {
  uni.showToast({ title, icon })
}

/** Promise 超时包装：promise 与超时竞速，任一先结算即结算，随后清理定时器（需求 11.6）。 */
function withTimeout(promise, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject({ code: 'OP_TIMEOUT' }), ms)
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
 * 加载卡片数据并决定页面状态（需求 1.9、11.7、11.8、11.9）。
 * - 未登录：不请求、不展示，进入 guest 展示登录入口（需求 11.8）。
 * - 账本相关卡片处于「全部账本」聚合视图：不请求、不展示（需求 1.9、11.8）。
 * - 非法卡片类型：不请求、静默隐藏。
 * - 请求成功且卡片可用：ready（提供出图/保存/分享入口）。
 * - 请求成功但卡片不可用：unavailable（展示「暂不可用」，隐藏出图/保存/分享入口，需求 11.7）。
 * - 请求失败或 5000ms 超时：hidden 静默降级，不弹阻断性弹窗（需求 11.9）。
 * - 过期（请求期间切换账本/周期）：丢弃迟到响应、不覆盖（需求 11.9）。
 */
async function loadCard() {
  if (!isLoggedIn.value) {
    pageState.value = 'guest'
    return
  }
  if (!VALID_CARD_TYPES.has(cardType.value)) {
    pageState.value = 'hidden'
    return
  }
  // 账本相关卡片在聚合视图下不提供入口（需求 1.9）。
  if (ledgerStore.isAll && isLedgerScoped(cardType.value)) {
    pageState.value = 'hidden'
    return
  }
  const mySeq = ++seq
  pageState.value = 'loading'
  const state = await resolveCardState({
    isLoggedIn: isLoggedIn.value,
    isAll: ledgerStore.isAll,
    cardType: cardType.value,
    fetchCard: () => shareCard(cardType.value, cardParams.value),
    timeoutMs: SHARE_CARD_TIMEOUT_MS,
    isStale: () => mySeq !== seq
  })
  if (state.stale) return
  card.value = state.card
  cardVisible.value = state.cardVisible
  if (!state.requested || !state.card) {
    pageState.value = 'hidden'
  } else if (state.cardVisible) {
    pageState.value = 'ready'
  } else {
    // 请求成功但卡片不可用（available=false）：展示「暂不可用」，隐藏出图/保存/分享入口。
    pageState.value = 'unavailable'
  }
}

// ── canvas 绘制六元素（复用 utils/digest.js 绘制范式；仅从白名单 display 绘制）─────────

/** 逐字符折行（中文无空格可断，按每行字符数上限折行，超出末行以 … 收尾）。 */
function wrapChars(text, perLine, maxLines) {
  const chars = Array.from(String(text || ''))
  const lines = []
  for (let i = 0; i < chars.length; i += perLine) {
    lines.push(chars.slice(i, i + perLine).join(''))
    if (lines.length === maxLines) break
  }
  if (lines.length === maxLines && chars.length > maxLines * perLine) {
    lines[maxLines - 1] = lines[maxLines - 1].slice(0, -1) + '…'
  }
  return lines
}

/**
 * 在离屏 canvas 上绘制分享卡片六元素（需求 2.1、2.5、2.6、11.1）。
 * 只从 disp（cardToDisplay 白名单）绘制：文字头像、昵称、标签、一句 AI 文案、核心数据、
 * 小尺寸品牌 Logo；绝不绘制任何促销/下载引导/二维码，也绝不引用白名单外字段（需求 2.6、12.6）。
 */
function drawShareCard(ctx, disp) {
  const W = CARD_W
  const H = CARD_H
  const cx = W / 2
  // 品牌 Logo 布局：面积 ≤ 卡片可见区域 LOGO_MAX_AREA_RATIO、置于右下角、不落入视觉中心（需求 2.5）。
  const layout = computeCardLayout(W, H, LOGO_MAX_AREA_RATIO)

  // 背景渐变
  const bg = ctx.createLinearGradient(0, 0, W, H)
  bg.addColorStop(0, '#22c55e')
  bg.addColorStop(0.55, '#12a150')
  bg.addColorStop(1, '#0b6b34')
  ctx.setFillStyle(bg)
  ctx.fillRect(0, 0, W, H)

  // 白色内卡
  ctx.setFillStyle('#ffffff')
  ctx.fillRect(40, 96, W - 80, H - 192)

  // ① 文字头像（昵称首字符；不含头像图片，需求 2.2）
  const avatarR = 56
  const avatarY = 200
  ctx.beginPath()
  ctx.arc(cx, avatarY, avatarR, 0, Math.PI * 2)
  ctx.setFillStyle('#e7f7ee')
  ctx.fill()
  ctx.setFillStyle('#12a150')
  ctx.setFontSize(48)
  ctx.setTextAlign('center')
  ctx.fillText(String(disp.avatarSeed || disp.nickname || '有').slice(0, 1), cx, avatarY + 18)

  // ② 昵称
  ctx.setFillStyle('#25292e')
  ctx.setFontSize(34)
  ctx.setTextAlign('center')
  ctx.fillText(String(disp.nickname || '有余用户').slice(0, 16), cx, 320)

  // ③ 标签（有则显示，无则省略，需求 2.4）
  let y = 372
  if (disp.label) {
    const label = String(disp.label).slice(0, 12)
    ctx.setFillStyle('#12a150')
    ctx.setFontSize(24)
    ctx.setTextAlign('center')
    ctx.fillText(`# ${label}`, cx, y)
    y += 44
  }

  // ④ 一句 AI 文案（主视觉之一）
  if (disp.narrative) {
    ctx.setFillStyle('#4b5563')
    ctx.setFontSize(28)
    ctx.setTextAlign('center')
    const lines = wrapChars(disp.narrative, 18, 3)
    lines.forEach((line) => {
      ctx.fillText(line, cx, y)
      y += 42
    })
    y += 16
  }

  // ⑤ 核心数据（主视觉）
  const coreLines = Array.isArray(disp.coreLines) ? disp.coreLines : []
  y = Math.max(y, 520)
  coreLines.slice(0, 5).forEach((line) => {
    ctx.setFillStyle('#1f2937')
    ctx.setFontSize(30)
    ctx.setTextAlign('center')
    ctx.fillText(String(line).slice(0, 22), cx, y)
    y += 50
  })

  // ⑥ 小尺寸品牌 Logo（按 computeCardLayout 置于右下角一角、面积 ≤ 5%、不置视觉中心，需求 2.5）
  const logo = layout.logo
  const brand = String(disp.brandName || '有余').slice(0, 6)
  // 字号按 Logo 包围盒高度取，确保绘制落在包围盒内、不喧宾夺主。
  const logoFont = Math.max(Math.min(logo.height * 0.7, 30), 12)
  ctx.setFillStyle('#9aa2ad')
  ctx.setFontSize(logoFont)
  ctx.setTextAlign('center')
  ctx.fillText(brand, logo.x + logo.width / 2, logo.y + logo.height / 2 + logoFont / 3)
  ctx.setTextAlign('left')
}

/** 绘制 → 出图为临时文件（复用 canvasToTempFilePath；需求 11.1、11.2）。 */
function renderCardToTempFile() {
  return new Promise((resolve, reject) => {
    try {
      const ctx = uni.createCanvasContext(POSTER_CANVAS_ID)
      drawShareCard(ctx, display.value)
      // draw 回调在实际绘制完成后触发，此时才可出图。
      ctx.draw(false, () => {
        uni.canvasToTempFilePath({
          canvasId: POSTER_CANVAS_ID,
          width: CARD_W,
          height: CARD_H,
          success: (res) => {
            const p = res && res.tempFilePath
            if (p) resolve(p)
            else reject({ code: 'EXPORT_FAILED' })
          },
          fail: () => reject({ code: 'EXPORT_FAILED' })
        })
      })
    } catch (e) {
      reject({ code: 'EXPORT_FAILED' })
    }
  })
}

// ── 相册授权（需求 11.4、11.5）─────────────────────────────────────
// 未授予先请求授权；拒绝 → 提示需授权、不写入、停留当前页、不进入错误态；
// 环境无 getSetting（如 H5）→ 放行，由保存分支给提示。

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

/** 此前已拒绝：提示 + 「去设置」，不发起写相册（需求 11.4）。 */
function promptOpenSetting() {
  uni.showModal({
    title: '需要相册权限',
    content: '保存分享卡片需要访问相册权限，请在设置中开启后重试。',
    confirmText: '去设置',
    cancelText: '取消',
    success: (r) => {
      if (r && r.confirm && typeof uni.openSetting === 'function') {
        uni.openSetting({ fail() {} })
      }
    },
    fail() {}
  })
}

/**
 * 相册写入授权（需求 11.4）：
 * - 未询问过 → 发起授权请求；被拒 → 提示需授权、返回 false。
 * - 此前已拒绝 → 提示 + 去设置，不发起绘制/写入、返回 false。
 * - 已授予 / 环境无 getSetting → 放行。
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

function saveToAlbum(filePath) {
  return new Promise((resolve, reject) => {
    try {
      uni.saveImageToPhotosAlbum({
        filePath,
        success: () => resolve(true),
        fail: (err) => {
          const msg = String((err && (err.errMsg || err.message)) || '')
          reject({ code: /deny|auth/i.test(msg) ? 'DENIED' : 'SAVE_FAILED' })
        }
      })
    } catch (e) {
      reject({ code: 'SAVE_FAILED' })
    }
  })
}

/**
 * 保存到相册（需求 11.3、11.4、11.5、11.6、11.7）。
 * - 卡片不可用 → 展示「暂不可用」，不发起 canvas 绘制/写相册（需求 11.7）。
 * - 相册授权未授予先请求授权，拒绝 → 提示、不写入、停留当前页、不进入错误态（需求 11.4）。
 * - 从触发起 3000ms 内渲染与写入未全部完成 → 结束操作、提示失败、停留当前页、允许再次触发（需求 11.6）。
 * - 写入成功 → 提示成功并停留（需求 11.5）。
 */
async function onSave() {
  if (pageState.value !== 'ready') {
    toast('该卡片暂不可用')
    return
  }
  if (busy.value) return
  busy.value = true
  // 超时后不写相册：expired 标志在写入之前再挡一次，避免超时提示后又偷偷写入。
  let expired = false
  const timer = setTimeout(() => {
    expired = true
  }, CARD_OP_TIMEOUT_MS)
  try {
    const authorized = await ensureAlbumAuth()
    if (!authorized) return // 提示已给出，停留当前页、不进入错误态
    const filePath = await withTimeout(renderCardToTempFile(), CARD_OP_TIMEOUT_MS)
    if (expired) throw { code: 'OP_TIMEOUT' }
    await saveToAlbum(filePath)
    toast('已保存到相册', 'success')
  } catch (e) {
    const code = (e && e.code) || ''
    toast(code === 'DENIED' ? '需要相册权限才能保存' : '保存失败，请稍后重试')
  } finally {
    clearTimeout(timer)
    busy.value = false
  }
}

/**
 * 转发分享（需求 11.2、11.3、11.6、11.7）。
 * - 卡片不可用 → 展示「暂不可用」，不发起 canvas 绘制/转发（需求 11.7）。
 * - 从触发起 3000ms 内出图未完成 → 结束操作、提示失败、停留当前页、允许再次触发（需求 11.6）。
 * - 出图成功 → 优先 showShareImageMenu 分享图片，不可用时提示用右上角转发。
 */
async function onShare() {
  if (pageState.value !== 'ready') {
    toast('该卡片暂不可用')
    return
  }
  if (busy.value) return
  busy.value = true
  try {
    const filePath = await withTimeout(renderCardToTempFile(), CARD_OP_TIMEOUT_MS)
    if (typeof uni.showShareImageMenu === 'function') {
      uni.showShareImageMenu({
        path: filePath,
        fail() {
          toast('请点右上角转发')
        }
      })
    } else {
      toast('请点右上角转发分享')
    }
  } catch (e) {
    toast('生成失败，请稍后重试')
  } finally {
    busy.value = false
  }
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

onLoad((query) => {
  const q = query || {}
  // 卡片类型：兼容 type / cardType 两种入参键。
  cardType.value = String(q.type || q.cardType || '')
  // 仅收敛该类型可能用到的可选周期/标识参数（其余忽略）。
  const params = {}
  if (q.month) params.month = String(q.month)
  if (q.year) params.year = String(q.year)
  if (q.code) params.code = String(q.code)
  if (q.milestone !== undefined && q.milestone !== null && q.milestone !== '') {
    params.milestone = String(q.milestone)
  }
  cardParams.value = params
})

onShow(() => {
  // 开启右上角转发菜单，供 onShareAppMessage 转发（需求 11.2）。
  uni.showShareMenu({ withShareTicket: false, fail() {} })
  loadCard()
})

// 右上角转发（需求 11.2）：转发回本分享页，标题含产品名「有余」，不含任何促销/下载引导（需求 2.6）。
onShareAppMessage(() => {
  const t = cardType.value
  const params = cardParams.value || {}
  const qs = [`type=${encodeURIComponent(t)}`]
  for (const k of ['month', 'year', 'code', 'milestone']) {
    if (params[k] != null && params[k] !== '') qs.push(`${k}=${encodeURIComponent(params[k])}`)
  }
  const titleName = display.value && display.value.nickname ? display.value.nickname : '有余用户'
  return {
    title: `${titleName} 的${pageTitle.value} · 有余`,
    path: `/pages/share/share?${qs.join('&')}`
  }
})
</script>

<template>
  <view class="page">
    <!-- LOADING：加载中指示，不展示卡片与失败文案 -->
    <view v-if="pageState === 'loading'" class="hint-card">
      <text class="h-d">正在生成分享卡片…</text>
    </view>

    <!-- GUEST：未登录，不请求不展示，展示登录入口（需求 11.8） -->
    <view v-else-if="pageState === 'guest'" class="hint-card">
      <text class="h-t">登录后生成分享卡片</text>
      <text class="h-d">把你的成就时刻做成卡片，保存或转发给好友</text>
      <text class="retry" @click="goLogin">去登录</text>
    </view>

    <!-- UNAVAILABLE：卡片不可用，展示「暂不可用」，不提供出图/保存/分享入口（需求 11.7） -->
    <view v-else-if="pageState === 'unavailable'" class="hint-card">
      <text class="h-t">该卡片暂不可用</text>
      <text class="h-d">积累更多记录后再来生成这张卡片吧～</text>
    </view>

    <!-- HIDDEN：静默降级 / 聚合视图 / 非法类型：不展示任何卡片，不弹阻断性弹窗（需求 1.9、11.9） -->
    <view v-else-if="pageState === 'hidden'" class="hint-card">
      <text class="h-d">暂无可展示的分享卡片</text>
    </view>

    <!-- READY：卡片可用，展示预览与出图/保存/分享入口（需求 11.1、11.3） -->
    <template v-else-if="pageState === 'ready' && display">
      <view class="preview">
        <!-- ① 文字头像 -->
        <view class="pv-avatar">{{ (display.avatarSeed || display.nickname || '有').slice(0, 1) }}</view>
        <!-- ② 昵称 -->
        <text class="pv-nick">{{ display.nickname || '有余用户' }}</text>
        <!-- ③ 标签（有则显示，需求 2.4） -->
        <text v-if="display.label" class="pv-label"># {{ display.label }}</text>
        <!-- ④ 一句 AI 文案（主视觉之一） -->
        <text v-if="display.narrative" class="pv-narrative">{{ display.narrative }}</text>
        <!-- ⑤ 核心数据（主视觉） -->
        <view class="pv-core">
          <text v-for="(line, i) in display.coreLines" :key="i" class="pv-core-line">{{ line }}</text>
        </view>
        <!-- ⑥ 小尺寸品牌 Logo（点缀，一角） -->
        <text class="pv-brand">{{ display.brandName || '有余' }}</text>
      </view>

      <view class="actions">
        <view class="act save" :class="{ busy }" @click="onSave">
          {{ busy ? '处理中…' : '保存到相册' }}
        </view>
        <view class="act share" :class="{ busy }" @click="onShare">分享图片</view>
      </view>
    </template>

    <!--
      离屏 canvas（需求 11.1）：定位到屏幕外，仅用于绘制出图，不参与页面视觉布局。
      逻辑尺寸 600x800（与 CARD_W/CARD_H 一致）。仅从 cardToDisplay 白名单绘制。
    -->
    <canvas
      :canvas-id="POSTER_CANVAS_ID"
      class="poster-canvas"
      :style="posterCssSize"
    ></canvas>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f2f4f6;
  padding: 24rpx;
}
/* 提示 / 加载 / 登录引导卡（复用成就页 fail-card 观感） */
.hint-card {
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
.hint-card .h-t {
  font-size: 30rpx;
  font-weight: 700;
  color: #25292e;
}
.hint-card .h-d {
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
/* 卡片预览：品牌绿渐变底 + 白色内容，主视觉为核心数据/昵称/文案，Logo 仅一角点缀 */
.preview {
  position: relative;
  border-radius: 28rpx;
  padding: 60rpx 40rpx 72rpx;
  background: linear-gradient(150deg, #22c55e, #12a150 55%, #0b6b34);
  box-shadow: 0 20rpx 44rpx rgba(22, 163, 74, 0.26);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20rpx;
  overflow: hidden;
}
.pv-avatar {
  width: 112rpx;
  height: 112rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.9);
  color: #12a150;
  text-align: center;
  line-height: 112rpx;
  font-size: 56rpx;
  font-weight: 800;
}
.pv-nick {
  font-size: 36rpx;
  font-weight: 800;
  color: #fff;
}
.pv-label {
  font-size: 24rpx;
  color: #eafff2;
  background: rgba(255, 255, 255, 0.22);
  border-radius: 999rpx;
  padding: 4rpx 20rpx;
}
.pv-narrative {
  font-size: 28rpx;
  color: #f3fff8;
  line-height: 1.6;
  text-align: center;
  margin-top: 8rpx;
}
.pv-core {
  width: 100%;
  margin-top: 12rpx;
  background: rgba(255, 255, 255, 0.94);
  border-radius: 20rpx;
  padding: 28rpx 24rpx;
  display: flex;
  flex-direction: column;
  gap: 14rpx;
}
.pv-core-line {
  font-size: 30rpx;
  font-weight: 700;
  color: #1f2937;
  text-align: center;
}
.pv-brand {
  position: absolute;
  right: 28rpx;
  bottom: 20rpx;
  font-size: 22rpx;
  color: rgba(255, 255, 255, 0.8);
}
/* 出图 / 保存 / 分享入口 */
.actions {
  display: flex;
  gap: 24rpx;
  margin-top: 32rpx;
}
.act {
  flex: 1;
  text-align: center;
  padding: 28rpx 0;
  border-radius: 18rpx;
  font-size: 30rpx;
  font-weight: 700;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.06);
}
.act.save {
  background: #12a150;
  color: #fff;
}
.act.share {
  background: #fff;
  color: #12a150;
}
.act.busy {
  opacity: 0.6;
}
/* 离屏画布：移出可视区域，不占布局位置（需求 11.1 的绘制载体） */
.poster-canvas {
  position: fixed;
  left: -9999rpx;
  top: 0;
}
</style>
