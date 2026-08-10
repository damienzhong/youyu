<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useAuthStore } from '../../stores/auth'
import { useLedgerStore } from '../../stores/ledger'
import { useThemeStore } from '../../stores/theme'
import { listAccounts, accountDisplayName } from '../../api/account'
import { listCategories, buildCategoryLabelMap, buildCategoryIconMap, buildCategoryColorMap } from '../../api/category'
import { resolveIcon } from '../../utils/icons'
import { listTransactionsByMonth } from '../../api/transaction'
import { listTags } from '../../api/tag'
import { listAllAccounts, listAllCategories, listAllTransactionsByMonth } from '../../api/aggregate'
import { budgetOverview } from '../../api/budget'
import { createLedger, joinLedger, renameLedger, listMembers } from '../../api/ledger'
import { fetchSuggestions } from '../../api/suggestion'
import { fetchRecurringPendingItems } from '../../api/recurring'
import { pendingCountOf, pendingBadgeText } from '../../utils/recurring'
import {
  SUGGEST_TIMEOUT_MS,
  shouldFetchSuggestions,
  pickVisibleSuggestions,
  buildRecordUrl
} from '../../utils/suggestion'
import { formatAmount, categoryEmoji, dayKeyOf, dayLabel, currentMonth } from '../../utils/format'

const auth = useAuthStore()
const ledgerStore = useLedgerStore()
const themeStore = useThemeStore()

const month = ref(currentMonth())
const loaded = ref(false)
const accounts = ref([])
const accountMap = ref({})
const categoryMap = ref({})
const categoryIconMap = ref({})
const categoryColorMap = ref({})
const transactions = ref([])
const budget = ref(null)
const memberMap = ref({})
const tagNameById = ref({})

// 记账推荐候选（至多 3 条；<2 条不展示卡）。纯只读派生，点候选仅跳转预填、绝不入账。
const suggestions = ref([])
// 账本页与首页一致，只展示前 2 条候选。
const topSuggestions = computed(() => suggestions.value.slice(0, 2))
// 请求序号：账本切换/多次 onShow 时丢弃过期响应，避免旧账本候选覆盖新账本。
let suggestSeq = 0

// 周期待确认入口角标：当前账本 PENDING 期数（0 或加载失败 / 未登录 / 聚合视图时隐藏入口）。
const recurringPendingCount = ref(0)
const recurringBadgeText = computed(() => pendingBadgeText(recurringPendingCount.value))
// 请求序号：账本切换/多次 onShow 丢弃过期响应，避免旧账本数量覆盖新账本。
let recurringSeq = 0

const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'
const isAll = computed(() => ledgerStore.isAll)
const isCollab = computed(() => !ledgerStore.isAll && ledgerStore.current?.type === 'COLLABORATIVE')
// AA 账本：多人分摊，不设月预算（需求 1.3）；首页隐藏预算相关入口与进度卡。
const isAa = computed(() => !ledgerStore.isAll && ledgerStore.current?.type === 'AA')
const isHistory = computed(() => month.value !== currentMonth())

const heroDateLabel = computed(() => {
  const [y, m] = month.value.split('-')
  return `${y}年${Number(m)}月`
})

const totals = computed(() => {
  let income = 0
  let expense = 0
  for (const t of transactions.value) {
    if (t.type === 'income') income += Number(t.amount)
    else if (t.type === 'expense') expense += Number(t.amount)
  }
  return { income, expense, net: income - expense }
})
const heroValueText = computed(() => {
  const n = totals.value.net
  return (n < 0 ? '-¥' : '¥') + formatAmount(Math.abs(n))
})

// 预算视图（仅具体账本；AA 账本不设预算，需求 1.3）
const budgetView = computed(() => {
  if (isAll.value || isAa.value) return null
  const ov = budget.value
  if (!ov || !ov.hasBudget) return { hasBudget: false }
  const pct = Number(ov.usedPercent) || 0
  const status = pct >= 100 ? 'over' : pct >= 80 ? 'warn' : 'normal'
  return {
    hasBudget: true,
    pct,
    widthPct: Math.min(pct, 100),
    status,
    spent: ov.spent,
    total: ov.totalBudget,
    remaining: ov.remaining
  }
})

// 协作账本本月成员支出
const memberExpenses = computed(() => {
  if (!isCollab.value) return []
  const by = new Map()
  for (const t of transactions.value) {
    if (t.type !== 'expense' || t.createdBy == null) continue
    by.set(t.createdBy, (by.get(t.createdBy) || 0) + Number(t.amount))
  }
  return [...by.entries()]
    .map(([uid, amt]) => ({ userId: uid, name: memberMap.value[uid] || '成员' + uid, amount: amt }))
    .sort((a, b) => b.amount - a.amount)
})

// 全部聚合：账本名映射，用于流水来源标签
const ledgerNameMap = computed(() =>
  Object.fromEntries((ledgerStore.ledgers || []).map((l) => [l.id, l.name]))
)

