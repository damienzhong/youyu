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
import { listTransactionsByMonth } from '../../api/transaction'
import { listAllTransactionsByMonth } from '../../api/aggregate'
import { levelProgress } from '../../utils/growth'
import { milestoneText, checkinCells } from '../../utils/streak'
import {
  SUGGEST_TIMEOUT_MS,
  shouldFetchSuggestions,
  pickVisibleSuggestions,
  buildRecordUrl
} from '../../utils/suggestion'
import { formatAmount, currentMonth, dayKeyOf, dayLabel } from '../../utils/format'

/**
 * 首页（总览/情绪价值）：精简为「一屏看完」。
 * 欢迎语 → 今日收支 + 记账提醒/反馈 → 猜你要记（横向药丸）→ 成长坚持（成长/成就/连续/情绪合并卡）→ 本月发现。
 * 记账入口交给底部凸起「＋」，首页不再放记一笔按钮。所有数据模块失败均静默降级，不阻断整页。
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

// —— 今日收支（当天流水聚合；不列明细，明细去账本页看）——
function todayKey() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
function yesterdayKey() {
  const d = new Date()
  d.setDate(d.getDate() - 1)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const todayExpense = ref(0)
const todayIncome = ref(0)
const todayCount = ref(0)
const yesterdayExpense = ref(0)
const todayLabel = computed(() => `今天 · ${dayLabel(todayKey())}`)
const doneToday = computed(() => todayCount.value > 0)
const todayTag = computed(() =>
  doneToday.value
    ? { text: `已记 ${todayCount.value} 笔 ✓`, cls: 'done' }
    : { text: '还没记账', cls: 'todo' }
)
// 记账提醒/反馈：未记账催记；已记账给昨日对比或保持鼓励。
const nudgeEmoji = computed(() => {
  if (!doneToday.value) return '🔥'
  const saved = yesterdayExpense.value - todayExpense.value
  if (yesterdayExpense.value > 0 && saved < -0.01) return '📈'
  return '👏'
})
const nudgeText = computed(() => {
  if (!doneToday.value) {
    return streakDays.value > 0
      ? `今天还没记账，花 10 秒记一笔，别断了 ${streakDays.value} 天连续`
      : '今天还没记账，花 10 秒记一笔，养成好习惯'
  }
  const saved = yesterdayExpense.value - todayExpense.value
  if (yesterdayExpense.value > 0 && Math.abs(saved) >= 0.01) {
    return saved > 0
      ? `今天比昨天少花了 ¥${formatAmount(saved)}，钱包在感谢你 💚`
      : `今天比昨天多花了 ¥${formatAmount(-saved)}，注意节奏哦`
  }
  return `今天已记 ${todayCount.value} 笔，继续保持 🎉`
})
async function loadToday() {
  try {
    const month = currentMonth()
    const txs = ledgerStore.isAll
      ? await listAllTransactionsByMonth(month)
      : await listTransactionsByMonth(month, ledgerStore.currentLedgerId)
    const tk = todayKey()
    const yk = yesterdayKey()
    let te = 0, ti = 0, tc = 0, ye = 0
    for (const t of txs || []) {
      const k = dayKeyOf(t.occurredAt)
      const amt = Number(t.amount) || 0
      if (k === tk) {
        tc += 1
        if (t.type === 'expense') te += amt
        else if (t.type === 'income') ti += amt
      } else if (k === yk && t.type === 'expense') {
        ye += amt
      }
    }
    todayExpense.value = te
    todayIncome.value = ti
    todayCount.value = tc
    yesterdayExpense.value = ye
  } catch (e) {
    /* 今日收支加载失败静默降级，保持 0 */
  }
}

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
const streakGoal = computed(() => milestoneText(streak.value))
const WD = ['日', '一', '二', '三', '四', '五', '六']
const weekCells = computed(() => {
  const cells = checkinCells(Date.now(), segItems.value).slice(-7)
  return cells.map((c, i) => {
    const last = i === cells.length - 1
    const d = new Date(c.date + 'T00:00:00')
    // 今日打卡状态以「今日是否已记」为准（当前段可能还没进历史区间接口）
    const checked = last ? doneToday.value : c.checked
    return { checked, label: last ? '今' : (WD[d.getDay()] || ''), today: last }
  })
})

