<script setup>
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app'
import { fetchStreakOverview, fetchStreakSegments } from '../../api/streak'
import {
  checkinCells,
  restartHint,
  milestoneText,
  isFirstTimeUser,
  hasMoreSegments,
  shouldRefresh,
  STREAK_PAGE_SIZE,
  STREAK_TIMEOUT_MS
} from '../../utils/streak'
import { useAuthStore } from '../../stores/auth'
import { dayLabel } from '../../utils/format'

/**
 * 连续记账页（需求 9.1~9.12、9.14~9.18）。
 *
 * 两条互相独立的三态状态机（loading | ready | error），刻意不合并成一条：
 * 概览与历史区间是两个独立请求，各自计时、各自失败、各自重试，失败只影响自己那块区域，
 * 另一块已加载成功的内容保持展示（需求 9.10）。范式照抄成长页 / 成就页的 `seq` 请求序号
 * + `withTimeout` 客户端超时守卫——底层请求仍会跑完，靠序号忽略迟到结果，避免旧响应覆盖新结果。
 *
 * 区域归属（六个区域，自上而下，需求 9.1）：
 *   概览驱动：① 今日打卡状态 ② 当前连续天数（含 restartHint）③ 历史最长 + 最长段起止
 *            ④ 里程碑进度 ⑤ 打卡格子（末格 = 判定日）
 *   区间驱动：⑥ 历史区间列表（按起始日降序、触底追加、到底提示）
 * 打卡格子的「已打卡」判定取自服务端下发的段边界（需求 9.15，miniapp 内不实现第二套段划分）：
 * 把概览的当前段与已加载的区间项一起喂给 checkinCells()，因此即便历史区间请求尚未返回，
 * 当前段与末格（= todayDone）也能正确点亮（需求 9.7）。
 *
 * 反挫败感（需求 9.4）：本页全部可见文案不含「归零 / 清空 / 失败 / 中断」四个词——
 * 加载出错文案用「没能加载出来」，断链引导用 restartHint() 的「上次连续 N 天，今天重新开始」。
 *
 * 未登录（需求 9.11）：不发两个请求、六个区域均不展示任何数值，只展示登录入口。
 * 页面不展示任何金额、账本名称、邮箱与邀请码（需求 9.14）；日期一律复用 utils/format.js 的
 * dayLabel、以 Asia/Shanghai 呈现（需求 9.15）；里程碑数值只取接口下发值、页面不写死（需求 9.16）。
 */

const auth = useAuthStore()

// 未登录分支：一条请求都不发（需求 9.11），与数据态互斥。
const guest = ref(false)

// —— 概览请求状态（驱动区域 ①②③④⑤）——
const ovState = ref('loading') // loading | ready | error
const overview = ref(null)
let ovSeq = 0
let ovInFlight = false

// —— 历史区间请求状态（驱动区域 ⑥）——
const segState = ref('loading') // loading | ready | error
const segItems = ref([])
const segTotal = ref(0)
const segLoadedPages = ref(0) // 已成功加载的页数（page 从 0 开始，故等于「下一页页码」）
const segAppending = ref(false) // 触底追加进行中
const segAppendError = ref(false) // 触底追加出错（尾部提示，不影响已加载主列表）
let segSeq = 0
let segInFlight = false

// 距上次一批请求发出的时刻（毫秒），供下拉刷新的 3000ms 节流判定（需求 9.12）。
let lastRequestAt = 0
// 下拉刷新时本批实际发出的请求数；两者均返回或均判失败后结束下拉动效（需求 9.12）。
let refreshPending = 0

/** 客户端单请求超时：底层请求仍会跑完，靠序号守卫忽略其迟到结果（需求 9.10，超时按单请求计）。 */
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

/** 计数字段兜底：非数字 / NaN / 负数一律折成 0，避免渲染出「-1」。 */
function countOf(n) {
  const v = Number(n)
  if (!Number.isFinite(v) || v <= 0) return 0
  return Math.floor(v)
}

/**
 * 拉取连续记账概览（区域 ①②③④⑤）。
 * @param {boolean} isRefresh 下拉刷新：请求返回前保留旧值展示（native 下拉动效即加载指示），
 *   成功整体替换、出错切 error；首屏 / 重试：先切 loading，出错切 error。
 *   自动重试次数为 0（需求 9.10）——出错只切 error 态，绝不自动重发。
 */
