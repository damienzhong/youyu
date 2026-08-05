<script setup>
import { computed, ref } from 'vue'
import { onLoad, onPullDownRefresh, onShareAppMessage, onUnload } from '@dcloudio/uni-app'
import { fetchGrowthOverview } from '../../api/growth'
import { fetchStreakOverview } from '../../api/streak'
import { levelProgress, badgeProgressText, shouldRefresh, GROWTH_TIMEOUT_MS } from '../../utils/growth'
import {
  ACHIEVEMENT_TOTAL,
  ACHIEVEMENT_PAGE_PATH,
  buildAchievementSharePayload
} from '../../utils/achievement'
// AchievementUnlockModal 由 easycom 自动注册，无需显式 import。
import {
  broadcastItem,
  broadcastVisible,
  closeBroadcastModal,
  enterAchievementPageFromBroadcast,
  releaseAchievementBroadcastOnLeave,
  startAchievementBroadcast
} from '../../utils/achievementBroadcast'
import { useAuthStore } from '../../stores/auth'
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
 *
 * 另承载成就系统的成就页入口（成就系统需求 9.1、9.2、9.14、9.15）：徽章墙上方一行，
 * 计数由本页已有的概览响应 `badges` 派生（不额外发成就请求），未登录不展示，
 * 概览失败时展示不含计数的入口且点击行为不变。
 */

const auth = useAuthStore()

const state = ref('loading') // loading | ready | error
const overview = ref(null)

// 连续记账入口的行尾取值来源（需求 9.1、9.13）。
//
// 刻意与成就入口不同：成就入口的「已解锁数 / 16」直接数本页已有的成长概览 badges，零额外请求；
// 而「今日打卡状态」不在成长概览的 15 项字段里（本 spec 不加第 16 项），只能由连续记账概览提供，
// 所以成长页为这一行多发一次 fetchStreakOverview()。这次概览是写入型 GET，服务端顺带的结算会被
// 与成长概览同一个 10 秒节流器合并，不会增加结算次数。请求失败时本 ref 保持 null，入口仍可点击
// 进入连续记账页，只是行尾两项不展示取值。
const streakOverview = ref(null)

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
    // 播报挂载点 ②（成就系统需求 7.2）：概览数据请求成功后立即触发一次待播报查询与解锁播报，
    // 远快于 1000ms 上限。同步返回、不 await：本页展示不受播报成败与快慢影响。
    startAchievementBroadcast()
  } catch (e) {
    if (s !== seq) return
    // 首屏 / 重试失败 → ERROR（不渲染任何数据）；下拉刷新失败 → 保留旧的 READY 值，仅结束动效。
    if (!isRefresh) state.value = 'error'
  } finally {
    // 请求发出或 10000ms 超时后一律结束下拉动效，避免动效卡死（需求 13.16）。
    if (isRefresh) uni.stopPullDownRefresh()
  }
}

/**
 * 拉取连续记账概览，仅用于成长页「连续记账」入口的行尾两项（今日打卡状态、当前连续天数）。
 * 未登录不发该请求（与入口一并隐藏）；失败时静默保留 null，入口仍可点击进入连续记账页、
 * 只是行尾不展示取值（需求 9.13）。与成长概览各自独立：这一行的失败不影响成长数据展示，
 * 反之亦然。
 */
async function loadStreak() {
  if (auth.isLoggedIn !== true) {
    streakOverview.value = null
    return
  }
  try {
    const res = await fetchStreakOverview()
    streakOverview.value = res || null
  } catch (e) {
    streakOverview.value = null
  }
}

onLoad(() => {
  load(false)
  loadStreak()
})

onPullDownRefresh(() => {
  // 不满 3000ms：不发请求，1000ms 内结束动效，页面取值一行不动（需求 13.16、13.17）。
  if (!shouldRefresh(lastRequestAt, Date.now())) {
    uni.stopPullDownRefresh()
    return
  }
  load(true)
  loadStreak()
})

