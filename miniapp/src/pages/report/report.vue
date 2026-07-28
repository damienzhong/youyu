<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { categoryReport, monthRange, shiftMonth } from '../../api/report'
import { formatAmount, categoryEmoji, currentMonth, monthLabel } from '../../utils/format'

const KINDS = [
  { value: 'expense', label: '支出' },
  { value: 'income', label: '收入' }
]

const kind = ref('expense')
const month = ref(currentMonth())
const total = ref('0.00')
const rows = ref([])
const loading = ref(false)

const COLORS = ['#16a34a', '#0ea5e9', '#f59e0b', '#e64340', '#8b5cf6', '#1677ff', '#f7b500']
function colorAt(i) {
  return COLORS[i % COLORS.length]
}

async function load() {
  loading.value = true
  try {
    const { from, to } = monthRange(month.value)
    const res = await categoryReport(from, to, kind.value)
    total.value = res.totalExpense
    rows.value = res.categories || []
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

onShow(load)

function selectKind(k) {
  kind.value = k
  load()
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

    <view v-if="!rows.length && !loading" class="empty">
      当月暂无{{ kind === 'expense' ? '支出' : '收入' }}
    </view>

    <view class="list" v-if="rows.length">
      <view v-for="(r, i) in rows" :key="r.categoryId ?? i" class="row">
        <text class="row-ic" :style="{ background: colorAt(i) + '22' }">
          {{ categoryEmoji(r.categoryName, kind) }}
        </text>
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
      </view>
    </view>
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
  background: #16a34a;
  color: #fff;
  font-weight: 700;
}
.total-card {
  border-radius: 28rpx;
  padding: 32rpx 36rpx 40rpx;
  margin-bottom: 24rpx;
  color: #fff;
  background: linear-gradient(150deg, #22c55e, #16a34a 55%, #0b6b34);
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
.empty {
  margin-top: 120rpx;
  text-align: center;
  color: #9ca3af;
  font-size: 28rpx;
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
</style>
