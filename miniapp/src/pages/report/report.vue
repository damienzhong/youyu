<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { categoryReport, memberReport, dimensionReport, trendReport, monthlyDigest, monthRange, shiftMonth } from '../../api/report'
import { listAllCategories, listAllTransactionsByMonth } from '../../api/aggregate'
import { buildCategoryLabelMap } from '../../api/category'
import { useLedgerStore } from '../../stores/ledger'
import { formatAmount, categoryEmoji, currentMonth, monthLabel } from '../../utils/format'
import { guessIcon } from '../../utils/icons'
import { STORAGE_KEYS } from '../../utils/config'
import {
  DIGEST_TIMEOUT_MS,
  resolveDigestState,
  digestStatusText,
  shortDate,
  drawDigestPoster,
  posterMoney
} from '../../utils/digest'

const ledgerStore = useLedgerStore()

const KINDS = [
  { value: 'expense', label: '支出' },
  { value: 'income', label: '收入' }
]

// 统计维度：分类 / 项目 / 商家 / 标签
const DIMS = [
  { value: 'category', label: '分类' },
  { value: 'project', label: '项目' },
  { value: 'merchant', label: '商家' },
  { value: 'tag', label: '标签' }
]
const dim = ref('category')
// 「全部账本」聚合仅支持分类维度（维度报表按当前账本）。
const showDims = computed(() => !ledgerStore.isAll)
const dimLabel = computed(() => DIMS.find((d) => d.value === dim.value)?.label || '分类')

const kind = ref('expense')
const month = ref(currentMonth())
const total = ref('0.00')
const rows = ref([])
const members = ref([])
const trend = ref([])
const loading = ref(false)

// 智能月报（需求 1、10）：与其它报表相互独立的响应式状态。
// digest 承载九模块数据包；digestVisible 控制月报区块是否展示。
// 仅具体账本 + 已登录时请求；失败或超时静默隐藏，不影响其它报表。
const digest = ref(null)
const digestVisible = ref(false)

// 月报配图（海报，需求 8）：前端 canvas 渲染 → 临时文件 → 保存/分享。
// posterImage 为出图成功后的临时文件路径；posterVisible 控制预览弹层；
// posterBusy 防止重复点击出图。海报仅绘制 digest 当前账本字段，绝不含邮箱/令牌/其它账本数据。
const POSTER_CANVAS_ID = 'digestPoster'
const POSTER_W = 600
const POSTER_H = 800
const posterImage = ref('')
const posterVisible = ref(false)
const posterBusy = ref(false)

// 趋势（近 6 个月，截至所选月）；仅具体账本展示
const showTrend = computed(() => !ledgerStore.isAll)
const trendMax = computed(() =>
  trend.value.reduce((m, p) => Math.max(m, Number(p.income), Number(p.expense)), 0) || 1
)
function barH(v) {
  return Math.max((Number(v) / trendMax.value) * 100, 1.5)
}
function trendMonthLabel(ym) {
  return Number(ym.split('-')[1]) + '月'
}

// 协作账本（非「全部」）展示成员占比（支出/收入随当前类别）。
const showMembers = computed(
  () => !ledgerStore.isAll && ledgerStore.current?.type === 'COLLABORATIVE'
)

const COLORS = ['#12a150', '#0ea5e9', '#f59e0b', '#f0553d', '#8b5cf6', '#1677ff', '#f7b500']
function colorAt(i) {
  return COLORS[i % COLORS.length]
}

