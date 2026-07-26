<script setup lang="ts">
/**
 * 预算页：月度总预算 + 预算健康（前瞻）+ 分类预算。
 *
 * 设计要点：
 *  - 总预算卡：剩余可用大字 + 预算/已用 + 进度条（正常绿 / 预警黄 / 超支红）+ 状态徽标。
 *  - 预算健康区（仅当前月且已设总预算）：本月剩余天数 · 日均可用 · 预计月底结余。
 *  - 分类预算：未分配额度说明 + 各分类 预算/已用（笔数）/剩余 + 进度条 + 预警色；可增改删。
 *  - 「沿用上月」一键复制上月预算；任意年月选择器回看各月。
 *
 * 数据/隔离由后端保证；金额一律字符串（DECIMAL/BigDecimal）。
 */
import { ref, computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import {
  fetchBudgetOverview,
  setTotalBudget,
  setCategoryBudget,
  deleteCategoryBudget,
  copyPreviousBudget,
  toBudgetErrorMessage,
  fetchCategories,
  categoryEmoji,
  formatAmount,
  currentMonth,
  monthLabel,
  type BudgetOverview,
  type CategoryBudgetItem,
  type Category,
} from '@/lib/ledger'

const loading = ref(true)
const loadError = ref('')
const overview = ref<BudgetOverview | null>(null)
const categories = ref<Category[]>([])

const month = ref(currentMonth())

onMounted(async () => {
  try {
    categories.value = await fetchCategories()
  } catch {
    /* 分类拉取失败不阻塞预算展示 */
  }
  await load()
})

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    overview.value = await fetchBudgetOverview(month.value)
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败，请重试'
  } finally {
    loading.value = false
  }
}

// === 月份选择器（任意年月，不可选未来月，复用首页交互） ===
const pickerOpen = ref(false)
const pickerYear = ref(Number(currentMonth().split('-')[0]))
const cur = (() => {
  const [y, m] = currentMonth().split('-').map(Number)
  return { y: y ?? 1970, m: m ?? 1 }
})()
const selectedYearNum = computed(() => Number(month.value.split('-')[0]))
const selectedMonthNum = computed(() => Number(month.value.split('-')[1]))
function openPicker() {
  pickerYear.value = selectedYearNum.value
  pickerOpen.value = true
}
function stepYear(delta: number) {
  pickerYear.value += delta
}
function isFutureMonth(y: number, m: number): boolean {
  return y > cur.y || (y === cur.y && m > cur.m)
}
async function pickMonth(m: number) {
  if (isFutureMonth(pickerYear.value, m)) return
  month.value = `${pickerYear.value}-${String(m).padStart(2, '0')}`
  pickerOpen.value = false
  await load()
}

// === 展示辅助 ===
function barClass(status: string | null): string {
  if (status === 'OVER') return 'over'
  if (status === 'WARN') return 'warn'
  return ''
}
function statusBadge(o: BudgetOverview): { cls: string; text: string } {
  if (o.status === 'OVER') return { cls: 'over', text: '🔴 已超支' }
  if (o.status === 'WARN') return { cls: 'warn', text: `⚠ 已用 ${o.usedPercent}%` }
  return { cls: 'ok', text: `已用 ${o.usedPercent}%` }
}
function widthPct(pct: number): string {
  return `${Math.min(Math.max(pct, 0), 100)}%`
}

/** 分类图标浅色底调色板。 */
const CAT_TINTS = ['#e9f7ef', '#e8f0fe', '#fff1e6', '#f3ecff', '#fdeaf3', '#e6f6ff', '#fff6e0', '#eafaf0']
function tintOf(index: number): string {
  return CAT_TINTS[index % CAT_TINTS.length]!
}
function emojiOf(name: string): string {
  return categoryEmoji(name, 'EXPENSE')
}

// === 提交反馈 ===
const submitting = ref(false)
const errorMsg = ref('')

// === 总预算编辑 ===
const totalSheetOpen = ref(false)
const totalAmount = ref('')
function openTotalSheet() {
  totalAmount.value = overview.value?.totalBudget ?? ''
  errorMsg.value = ''
  totalSheetOpen.value = true
}
async function saveTotal() {
  errorMsg.value = ''
  const amt = sanitizeAmount(totalAmount.value)
  if (!amt) {
    errorMsg.value = '请输入预算金额'
    return
  }
  submitting.value = true
  try {
    overview.value = await setTotalBudget(month.value, amt)
    totalSheetOpen.value = false
  } catch (e) {
    errorMsg.value = toBudgetErrorMessage(e)
  } finally {
    submitting.value = false
  }
}

