<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import {
  createRecurringRule,
  updateRecurringRule,
  fetchRecurringRule
} from '../../api/recurring'
import { listCategories } from '../../api/category'
import { listSelectableAccounts, accountDisplayName, accountTypeLabel } from '../../api/account'
import { formatAmount } from '../../utils/format'
import { safeBack } from '../../utils/nav'
import {
  TYPE_OPTIONS,
  FREQUENCY_OPTIONS,
  END_CONDITION_OPTIONS,
  WEEKDAY_OPTIONS,
  POST_MODE_OPTIONS,
  POST_MODE_HINTS,
  validateRuleForm,
  buildRulePayload,
  mapRuleError,
  ruleToForm,
  ruleSummary,
  endConditionLabel
} from '../../utils/recurring'

/**
 * 周期规则新建 / 编辑页（任务 9.3，需求 1.1、1.5、1.6、1.7、1.8、6.3）。
 *
 * 同一页面处理新建（无 id 参数）与编辑（带 id → fetchRecurringRule 回填）。表单含：
 * 类型（支出 / 收入）、金额、分类、账户（复用 AccountBadge）、备注，
 * 频率（每天 / 每周星期几多选 / 每月指定日或月末 / 每年月+日）、开始日期、结束条件（永不 / 到某日 / 共 N 次）。
 *
 * 表单态 → 后端 RecurringRuleRequest 的构造、提交前本地校验、后端错误码→字段提示的映射，
 * 全部收敛到 utils/recurring.js 的纯函数（可单测）；本页只做数据加载、选择器交互与提交编排。
 * 频率摘要用 utils/recurring.js 的 ruleSummary 做实时预览。
 */

const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'

const editingId = ref(null)
const isEditing = computed(() => editingId.value != null)

// 表单态：默认支出 / 每月 1 日 / 永不结束。
const form = ref({
  type: 'expense',
  amountText: '',
  categoryId: null,
  accountId: null,
  note: '',
  frequency: 'MONTHLY',
  weeklyDays: [],
  monthEnd: false,
  monthDay: 1,
  yearMonth: 1,
  yearDay: 1,
  startDate: '',
  endCondition: 'NEVER',
  untilDate: '',
  countN: '',
  // 入账方式：默认「待确认」，与后端默认 CONFIRM 一致（recurring-auto-post 需求 7.2）。
  postMode: 'CONFIRM'
})

// 当前入账方式的说明文案。
const postModeHint = computed(() => POST_MODE_HINTS[form.value.postMode] || '')

// 分类树 / 账户可选集。
const tree = ref({ expense: [], income: [] })
const accounts = ref([])
const loadState = ref('loading') // loading | ready | error

const parents = computed(() => (form.value.type === 'income' ? tree.value.income : tree.value.expense))
const expandedId = ref(null)
const expandedChildren = computed(() => {
  const p = parents.value.find((x) => x.id === expandedId.value)
  return p ? p.children || [] : []
})

const selectedAccount = computed(() => accounts.value.find((a) => a.id === form.value.accountId) || null)
const accountSheet = ref(false)

const monthDayOptions = Array.from({ length: 31 }, (_, i) => i + 1)
const monthOptions = Array.from({ length: 12 }, (_, i) => i + 1)

// 实时频率摘要预览：把当前表单折算成 RecurringRuleResponse 形状喂给 ruleSummary。
const previewRule = computed(() => ({
  type: form.value.type,
  amount: form.value.amountText || 0,
  frequency: form.value.frequency,
  weeklyDays: form.value.weeklyDays,
  monthDay: form.value.monthDay,
  monthEnd: form.value.monthEnd,
  yearMonth: form.value.yearMonth,
  yearDay: form.value.yearDay,
  endCondition: form.value.endCondition,
  untilDate: form.value.untilDate,
  countN: Number(form.value.countN) || null
}))
const summaryText = computed(() => ruleSummary(previewRule.value))
const endText = computed(() => endConditionLabel(previewRule.value))

onLoad(async (q) => {
  editingId.value = q && q.id ? Number(q.id) : null
  await load()
})

async function load() {
  loadState.value = 'loading'
  try {
    const [cats, accs] = await Promise.all([listCategories(), listSelectableAccounts()])
    tree.value = cats || { expense: [], income: [] }
    accounts.value = Array.isArray(accs) ? accs : []
    if (isEditing.value) {
      const rule = await fetchRecurringRule(editingId.value)
      form.value = ruleToForm(rule)
    } else {
      // 默认账户：可选集第一个（用户可改）。
      form.value.accountId = accounts.value[0]?.id ?? null
    }
    loadState.value = 'ready'
  } catch (e) {
    loadState.value = 'error'
    uni.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
  }
}