// ── 智能月报区块展示辅助（任务 9）───────────────────────────────
// 均对 digest 的空/缺省字段做兜底，保证空模块不报错、优雅渲染。
// 月状态徽标文案 digestStatusText / 日期紧凑串 shortDate 由 utils/digest 提供（单一事实源）。
// 分类排行仅取 Top 5（保持区块紧凑）。
const digestTop = computed(() => (digest.value?.categoryRanking || []).slice(0, 5))
// 趋势迷你图：以每日收入/支出最大值为基准做高度归一化。
const digestTrendMax = computed(
  () =>
    (digest.value?.trend || []).reduce(
      (m, p) => Math.max(m, Number(p.income), Number(p.expense)),
      0
    ) || 1
)
function digestBarH(v) {
  return Math.max((Number(v) / digestTrendMax.value) * 100, 1.5)
}
// 预算状态徽标文案（口径同后端 OK/WARN/OVER）。
function budgetStatusText(s) {
  if (s === 'OVER') return '已超支'
  if (s === 'WARN') return '接近预算'
  if (s === 'OK') return '正常'
  return ''
}
// 智能月报数据加载与静默降级（需求 1.8、1.9、10）。
// - 未登录（无 token）或全部账本聚合视图：不请求、不展示。
// - 5000ms 超时（Promise.race + 定时器）；失败或超时 → digestVisible=false 静默隐藏，
//   不弹阻断性错误，不影响分类占比/趋势等既有报表。
// - 与主 load() 的 try/catch 相互独立：本函数自带 try/catch，异常不冒泡。
async function loadDigest() {
  const token = uni.getStorageSync(STORAGE_KEYS.token)
  const targetMonth = month.value
  // 决策与降级核心抽到 utils/digest.resolveDigestState（纯逻辑，单一事实源、可测试）：
  // - 未登录 / 全部账本聚合视图 → 不请求、不展示；
  // - 5000ms 超时或失败 → 静默隐藏，不影响其它报表；
  // - stale（请求期间切了账本/月份）→ 跳过应用，避免过期数据覆盖新结果。
  const state = await resolveDigestState({
    isLoggedIn: !!token,
    isAll: ledgerStore.isAll,
    fetchDigest: () => monthlyDigest(targetMonth),
    timeoutMs: DIGEST_TIMEOUT_MS,
    isStale: () => month.value !== targetMonth || ledgerStore.isAll
  })
  if (state.stale) return
  digest.value = state.digest
  digestVisible.value = state.digestVisible
}

// ── 月报配图（海报）生成 / 保存 / 分享（需求 8）──────────────────
// 入口点击 → 用 canvas 绘制卡片 → canvasToTempFilePath 出图 → 预览弹层。
// 关键约束：卡片仅取 digest（当前账本九模块）字段；出图失败仅 toast 提示，
// 不改变月报数据与页面其余展示（不进入错误态，需求 8.6）。

// 海报绘制逻辑（含白名单字段抽取）抽到 utils/digest.drawDigestPoster（单一事实源、可测试）。
// posterMoney / digestStatusText / shortDate 亦由 utils/digest 提供。卡片仅绘制 digest
// 当前账本字段，绝不含邮箱/令牌/其它账本数据（需求 8.3）。

// 生成月报配图：绘制 → draw 完成回调 → 出图为临时文件 → 打开预览。
function generatePoster() {
  if (!digest.value || posterBusy.value) return
  posterBusy.value = true
  try {
    const ctx = uni.createCanvasContext(POSTER_CANVAS_ID)
    drawDigestPoster(ctx, digest.value, { width: POSTER_W, height: POSTER_H, money: posterMoney })
    // draw(true, cb)：reserve=true 保留已有绘制；回调在实际绘制完成后触发，此时才可出图。
    ctx.draw(true, () => {
      uni.canvasToTempFilePath({
        canvasId: POSTER_CANVAS_ID,
        width: POSTER_W,
        height: POSTER_H,
        success(res) {
          posterImage.value = res.tempFilePath
          posterVisible.value = true
          posterBusy.value = false
        },
        fail() {
          // 出图失败：仅提示，月报数据与页面其余内容保持正常展示（需求 8.6）。
          posterBusy.value = false
          uni.showToast({ title: '生成失败', icon: 'none' })
        }
      })
    })
  } catch (e) {
    posterBusy.value = false
    uni.showToast({ title: '生成失败', icon: 'none' })
  }
}

function closePoster() {
  posterVisible.value = false
}

// 保存到相册：授权被拒时引导去设置开启权限，页面不进入错误态（需求 8.5）。
function savePoster() {
  if (!posterImage.value) return
  uni.saveImageToPhotosAlbum({
    filePath: posterImage.value,
    success() {
      uni.showToast({ title: '已保存到相册', icon: 'success' })
    },
    fail(err) {
      const msg = String(err && err.errMsg ? err.errMsg : '')
      // 相册授权被拒：showModal 引导去授权，不弹错误、不改变页面状态。
      if (/auth\s*deny|authorize|auth denied|permission/i.test(msg)) {
        uni.showModal({
          title: '需要相册权限',
          content: '保存月报配图需要访问相册权限，请在设置中开启后重试。',
          confirmText: '去设置',
          cancelText: '取消',
          success(r) {
            if (r.confirm) {
              uni.openSetting({ fail() {} })
            }
          }
        })
      } else if (!/cancel/i.test(msg)) {
        uni.showToast({ title: '保存失败', icon: 'none' })
      }
    }
  })
}

