<script setup>
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app'
import { fetchGrowthOverview } from '../../api/growth'
import { levelProgress, badgeProgressText, shouldRefresh, GROWTH_TIMEOUT_MS } from '../../utils/growth'
import { formatAmount } from '../../utils/format'

/**
 * 我的成长页（需求 13.2、13.3、13.4、13.5、13.6、13.7、13.8、13.9、13.11、13.12、13.14、13.16、13.17）。
 *
 * 单一状态机（与邀请页刻意不同：邀请页维护三条互相独立的状态机，因为二维码依赖微信接口、
 * 挂了不能连坐邀请码；成长概览是一次请求返回全部数据，没有需要拆分的独立子系统）：
 *   LOADING → READY / ERROR；READY → REFRESHING → READY。
 * REFRESHING 是 READY 的子态：下拉刷新期间继续展示旧值，成功则整体更新，失败则保留旧值。
 *
 * 三条硬性约束：
 * 1. ERROR 态不展示任何占位假数据（等级 / 经验 / 累计统计 / 徽章一律不渲染，不是渲染成 0 或 --）：
 *    只有失败文案 + 重试胶囊。理由：一个显示「Lv1 / 0 经验」的失败页会让用户以为自己的成长数据
 *    被清空了，比明说加载失败糟糕得多（需求 13.8）。
 * 2. 本期只展示 7 项：当前等级、经验值、升级进度、累计记账笔数、累计支出金额、累计记账天数、
 *    当前连续天数。totalIncome 与 maxStreakDays 本期不展示；currentLevelExp / nextLevelExp /
 *    expInCurrentLevel / expToNextLevel / maxLevel / maxLevelReached 六项只参与进度渲染与满级判定，
 *    不单独成项（需求 13.3、13.4）。
 * 3. 下拉刷新的 3000ms 客户端节流（需求 13.16、13.17）：不满 3000ms 不发请求、1000ms 内结束动效、
 *    取值一行不动；请求发出或 10000ms 超时后一律在 finally 里结束动效。
 *
 * 沿用品牌绿 #12a150 作为等级、进度条与已点亮徽章的强调色，复用既有 .sect / .card / .row / .r-ic
 * 与 AppIcon 组件，不新增组件、不引入新主色（需求 13.14）。
 */

const state = ref('loading') // loading | ready | error
const overview = ref(null)

// 距上次成长概览请求发出的时刻（毫秒），供下拉刷新节流判定（需求 13.16）。
let lastRequestAt = 0
// 请求序号：重试 / 刷新时丢弃迟到的旧响应，避免覆盖新结果（沿用邀请页写法）。
let seq = 0

/** 客户端超时：底层请求仍会跑完，靠序号守卫忽略其迟到结果（需求 13.8、13.16）。 */
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
 * 拉取成长概览。
 * @param {boolean} isRefresh true 表示下拉刷新（保留旧值、成功才整体替换、失败保留旧值、
 *   结束下拉动效）；false 表示首屏加载或点重试（先切 LOADING，失败切 ERROR）。
 */
async function load(isRefresh = false) {
  const s = ++seq
  if (!isRefresh) state.value = 'loading'
  lastRequestAt = Date.now()
  try {
    const res = await withTimeout(fetchGrowthOverview(), GROWTH_TIMEOUT_MS)
    if (s !== seq) return
    overview.value = res || {}
    state.value = 'ready'
  } catch (e) {
    if (s !== seq) return
    // 首屏 / 重试失败 → ERROR（不渲染任何数据）；下拉刷新失败 → 保留旧的 READY 值，仅结束动效。
    if (!isRefresh) state.value = 'error'
  } finally {
    // 请求发出或 10000ms 超时后一律结束下拉动效，避免动效卡死（需求 13.16）。
    if (isRefresh) uni.stopPullDownRefresh()
  }
}

onLoad(() => {
  load(false)
})

onPullDownRefresh(() => {
  // 不满 3000ms：不发请求，1000ms 内结束动效，页面取值一行不动（需求 13.16、13.17）。
  if (!shouldRefresh(lastRequestAt, Date.now())) {
    uni.stopPullDownRefresh()
    return
  }
  load(true)
})