// === 分类预算编辑 ===
const catSheetOpen = ref(false)
const catSheetEditing = ref(false) // true=编辑已有分类预算（分类固定）；false=新增（可选分类）
const catSheetCategoryId = ref<number | null>(null)
const catSheetAmount = ref('')
const catSheetEditingName = ref('') // 编辑已有时展示名称（分类不可改）

/** 可选的支出叶子分类（未设预算的），供新增时选择。 */
const expenseLeafOptions = computed(() => {
  const list = categories.value.filter((c) => c.kind === 'EXPENSE')
  const parents = list.filter((c) => c.parentId == null)
  const childrenByParent = new Map<number, Category[]>()
  for (const c of list) {
    if (c.parentId != null) {
      const arr = childrenByParent.get(c.parentId) ?? []
      arr.push(c)
      childrenByParent.set(c.parentId, arr)
    }
  }
  const opts: { id: number; name: string }[] = []
  for (const p of parents) {
    const kids = childrenByParent.get(p.id)
    if (kids && kids.length) {
      for (const k of kids) opts.push({ id: k.id, name: `${p.name}·${k.name}` })
    } else {
      opts.push({ id: p.id, name: p.name })
    }
  }
  const budgeted = new Set((overview.value?.categories ?? []).map((c) => c.categoryId))
  return opts.filter((o) => !budgeted.has(o.id))
})

function openAddCategory() {
  catSheetEditing.value = false
  catSheetCategoryId.value = null
  catSheetAmount.value = ''
  catSheetEditingName.value = ''
  errorMsg.value = ''
  catSheetOpen.value = true
}
function openEditCategory(item: CategoryBudgetItem) {
  catSheetEditing.value = true
  catSheetCategoryId.value = item.categoryId
  catSheetAmount.value = item.budget
  catSheetEditingName.value = item.name
  errorMsg.value = ''
  catSheetOpen.value = true
}
async function saveCategory() {
  errorMsg.value = ''
  if (catSheetCategoryId.value == null) {
    errorMsg.value = '请选择分类'
    return
  }
  const amt = sanitizeAmount(catSheetAmount.value)
  if (!amt) {
    errorMsg.value = '请输入预算金额'
    return
  }
  submitting.value = true
  try {
    overview.value = await setCategoryBudget(month.value, catSheetCategoryId.value, amt)
    catSheetOpen.value = false
  } catch (e) {
    errorMsg.value = toBudgetErrorMessage(e)
  } finally {
    submitting.value = false
  }
}
async function removeCategory(item: CategoryBudgetItem) {
  submitting.value = true
  try {
    overview.value = await deleteCategoryBudget(month.value, item.categoryId)
    catSheetOpen.value = false
  } catch (e) {
    errorMsg.value = toBudgetErrorMessage(e)
  } finally {
    submitting.value = false
  }
}

// === 沿用上月 ===
async function onCopyPrevious() {
  submitting.value = true
  errorMsg.value = ''
  try {
    overview.value = await copyPreviousBudget(month.value)
  } catch (e) {
    errorMsg.value = toBudgetErrorMessage(e)
  } finally {
    submitting.value = false
  }
}

/** 清洗金额输入：仅保留数字与小数点，最多两位小数、9 位整数；返回合法字符串或空串。 */
function sanitizeAmount(raw: string): string {
  let v = String(raw).replace(/[^\d.]/g, '')
  const dot = v.indexOf('.')
  if (dot !== -1) {
    const int = v.slice(0, dot).replace(/\./g, '').slice(0, 16)
    const dec = v.slice(dot + 1).replace(/\./g, '').slice(0, 2)
    v = `${int}.${dec}`
  } else {
    v = v.slice(0, 16)
  }
  const n = Number(v)
  if (!Number.isFinite(n) || n < 0.01) return ''
  return v
}
function onAmountInput(e: Event, target: 'total' | 'cat') {
  const el = e.target as HTMLInputElement
  const v = el.value.replace(/[^\d.]/g, '')
  if (target === 'total') totalAmount.value = v
  else catSheetAmount.value = v
  el.value = v
  errorMsg.value = ''
}
</script>

