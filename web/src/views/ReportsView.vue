<script setup lang="ts">
/**
 * 统计页（Reports）—— 对齐竞品重设计。
 *
 * - 顶栏：返回 + 居中「统计」。
 * - 时间维度：周 / 月 / 年 / 自定义（区间导航，不可选未来）。
 * - KPI 绿卡：以「支出」为主视角 + 收入/结余 + 环比（较上一周期支出）。
 * - 收支趋势：按日（周/月/自定义）或按月（年）柱状，支出/收入切换；平方根压缩缩放，
 *   避免单日大额把其它压平（需求 7.4）。
 * - 分类统计：环形图 + 排行榜（图标 / 笔数 / 进度条 / 金额 / 占比），支出/收入切换（需求 7.2、7.3）。
 * - 收支明细：按日/按月的 收入/支出/结余 表（合计置顶，需求 7.1）。
 *
 * 数据一律来自 GET /reports/range（区间总收支 + 有活动自然日明细）与 /reports/category（分类占比+笔数），
 * 年视角在前端把按日明细聚合为按月。金额一律字符串，排除转账（需求 4.12、7.5）。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  fetchCategories,
  fetchRangeReport,
  fetchCategoryReport,
  formatAmount,
  categoryNameOf,
  categoryEmoji,
  type Category,
  type RangeReport,
  type CategoryReport,
} from '@/lib/ledger'

type Period = 'week' | 'month' | 'year' | 'custom'
type Kind = 'expense' | 'income'

/** 分类配色（循环，末位灰兜底）。 */
const PALETTE = [
  '#16a34a', '#0ea5e9', '#f59e0b', '#ef4444', '#8b5cf6',
  '#ec4899', '#14b8a6', '#f97316', '#6366f1', '#84cc16',
]

const router = useRouter()
function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/')
}

