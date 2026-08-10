<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  fetchRecurringPendingItems,
  confirmRecurringPendingItem,
  skipRecurringPendingItem,
  batchConfirmRecurringPendingItems,
  batchSkipRecurringPendingItems
} from '../../api/recurring'
import { listCategories, buildCategoryLabelMap } from '../../api/category'
import { listSelectableAccounts, accountDisplayName } from '../../api/account'
import { formatAmount, categoryEmoji } from '../../utils/format'
import { safeBack } from '../../utils/nav'
import { directionLabel } from '../../utils/recurring'
import {
  parseRuleAmount,
  groupPendingItemsByDate,
  pendingItemErrorLabel,
  batchResultSummary,
  describeBatchFailures,
  buildConfirmOverrides
} from '../../utils/recurring'
import { useAuthStore } from '../../stores/auth'

/**
 * 待确认项列表页（任务 9.4，需求 4.1、4.3、4.4、5.1、5.3、5.4、5.5、5.6）。
 *
 * 后端 GET /recurring/pending-items 先触发懒生成再返回当前账本 PENDING 列表（已按到期日升序、可复现）。
 * 本页按到期日升序分组呈现，支持：单条确认 / 修改后确认（confirm sheet 可改金额 / 分类 / 账户 / 备注 / 记账日期）/
 * 跳过；多选批量确认 / 批量跳过，批量结果按逐条成功 / 失败反馈（成功移除、失败就地标注原因 + 结果弹层）。
 *
 * 分组 / 排序、批量摘要、错误码→中文、修改后确认的 overrides 构造均下沉到 utils/recurring.js 纯函数（可单测）；
 * 本页只做数据加载、选择器交互与提交编排。分类图标复用 CategoryIcon、账户复用 AccountBadge、金额 tabular-nums。
 */

const auth = useAuthStore()

const guest = ref(false)
const listState = ref('loading') // loading | ready | error
const items = ref([])
let listSeq = 0
let listInFlight = false

// 展示 / 修改用元数据：分类树（confirm sheet 选择）、分类名映射、可选账户。
const categoryTree = ref({ expense: [], income: [] })
const categoryLabels = ref({})
const accounts = ref([])
const accountMap = ref({})

// 批量选择态。
const selecting = ref(false)
const selectedIds = ref([])

// 逐条失败标注：{ [itemId]: errorCode }。
const failFlags = ref({})

// 批量结果弹层。
const batchResult = ref(null) // { title, summary, failures:[{itemId,message,label}] }

// 修改后确认弹层。
const sheetOpen = ref(false)
const sheetItem = ref(null)
const sheetForm = ref({
  amountText: '',
  categoryId: null,
  accountId: null,
  note: '',
  occurredDate: ''
})
const sheetSubmitting = ref(false)

async function loadMeta() {
  try {
    const [tree, accs] = await Promise.all([listCategories(), listSelectableAccounts()])
    categoryTree.value = tree || { expense: [], income: [] }
    categoryLabels.value = buildCategoryLabelMap(tree)
    accounts.value = Array.isArray(accs) ? accs : []
    const map = {}
    for (const a of accounts.value) map[a.id] = a
    accountMap.value = map
  } catch (e) {
    // 名称 / 选择元数据是锦上添花：失败不阻断待确认项列表渲染。
  }
}

async function loadItems() {
  if (listInFlight) return
  const s = ++listSeq
  listInFlight = true
  listState.value = 'loading'
  try {
    const res = await fetchRecurringPendingItems()
    if (s !== listSeq) return
    items.value = Array.isArray(res) ? res.filter((it) => it && it.id != null) : []
    listState.value = 'ready'
  } catch (e) {
    if (s !== listSeq) return
    listState.value = 'error'
  } finally {
    if (s === listSeq) listInFlight = false
  }
}

onShow(() => {
  if (!auth.isLoggedIn) {
    guest.value = true
    return
  }
  guest.value = false
  exitSelect()
  loadMeta()
  loadItems()
})

