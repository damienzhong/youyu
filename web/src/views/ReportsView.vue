<script setup lang="ts">
/**
 * 报表页（Reports）。
 *
 * 展示（需求 7.1 本月收支结余；7.2 分类占比；7.4 月度趋势；11.1 响应式；11.5 加载失败保留旧数据+重试）：
 *  - 月份切换（上一月 / 下一月）驱动月度概览与分类占比范围。
 *  - 分类占比环形图（当月支出各分类金额 + 百分比），SVG 绘制，附图例。
 *  - 月度趋势柱状图（近 12 个月每月收入 / 支出），CSS 绘制，响应式。
 *
 * 图表用轻量内联 SVG / CSS，不引入图表库，保证包体精简与全断点响应式。
 */
import { ref, computed, onMounted } from 'vue'
import {
  fetchCategories,
  fetchMonthlyReport,
  fetchCategoryReport,
  fetchTrendReport,
  currentMonth,
  shiftMonth,
  monthRange,
  monthLabel,
  shortMonthLabel,
  formatAmount,
  categoryNameOf,
  type Category,
  type MonthlyReport,
  type CategoryReport,
  type TrendReport,
} from '@/lib/ledger'

/** 分类占比配色（循环使用；末位为「其他/未知」兜底灰）。 */
const PALETTE = [
  '#16a34a', '#0ea5e9', '#f59e0b', '#ef4444', '#8b5cf6',
  '#ec4899', '#14b8a6', '#f97316', '#6366f1', '#84cc16',
]
const REST_COLOR = '#cbd5e1'

/** 趋势图展示的月份数（含当前选中月，向前推 11 个月，合计 12，处于 24 月上限内）。 */
const TREND_MONTHS = 12

const loading = ref(true)
const loadError = ref('')
const loaded = ref(false)

const month = ref(currentMonth())
const categories = ref<Category[]>([])
const monthly = ref<MonthlyReport | null>(null)
const category = ref<CategoryReport | null>(null)
const trend = ref<TrendReport | null>(null)

/** 不允许切到未来月（超过当前自然月）。 */
const atCurrentMonth = computed(() => month.value >= currentMonth())

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  const { from, to } = monthRange(month.value)
  const fromMonth = shiftMonth(month.value, -(TREND_MONTHS - 1))
  try {
    const [cats, mRep, cRep, tRep] = await Promise.all([
      fetchCategories(),
      fetchMonthlyReport(month.value),
      fetchCategoryReport(from, to),
      fetchTrendReport(fromMonth, month.value),
    ])
    categories.value = cats
    monthly.value = mRep
    category.value = cRep
    trend.value = tRep
    loaded.value = true
  } catch (e) {
    // 失败：保留上次已加载数据，仅提示 + 重试（需求 11.5）。
    loadError.value = e instanceof Error ? e.message : '加载失败，请重试'
  } finally {
    loading.value = false
  }
}

function prevMonth() {
  month.value = shiftMonth(month.value, -1)
  load()
}
function nextMonth() {
  if (atCurrentMonth.value) return
  month.value = shiftMonth(month.value, 1)
  load()
}

// === 分类占比环形图 ===

const DONUT_R = 70
const DONUT_C = 2 * Math.PI * DONUT_R // 周长

interface Slice {
  categoryId: number
  name: string
  amount: string
  percentage: number
  color: string
  dash: number // 该段弧长
  offset: number // 起始偏移（负值，SVG dashoffset）
}

/** 由分类占比数据构造环形图分段与图例。 */
const slices = computed<Slice[]>(() => {
  const items = category.value?.categories ?? []
  let acc = 0
  return items.map((it, i) => {
    const dash = (it.percentage / 100) * DONUT_C
    const offset = -acc
    acc += dash
    return {
      categoryId: it.categoryId,
      name: categoryNameOf(categories.value, it.categoryId) || '未分类',
      amount: it.amount,
      percentage: it.percentage,
      color: PALETTE[i % PALETTE.length] ?? REST_COLOR,
      dash,
      offset,
    }
  })
})

const hasCategoryData = computed(() => Number(category.value?.totalExpense ?? '0') > 0 && slices.value.length > 0)

// === 月度趋势柱状图 ===

interface TrendBar {
  month: string
  label: string
  income: number
  expense: number
  incomeH: number // 百分比高度（0–100）
  expenseH: number
}

const trendMax = computed(() => {
  const months = trend.value?.months ?? []
  let max = 0
  for (const m of months) {
    max = Math.max(max, Number(m.income) || 0, Number(m.expense) || 0)
  }
  return max
})