// ===== 日期工具（本地时区，面向 CN 用户即 Asia/Shanghai） =====
function pad(n: number): string {
  return String(n).padStart(2, '0')
}
function iso(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
function parseIso(s: string): Date {
  const [y, m, d] = s.split('-').map(Number)
  return new Date(y ?? 1970, (m ?? 1) - 1, d ?? 1)
}
const TODAY_ISO = iso(new Date())

// ===== 视角状态 =====
const period = ref<Period>('month')
const anchor = ref<Date>(new Date()) // 周/月/年 的锚点日期
const customFrom = ref<string>(iso(new Date()))
const customTo = ref<string>(iso(new Date()))
const kind = ref<Kind>('expense') // 分类统计 支出/收入
const trendKind = ref<Kind>('expense') // 趋势图 支出/收入

// ===== 当前区间 { from, to, label } =====
interface Range {
  from: string
  to: string
  label: string
}
function rangeFor(p: Period, a: Date): Range {
  if (p === 'week') {
    const day = (a.getDay() + 6) % 7 // 周一=0
    const start = new Date(a)
    start.setDate(a.getDate() - day)
    const end = new Date(start)
    end.setDate(start.getDate() + 6)
    return {
      from: iso(start),
      to: iso(end),
      label: `${start.getMonth() + 1}月${start.getDate()}日 – ${end.getMonth() + 1}月${end.getDate()}日`,
    }
  }
  if (p === 'year') {
    const y = a.getFullYear()
    return { from: `${y}-01-01`, to: `${y}-12-31`, label: `${y}年` }
  }
  if (p === 'custom') {
    return { from: customFrom.value, to: customTo.value, label: `${customFrom.value} ~ ${customTo.value}` }
  }
  // month
  const y = a.getFullYear()
  const m = a.getMonth()
  const start = new Date(y, m, 1)
  const end = new Date(y, m + 1, 0)
  return { from: iso(start), to: iso(end), label: `${y}年${m + 1}月` }
}
const range = computed<Range>(() => rangeFor(period.value, anchor.value))

/** 上一周期区间（用于环比）。 */
const prevRange = computed<Range>(() => {
  const a = new Date(anchor.value)
  if (period.value === 'week') {
    a.setDate(a.getDate() - 7)
    return rangeFor('week', a)
  }
  if (period.value === 'year') {
    a.setFullYear(a.getFullYear() - 1)
    return rangeFor('year', a)
  }
  if (period.value === 'custom') {
    const f = parseIso(customFrom.value)
    const t = parseIso(customTo.value)
    const spanDays = Math.round((t.getTime() - f.getTime()) / 86400000) + 1
    const pf = new Date(f)
    pf.setDate(f.getDate() - spanDays)
    const pt = new Date(f)
    pt.setDate(f.getDate() - 1)
    return { from: iso(pf), to: iso(pt), label: '上一区间' }
  }
  a.setMonth(a.getMonth() - 1)
  return rangeFor('month', a)
})

const prevLabel = computed(
  () => ({ week: '上周', month: '上月', year: '去年', custom: '上一区间' })[period.value],
)

/** 是否已到最新（区间已含今天或更晚），用于禁用「下一个」。 */
const atLatest = computed(() => range.value.to >= TODAY_ISO)
const showNav = computed(() => period.value !== 'custom')

// ===== 数据 =====
const loading = ref(true)
const loadError = ref('')
const loaded = ref(false)
const categories = ref<Category[]>([])
const rangeData = ref<RangeReport | null>(null)
const categoryData = ref<CategoryReport | null>(null)
const prevExpense = ref('0')

onMounted(async () => {
  try {
    categories.value = await fetchCategories()
  } catch {
    /* 分类名失败不阻断，用占位 */
  }
  await load()
})

async function load() {
  loading.value = true
  loadError.value = ''
  const r = range.value
  const p = prevRange.value
  try {
    const [rep, cat, prevRep] = await Promise.all([
      fetchRangeReport(r.from, r.to),
      fetchCategoryReport(r.from, r.to, kind.value),
      fetchRangeReport(p.from, p.to),
    ])
    rangeData.value = rep
    categoryData.value = cat
    prevExpense.value = prevRep.expense
    loaded.value = true
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败，请重试'
  } finally {
    loading.value = false
  }
}

/** 仅切换分类统计的支出/收入（重取分类，不动其它）。 */
async function switchKind(k: Kind) {
  if (kind.value === k) return
  kind.value = k
  const r = range.value
  try {
    categoryData.value = await fetchCategoryReport(r.from, r.to, k)
  } catch {
    /* 保留旧数据 */
  }
}

function setPeriod(p: Period) {
  if (period.value === p) return
  period.value = p
  anchor.value = new Date()
  load()
}
function step(delta: number) {
  const a = new Date(anchor.value)
  if (period.value === 'week') a.setDate(a.getDate() + delta * 7)
  else if (period.value === 'year') a.setFullYear(a.getFullYear() + delta)
  else a.setMonth(a.getMonth() + delta)
  anchor.value = a
  load()
}
function prev() {
  step(-1)
}
function next() {
  if (atLatest.value) return
  step(1)
}
function applyCustom() {
  if (customFrom.value > customTo.value) {
    const t = customFrom.value
    customFrom.value = customTo.value
    customTo.value = t
  }
  load()
}

// ===== KPI =====
const income = computed(() => rangeData.value?.income ?? '0')
const expense = computed(() => rangeData.value?.expense ?? '0')
const balance = computed(() => rangeData.value?.balance ?? '0')

/** 环比支出：与上一周期比较（百分比，正=增加）。 */
const momPercent = computed<number | null>(() => {
  const prev = Number(prevExpense.value)
  const cur = Number(expense.value)
  if (!(prev > 0)) return null
  return ((cur - prev) / prev) * 100
})

// ===== 收支趋势柱状 =====
interface Bar {
  label: string
  income: number
  expense: number
  h: number // 高度百分比（sqrt 压缩）
  active: boolean
}

/** 年视角把按日明细聚合到 12 个月；其它视角构造 from..to 的完整日轴。 */
const bars = computed<Bar[]>(() => {
  const rep = rangeData.value
  if (!rep) return []
  const useVal = (income: number, expense: number) =>
    trendKind.value === 'expense' ? expense : income

  if (period.value === 'year') {
    const inc = new Array(12).fill(0)
    const exp = new Array(12).fill(0)
    for (const d of rep.days) {
      const m = Number(d.date.split('-')[1]) - 1
      if (m >= 0 && m < 12) {
        inc[m] += Number(d.income)
        exp[m] += Number(d.expense)
      }
    }
    const vals = inc.map((_, i) => useVal(inc[i], exp[i]))
    const max = Math.max(...vals, 0)
    return vals.map((v, i) => ({
      label: `${i + 1}`,
      income: inc[i],
      expense: exp[i],
      h: v <= 0 ? 0 : Math.max(6, Math.round(Math.sqrt(v / max) * 100)),
      active: v > 0,
    }))
  }

  // 周/月/自定义：完整日轴
  const map = new Map(rep.days.map((d) => [d.date, d]))
  const start = parseIso(rep.from)
  const end = parseIso(rep.to)
  const out: Bar[] = []
  const vals: number[] = []
  const cur = new Date(start)
  while (cur <= end) {
    const key = iso(cur)
    const d = map.get(key)
    const inc = d ? Number(d.income) : 0
    const exp = d ? Number(d.expense) : 0
    vals.push(useVal(inc, exp))
    out.push({ label: String(cur.getDate()), income: inc, expense: exp, h: 0, active: false })
    cur.setDate(cur.getDate() + 1)
  }
  const max = Math.max(...vals, 0)
  out.forEach((b, i) => {
    const v = vals[i] ?? 0
    b.h = v <= 0 ? 0 : Math.max(6, Math.round(Math.sqrt(v / max) * 100))
    b.active = v > 0
  })
  return out
})
const hasTrend = computed(() => bars.value.some((b) => b.active))

/** x 轴稀疏刻度（最多 5 个）。 */
const xTicks = computed<string[]>(() => {
  const bs = bars.value
  if (bs.length === 0) return []
  if (bs.length <= 6) return bs.map((b) => b.label)
  const idx = [0, 0.25, 0.5, 0.75, 1].map((r) => Math.round(r * (bs.length - 1)))
  return [...new Set(idx)].map((i) => bs[i]!.label)
})

// ===== 分类环形 + 排行 =====
const DONUT_R = 60
const DONUT_C = 2 * Math.PI * DONUT_R

interface Slice {
  categoryId: number
  name: string
  emoji: string
  amount: string
  percentage: number
  count: number
  color: string
  dash: number
  offset: number
}
const slices = computed<Slice[]>(() => {
  const items = categoryData.value?.categories ?? []
  let acc = 0
  return items.map((it, i) => {
    const dash = (it.percentage / 100) * DONUT_C
    const offset = -acc
    acc += dash
    const name = categoryNameOf(categories.value, it.categoryId) || '未分类'
    return {
      categoryId: it.categoryId,
      name,
      emoji: categoryEmoji(name, kind.value === 'income' ? 'INCOME' : 'EXPENSE'),
      amount: it.amount,
      percentage: it.percentage,
      count: it.count,
      color: PALETTE[i % PALETTE.length] ?? '#cbd5e1',
      dash,
      offset,
    }
  })
})
const hasCategory = computed(() => Number(categoryData.value?.totalExpense ?? '0') > 0 && slices.value.length > 0)
const categoryTotal = computed(() => categoryData.value?.totalExpense ?? '0')

// ===== 收支明细 =====
interface DetailRow {
  label: string
  income: number
  expense: number
}
const detailRows = computed<DetailRow[]>(() => {
  const rep = rangeData.value
  if (!rep) return []
  if (period.value === 'year') {
    const inc = new Array(12).fill(0)
    const exp = new Array(12).fill(0)
    for (const d of rep.days) {
      const m = Number(d.date.split('-')[1]) - 1
      if (m >= 0 && m < 12) {
        inc[m] += Number(d.income)
        exp[m] += Number(d.expense)
      }
    }
    const rows: DetailRow[] = []
    for (let i = 11; i >= 0; i--) {
      if (inc[i] > 0 || exp[i] > 0) rows.push({ label: `${i + 1}月`, income: inc[i], expense: exp[i] })
    }
    return rows
  }
  // 稀疏日，倒序（近的在前）
  return [...rep.days]
    .reverse()
    .map((d) => {
      const [, m, day] = d.date.split('-')
      return { label: `${m}/${day}`, income: Number(d.income), expense: Number(d.expense) }
    })
})
</script>

<template>
  <section class="reports">
    <!-- 顶栏 -->
    <header class="appbar">
      <button type="button" class="ab-btn" aria-label="返回" @click="goBack">←</button>
      <h1 class="ab-title">统计</h1>
      <span class="ab-btn" aria-hidden="true"></span>
    </header>

    <!-- 时间维度 -->
    <div class="seg">
      <button :class="{ on: period === 'week' }" @click="setPeriod('week')">周</button>
      <button :class="{ on: period === 'month' }" @click="setPeriod('month')">月</button>
      <button :class="{ on: period === 'year' }" @click="setPeriod('year')">年</button>
      <button :class="{ on: period === 'custom' }" @click="setPeriod('custom')">自定义</button>
    </div>

    <!-- 区间导航 / 自定义选择 -->
    <div v-if="showNav" class="range-nav">
      <button class="arw" type="button" aria-label="上一个" @click="prev">‹</button>
      <span class="r-label">{{ range.label }}</span>
      <button class="arw" type="button" aria-label="下一个" :disabled="atLatest" @click="next">›</button>
    </div>
    <div v-else class="custom-row">
      <input type="date" v-model="customFrom" :max="customTo" @change="applyCustom" />
      <span class="tilde">~</span>
      <input type="date" v-model="customTo" :min="customFrom" :max="TODAY_ISO" @change="applyCustom" />
    </div>

    <div v-if="loadError" class="banner banner-err" role="alert">
      <span>{{ loadError }}</span>
      <button class="link-btn" type="button" @click="load">重试</button>
    </div>

    <p v-if="loading && !loaded" class="text-muted loading">加载中…</p>

    <template v-if="loaded">
      <!-- KPI 绿卡 -->
      <div class="kpi">
        <div class="lead">{{ prevLabel === '上月' ? '本月支出' : '当期支出' }}</div>
        <div class="big num">¥{{ formatAmount(expense) }}</div>
        <div class="k-row">
          <div class="cell"><div class="k">收入</div><div class="v num">¥{{ formatAmount(income) }}</div></div>
          <div class="cell">
            <div class="k">结余</div>
            <div class="v num">{{ Number(balance) >= 0 ? '+' : '' }}¥{{ formatAmount(balance) }}</div>
          </div>
        </div>
        <div v-if="momPercent !== null" class="mom">
          较{{ prevLabel }}支出
          {{ momPercent <= 0 ? '↓' : '↑' }} {{ Math.abs(momPercent).toFixed(1) }}%
          （{{ prevLabel }} ¥{{ formatAmount(prevExpense) }}）
        </div>
      </div>

      <!-- 收支趋势 -->
      <div class="card">
        <div class="toolbar">
          <h2>收支趋势</h2>
          <div class="mini">
            <button :class="{ on: trendKind === 'expense' }" @click="trendKind = 'expense'">支出</button>
            <button :class="{ on: trendKind === 'income' }" @click="trendKind = 'income'">收入</button>
          </div>
        </div>
        <p v-if="!hasTrend" class="text-muted empty">当期暂无{{ trendKind === 'expense' ? '支出' : '收入' }}记录。</p>
        <template v-else>
          <div class="bars" role="img" :aria-label="`按${period === 'year' ? '月' : '日'}的${trendKind === 'expense' ? '支出' : '收入'}趋势`">
            <div
              v-for="(b, i) in bars"
              :key="i"
              class="bar"
              :class="{ zero: !b.active, inc: trendKind === 'income' }"
              :style="{ height: b.h + '%' }"
              :title="`${b.label} ${trendKind === 'expense' ? '支出' : '收入'} ¥${formatAmount(trendKind === 'expense' ? b.expense : b.income)}`"
            ></div>
          </div>
          <div class="xaxis">
            <span v-for="(t, i) in xTicks" :key="i">{{ t }}</span>
          </div>
        </template>
      </div>

      <!-- 分类统计 -->
      <div class="card">
        <div class="toolbar">
          <h2>分类统计</h2>
          <div class="mini">
            <button :class="{ on: kind === 'expense' }" @click="switchKind('expense')">支出</button>
            <button :class="{ on: kind === 'income' }" @click="switchKind('income')">收入</button>
          </div>
        </div>
        <p v-if="!hasCategory" class="text-muted empty">当期暂无{{ kind === 'expense' ? '支出' : '收入' }}记录。</p>
        <div v-else class="cat-wrap">
          <svg class="donut" viewBox="0 0 150 150" role="img" :aria-label="`分类占比，共 ¥${formatAmount(categoryTotal)}`">
            <g transform="rotate(-90 75 75)">
              <circle cx="75" cy="75" :r="DONUT_R" fill="none" stroke="#f1f5f9" stroke-width="20" />
              <circle
                v-for="s in slices"
                :key="s.categoryId"
                cx="75"
                cy="75"
                :r="DONUT_R"
                fill="none"
                :stroke="s.color"
                stroke-width="20"
                :stroke-dasharray="`${s.dash} ${DONUT_C - s.dash}`"
                :stroke-dashoffset="s.offset"
              >
                <title>{{ s.name }} {{ s.percentage.toFixed(1) }}%</title>
              </circle>
            </g>
            <text x="75" y="70" text-anchor="middle" class="d-lbl">{{ kind === 'expense' ? '总支出' : '总收入' }}</text>
            <text x="75" y="90" text-anchor="middle" class="d-val">¥{{ formatAmount(categoryTotal) }}</text>
          </svg>

          <ul class="rank">
            <li v-for="s in slices" :key="s.categoryId" class="ri">
              <span class="ic" :style="{ background: s.color + '22' }">{{ s.emoji }}</span>
              <div class="mid">
                <div class="nm">
                  <span class="name">{{ s.name }}</span>
                  <span class="amt num">¥{{ formatAmount(s.amount) }}</span>
                </div>
                <div class="sub">
                  <span class="cnt">{{ s.count }}笔</span>
                  <span class="barbg"><span class="barfill" :style="{ width: s.percentage + '%', background: s.color }"></span></span>
                  <span class="pct">{{ s.percentage.toFixed(1) }}%</span>
                </div>
              </div>
            </li>
          </ul>
        </div>
      </div>

      <!-- 收支明细 -->
      <div class="card detail">
        <h2>收支明细</h2>
        <table class="tbl">
          <thead>
            <tr><th>{{ period === 'year' ? '月份' : '日期' }}</th><th>收入</th><th>支出</th><th>结余</th></tr>
          </thead>
          <tbody>
            <tr class="sum">
              <td>合计</td>
              <td class="inc">{{ formatAmount(income) }}</td>
              <td class="exp">{{ formatAmount(expense) }}</td>
              <td>{{ Number(balance) >= 0 ? '+' : '' }}{{ formatAmount(balance) }}</td>
            </tr>
            <tr v-for="(row, i) in detailRows" :key="i">
              <td>{{ row.label }}</td>
              <td class="inc">{{ row.income > 0 ? formatAmount(row.income) : '0.00' }}</td>
              <td class="exp">{{ row.expense > 0 ? formatAmount(row.expense) : '0.00' }}</td>
              <td :class="{ negtxt: row.income - row.expense < 0 }">
                {{ row.income - row.expense >= 0 ? '+' : '-' }}{{ formatAmount(Math.abs(row.income - row.expense)) }}
              </td>
            </tr>
            <tr v-if="detailRows.length === 0">
              <td colspan="4" class="text-muted" style="text-align:center;padding:16px 0">当期暂无收支记录</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
  </section>
</template>

<style scoped>
.num {
  font-variant-numeric: tabular-nums;
}
.reports {
  padding-bottom: 40px;
}

/* 顶栏（破出容器内边距，全宽 sticky） */
.appbar {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 4px;
  margin: -16px calc(-1 * clamp(12px, 4vw, 32px)) 12px;
  padding: calc(6px + var(--safe-top)) 6px 6px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
}
.ab-btn {
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: none;
  font-size: 20px;
  color: var(--color-text);
  cursor: pointer;
}
.ab-title {
  flex: 1;
  margin: 0;
  text-align: center;
  font-size: 17px;
  font-weight: 700;
}
@media (min-width: 768px) {
  .appbar {
    margin-top: 0;
  }
}

/* 时间维度 tab */
.seg {
  display: flex;
  gap: 4px;
  margin-bottom: 10px;
}
.seg button {
  flex: 1;
  padding: 7px 0;
  border: none;
  background: none;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-muted);
  border-radius: 9px;
  cursor: pointer;
}
.seg button.on {
  background: #ecfdf3;
  color: var(--color-primary-dark);
}

