<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  fetchRecurringRules,
  pauseRecurringRule,
  resumeRecurringRule,
  deleteRecurringRule
} from '../../api/recurring'
import { listCategories, buildCategoryLabelMap } from '../../api/category'
import { listAccounts, accountDisplayName } from '../../api/account'
import { formatAmount, categoryEmoji } from '../../utils/format'
import {
  frequencyLabel,
  directionLabel,
  endConditionLabel,
  statusLabel
} from '../../utils/recurring'
import { useAuthStore } from '../../stores/auth'

/**
 * 周期规则列表页（需求 6.1、6.2、6.5）。
 *
 * 列出当前账本当前用户的 ACTIVE / PAUSED 规则，每条展示频率摘要（如「每月 5 日 · 支出 ¥3,000.00」），
 * 并提供暂停 / 恢复 / 编辑 / 删除入口。规则按当前账本隔离（api/recurring.js 自动带 X-Ledger-Id）。
 *
 * 数据加载对齐提醒页 / 借贷页：onShow 拉取，listState 只在 ready 才渲染规则；分类 / 账户名映射用于
 * 展示但失败不阻断规则列表。暂停 / 恢复 / 删除均先本地就地更新再提示，删除前 uni.showModal 二次确认。
 * 编辑 / 新建跳转 pages/recurringedit/recurringedit。
 */

const auth = useAuthStore()

const guest = ref(false)
const listState = ref('loading') // loading | ready | error
const rules = ref([])
let listSeq = 0
let listInFlight = false

// 分类 / 账户名映射（展示用；加载失败不影响规则列表渲染）。
const categoryLabels = ref({})
const accountMap = ref({})

async function loadMeta() {
  try {
    const [tree, accounts] = await Promise.all([listCategories(), listAccounts()])
    categoryLabels.value = buildCategoryLabelMap(tree)
    const map = {}
    for (const a of accounts || []) map[a.id] = a
    accountMap.value = map
  } catch (e) {
    // 名称映射是锦上添花：失败就退化为只展示频率摘要，不切错误态。
  }
}

async function loadRules() {
  if (listInFlight) return
  const s = ++listSeq
  listInFlight = true
  listState.value = 'loading'
  try {
    const res = await fetchRecurringRules()
    if (s !== listSeq) return
    rules.value = Array.isArray(res) ? res.filter((r) => r && r.id != null) : []
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
  loadMeta()
  loadRules()
})

function retryList() {
  if (listInFlight) return
  loadRules()
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

// —— 展示辅助 ——

function categoryNameOf(rule) {
  return categoryLabels.value[rule.categoryId] || ''
}

function accountOf(rule) {
  return accountMap.value[rule.accountId] || null
}

function titleOf(rule) {
  return categoryNameOf(rule) || directionLabel(rule.type)
}

function emojiOf(rule) {
  return categoryEmoji(categoryNameOf(rule), rule.type)
}

const list = computed(() => rules.value)

// —— 生命周期操作 ——

function goCreate() {
  uni.navigateTo({ url: '/pages/recurringedit/recurringedit' })
}

function goEdit(rule) {
  uni.navigateTo({ url: `/pages/recurringedit/recurringedit?id=${rule.id}` })
}

/** 暂停 / 恢复（需求 6.1、6.2）：调接口后就地更新该项状态。 */
async function toggleStatus(rule) {
  const paused = rule.status === 'PAUSED'
  try {
    const res = paused ? await resumeRecurringRule(rule.id) : await pauseRecurringRule(rule.id)
    const idx = rules.value.findIndex((r) => r.id === rule.id)
    if (idx >= 0) {
      rules.value[idx] = { ...rules.value[idx], status: res?.status || (paused ? 'ACTIVE' : 'PAUSED') }
    }
    uni.showToast({ title: paused ? '已恢复' : '已暂停', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: (e && e.message) || '操作失败，请稍后重试', icon: 'none' })
  }
}

/** 删除规则（需求 6.5）：二次确认后调接口，成功就地移除。 */
function removeRule(rule) {
  uni.showModal({
    title: '删除周期规则',
    content: '删除后不再生成新的待确认项，已确认的历史流水不受影响。确定删除？',
    confirmColor: '#e5484d',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await deleteRecurringRule(rule.id)
        rules.value = rules.value.filter((x) => x.id !== rule.id)
        uni.showToast({ title: '已删除', icon: 'success' })
      } catch (e) {
        uni.showToast({ title: (e && e.message) || '删除失败，请稍后重试', icon: 'none' })
      }
    }
  })
}
</script>