const trendBars = computed<TrendBar[]>(() => {
  const months = trend.value?.months ?? []
  const max = trendMax.value
  return months.map((m) => {
    const income = Number(m.income) || 0
    const expense = Number(m.expense) || 0
    return {
      month: m.month,
      label: shortMonthLabel(m.month),
      income,
      expense,
      incomeH: max > 0 ? (income / max) * 100 : 0,
      expenseH: max > 0 ? (expense / max) * 100 : 0,
    }
  })
})

const hasTrendData = computed(() => trendMax.value > 0)
</script>

<template>
  <section class="reports">
    <header class="rep-head">
      <h1>报表</h1>
      <div class="month-switch">
        <button class="nav-btn" type="button" aria-label="上一月" @click="prevMonth">‹</button>
        <span class="month-label">{{ monthLabel(month) }}</span>
        <button
          class="nav-btn"
          type="button"
          aria-label="下一月"
          :disabled="atCurrentMonth"
          @click="nextMonth"
        >›</button>
      </div>
    </header>

    <!-- 加载失败：提示 + 重试（保留上次数据，需求 11.5） -->
    <div v-if="loadError" class="banner banner-err" role="alert">
      <span>{{ loadError }}</span>
      <button class="link-btn" type="button" @click="load">重试</button>
    </div>

    <p v-if="loading && !loaded" class="text-muted loading">加载中…</p>

    <template v-if="loaded">
      <!-- 本月收支结余（需求 7.1） -->
      <div class="card overview">
        <div class="ov-item">
          <span class="ov-label">收入</span>
          <span class="ov-val income">¥{{ formatAmount(monthly?.totalIncome ?? '0') }}</span>
        </div>
        <div class="ov-item">
          <span class="ov-label">支出</span>
          <span class="ov-val expense">¥{{ formatAmount(monthly?.totalExpense ?? '0') }}</span>
        </div>
        <div class="ov-item">
          <span class="ov-label">结余</span>
          <span class="ov-val" :class="{ neg: Number(monthly?.balance) < 0 }">
            ¥{{ formatAmount(monthly?.balance ?? '0') }}
          </span>
        </div>
      </div>

      <!-- 分类占比环形图（需求 7.2） -->
      <div class="card chart-card">
        <h2>支出分类占比</h2>
        <p v-if="!hasCategoryData" class="text-muted empty">本月暂无支出记录。</p>
        <div v-else class="donut-wrap">
          <svg
            class="donut"
            viewBox="0 0 180 180"
            role="img"
            :aria-label="`本月支出共 ${formatAmount(category?.totalExpense ?? '0')} 元，按分类占比`"
          >
            <g transform="rotate(-90 90 90)">
              <circle cx="90" cy="90" :r="DONUT_R" fill="none" stroke="#f1f5f9" stroke-width="24" />
              <circle
                v-for="s in slices"
                :key="s.categoryId"
                cx="90"
                cy="90"
                :r="DONUT_R"
                fill="none"
                :stroke="s.color"
                stroke-width="24"
                :stroke-dasharray="`${s.dash} ${DONUT_C - s.dash}`"
                :stroke-dashoffset="s.offset"
              >
                <title>{{ s.name }} {{ s.percentage.toFixed(2) }}%</title>
              </circle>
            </g>
            <text x="90" y="84" text-anchor="middle" class="donut-center-label">本月支出</text>
            <text x="90" y="104" text-anchor="middle" class="donut-center-val">
              ¥{{ formatAmount(category?.totalExpense ?? '0') }}
            </text>
          </svg>

          <ul class="legend">
            <li v-for="s in slices" :key="s.categoryId" class="legend-item">
              <span class="legend-dot" :style="{ background: s.color }" aria-hidden="true"></span>
              <span class="legend-name">{{ s.name }}</span>
              <span class="legend-pct text-muted">{{ s.percentage.toFixed(2) }}%</span>
              <span class="legend-amt">¥{{ formatAmount(s.amount) }}</span>
            </li>
          </ul>
        </div>
      </div>

      <!-- 月度趋势柱状图（需求 7.4） -->
      <div class="card chart-card">
        <h2>月度趋势（近 {{ TREND_MONTHS }} 个月）</h2>
        <div class="trend-legend">
          <span class="tl-item"><span class="legend-dot income-dot" aria-hidden="true"></span>收入</span>
          <span class="tl-item"><span class="legend-dot expense-dot" aria-hidden="true"></span>支出</span>
        </div>
        <p v-if="!hasTrendData" class="text-muted empty">所选区间暂无收支记录。</p>
        <div v-else class="trend-chart" role="img" aria-label="近 12 个月每月收入与支出趋势">
          <div v-for="b in trendBars" :key="b.month" class="trend-col">
            <div class="bars">
              <div
                class="bar bar-income"
                :style="{ height: `${b.incomeH}%` }"
                :title="`${b.label} 收入 ¥${formatAmount(b.income)}`"
              ></div>
              <div
                class="bar bar-expense"
                :style="{ height: `${b.expenseH}%` }"
                :title="`${b.label} 支出 ¥${formatAmount(b.expense)}`"
              ></div>
            </div>
            <span class="trend-x">{{ b.label }}</span>
          </div>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.reports {
  padding-bottom: 80px;
}
.rep-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.rep-head h1 {
  margin: 0;
  font-size: 22px;
  color: var(--color-primary);
}
.month-switch {
  display: flex;
  align-items: center;
  gap: 8px;
}
.month-label {
  min-width: 6.5em;
  text-align: center;
  font-size: 15px;
  font-weight: 600;
}
.nav-btn {
  width: 36px;
  height: 36px;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: var(--color-surface);
  font-size: 20px;
  line-height: 1;
  color: var(--color-text);
}
.nav-btn:disabled {
  color: var(--color-border);
  cursor: not-allowed;
}
.nav-btn:not(:disabled):active {
  background: var(--color-bg);
}
.loading {
  padding: 24px 0;
}