.range-nav {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 18px;
  margin-bottom: 14px;
}
.r-label {
  min-width: 8em;
  text-align: center;
  font-size: 15px;
  font-weight: 700;
}
.arw {
  width: 30px;
  height: 30px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
  background: var(--color-surface);
  font-size: 15px;
  color: var(--color-text);
  cursor: pointer;
}
.arw:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.custom-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
}
.custom-row input {
  flex: 1;
  min-width: 0;
  min-height: 40px;
  padding: 6px 10px;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  font: inherit;
  background: var(--color-surface);
}
.tilde {
  color: var(--color-muted);
}
.loading {
  padding: 24px 0;
}

/* KPI 绿卡 */
.kpi {
  color: #fff;
  border-radius: 18px;
  padding: 16px 18px;
  margin-bottom: 14px;
  background: linear-gradient(150deg, #22c55e, #16a34a 55%, #0b6b34);
  box-shadow: 0 14px 30px rgba(22, 163, 74, 0.26);
}
.kpi .lead {
  font-size: 13px;
  opacity: 0.92;
}
.kpi .big {
  font-size: 32px;
  font-weight: 850;
  letter-spacing: -0.02em;
  margin-top: 2px;
  overflow-wrap: anywhere;
}
.kpi .k-row {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}
.kpi .cell {
  flex: 1;
  min-width: 0;
  background: rgba(255, 255, 255, 0.14);
  border-radius: 12px;
  padding: 9px 11px;
}
.kpi .k {
  font-size: 11px;
  opacity: 0.85;
}
.kpi .v {
  font-size: 15px;
  font-weight: 700;
  margin-top: 3px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.kpi .mom {
  display: inline-block;
  margin-top: 12px;
  font-size: 12px;
  background: rgba(255, 255, 255, 0.16);
  padding: 4px 10px;
  border-radius: 999px;
}

.card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 16px;
  padding: 16px;
  margin-bottom: 14px;
}
.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.card h2 {
  margin: 0;
  font-size: 15px;
}
.empty {
  text-align: center;
  padding: 18px 0;
}
.mini {
  display: inline-flex;
  background: var(--color-bg);
  border-radius: 8px;
  padding: 2px;
}
.mini button {
  border: none;
  background: none;
  font-size: 12px;
  font-weight: 600;
  color: var(--color-muted);
  padding: 4px 12px;
  border-radius: 6px;
  cursor: pointer;
}
.mini button.on {
  background: var(--color-surface);
  color: var(--color-primary-dark);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.08);
}