// —— 类型 / 分类 ——

function setType(t) {
  if (form.value.type === t) return
  form.value.type = t
  form.value.categoryId = null
  expandedId.value = null
}

function pickParent(p) {
  form.value.categoryId = p.id
  expandedId.value = p.children && p.children.length ? (expandedId.value === p.id ? null : p.id) : null
}
function pickChild(c) {
  form.value.categoryId = c.id
}

// —— 账户 ——

function pickAccount(a) {
  form.value.accountId = a.id
  accountSheet.value = false
}

// —— 频率 ——

function setFrequency(freq) {
  form.value.frequency = freq
}
function toggleWeekday(day) {
  const i = form.value.weeklyDays.indexOf(day)
  if (i >= 0) form.value.weeklyDays.splice(i, 1)
  else form.value.weeklyDays.push(day)
}
function isWeekdayOn(day) {
  return form.value.weeklyDays.includes(day)
}
function setMonthMode(end) {
  form.value.monthEnd = end
}
function onMonthDayChange(e) {
  form.value.monthDay = monthDayOptions[Number(e.detail.value)]
}
function onYearMonthChange(e) {
  form.value.yearMonth = monthOptions[Number(e.detail.value)]
}
function onYearDayChange(e) {
  form.value.yearDay = monthDayOptions[Number(e.detail.value)]
}

// —— 开始日期 / 结束条件 ——

function onStartDateChange(e) {
  form.value.startDate = e.detail.value
}
function clearStartDate() {
  form.value.startDate = ''
}
function setEndCondition(cond) {
  form.value.endCondition = cond
}
function setPostMode(mode) {
  form.value.postMode = mode
}
function onUntilDateChange(e) {
  form.value.untilDate = e.detail.value
}

// —— 提交 ——

const submitting = ref(false)
async function submit() {
  if (submitting.value) return
  const check = validateRuleForm(form.value)
  if (!check.ok) {
    uni.showToast({ title: check.message, icon: 'none' })
    return
  }
  const payload = buildRulePayload(form.value)
  submitting.value = true
  try {
    if (isEditing.value) {
      await updateRecurringRule(editingId.value, payload)
    } else {
      await createRecurringRule(payload)
    }
    uni.showToast({ title: isEditing.value ? '已保存' : '已创建', icon: 'success' })
    setTimeout(() => safeBack('/pages/recurring/recurring'), 500)
  } catch (e) {
    const mapped = mapRuleError(e)
    uni.showToast({ title: mapped.message, icon: 'none' })
  } finally {
    submitting.value = false
  }
}

function goBack() {
  safeBack('/pages/recurring/recurring')
}
</script>