async function load() {
  try {
    if (isAll.value) {
      const [accs, cats, txs] = await Promise.all([
        listAllAccounts(),
        listAllCategories(),
        listAllTransactionsByMonth(month.value)
      ])
      categoryMap.value = buildCategoryLabelMap(cats)
      categoryIconMap.value = buildCategoryIconMap(cats)
      categoryColorMap.value = buildCategoryColorMap(cats)
      accounts.value = accs
      accountMap.value = Object.fromEntries(accs.map((a) => [a.id, accountDisplayName(a)]))
      transactions.value = txs
      budget.value = null
      memberMap.value = {}
      tagNameById.value = {} // 全部账本聚合下不展示标签(跨账本 id 不可靠)
    } else {
      const [accs, cats, txs] = await Promise.all([
        listAccounts(),
        listCategories(),
        listTransactionsByMonth(month.value)
      ])
      categoryMap.value = buildCategoryLabelMap(cats)
      categoryIconMap.value = buildCategoryIconMap(cats)
      categoryColorMap.value = buildCategoryColorMap(cats)
      accounts.value = accs
      accountMap.value = Object.fromEntries(accs.map((a) => [a.id, accountDisplayName(a)]))
      transactions.value = txs
      // AA 账本不设预算（需求 1.3），跳过预算拉取。
      if (isAa.value) budget.value = null
      else loadBudget()
      if (isCollab.value) {
        try {
          const ms = await listMembers(ledgerStore.currentLedgerId)
          memberMap.value = Object.fromEntries(ms.map((m) => [m.userId, m.displayName || '用户' + m.userId]))
        } catch (e) {
          memberMap.value = {}
        }
      } else {
        memberMap.value = {}
      }
      try {
        const ts = await listTags()
        tagNameById.value = Object.fromEntries(ts.map((t) => [t.id, t.name]))
      } catch (e) {
        tagNameById.value = {}
      }
    }
    loaded.value = true
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  }
}

async function loadBudget() {
  try {
    budget.value = await budgetOverview(month.value)
  } catch (e) {
    budget.value = null
  }
}

// ---------- 记账推荐 ----------
// 3000ms 超时包裹：超时即 reject，交由调用方静默降级（需求 7.2）。
function withTimeout(promise, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject({ code: 'SUGGEST_TIMEOUT' }), ms)
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

// 拉取当前账本推荐候选。聚合视图/未登录不请求；<2 或失败/超时静默隐藏，重试 0 次（需求 5.3/7.2/7.4/7.5）。
function loadSuggestions() {
  if (!shouldFetchSuggestions(auth.isLoggedIn, isAll.value)) {
    suggestions.value = []
    return
  }
  const seq = ++suggestSeq
  suggestions.value = [] // 请求期间不占位，等真实候选到达再渲染（需求 1.5）
  withTimeout(fetchSuggestions(ledgerStore.currentLedgerId), SUGGEST_TIMEOUT_MS)
    .then((res) => {
      if (seq !== suggestSeq) return // 过期响应（已切账本/已重发）丢弃
      suggestions.value = pickVisibleSuggestions(res && res.suggestions)
    })
    .catch(() => {
      if (seq !== suggestSeq) return
      suggestions.value = [] // 失败/超时静默降级，不弹提示、不重试、不影响首页其余模块
    })
}

// 展示辅助：标题（分类名，已删则按方向兜底）、方向、带符号金额。
function suggestTitle(s) {
  // 优先用首页已构建的分类全路径映射（如「交通 / 过路费」），与流水列表口径一致；
  // 分类已删或映射缺失时回退后端返回的分类名，再回退方向文案。
  return categoryMap.value[s.categoryId] || s.categoryName || (s.type === 'income' ? '收入' : '支出')
}
function suggestDir(s) {
  return s.type === 'income' ? '收入' : '支出'
}
function suggestAmt(s) {
  const sign = s.type === 'income' ? '+' : '-'
  return sign + formatAmount(s.amount)
}

// ---------- 周期待确认入口 ----------
// 独立拉取当前账本 PENDING 待确认项，取列表长度为角标数字。GET 会触发后端懒生成，
// 故数字反映「已到期待确认」期数。聚合视图 / 未登录不请求；任何失败静默降级为 0（隐藏入口），
// 绝不阻断首页其余模块渲染（需求 5.1）。账本切换 onShow 重跑，据切换后账本重拉。
function loadRecurringPending() {
  if (!auth.isLoggedIn || isAll.value) {
    recurringPendingCount.value = 0
    return
  }
  const seq = ++recurringSeq
  fetchRecurringPendingItems()
    .then((items) => {
      if (seq !== recurringSeq) return // 过期响应（已切账本/已重发）丢弃
      recurringPendingCount.value = pendingCountOf(items)
    })
    .catch(() => {
      if (seq !== recurringSeq) return
      recurringPendingCount.value = 0 // 失败 / 未登录静默降级，不弹提示、不影响首页其余模块
    })
}

// 点入口进入待确认列表页；跳转失败提示并停留原页。
function goRecurringPending() {
  uni.navigateTo({
    url: '/pages/recurringpending/recurringpending',
    fail() {
      uni.showToast({ title: '打开待确认列表失败', icon: 'none' })
    }
  })
}

// 点候选：仅 navigateTo 带预填参数进入记账页，绝不调用任何写接口 / 不创建交易（需求 4.2）。
// 跳转失败则提示并停留原页（需求 4.7）。
function pickSuggestion(s) {
  uni.navigateTo({
    url: buildRecordUrl(s),
    fail() {
      uni.showToast({ title: '打开记账页失败', icon: 'none' })
    }
  })
}

function onMonthChange(e) {
  month.value = e.detail.value
  load()
}