<template>
  <section class="budget">
    <header class="bud-head">
      <h1>预算</h1>
      <button type="button" class="month-chip" @click="openPicker">
        {{ monthLabel(month) }} <span class="car">▾</span>
      </button>
    </header>

    <div v-if="loadError" class="banner banner-err" role="alert">
      <span>{{ loadError }}</span>
      <button class="link-btn" type="button" @click="load">重试</button>
    </div>
    <p v-if="loading" class="text-muted loading">加载中…</p>

    <template v-else-if="overview">
      <!-- 空态：未设总预算且无分类预算 -->
      <div v-if="!overview.hasBudget && overview.categories.length === 0" class="card empty">
        <div class="empty-ic">🧮</div>
        <p class="empty-title">给这个月定个预算吧</p>
        <p class="text-muted empty-sub">设定后，有余会帮你盯着别超支，还能算出每天还能花多少。</p>
        <button type="button" class="btn" @click="openTotalSheet">设置月度总预算</button>
        <button type="button" class="link-btn copy" @click="onCopyPrevious" :disabled="submitting">
          或 沿用上月预算
        </button>
      </div>

      <template v-else>
        <!-- 总预算卡 -->
        <div v-if="overview.hasBudget" class="card tot">
          <div class="tot-head">
            <span class="t">月度总预算</span>
            <span class="badge" :class="statusBadge(overview).cls">{{ statusBadge(overview).text }}</span>
          </div>
          <div class="tot-nums">
            <div class="left">
              <div class="k">剩余可用</div>
              <div class="v num" :class="{ neg: Number(overview.remaining) < 0 }">
                ¥{{ formatAmount(overview.remaining ?? '0') }}
              </div>
            </div>
            <div class="right text-muted">
              预算 ¥{{ formatAmount(overview.totalBudget ?? '0') }}
              <b class="num">已用 ¥{{ formatAmount(overview.spent) }}</b>
            </div>
          </div>
          <div class="bar" :class="barClass(overview.status)">
            <span :style="{ width: widthPct(overview.usedPercent) }"></span>
          </div>
          <div class="tot-actions">
            <button type="button" class="link-btn" @click="openTotalSheet">编辑总预算</button>
            <button type="button" class="link-btn" @click="onCopyPrevious" :disabled="submitting">沿用上月</button>
          </div>
        </div>

        <!-- 未设总预算但有分类预算：引导设置总预算 -->
        <div v-else class="card set-total-hint">
          <span class="text-muted">还没设月度总预算</span>
          <button type="button" class="btn btn-sm" @click="openTotalSheet">设置</button>
        </div>

        <!-- 预算健康区（仅当前月且已设总预算） -->
        <div v-if="overview.hasBudget && overview.currentMonth && overview.health" class="health">
          <div class="hc">
            <div class="k">本月还剩</div>
            <div class="v">{{ overview.health.daysLeft }} 天</div>
          </div>
          <div class="hc">
            <div class="k">日均可用</div>
            <div class="v good">¥{{ formatAmount(overview.health.dailyAvailable) }}</div>
          </div>
          <div class="hc">
            <div class="k">预计月底</div>
            <div class="v" :class="overview.health.projectedOver ? 'bad' : 'good'">
              {{ overview.health.projectedOver ? '超支' : '结余' }}
              ¥{{ formatAmount(overview.health.projectedBalance.replace('-', '')) }}
            </div>
          </div>
        </div>

        <!-- 分类预算 -->
        <div class="card cats">
          <div class="sec">
            <h2>分类预算</h2>
            <button type="button" class="add" aria-label="新增分类预算" @click="openAddCategory">＋</button>
          </div>
          <p v-if="overview.hasBudget" class="unalloc text-muted">
            未分配额度：<b class="num" :class="{ neg: Number(overview.unallocated) < 0 }">
              ¥{{ formatAmount(overview.unallocated ?? '0') }}</b>
            （总预算 − 已分配 ¥{{ formatAmount(overview.allocated) }}）
          </p>

          <p v-if="overview.categories.length === 0" class="text-muted empty-cats">
            还没有分类预算，点右上角「＋」给某个支出分类设个上限。
          </p>

          <button
            v-for="(item, i) in overview.categories"
            :key="item.categoryId"
            type="button"
            class="cat"
            @click="openEditCategory(item)"
          >
            <span class="ic" :style="{ background: tintOf(i) }">{{ emojiOf(item.name) }}</span>
            <div class="body">
              <div class="row1">
                <span class="nm">{{ item.name }}</span>
                <span class="left-amt num" :class="{ bad: item.status === 'OVER' }">
                  {{ item.status === 'OVER' ? '超' : '剩' }}
                  ¥{{ formatAmount(item.remaining.replace('-', '')) }}
                </span>
              </div>
              <div class="cbar" :class="barClass(item.status)">
                <span :style="{ width: widthPct(item.usedPercent) }"></span>
              </div>
              <div class="row2 text-muted">
                <span>预算 ¥{{ formatAmount(item.budget) }} · 已用 ¥{{ formatAmount(item.spent) }}（{{ item.txCount }}笔）</span>
                <span :class="{ bad: item.status === 'OVER' }">{{ item.usedPercent }}%</span>
              </div>
            </div>
          </button>
        </div>
      </template>
    </template>

    <!-- 月份选择底部面板 -->
    <div v-if="pickerOpen" class="sheet-mask" @click.self="pickerOpen = false">
      <div class="sheet" role="dialog" aria-label="选择月份">
        <div class="sheet-head">
          <button type="button" class="s-cancel" @click="pickerOpen = false">取消</button>
          <span class="s-title">选择月份</span>
          <span class="s-spacer"></span>
        </div>
        <div class="year-row">
          <button type="button" class="y-arrow" aria-label="上一年" @click="stepYear(-1)">‹</button>
          <span class="y-val num">{{ pickerYear }}</span>
          <button type="button" class="y-arrow" aria-label="下一年" :disabled="pickerYear >= cur.y" @click="stepYear(1)">›</button>
        </div>
        <div class="months">
          <button
            v-for="m in 12"
            :key="m"
            type="button"
            class="mo"
            :class="{ active: pickerYear === selectedYearNum && m === selectedMonthNum, future: isFutureMonth(pickerYear, m) }"
            :disabled="isFutureMonth(pickerYear, m)"
            @click="pickMonth(m)"
          >
            {{ m }}月
          </button>
        </div>
      </div>
    </div>

    <!-- 总预算编辑面板 -->
    <div v-if="totalSheetOpen" class="sheet-mask" @click.self="totalSheetOpen = false">
      <div class="sheet" role="dialog" aria-label="设置月度总预算">
        <div class="sheet-head">
          <button type="button" class="s-cancel" @click="totalSheetOpen = false">取消</button>
          <span class="s-title">月度总预算</span>
          <button type="button" class="s-ok" :disabled="submitting" @click="saveTotal">保存</button>
        </div>
        <div class="amount-field">
          <span class="cur">¥</span>
          <input
            :value="totalAmount"
            @input="onAmountInput($event, 'total')"
            type="text"
            inputmode="decimal"
            placeholder="0.00"
            aria-label="预算金额"
          />
        </div>
        <p v-if="errorMsg" class="feedback err">{{ errorMsg }}</p>
      </div>
    </div>

    <!-- 分类预算编辑面板 -->
    <div v-if="catSheetOpen" class="sheet-mask" @click.self="catSheetOpen = false">
      <div class="sheet" role="dialog" aria-label="设置分类预算">
        <div class="sheet-head">
          <button type="button" class="s-cancel" @click="catSheetOpen = false">取消</button>
          <span class="s-title">分类预算</span>
          <button type="button" class="s-ok" :disabled="submitting" @click="saveCategory">保存</button>
        </div>

        <template v-if="!catSheetEditing">
          <div class="field-label">选择支出分类</div>
          <p v-if="expenseLeafOptions.length === 0" class="text-muted small">
            没有可设预算的支出分类，先去<RouterLink to="/categories">分类管理</RouterLink>添加。
          </p>
          <div v-else class="cat-pick">
            <button
              v-for="opt in expenseLeafOptions"
              :key="opt.id"
              type="button"
              class="pick"
              :class="{ active: catSheetCategoryId === opt.id }"
              @click="catSheetCategoryId = opt.id"
            >
              {{ emojiOf(opt.name) }} {{ opt.name }}
            </button>
          </div>
        </template>
        <div v-else class="editing-name">{{ catSheetEditingName }}</div>

        <div class="amount-field">
          <span class="cur">¥</span>
          <input
            :value="catSheetAmount"
            @input="onAmountInput($event, 'cat')"
            type="text"
            inputmode="decimal"
            placeholder="0.00"
            aria-label="分类预算金额"
          />
        </div>
        <p v-if="errorMsg" class="feedback err">{{ errorMsg }}</p>

        <button
          v-if="catSheetEditing && catSheetCategoryId != null"
          type="button"
          class="del-btn"
          :disabled="submitting"
          @click="removeCategory({ categoryId: catSheetCategoryId } as CategoryBudgetItem)"
        >
          删除该分类预算
        </button>
      </div>
    </div>
  </section>