<template>
  <view class="page">
    <view class="statusbar" :style="{ height: statusBarHeight }"></view>
    <view class="rnav">
      <text class="nb back" @click="goBack">‹</text>
      <text class="title">{{ isEditing ? '编辑周期规则' : '新建周期规则' }}</text>
      <view class="nb spacer"></view>
    </view>

    <!-- 加载中 / 出错 -->
    <view v-if="loadState === 'loading'" class="hint">正在加载…</view>
    <view v-else-if="loadState === 'error'" class="hint">
      <text>加载失败</text>
      <text class="retry" @click="load">重试</text>
    </view>

    <scroll-view v-else scroll-y class="main">
      <!-- 类型 -->
      <view class="seg">
        <text
          v-for="t in TYPE_OPTIONS"
          :key="t.value"
          class="s"
          :class="{ on: form.type === t.value }"
          @click="setType(t.value)"
        >{{ t.label }}</text>
      </view>

      <!-- 金额 -->
      <view class="amtbox">
        <text class="cny">¥</text>
        <input class="amt" v-model="form.amountText" type="digit" placeholder="0.00" placeholder-class="amt-ph" />
      </view>

      <!-- 分类 -->
      <view class="sec-hd">分类</view>
      <view v-if="!parents.length" class="empty">
        还没有{{ form.type === 'income' ? '收入' : '支出' }}分类，
        <text class="link" @click="uni.navigateTo({ url: '/pages/categories/categories' })">去添加</text>
      </view>
      <view v-else class="cgrid">
        <view
          v-for="p in parents"
          :key="p.id"
          class="cat"
          :class="{ on: form.categoryId === p.id }"
          @click="pickParent(p)"
        >
          <view class="cic">
            <CategoryIcon :icon="p.icon" :name="p.name" :kind="form.type" :color="p.iconColor" :size="46" :round="true" />
            <text v-if="p.children && p.children.length" class="subdot">{{ expandedId === p.id ? '▴' : '▾' }}</text>
          </view>
          <text class="cl" :class="{ on: form.categoryId === p.id }">{{ p.name }}</text>
        </view>
      </view>
      <view v-if="expandedChildren.length" class="subwrap">
        <view
          v-for="c in expandedChildren"
          :key="c.id"
          class="cat"
          :class="{ on: form.categoryId === c.id }"
          @click="pickChild(c)"
        >
          <view class="cic sub"><CategoryIcon :icon="c.icon" :name="c.name" :kind="form.type" :color="c.iconColor" :size="40" :round="true" /></view>
          <text class="cl" :class="{ on: form.categoryId === c.id }">{{ c.name }}</text>
        </view>
      </view>

      <!-- 账户 -->
      <view class="frow" @click="accountSheet = true">
        <text class="fk">账户</text>
        <view class="fv row">
          <template v-if="selectedAccount">
            <AccountBadge :account="selectedAccount" :size="44" />
            <text>{{ accountDisplayName(selectedAccount) }} ▾</text>
          </template>
          <text v-else class="muted">选择账户 ▾</text>
        </view>
      </view>

      <!-- 备注 -->
      <view class="frow">
        <text class="fk">备注</text>
        <input class="fv-input" v-model="form.note" placeholder="添加备注" placeholder-class="ph" :maxlength="200" />
      </view>

      <!-- 频率 -->
      <view class="sec-hd">频率</view>
      <view class="seg wrap">
        <text
          v-for="opt in FREQUENCY_OPTIONS"
          :key="opt.value"
          class="s"
          :class="{ on: form.frequency === opt.value }"
          @click="setFrequency(opt.value)"
        >{{ opt.label }}</text>
      </view>

      <!-- 每周：星期几多选 -->
      <view v-if="form.frequency === 'WEEKLY'" class="week-row">
        <text
          v-for="w in WEEKDAY_OPTIONS"
          :key="w.value"
          class="wchip"
          :class="{ on: isWeekdayOn(w.value) }"
          @click="toggleWeekday(w.value)"
        >{{ w.label }}</text>
      </view>

      <!-- 每月：指定日 / 月末 -->
      <template v-if="form.frequency === 'MONTHLY'">
        <view class="seg sub">
          <text class="s" :class="{ on: !form.monthEnd }" @click="setMonthMode(false)">指定日</text>
          <text class="s" :class="{ on: form.monthEnd }" @click="setMonthMode(true)">月末</text>
        </view>
        <picker v-if="!form.monthEnd" class="frow picker" mode="selector" :range="monthDayOptions" @change="onMonthDayChange">
          <text class="fk">每月</text>
          <text class="fv">{{ form.monthDay }} 日 ›</text>
        </picker>
      </template>

      <!-- 每年：月 + 日 -->
      <view v-if="form.frequency === 'YEARLY'" class="year-row">
        <picker class="frow picker flex1" mode="selector" :range="monthOptions" @change="onYearMonthChange">
          <text class="fk">月</text>
          <text class="fv">{{ form.yearMonth }} 月 ›</text>
        </picker>
        <picker class="frow picker flex1" mode="selector" :range="monthDayOptions" @change="onYearDayChange">
          <text class="fk">日</text>
          <text class="fv">{{ form.yearDay }} 日 ›</text>
        </picker>
      </view>

      <!-- 开始日期 -->
      <view class="frow">
        <text class="fk">开始日期</text>
        <view class="fv row">
          <picker mode="date" :value="form.startDate" @change="onStartDateChange">
            <text :class="{ muted: !form.startDate }">{{ form.startDate || '创建当日 ›' }}</text>
          </picker>
          <text v-if="form.startDate" class="clear" @click="clearStartDate">清除</text>
        </view>
      </view>

      <!-- 结束条件 -->
      <view class="sec-hd">结束条件</view>
      <view class="seg wrap">
        <text
          v-for="opt in END_CONDITION_OPTIONS"
          :key="opt.value"
          class="s"
          :class="{ on: form.endCondition === opt.value }"
          @click="setEndCondition(opt.value)"
        >{{ opt.label }}</text>
      </view>
      <picker v-if="form.endCondition === 'UNTIL_DATE'" class="frow picker" mode="date" :value="form.untilDate" @change="onUntilDateChange">
        <text class="fk">结束日期</text>
        <text class="fv" :class="{ muted: !form.untilDate }">{{ form.untilDate || '选择日期 ›' }}</text>
      </picker>
      <view v-if="form.endCondition === 'COUNT'" class="frow">
        <text class="fk">共</text>
        <view class="fv row">
          <input class="count-input" v-model="form.countN" type="number" placeholder="次数" placeholder-class="ph" />
          <text>次</text>
        </view>
      </view>

      <!-- 入账方式 -->
      <view class="sec-hd">入账方式</view>
      <view class="seg">
        <text
          v-for="opt in POST_MODE_OPTIONS"
          :key="opt.value"
          class="s"
          :class="{ on: form.postMode === opt.value }"
          @click="setPostMode(opt.value)"
        >{{ opt.label }}</text>
      </view>
      <view class="pm-hint">{{ postModeHint }}</view>

      <!-- 摘要预览 -->
      <view class="preview">
        <text class="pv-hd">规则预览</text>
        <text class="pv-sum">{{ summaryText }}</text>
        <text v-if="endText" class="pv-end">{{ endText }}</text>
      </view>

      <view class="pad"></view>
    </scroll-view>

    <!-- 保存 -->
    <view v-if="loadState === 'ready'" class="footer">
      <text class="save" :class="{ busy: submitting }" @click="submit">{{ submitting ? '保存中…' : '保存' }}</text>
    </view>

    <!-- 账户选择 -->
    <view v-if="accountSheet" class="mask" @click="accountSheet = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">选择账户</text>
        <view v-if="!accounts.length" class="empty">
          还没有可用账户，
          <text class="link" @click="uni.switchTab({ url: '/pages/accounts/accounts' })">去创建</text>
        </view>
        <scroll-view v-else scroll-y class="slist" :show-scrollbar="false">
          <view v-for="a in accounts" :key="a.id" class="sitem" @click="pickAccount(a)">
            <AccountBadge :account="a" :size="60" />
            <view class="si-name">
              <text class="si-nm">{{ accountDisplayName(a) }}</text>
              <text class="si-type">{{ accountTypeLabel(a.type) }}</text>
            </view>
            <text v-if="a.canSeeBalance !== false" class="si-bal" :class="{ neg: Number(a.currentBalance) < 0 }">¥{{ formatAmount(a.currentBalance) }}</text>
            <text v-if="form.accountId === a.id" class="tick">✓</text>
          </view>
        </scroll-view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; background: #f5f6f8; display: flex; flex-direction: column; }