/* 柱状 */
.bars {
  display: flex;
  align-items: flex-end;
  gap: 2px;
  height: 130px;
  padding-top: 6px;
}
.bar {
  flex: 1 1 0;
  min-width: 0;
  min-height: 2px;
  border-radius: 3px 3px 0 0;
  background: linear-gradient(180deg, #34d399, #16a34a);
}
.bar.inc {
  background: linear-gradient(180deg, #38bdf8, #0284c7);
}
.bar.zero {
  background: #eef2f6;
}
.xaxis {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: var(--color-muted);
  margin-top: 6px;
}

/* 分类：环形 + 排行 */
.cat-wrap {
  display: flex;
  flex-direction: column;
  gap: 16px;
  align-items: center;
}
.donut {
  width: min(180px, 60vw);
  height: auto;
  flex: 0 0 auto;
}
.d-lbl {
  font-size: 11px;
  fill: var(--color-muted);
}
.d-val {
  font-size: 14px;
  font-weight: 800;
  fill: var(--color-text);
}
.rank {
  list-style: none;
  margin: 0;
  padding: 0;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.ri {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ri .ic {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 16px;
  flex: 0 0 auto;
}
.ri .mid {
  flex: 1;
  min-width: 0;
}
.ri .nm {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
}
.ri .name {
  overflow-wrap: anywhere;
}
.ri .amt {
  font-weight: 800;
  white-space: nowrap;
}
.ri .sub {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 5px;
}
.ri .cnt {
  font-size: 11px;
  color: var(--color-muted);
  white-space: nowrap;
}
.ri .barbg {
  flex: 1;
  height: 6px;
  border-radius: 999px;
  background: var(--color-bg);
  overflow: hidden;
}
.ri .barfill {
  display: block;
  height: 100%;
  border-radius: 999px;
}
.ri .pct {
  font-size: 11px;
  color: var(--color-muted);
  white-space: nowrap;
}
@media (min-width: 640px) {
  .cat-wrap {
    flex-direction: row;
    align-items: center;
  }
  .rank {
    flex: 1;
  }
}

/* 明细表 */
.detail h2 {
  margin-bottom: 10px;
}
.tbl {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
}
.tbl th {
  text-align: right;
  color: var(--color-muted);
  font-weight: 600;
  padding: 8px 4px;
  border-bottom: 1px solid var(--color-border);
  font-size: 12px;
}
.tbl th:first-child {
  text-align: left;
}
.tbl td {
  text-align: right;
  padding: 10px 4px;
  border-bottom: 1px solid var(--color-border);
}
.tbl td:first-child {
  text-align: left;
  color: var(--color-muted);
}
.tbl .inc {
  color: var(--color-primary);
}
.tbl .exp {
  color: var(--color-danger);
}
.tbl .negtxt {
  color: var(--color-danger);
}
.tbl tr.sum td {
  font-weight: 800;
  border-bottom: 2px solid var(--color-border);
}

.banner {
  margin: 0 0 14px;
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