</template>

<style scoped>
.num {
  font-variant-numeric: tabular-nums;
}
.budget {
  padding-bottom: 24px;
}
.bud-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.bud-head h1 {
  margin: 0;
  font-size: 22px;
}
.month-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  border: 1px solid var(--color-border);
  border-radius: 999px;
  background: var(--color-surface);
  font-size: 14px;
  font-weight: 700;
  cursor: pointer;
}
.month-chip .car {
  font-size: 11px;
  color: var(--color-muted);
}
.loading {
  padding: 24px 0;
  text-align: center;
}

.card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: 18px;
  margin-bottom: 14px;
}

/* 空态 */
.empty {
  text-align: center;
  display: grid;
  gap: 8px;
  justify-items: center;
  padding: 32px 20px;
}
.empty-ic {
  font-size: 40px;
}
.empty-title {
  font-weight: 700;
  font-size: 16px;
}
.empty-sub {
  font-size: 13px;
}
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 0 22px;
  margin-top: 6px;
  border: none;
  border-radius: 12px;
  background: var(--color-primary);
  color: #fff;
  font-weight: 700;
  cursor: pointer;
}
.btn-sm {
  min-height: 36px;
  padding: 0 16px;
  font-size: 14px;
}
.copy {
  margin-top: 4px;
}

