<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { listAccounts } from '../../api/account'
import { listCategories, buildCategoryLabelMap } from '../../api/category'
import { listTransactionsByMonth, deleteTransaction, searchTransactions, batchDeleteTransactions } from '../../api/transaction'
import { listMembers } from '../../api/ledger'
import { listProjects } from '../../api/project'
import { listMerchants } from '../../api/merchant'
import { listTags } from '../../api/tag'
import { trendReport, shiftMonth } from '../../api/report'
import {
  listAllAccounts,
  listAllCategories,
  listAllTransactionsByMonth
} from '../../api/aggregate'
import { useLedgerStore } from '../../stores/ledger'
import {
  formatAmount,
  categoryEmoji,
  dayKeyOf,
  dayLabel,
  timeLabelOf,
  currentMonth
} from '../../utils/format'

const ledgerStore = useLedgerStore()

// ---------- 视图/周期 ----------
const viewMode = ref('month') // 'month' | 'year'
const month = ref(currentMonth()) // YYYY-MM
const year = computed(() => Number(month.value.slice(0, 4)))
const monthNum = computed(() => Number(month.value.slice(5, 7)))
const isAll = computed(() => ledgerStore.isAll)
const isCollab = computed(() => !isAll.value && ledgerStore.current?.type === 'COLLABORATIVE')

// ---------- 数据 ----------
const transactions = ref([])
const accountMap = ref({})
const categoryMap = ref({})
const memberMap = ref({})
const tagNameById = ref({})
const loading = ref(false)

// 年视图数据（复用趋势接口：每月收入/支出）
const yearMonths = ref([])

// 筛选选项
const projects = ref([])
const merchants = ref([])
const tags = ref([])
const catOptions = ref([]) // [{id,name,kind}]
const acctOptions = ref([]) // [{id,name}]

// ---------- 多维筛选（可组合，客户端过滤当月）----------
const fTypes = ref(new Set())
const fCats = ref(new Set())
const fAccts = ref(new Set())
const fProjects = ref(new Set())
const fTags = ref(new Set())
const fRecorders = ref(new Set())
const filterPanel = ref(false)
const activeFilterCount = computed(() =>
  fTypes.value.size + fCats.value.size + fAccts.value.size +
  fProjects.value.size + fTags.value.size + fRecorders.value.size
)
function toggleSet(setRef, val) {
  const s = new Set(setRef.value)
  if (s.has(val)) s.delete(val)
  else s.add(val)
  setRef.value = s
}
function toggleType(t) { toggleSet(fTypes, t) }
function toggleCat(id) { toggleSet(fCats, id) }
function toggleAcct(id) { toggleSet(fAccts, id) }
function toggleProject(id) { toggleSet(fProjects, id) }
function toggleTag(id) { toggleSet(fTags, id) }
function toggleRecorder(id) { toggleSet(fRecorders, id) }
function resetFilter() {
  fTypes.value = new Set(); fCats.value = new Set(); fAccts.value = new Set()
  fProjects.value = new Set(); fTags.value = new Set(); fRecorders.value = new Set()
}

const visibleTx = computed(() => {
  if (!activeFilterCount.value) return transactions.value
  return transactions.value.filter((t) => {
    if (fTypes.value.size && !fTypes.value.has(t.type)) return false
    if (fCats.value.size && !fCats.value.has(t.categoryId)) return false
    if (fAccts.value.size) {
      const hit = fAccts.value.has(t.accountId) ||
        fAccts.value.has(t.sourceAccountId) || fAccts.value.has(t.destinationAccountId)
      if (!hit) return false
    }
    if (fProjects.value.size && !fProjects.value.has(t.projectId)) return false
    if (fTags.value.size) {
      if (!Array.isArray(t.tagIds) || !t.tagIds.some((id) => fTags.value.has(id))) return false
    }
    if (fRecorders.value.size && !fRecorders.value.has(t.createdBy)) return false
    return true
  })
})

// ---------- KPI ----------
const kpi = computed(() => {
  let income = 0, expense = 0
  for (const t of visibleTx.value) {
    if (t.type === 'income') income += Number(t.amount)
    else if (t.type === 'expense') expense += Number(t.amount)
  }
  return { income, expense, balance: income - expense }
})

// ---------- 分组 ----------
const groupBy = ref('day') // 'day' | 'category' | 'type'
const PALETTE = ['#e5793a', '#8b78e0', '#2eb8a6', '#3aa0d0', '#e0609a', '#5b8def', '#f0a13b', '#3ba55d']
function catColor(name) {
  let h = 0
  const s = String(name || '')
  for (let i = 0; i < s.length; i++) h = (h + s.charCodeAt(i)) >>> 0
  return PALETTE[h % PALETTE.length]
}