function retry() {
  load(false)
  loadStreak()
}

function goLog() {
  // 进入经验明细页；本页不展示任何经验明细列表项（需求 13.9）。
  uni.navigateTo({ url: '/pages/growthlog/growthlog' })
}

function goAchievement() {
  // 成就系统需求 9.2：点击入口打开成就页；概览请求失败与否都是同一个跳转（需求 9.15）。
  uni.navigateTo({ url: ACHIEVEMENT_PAGE_PATH })
}

function goStreak() {
  // 需求 9.1：点击入口进入连续记账页；连续记账概览请求成功与否都是同一个跳转（需求 9.13）。
  uni.navigateTo({ url: '/pages/streak/streak' })
}

// ---- 解锁播报挂载点 ②（成就系统需求 7.2、7.13、7.16、8.1）----
// 弹层状态是 utils/achievementBroadcast.js 的模块级 ref，本页只做绑定与事件转发。

// 弹层内「分享给好友」不需要本页处理：转发面板由弹层里 open-type="share" 的 button 唤起、
// 落到下面的 onShareAppMessage，转发目标直接取 broadcastItem，弹层也照常保持展示（需求 7.13）。

/**
 * 弹层内「保存卡片」：canvas 绘制只在成就页（离屏画布在那一页上），
 * 因此本页把它转成「进入成就页并高亮这一枚」——落地后那一项自带
 * 「保存卡片到相册」入口。同时按需求 7.16 收起弹层、放弃未展示的 Toast 并推进游标。
 */
function onBroadcastSave() {
  uni.showToast({ title: '在成就页保存这张卡片', icon: 'none' })
  enterAchievementPageFromBroadcast()
}

onShareAppMessage(() => {
  // 只在解锁弹层给出转发目标时返回成就分享卡片；页面右上角菜单的普通转发不受影响
  // （返回 undefined 即用平台默认卡片）。标题与路径的构造全在纯函数里（需求 8.3）。
  const item = broadcastItem.value
  if (!item) return
  const payload = buildAchievementSharePayload(item)
  return { title: payload.title, path: payload.path }
})

onUnload(() => {
  // 弹层若正展示在本页而本页要卸载：放弃本次播报、释放守卫、不推进游标，
  // 这些成就留在待播报集合内等下次播报（需求 7.11 的至少一次语义）。
  releaseAchievementBroadcastOnLeave()
})

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

// ---- 成就页入口（成就系统需求 9.1、9.2、9.14、9.15）----
// 未登录不展示入口（需求 9.14）。入口本身不发任何成就请求：计数完全取自已有的概览响应，
// 所以入口的出现不会给成就清单接口带来一次多余调用。
const showAchievementEntry = computed(() => auth.isLoggedIn === true)

// 已解锁成就数取自概览响应 `badges` 数组里已解锁项的个数，并钳在 [0, 16]（需求 9.1）：
// 概览的徽章列表与成就清单由服务端同一份快照投影而来，因此这里数 badges 与调成就清单接口等价，
// 且省掉一次请求。钳制是防字段异常渲染出「17 / 16」这种自相矛盾的计数。
const achievementUnlockedCount = computed(() => {
  const n = badges.value.filter((b) => b && b.unlocked === true).length
  return Math.max(0, Math.min(n, ACHIEVEMENT_TOTAL))
})

// 概览请求失败或超时（state === 'error'）时返回 ''，入口只剩标题与箭头、点击行为不变（需求 9.15）；
// 与「我的」页成长入口的 `growthLevel === null` 同一套降级思路。
const achievementCountText = computed(() =>
  state.value === 'ready' ? `${achievementUnlockedCount.value} / ${ACHIEVEMENT_TOTAL}` : ''
)