async function loadOverview(isRefresh) {
  if (ovInFlight) return // 不重复发起尚未返回的同类请求（需求 9.12）
  const s = ++ovSeq
  ovInFlight = true
  if (!isRefresh) ovState.value = 'loading'
  try {
    const res = await withTimeout(fetchStreakOverview(), STREAK_TIMEOUT_MS)
    if (s !== ovSeq) return
    overview.value = res || {}
    ovState.value = 'ready'
  } catch (e) {
    if (s !== ovSeq) return
    ovState.value = 'error'
  } finally {
    if (s === ovSeq) ovInFlight = false
    settleRefresh(isRefresh)
  }
}

/**
 * 拉取历史区间第 0 页（区域 ⑥），用于首屏、重试与下拉刷新。
 * 成功时丢弃已加载的后续页、以第 0 页整体替换列表（需求 9.12）。
 */
async function loadSegmentsPage0(isRefresh) {
  if (segInFlight) return // 不重复发起尚未返回的同类请求（需求 9.9、9.12）
  const s = ++segSeq
  segInFlight = true
  segAppendError.value = false
  if (!isRefresh) segState.value = 'loading'
  try {
    const res = await withTimeout(fetchStreakSegments(0, STREAK_PAGE_SIZE), STREAK_TIMEOUT_MS)
    if (s !== segSeq) return
    segItems.value = Array.isArray(res?.items) ? res.items.filter((it) => it && it.startDate) : []
    segTotal.value = countOf(res?.total)
    segLoadedPages.value = 1
    segState.value = 'ready'
  } catch (e) {
    if (s !== segSeq) return
    segState.value = 'error'
  } finally {
    if (s === segSeq) segInFlight = false
    settleRefresh(isRefresh)
  }
}

/**
 * 触底追加下一页（需求 9.9、9.18）。
 * 已加载条数等于总条数时不再发起；同一时刻至多 1 个未返回的区间请求（segInFlight 守卫）；
 * 追加结果按 startDate 去重，不重复展示同一起始日的区间项。
 */
async function loadMoreSegments() {
  if (segState.value !== 'ready') return
  if (segInFlight) return
  if (!hasMoreSegments(segItems.value.length, segTotal.value)) return
  const s = ++segSeq
  segInFlight = true
  segAppending.value = true
  segAppendError.value = false
  const page = segLoadedPages.value
  try {
    const res = await withTimeout(fetchStreakSegments(page, STREAK_PAGE_SIZE), STREAK_TIMEOUT_MS)
    if (s !== segSeq) return
    const incoming = Array.isArray(res?.items) ? res.items : []
    const seen = new Set(segItems.value.map((x) => x.startDate))
    const merged = segItems.value.slice()
    for (const it of incoming) {
      if (it && it.startDate && !seen.has(it.startDate)) {
        merged.push(it)
        seen.add(it.startDate)
      }
    }
    segItems.value = merged
    segLoadedPages.value = page + 1
    segTotal.value = countOf(res?.total)
  } catch (e) {
    if (s !== segSeq) return
    segAppendError.value = true // 仅尾部提示，已加载主列表保持不变
  } finally {
    if (s === segSeq) {
      segInFlight = false
      segAppending.value = false
    }
  }
}

/** 下拉刷新收尾：本批请求全部落地后结束下拉动效（需求 9.12）。 */
function settleRefresh(isRefresh) {
  if (!isRefresh) return
  refreshPending--
  if (refreshPending <= 0) {
    refreshPending = 0
    uni.stopPullDownRefresh()
  }
}

function openLoad() {
  lastRequestAt = Date.now()
  loadOverview(false)
  loadSegmentsPage0(false)
}

onLoad(() => {
  if (!auth.isLoggedIn) {
    // 未登录：不发任何请求，展示登录入口（需求 9.11）。
    guest.value = true
    return
  }
  openLoad()
})

onPullDownRefresh(() => {
  if (guest.value) {
    uni.stopPullDownRefresh()
    return
  }
  // 距上次请求不满 3000ms：不发请求，结束动效、页面取值一行不动（需求 9.12）。
  if (!shouldRefresh(lastRequestAt, Date.now())) {
    uni.stopPullDownRefresh()
    return
  }
  lastRequestAt = Date.now()
  // 尚未返回的同类请求不重复发起（需求 9.12）；只统计本批实际发出的请求数。
  const fireOv = !ovInFlight
  const fireSeg = !segInFlight
  refreshPending = (fireOv ? 1 : 0) + (fireSeg ? 1 : 0)
  if (refreshPending === 0) {
    uni.stopPullDownRefresh()
    return
  }
  if (fireOv) loadOverview(true)
  if (fireSeg) loadSegmentsPage0(true) // page0 成功即丢弃已加载后续页
})

