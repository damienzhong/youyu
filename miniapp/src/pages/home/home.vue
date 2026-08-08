<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useAuthStore } from '../../stores/auth'
import { useLedgerStore } from '../../stores/ledger'
import { useThemeStore } from '../../stores/theme'
import { fetchGrowthOverview } from '../../api/growth'
import { fetchStreakOverview, fetchStreakSegments } from '../../api/streak'
import { fetchAchievements } from '../../api/achievement'
import { fetchSuggestions } from '../../api/suggestion'
import { levelProgress } from '../../utils/growth'
import { milestoneText, checkinCells } from '../../utils/streak'
import {
  SUGGEST_TIMEOUT_MS,
  shouldFetchSuggestions,
  pickVisibleSuggestions,
  buildRecordUrl
} from '../../utils/suggestion'
import { formatAmount } from '../../utils/format'

/**
 * 首页（总览/情绪价值）：不展示资产总额与账本明细。
 * 欢迎语 + 订阅版本 → 成长等级/最新成就 → 连续记账打卡 → 猜你要记 → 本月发现。
 * 所有数据模块失败均静默降级，不阻断整页。
 */

const auth = useAuthStore()
const ledgerStore = useLedgerStore()
const themeStore = useThemeStore()
const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'

const nickname = computed(() => auth.user?.nickname || '有余用户')
const planLabel = computed(() => {
  const p = auth.user?.plan
  return p === 'pro' ? '专业版' : p === 'lifetime' ? '终身版' : '免费版'
})
const greetWord = computed(() => {
  const h = new Date().getHours()
  if (h < 5) return '夜深了'
  if (h < 11) return '早上好'
  if (h < 13) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})

// —— 成长 / 成就 ——
const growth = ref(null)
const achv = ref(null)
const level = computed(() => {
  const n = Number(growth.value?.level)
  return Number.isFinite(n) && n >= 1 ? n : 1
})
const levelPct = computed(() => Math.round(levelProgress(growth.value) * 100))
const maxed = computed(() => growth.value?.maxLevelReached === true)
const expToNext = computed(() => {
  const n = Number(growth.value?.expToNextLevel)
  return Number.isFinite(n) && n > 0 ? n : null
})
const ringStyle = computed(() => {
  const pct = maxed.value ? 100 : levelPct.value
  const brand = themeStore.current.vars['--c-brand']
  return { background: `conic-gradient(${brand} ${pct}%, #e9edf0 0)` }
})
const unlockedCount = computed(() => {
  const n = Number(achv.value?.unlockedCount)
  return Number.isFinite(n) && n >= 0 ? n : null
})
const latestAchv = computed(() => {
  const list = achv.value?.achievements
  if (!Array.isArray(list)) return null
  const unlocked = list.filter((a) => a && a.unlocked === true)
  if (!unlocked.length) return null
  unlocked.sort((a, b) => String(b.unlockedAt || '').localeCompare(String(a.unlockedAt || '')))
  return unlocked[0]
})

// —— 连续记账 ——
const streak = ref(null)
const segItems = ref([])
const streakDays = computed(() => {
  const n = Number(streak.value?.currentStreakDays)
  return Number.isFinite(n) && n >= 0 ? n : 0
})
const todayDone = computed(() => streak.value?.todayDone === true)
const streakGoal = computed(() => milestoneText(streak.value))
const WD = ['日', '一', '二', '三', '四', '五', '六']
const weekCells = computed(() => {
  const cells = checkinCells(Date.now(), segItems.value).slice(-7)
  return cells.map((c, i) => {
    const last = i === cells.length - 1
    const d = new Date(c.date + 'T00:00:00')
    // 今日的打卡状态以 todayDone 为准（当前段可能还没进历史区间接口）
    const checked = last ? todayDone.value : c.checked
    return { checked, label: last ? '今' : (WD[d.getDay()] || ''), today: last }
  })
})