/* 总预算卡 */
.tot-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}
.tot-head .t {
  font-size: 15px;
  font-weight: 700;
}
.badge {
  font-size: 12px;
  font-weight: 700;
  padding: 3px 10px;
  border-radius: 999px;
}
.badge.ok {
  background: #ecfdf3;
  color: var(--color-primary-dark);
}
.badge.warn {
  background: #fff4e5;
  color: #b45309;
}
.badge.over {
  background: #fef2f2;
  color: var(--color-danger);
}
.tot-nums {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 10px;
}
.tot-nums .left .k {
  font-size: 12px;
  color: var(--color-muted);
}
.tot-nums .left .v {
  font-size: 30px;
  font-weight: 850;
  letter-spacing: -0.02em;
}
.tot-nums .left .v.neg {
  color: var(--color-danger);
}
.tot-nums .right {
  text-align: right;
  font-size: 12px;
}
.tot-nums .right b {
  display: block;
  font-size: 15px;
  color: var(--color-text);
  font-weight: 700;
  margin-top: 2px;
}
.bar {
  height: 10px;
  border-radius: 6px;
  background: var(--color-bg);
  overflow: hidden;
}
.bar > span {
  display: block;
  height: 100%;
  border-radius: 6px;
  background: linear-gradient(90deg, #22c55e, #16a34a);
}
.bar.warn > span {
  background: linear-gradient(90deg, #fbbf24, #f59e0b);
}
.bar.over > span {
  background: linear-gradient(90deg, #f87171, #dc2626);
}
.tot-actions {
  display: flex;
  gap: 18px;
  margin-top: 12px;
}
.set-total-hint {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

/* 健康区 */
.health {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
}
.hc {
  flex: 1;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 14px;
  padding: 12px;
  text-align: center;
}
.hc .k {
  font-size: 11px;
  color: var(--color-muted);
}
.hc .v {
  font-size: 16px;
  font-weight: 800;
  margin-top: 4px;
  white-space: nowrap;
}
.hc .v.good {
  color: var(--color-primary);
}
.hc .v.bad {
  color: var(--color-danger);
}

/* 分类预算 */
.sec {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}
.sec h2 {
  margin: 0;
  font-size: 16px;
}
.add {
  border: none;
  background: none;
  font-size: 24px;
  color: var(--color-primary);
  cursor: pointer;
  line-height: 1;
}
.unalloc {
  font-size: 12px;
  margin: 0 0 12px;
}
.unalloc b {
  color: var(--color-text);
}
.unalloc b.neg {
  color: var(--color-danger);
}
.empty-cats {
  font-size: 13px;
  padding: 12px 0;
}
.cat {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border: none;
  border-top: 1px solid var(--color-border);
  background: none;
  text-align: left;
  cursor: pointer;
}
.cats .cat:first-of-type {
  border-top: none;
}
.cat .ic {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 19px;
  flex: 0 0 auto;
}
.cat .body {
  flex: 1;
  min-width: 0;
}
.cat .row1 {
  display: flex;
  justify-content: space-between;
  font-size: 14px;
}
.cat .row1 .nm {
  font-weight: 700;
}
.cat .row1 .left-amt {
  font-weight: 800;
}
.cat .row1 .left-amt.bad {
  color: var(--color-danger);
}
.cbar {
  height: 6px;
  border-radius: 4px;
  background: var(--color-bg);
  overflow: hidden;
  margin: 7px 0 5px;
}
.cbar > span {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: var(--color-primary);
}
.cbar.warn > span {
  background: #f59e0b;
}
.cbar.over > span {
  background: var(--color-danger);
}
.cat .row2 {
  display: flex;
  justify-content: space-between;
  font-size: 12px;
}
.cat .row2 .bad {
  color: var(--color-danger);
}

/* 底部面板通用 */
.sheet-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.4);
  display: flex;
  align-items: flex-end;
  justify-content: center;
  z-index: 60;
}
.sheet {
  width: 100%;
  max-width: 480px;
  background: var(--color-surface);
  border-radius: 18px 18px 0 0;
  padding: 14px 16px calc(20px + var(--safe-bottom));
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.s-cancel {
  border: none;
  background: none;
  color: var(--color-muted);
  font-size: 14px;
  cursor: pointer;
}
.s-title {
  font-size: 16px;
  font-weight: 800;
}
.s-ok {
  border: none;
  background: none;
  color: var(--color-primary);
  font-weight: 700;
  font-size: 14px;
  cursor: pointer;
}
.s-ok:disabled {
  opacity: 0.5;
}
.s-spacer {
  width: 28px;
}
.year-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 20px;
  margin-bottom: 16px;
}
.y-val {
  font-size: 20px;
  font-weight: 800;
  min-width: 72px;
  text-align: center;
}
.y-arrow {
  width: 34px;
  height: 34px;
  border: 1px solid var(--color-border);
  border-radius: 9px;
  background: var(--color-surface);
  font-size: 16px;
  cursor: pointer;
}
.y-arrow:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
.months {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
}
.mo {
  height: 46px;
  border: 1px solid var(--color-border);
  border-radius: 11px;
  background: var(--color-surface);
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text);
  cursor: pointer;
}
.mo.active {
  background: var(--color-primary);
  color: #fff;
  border-color: var(--color-primary);
}
.mo.future,
.mo:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

/* 金额输入 */
.amount-field {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
}
.amount-field .cur {
  font-size: 20px;
  color: var(--color-muted);
}
.amount-field input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  font-size: 30px;
  font-weight: 800;
  color: var(--color-primary);
  background: none;
}
.field-label {
  font-size: 13px;
  color: var(--color-muted);
  font-weight: 600;
  margin-bottom: 10px;
}
.small {
  font-size: 13px;
}
.cat-pick {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
  max-height: 180px;
  overflow-y: auto;
}
.pick {
  padding: 8px 12px;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: var(--color-surface);
  font-size: 14px;
  cursor: pointer;
}
.pick.active {
  border-color: var(--color-primary);
  background: #ecfdf5;
  color: var(--color-primary-dark);
  font-weight: 700;
}
.editing-name {
  font-weight: 700;
  margin-bottom: 12px;
}
.del-btn {
  width: 100%;
  margin-top: 14px;
  padding: 12px 0;
  border: none;
  background: #fef2f2;
  color: var(--color-danger);
  border-radius: 12px;
  font-weight: 700;
  cursor: pointer;
}

/* 反馈 / 通用 */
.feedback {
  margin: 12px 0 0;
  font-size: 13px;
}
.feedback.err {
  color: var(--color-danger);
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
  color: var(--color-primary);
  font-weight: 600;
  font-size: 13px;
  cursor: pointer;
}
.link-btn:disabled {
  opacity: 0.5;
}

@media (min-width: 768px) {
  .sheet-mask {
    align-items: center;
  }
  .sheet {
    max-width: 420px;
    border-radius: 16px;
  }
  .budget {
    max-width: 720px;
  }
}
</style>