<template>
  <view class="page">
    <!-- 未登录：只展示登录入口（对齐提醒页） -->
    <view v-if="guest" class="fail-card">
      <AppIcon name="calendar" :size="52" color="#12a150" />
      <text class="f-t">登录后管理周期记账</text>
      <text class="f-d">为房租、订阅、工资等固定收支建立周期规则</text>
      <text class="retry" @click="goLogin">去登录</text>
    </view>

    <template v-else>
      <!-- 加载中 -->
      <view v-if="listState === 'loading'" class="fail-card slim">
        <text class="f-d">正在加载周期规则…</text>
      </view>

      <!-- 出错 + 重试 -->
      <view v-else-if="listState === 'error'" class="fail-card slim">
        <AppIcon name="warning" :size="52" color="#c7ccd2" />
        <text class="f-t">规则没能加载出来</text>
        <text class="f-d">网络不太顺畅，稍后再试一次</text>
        <text class="retry" @click="retryList">重试</text>
      </view>

      <!-- 就绪 -->
      <template v-else>
        <view class="sect">我的周期规则</view>

        <!-- 空列表 -->
        <view v-if="list.length === 0" class="fail-card slim">
          <AppIcon name="calendar" :size="52" color="#c7ccd2" />
          <text class="f-t">还没有周期规则</text>
          <text class="f-d">添加一条，固定的账不用每次从零填</text>
        </view>

        <view
          v-for="rule in list"
          v-else
          :key="rule.id"
          class="card"
          :class="{ paused: rule.status === 'PAUSED' }"
        >
          <view class="r-top">
            <AccountBadge v-if="accountOf(rule)" :account="accountOf(rule)" :size="72" />
            <view v-else class="r-emoji">{{ emojiOf(rule) }}</view>
            <view class="r-main">
              <view class="r-title-row">
                <text class="r-title">{{ titleOf(rule) }}</text>
                <text class="r-status" :class="{ paused: rule.status === 'PAUSED' }">
                  {{ statusLabel(rule.status) }}
                </text>
              </view>
              <text class="r-sum">{{ frequencyLabel(rule) }}</text>
              <text v-if="endConditionLabel(rule)" class="r-end">{{ endConditionLabel(rule) }}</text>
            </view>
            <text class="r-amt" :class="rule.type">
              {{ directionLabel(rule.type) }} ¥{{ formatAmount(rule.amount) }}
            </text>
          </view>

          <view class="r-actions">
            <text class="act" @click="toggleStatus(rule)">
              {{ rule.status === 'PAUSED' ? '恢复' : '暂停' }}
            </text>
            <text class="act" @click="goEdit(rule)">编辑</text>
            <text class="act danger" @click="removeRule(rule)">删除</text>
          </view>
        </view>

        <view class="add-btn" @click="goCreate">＋ 新建周期规则</view>
      </template>
    </template>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f2f4f6;
  padding: 24rpx 24rpx 40rpx;
}
/* 失败 / 加载 / 空 / 登录引导卡（复用提醒页观感） */
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
.sect {
  font-size: 24rpx;
  color: #9aa2ad;
  padding: 30rpx 8rpx 12rpx;
}
/* 规则卡 */
.card {
  background: #fff;
  border-radius: 20rpx;
  padding: 26rpx 28rpx 8rpx;
  margin-bottom: 20rpx;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
}
.card.paused {
  opacity: 0.72;
}
.r-top {
  display: flex;
  align-items: flex-start;
  gap: 20rpx;
}
.r-emoji {
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
.r-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.r-title-row {
  display: flex;
  align-items: center;
  gap: 14rpx;
}
.r-title {
  font-size: 30rpx;
  font-weight: 700;
  color: #25292e;
}
.r-status {
  font-size: 20rpx;
  color: #12a150;
  background: #e7f7ee;
  border-radius: 999rpx;
  padding: 2rpx 14rpx;
}
.r-status.paused {
  color: #9aa2ad;
  background: #eef0f2;
}
.r-sum {
  font-size: 26rpx;
  color: #5b6470;
}
.r-end {
  font-size: 22rpx;
  color: #9aa2ad;
}
.r-amt {
  font-size: 30rpx;
  font-weight: 700;
  flex: 0 0 auto;
  font-variant-numeric: tabular-nums;
}
.r-amt.expense {
  color: #25292e;
}
.r-amt.income {
  color: #12a150;
}
.r-actions {
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
  padding: 12rpx 28rpx;
}
.act.danger {
  color: #e5484d;
}
.add-btn {
  margin-top: 8rpx;
  background: #12a150;
  color: #fff;
  border-radius: 18rpx;
  text-align: center;
  padding: 28rpx;
  font-size: 30rpx;
  font-weight: 700;
  box-shadow: 0 8rpx 22rpx rgba(18, 161, 80, 0.22);
}
</style>