.statusbar { width: 100%; }
.rnav { display: flex; align-items: center; height: 88rpx; padding: 0 12rpx; }
.nb { width: 88rpx; text-align: center; }
.back { font-size: 52rpx; color: #1f2329; }
.title { flex: 1; text-align: center; font-size: 32rpx; font-weight: 600; color: #1f2329; }
.spacer { width: 88rpx; }

.hint { text-align: center; color: #8a94a6; font-size: 26rpx; padding: 60rpx 0; display: flex; flex-direction: column; align-items: center; gap: 16rpx; }
.retry { color: #12a150; border: 1rpx solid #12a150; border-radius: 999rpx; padding: 8rpx 32rpx; }

.main { flex: 1; padding: 0 24rpx; }

/* 分段控件 */
.seg { display: flex; background: #eceef1; border-radius: 14rpx; padding: 6rpx; margin-top: 16rpx; }
.seg.wrap { flex-wrap: wrap; gap: 6rpx; }
.seg.sub { margin-top: 12rpx; }
.seg .s { flex: 1; text-align: center; padding: 16rpx 0; font-size: 28rpx; color: #5b6470; border-radius: 10rpx; }
.seg.wrap .s { flex: 1 1 30%; }
.seg .s.on { background: #fff; color: #12a150; font-weight: 600; }

/* 金额 */
.amtbox { display: flex; align-items: baseline; justify-content: center; gap: 6rpx; padding: 24rpx 0 12rpx; }
.cny { font-size: 40rpx; color: #1f2329; font-weight: 600; }
.amt { font-size: 72rpx; font-weight: 700; color: #1f2329; min-width: 200rpx; text-align: center; font-variant-numeric: tabular-nums; }
.amt-ph { color: #c8ccd2; }

.sec-hd { font-size: 24rpx; color: #8a94a6; margin: 20rpx 4rpx 8rpx; }

/* 分类九宫格 */
.cgrid { display: flex; flex-wrap: wrap; background: #fff; border-radius: 16rpx; padding: 12rpx 8rpx; }
.cat { width: 20%; display: flex; flex-direction: column; align-items: center; padding: 12rpx 0; }
.cic { position: relative; }
.subdot { position: absolute; right: -8rpx; bottom: -2rpx; font-size: 20rpx; color: #8a94a6; }
.cl { font-size: 22rpx; color: #5b6470; margin-top: 8rpx; }
.cl.on { color: #12a150; font-weight: 600; }
.subwrap { display: flex; flex-wrap: wrap; background: #f0f2f5; border-radius: 16rpx; padding: 8rpx; margin-top: 8rpx; }
.subwrap .cat { width: 20%; }
.empty { background: #fff; border-radius: 16rpx; padding: 32rpx; text-align: center; color: #8a94a6; font-size: 26rpx; }
.link { color: #12a150; }

/* 表单行 */
.frow { display: flex; align-items: center; justify-content: space-between; background: #fff; border-radius: 16rpx; padding: 24rpx; margin-top: 16rpx; min-height: 96rpx; box-sizing: border-box; }
.frow.flex1 { flex: 1; }
.fk { font-size: 28rpx; color: #1f2329; }
.fv { font-size: 28rpx; color: #5b6470; display: flex; align-items: center; }
.fv.row { gap: 12rpx; }
.fv-input { flex: 1; text-align: right; font-size: 28rpx; color: #1f2329; }
.count-input { width: 160rpx; text-align: right; font-size: 28rpx; color: #1f2329; }
.ph { color: #c8ccd2; }
.muted { color: #c8ccd2; }
.clear { font-size: 24rpx; color: #8a94a6; }

/* 每周星期几 */
.week-row { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 12rpx; }
.wchip { flex: 1 1 12%; text-align: center; padding: 18rpx 0; border-radius: 12rpx; background: #fff; color: #5b6470; font-size: 26rpx; }
.wchip.on { background: #e7f7ee; color: #12a150; font-weight: 700; border: 1rpx solid #12a150; }

.year-row { display: flex; gap: 16rpx; }

/* 预览 */
.preview { background: #f7f9fb; border: 1rpx solid #e9edf2; border-radius: 16rpx; padding: 20rpx 24rpx; margin-top: 24rpx; display: flex; flex-direction: column; gap: 8rpx; }
.pv-hd { font-size: 24rpx; color: #8a94a6; }
.pv-sum { font-size: 30rpx; color: #1f2329; font-weight: 600; }
.pv-end { font-size: 24rpx; color: #8a94a6; }

.pm-hint { font-size: 22rpx; color: #8a94a6; margin: 10rpx 4rpx 0; line-height: 1.5; }

.pad { height: 40rpx; }

.footer { padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom)); background: #fff; border-top: 1rpx solid #f0f2f5; }
.save { display: block; text-align: center; background: #12a150; color: #fff; border-radius: 44rpx; padding: 26rpx 0; font-size: 30rpx; font-weight: 600; }
.save.busy { opacity: 0.6; }

/* 底部弹层 */
.mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); z-index: 50; display: flex; align-items: flex-end; }
.sheet { width: 100%; background: #fff; border-radius: 24rpx 24rpx 0 0; padding: 24rpx 24rpx calc(24rpx + env(safe-area-inset-bottom)); max-height: 76vh; display: flex; flex-direction: column; }
.sheet-title { font-size: 30rpx; font-weight: 600; color: #1f2329; padding: 8rpx 0 16rpx; }
.slist { max-height: 56vh; }
.sitem { display: flex; align-items: center; gap: 16rpx; padding: 20rpx 8rpx; border-bottom: 1rpx solid #f0f2f5; }
.si-name { flex: 1; display: flex; flex-direction: column; }
.si-nm { font-size: 28rpx; color: #1f2329; }
.si-type { font-size: 22rpx; color: #8a94a6; }
.si-bal { font-size: 26rpx; color: #1f2329; font-variant-numeric: tabular-nums; }
.si-bal.neg { color: #e5533d; }
.tick { color: #12a150; font-size: 30rpx; margin-left: 12rpx; }
</style>