function retryList() {
  if (listInFlight) return
  loadItems()
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

function goBack() {
  safeBack('/pages/index/index')
}

// —— 分组 / 展示辅助 ——

const groups = computed(() => groupPendingItemsByDate(items.value))

function categoryNameOf(item) {
  return categoryLabels.value[item.categoryId] || ''
}
function accountOf(item) {
  return accountMap.value[item.accountId] || null
}
function titleOf(item) {
  return categoryNameOf(item) || directionLabel(item.type)
}
function emojiOf(item) {
  return categoryEmoji(categoryNameOf(item), item.type)
}
function failLabelOf(item) {
  const code = failFlags.value[item.id]
  return code ? pendingItemErrorLabel(code) : ''
}

function removeItem(id) {
  items.value = items.value.filter((it) => it.id !== id)
}

function annotateFail(id, code) {
  failFlags.value = { ...failFlags.value, [id]: code }
}

/** 单条操作错误统一处理：已处理→移除并提示；目标缺失→提示并打开修改弹层；其余→toast。 */
function handleItemError(item, e) {
  const code = e && e.code
  if (code === 'RECURRING_ITEM_ALREADY_PROCESSED' || code === 'NOT_FOUND') {
    removeItem(item.id)
    uni.showToast({ title: pendingItemErrorLabel(code), icon: 'none' })
  } else if (code === 'RECURRING_ITEM_TARGET_MISSING') {
    annotateFail(item.id, code)
    uni.showToast({ title: pendingItemErrorLabel(code), icon: 'none' })
    openSheet(item)
  } else {
    uni.showToast({ title: (e && e.message) || pendingItemErrorLabel(code), icon: 'none' })
  }
}

// —— 单条确认 / 跳过 ——

const acting = ref({}) // { [id]: true } 防重复点击

function isActing(id) {
  return !!acting.value[id]
}
function setActing(id, v) {
  acting.value = { ...acting.value, [id]: v }
}

async function confirmOne(item) {
  if (isActing(item.id)) return
  setActing(item.id, true)
  try {
    await confirmRecurringPendingItem(item.id)
    removeItem(item.id)
    uni.showToast({ title: '已入账', icon: 'success' })
  } catch (e) {
    handleItemError(item, e)
  } finally {
    setActing(item.id, false)
  }
}

function skipOne(item) {
  uni.showModal({
    title: '跳过本期',
    content: '跳过后本期不再入账，也不影响其它期次。确定跳过？',
    success: async (r) => {
      if (!r.confirm || isActing(item.id)) return
      setActing(item.id, true)
      try {
        await skipRecurringPendingItem(item.id)
        removeItem(item.id)
        uni.showToast({ title: '已跳过', icon: 'success' })
      } catch (e) {
        handleItemError(item, e)
      } finally {
        setActing(item.id, false)
      }
    }
  })
}

// —— 修改后确认弹层 ——

const sheetType = computed(() => (sheetItem.value ? sheetItem.value.type : 'expense'))

// 当前类型下的分类扁平选项（父 + 子），供 confirm sheet 选择。
const sheetCategories = computed(() => {
  const kind = sheetType.value === 'income' ? 'income' : 'expense'
  const parents = Array.isArray(categoryTree.value[kind]) ? categoryTree.value[kind] : []
  const flat = []
  for (const p of parents) {
    flat.push({ id: p.id, name: p.name, icon: p.icon, color: p.iconColor })
    for (const c of p.children || []) {
      flat.push({ id: c.id, name: c.name, icon: c.icon, color: c.iconColor })
    }
  }
  return flat
})

function openSheet(item) {
  sheetItem.value = item
  sheetForm.value = {
    amountText: item.amount != null ? String(item.amount) : '',
    categoryId: item.categoryId != null ? item.categoryId : null,
    accountId: item.accountId != null ? item.accountId : null,
    note: item.note || '',
    occurredDate: item.occurrenceDate ? String(item.occurrenceDate).slice(0, 10) : ''
  }
  sheetOpen.value = true
}

function closeSheet() {
  sheetOpen.value = false
  sheetItem.value = null
}

function pickSheetCategory(c) {
  sheetForm.value.categoryId = c.id
}
function pickSheetAccount(a) {
  sheetForm.value.accountId = a.id
}
function onSheetDateChange(e) {
  sheetForm.value.occurredDate = e.detail.value
}

async function submitSheet() {
  if (sheetSubmitting.value || !sheetItem.value) return
  const amt = parseRuleAmount(sheetForm.value.amountText)
  if (amt == null) {
    uni.showToast({ title: '请输入 0.01–999999999.99 之间、至多两位小数的金额', icon: 'none' })
    return
  }
  if (sheetForm.value.categoryId == null) {
    uni.showToast({ title: '请选择分类', icon: 'none' })
    return
  }
  if (sheetForm.value.accountId == null) {
    uni.showToast({ title: '请选择账户', icon: 'none' })
    return
  }
  const item = sheetItem.value
  const overrides = buildConfirmOverrides(sheetForm.value, item)
  sheetSubmitting.value = true
  try {
    await confirmRecurringPendingItem(item.id, overrides)
    removeItem(item.id)
    closeSheet()
    uni.showToast({ title: '已入账', icon: 'success' })
  } catch (e) {
    const code = e && e.code
    uni.showToast({ title: (e && e.message) || pendingItemErrorLabel(code), icon: 'none' })
  } finally {
    sheetSubmitting.value = false
  }
}

// —— 批量选择 ——

function enterSelect() {
  selecting.value = true
  selectedIds.value = []
}
function exitSelect() {
  selecting.value = false
  selectedIds.value = []
}
function isSelected(id) {
  return selectedIds.value.includes(id)
}
function toggleSelect(id) {
  const i = selectedIds.value.indexOf(id)
  if (i >= 0) selectedIds.value.splice(i, 1)
  else selectedIds.value.push(id)
}
const allSelected = computed(
  () => items.value.length > 0 && selectedIds.value.length === items.value.length
)
function toggleSelectAll() {
  if (allSelected.value) selectedIds.value = []
  else selectedIds.value = items.value.map((it) => it.id)
}

function showBatchResult(res, actionLabel) {
  const succeeded = Array.isArray(res.succeededIds) ? res.succeededIds : []
  const failures = describeBatchFailures(res)
  // 成功条从列表移除；失败条就地标注原因。
  const succeededSet = new Set(succeeded)
  items.value = items.value.filter((it) => !succeededSet.has(it.id))
  const flags = { ...failFlags.value }
  for (const f of failures) if (f.itemId != null) flags[f.itemId] = f.errorCode
  failFlags.value = flags
  // 结果弹层含逐条失败明细（需求 5.6）。
  const detailed = failures.map((f) => {
    const item = items.value.find((it) => it.id === f.itemId)
    return {
      itemId: f.itemId,
      title: item ? titleOf(item) : `#${f.itemId}`,
      message: f.message
    }
  })
  batchResult.value = {
    title: `${actionLabel}结果`,
    summary: batchResultSummary(res),
    failures: detailed
  }
  uni.showToast({ title: batchResultSummary(res), icon: 'none' })
  exitSelect()
}

const batching = ref(false)

async function batchConfirm() {
  if (batching.value) return
  const ids = [...selectedIds.value]
  if (!ids.length) {
    uni.showToast({ title: '请先选择待确认项', icon: 'none' })
    return
  }
  batching.value = true
  try {
    const res = await batchConfirmRecurringPendingItems(ids)
    showBatchResult(res, '批量确认')
  } catch (e) {
    uni.showToast({ title: (e && e.message) || '批量确认失败，请稍后重试', icon: 'none' })
  } finally {
    batching.value = false
  }
}

function batchSkip() {
  const ids = [...selectedIds.value]
  if (!ids.length) {
    uni.showToast({ title: '请先选择待确认项', icon: 'none' })
    return
  }
  uni.showModal({
    title: '批量跳过',
    content: `将跳过所选 ${ids.length} 条待确认项，本期不再入账。确定？`,
    success: async (r) => {
      if (!r.confirm || batching.value) return
      batching.value = true
      try {
        const res = await batchSkipRecurringPendingItems(ids)
        showBatchResult(res, '批量跳过')
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '批量跳过失败，请稍后重试', icon: 'none' })
      } finally {
        batching.value = false
      }
    }
  })
}