.overview {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
.ov-item {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.ov-label {
  font-size: 13px;
  color: var(--color-muted);
}
.ov-val {
  font-size: 17px;
  font-weight: 700;
  overflow-wrap: anywhere;
}
.ov-val.income {
  color: var(--color-primary);
}
.ov-val.expense {
  color: var(--color-danger);
}
.ov-val.neg {
  color: var(--color-danger);
}

.chart-card {
  margin-bottom: 16px;
}
.chart-card h2 {
  margin: 0 0 12px;
  font-size: 16px;
}
.empty {
  text-align: center;
  padding: 16px 0;
}

/* 环形图：窄屏上下堆叠，宽屏左右并排。 */
.donut-wrap {
  display: flex;
  flex-direction: column;
  gap: 16px;
  align-items: center;
}
.donut {
  width: min(220px, 70vw);
  height: auto;
  flex-shrink: 0;
}
.donut-center-label {
  font-size: 12px;
  fill: var(--color-muted);
}
.donut-center-val {
  font-size: 15px;
  font-weight: 700;
  fill: var(--color-text);
}
.legend {
  list-style: none;
  margin: 0;
  padding: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}
.legend-dot {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  flex-shrink: 0;
}
.legend-name {
  flex: 1;
  min-width: 0;
  overflow-wrap: anywhere;
}
.legend-pct {
  font-size: 13px;
  white-space: nowrap;
}
.legend-amt {
  font-weight: 600;
  white-space: nowrap;
}
@media (min-width: 640px) {
  .donut-wrap {
    flex-direction: row;
    align-items: center;
  }
  .legend {
    flex: 1;
  }
}

/* 趋势图例 */
.trend-legend {
  display: flex;
  gap: 16px;
  margin-bottom: 12px;
  font-size: 13px;
  color: var(--color-muted);
}
.tl-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.income-dot {
  background: var(--color-primary);
}
.expense-dot {
  background: var(--color-danger);
}

/* 柱状图：等分列填满宽度，避免横向滚动（需求 11.1）。 */
.trend-chart {
  display: flex;
  align-items: flex-end;
  gap: clamp(2px, 1.2vw, 8px);
  height: 160px;
  padding-top: 8px;
}
.trend-col {
  flex: 1 1 0;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  height: 100%;
}
.bars {
  flex: 1;
  width: 100%;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  gap: 2px;
}
.bar {
  width: 42%;
  max-width: 14px;
  min-height: 2px;
  border-radius: 3px 3px 0 0;
  transition: height 0.2s ease;
}
.bar-income {
  background: var(--color-primary);
}
.bar-expense {
  background: var(--color-danger);
}
.trend-x {
  margin-top: 6px;
  font-size: 11px;
  color: var(--color-muted);
  white-space: nowrap;
}

.banner {
  margin: 0 0 16px;
  padding: 10px 12px;
  border-radius: var(--radius);
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.banner-err {
  background: #fef2f2;
  color: var(--color-danger);
}
.link-btn {
  border: none;
  background: none;
  color: inherit;
  text-decoration: underline;
  font-weight: 600;
}
</style>