// ---- 连续记账入口（需求 9.1、9.13）----
// 未登录不展示入口、也不发连续记账概览请求（loadStreak 已在登录态外提前返回）。
// 展示条件与成就入口一致（都只看登录态），二者共用同一处「登录且非 LOADING」的尾块结构。
const showStreakEntry = computed(() => auth.isLoggedIn === true)

// 行尾两项：今日打卡状态 + 当前连续天数，取自连续记账概览。请求未返回或失败（streakOverview 为 null）
// 时返回 ''，此时入口只剩标题与箭头、点击仍进入连续记账页（需求 9.13）。
const streakTailText = computed(() => {
  const ov = streakOverview.value
  if (!ov) return ''
  const done = ov.todayDone === true ? '今日已记' : '今日未记'
  const n = Number(ov.currentStreakDays)
  const days = Number.isFinite(n) && n >= 0 ? n : 0
  return `${done} · 连续 ${days} 天`
})

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

    <!-- READY 上半：等级卡 + 经验进度 + 四项统计 -->
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
    </template>

    <!--
      成就页入口（成就系统需求 9.1、9.2、9.14、9.15）：与「我的」页成长入口同构。
      刻意放在 READY 与 ERROR 两态之外的独立块里，而不是塞进 READY 模板：
      需求 9.15 要求概览请求失败时仍展示不含计数的入口、点击行为不变，
      而 ERROR 态本身不渲染任何成长数据（需求 13.8），两条只能靠这种「共用尾块」结构同时满足。
      位置因此恒为徽章墙上方（READY）或失败卡下方（ERROR）；LOADING 态不展示。
    -->
    <template v-if="showAchievementEntry && state !== 'loading'">
      <view class="sect">成就</view>
      <view class="card">
        <view class="row" @click="goAchievement">
          <view class="r-ic t-green"><AppIcon name="badge" :size="36" /></view>
          <text class="r-t">我的成就</text>
          <text v-if="achievementCountText" class="r-v">{{ achievementCountText }}</text>
          <text class="arrow">›</text>
        </view>
      </view>
    </template>

    <!--
      连续记账入口（需求 9.1、9.13）：结构与成就入口同构（.card + .row + .r-ic t-green + AppIcon
      + .r-v + .arrow）。放在成就入口一行之下，展示条件同为「登录且非 LOADING」。
      行尾的 streakTailText 取自单独一次连续记账概览请求（loadStreak），与成就入口的「零额外请求」
      刻意不同——今日打卡状态不在成长概览字段集内，只能由连续记账概览提供。
    -->
    <template v-if="showStreakEntry && state !== 'loading'">
      <view class="sect">连续记账</view>
      <view class="card">
        <view class="row" @click="goStreak">
          <view class="r-ic t-green"><AppIcon name="star" :size="36" /></view>
          <text class="r-t">连续记账</text>
          <text v-if="streakTailText" class="r-v">{{ streakTailText }}</text>
          <text class="arrow">›</text>
        </view>
      </view>
    </template>

    <!-- READY 下半：徽章墙 + 经验明细入口 -->
    <template v-if="state === 'ready'">
      <!-- 徽章墙：按响应顺序 -->
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

    <!--
      解锁弹层（挂载点 ②，成就系统需求 7.4）：可见性与当前项来自 utils/achievementBroadcast.js
      的模块级状态。放在三态之外——它与本页数据态无关。
    -->
    <AchievementUnlockModal
      :visible="broadcastVisible"
      :achievement="broadcastItem"
      @update:visible="closeBroadcastModal"
      @enter="enterAchievementPageFromBroadcast"
      @save="onBroadcastSave"
    />
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
/* 行尾取值（成就入口的「已解锁数 / 16」）：与 me.vue 的 .r-v-invite 同一套品牌绿观感 */
.r-v {
  font-size: 26rpx;
  color: #12a150;
  font-weight: 700;
}
.arrow {
  color: #c7ccd2;
  font-size: 34rpx;
  margin-left: 4rpx;
}
</style>