function closeBatchResult() {
  batchResult.value = null
}
</script>

<template>
  <view class="page">
    <!-- 未登录 -->
    <view v-if="guest" class="fail-card">
      <AppIcon name="calendar" :size="52" color="#12a150" />
      <text class="f-t">登录后查看待确认周期记账</text>
      <text class="f-d">周期规则到期会生成待确认项，确认后才入账</text>
      <text class="retry" @click="goLogin">去登录</text>
    </view>

    <template v-else>
      <!-- 加载中 -->
      <view v-if="listState === 'loading'" class="fail-card slim">
        <text class="f-d">正在加载待确认项…</text>
      </view>

      <!-- 出错 -->
      <view v-else-if="listState === 'error'" class="fail-card slim">
        <AppIcon name="warning" :size="52" color="#c7ccd2" />
        <text class="f-t">待确认项没能加载出来</text>
        <text class="f-d">网络不太顺畅，稍后再试一次</text>
        <text class="retry" @click="retryList">重试</text>
      </view>

      <!-- 就绪 -->
      <template v-else>
        <view class="topbar">
          <text class="tb-title">待确认记账</text>
          <text v-if="items.length" class="tb-action" @click="selecting ? exitSelect() : enterSelect()">
            {{ selecting ? '取消' : '多选' }}
          </text>
        </view>

        <!-- 空 -->
        <view v-if="items.length === 0" class="fail-card slim">
          <AppIcon name="calendar" :size="52" color="#c7ccd2" />
          <text class="f-t">没有待确认的周期记账</text>
          <text class="f-d">规则到期后会在这里生成待确认项</text>
        </view>

        <template v-else>
          <!-- 全选行（多选态） -->
          <view v-if="selecting" class="selall" @click="toggleSelectAll">
            <text class="cb" :class="{ on: allSelected }">{{ allSelected ? '✓' : '' }}</text>
            <text class="selall-t">全选（已选 {{ selectedIds.length }} / {{ items.length }}）</text>
          </view>

          <view v-for="g in groups" :key="g.date || 'nodate'" class="group">
            <view class="g-date">{{ g.date || '未标注日期' }}</view>
            <view
              v-for="item in g.items"
              :key="item.id"
              class="card"
              :class="{ selectable: selecting }"
              @click="selecting ? toggleSelect(item.id) : null"
            >
              <view class="c-top">
                <text v-if="selecting" class="cb" :class="{ on: isSelected(item.id) }">
                  {{ isSelected(item.id) ? '✓' : '' }}
                </text>
                <AccountBadge v-if="accountOf(item)" :account="accountOf(item)" :size="72" />
                <view v-else class="c-emoji">{{ emojiOf(item) }}</view>
                <view class="c-main">
                  <text class="c-title">{{ titleOf(item) }}</text>
                  <text v-if="accountOf(item)" class="c-sub">{{ accountDisplayName(accountOf(item)) }}</text>
                  <text v-if="item.note" class="c-note">{{ item.note }}</text>
                  <text v-if="failLabelOf(item)" class="c-fail">{{ failLabelOf(item) }}</text>
                </view>
                <text class="c-amt" :class="item.type">
                  {{ item.type === 'income' ? '+' : '-' }}¥{{ formatAmount(item.amount) }}
                </text>
              </view>

              <view v-if="!selecting" class="c-actions">
                <text class="act" @click.stop="skipOne(item)">跳过</text>
                <text class="act" @click.stop="openSheet(item)">修改</text>
                <text class="act primary" :class="{ busy: isActing(item.id) }" @click.stop="confirmOne(item)">确认</text>
              </view>
            </view>
          </view>
        </template>
      </template>
    </template>

    <!-- 批量操作栏 -->
    <view v-if="!guest && selecting && items.length" class="batchbar">
      <text class="bb skip" :class="{ busy: batching }" @click="batchSkip">批量跳过</text>
      <text class="bb confirm" :class="{ busy: batching }" @click="batchConfirm">批量确认</text>
    </view>

    <!-- 修改后确认弹层 -->
    <view v-if="sheetOpen" class="mask" @click="closeSheet">
      <view class="sheet" @click.stop>
        <text class="sheet-title">修改后确认</text>
        <scroll-view scroll-y class="sheet-body" :show-scrollbar="false">
          <!-- 金额 -->
          <view class="s-row">
            <text class="s-k">金额</text>
            <view class="s-amt">
              <text class="cny">¥</text>
              <input class="amt-input" v-model="sheetForm.amountText" type="digit" placeholder="0.00" placeholder-class="ph" />
            </view>
          </view>

          <!-- 分类 -->
          <text class="s-hd">分类</text>
          <view v-if="!sheetCategories.length" class="s-empty">暂无可选分类</view>
          <scroll-view v-else scroll-x class="chiprow" :show-scrollbar="false">
            <view
              v-for="c in sheetCategories"
              :key="c.id"
              class="chip"
              :class="{ on: sheetForm.categoryId === c.id }"
              @click="pickSheetCategory(c)"
            >
              <CategoryIcon :icon="c.icon" :name="c.name" :kind="sheetType" :color="c.color" :size="40" :round="true" />
              <text class="chip-t">{{ c.name }}</text>
            </view>
          </scroll-view>

          <!-- 账户 -->
          <text class="s-hd">账户</text>
          <view v-if="!accounts.length" class="s-empty">暂无可选账户</view>
          <scroll-view v-else scroll-x class="chiprow" :show-scrollbar="false">
            <view
              v-for="a in accounts"
              :key="a.id"
              class="chip"
              :class="{ on: sheetForm.accountId === a.id }"
              @click="pickSheetAccount(a)"
            >
              <AccountBadge :account="a" :size="44" />
              <text class="chip-t">{{ accountDisplayName(a) }}</text>
            </view>
          </scroll-view>

          <!-- 备注 -->
          <view class="s-row">
            <text class="s-k">备注</text>
            <input class="s-input" v-model="sheetForm.note" placeholder="添加备注" placeholder-class="ph" :maxlength="200" />
          </view>

          <!-- 记账日期 -->
          <picker class="s-row picker" mode="date" :value="sheetForm.occurredDate" @change="onSheetDateChange">
            <text class="s-k">记账日期</text>
            <text class="s-v" :class="{ muted: !sheetForm.occurredDate }">{{ sheetForm.occurredDate || '选择日期 ›' }}</text>
          </picker>
        </scroll-view>

        <view class="sheet-foot">
          <text class="s-cancel" @click="closeSheet">取消</text>
          <text class="s-save" :class="{ busy: sheetSubmitting }" @click="submitSheet">
            {{ sheetSubmitting ? '确认中…' : '确认入账' }}
          </text>
        </view>
      </view>
    </view>

    <!-- 批量结果弹层 -->
    <view v-if="batchResult" class="mask" @click="closeBatchResult">
      <view class="result" @click.stop>
        <text class="rs-title">{{ batchResult.title }}</text>
        <text class="rs-sum">{{ batchResult.summary }}</text>
        <scroll-view v-if="batchResult.failures.length" scroll-y class="rs-list" :show-scrollbar="false">
          <view v-for="f in batchResult.failures" :key="f.itemId" class="rs-item">
            <text class="rs-nm">{{ f.title }}</text>
            <text class="rs-reason">{{ f.message }}</text>
          </view>
        </scroll-view>
        <text class="rs-ok" @click="closeBatchResult">知道了</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f2f4f6;
  padding: 24rpx 24rpx 160rpx;
}
/* 失败 / 加载 / 空 / 登录引导卡 */
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
.fail-card.slim {
  padding: 44rpx 30rpx;
  margin-top: 24rpx;
}
.fail-card .f-t {
  font-size: 30rpx;
  font-weight: 700;
  color: #25292e;
}
.fail-card .f-d {
  font-size: 24rpx;
  color: #9aa2ad;
  line-height: 1.6;
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

.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12rpx 8rpx 4rpx;
}
.tb-title {
  font-size: 32rpx;
  font-weight: 700;
  color: #25292e;
}
.tb-action {
  font-size: 26rpx;
  color: #12a150;
  font-weight: 600;
  padding: 8rpx 20rpx;
}