// 分享月报配图：优先直接分享图片（微信 showShareImageMenu），
// 不可用时提示使用右上角菜单转发（showShareMenu 已在 onShow 开启）。
function sharePoster() {
  if (!posterImage.value) return
  if (typeof uni.showShareImageMenu === 'function') {
    uni.showShareImageMenu({
      path: posterImage.value,
      fail() {
        uni.showToast({ title: '请点右上角转发', icon: 'none' })
      }
    })
  } else {
    uni.showToast({ title: '请点右上角转发分享', icon: 'none' })
  }
}

async function load() {
  // 月报与其它报表相互独立：并行发起、独立降级，不参与下方主 try/catch。
  loadDigest()
  loading.value = true
  try {
    if (ledgerStore.isAll) {
      await loadAllAggregate()
      members.value = []
      trend.value = []
    } else {
      const { from, to } = monthRange(month.value)
      if (dim.value === 'category') {
        const res = await categoryReport(from, to, kind.value)
        total.value = res.totalExpense
        rows.value = res.categories || []
      } else {
        const res = await dimensionReport(from, to, dim.value, kind.value)
        total.value = res.total
        // 归一化为与分类行相同的结构，复用列表渲染。
        rows.value = (res.items || []).map((it) => ({
          categoryId: it.id,
          categoryName: it.name,
          amount: it.amount,
          percentage: it.percentage,
          count: it.count
        }))
      }
      members.value = showMembers.value
        ? (await memberReport(from, to, kind.value)).members || []
        : []
      try {
        const tr = await trendReport(shiftMonth(month.value, -5), month.value)
        trend.value = tr.months || []
      } catch (e) {
        trend.value = []
      }
    }
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

// 全部账本：跨账本客户端聚合分类占比
async function loadAllAggregate() {
  const [cats, txs] = await Promise.all([
    listAllCategories(),
    listAllTransactionsByMonth(month.value)
  ])
  const nameMap = buildCategoryLabelMap(cats)
  const wanted = kind.value === 'income' ? 'income' : 'expense'
  const byCat = new Map()
  let totalCents = 0
  for (const t of txs) {
    if (t.type !== wanted) continue
    const cents = Math.round(Number(t.amount) * 100)
    totalCents += cents
    const key = t.categoryId ?? 0
    const cur = byCat.get(key) || { categoryId: t.categoryId, amount: 0, count: 0 }
    cur.amount += cents
    cur.count += 1
    byCat.set(key, cur)
  }
  total.value = (totalCents / 100).toFixed(2)
  const list = [...byCat.values()].map((c) => ({
    categoryId: c.categoryId,
    categoryName: nameMap[c.categoryId] || '未分类',
    amount: (c.amount / 100).toFixed(2),
    percentage: totalCents > 0 ? Number(((c.amount / totalCents) * 100).toFixed(2)) : 0,
    count: c.count
  }))
  list.sort((a, b) => Number(b.amount) - Number(a.amount))
  rows.value = list
}

onShow(() => {
  uni.hideTabBar({ animation: false, fail() {} })
  // 开启右上角转发菜单，供月报配图分享（需求 8.4）。
  uni.showShareMenu({ withShareTicket: false, fail() {} })
  load()
})

function selectKind(k) {
  kind.value = k
  load()
}
function selectDim(d) {
  if (dim.value === d) return
  dim.value = d
  rows.value = []
  load()
}
// 维度报表行 → 跳到「明细」tab 并按该维度项筛选（分类维度暂无对应筛选，不跳转）。
function onRowTap(r) {
  if (ledgerStore.isAll || dim.value === 'category' || r.categoryId == null) return
  try {
    uni.setStorageSync('youyu_records_filter', {
      dim: dim.value,
      id: r.categoryId,
      name: r.categoryName,
      month: month.value
    })
  } catch (e) {
    /* ignore */
  }
  uni.navigateTo({ url: '/pages/records/records' })
}
function prevMonth() {
  month.value = shiftMonth(month.value, -1)
  load()
}
function nextMonth() {
  month.value = shiftMonth(month.value, 1)
  load()
}
</script>

<template>
  <view class="page">
    <view class="kinds">
      <view
        v-for="k in KINDS"
        :key="k.value"
        class="kind"
        :class="{ active: kind === k.value }"
        @click="selectKind(k.value)"
      >
        {{ k.label }}
      </view>
    </view>

    <!-- 概览卡 -->
    <view class="total-card">
      <view class="month-bar">
        <text class="nav" @click="prevMonth">‹</text>
        <text class="month">{{ monthLabel(month) }}</text>
        <text class="nav" @click="nextMonth">›</text>
      </view>
      <text class="total-label">{{ kind === 'expense' ? '总支出' : '总收入' }}</text>
      <text class="total-value">¥{{ formatAmount(total) }}</text>
    </view>

    <!--
      智能月报区块（任务 9 渲染；任务 10 海报）。
      渲染条件：v-if="digestVisible && digest"（仅具体账本 + 已登录且加载成功时展示）。
      数据来源：digest（九模块数据包）；降级逻辑见 loadDigest()。
      所有子模块对空/缺省字段做兜底，空模块按空/缺省语义渲染，不报错。
    -->
    <view v-if="digestVisible && digest" class="digest">
      <!-- 头部：目标月标识 YYYY-MM + 月状态徽标 -->
      <view class="dg-head">
        <text class="dg-title">智能月报</text>
        <view class="dg-head-right">
          <text class="dg-month">{{ digest.month }}</text>
          <text
            class="dg-badge"
            :class="digest.monthStatus === 'final' ? 'final' : 'partial'"
          >{{ digestStatusText(digest.monthStatus) }}</text>
        </view>
      </view>

      <!-- 收入 / 支出 / 结余 -->
      <view class="dg-stats">
        <view class="dg-stat">
          <text class="dg-stat-label">收入</text>
          <text class="dg-stat-value inc">¥{{ formatAmount(digest.income) }}</text>
        </view>
        <view class="dg-stat">
          <text class="dg-stat-label">支出</text>
          <text class="dg-stat-value exp">¥{{ formatAmount(digest.expense) }}</text>
        </view>
        <view class="dg-stat">
          <text class="dg-stat-label">结余</text>
          <text
            class="dg-stat-value"
            :class="Number(digest.netBalance) < 0 ? 'exp' : 'net'"
          >¥{{ formatAmount(digest.netBalance) }}</text>
        </view>
      </view>

      <!-- 消费趋势迷你图（按日收入/支出）；空趋势时显示轻提示 -->
      <view class="dg-block">
        <text class="dg-block-title">消费趋势</text>
        <view v-if="digest.trend && digest.trend.length" class="dg-spark">
          <view v-for="p in digest.trend" :key="p.date" class="dg-spark-col">
            <view class="dg-spark-pair">
              <view class="dg-sbar inc" :style="{ height: digestBarH(p.income) + '%' }"></view>
              <view class="dg-sbar exp" :style="{ height: digestBarH(p.expense) + '%' }"></view>
            </view>
          </view>
        </view>
        <text v-else class="dg-empty">本月暂无消费记录</text>
      </view>

      <!-- 分类排行 Top 5；空排行时显示轻提示 -->
      <view class="dg-block">
        <text class="dg-block-title">分类排行</text>
        <view v-if="digestTop.length" class="dg-rank">
          <view v-for="(c, i) in digestTop" :key="c.categoryId ?? i" class="dg-rank-row">
            <text class="dg-rank-no" :style="{ background: colorAt(i) }">{{ i + 1 }}</text>
            <text class="dg-rank-name">{{ c.categoryName || '未分类' }}</text>
            <text class="dg-rank-pct">{{ c.percentage }}%</text>
            <text class="dg-rank-amount">¥{{ formatAmount(c.amount) }}</text>
          </view>
        </view>
        <text v-else class="dg-empty">本月暂无支出分类</text>
      </view>

      <!-- 预算情况：已设预算展示明细（含前瞻）；未设预算显示占位 -->
      <view class="dg-block">
        <text class="dg-block-title">预算情况</text>
        <template v-if="digest.budget && digest.budget.hasBudget">
          <view class="dg-budget-head">
            <text class="dg-budget-used">已用 {{ digest.budget.usedPercent }}%</text>
            <text
              v-if="budgetStatusText(digest.budget.status)"
              class="dg-badge"
              :class="digest.budget.status === 'OVER' ? 'over' : (digest.budget.status === 'WARN' ? 'warn' : 'ok')"
            >{{ budgetStatusText(digest.budget.status) }}</text>
          </view>
          <view class="dg-budget-grid">
            <view class="dg-bg-item">
              <text class="dg-bg-label">总预算</text>
              <text class="dg-bg-value">¥{{ formatAmount(digest.budget.totalBudget) }}</text>
            </view>
            <view class="dg-bg-item">
              <text class="dg-bg-label">已支出</text>
              <text class="dg-bg-value">¥{{ formatAmount(digest.budget.spent) }}</text>
            </view>
            <view class="dg-bg-item">
              <text class="dg-bg-label">剩余</text>
              <text class="dg-bg-value">¥{{ formatAmount(digest.budget.remaining) }}</text>
            </view>
          </view>
          <view v-if="digest.budget.forecast" class="dg-forecast">
            <text class="dg-forecast-line">剩余 {{ digest.budget.forecast.daysLeft }} 天，日均可用 ¥{{ formatAmount(digest.budget.forecast.dailyAvailable) }}</text>
            <text
              class="dg-forecast-line"
              :class="{ over: digest.budget.forecast.projectedOver }"
            >预计月末结余 ¥{{ formatAmount(digest.budget.forecast.projectedBalance) }}{{ digest.budget.forecast.projectedOver ? '（预计超支）' : '' }}</text>
          </view>
        </template>
        <text v-else class="dg-empty">未设置预算</text>
      </view>

      <!-- 最大单笔消费：无支出时占位 -->
      <view class="dg-block">
        <text class="dg-block-title">最大单笔</text>
        <view v-if="digest.largestExpense" class="dg-largest">
          <view class="dg-largest-main">
            <text class="dg-largest-cat">{{ digest.largestExpense.categoryName || '未分类' }}</text>
            <text class="dg-largest-amount">¥{{ formatAmount(digest.largestExpense.amount) }}</text>
          </view>
          <view class="dg-largest-sub">
            <text class="dg-largest-date">{{ shortDate(digest.largestExpense.date) }}</text>
            <text v-if="digest.largestExpense.note" class="dg-largest-note">{{ digest.largestExpense.note }}</text>
          </view>
        </view>
        <text v-else class="dg-empty">无</text>
      </view>

      <!-- 最省钱的一周：无完整周分段时占位 -->
      <view class="dg-block">
        <text class="dg-block-title">最省钱的一周</text>
        <view v-if="digest.mostFrugalWeek" class="dg-frugal">
          <text class="dg-frugal-range">{{ shortDate(digest.mostFrugalWeek.startDate) }} ~ {{ shortDate(digest.mostFrugalWeek.endDate) }}</text>
          <text class="dg-frugal-amount">¥{{ formatAmount(digest.mostFrugalWeek.expense) }}</text>
        </view>
        <text v-else class="dg-empty">无</text>
      </view>

      <!-- 生成月报配图入口（需求 8.1）：月报加载成功后展示 -->
      <view class="dg-poster-entry" :class="{ busy: posterBusy }" @click="generatePoster">
        <text class="dg-poster-icon">🖼️</text>
        <text class="dg-poster-text">{{ posterBusy ? '生成中…' : '生成月报配图' }}</text>
      </view>
    </view>

    <!-- 统计维度：分类 / 项目 / 商家 / 标签 -->
    <scroll-view v-if="showDims" scroll-x class="dims" :show-scrollbar="false">
      <text
        v-for="d in DIMS"
        :key="d.value"
        class="dim"
        :class="{ on: dim === d.value }"
        @click="selectDim(d.value)"
      >{{ d.label }}</text>
    </scroll-view>

    <!-- 收支趋势（近半年） -->
    <view v-if="showTrend && trend.length" class="trend-card">
      <view class="tc-head">
        <text class="tc-title">近半年收支趋势</text>
        <view class="tc-legend">
          <text class="lg"><text class="dot inc"></text>收入</text>
          <text class="lg"><text class="dot exp"></text>支出</text>
        </view>
      </view>
      <view class="bars">
        <view v-for="p in trend" :key="p.month" class="bcol">
          <view class="bpair">
            <view class="bar inc" :style="{ height: barH(p.income) + '%' }"></view>
            <view class="bar exp" :style="{ height: barH(p.expense) + '%' }"></view>
          </view>
          <text class="blabel">{{ trendMonthLabel(p.month) }}</text>
        </view>
      </view>
    </view>

    <view v-if="!rows.length && !loading" class="empty">
      当月暂无{{ dim === 'category' ? '' : dimLabel + '归属的' }}{{ kind === 'expense' ? '支出' : '收入' }}
    </view>

    <view class="list" v-if="rows.length">
      <view v-for="(r, i) in rows" :key="r.categoryId ?? i" class="row" @click="onRowTap(r)">
        <view class="cat-ic">
          <AppIcon :name="guessIcon(r.categoryName, kind)" :size="40" />
        </view>
        <view class="row-body">
          <view class="row-head">
            <text class="row-name">{{ r.categoryName || '未分类' }}</text>
            <text class="row-amount">¥{{ formatAmount(r.amount) }}</text>
          </view>
          <view class="bar-bg">
            <view class="bar" :style="{ width: r.percentage + '%', background: colorAt(i) }"></view>
          </view>
          <view class="row-foot">
            <text class="row-pct">{{ r.percentage }}%</text>
            <text class="row-count">{{ r.count }} 笔</text>
          </view>
        </view>
        <text v-if="showDims && dim !== 'category'" class="row-caret">›</text>
      </view>
    </view>

    <!-- 协作账本：成员支出占比 -->
    <template v-if="showMembers && members.length">
      <text class="section-title">{{ kind === 'expense' ? '成员支出' : '成员收入' }}</text>
      <view class="list">
        <view v-for="(m, i) in members" :key="m.userId ?? i" class="row">
          <text class="row-ic member-ic" :style="{ background: colorAt(i) }">
            {{ (m.displayName || '?').slice(0, 1).toUpperCase() }}
          </text>
          <view class="row-body">
            <view class="row-head">
              <text class="row-name">{{ m.displayName || '未知' }}</text>
              <text class="row-amount">¥{{ formatAmount(m.amount) }}</text>
            </view>
            <view class="bar-bg">
              <view class="bar" :style="{ width: m.percentage + '%', background: colorAt(i) }"></view>
            </view>
            <view class="row-foot">
              <text class="row-pct">{{ m.percentage }}%</text>
              <text class="row-count">{{ m.count }} 笔</text>
            </view>
          </view>
        </view>
      </view>
    </template>

    <view style="height:180rpx;"></view>

    <!--
      月报配图离屏 canvas（需求 8.2）：定位到屏幕外，仅用于绘制出图，不参与页面视觉布局。
      逻辑尺寸 600x800（与 POSTER_W/POSTER_H 一致）。仅绘制 digest 当前账本字段。
    -->
    <canvas
      :canvas-id="POSTER_CANVAS_ID"
      class="poster-canvas"
      :style="{ width: POSTER_W + 'px', height: POSTER_H + 'px' }"
    ></canvas>

    <!-- 月报配图预览弹层：出图成功后展示，提供保存 / 分享入口（需求 8.4） -->
    <view v-if="posterVisible" class="poster-mask" @click="closePoster">
      <view class="poster-sheet" @click.stop>
        <image v-if="posterImage" class="poster-preview" :src="posterImage" mode="widthFix" />
        <view class="poster-actions">
          <view class="poster-btn save" @click="savePoster">保存到相册</view>
          <view class="poster-btn share" @click="sharePoster">分享图片</view>
        </view>
        <view class="poster-close" @click="closePoster">关闭</view>
      </view>
    </view>

    <TabBar active="report" />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
}
.kinds {
  display: flex;
  background: #fff;
  border-radius: 20rpx;
  overflow: hidden;
  margin-bottom: 24rpx;
}
.kind {
  flex: 1;
  text-align: center;
  padding: 28rpx 0;
  font-size: 30rpx;
  color: #6b7280;
}
.kind.active {
  background: #12a150;
  color: #fff;
  font-weight: 700;
}
.total-card {
  border-radius: 28rpx;
  padding: 32rpx 36rpx 40rpx;
  margin-bottom: 24rpx;
  color: #fff;
  background: linear-gradient(150deg, #22c55e, #12a150 55%, #0b6b34);
  box-shadow: 0 20rpx 44rpx rgba(22, 163, 74, 0.26);
}
.month-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 40rpx;
  margin-bottom: 20rpx;
}
.nav {
  font-size: 44rpx;
  padding: 0 20rpx;
  opacity: 0.9;
}
.month {
  font-size: 30rpx;
  font-weight: 700;
}
.total-label {
  font-size: 24rpx;
  opacity: 0.9;
}
.total-value {
  display: block;
  margin-top: 8rpx;
  font-size: 64rpx;
  font-weight: 800;
}
.dims {
  white-space: nowrap;
  margin-bottom: 20rpx;
}
.dim {
  display: inline-block;
  padding: 12rpx 30rpx;
  margin-right: 14rpx;
  border-radius: 999rpx;
  background: #fff;
  font-size: 26rpx;
  color: #5b6470;
  box-shadow: 0 4rpx 12rpx rgba(20, 24, 28, 0.04);
}
.dim.on {
  background: #12a150;
  color: #fff;
  font-weight: 700;
}
.trend-card {
  background: #fff;
  border-radius: 24rpx;
  padding: 28rpx 24rpx 20rpx;
  margin-bottom: 24rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.tc-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20rpx;
}
.tc-title {
  font-size: 28rpx;
  font-weight: 700;
  color: #16181c;
}
.tc-legend {
  display: flex;
  gap: 20rpx;
}
.lg {
  font-size: 22rpx;
  color: #6b7280;
  display: flex;
  align-items: center;
  gap: 8rpx;
}
.dot {
  width: 14rpx;
  height: 14rpx;
  border-radius: 4rpx;
}
.dot.inc { background: #12a150; }
.dot.exp { background: #f0553d; }
.bars {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  height: 200rpx;
}
.bcol {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
  height: 100%;
}
.bpair {
  flex: 1;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 6rpx;
  width: 100%;
}
.bar {
  width: 16rpx;
  border-radius: 6rpx 6rpx 0 0;
  min-height: 4rpx;
}
.bar.inc { background: linear-gradient(180deg, #24bd6a, #12a150); }
.bar.exp { background: linear-gradient(180deg, #f47a66, #f0553d); }
.blabel {
  font-size: 20rpx;
  color: #9aa2ad;
}
.empty {
  margin-top: 120rpx;
  text-align: center;
  color: #9ca3af;
  font-size: 28rpx;
}
.section-title {
  display: block;
  font-size: 26rpx;
  font-weight: 700;
  color: #1f2937;
  margin: 28rpx 8rpx 14rpx;
}
.member-ic {
  color: #fff;
  font-weight: 700;
}
.list {
  background: #fff;
  border-radius: 24rpx;
  padding: 12rpx 28rpx;
}
.row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 26rpx 0;
  border-top: 1rpx solid #eef0f2;
}
.list .row:first-child {
  border-top: none;
}
.row-ic {
  width: 72rpx;
  height: 72rpx;
  border-radius: 20rpx;
  text-align: center;
  line-height: 72rpx;
  font-size: 34rpx;
  flex: 0 0 auto;
}
.cat-ic {
  width: 72rpx;
  height: 72rpx;
  border-radius: 20rpx;
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}
.row-body {
  flex: 1;
  min-width: 0;
}
.row-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 12rpx;
}
.row-name {
  font-size: 28rpx;
  color: #1f2937;
  font-weight: 600;
}
.row-amount {
  font-size: 28rpx;
  font-weight: 700;
  color: #1f2937;
}
.bar-bg {
  height: 14rpx;
  background: #f0f0f0;
  border-radius: 8rpx;
  overflow: hidden;
}
.bar {
  height: 100%;
  border-radius: 8rpx;
}
.row-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 10rpx;
}
.row-pct {
  font-size: 22rpx;
  color: #6b7280;
  font-weight: 600;
}
.row-count {
  font-size: 22rpx;
  color: #bbb;
}
.row-caret {
  flex: 0 0 auto;
  color: #c0c4cc;
  font-size: 40rpx;
  padding-left: 8rpx;
}

/* ── 智能月报区块 ─────────────────────────────── */
.digest {
  background: #fff;
  border-radius: 24rpx;
  padding: 28rpx 28rpx 20rpx;
  margin-bottom: 24rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.dg-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 24rpx;
}
.dg-title {
  font-size: 30rpx;
  font-weight: 800;
  color: #16181c;
}
.dg-head-right {
  display: flex;
  align-items: center;
  gap: 14rpx;
}
.dg-month {
  font-size: 26rpx;
  color: #6b7280;
  font-weight: 600;
}
.dg-badge {
  font-size: 20rpx;
  padding: 4rpx 16rpx;
  border-radius: 999rpx;
  font-weight: 700;
}
.dg-badge.partial {
  background: #e6f6ee;
  color: #12a150;
}
.dg-badge.final {
  background: #eef0f2;
  color: #6b7280;
}
.dg-badge.ok {
  background: #e6f6ee;
  color: #12a150;
}
.dg-badge.warn {
  background: #fff4e5;
  color: #f59e0b;
}
.dg-badge.over {
  background: #fdece9;
  color: #f0553d;
}
.dg-stats {
  display: flex;
  gap: 16rpx;
  margin-bottom: 24rpx;
}
.dg-stat {
  flex: 1;
  background: #f7f8fa;
  border-radius: 18rpx;
  padding: 20rpx 16rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.dg-stat-label {
  font-size: 22rpx;
  color: #9aa2ad;
}
.dg-stat-value {
  font-size: 30rpx;
  font-weight: 800;
  color: #1f2937;
}
.dg-stat-value.inc {
  color: #12a150;
}
.dg-stat-value.exp {
  color: #f0553d;
}
.dg-stat-value.net {
  color: #1677ff;
}
.dg-block {
  padding: 20rpx 0;
  border-top: 1rpx solid #eef0f2;
}
.dg-block-title {
  display: block;
  font-size: 26rpx;
  font-weight: 700;
  color: #1f2937;
  margin-bottom: 16rpx;
}
.dg-empty {
  display: block;
  font-size: 24rpx;
  color: #bbb;
}
/* 趋势迷你图 */
.dg-spark {
  display: flex;
  align-items: flex-end;
  gap: 4rpx;
  height: 120rpx;
}
.dg-spark-col {
  flex: 1;
  height: 100%;
  display: flex;
  align-items: flex-end;
  justify-content: center;
}
.dg-spark-pair {
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 2rpx;
  width: 100%;
  height: 100%;
}
.dg-sbar {
  width: 6rpx;
  border-radius: 3rpx 3rpx 0 0;
  min-height: 2rpx;
}
.dg-sbar.inc {
  background: #24bd6a;
}
.dg-sbar.exp {
  background: #f47a66;
}
/* 分类排行 */
.dg-rank-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 12rpx 0;
}
.dg-rank-no {
  width: 36rpx;
  height: 36rpx;
  border-radius: 10rpx;
  text-align: center;
  line-height: 36rpx;
  font-size: 22rpx;
  font-weight: 700;
  color: #fff;
  flex: 0 0 auto;
}
.dg-rank-name {
  flex: 1;
  min-width: 0;
  font-size: 26rpx;
  color: #1f2937;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.dg-rank-pct {
  font-size: 22rpx;
  color: #9aa2ad;
  flex: 0 0 auto;
}
.dg-rank-amount {
  font-size: 26rpx;
  font-weight: 700;
  color: #1f2937;
  flex: 0 0 auto;
}
/* 预算 */
.dg-budget-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16rpx;
}
.dg-budget-used {
  font-size: 26rpx;
  font-weight: 700;
  color: #1f2937;
}
.dg-budget-grid {
  display: flex;
  gap: 16rpx;
}
.dg-bg-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.dg-bg-label {
  font-size: 22rpx;
  color: #9aa2ad;
}
.dg-bg-value {
  font-size: 26rpx;
  font-weight: 700;
  color: #1f2937;
}
.dg-forecast {
  margin-top: 16rpx;
  padding: 16rpx;
  background: #f7f8fa;
  border-radius: 14rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.dg-forecast-line {
  font-size: 22rpx;
  color: #6b7280;
}
.dg-forecast-line.over {
  color: #f0553d;
  font-weight: 700;
}
/* 最大单笔 / 最省钱的一周 */
.dg-largest-main,
.dg-frugal {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.dg-largest-cat {
  font-size: 28rpx;
  color: #1f2937;
  font-weight: 600;
}
.dg-largest-amount,
.dg-frugal-amount {
  font-size: 30rpx;
  font-weight: 800;
  color: #f0553d;
}
.dg-largest-sub {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 8rpx;
}
.dg-largest-date,
.dg-frugal-range {
  font-size: 22rpx;
  color: #9aa2ad;
}
.dg-largest-note {
  font-size: 22rpx;
  color: #6b7280;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.dg-frugal-amount {
  color: #12a150;
}
/* 生成月报配图入口 */
.dg-poster-entry {
  margin-top: 24rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12rpx;
  padding: 24rpx 0;
  border-radius: 18rpx;
  background: linear-gradient(150deg, #22c55e, #12a150 55%, #0b6b34);
  box-shadow: 0 10rpx 24rpx rgba(22, 163, 74, 0.22);
}
.dg-poster-entry.busy {
  opacity: 0.6;
}
.dg-poster-icon {
  font-size: 30rpx;
}
.dg-poster-text {
  font-size: 28rpx;
  font-weight: 700;
  color: #fff;
}

/* 离屏 canvas：移出可视区域，仅用于出图 */
.poster-canvas {
  position: fixed;
  left: -9999px;
  top: 0;
  z-index: -1;
}

/* 月报配图预览弹层 */
.poster-mask {
  position: fixed;
  left: 0;
  top: 0;
  right: 0;
  bottom: 0;
  background: rgba(0, 0, 0, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 999;
  padding: 48rpx;
}
.poster-sheet {
  width: 100%;
  max-width: 560rpx;
  background: #fff;
  border-radius: 24rpx;
  padding: 28rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 24rpx;
}
.poster-preview {
  width: 100%;
  border-radius: 16rpx;
}
.poster-actions {
  display: flex;
  gap: 20rpx;
  width: 100%;
}
.poster-btn {
  flex: 1;
  text-align: center;
  padding: 24rpx 0;
  border-radius: 16rpx;
  font-size: 28rpx;
  font-weight: 700;
}
.poster-btn.save {
  background: #12a150;
  color: #fff;
}
.poster-btn.share {
  background: #eef6f0;
  color: #12a150;
}
.poster-close {
  font-size: 26rpx;
  color: #9aa2ad;
  padding: 8rpx 0;
}
</style>