function retry() {
  load(false)
}

function goLog() {
  // 进入经验明细页；本页不展示任何经验明细列表项（需求 13.9）。
  uni.navigateTo({ url: '/pages/growthlog/growthlog' })
}

// ---- 展示派生值（仅在 state === 'ready' 时使用；overview 恒非空）----

const level = computed(() => {
  const n = Number(overview.value?.level)
  return Number.isFinite(n) && n >= 1 ? n : 1
})
const exp = computed(() => {
  const n = Number(overview.value?.exp)
  return Number.isFinite(n) && n >= 0 ? n : 0
})
const maxLevelReached = computed(() => overview.value?.maxLevelReached === true)
const expToNextLevel = computed(() => {
  const n = Number(overview.value?.expToNextLevel)
  return Number.isFinite(n) && n >= 0 ? n : 0
})
// 升级进度 0–1，交给纯函数处理满级 / 分母 <=0 / 畸形字段等边界（需求 13.5、13.6）。
const progressPct = computed(() => Math.round(levelProgress(overview.value) * 100) + '%')

const totalRecordCount = computed(() => {
  const n = Number(overview.value?.totalRecordCount)
  return Number.isFinite(n) && n >= 0 ? n : 0
})
const totalRecordDays = computed(() => {
  const n = Number(overview.value?.totalRecordDays)
  return Number.isFinite(n) && n >= 0 ? n : 0
})
const currentStreakDays = computed(() => {
  const n = Number(overview.value?.currentStreakDays)
  return Number.isFinite(n) && n >= 0 ? n : 0
})
const totalExpenseText = computed(() => formatAmount(overview.value?.totalExpense))

// 徽章墙：按响应顺序渲染，缺失时取空数组（需求 13.7）。
const badges = computed(() => (Array.isArray(overview.value?.badges) ? overview.value.badges : []))

/** 徽章解锁时刻：LocalDateTime 字符串（如 2025-06-01T12:00:00）→ YYYY-MM-DD HH:mm。 */
function unlockedLabel(badge) {
  const s = String((badge && badge.unlockedAt) || '')
  if (!s) return ''
  const date = s.slice(0, 10)
  const time = s.slice(11, 16)
  return [date, time].filter(Boolean).join(' ')
}
</script>