.selall {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 20rpx 24rpx;
  background: #fff;
  border-radius: 16rpx;
  margin: 12rpx 0;
}
.selall-t {
  font-size: 26rpx;
  color: #5b6470;
}
.cb {
  width: 40rpx;
  height: 40rpx;
  border-radius: 999rpx;
  border: 2rpx solid #c7ccd2;
  color: #fff;
  font-size: 26rpx;
  text-align: center;
  line-height: 40rpx;
  flex: 0 0 auto;
}
.cb.on {
  background: #12a150;
  border-color: #12a150;
}

.group {
  margin-top: 16rpx;
}
.g-date {
  font-size: 24rpx;
  color: #9aa2ad;
  padding: 12rpx 8rpx 10rpx;
  font-variant-numeric: tabular-nums;
}
.card {
  background: #fff;
  border-radius: 20rpx;
  padding: 24rpx 26rpx 8rpx;
  margin-bottom: 16rpx;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
}
.card.selectable {
  padding-bottom: 24rpx;
}
.c-top {
  display: flex;
  align-items: flex-start;
  gap: 18rpx;
}
.c-emoji {
  width: 72rpx;
  height: 72rpx;
  border-radius: 20rpx;
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 36rpx;
  flex: 0 0 auto;
}
.c-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.c-title {
  font-size: 30rpx;
  font-weight: 700;
  color: #25292e;
}
.c-sub {
  font-size: 24rpx;
  color: #9aa2ad;
}
.c-note {
  font-size: 24rpx;
  color: #5b6470;
}
.c-fail {
  font-size: 22rpx;
  color: #e5484d;
}
.c-amt {
  font-size: 32rpx;
  font-weight: 700;
  flex: 0 0 auto;
  font-variant-numeric: tabular-nums;
}
.c-amt.expense {
  color: #25292e;
}
.c-amt.income {
  color: #12a150;
}
.c-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8rpx;
  margin-top: 16rpx;
  padding-top: 12rpx;
  border-top: 1rpx solid #eef0f2;
}
.act {
  font-size: 26rpx;
  color: #4b5563;
  padding: 12rpx 30rpx;
  border-radius: 999rpx;
}
.act.primary {
  color: #fff;
  background: #12a150;
  font-weight: 600;
}
.act.busy {
  opacity: 0.6;
}