// —— 猜你要记（横向快捷药丸，至多 3 条）——
const suggestions = ref([])
const topSuggestions = computed(() => suggestions.value.slice(0, 3))
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
  loadToday()
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

    <!-- 今日收支卡（叠在 hero 底部） -->
    <view class="wrap today-wrap">
      <view class="card today">
        <view class="t-head">
          <text class="t-date">{{ todayLabel }}</text>
          <text class="t-tag" :class="todayTag.cls">{{ todayTag.text }}</text>
        </view>
        <view class="t-io">
          <view class="t-col">
            <text class="t-k">今日支出</text>
            <text class="t-v exp" :class="{ zero: todayExpense <= 0 }">¥{{ formatAmount(todayExpense) }}</text>
          </view>
          <view class="t-div"></view>
          <view class="t-col">
            <text class="t-k">今日收入</text>
            <text class="t-v inc" :class="{ zero: todayIncome <= 0 }">¥{{ formatAmount(todayIncome) }}</text>
          </view>
        </view>
        <view class="nudge">
          <text class="em">{{ nudgeEmoji }}</text>
          <text class="txt">{{ nudgeText }}</text>
        </view>
      </view>
    </view>

    <!-- 猜你要记：横向快捷药丸 -->
    <view v-if="suggestions.length >= 2" class="wrap">
      <view class="blk-h"><text class="blk-t">✨ 猜你要记</text><text class="blk-m">点一下去记账</text></view>
      <scroll-view scroll-x class="quick" :show-scrollbar="false">
        <view
          v-for="(s, i) in topSuggestions"
          :key="`${s.type}-${s.categoryId}-${s.accountId}-${s.amount}-${i}`"
          class="qpill"
          @click="pickSuggestion(s)"
        >
          <CategoryIcon :icon="s.categoryIcon" :name="s.categoryName" :kind="s.type" :size="34" />
          <text class="qn">{{ suggestTitle(s) }}</text>
          <text class="qa" :class="s.type">{{ suggestAmt(s) }}</text>
        </view>
      </scroll-view>
    </view>

    <!-- 成长坚持合并卡：成长等级 + 最新成就 + 连续记账 + 情绪一句 -->
    <view class="wrap">
      <view class="card grow">
        <view class="g-strip">
          <view class="g-col" @click="goGrowth">
            <view class="lv-ring" :style="ringStyle"><view class="lv-inner">Lv.{{ level }}</view></view>
            <view class="g-info">
              <text class="g-t">成长等级</text>
              <text class="g-v">Lv.{{ level }}</text>
              <text class="g-s">{{ maxed ? '已满级' : (expToNext != null ? '距下一级 ' + expToNext + ' 经验' : '记账攒经验') }}</text>
            </view>
          </view>
          <view class="g-sep"></view>
          <view class="g-col" @click="goAchievement">
            <view class="medal">🏅</view>
            <view class="g-info">
              <text class="g-t">最新成就</text>
              <text class="g-v">{{ latestAchv ? latestAchv.name : '待解锁' }}</text>
              <text class="g-s">{{ unlockedCount != null ? '已解锁 ' + unlockedCount + ' 枚 ›' : '去看看 ›' }}</text>
            </view>
          </view>
        </view>
        <view class="g-hr"></view>
        <view class="sc-h" @click="goStreak">
          <text class="sc-t">🔥 已连续记账 <text class="sc-n">{{ streakDays }}</text> 天</text>
          <text class="sc-m" :class="{ done: doneToday }">{{ doneToday ? '今日已记 ✓' : '今天还没记' }}</text>
        </view>
        <text class="mood-line">{{ streakGoal || '保持每天记一笔，坚持就有成就 🏅' }}</text>
        <view class="week">
          <view v-for="(c, i) in weekCells" :key="i" class="wd" :class="{ done: c.checked, today: c.today }">
            <text class="dot">{{ c.checked ? '✓' : '·' }}</text>
            <text class="wl">{{ c.label }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 本月发现 -->
    <view class="wrap">
      <view class="blk-h"><text class="blk-t">✨ 本月发现</text></view>
      <view class="card disc-card">
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
  padding: 0 30rpx 76rpx;
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
.h-top { display: flex; align-items: center; gap: 16rpx; height: 60rpx; position: relative; z-index: 2; }
.h-btn {
  width: 58rpx; height: 58rpx; border-radius: 50%;
  background: rgba(255, 255, 255, 0.14);
  display: flex; align-items: center; justify-content: center;
  font-size: 28rpx;
}
.h-greet { display: flex; align-items: center; gap: 14rpx; position: relative; z-index: 2; margin-top: 8rpx; }
.hg-t { font-size: 36rpx; font-weight: 800; }
.plan-chip {
  font-size: 22rpx; font-weight: 700;
  background: rgba(255, 255, 255, 0.18); color: #fff;
  border-radius: 999rpx; padding: 4rpx 18rpx;
}
.h-sub { display: block; font-size: 25rpx; opacity: 0.8; margin-top: 10rpx; position: relative; z-index: 2; }

.wrap { padding: 0 24rpx; }
.card { background: #fff; border-radius: 22rpx; box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.06); }

/* 今日收支卡 */
.today-wrap { margin-top: -56rpx; position: relative; z-index: 3; }
.today { overflow: hidden; }
.t-head { display: flex; align-items: center; justify-content: space-between; padding: 24rpx 26rpx 0; }
.t-date { font-size: 27rpx; font-weight: 800; color: #16181c; }
.t-tag { font-size: 22rpx; font-weight: 700; padding: 4rpx 18rpx; border-radius: 999rpx; }
.t-tag.todo { background: #fff4e5; color: #c47f16; }
.t-tag.done { background: var(--c-brand-weak, #e6f6ec); color: var(--c-brand-ink, #0e8a44); }
.t-io { display: flex; padding: 18rpx 26rpx 4rpx; }
.t-col { flex: 1; display: flex; flex-direction: column; gap: 6rpx; }
.t-k { font-size: 22rpx; color: #9aa2ad; }
.t-v { font-size: 50rpx; font-weight: 800; letter-spacing: -0.02em; }
.t-v.exp { color: #e5563d; }
.t-v.inc { color: #0f8a45; }
.t-v.zero { color: #c7ccd2; }
.t-div { width: 1rpx; background: #eef0f2; margin: 10rpx 8rpx; }
.nudge {
  display: flex; align-items: center; gap: 14rpx;
  margin: 18rpx 22rpx 22rpx; padding: 18rpx 20rpx;
  background: #f6f7f9; border-radius: 16rpx;
}
.nudge .em { font-size: 34rpx; flex: 0 0 auto; }
.nudge .txt { flex: 1; font-size: 24rpx; color: #5b6470; line-height: 1.45; }

/* 猜你要记：横向药丸 */
.blk-h { display: flex; align-items: center; justify-content: space-between; padding: 24rpx 8rpx 12rpx; }
.blk-t { font-size: 28rpx; font-weight: 800; color: #16181c; }
.blk-m { font-size: 22rpx; color: #9aa2ad; }
.quick { white-space: nowrap; }
.qpill {
  display: inline-flex; align-items: center; gap: 12rpx;
  background: #fff; border-radius: 999rpx; padding: 12rpx 24rpx 12rpx 12rpx;
  margin-right: 14rpx; box-shadow: 0 6rpx 16rpx rgba(20, 24, 28, 0.06);
}
.qpill .qn { font-size: 26rpx; font-weight: 600; color: #16181c; }
.qpill .qa { font-size: 24rpx; font-weight: 700; }
.qpill .qa.expense { color: #e5563d; }
.qpill .qa.income { color: #0f8a45; }

/* 成长坚持合并卡 */
.grow { margin-top: 16rpx; padding: 24rpx 24rpx 26rpx; }
.g-strip { display: flex; align-items: center; }
.g-col { flex: 1; display: flex; align-items: center; gap: 16rpx; min-width: 0; }
.lv-ring {
  width: 84rpx; height: 84rpx; border-radius: 50%; flex: 0 0 auto;
  display: flex; align-items: center; justify-content: center;
}
.lv-inner {
  width: 64rpx; height: 64rpx; border-radius: 50%; background: #fff;
  display: flex; align-items: center; justify-content: center;
  font-size: 24rpx; font-weight: 800; color: var(--c-brand-ink, #0e8a44);
}
.medal {
  width: 84rpx; height: 84rpx; border-radius: 24rpx; flex: 0 0 auto;
  background: #fdf3e2; display: flex; align-items: center; justify-content: center;
  font-size: 40rpx;
}
.g-info { min-width: 0; display: flex; flex-direction: column; gap: 3rpx; }
.g-t { font-size: 21rpx; color: #9aa2ad; }
.g-v { font-size: 28rpx; font-weight: 800; color: #16181c; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; max-width: 200rpx; }
.g-s { font-size: 20rpx; color: #9aa2ad; }
.g-sep { width: 1rpx; height: 76rpx; background: #eef0f2; margin: 0 16rpx; flex: 0 0 auto; }
.g-hr { height: 1rpx; background: #f1f3f5; margin: 22rpx 0; }
.sc-h { display: flex; align-items: baseline; justify-content: space-between; }
.sc-t { font-size: 28rpx; font-weight: 800; color: #16181c; }
.sc-n { color: var(--c-brand, #12a150); font-size: 36rpx; }
.sc-m { font-size: 22rpx; color: #9aa2ad; }
.sc-m.done { color: var(--c-brand-ink, #0e8a44); font-weight: 700; }
.mood-line { display: block; font-size: 23rpx; color: #5b6470; margin-top: 10rpx; line-height: 1.4; }
.week { display: flex; justify-content: space-between; margin: 20rpx 2rpx 0; }
.wd { display: flex; flex-direction: column; align-items: center; gap: 10rpx; font-size: 20rpx; color: #9aa2ad; }
.wd .dot {
  width: 54rpx; height: 54rpx; border-radius: 50%; background: #f4f5f7;
  display: flex; align-items: center; justify-content: center;
  font-size: 24rpx; color: #c8ccd2;
}
.wd.done .dot { background: var(--c-brand-weak, #e6f6ec); color: var(--c-brand, #12a150); }
.wd.today .dot { background: var(--c-brand, #12a150); color: #fff; box-shadow: 0 6rpx 14rpx rgba(20, 24, 28, 0.22); }
.wd.today { color: var(--c-brand-ink, #0e8a44); font-weight: 700; }

/* 本月发现：完整三行列表（图标 + 标题 + 说明 + 箭头） */
.disc-card { overflow: hidden; }
.cand { display: flex; align-items: center; gap: 20rpx; padding: 26rpx 24rpx; border-top: 1rpx solid #f1f3f5; }
.disc-card .cand:first-child { border-top: none; }
.cand-ic { width: 78rpx; height: 78rpx; border-radius: 22rpx; background: #f4f5f7; display: flex; align-items: center; justify-content: center; flex: 0 0 auto; }
.cand-ic.emoji { font-size: 38rpx; }
.cand-info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 4rpx; }
.cand-name { font-size: 30rpx; font-weight: 600; color: #16181c; }
.cand-meta { font-size: 22rpx; color: #9aa2ad; }
.disc-go { flex: 0 0 auto; font-size: 24rpx; font-weight: 700; color: var(--c-brand-ink, #0e8a44); }
</style>