<template>
  <view class="page">
    <!-- LOADING：首屏加载占位（不展示任何数据） -->
    <view v-if="state === 'loading'" class="fail-card">
      <text class="f-d">正在加载成长数据…</text>
    </view>

    <!-- ERROR：只有失败文案 + 重试胶囊，绝不渲染等级 / 经验 / 累计统计 / 徽章（需求 13.8） -->
    <view v-else-if="state === 'error'" class="fail-card">
      <AppIcon name="warning" :size="52" color="#c7ccd2" />
      <text class="f-t">成长数据加载失败</text>
      <text class="f-d">网络不太顺畅，稍后再试一次</text>
      <text class="retry" @click="retry">重试</text>
    </view>

    <!-- READY：等级卡 + 经验进度 + 四项统计 + 徽章墙 + 经验明细入口 -->
    <template v-else>
      <!-- 等级卡：品牌绿强调 -->
      <view class="hero">
        <view class="lv">
          <text class="lv-tag">Lv</text><text class="lv-num">{{ level }}</text>
        </view>
        <text class="lv-exp">{{ exp }} 经验</text>

        <!-- 升级进度 -->
        <view class="bar">
          <view class="bar-fill" :style="{ width: progressPct }"></view>
        </view>
        <text class="bar-hint">
          {{ maxLevelReached ? '已达满级 Lv100' : `距离下一级还需 ${expToNextLevel} 经验` }}
        </text>
      </view>

      <!-- 四项累计统计 -->
      <view class="stats">
        <view class="st">
          <text class="st-v">{{ totalRecordCount }}</text>
          <text class="st-k">累计记账</text>
        </view>
        <view class="st">
          <text class="st-v">{{ totalRecordDays }}</text>
          <text class="st-k">累计天数</text>
        </view>
        <view class="st">
          <text class="st-v">{{ currentStreakDays }}</text>
          <text class="st-k">连续天数</text>
        </view>
        <view class="st">
          <text class="st-v st-money">{{ totalExpenseText }}</text>
          <text class="st-k">累计支出</text>
        </view>
      </view>

      <!-- 徽章墙：9 枚按响应顺序 -->
      <view class="sect">成长徽章</view>
      <view class="badges">
        <view v-for="b in badges" :key="b.code" class="bg" :class="{ locked: !b.unlocked }">
          <view class="bg-ic" :class="b.unlocked ? 'on' : 'off'">
            <AppIcon name="badge" :size="40" :color="b.unlocked ? '#12a150' : '#c7ccd2'" />
          </view>
          <text class="bg-name">{{ b.name }}</text>
          <!-- 已点亮：解锁时刻，不显示进度文案；未点亮：current / target，不显示解锁时刻（需求 13.7） -->
          <text v-if="b.unlocked" class="bg-time">{{ unlockedLabel(b) }}</text>
          <text v-else class="bg-progress">{{ badgeProgressText(b) }}</text>
        </view>
      </view>

      <!-- 经验明细入口：本页不展示任何明细列表项（需求 13.9） -->
      <view class="sect">经验记录</view>
      <view class="card">
        <view class="row" @click="goLog">
          <view class="r-ic t-green"><AppIcon name="list" :size="36" /></view>
          <text class="r-t">经验明细</text>
          <text class="arrow">›</text>
        </view>
      </view>

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
/* 失败 / 加载卡 */
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
/* 等级卡 */
.hero {
  background: linear-gradient(135deg, #22c55e, #0f8a45 72%);
  border-radius: 28rpx;
  padding: 40rpx 34rpx;
  color: #fff;
  box-shadow: 0 20rpx 40rpx rgba(18, 161, 80, 0.28);
}
.hero .lv {
  display: flex;
  align-items: baseline;
}
.hero .lv-tag {
  font-size: 34rpx;
  font-weight: 700;
  opacity: 0.92;
}
.hero .lv-num {
  font-size: 86rpx;
  font-weight: 800;
  line-height: 1.05;
  margin-left: 8rpx;
}
.hero .lv-exp {
  display: block;
  font-size: 26rpx;
  opacity: 0.92;
  margin-top: 4rpx;
}
.hero .bar {
  margin-top: 28rpx;
  height: 16rpx;
  border-radius: 999rpx;
  background: rgba(255, 255, 255, 0.28);
  overflow: hidden;
}
.hero .bar-fill {
  height: 100%;
  border-radius: 999rpx;
  background: #fff;
  transition: width 0.3s ease;
}
.hero .bar-hint {
  display: block;
  font-size: 23rpx;
  opacity: 0.92;
  margin-top: 14rpx;
}
/* 四项统计 */
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
  font-size: 38rpx;
  font-weight: 800;
  color: #12a150;
}
.stats .st-v.st-money {
  font-size: 30rpx;
}
.stats .st-k {
  font-size: 22rpx;
  color: #9aa2ad;
}
/* 徽章墙 */
.sect {
  font-size: 24rpx;
  color: #9aa2ad;
  padding: 30rpx 8rpx 12rpx;
}
.badges {
  display: flex;
  flex-wrap: wrap;
  background: #fff;
  border-radius: 20rpx;
  padding: 20rpx 12rpx;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
}
.bg {
  width: 33.3333%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
  padding: 22rpx 8rpx;
  box-sizing: border-box;
}
.bg-ic {
  width: 88rpx;
  height: 88rpx;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.bg-ic.on {
  background: #e7f7ee;
}
.bg-ic.off {
  background: #eef1f4;
}
.bg-name {
  font-size: 24rpx;
  font-weight: 600;
  color: #25292e;
}
.bg.locked .bg-name {
  color: #9aa2ad;
  font-weight: 500;
}
.bg-time {
  font-size: 20rpx;
  color: #12a150;
}
.bg-progress {
  font-size: 22rpx;
  color: #9aa2ad;
}
/* 列表行（复用 me.vue 既有观感） */
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
  font-size: 30rpx;
  color: #25292e;
}
.arrow {
  color: #c7ccd2;
  font-size: 34rpx;
  margin-left: 4rpx;
}
</style>