/* 批量操作栏 */
.batchbar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  gap: 16rpx;
  padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1rpx solid #eef0f2;
}
.bb {
  flex: 1;
  text-align: center;
  padding: 26rpx 0;
  border-radius: 44rpx;
  font-size: 30rpx;
  font-weight: 600;
}
.bb.skip {
  color: #4b5563;
  background: #eef0f2;
}
.bb.confirm {
  color: #fff;
  background: #12a150;
}
.bb.busy {
  opacity: 0.6;
}

/* 弹层通用 */
.mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  z-index: 50;
  display: flex;
  align-items: flex-end;
}
.sheet {
  width: 100%;
  background: #fff;
  border-radius: 24rpx 24rpx 0 0;
  padding: 24rpx 24rpx calc(20rpx + env(safe-area-inset-bottom));
  max-height: 82vh;
  display: flex;
  flex-direction: column;
}
.sheet-title {
  font-size: 30rpx;
  font-weight: 700;
  color: #1f2329;
  padding: 8rpx 0 12rpx;
}
.sheet-body {
  max-height: 60vh;
}
.s-hd {
  display: block;
  font-size: 24rpx;
  color: #8a94a6;
  margin: 20rpx 4rpx 10rpx;
}
.s-empty {
  background: #f4f5f7;
  border-radius: 12rpx;
  padding: 24rpx;
  text-align: center;
  color: #9aa2ad;
  font-size: 24rpx;
}
.s-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: #f7f8fa;
  border-radius: 14rpx;
  padding: 22rpx 24rpx;
  margin-top: 14rpx;
  min-height: 88rpx;
  box-sizing: border-box;
}
.s-row.picker {
  display: flex;
}
.s-k {
  font-size: 28rpx;
  color: #1f2329;
}
.s-v {
  font-size: 28rpx;
  color: #5b6470;
}
.s-v.muted {
  color: #c8ccd2;
}
.s-amt {
  display: flex;
  align-items: baseline;
  gap: 6rpx;
}
.cny {
  font-size: 30rpx;
  color: #1f2329;
  font-weight: 600;
}
.amt-input {
  font-size: 36rpx;
  font-weight: 700;
  color: #1f2329;
  min-width: 200rpx;
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.s-input {
  flex: 1;
  text-align: right;
  font-size: 28rpx;
  color: #1f2329;
}
.ph {
  color: #c8ccd2;
}
.chiprow {
  white-space: nowrap;
}
.chip {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
  width: 120rpx;
  padding: 12rpx 4rpx;
  margin-right: 8rpx;
  border-radius: 14rpx;
  vertical-align: top;
}
.chip.on {
  background: #e7f7ee;
  border: 1rpx solid #12a150;
}
.chip-t {
  font-size: 22rpx;
  color: #5b6470;
  max-width: 112rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sheet-foot {
  display: flex;
  gap: 16rpx;
  padding-top: 18rpx;
}
.s-cancel {
  flex: 0 0 30%;
  text-align: center;
  padding: 24rpx 0;
  border-radius: 44rpx;
  background: #eef0f2;
  color: #4b5563;
  font-size: 30rpx;
}
.s-save {
  flex: 1;
  text-align: center;
  padding: 24rpx 0;
  border-radius: 44rpx;
  background: #12a150;
  color: #fff;
  font-size: 30rpx;
  font-weight: 600;
}
.s-save.busy {
  opacity: 0.6;
}

/* 批量结果弹层 */
.result {
  width: 100%;
  background: #fff;
  border-radius: 24rpx 24rpx 0 0;
  padding: 32rpx 30rpx calc(24rpx + env(safe-area-inset-bottom));
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  max-height: 70vh;
}
.rs-title {
  font-size: 30rpx;
  font-weight: 700;
  color: #1f2329;
}
.rs-sum {
  font-size: 26rpx;
  color: #5b6470;
}
.rs-list {
  max-height: 40vh;
  margin-top: 8rpx;
}
.rs-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16rpx;
  padding: 18rpx 0;
  border-bottom: 1rpx solid #f0f2f5;
}
.rs-nm {
  font-size: 26rpx;
  color: #1f2329;
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.rs-reason {
  font-size: 22rpx;
  color: #e5484d;
  flex: 0 0 auto;
}
.rs-ok {
  margin-top: 16rpx;
  text-align: center;
  padding: 24rpx 0;
  border-radius: 44rpx;
  background: #12a150;
  color: #fff;
  font-size: 30rpx;
  font-weight: 600;
}
</style>
