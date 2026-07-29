<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  budgetOverview,
  setTotalBudget,
  setCategoryBudget,
  deleteCategoryBudget,
  copyPreviousBudget
} from '../../api/budget'
import { listCategories, flattenCategories } from '../../api/category'
import { formatAmount, currentMonth, monthLabel } from '../../utils/format'
import { shiftMonth } from '../../api/report'

const month = ref(currentMonth())
const ov = ref(null)
const loading = ref(false)
const expenseCats = ref([])

// 弹层：'total' 设总预算 | 'category' 设分类预算 | null
const sheet = ref(null)
const amountInput = ref('')
const catIndex = ref(0)
const editingCatId = ref(null)

function statusColor(status) {
  if (status === 'OVER') return '#f0553d'
  if (status === 'WARN') return '#f59e0b'
  return '#12a150'
}

async function load() {
  loading.value = true
  try {
    const [o, cats] = await Promise.all([budgetOverview(month.value), listCategories()])
    ov.value = o
    expenseCats.value = flattenCategories(cats.expense)
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

onShow(load)

function prevMonth() {
  month.value = shiftMonth(month.value, -1)
  load()
}
function nextMonth() {
  month.value = shiftMonth(month.value, 1)
  load()
}

// 已设分类预算的 id 集合，用于「添加分类预算」时过滤
const budgetedCatIds = computed(() => new Set((ov.value?.categories || []).map((c) => c.categoryId)))
const addableCats = computed(() => expenseCats.value.filter((c) => !budgetedCatIds.value.has(c.id)))

function openTotal() {
  amountInput.value = ov.value?.totalBudget != null ? String(ov.value.totalBudget) : ''
  sheet.value = 'total'
}
function openAddCategory() {
  if (!addableCats.value.length) {
    uni.showToast({ title: '没有可添加的分类', icon: 'none' })
    return
  }
  editingCatId.value = null
  catIndex.value = 0
  amountInput.value = ''
  sheet.value = 'category'
}
function openEditCategory(item) {
  editingCatId.value = item.categoryId
  amountInput.value = String(item.budget)
  sheet.value = 'categoryEdit'
}
function onCatChange(e) {
  catIndex.value = Number(e.detail.value)
}

async function submitSheet() {
  const amt = amountInput.value.trim()
  if (!amt || Number(amt) <= 0) {
    uni.showToast({ title: '请输入正确金额', icon: 'none' })
    return
  }
  try {
    if (sheet.value === 'total') {
      ov.value = await setTotalBudget(month.value, amt)
    } else if (sheet.value === 'category') {
      const cat = addableCats.value[catIndex.value]
      ov.value = await setCategoryBudget(month.value, cat.id, amt)
    } else if (sheet.value === 'categoryEdit') {
      ov.value = await setCategoryBudget(month.value, editingCatId.value, amt)
    }
    sheet.value = null
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  }
}

function removeCategory(item) {
  uni.showModal({
    title: '删除分类预算',
    content: `确定删除「${item.name}」的预算？`,
    success: async (r) => {
      if (!r.confirm) return
      try {
        ov.value = await deleteCategoryBudget(month.value, item.categoryId)
        await load()
      } catch (e) {
        uni.showToast({ title: e.message || '删除失败', icon: 'none' })
      }
    }
  })
}

async function copyPrev() {
  try {
    ov.value = await copyPreviousBudget(month.value)
    await load()
    uni.showToast({ title: '已沿用上月', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: e.message || '操作失败', icon: 'none' })
  }
}

function pct(v) {
  return Math.min(Number(v) || 0, 100)
}
</script>

<template>
  <view class="page">
    <view class="month-bar">
      <text class="nav" @click="prevMonth">‹</text>
      <text class="month">{{ monthLabel(month) }}</text>
      <text class="nav" @click="nextMonth">›</text>
    </view>

    <!-- 总预算卡 -->
    <view v-if="ov && ov.hasBudget" class="total-card">
      <text class="tc-label">本月剩余可用</text>
      <text class="tc-remain" :class="{ neg: Number(ov.remaining) < 0 }">¥{{ formatAmount(ov.remaining) }}</text>
      <view class="tc-bar-bg">
        <view class="tc-bar" :style="{ width: pct(ov.usedPercent) + '%' }"></view>
      </view>
      <view class="tc-foot">
        <text>已用 ¥{{ formatAmount(ov.spent) }} / {{ formatAmount(ov.totalBudget) }}</text>
        <text>{{ ov.usedPercent }}%</text>
      </view>
      <text class="tc-edit" @click="openTotal">编辑总预算</text>
    </view>

    <view v-else-if="ov" class="total-empty">
      <text class="te-title">还没有设置本月预算</text>
      <view class="te-actions">
        <button class="te-btn primary" @click="openTotal">设置总预算</button>
        <button class="te-btn" @click="copyPrev">沿用上月</button>
      </view>
    </view>

    <!-- 预算健康 -->
    <view v-if="ov && ov.hasBudget && ov.health" class="health">
      <view class="h-item">
        <text class="h-k">剩余天数</text>
        <text class="h-v">{{ ov.health.daysLeft }} 天</text>
      </view>
      <view class="h-item">
        <text class="h-k">日均可用</text>
        <text class="h-v">¥{{ formatAmount(ov.health.dailyAvailable) }}</text>
      </view>
      <view class="h-item">
        <text class="h-k">预计月底</text>
        <text class="h-v" :class="{ neg: ov.health.projectedOver }">
          ¥{{ formatAmount(ov.health.projectedBalance) }}
        </text>
      </view>
    </view>

    <!-- 分类预算 -->
    <view class="section-head">
      <text class="sh-title">分类预算</text>
      <text class="sh-add" @click="openAddCategory">＋ 添加</text>
    </view>

    <view v-if="ov && !ov.categories.length" class="empty">还没有分类预算</view>

    <view class="cat-list" v-if="ov && ov.categories.length">
      <view
        v-for="c in ov.categories"
        :key="c.categoryId"
        class="cat"
        @click="openEditCategory(c)"
        @longpress="removeCategory(c)"
      >
        <view class="cat-head">
          <text class="cat-name">{{ c.name }}</text>
          <text class="cat-amt" :style="{ color: statusColor(c.status) }">
            ¥{{ formatAmount(c.spent) }} / {{ formatAmount(c.budget) }}
          </text>
        </view>
        <view class="cat-bar-bg">
          <view class="cat-bar" :style="{ width: pct(c.usedPercent) + '%', background: statusColor(c.status) }"></view>
        </view>
        <view class="cat-foot">
          <text>{{ c.txCount }} 笔</text>
          <text :style="{ color: statusColor(c.status) }">{{ c.usedPercent }}%</text>
        </view>
      </view>
    </view>

    <text v-if="ov && ov.categories.length" class="hint">点击编辑 · 长按删除</text>

    <!-- 弹层 -->
    <view v-if="sheet" class="mask" @click="sheet = null">
      <view class="sheet" @click.stop>
        <text class="sheet-title">
          {{ sheet === 'total' ? '设置月度总预算' : sheet === 'category' ? '添加分类预算' : '编辑分类预算' }}
        </text>
        <picker
          v-if="sheet === 'category'"
          class="field picker"
          :range="addableCats"
          range-key="label"
          :value="catIndex"
          @change="onCatChange"
        >
          <text>分类：{{ addableCats[catIndex]?.label || '-' }}</text>
        </picker>
        <input v-model="amountInput" class="field" type="digit" placeholder="预算金额" />
        <button class="submit" @click="submitSheet">保存</button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
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
  color: #576b95;
  padding: 0 20rpx;
}
.month {
  font-size: 32rpx;
  font-weight: 700;
  color: #1f2937;
}

/* 总预算卡 */
.total-card {
  border-radius: 28rpx;
  padding: 36rpx;
  color: #fff;
  background: linear-gradient(150deg, #22c55e, #12a150 55%, #0b6b34);
  box-shadow: 0 20rpx 44rpx rgba(22, 163, 74, 0.26);
  margin-bottom: 24rpx;
}
.tc-label {
  font-size: 24rpx;
  opacity: 0.9;
}
.tc-remain {
  display: block;
  margin: 8rpx 0 24rpx;
  font-size: 64rpx;
  font-weight: 800;
}
.tc-remain.neg {
  color: #fee2e2;
}
.tc-bar-bg {
  height: 16rpx;
  background: rgba(255, 255, 255, 0.28);
  border-radius: 8rpx;
  overflow: hidden;
}
.tc-bar {
  height: 100%;
  background: #fff;
  border-radius: 8rpx;
}
.tc-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 16rpx;
  font-size: 24rpx;
  opacity: 0.95;
}
.tc-edit {
  display: block;
  margin-top: 20rpx;
  text-align: center;
  font-size: 26rpx;
  padding: 14rpx;
  background: rgba(255, 255, 255, 0.18);
  border-radius: 999rpx;
}

.total-empty {
  background: #fff;
  border-radius: 28rpx;
  padding: 48rpx 36rpx;
  margin-bottom: 24rpx;
  text-align: center;
}
.te-title {
  display: block;
  font-size: 30rpx;
  color: #6b7280;
  margin-bottom: 28rpx;
}
.te-actions {
  display: flex;
  gap: 20rpx;
}
.te-btn {
  flex: 1;
  font-size: 28rpx;
  border-radius: 44rpx;
  background: #f0f2f5;
  color: #4b5563;
}
.te-btn.primary {
  background: #12a150;
  color: #fff;
}

/* 预算健康 */
.health {
  display: flex;
  background: #fff;
  border-radius: 24rpx;
  padding: 28rpx 0;
  margin-bottom: 24rpx;
}
.h-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
}
.h-k {
  font-size: 22rpx;
  color: #9ca3af;
}
.h-v {
  font-size: 28rpx;
  font-weight: 700;
  color: #1f2937;
}
.h-v.neg {
  color: #f0553d;
}