onShow(async () => {
  // 隐藏原生 H5/小程序 tabBar，只显示自定义 <TabBar>（custom:true 在 H5 不生效）
  uni.hideTabBar({ animation: false, fail() {} })
  if (!auth.isLoggedIn) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  try {
    await ledgerStore.load()
  } catch (e) {
    /* ignore */
  }
  // AA 账本首页独立（hero 三口径 + 分摊流水，需求 1.2/5.2）：切到 AA 账本时改用专用首页。
  if (!ledgerStore.isAll && ledgerStore.current?.type === 'AA') {
    uni.redirectTo({ url: '/pages/aahome/aahome' })
    return
  }
  load()
  // 推荐独立拉取：与首页其余模块解耦，任何失败都不影响 load()（需求 7.3）。
  // 账本切换会 reLaunch 首页 → onShow 重跑，据切换后账本重拉（需求 5.2）；记账返回也在此刷新（需求 5.5）。
  loadSuggestions()
  // 周期待确认角标独立拉取：与首页其余模块解耦，onShow 刷新（从待确认列表返回也在此更新）。
  loadRecurringPending()
})

// ---------- 按日分组 ----------
const grouped = computed(() => {
  const groups = []
  let cur = null
  for (const t of transactions.value) {
    const day = dayKeyOf(t.occurredAt)
    if (!cur || cur.day !== day) {
      cur = { day, label: dayLabel(day), income: 0, expense: 0, items: [] }
      groups.push(cur)
    }
    cur.items.push(t)
    if (t.type === 'income') cur.income += Number(t.amount)
    else if (t.type === 'expense') cur.expense += Number(t.amount)
  }
  return groups
})

const PALETTE = ['#e5793a', '#8b78e0', '#2eb8a6', '#3aa0d0', '#e0609a', '#5b8def', '#f0a13b', '#3ba55d']
function categoryColor(t) {
  if (t.type === 'income') return '#12a150'
  if (t.type === 'transfer') return '#8a94a6'
  const label = categoryMap.value[t.categoryId] || ''
  let h = 0
  for (let i = 0; i < label.length; i++) h = (h + label.charCodeAt(i)) >>> 0
  return PALETTE[h % PALETTE.length]
}
function iconOf(t) {
  if (t.type === 'transfer') return '🔁'
  return categoryEmoji(categoryMap.value[t.categoryId], t.type)
}
// 交易行图标 key（统一线性图标）。
function iconKeyOf(t) {
  if (t.type === 'transfer') return 'transfer'
  return resolveIcon(categoryIconMap.value[t.categoryId], categoryMap.value[t.categoryId], t.type)
}
// 交易行分类图标背景色：转账用默认色，收支取分类 icon_color（缺省由 CategoryIcon 兜底）。
function iconColorOf(t) {
  if (t.type === 'transfer') return ''
  return categoryColorMap.value[t.categoryId] || ''
}
function tagNamesOf(t) {
  if (!Array.isArray(t.tagIds) || !t.tagIds.length) return []
  return t.tagIds.map((id) => tagNameById.value[id]).filter(Boolean)
}
function titleOf(t) {
  if (t.type === 'transfer') {
    return `${accountMap.value[t.sourceAccountId] || '?'} → ${accountMap.value[t.destinationAccountId] || '?'}`
  }
  return categoryMap.value[t.categoryId] || (t.type === 'income' ? '收入' : '支出')
}
function accOf(t) {
  return t.type === 'transfer' ? (t.note || '转账') : accountMap.value[t.accountId] || ''
}
function recorderOf(t) {
  if (isCollab.value && t.createdBy != null && memberMap.value[t.createdBy]) {
    return memberMap.value[t.createdBy]
  }
  return ''
}
function subOf(t) {
  const parts = []
  const acc = accOf(t)
  if (acc) parts.push(acc)
  const r = recorderOf(t)
  if (r) parts.push('👤' + r)
  return parts.join(' · ')
}
function ledgerTag(t) {
  return isAll.value ? ledgerNameMap.value[t.ledgerId] || '' : ''
}
function signed(t) {
  if (t.type === 'expense') return `-${formatAmount(t.amount)}`
  if (t.type === 'income') return `+${formatAmount(t.amount)}`
  return formatAmount(t.amount)
}

// ---------- 账本切换 ----------
const LEDGER_EMOJI = ['🧾', '🐾', '🏠', '💼', '✈️', '🎁', '📚', '🍼']
function ledgerEmoji(i) {
  return LEDGER_EMOJI[i % LEDGER_EMOJI.length]
}
const showLedgerSheet = ref(false)
function pickLedger(id) {
  showLedgerSheet.value = false
  if (id !== ledgerStore.currentLedgerId) {
    ledgerStore.setCurrent(id)
    uni.reLaunch({ url: '/pages/index/index' })
  }
}
const nameSheet = ref(false)
function addLedger() {
  showLedgerSheet.value = false
  nameSheet.value = true
}
async function onCreateLedger(name) {
  if (!name) return
  nameSheet.value = false
  try {
    const l = await createLedger(name)
    ledgerStore.setCurrent(l.id)
    uni.reLaunch({ url: '/pages/index/index' })
  } catch (e) {
    uni.showToast({ title: e.message || '创建失败', icon: 'none' })
  }
}

// 重命名账本（仅自己拥有的账本）
const renameSheet = ref({ visible: false, id: null, value: '' })
function renameLedgerRow(l) {
  showLedgerSheet.value = false
  renameSheet.value = { visible: true, id: l.id, value: l.name }
}
async function onRenameLedger(name) {
  const id = renameSheet.value.id
  renameSheet.value.visible = false
  if (!name || !id) return
  try {
    await renameLedger(id, name)
    await ledgerStore.load()
    uni.showToast({ title: '已改名', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: e.message || '改名失败', icon: 'none' })
  }
}