// —— 猜你要记 ——
const suggestions = ref([])
// 首页只展示 2 条（账本页仍展示至多 3 条，保持不变）。
const topSuggestions = computed(() => suggestions.value.slice(0, 2))
let suggestSeq = 0
function withTimeout(promise, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject({ code: 'SUGGEST_TIMEOUT' }), ms)
    promise.then(
      (v) => { clearTimeout(timer); resolve(v) },
      (e) => { clearTimeout(timer); reject(e) }
    )
  })
}
function loadSuggestions() {
  if (!shouldFetchSuggestions(auth.isLoggedIn, ledgerStore.isAll)) {
    suggestions.value = []
    return
  }
  const seq = ++suggestSeq
  suggestions.value = []
  withTimeout(fetchSuggestions(ledgerStore.currentLedgerId), SUGGEST_TIMEOUT_MS)
    .then((res) => {
      if (seq !== suggestSeq) return
      suggestions.value = pickVisibleSuggestions(res && res.suggestions)
    })
    .catch(() => { if (seq === suggestSeq) suggestions.value = [] })
}
function suggestTitle(s) { return s.categoryName || (s.type === 'income' ? '收入' : '支出') }
function suggestAmt(s) { return (s.type === 'income' ? '+' : '-') + formatAmount(s.amount) }
function pickSuggestion(s) {
  uni.navigateTo({ url: buildRecordUrl(s), fail() { uni.showToast({ title: '打开记账页失败', icon: 'none' }) } })
}

function loadGrowthStreak() {
  fetchGrowthOverview().then((r) => { growth.value = r }).catch(() => {})
  fetchStreakOverview().then((r) => { streak.value = r }).catch(() => {})
  fetchStreakSegments(0, 20).then((r) => { segItems.value = (r && r.items) || [] }).catch(() => {})
  fetchAchievements().then((r) => { achv.value = r }).catch(() => {})
}

onShow(async () => {
  uni.hideTabBar({ animation: false, fail() {} })
  if (!auth.isLoggedIn) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  try { await ledgerStore.load() } catch (e) { /* ignore */ }
  loadGrowthStreak()
  loadSuggestions()
})

function goReminder() { uni.navigateTo({ url: '/pages/reminder/reminder' }) }
function goSettings() { uni.navigateTo({ url: '/pages/account/account' }) }
function goGrowth() { uni.navigateTo({ url: '/pages/growth/growth' }) }
function goAchievement() { uni.navigateTo({ url: '/pages/achievement/achievement' }) }
function goStreak() { uni.navigateTo({ url: '/pages/streak/streak' }) }
function goReport() { uni.navigateTo({ url: '/pages/report/report' }) }
function goPersonality() { uni.navigateTo({ url: '/pages/personality/personality' }) }
function goFun() { uni.navigateTo({ url: '/pages/funanalysis/funanalysis' }) }
function onPlan() { uni.showToast({ title: '当前免费版 · 全部功能免费开放', icon: 'none' }) }
</script>