onReachBottom(() => {
  if (guest.value) return
  loadMoreSegments()
})

/** 概览重试：只重发概览请求；重试胶囊随 error 卡卸载，返回前不可能被再次触发（需求 9.17）。 */
function retryOverview() {
  if (ovInFlight) return
  loadOverview(false)
}

/** 历史区间重试：只重发区间请求（需求 9.17）。 */
function retrySegments() {
  if (segInFlight) return
  loadSegmentsPage0(false)
}

function goRecord() {
  uni.navigateTo({ url: '/pages/record/record' })
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

// —— 概览派生值（仅在 ovState === 'ready' 且 overview 非空时使用）——

const todayDone = computed(() => overview.value?.todayDone === true)
const currentStreakDays = computed(() => countOf(overview.value?.currentStreakDays))
const maxStreakDays = computed(() => countOf(overview.value?.maxStreakDays))
const longestStart = computed(() => overview.value?.longestSegmentStart || '')
const longestEnd = computed(() => overview.value?.longestSegmentEnd || '')
const hasLongest = computed(() => !!longestStart.value && !!longestEnd.value)

// 断链引导文案：broken 真且 lastStreakDays 非空时返回「上次连续 N 天，今天重新开始」，否则 ''。
const restartText = computed(() => restartHint(overview.value))
// 里程碑文案：nextMilestone 空 → 「已达成全部里程碑」；还需天数 < 1 → ''（不展示，需求 9.8）。
const milestone = computed(() => milestoneText(overview.value))

// 首次记账用户（需求 9.5）：累计记账天数与段总数都为 0；仅在概览就绪后才可判定。
const firstTime = computed(() => ovState.value === 'ready' && isFirstTimeUser(overview.value))

// 打卡格子（需求 9.6、9.7、9.15）：把概览当前段与已加载区间项一起喂给纯函数，
// 因此当前段与末格（= todayDone）不依赖历史区间请求是否已返回。
const checkinSource = computed(() => {
  const list = segItems.value.slice()
  const o = overview.value
  if (o && o.currentSegmentStart && o.currentSegmentEnd) {
    list.push({ startDate: o.currentSegmentStart, endDate: o.currentSegmentEnd })
  }
  return list
})
const cells = computed(() => checkinCells(Date.now(), checkinSource.value))

// —— 历史区间列表派生（区域 ⑥）——
const segReachedEnd = computed(
  () => segItems.value.length > 0 && !hasMoreSegments(segItems.value.length, segTotal.value)
)

/** 日期串（YYYY-MM-DD）→「M月D日 周X」，复用 utils/format.js，以 Asia/Shanghai 呈现。 */
function fmt(dateStr) {
  return dateStr ? dayLabel(dateStr) : ''
}
</script>

<template>
  <view class="page">
    <!-- 未登录：只展示登录入口，六个区域均不展示任何数值（需求 9.11） -->
    <view v-if="guest" class="fail-card">
      <AppIcon name="badge" :size="52" color="#12a150" />
      <text class="f-t">登录后查看你的连续记账</text>
      <text class="f-d">记录每一天的坚持，登录即可开始</text>
      <text class="retry" @click="goLogin">去登录</text>
    </view>

    <template v-else>
      <!-- ============ 概览区块（区域 ①②③④⑤） ============ -->
      <!-- 概览加载中：只展示占位，不展示任何数值（需求 9.2） -->
      <view v-if="ovState === 'loading'" class="fail-card">
        <text class="f-d">正在加载连续记账数据…</text>
      </view>

      <!-- 概览出错：失败提示 + 重试胶囊，不展示任何取值为 0 的默认数值（需求 9.10） -->
      <view v-else-if="ovState === 'error'" class="fail-card">
        <AppIcon name="warning" :size="52" color="#c7ccd2" />
        <text class="f-t">数据没能加载出来</text>
        <text class="f-d">网络不太顺畅，稍后再试一次</text>
        <text class="retry" @click="retryOverview">重试</text>
      </view>

      <!-- 概览就绪 -->
      <template v-else>
        <!-- 首次记账用户：只展示今日打卡 + 首次引导 + 跳转记账，不展示历史区间空骨架（需求 9.5） -->
        <template v-if="firstTime">
          <view class="hero" :class="{ done: todayDone }">
            <text class="hero-k">今日打卡</text>
            <text class="hero-v">{{ todayDone ? '今天已记账' : '今天还没记账' }}</text>
          </view>
          <view class="card intro-card">
            <text class="intro-t">开始你的第一笔记账</text>
            <text class="intro-d">记下第一笔，点亮第一格，坚持从今天算起</text>
            <text class="cta" @click="goRecord">去记一笔</text>
          </view>
        </template>

        <template v-else>
          <!-- ① 今日打卡状态（需求 9.3、9.7） -->
          <view class="hero" :class="{ done: todayDone }">
            <text class="hero-k">今日打卡</text>
            <text class="hero-v">{{ todayDone ? '今天已记账' : '今天还没记账' }}</text>
            <text class="hero-d">{{ todayDone ? '太棒了，今天的坚持已记录' : '记一笔，延续你的坚持' }}</text>
            <text v-if="!todayDone" class="cta cta-light" @click="goRecord">去记一笔</text>
          </view>

          <!-- ② 当前连续天数（broken 且 lastStreakDays 非空时追加 restartHint，需求 9.4） -->
          <view class="stats">
            <view class="st">
              <text class="st-v">{{ currentStreakDays }}</text>
              <text class="st-k">当前连续（天）</text>
            </view>
            <view class="st">
              <text class="st-v">{{ maxStreakDays }}</text>
              <text class="st-k">历史最长（天）</text>
            </view>
          </view>
          <view v-if="restartText" class="restart-hint">{{ restartText }}</view>

          <!-- ③ 历史最长连续天数的起止日 -->
          <template v-if="hasLongest">
            <view class="sect">最长的一段</view>
            <view class="card">
              <view class="row">
                <view class="r-ic t-green"><AppIcon name="badge" :size="36" color="#12a150" /></view>
                <text class="r-t">{{ fmt(longestStart) }} — {{ fmt(longestEnd) }}</text>
                <text class="r-v">{{ maxStreakDays }} 天</text>
              </view>
            </view>
          </template>

          <!-- ④ 里程碑进度（milestoneText 为空时不展示，需求 9.8、9.16） -->
          <template v-if="milestone">
            <view class="sect">里程碑</view>
            <view class="card">
              <view class="row">
                <view class="r-ic t-green"><AppIcon name="list" :size="36" color="#12a150" /></view>
                <text class="r-t">{{ milestone }}</text>
              </view>
            </view>
          </template>

          <!-- ⑤ 打卡格子：30 格，升序、末格为判定日（需求 9.6、9.7） -->
          <view class="sect">近 30 天</view>
          <view class="card cells-card">
            <view class="cells">
              <view
                v-for="c in cells"
                :key="c.date"
                class="cell"
                :class="{ on: c.checked }"
              ></view>
            </view>
          </view>
        </template>
      </template>

      <!-- ============ 历史区间列表（区域 ⑥，独立于概览态；首次用户不展示，需求 9.5、9.10） ============ -->
      <template v-if="!firstTime">
        <view class="sect">坚持记录</view>

        <!-- 区间加载中 -->
        <view v-if="segState === 'loading'" class="fail-card slim">
          <text class="f-d">正在加载坚持记录…</text>
        </view>

        <!-- 区间出错：只影响本区域，概览区域内容保持展示（需求 9.10） -->
        <view v-else-if="segState === 'error'" class="fail-card slim">
          <text class="f-t">记录没能加载出来</text>
          <text class="f-d">网络不太顺畅，稍后再试一次</text>
          <text class="retry" @click="retrySegments">重试</text>
        </view>

        <!-- 区间就绪：按起始日降序展示，每项含起始日、结束日与段天数（需求 9.9） -->
        <template v-else>
          <view v-if="segItems.length > 0" class="card">
            <view v-for="it in segItems" :key="it.startDate" class="row">
              <view class="r-ic t-green"><AppIcon name="badge" :size="32" color="#12a150" /></view>
              <text class="r-t seg-range">{{ fmt(it.startDate) }} — {{ fmt(it.endDate) }}</text>
              <text class="r-v">{{ it.days }} 天</text>
            </view>
          </view>

          <!-- 触底追加状态 / 到底提示（需求 9.18） -->
          <view class="list-foot">
            <text v-if="segAppending" class="foot-d">正在加载更多…</text>
            <text v-else-if="segAppendError" class="foot-retry" @click="loadMoreSegments">
              没能加载更多，轻触重试
            </text>
            <text v-else-if="segReachedEnd" class="foot-d">已经到底啦</text>
          </view>
        </template>
      </template>

      <view style="height: 60rpx"></view>
    </template>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f2f4f6;
  padding: 24rpx 24rpx 0;
}
/* 失败 / 加载 / 登录引导卡（复用成长页 / 成就页既有观感） */
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
.fail-card.slim {
  padding: 40rpx 30rpx;
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
/* ① 今日打卡状态：品牌绿卡（done）/ 中性引导卡（未完成） */
.hero {
  background: linear-gradient(135deg, #22c55e, #0f8a45 72%);
  border-radius: 28rpx;
  padding: 40rpx 34rpx;
  color: #fff;
  box-shadow: 0 20rpx 40rpx rgba(18, 161, 80, 0.28);
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.hero:not(.done) {
  background: linear-gradient(135deg, #9aa7b2, #6b7683 72%);
  box-shadow: 0 20rpx 40rpx rgba(58, 66, 74, 0.18);
}
.hero-k {
  font-size: 24rpx;
  opacity: 0.92;
}
.hero-v {
  font-size: 46rpx;
  font-weight: 800;
  line-height: 1.15;
}
.hero-d {
  font-size: 24rpx;
  opacity: 0.92;
}
.cta {
  margin-top: 14rpx;
  align-self: flex-start;
  font-size: 26rpx;
  font-weight: 600;
  color: #12a150;
  background: #fff;
  border-radius: 999rpx;
  padding: 12rpx 40rpx;
}
.cta-light {
  color: #12a150;
}
/* ② 当前连续 / 历史最长 双数值卡 */
.stats {
  display: flex;
  background: #fff;
  border-radius: 20rpx;
  margin-top: 24rpx;
  padding: 32rpx 8rpx;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
}
.stats .st {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
  min-width: 0;
}
.stats .st-v {
  font-size: 48rpx;
  font-weight: 800;
  color: #12a150;
}
.stats .st-k {
  font-size: 22rpx;
  color: #9aa2ad;
}
.restart-hint {
  margin-top: 14rpx;
  padding: 20rpx 24rpx;
  background: #e7f7ee;
  border-radius: 16rpx;
  font-size: 24rpx;
  color: #0f8a45;
  text-align: center;
}
/* 区块标题与卡片（复用成长页 / 我的页既有样式类） */
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
.r-t {
  flex: 1;
  font-size: 28rpx;
  color: #25292e;
  min-width: 0;
}
.seg-range {
  font-size: 26rpx;
}
.r-v {
  font-size: 26rpx;
  color: #12a150;
  font-weight: 700;
  flex: 0 0 auto;
  margin-left: 4rpx;
}
/* 首次记账引导卡 */
.intro-card {
  margin-top: 24rpx;
  padding: 40rpx 34rpx;
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}
.intro-t {
  font-size: 32rpx;
  font-weight: 700;
  color: #25292e;
}
.intro-d {
  font-size: 24rpx;
  color: #9aa2ad;
  line-height: 1.6;
}
.intro-card .cta {
  background: #12a150;
  color: #fff;
}
/* ⑤ 打卡格子：30 格自适应换行，小屏（iPhone SE）不折行错位 */
.cells-card {
  padding: 28rpx 24rpx;
}
.cells {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
  justify-content: space-between;
}
.cell {
  width: 52rpx;
  height: 52rpx;
  border-radius: 12rpx;
  background: #eef1f4;
}
.cell.on {
  background: #12a150;
}
/* ⑥ 列表尾部：追加中 / 重试 / 到底提示 */
.list-foot {
  padding: 26rpx 8rpx;
  text-align: center;
}
.foot-d {
  font-size: 22rpx;
  color: #9aa2ad;
}
.foot-retry {
  font-size: 24rpx;
  color: #12a150;
  font-weight: 600;
}
</style>