// 加入协作账本：凭邀请码
const joinSheet = ref(false)
function joinLedgerByCode() {
  showLedgerSheet.value = false
  joinSheet.value = true
}
async function onJoinLedger(code) {
  if (!code) return
  joinSheet.value = false
  try {
    const l = await joinLedger(code)
    ledgerStore.setCurrent(l.id)
    uni.showToast({ title: `已加入「${l.name}」`, icon: 'success' })
    uni.reLaunch({ url: '/pages/index/index' })
  } catch (e) {
    uni.showToast({ title: e.message || '加入失败', icon: 'none' })
  }
}

// ---------- 更多弹层 ----------
const showMore = ref(false)
function nav(url) {
  showMore.value = false
  uni.navigateTo({ url })
}
function goRecord() {
  uni.navigateTo({ url: '/pages/record/record' })
}
// 点击流水弹出账单详情半弹窗（可修改/删除）。
const detailVisible = ref(false)
const detailId = ref(null)
const detailLedgerId = ref(null)
function goEdit(t) {
  detailId.value = t.id
  detailLedgerId.value = t.ledgerId != null ? Number(t.ledgerId) : null
  detailVisible.value = true
}
function onDetailDeleted() {
  load()
}
function goRecords() {
  // 「明细」已从底部 tab 移除，改为普通页 push 进入。
  uni.navigateTo({ url: '/pages/records/records' })
}
</script>