const dayGroups = computed(() => {
  const groups = []
  let cur = null
  for (const t of visibleTx.value) {
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

const catGroups = computed(() => {
  const map = new Map()
  let totalAbs = 0
  for (const t of visibleTx.value) {
    if (t.type === 'transfer' || t.categoryId == null) continue
    const amt = Number(t.amount)
    totalAbs += amt
    const key = t.categoryId
    const g = map.get(key) || { id: key, name: categoryMap.value[key] || '未分类', type: t.type, sum: 0, count: 0 }
    g.sum += amt
    g.count += 1
    map.set(key, g)
  }
  const list = [...map.values()].map((g) => ({
    ...g,
    pct: totalAbs > 0 ? Math.round((g.sum / totalAbs) * 100) : 0,
    color: catColor(g.name)
  }))
  list.sort((a, b) => b.sum - a.sum)
  return list
})

const typeGroups = computed(() => {
  const defs = [
    { type: 'expense', label: '支出' },
    { type: 'income', label: '收入' },
    { type: 'transfer', label: '转账' }
  ]
  return defs.map((d) => {
    const items = visibleTx.value.filter((t) => t.type === d.type)
    const sum = items.reduce((s, t) => s + Number(t.amount), 0)
    return { ...d, items, sum, count: items.length }
  }).filter((g) => g.items.length)
})

// ---------- 加载 ----------
async function load() {
  if (viewMode.value === 'year') return loadYear()
  loading.value = true
  try {
    const all = isAll.value
    const [accs, cats, txs] = await Promise.all([
      all ? listAllAccounts() : listAccounts(),
      all ? listAllCategories() : listCategories(),
      all ? listAllTransactionsByMonth(month.value) : listTransactionsByMonth(month.value)
    ])
    accountMap.value = Object.fromEntries(accs.map((a) => [a.id, a.name]))
    acctOptions.value = accs.map((a) => ({ id: a.id, name: a.name }))
    categoryMap.value = buildCategoryLabelMap(cats)
    catOptions.value = [...(cats.expense || []), ...(cats.income || [])].map((c) => ({
      id: c.id, name: c.name, kind: (cats.expense || []).includes(c) ? 'expense' : 'income'
    }))
    transactions.value = txs

    if (isCollab.value) {
      try {
        const ms = await listMembers(ledgerStore.currentLedgerId)
        memberMap.value = Object.fromEntries(ms.map((m) => [m.userId, m.displayName || '用户' + m.userId]))
      } catch (e) { memberMap.value = {} }
    } else {
      memberMap.value = {}
    }

    if (!all) {
      try {
        const [ps, mrs, ts] = await Promise.all([listProjects(), listMerchants(), listTags()])
        projects.value = ps; merchants.value = mrs; tags.value = ts
        tagNameById.value = Object.fromEntries(ts.map((t) => [t.id, t.name]))
      } catch (e) { /* ignore */ }
    } else {
      projects.value = []; merchants.value = []; tags.value = []; tagNameById.value = {}
    }
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

async function loadYear() {
  loading.value = true
  try {
    const tr = await trendReport(`${year.value}-01`, `${year.value}-12`)
    yearMonths.value = (tr.months || []).map((m) => ({
      month: m.month,
      mm: Number(m.month.slice(5, 7)),
      income: Number(m.income),
      expense: Number(m.expense),
      balance: Number(m.income) - Number(m.expense)
    }))
  } catch (e) {
    yearMonths.value = []
  } finally {
    loading.value = false
  }
}
const yearTotals = computed(() => {
  let income = 0, expense = 0
  for (const m of yearMonths.value) { income += m.income; expense += m.expense }
  return { income, expense, balance: income - expense }
})

onShow(() => {
  uni.hideTabBar({ animation: false, fail() {} })
  load()
})

// ---------- 月份/年份切换 ----------
function prevPeriod() {
  if (viewMode.value === 'year') month.value = `${year.value - 1}-${month.value.slice(5)}`
  else month.value = shiftMonth(month.value, -1)
  load()
}
function nextPeriod() {
  if (viewMode.value === 'year') month.value = `${year.value + 1}-${month.value.slice(5)}`
  else month.value = shiftMonth(month.value, 1)
  load()
}
function setViewMode(m) {
  if (viewMode.value === m) return
  viewMode.value = m
  load()
}

// 月份选择器
const pickerOpen = ref(false)
const pickerYear = ref(year.value)
const monthsWithData = ref(new Set())
async function openPicker() {
  pickerYear.value = year.value
  pickerOpen.value = true
  loadPickerDots()
}
async function loadPickerDots() {
  if (isAll.value) { monthsWithData.value = new Set(); return }
  try {
    const tr = await trendReport(`${pickerYear.value}-01`, `${pickerYear.value}-12`)
    const s = new Set()
    for (const m of tr.months || []) {
      if (Number(m.income) > 0 || Number(m.expense) > 0) s.add(Number(m.month.slice(5, 7)))
    }
    monthsWithData.value = s
  } catch (e) { monthsWithData.value = new Set() }
}
function pickerShiftYear(d) { pickerYear.value += d; loadPickerDots() }
function pickMonth(m) {
  month.value = `${pickerYear.value}-${String(m).padStart(2, '0')}`
  pickerOpen.value = false
  load()
}
function openMonthEntry() {
  if (viewMode.value === 'year') { pickerYear.value = year.value; return }
  openPicker()
}

// ---------- 搜索 ----------
const searchMode = ref(false)
const query = ref('')
const searchResults = ref([])
const searched = ref(false)
function openSearch() { searchMode.value = true; query.value = ''; searchResults.value = []; searched.value = false }
function closeSearch() { searchMode.value = false }
async function doSearch() {
  const q = query.value.trim()
  if (!q) { searchResults.value = []; searched.value = false; return }
  try {
    searchResults.value = await searchTransactions(q)
    searched.value = true
  } catch (e) {
    uni.showToast({ title: e.message || '搜索失败', icon: 'none' })
  }
}
const searchGroups = computed(() => {
  const groups = []
  let cur = null
  for (const t of searchResults.value) {
    const day = dayKeyOf(t.occurredAt)
    if (!cur || cur.day !== day) { cur = { day, label: dayLabel(day), items: [] }; groups.push(cur) }
    cur.items.push(t)
  }
  return groups
})
const searchTotal = computed(() => {
  let e = 0
  for (const t of searchResults.value) if (t.type === 'expense') e += Number(t.amount)
  return e
})

// ---------- 日历视图 ----------
const calendarOpen = ref(false)
const selectedDay = ref('')
const WEEK = ['日', '一', '二', '三', '四', '五', '六']
const dailyMap = computed(() => {
  const m = {}
  for (const t of transactions.value) {
    const d = dayKeyOf(t.occurredAt)
    if (!m[d]) m[d] = { income: 0, expense: 0 }
    if (t.type === 'income') m[d].income += Number(t.amount)
    else if (t.type === 'expense') m[d].expense += Number(t.amount)
  }
  return m
})
const calendarCells = computed(() => {
  const y = year.value, mo = monthNum.value
  const first = new Date(y, mo - 1, 1).getDay()
  const days = new Date(y, mo, 0).getDate()
  const cells = []
  for (let i = 0; i < first; i++) cells.push(null)
  for (let d = 1; d <= days; d++) {
    const key = `${y}-${String(mo).padStart(2, '0')}-${String(d).padStart(2, '0')}`
    const info = dailyMap.value[key]
    cells.push({ d, key, expense: info ? info.expense : 0, income: info ? info.income : 0 })
  }
  return cells
})
const selectedDayTx = computed(() =>
  selectedDay.value ? transactions.value.filter((t) => dayKeyOf(t.occurredAt) === selectedDay.value) : []
)
function openCalendar() { calendarOpen.value = true; selectedDay.value = '' }
function pickDay(c) { if (!c) return; selectedDay.value = selectedDay.value === c.key ? '' : c.key }

// ---------- 行渲染 ----------
function titleOf(t) {
  if (t.type === 'transfer') {
    return `${accountMap.value[t.sourceAccountId] || '?'} → ${accountMap.value[t.destinationAccountId] || '?'}`
  }
  return categoryMap.value[t.categoryId] || (t.type === 'income' ? '收入' : '支出')
}
function subtitleOf(t) {
  const parts = []
  if (t.type !== 'transfer') parts.push(accountMap.value[t.accountId] || '')
  const tm = timeLabelOf(t.occurredAt)
  if (tm) parts.push(tm)
  if (t.note) parts.push(t.note)
  if (isCollab.value && t.createdBy != null && memberMap.value[t.createdBy]) parts.push(`👤${memberMap.value[t.createdBy]}`)
  return parts.filter(Boolean).join(' · ')
}
function iconOf(t) {
  if (t.type === 'transfer') return '🔁'
  return categoryEmoji(categoryMap.value[t.categoryId], t.type)
}
function iconColor(t) {
  if (t.type === 'transfer') return '#8a94a6'
  return catColor(categoryMap.value[t.categoryId] || '')
}
function tagNamesOf(t) {
  if (!Array.isArray(t.tagIds) || !t.tagIds.length) return []
  return t.tagIds.map((id) => tagNameById.value[id]).filter(Boolean)
}
function signedAmount(t) {
  if (t.type === 'expense') return `-${formatAmount(t.amount)}`
  if (t.type === 'income') return `+${formatAmount(t.amount)}`
  return formatAmount(t.amount)
}
function typeLabel(t) { return t === 'income' ? '收入' : t === 'transfer' ? '转账' : '支出' }

function goEdit(t) {
  const suffix = t.ledgerId ? `&ledgerId=${t.ledgerId}` : ''
  uni.navigateTo({ url: `/pages/record/record?id=${t.id}${suffix}` })
}
function confirmDelete(t) {
  uni.showModal({
    title: '删除记录',
    content: '删除后会同步回滚账户余额，确定删除？',
    success: async (r) => {
      if (!r.confirm) return
      try { await deleteTransaction(t.id, t.ledgerId); await load() }
      catch (e) { uni.showToast({ title: e.message || '删除失败', icon: 'none' }) }
    }
  })
}
function jumpToMonth(m) {
  month.value = m.month
  viewMode.value = 'month'
  load()
}

// ---------- 批量操作 ----------
const selectMode = ref(false)
const selectedIds = ref(new Set())
function enterSelect(id) {
  selectMode.value = true
  selectedIds.value = id != null ? new Set([id]) : new Set()
}
function exitSelect() { selectMode.value = false; selectedIds.value = new Set() }
function toggleSelect(id) {
  const s = new Set(selectedIds.value)
  s.has(id) ? s.delete(id) : s.add(id)
  selectedIds.value = s
}
function txTap(t) { if (selectMode.value) toggleSelect(t.id); else goEdit(t) }
function selectAll() { selectedIds.value = new Set(visibleTx.value.map((t) => t.id)) }
async function batchDelete() {
  const ids = [...selectedIds.value]
  if (!ids.length) return
  uni.showModal({
    title: '移入回收站',
    content: `将 ${ids.length} 笔移入回收站，可在回收站恢复。`,
    success: async (r) => {
      if (!r.confirm) return
      try {
        await batchDeleteTransactions(ids)
        uni.showToast({ title: '已移入回收站', icon: 'success' })
        exitSelect()
        await load()
      } catch (e) {
        uni.showToast({ title: e.message || '操作失败', icon: 'none' })
      }
    }
  })
}
</script>

<template>
  <view class="page">
    <!-- 顶部栏 -->
    <view class="topbar">
      <view class="tb-row1">
        <text class="ledger">{{ ledgerStore.currentName }}</text>
        <view v-if="!isAll" class="seg">
          <text :class="{ on: viewMode === 'month' }" @click="setViewMode('month')">月账单</text>
          <text :class="{ on: viewMode === 'year' }" @click="setViewMode('year')">年账单</text>
        </view>
        <view class="tb-icons">
          <text v-if="!isAll && viewMode === 'month'" @click="openCalendar">📅</text>
          <text v-if="!isAll" @click="openSearch">🔍</text>
        </view>
      </view>
      <view class="monthnav">
        <text class="arw" @click="prevPeriod">‹</text>
        <text class="m" @click="openMonthEntry">
          {{ viewMode === 'year' ? year + ' 年' : year + '年' + monthNum + '月' }}
          <text v-if="viewMode === 'month'" class="caret">▾</text>
        </text>
        <text class="arw" @click="nextPeriod">›</text>
      </view>
      <view class="kpis">
        <template v-if="viewMode === 'month'">
          <view class="kpi"><text class="k">收入</text><text class="v inc">{{ formatAmount(kpi.income) }}</text></view>
          <view class="kpi"><text class="k">支出</text><text class="v exp">{{ formatAmount(kpi.expense) }}</text></view>
          <view class="kpi"><text class="k">结余</text><text class="v">{{ formatAmount(kpi.balance) }}</text></view>
        </template>
        <template v-else>
          <view class="kpi"><text class="k">年收入</text><text class="v inc">{{ formatAmount(yearTotals.income) }}</text></view>
          <view class="kpi"><text class="k">年支出</text><text class="v exp">{{ formatAmount(yearTotals.expense) }}</text></view>
          <view class="kpi"><text class="k">年结余</text><text class="v">{{ formatAmount(yearTotals.balance) }}</text></view>
        </template>
      </view>
    </view>

    <!-- ============ 年视图：按月汇总表 ============ -->
    <template v-if="viewMode === 'year'">
      <view class="thead">
        <text style="flex:0 0 80rpx">月份</text>
        <text style="flex:1;text-align:right">收入</text>
        <text style="flex:1;text-align:right">支出</text>
        <text style="flex:1;text-align:right">结余</text>
        <text style="flex:0 0 28rpx"></text>
      </view>
      <view class="card yeartable">
        <view v-for="m in yearMonths" :key="m.month" class="mrow" @click="jumpToMonth(m)">
          <text class="mm">{{ m.mm }}月</text>
          <text class="mi">{{ formatAmount(m.income) }}</text>
          <text class="me">{{ formatAmount(m.expense) }}</text>
          <text class="mb">{{ formatAmount(m.balance) }}</text>
          <text class="mc">›</text>
        </view>
      </view>
    </template>

    <!-- ============ 月视图 ============ -->
    <template v-else>
      <!-- 多选工具条 -->
      <view v-if="selectMode" class="selbar">
        <text class="selcancel" @click="exitSelect">取消</text>
        <text class="seln">已选 {{ selectedIds.size }}</text>
        <text class="selall" @click="selectAll">全选</text>
      </view>
      <!-- 筛选栏 -->
      <view v-else class="filterbar">
        <view v-if="activeFilterCount" class="active-filter" @click="filterPanel = true">
          <text>已筛选 {{ activeFilterCount }} 项</text>
          <text class="af-x" @click.stop="resetFilter">✕</text>
        </view>
        <view class="filter-btn" @click="filterPanel = true">🔎 筛选</view>
        <view v-if="groupBy !== 'category' && visibleTx.length" class="filter-btn" @click="enterSelect()">☑ 多选</view>
        <view class="grp-seg">
          <text :class="{ on: groupBy === 'day' }" @click="groupBy = 'day'">按天</text>
          <text :class="{ on: groupBy === 'category' }" @click="groupBy = 'category'">按分类</text>
          <text :class="{ on: groupBy === 'type' }" @click="groupBy = 'type'">按收支</text>
        </view>
      </view>

      <view v-if="!visibleTx.length && !loading" class="empty">
        <text class="big">🧾</text>
        <text>{{ activeFilterCount ? '该筛选下本月无记录' : '这个月还没有记录' }}</text>
      </view>

      <!-- 按天 -->
      <template v-if="groupBy === 'day'">
        <view v-for="g in dayGroups" :key="g.day" class="daygrp">
          <view class="dayhead">
            <text class="dt">{{ g.label }}</text>
            <text class="sum"><text class="i">收 {{ formatAmount(g.income) }}</text><text class="e">支 {{ formatAmount(g.expense) }}</text></text>
          </view>
          <view class="card">
            <view v-for="t in g.items" :key="t.id" class="tx" @click="txTap(t)" @longpress="enterSelect(t.id)">
              <text v-if="selectMode" class="chk" :class="{ on: selectedIds.has(t.id) }">{{ selectedIds.has(t.id) ? '✓' : '' }}</text>
              <text class="tico" :style="{ background: iconColor(t) }">{{ iconOf(t) }}</text>
              <view class="tinfo">
                <text class="tname">{{ titleOf(t) }}</text>
                <text class="tsub">{{ subtitleOf(t) }}</text>
                <view v-if="tagNamesOf(t).length" class="tags">
                  <text v-for="(tn, i) in tagNamesOf(t)" :key="i" class="tag">{{ tn }}</text>
                </view>
              </view>
              <text class="tamt" :class="t.type">{{ signedAmount(t) }}</text>
            </view>
          </view>
        </view>
      </template>

      <!-- 按分类 -->
      <template v-else-if="groupBy === 'category'">
        <view class="daygrp">
          <view class="card">
            <view v-for="g in catGroups" :key="g.id" class="tx">
              <text class="tico" :style="{ background: g.color }">{{ categoryEmoji(g.name, g.type) }}</text>
              <view class="tinfo">
                <text class="tname">{{ g.name }} · {{ g.count }} 笔</text>
                <view class="pctbar"><view class="pctfill" :style="{ width: g.pct + '%', background: g.color }"></view></view>
              </view>
              <view class="tright">
                <text class="tamt" :class="g.type">{{ signedAmount({ type: g.type, amount: g.sum }) }}</text>
                <text class="tsub">{{ g.pct }}%</text>
              </view>
            </view>
          </view>
        </view>
      </template>

      <!-- 按收支 -->
      <template v-else>
        <view v-for="g in typeGroups" :key="g.type" class="daygrp">
          <view class="dayhead">
            <text class="dt">{{ g.label }} · {{ g.count }} 笔</text>
            <text class="sum"><text :class="g.type === 'income' ? 'i' : 'e'">合计 {{ formatAmount(g.sum) }}</text></text>
          </view>
          <view class="card">
            <view v-for="t in g.items" :key="t.id" class="tx" @click="txTap(t)" @longpress="enterSelect(t.id)">
              <text v-if="selectMode" class="chk" :class="{ on: selectedIds.has(t.id) }">{{ selectedIds.has(t.id) ? '✓' : '' }}</text>
              <text class="tico" :style="{ background: iconColor(t) }">{{ iconOf(t) }}</text>
              <view class="tinfo">
                <text class="tname">{{ titleOf(t) }}</text>
                <text class="tsub">{{ subtitleOf(t) }}</text>
              </view>
              <text class="tamt" :class="t.type">{{ signedAmount(t) }}</text>
            </view>
          </view>
        </view>
      </template>

      <text v-if="visibleTx.length && !selectMode" class="hint">点击编辑 · 长按多选</text>

      <!-- 批量操作底栏 -->
      <view v-if="selectMode" class="batchbar">
        <text class="bcount">已选 {{ selectedIds.size }} 笔</text>
        <text class="bdel" :class="{ disabled: !selectedIds.size }" @click="batchDelete">移入回收站</text>
      </view>
    </template>

    <view style="height:180rpx;"></view>
    <TabBar v-if="!selectMode" active="records" />

    <!-- ============ 月份选择器 ============ -->
    <view v-if="pickerOpen" class="mask" @click="pickerOpen = false">
      <view class="sheet" @click.stop>
        <view class="pick-year">
          <text class="arw" @click="pickerShiftYear(-1)">‹</text>
          <text class="py">{{ pickerYear }} 年</text>
          <text class="arw" @click="pickerShiftYear(1)">›</text>
        </view>
        <view class="mgrid">
          <view
            v-for="m in 12"
            :key="m"
            class="mcell"
            :class="{ sel: m === monthNum && pickerYear === year, has: monthsWithData.has(m) }"
            @click="pickMonth(m)"
          >{{ m }}月</view>
        </view>
      </view>
    </view>

    <!-- ============ 多维筛选面板 ============ -->
    <view v-if="filterPanel" class="mask" @click="filterPanel = false">
      <view class="sheet fpanel" @click.stop>
        <text class="sheet-title">筛选</text>
        <scroll-view scroll-y class="fscroll">
          <view class="fgroup"><text class="flabel">收支类型</text>
            <view class="pills">
              <text class="pill" :class="{ on: !fTypes.size }" @click="fTypes = new Set()">全部</text>
              <text class="pill" :class="{ on: fTypes.has('expense') }" @click="toggleType('expense')">支出</text>
              <text class="pill" :class="{ on: fTypes.has('income') }" @click="toggleType('income')">收入</text>
              <text class="pill" :class="{ on: fTypes.has('transfer') }" @click="toggleType('transfer')">转账</text>
            </view>
          </view>
          <view class="fgroup" v-if="catOptions.length"><text class="flabel">分类</text>
            <view class="pills">
              <text v-for="c in catOptions" :key="c.id" class="pill" :class="{ on: fCats.has(c.id) }" @click="toggleCat(c.id)">{{ c.name }}</text>
            </view>
          </view>
          <view class="fgroup" v-if="acctOptions.length"><text class="flabel">账户</text>
            <view class="pills">
              <text v-for="a in acctOptions" :key="a.id" class="pill" :class="{ on: fAccts.has(a.id) }" @click="toggleAcct(a.id)">{{ a.name }}</text>
            </view>
          </view>
          <view class="fgroup" v-if="projects.length"><text class="flabel">项目</text>
            <view class="pills">
              <text v-for="p in projects" :key="p.id" class="pill" :class="{ on: fProjects.has(p.id) }" @click="toggleProject(p.id)">{{ p.name }}</text>
            </view>
          </view>
          <view class="fgroup" v-if="tags.length"><text class="flabel">标签</text>
            <view class="pills">
              <text v-for="t in tags" :key="t.id" class="pill" :class="{ on: fTags.has(t.id) }" @click="toggleTag(t.id)">{{ t.name }}</text>
            </view>
          </view>
          <view class="fgroup" v-if="isCollab"><text class="flabel">记账人</text>
            <view class="pills">
              <text v-for="(name, uid) in memberMap" :key="uid" class="pill" :class="{ on: fRecorders.has(Number(uid)) }" @click="toggleRecorder(Number(uid))">{{ name }}</text>
            </view>
          </view>
        </scroll-view>
        <view class="factions">
          <text class="freset" @click="resetFilter">重置</text>
          <text class="fdone" @click="filterPanel = false">查看 {{ visibleTx.length }} 条结果</text>
        </view>
      </view>
    </view>

    <!-- ============ 日历视图 ============ -->
    <view v-if="calendarOpen" class="searchlayer">
      <view class="sbar">
        <text class="sback" @click="calendarOpen = false">‹</text>
        <view class="cal-nav">
          <text class="arw" @click="prevPeriod">‹</text>
          <text class="cal-title">{{ year }}年{{ monthNum }}月</text>
          <text class="arw" @click="nextPeriod">›</text>
        </view>
        <text style="width:44rpx"></text>
      </view>
      <scroll-view scroll-y class="sresults">
        <view class="calwrap">
          <view class="calweek"><text v-for="w in WEEK" :key="w">{{ w }}</text></view>
          <view class="calgrid">
            <view
              v-for="(c, i) in calendarCells"
              :key="i"
              class="calcell"
              :class="{ blank: !c, sel: c && selectedDay === c.key }"
              @click="pickDay(c)"
            >
              <template v-if="c">
                <text class="cd">{{ c.d }}</text>
                <text v-if="c.expense" class="ce">-{{ formatAmount(c.expense) }}</text>
                <text v-if="c.income" class="ci">+{{ formatAmount(c.income) }}</text>
              </template>
            </view>
          </view>
        </view>
        <view v-if="selectedDay" class="daygrp">
          <view class="dayhead"><text class="dt">{{ selectedDay.slice(5) }}</text></view>
          <view v-if="!selectedDayTx.length" class="empty" style="padding:60rpx 0"><text>这天没有记录</text></view>
          <view v-else class="card">
            <view v-for="t in selectedDayTx" :key="t.id" class="tx" @click="goEdit(t)" @longpress="confirmDelete(t)">
              <text class="tico" :style="{ background: iconColor(t) }">{{ iconOf(t) }}</text>
              <view class="tinfo">
                <text class="tname">{{ titleOf(t) }}</text>
                <text class="tsub">{{ subtitleOf(t) }}</text>
              </view>
              <text class="tamt" :class="t.type">{{ signedAmount(t) }}</text>
            </view>
          </view>
        </view>
        <view v-else class="cal-hint">点某一天查看当日明细</view>
      </scroll-view>
    </view>

    <!-- ============ 搜索 ============ -->
    <view v-if="searchMode" class="searchlayer">
      <view class="sbar">
        <text class="sback" @click="closeSearch">‹</text>
        <input class="sinput" v-model="query" placeholder="搜备注 / 分类 / 商家 / 金额" confirm-type="search" focus @confirm="doSearch" />
        <text class="sgo" @click="doSearch">搜索</text>
      </view>
      <scroll-view scroll-y class="sresults">
        <view v-if="searched && !searchResults.length" class="empty"><text class="big">🔍</text><text>没有匹配的记录</text></view>
        <view v-if="searchResults.length" class="scount">共 {{ searchResults.length }} 笔 · 支出合计 <text class="exp">¥{{ formatAmount(searchTotal) }}</text></view>
        <view v-for="g in searchGroups" :key="g.day" class="daygrp">
          <view class="dayhead"><text class="dt">{{ g.label }}</text></view>
          <view class="card">
            <view v-for="t in g.items" :key="t.id" class="tx" @click="goEdit(t)">
              <text class="tico" :style="{ background: iconColor(t) }">{{ iconOf(t) }}</text>
              <view class="tinfo">
                <text class="tname">{{ titleOf(t) }}</text>
                <text class="tsub">{{ subtitleOf(t) }}</text>
              </view>
              <text class="tamt" :class="t.type">{{ signedAmount(t) }}</text>
            </view>
          </view>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; background: #eef0f2; padding-bottom: 40rpx; }

/* 顶部栏 */
.topbar { background: #fff; padding: 12rpx 24rpx 20rpx; }
.tb-row1 { display: flex; align-items: center; justify-content: space-between; height: 64rpx; }
.ledger { font-size: 32rpx; font-weight: 800; color: #16181c; }
.seg { display: inline-flex; background: #eef0f2; border-radius: 12rpx; padding: 4rpx; }
.seg text { padding: 8rpx 22rpx; font-size: 24rpx; font-weight: 700; color: #5b6470; border-radius: 10rpx; }
.seg text.on { background: #fff; color: #16181c; box-shadow: 0 2rpx 6rpx rgba(0,0,0,0.08); }
.tb-icons { display: flex; gap: 24rpx; font-size: 34rpx; min-width: 40rpx; justify-content: flex-end; }
.monthnav { display: flex; align-items: center; justify-content: center; gap: 40rpx; margin-top: 8rpx; }
.monthnav .arw { color: #9aa2ad; font-size: 40rpx; padding: 4rpx 16rpx; }
.monthnav .m { font-size: 32rpx; font-weight: 800; }
.monthnav .caret { font-size: 22rpx; color: #9aa2ad; }
.kpis { display: flex; margin-top: 18rpx; background: #f6f7f9; border-radius: 16rpx; padding: 20rpx 0; }
.kpi { flex: 1; text-align: center; }
.kpi + .kpi { border-left: 1rpx solid #e7eaed; }
.kpi .k { font-size: 22rpx; color: #9aa2ad; }
.kpi .v { display: block; font-size: 34rpx; font-weight: 800; margin-top: 4rpx; }
.kpi .v.exp { color: #f0553d; }
.kpi .v.inc { color: #12a150; }

/* 年视图表 */
.thead { display: flex; padding: 24rpx 32rpx 8rpx; font-size: 22rpx; color: #9aa2ad; }
.yeartable { margin: 0 24rpx; }
.mrow { display: flex; align-items: center; padding: 26rpx 20rpx; border-top: 1rpx solid #f1f3f5; font-size: 26rpx; }
.yeartable .mrow:first-child { border-top: none; }
.mrow .mm { flex: 0 0 80rpx; font-weight: 800; color: #16181c; }
.mrow .mi { flex: 1; text-align: right; color: #12a150; }
.mrow .me { flex: 1; text-align: right; color: #f0553d; }
.mrow .mb { flex: 1; text-align: right; font-weight: 700; color: #16181c; }
.mrow .mc { flex: 0 0 28rpx; text-align: right; color: #c0c4cc; }

/* 筛选栏 */
.filterbar { display: flex; align-items: center; gap: 14rpx; padding: 18rpx 24rpx 6rpx; }
.active-filter { display: flex; align-items: center; gap: 10rpx; background: #e6f6ec; color: #0e8a44; border-radius: 999rpx; padding: 10rpx 20rpx; font-size: 24rpx; font-weight: 700; }
.af-x { color: #0e8a44; }
.filter-btn { background: #fff; border-radius: 999rpx; padding: 10rpx 24rpx; font-size: 24rpx; color: #5b6470; box-shadow: 0 2rpx 8rpx rgba(20,24,28,0.05); white-space: nowrap; }
.grp-seg { margin-left: auto; display: inline-flex; background: #fff; border-radius: 10rpx; padding: 4rpx; box-shadow: 0 2rpx 8rpx rgba(20,24,28,0.05); }
.grp-seg text { padding: 8rpx 16rpx; font-size: 22rpx; font-weight: 700; color: #5b6470; border-radius: 8rpx; }
.grp-seg text.on { background: #12a150; color: #fff; }

/* 多选工具条 */
.selbar { display: flex; align-items: center; justify-content: space-between; padding: 18rpx 32rpx 6rpx; }
.selcancel { font-size: 28rpx; color: #5b6470; }
.seln { font-size: 28rpx; font-weight: 800; color: #16181c; }
.selall { font-size: 28rpx; color: #0e8a44; font-weight: 700; }
.chk { width: 40rpx; height: 40rpx; border-radius: 50%; border: 2rpx solid #cfd4da; text-align: center; line-height: 38rpx; font-size: 26rpx; color: #fff; flex: 0 0 auto; box-sizing: border-box; }
.chk.on { background: #12a150; border-color: #12a150; }
/* 批量底栏 */
.batchbar { position: fixed; left: 0; right: 0; bottom: 0; background: #fff; display: flex; align-items: center; justify-content: space-between; padding: 20rpx 32rpx calc(20rpx + env(safe-area-inset-bottom)); box-shadow: 0 -6rpx 20rpx rgba(0,0,0,0.06); z-index: 40; }
.bcount { font-size: 26rpx; color: #5b6470; }
.bdel { background: #e5484d; color: #fff; font-weight: 700; font-size: 28rpx; padding: 16rpx 40rpx; border-radius: 999rpx; }
.bdel.disabled { background: #f0c4c4; }

/* 分组卡 */
.daygrp { margin: 16rpx 24rpx 0; }
.dayhead { display: flex; justify-content: space-between; align-items: baseline; padding: 6rpx 8rpx 12rpx; font-size: 22rpx; color: #6b7280; }
.dayhead .dt { font-weight: 700; }
.dayhead .sum { display: flex; gap: 20rpx; }
.dayhead .sum .i { color: #12a150; }
.dayhead .sum .e { color: #f0553d; }
.card { background: #fff; border-radius: 20rpx; overflow: hidden; box-shadow: 0 6rpx 18rpx rgba(20,24,28,0.05); }
.tx { display: flex; align-items: center; gap: 20rpx; padding: 24rpx 26rpx; border-top: 1rpx solid #f1f3f5; }
.card .tx:first-child { border-top: none; }
.tico { width: 76rpx; height: 76rpx; border-radius: 50%; text-align: center; line-height: 76rpx; font-size: 38rpx; color: #fff; flex: 0 0 auto; }
.tinfo { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.tname { font-size: 30rpx; font-weight: 600; color: #16181c; }
.tsub { font-size: 22rpx; color: #9aa2ad; }
.tags { display: flex; flex-wrap: wrap; gap: 8rpx; margin-top: 4rpx; }
.tag { font-size: 20rpx; color: #0e8a44; background: #e6f6ec; border-radius: 6rpx; padding: 2rpx 12rpx; }
.tright { text-align: right; }
.tamt { font-size: 32rpx; font-weight: 800; }
.tamt.expense { color: #f0553d; }
.tamt.income { color: #12a150; }
.tamt.transfer { color: #8a94a6; }
.pctbar { height: 10rpx; background: #f0f0f0; border-radius: 6rpx; margin-top: 12rpx; overflow: hidden; width: 320rpx; max-width: 60vw; }
.pctfill { height: 100%; border-radius: 6rpx; }

.empty { display: flex; flex-direction: column; align-items: center; color: #9aa2ad; font-size: 28rpx; padding: 140rpx 40rpx; }
.empty .big { font-size: 90rpx; opacity: .5; margin-bottom: 20rpx; }
.hint { display: block; text-align: center; font-size: 22rpx; color: #bbb; margin: 28rpx 0; }

/* 遮罩/弹层 */
.mask { position: fixed; inset: 0; background: rgba(15,23,42,0.42); display: flex; align-items: flex-end; z-index: 50; }
.sheet { width: 100%; background: #fff; border-radius: 28rpx 28rpx 0 0; padding: 28rpx 28rpx calc(28rpx + env(safe-area-inset-bottom)); box-sizing: border-box; }
.sheet-title { display: block; text-align: center; font-size: 30rpx; font-weight: 800; margin-bottom: 16rpx; }

/* 月份选择器 */
.pick-year { display: flex; align-items: center; justify-content: center; gap: 60rpx; margin-bottom: 20rpx; }
.pick-year .arw { color: #9aa2ad; font-size: 40rpx; padding: 4rpx 16rpx; }
.pick-year .py { font-size: 32rpx; font-weight: 800; }
.mgrid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16rpx; }
.mcell { text-align: center; padding: 24rpx 0; border-radius: 14rpx; background: #f2f4f6; font-size: 26rpx; font-weight: 700; color: #5b6470; position: relative; }
.mcell.has::after { content: ''; position: absolute; left: 50%; bottom: 10rpx; transform: translateX(-50%); width: 8rpx; height: 8rpx; border-radius: 50%; background: #12a150; }
.mcell.sel { background: #12a150; color: #fff; }
.mcell.sel.has::after { background: #fff; }

/* 筛选面板 */
.fpanel { max-height: 86vh; display: flex; flex-direction: column; }
.fscroll { max-height: 60vh; }
.fgroup { margin-bottom: 20rpx; }
.flabel { display: block; font-size: 24rpx; color: #9aa2ad; margin-bottom: 12rpx; }
.pills { display: flex; flex-wrap: wrap; gap: 14rpx; }
.pill { padding: 12rpx 26rpx; border-radius: 999rpx; background: #f2f4f6; font-size: 24rpx; color: #5b6470; border: 1rpx solid transparent; }
.pill.on { background: #e6f6ec; color: #0e8a44; font-weight: 700; border-color: #12a150; }
.factions { display: flex; gap: 16rpx; margin-top: 16rpx; }
.freset { flex: 0 0 auto; padding: 22rpx 40rpx; border-radius: 14rpx; background: #f2f4f6; color: #5b6470; font-weight: 700; font-size: 28rpx; }
.fdone { flex: 1; text-align: center; padding: 22rpx 0; border-radius: 14rpx; background: #12a150; color: #fff; font-weight: 700; font-size: 28rpx; }

/* 搜索层 */
.searchlayer { position: fixed; inset: 0; background: #eef0f2; z-index: 60; display: flex; flex-direction: column; }
.sbar { background: #fff; display: flex; align-items: center; gap: 16rpx; padding: 16rpx 24rpx; padding-top: calc(16rpx + env(safe-area-inset-top)); }
.sback { font-size: 44rpx; color: #5b6470; }
.sinput { flex: 1; background: #f2f4f6; border-radius: 999rpx; padding: 16rpx 28rpx; font-size: 28rpx; }
.sgo { font-size: 28rpx; color: #0e8a44; font-weight: 700; }
.sresults { flex: 1; }
.scount { padding: 20rpx 32rpx; font-size: 24rpx; color: #9aa2ad; }
.scount .exp { color: #f0553d; font-weight: 700; }

/* 日历 */
.cal-nav { flex: 1; display: flex; align-items: center; justify-content: center; gap: 30rpx; }
.cal-nav .arw { color: #9aa2ad; font-size: 36rpx; padding: 0 12rpx; }
.cal-title { font-size: 30rpx; font-weight: 800; }
.calwrap { background: #fff; border-radius: 20rpx; margin: 20rpx 24rpx; padding: 16rpx 12rpx; box-shadow: 0 6rpx 18rpx rgba(20,24,28,0.05); }
.calweek { display: flex; }
.calweek text { flex: 1; text-align: center; font-size: 22rpx; color: #9aa2ad; padding: 8rpx 0; }
.calgrid { display: flex; flex-wrap: wrap; }
.calcell {
  width: 14.285%; height: 96rpx;
  display: flex; flex-direction: column; align-items: center; justify-content: flex-start;
  padding-top: 10rpx; box-sizing: border-box; border-radius: 12rpx;
}
.calcell.blank { visibility: hidden; }
.calcell.sel { background: #e6f6ec; }
.cd { font-size: 24rpx; color: #16181c; }
.ce { font-size: 16rpx; color: #f0553d; margin-top: 2rpx; }
.ci { font-size: 16rpx; color: #12a150; }
.cal-hint { text-align: center; color: #9aa2ad; font-size: 24rpx; padding: 40rpx; }
</style>