.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 8rpx 8rpx 16rpx;
}
.sh-title {
  font-size: 32rpx;
  font-weight: 700;
  color: #1f2937;
}
.sh-add {
  font-size: 26rpx;
  color: #12a150;
  font-weight: 600;
}
.empty {
  text-align: center;
  color: #9ca3af;
  font-size: 28rpx;
  padding: 40rpx 0;
}

.cat-list {
  background: #fff;
  border-radius: 24rpx;
  padding: 8rpx 28rpx;
}
.cat {
  padding: 26rpx 0;
  border-top: 1rpx solid #eef0f2;
}
.cat-list .cat:first-child {
  border-top: none;
}
.cat-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 14rpx;
}
.cat-name {
  font-size: 28rpx;
  font-weight: 600;
  color: #1f2937;
}
.cat-amt {
  font-size: 26rpx;
  font-weight: 700;
}
.cat-bar-bg {
  height: 14rpx;
  background: #f0f0f0;
  border-radius: 8rpx;
  overflow: hidden;
}
.cat-bar {
  height: 100%;
  border-radius: 8rpx;
}
.cat-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 10rpx;
  font-size: 22rpx;
  color: #9ca3af;
}
.hint {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: #bbb;
  margin: 20rpx 0;
}

.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.4);
  display: flex;
  align-items: flex-end;
}
.sheet {
  width: 100%;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 40rpx;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}
.sheet-title {
  font-size: 32rpx;
  font-weight: 800;
}
.field {
  background: #f5f6f7;
  border-radius: 14rpx;
  padding: 26rpx;
  font-size: 30rpx;
}
.submit {
  background: #12a150;
  color: #fff;
  border-radius: 44rpx;
  font-size: 32rpx;
}
</style>