<template>
  <view class="home" :style="themeStore.current.vars">
    <!-- Hero -->
    <view class="top" :class="{ agg: isAll }">
      <view class="statusbar" :style="{ height: statusBarHeight }"></view>
      <view class="hnav">
        <view class="hnav-left" @click="showLedgerSheet = true">
          <text class="hl-name">{{ ledgerStore.currentName }}</text>
          <text v-if="isCollab" class="hl-tag">协作</text>
          <text class="hl-caret">▾</text>
        </view>
      </view>

      <view class="hero-main">
        <picker mode="date" fields="month" :value="month" @change="onMonthChange">
          <text class="bal-k">
            {{ heroDateLabel }}
            <text v-if="isHistory" class="hist">历史</text> ▾
          </text>
        </picker>
        <view class="io-main">
          <view class="io-item">
            <text class="io-k">收入</text>
            <text class="io-v">{{ formatAmount(totals.income) }}</text>
          </view>
          <view class="io-div"></view>
          <view class="io-item">
            <text class="io-k">支出</text>
            <text class="io-v">{{ formatAmount(totals.expense) }}</text>
          </view>
        </view>
        <text class="bal-sub" :class="{ neg: totals.net < 0 }">
          净收支 {{ heroValueText }}
        </text>
      </view>
    </view>

    <!-- 快捷入口 -->
    <view class="quick-wrap">
      <view class="quick">
        <view class="qa" @click="nav('/pages/categories/categories')"><view class="qa-ic"><AppIcon name="tag" :size="42" /></view><text class="qa-l">分类</text></view>
        <view v-if="!isAa" class="qa" @click="nav('/pages/budget/budget')"><view class="qa-ic"><AppIcon name="budget" :size="42" /></view><text class="qa-l">预算</text></view>
        <view class="qa" @click="goRecords"><view class="qa-ic"><AppIcon name="list" :size="42" /></view><text class="qa-l">明细</text></view>
        <view class="qa" @click="nav('/pages/report/report')"><view class="qa-ic"><AppIcon name="chart" :size="42" /></view><text class="qa-l">报表</text></view>
        <view class="qa" @click="showMore = true"><view class="qa-ic"><AppIcon name="more" :size="42" /></view><text class="qa-l">更多</text></view>
      </view>
    </view>

    <!-- 周期待确认入口（有待确认项才显示；带数量角标，点击进入待确认列表） -->
    <view v-if="recurringPendingCount > 0" class="pad">
      <view class="recurring-nudge" @click="goRecurringPending">
        <view class="rn-ic"><AppIcon name="calendar" :size="44" /></view>
        <view class="rn-main">
          <text class="rn-title">周期待确认</text>
          <text class="rn-sub">有 {{ recurringPendingCount }} 期待你确认入账</text>
        </view>
        <text class="rn-badge">{{ recurringBadgeText }}</text>
        <text class="rn-arrow">›</text>
      </view>
    </view>

    <!-- 预算进度卡（仅具体账本；置于推荐卡之上） -->
    <view v-if="budgetView" class="pad">
      <view class="card budget" @click="nav('/pages/budget/budget')">
        <template v-if="budgetView.hasBudget">
          <view class="brow">
            <text class="btitle">{{ Number(month.split('-')[1]) }}月预算</text>
            <text class="bsmall" :class="budgetView.status">
              {{ budgetView.status === 'over' ? '已超支 ¥' + formatAmount(Number(budgetView.spent) - Number(budgetView.total)) : '剩余 ¥' + formatAmount(budgetView.remaining) }}
            </text>
          </view>
          <view class="barbg"><view class="bar" :class="budgetView.status" :style="{ width: budgetView.widthPct + '%' }"></view></view>
          <view class="brow">
            <text class="bsmall">已用 {{ formatAmount(budgetView.spent) }} / {{ formatAmount(budgetView.total) }}</text>
            <text class="bsmall" :class="budgetView.status">
              {{ budgetView.status === 'over' ? '超支 ' : budgetView.status === 'warn' ? '预警 ' : '' }}{{ budgetView.pct }}%
            </text>
          </view>
        </template>
        <template v-else>
          <view class="brow"><text class="btitle">{{ Number(month.split('-')[1]) }}月预算</text><text class="bsmall">未设置</text></view>
          <view class="barbg"><view class="bar" style="width:0"></view></view>
          <view class="brow"><text class="bsmall">设置预算，月末不透支</text><text class="bsmall link">去设置 ›</text></view>
        </template>
      </view>
    </view>

    <!-- 记账推荐卡（预算卡下方；≥2 条才渲染，点候选去记账页预填不入账） -->
    <view v-if="suggestions.length >= 2" class="pad">
      <view class="card sug">
        <view class="sug-h">
          <text class="sug-t">✨ 猜你要记</text>
          <text class="sug-why">按常记 · 点一下去记账</text>
        </view>
        <view
          v-for="(s, i) in topSuggestions"
          :key="`${s.type}-${s.categoryId}-${s.accountId}-${s.amount}-${i}`"
          class="cand"
          @click="pickSuggestion(s)"
        >
          <CategoryIcon :icon="s.categoryIcon" :name="s.categoryName" :kind="s.type" :size="35" />
          <view class="cand-info">
            <text class="cand-name">{{ suggestTitle(s) }}</text>
            <text class="cand-meta">{{ suggestDir(s) }}</text>
          </view>
          <text class="cand-amt" :class="s.type">{{ suggestAmt(s) }}</text>
          <view class="cand-go" @click.stop="pickSuggestion(s)">去记账</view>
        </view>
      </view>
    </view>

    <!-- 协作：本月成员支出 -->
    <view v-if="isCollab && memberExpenses.length" class="pad">
      <view class="card mcard">
        <view class="brow"><text class="mc-title">本月成员支出</text><text class="bsmall">共 {{ memberExpenses.length }} 人</text></view>
        <view class="chips">
          <view v-for="m in memberExpenses" :key="m.userId" class="chip">
            <text class="av">{{ (m.name || '?').slice(0, 1) }}</text>
            <text>{{ m.name }} ¥{{ formatAmount(m.amount) }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 全部聚合只读提示 -->
    <view v-if="isAll" class="agg-note">聚合视图只读 · 记账将记入「默认账本」</view>

    <!-- 内容区 -->
    <view class="content">
      <!-- 新用户：无账户 -->
      <view v-if="loaded && !accounts.length" class="guide card">
        <text class="g-em">🌱</text>
        <text class="g-t">开始记录第一笔</text>
        <text class="g-s">三步搞定：建账户 → 记一笔 → 看报表</text>
        <view class="g-btn" @click="goRecord">＋ 记一笔</view>
        <text class="g-link" @click="nav('/pages/billimport/billimport')">导入支付宝 / 微信账单 →</text>
      </view>

      <!-- 有账户但本月无流水 -->
      <view v-else-if="loaded && !transactions.length" class="month-empty">
        <text class="me-em">🧾</text>
        <text class="me-t">本月还没有流水</text>
        <text class="me-s">{{ isCollab ? '谁都可以记一笔，记账人会自动标注' : '点下方「＋」记一笔，或点顶部切换月份' }}</text>
      </view>

      <!-- 按日分组流水 -->
      <view v-for="g in grouped" :key="g.day" class="day">
        <view class="day-h">
          <text class="day-date">{{ g.label }}</text>
          <text class="day-sum">收 {{ formatAmount(g.income) }} · 支 {{ formatAmount(g.expense) }}</text>
        </view>
        <view class="tx-list">
          <view v-for="t in g.items" :key="t.id" class="tx" @click="goEdit(t)">
            <CategoryIcon :icon="iconKeyOf(t)" :color="iconColorOf(t)" :size="35" />
            <view class="tx-info">
              <view class="tx-titrow">
                <text class="tx-title">{{ titleOf(t) }}</text>
                <text v-if="ledgerTag(t)" class="tx-ltag">{{ ledgerTag(t) }}</text>
              </view>
              <text class="tx-sub">{{ subOf(t) }}</text>
              <view v-if="tagNamesOf(t).length" class="tx-tags">
                <text v-for="(tn, i) in tagNamesOf(t)" :key="i" class="tx-tag">{{ tn }}</text>
              </view>
            </view>
            <text class="tx-amt" :class="t.type">{{ signed(t) }}</text>
          </view>
        </view>
      </view>
      <view style="height:180rpx;"></view>
    </view>

    <TabBar active="ledger" />

    <!-- 账本切换 -->
    <view v-if="showLedgerSheet" class="mask" @click="showLedgerSheet = false">
      <view class="sheet" @click.stop>
        <view class="sheet-head">
          <text class="sheet-cancel" @click="showLedgerSheet = false">取消</text>
          <text class="sheet-title">选择账本</text>
          <text class="sheet-spacer"></text>
        </view>
        <scroll-view scroll-y class="sheet-list">
          <view class="sheet-item" @click="pickLedger('all')">
            <text class="li-ic">🗂️</text><text class="li-name">全部账本</text>
            <text class="li-radio" :class="{ on: ledgerStore.currentLedgerId === 'all' }"></text>
          </view>
          <view v-for="(l, i) in ledgerStore.ledgers" :key="l.id" class="sheet-item" @click="pickLedger(l.id)">
            <text class="li-ic">{{ ledgerEmoji(i) }}</text>
            <view class="li-name">
              <text>{{ l.name }}</text>
              <text v-if="l.type === 'COLLABORATIVE'" class="li-collab">协作</text>
            </view>
            <text v-if="l.role === 'OWNER'" class="li-edit" @click.stop="renameLedgerRow(l)">改名</text>
            <text class="li-radio" :class="{ on: l.id === ledgerStore.currentLedgerId }"></text>
          </view>
        </scroll-view>
        <view class="sheet-foot">
          <view class="sheet-act" @click="addLedger"><text>＋ 新建账本</text></view>
          <view class="sheet-act join" @click="joinLedgerByCode"><text>🔗 加入账本</text></view>
        </view>
      </view>
    </view>

    <!-- 更多 -->
    <view v-if="showMore" class="mask" @click="showMore = false">
      <view class="sheet" @click.stop>
        <view class="sheet-head">
          <text class="sheet-cancel" @click="showMore = false">取消</text>
          <text class="sheet-title">更多功能</text>
          <text class="sheet-spacer"></text>
        </view>
        <view class="more-grid">
          <view class="mi" @click="nav('/pages/loans/loans')"><view class="mi-ic"><AppIcon name="loan" :size="44" /></view><text class="mi-l">借贷往来</text></view>
          <view class="mi" @click="nav('/pages/billimport/billimport')"><view class="mi-ic"><AppIcon name="import" :size="44" /></view><text class="mi-l">账单导入</text></view>
          <view class="mi" @click="nav('/pages/data/data')"><view class="mi-ic"><AppIcon name="export" :size="44" /></view><text class="mi-l">数据备份</text></view>
          <view class="mi" @click="nav('/pages/categories/categories')"><view class="mi-ic"><AppIcon name="tag" :size="44" /></view><text class="mi-l">分类管理</text></view>
          <view v-if="!isAa" class="mi" @click="nav('/pages/budget/budget')"><view class="mi-ic"><AppIcon name="budget" :size="44" /></view><text class="mi-l">预算管理</text></view>
          <view class="mi" @click="nav('/pages/ledgers/ledgers')"><view class="mi-ic"><AppIcon name="book" :size="44" /></view><text class="mi-l">账本管理</text></view>
        </view>
        <view style="height:calc(20rpx + env(safe-area-inset-bottom));"></view>
      </view>
    </view>

    <InputSheet :visible="nameSheet" title="新建账本" placeholder="账本名称" @update:visible="nameSheet = $event" @confirm="onCreateLedger" />

    <InputSheet :visible="joinSheet" title="加入协作账本" placeholder="输入邀请码" confirm-text="加入" tip="向账本创建者获取邀请码" @update:visible="joinSheet = $event" @confirm="onJoinLedger" />

    <InputSheet :visible="renameSheet.visible" title="重命名账本" placeholder="账本名称" :value="renameSheet.value" @update:visible="renameSheet.visible = $event" @confirm="onRenameLedger" />

    <!-- 账单详情半弹窗 -->
    <TransactionDetailSheet
      v-model:visible="detailVisible"
      :id="detailId"
      :ledger-id="detailLedgerId"
      @deleted="onDetailDeleted"
    />
  </view>
</template>

<style scoped>
.home {
  min-height: 100vh;
  background: var(--c-page-bg, #eef0f2);
}
.top {
  background: var(--c-hero, linear-gradient(155deg, #1fbf63, #0f8a45 78%));
  padding-bottom: 74rpx;
  position: relative;
  overflow: hidden;
}
/* 「全部账本」聚合视图沿用主题页头，保持与具体账本一致的观感 */
.top.agg {
  background: var(--c-hero, linear-gradient(155deg, #1fbf63, #0f8a45 78%));
}
.top::after {
  content: '';
  position: absolute;
  right: -60rpx;
  top: -40rpx;
  width: 320rpx;
  height: 320rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
}
.hnav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 84rpx;
  padding: 0 28rpx;
  color: #fff;
  position: relative;
  z-index: 2;
}
.hnav-left {
  display: flex;
  align-items: center;
  gap: 10rpx;
}
.hl-name {
  font-size: 32rpx;
  font-weight: 800;
}
.hl-tag {
  font-size: 18rpx;
  background: rgba(255, 255, 255, 0.22);
  border-radius: 999rpx;
  padding: 2rpx 12rpx;
}
.hl-caret {
  font-size: 20rpx;
  opacity: 0.9;
}
.hero-main {
  padding: 4rpx 32rpx 0;
  color: #fff;
  position: relative;
  z-index: 2;
}
.bal-k {
  font-size: 22rpx;
  opacity: 0.9;
}
.hist {
  font-size: 18rpx;
  background: rgba(255, 255, 255, 0.22);
  border-radius: 999rpx;
  padding: 2rpx 10rpx;
  margin-left: 4rpx;
}
.io-main {
  display: flex;
  align-items: stretch;
  margin-top: 10rpx;
}
.io-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 2rpx;
}
.io-k {
  font-size: 22rpx;
  opacity: 0.9;
}
.io-v {
  font-size: 60rpx;
  font-weight: 800;
  letter-spacing: -0.02em;
  line-height: 1.15;
}
.io-div {
  width: 1px;
  align-self: center;
  height: 56rpx;
  margin: 0 28rpx;
  background: rgba(255, 255, 255, 0.28);
}
.bal-sub {
  display: inline-block;
  margin-top: 10rpx;
  font-size: 24rpx;
  opacity: 0.92;
}
.bal-sub.neg {
  color: #ffd9d0;
}

.quick-wrap {
  padding: 0 24rpx;
  margin-top: -54rpx;
  position: relative;
  z-index: 3;
}
.quick {
  display: flex;
  background: #fff;
  border-radius: 20rpx;
  padding: 26rpx 6rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.06);
}
.qa {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
}
.qa-ic {
  width: 82rpx;
  height: 82rpx;
  border-radius: 24rpx;
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
}
.qa-l {
  font-size: 22rpx;
  color: #5b6470;
}

.pad {
  padding: 20rpx 24rpx 0;
}
.repay-nudge {
  display: flex;
  align-items: center;
  gap: 18rpx;
  background: #fff;
  border-radius: 18rpx;
  padding: 22rpx 26rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
  border-left: 8rpx solid #f0553d;
}
.rn-ic {
  flex: 0 0 auto;
}
.rn-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.rn-title {
  font-size: 28rpx;
  font-weight: 700;
  color: #16181c;
}
.rn-sub {
  font-size: 22rpx;
  color: #9aa2ad;
}
.rn-arrow {
  color: #c0c4cc;
  font-size: 34rpx;
}
/* 周期待确认入口卡：复用 rn-* 内部结构，品牌绿左侧条 + 红色数量角标 */
.recurring-nudge {
  display: flex;
  align-items: center;
  gap: 18rpx;
  background: #fff;
  border-radius: 18rpx;
  padding: 22rpx 26rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
  border-left: 8rpx solid var(--c-brand, #12a150);
}
.recurring-nudge:active {
  opacity: 0.9;
}
.rn-badge {
  flex: 0 0 auto;
  min-width: 40rpx;
  height: 40rpx;
  padding: 0 12rpx;
  border-radius: 999rpx;
  background: #f0553d;
  color: #fff;
  font-size: 22rpx;
  font-weight: 700;
  text-align: center;
  line-height: 40rpx;
  font-variant-numeric: tabular-nums;
}
.card {
  background: #fff;
  border-radius: 18rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.budget {
  padding: 24rpx 26rpx;
}
.brow {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.btitle {
  font-size: 26rpx;
  font-weight: 700;
  color: #16181c;
}
.bsmall {
  font-size: 22rpx;
  color: #9aa2ad;
}
.bsmall.link {
  color: #0e8a44;
  font-weight: 700;
}
.bsmall.warn {
  color: #f4a72b;
  font-weight: 700;
}
.bsmall.over {
  color: #e5484d;
  font-weight: 700;
}
.barbg {
  height: 14rpx;
  background: #f0f2f5;
  border-radius: 8rpx;
  overflow: hidden;
  margin: 16rpx 0 12rpx;
}
.bar {
  height: 100%;
  border-radius: 8rpx;
  background: var(--c-brand, #12a150);
}
.bar.warn {
  background: #f4a72b;
}
.bar.over {
  background: #e5484d;
}

/* 记账推荐卡 */
.sug {
  padding: 20rpx 26rpx 8rpx;
}
.sug-h {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6rpx;
}
.sug-t {
  font-size: 26rpx;
  font-weight: 800;
  color: #16181c;
}
.sug-why {
  font-size: 20rpx;
  color: #9aa2ad;
}
.cand {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 18rpx 0;
  border-top: 1rpx dashed #eceef1;
}
.cand:first-of-type {
  border-top: none;
}
.cand:active {
  background: #f6f7f9;
}
.cand-ic {
  width: 78rpx;
  height: 78rpx;
  border-radius: 22rpx;
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}
.cand-info {
  flex: 1;
  min-width: 0;
}
.cand-name {
  font-size: 29rpx;
  font-weight: 500;
  color: #16181c;
}
.cand-meta {
  font-size: 22rpx;
  color: #9aa2ad;
  margin-top: 4rpx;
  display: block;
}
.cand-amt {
  font-size: 30rpx;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.cand-amt.expense {
  color: #e5544b;
}
.cand-amt.income {
  color: #12a150;
}
.cand-go {
  margin-left: 18rpx;
  flex: 0 0 auto;
  font-size: 24rpx;
  font-weight: 700;
  color: var(--c-brand-ink, #0e8a44);
  background: var(--c-brand-weak, #e6f6ec);
  border-radius: 999rpx;
  padding: 10rpx 22rpx;
}
.cand-go:active {
  opacity: 0.85;
}

.mcard {
  padding: 22rpx 26rpx;
}
.mc-title {
  font-size: 26rpx;
  font-weight: 700;
  color: #16181c;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 14rpx;
  margin-top: 16rpx;
}
.chip {
  display: flex;
  align-items: center;
  gap: 10rpx;
  background: #f6f7f9;
  border-radius: 999rpx;
  padding: 8rpx 18rpx;
  font-size: 23rpx;
  color: #5b6470;
}
.av {
  width: 34rpx;
  height: 34rpx;
  border-radius: 50%;
  background: var(--c-brand, #12a150);
  color: #fff;
  font-size: 20rpx;
  text-align: center;
  line-height: 34rpx;
}

.agg-note {
  text-align: center;
  font-size: 22rpx;
  color: #9aa2ad;
  padding: 18rpx 24rpx 0;
}

.content {
  padding: 8rpx 24rpx 0;
}
.guide {
  margin-top: 16rpx;
  padding: 40rpx 30rpx;
  text-align: center;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.g-em {
  font-size: 96rpx;
}
.g-t {
  font-size: 34rpx;
  font-weight: 800;
  margin-top: 12rpx;
}
.g-s {
  font-size: 24rpx;
  color: #5b6470;
  margin-top: 10rpx;
}
.g-btn {
  margin-top: 28rpx;
  width: 100%;
  height: 88rpx;
  border-radius: 999rpx;
  background: var(--c-brand, #12a150);
  color: #fff;
  font-size: 30rpx;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 14rpx 30rpx rgba(20, 24, 28, 0.18);
}
.g-link {
  margin-top: 20rpx;
  font-size: 24rpx;
  color: var(--c-brand-ink, #0e8a44);
  font-weight: 700;
}
.month-empty {
  text-align: center;
  padding: 90rpx 0 40rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10rpx;
}
.me-em {
  font-size: 84rpx;
  opacity: 0.9;
}
.me-t {
  font-size: 30rpx;
  font-weight: 700;
  color: #16181c;
}
.me-s {
  font-size: 24rpx;
  color: #9aa2ad;
}

.day {
  margin-bottom: 18rpx;
}
.day-h {
  display: flex;
  justify-content: space-between;
  padding: 12rpx 8rpx;
  font-size: 22rpx;
  color: #9aa2ad;
  font-weight: 600;
}
.tx-list {
  background: #fff;
  border-radius: 18rpx;
  overflow: hidden;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.04);
}
.tx {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 22rpx 26rpx;
  border-top: 1rpx solid #f1f3f5;
}
.tx-list .tx:first-child {
  border-top: none;
}
.tx-ic {
  width: 78rpx;
  height: 78rpx;
  border-radius: 22rpx;
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}
.tx-info {
  flex: 1;
  min-width: 0;
}
.tx-titrow {
  display: flex;
  align-items: center;
  gap: 10rpx;
}
.tx-title {
  font-size: 29rpx;
  font-weight: 500;
  color: #16181c;
}
.tx-ltag {
  font-size: 18rpx;
  color: #9aa2ad;
  background: #f0f2f5;
  border-radius: 999rpx;
  padding: 2rpx 12rpx;
}
.tx-sub {
  font-size: 22rpx;
  color: #9aa2ad;
  margin-top: 4rpx;
}
.tx-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx;
  margin-top: 6rpx;
}
.tx-tag {
  font-size: 18rpx;
  color: var(--c-brand-ink, #0e8a44);
  background: var(--c-brand-weak, #e6f6ec);
  border-radius: 6rpx;
  padding: 2rpx 12rpx;
}
.tx-amt {
  font-size: 30rpx;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.tx-amt.expense {
  color: #e5544b;
}
.tx-amt.income {
  color: #12a150;
}
.tx-amt.transfer {
  color: #8a94a6;
}

.fab {
  position: fixed;
  right: 40rpx;
  bottom: calc(136rpx + env(safe-area-inset-bottom));
  width: 104rpx;
  height: 104rpx;
  border-radius: 50%;
  background: var(--c-hero, linear-gradient(135deg, #18b85a, #0e8a44));
  color: #fff;
  font-size: 62rpx;
  line-height: 104rpx;
  text-align: center;
  box-shadow: 0 14rpx 34rpx rgba(20, 24, 28, 0.28);
  z-index: 200;
}

.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.42);
  display: flex;
  align-items: flex-end;
  /* 高于底部 TabBar（z-index:500），作为模态完整覆盖导航栏，避免弹层底部被遮挡 */
  z-index: 600;
}
.sheet {
  width: 100%;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding-bottom: calc(12rpx + env(safe-area-inset-bottom));
}
.sheet-head {
  display: flex;
  align-items: center;
  padding: 26rpx 32rpx;
  border-bottom: 1rpx solid #f1f3f5;
}
.sheet-cancel {
  font-size: 28rpx;
  color: #9aa2ad;
  width: 90rpx;
}
.sheet-title {
  flex: 1;
  text-align: center;
  font-size: 30rpx;
  font-weight: 800;
  color: #16181c;
}
.sheet-spacer {
  width: 90rpx;
}
.sheet-list {
  max-height: 560rpx;
}
.sheet-item {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 26rpx 36rpx;
}
.li-ic {
  width: 64rpx;
  height: 64rpx;
  border-radius: 16rpx;
  background: #f6f7f9;
  text-align: center;
  line-height: 64rpx;
  font-size: 32rpx;
}
.li-name {
  flex: 1;
  font-size: 30rpx;
  color: #16181c;
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.li-collab {
  font-size: 18rpx;
  color: #b9761a;
  background: #fdf3e2;
  border-radius: 999rpx;
  padding: 2rpx 12rpx;
}
.li-radio {
  width: 36rpx;
  height: 36rpx;
  border-radius: 50%;
  border: 3rpx solid #d1d5db;
  box-sizing: border-box;
}
.li-radio.on {
  border-color: var(--c-brand, #12a150);
  background: radial-gradient(circle at center, var(--c-brand, #12a150) 0, var(--c-brand, #12a150) 9rpx, #fff 10rpx, #fff 100%);
}
.li-edit {
  font-size: 24rpx;
  color: #576b95;
  padding: 6rpx 18rpx;
  margin-right: 8rpx;
  border-radius: 999rpx;
  background: #f6f7f9;
}
.li-edit:active {
  background: #eceef1;
}
.sheet-foot {
  display: flex;
  border-top: 1rpx solid #f1f3f5;
}
.sheet-act {
  flex: 1;
  text-align: center;
  padding: 30rpx;
  font-size: 30rpx;
  color: var(--c-brand-ink, #0e8a44);
  font-weight: 700;
}
.sheet-act.join {
  border-left: 1rpx solid #f1f3f5;
}
.sheet-act:active {
  background: #f6f7f9;
}
.more-grid {
  display: flex;
  flex-wrap: wrap;
  padding: 12rpx 12rpx 0;
}
.mi {
  width: 25%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10rpx;
  padding: 20rpx 0;
}
.mi-ic {
  width: 92rpx;
  height: 92rpx;
  border-radius: 26rpx;
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
}
.mi-l {
  font-size: 22rpx;
  color: #5b6470;
}
</style>