<template>
  <view class="page" :style="themeStore.current.vars">
    <!-- 欢迎 hero：图标在左上（避开右上微信胶囊） -->
    <view class="hero" :style="{ paddingTop: `calc(${statusBarHeight} + 12rpx)` }">
      <view class="h-top">
        <view class="h-btn" @click="goReminder">🔔</view>
        <view class="h-btn" @click="goSettings">⚙️</view>
      </view>
      <view class="h-greet">
        <text class="hg-t">👋 {{ greetWord }}，{{ nickname }}</text>
        <text class="plan-chip" @click="onPlan">{{ planLabel }}</text>
      </view>
      <text class="h-sub">记好每一笔，日子更有余 ✍️</text>
    </view>

    <!-- 成长等级 / 最新成就（醒目卡，叠在 hero 底部） -->
    <view class="wrap growth-wrap">
      <view class="card growth">
        <view class="g-col" @click="goGrowth">
          <view class="lv-ring" :style="ringStyle"><view class="lv-inner">Lv.{{ level }}</view></view>
          <view class="g-info">
            <text class="g-t">成长等级</text>
            <text class="g-s">{{ maxed ? '已满级' : (expToNext != null ? '距下一级 ' + expToNext + ' 经验' : '记账攒经验升级') }}</text>
          </view>
        </view>
        <view class="g-div"></view>
        <view class="g-col ach" @click="goAchievement">
          <view class="ach-badge">🏅</view>
          <view class="g-info">
            <text class="g-t">最新成就</text>
            <text class="g-v">{{ latestAchv ? latestAchv.name : '待解锁' }}</text>
            <text class="g-s">{{ unlockedCount != null ? '已解锁 ' + unlockedCount + ' 枚 ›' : '去看看 ›' }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 连续记账打卡 -->
    <view class="wrap">
      <view class="card streakcard" @click="goStreak">
        <view class="sc-h">
          <text class="sc-t">🔥 已连续记账 <text class="sc-n">{{ streakDays }}</text> 天</text>
          <text class="sc-m">{{ todayDone ? '今日已记 ✓' : '今天还没记' }}</text>
        </view>
        <view class="week">
          <view v-for="(c, i) in weekCells" :key="i" class="wd" :class="{ done: c.checked, today: c.today }">
            <text class="dot">{{ c.checked ? '✓' : '·' }}</text>
            <text class="wl">{{ c.label }}</text>
          </view>
        </view>
        <text v-if="streakGoal" class="sc-goal">{{ streakGoal }}</text>
        <text v-else class="sc-goal">保持每天记一笔，坚持就有成就 🏅</text>
      </view>
    </view>

    <!-- 猜你要记 -->
    <view v-if="suggestions.length >= 2" class="wrap">
      <view class="blk-h"><text class="blk-t">✨ 猜你要记</text><text class="blk-m">点一下去记账</text></view>
      <view class="card">
        <view
          v-for="(s, i) in topSuggestions"
          :key="`${s.type}-${s.categoryId}-${s.accountId}-${s.amount}-${i}`"
          class="cand"
          @click="pickSuggestion(s)"
        >
          <CategoryIcon :icon="s.categoryIcon" :name="s.categoryName" :kind="s.type" :size="41" />
          <view class="cand-info">
            <text class="cand-name">{{ suggestTitle(s) }}</text>
            <text class="cand-meta">{{ s.type === 'income' ? '收入' : '支出' }} · 常记</text>
          </view>
          <text class="cand-amt" :class="s.type">{{ suggestAmt(s) }}</text>
          <view class="cand-go" @click.stop="pickSuggestion(s)">去记账</view>
        </view>
      </view>
    </view>

    <!-- 本月发现 -->
    <view class="wrap">
      <view class="blk-h"><text class="blk-t">✨ 本月发现</text></view>
      <view class="card">
        <view class="cand" @click="goReport">
          <view class="cand-ic emoji">📊</view>
          <view class="cand-info"><text class="cand-name">本月报表</text><text class="cand-meta">看看这个月钱都去哪了</text></view>
          <text class="disc-go">查看 ›</text>
        </view>
        <view class="cand" @click="goPersonality">
          <view class="cand-ic emoji">🏷️</view>
          <view class="cand-info"><text class="cand-name">消费人格</text><text class="cand-meta">看看你是哪种记账人格</text></view>
          <text class="disc-go">去看 ›</text>
        </view>
        <view class="cand" @click="goFun">
          <view class="cand-ic emoji">🧋</view>
          <view class="cand-info"><text class="cand-name">趣味分析</text><text class="cand-meta">你的消费小趣事</text></view>
          <text class="disc-go">去看 ›</text>
        </view>
      </view>
    </view>

    <view style="height:calc(160rpx + env(safe-area-inset-bottom));"></view>
    <TabBar active="home" />
  </view>
</template>

<style scoped>
.page { min-height: 100vh; background: var(--c-page-bg, #eef0f2); }
.hero {
  background: var(--c-hero, linear-gradient(150deg, #1fbf63, #0f8a45 78%));
  color: #fff;
  padding: 0 30rpx 60rpx;
  position: relative;
  overflow: hidden;
}
.hero::after {
  content: '';
  position: absolute;
  right: -60rpx; top: -40rpx;
  width: 320rpx; height: 320rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.06);
}
.h-top { display: flex; align-items: center; gap: 16rpx; height: 64rpx; position: relative; z-index: 2; }
.h-btn {
  width: 60rpx; height: 60rpx; border-radius: 50%;
  background: rgba(255, 255, 255, 0.14);
  display: flex; align-items: center; justify-content: center;
  font-size: 30rpx;
}
.h-greet { display: flex; align-items: center; gap: 14rpx; position: relative; z-index: 2; margin-top: 12rpx; }
.hg-t { font-size: 38rpx; font-weight: 800; }
.plan-chip {
  font-size: 22rpx; font-weight: 700;
  background: rgba(255, 255, 255, 0.18); color: #fff;
  border-radius: 999rpx; padding: 4rpx 18rpx;
}
.h-sub { display: block; font-size: 26rpx; opacity: 0.8; margin-top: 12rpx; position: relative; z-index: 2; }

.wrap { padding: 0 24rpx; }
.growth-wrap { margin-top: -40rpx; position: relative; z-index: 3; }
.card { background: #fff; border-radius: 24rpx; box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.06); }

/* 成长卡 */
.growth { display: flex; align-items: center; padding: 26rpx 22rpx; }
.g-col { flex: 1; display: flex; align-items: center; gap: 18rpx; min-width: 0; }
.lv-ring {
  width: 92rpx; height: 92rpx; border-radius: 50%; flex: 0 0 auto;
  display: flex; align-items: center; justify-content: center;
}
.lv-inner {
  width: 70rpx; height: 70rpx; border-radius: 50%; background: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 26rpx; font-weight: 800; color: var(--c-brand-ink, #0e8a44);
}
.ach-badge {
  width: 76rpx; height: 76rpx; border-radius: 22rpx; flex: 0 0 auto;
  background: #fdf3e2; display: flex; align-items: center; justify-content: center;
  font-size: 40rpx;
}
.g-info { min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.g-t { font-size: 22rpx; color: #9aa2ad; }
.g-v { font-size: 30rpx; font-weight: 800; color: #16181c; }
.g-s { font-size: 20rpx; color: #9aa2ad; }
.g-div { width: 1rpx; height: 88rpx; background: #eef0f2; margin: 0 18rpx; flex: 0 0 auto; }

/* 连续记账卡 */
.streakcard { padding: 26rpx 28rpx; margin-top: 20rpx; }
.sc-h { display: flex; align-items: baseline; justify-content: space-between; }
.sc-t { font-size: 30rpx; font-weight: 800; color: #16181c; }
.sc-n { color: var(--c-brand, #12a150); font-size: 38rpx; }
.sc-m { font-size: 22rpx; color: #9aa2ad; }
.week { display: flex; justify-content: space-between; margin: 22rpx 2rpx 18rpx; }
.wd { display: flex; flex-direction: column; align-items: center; gap: 10rpx; font-size: 20rpx; color: #9aa2ad; }
.wd .dot {
  width: 56rpx; height: 56rpx; border-radius: 50%; background: #f4f5f7;
  display: flex; align-items: center; justify-content: center;
  font-size: 26rpx; color: #c8ccd2;
}
.wd.done .dot { background: var(--c-brand-weak, #e6f6ec); color: var(--c-brand, #12a150); }
.wd.today .dot { background: var(--c-brand, #12a150); color: #fff; box-shadow: 0 6rpx 14rpx rgba(20, 24, 28, 0.22); }
.wd.today { color: var(--c-brand-ink, #0e8a44); font-weight: 700; }
.sc-goal { display: block; font-size: 24rpx; color: #5b6470; background: #f6f7f9; border-radius: 14rpx; padding: 18rpx 20rpx; }

/* 猜你要记 / 本月发现 */
.blk-h { display: flex; align-items: center; justify-content: space-between; padding: 26rpx 8rpx 12rpx; }
.blk-t { font-size: 28rpx; font-weight: 800; color: #16181c; }
.blk-m { font-size: 22rpx; color: #9aa2ad; }
.cand { display: flex; align-items: center; gap: 20rpx; padding: 22rpx 24rpx; border-top: 1rpx solid #f1f3f5; }
.cand:first-child { border-top: none; }
.cand-ic { width: 78rpx; height: 78rpx; border-radius: 22rpx; background: #f4f5f7; display: flex; align-items: center; justify-content: center; flex: 0 0 auto; }
.cand-ic.emoji { font-size: 38rpx; }
.cand-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4rpx; }
.cand-name { font-size: 30rpx; font-weight: 600; color: #16181c; }
.cand-meta { font-size: 22rpx; color: #9aa2ad; }
.cand-amt { font-size: 30rpx; font-weight: 800; }
.cand-amt.expense { color: #e5563d; }
.cand-amt.income { color: #0f8a45; }
.cand-go { margin-left: 16rpx; flex: 0 0 auto; font-size: 24rpx; font-weight: 700; color: var(--c-brand-ink, #0e8a44); background: var(--c-brand-weak, #e6f6ec); border-radius: 999rpx; padding: 10rpx 22rpx; }
.disc-go { flex: 0 0 auto; font-size: 24rpx; font-weight: 700; color: var(--c-brand-ink, #0e8a44); }
.disc-go.soon { color: #c0c4cc; }
</style>
