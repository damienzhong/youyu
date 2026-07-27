<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { categoryReport, monthRange, shiftMonth } from '../../api/report'

const KINDS = [
  { value: 'expense', label: '支出' },
  { value: 'income', label: '收入' }
]

const kind = ref('expense')
const month = ref(thisMonth())
const total = ref('0.00')
const rows = ref([])
const loading = ref(false)

function thisMonth() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

// 占比条颜色轮转，视觉区分各分类
const COLORS = ['#07c160', '#576b95', '#f0883a', '#e64340', '#8a6de9', '#3aa1ff', '#f7b500']
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
    uni.showToast({ title: e.message || '加载失败', icon: 'none' })
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

    <view class="month-bar">
      <text class="nav" @click="prevMonth">‹</text>
      <text class="month">{{ month }}</text>
      <text class="nav" @click="nextMonth">›</text>
    </view>

    <view class="total-card">
      <text class="total-label">合计</text>
      <text class="total-value">{{ total }}</text>
    </view>

    <view v-if="!rows.length && !loading" class="empty">当月暂无{{ kind === 'expense' ? '支出' : '收入' }}</view>

    <view v-for="(r, i) in rows" :key="r.categoryId ?? i" class="row">
      <view class="row-head">
        <text class="row-name">{{ r.categoryName || '未分类' }}</text>
        <text class="row-amount">{{ r.amount }}（{{ r.percentage }}%）</text>
      </view>
      <view class="bar-bg">
        <view
          class="bar"
          :style="{ width: r.percentage + '%', background: colorAt(i) }"
        ></view>
      </view>
      <text class="row-count">{{ r.count }} 笔</text>
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
  border-radius: 16rpx;
  overflow: hidden;
  margin-bottom: 20rpx;
}
.kind {
  flex: 1;
  text-align: center;
  padding: 26rpx 0;
  font-size: 30rpx;
  color: #666;
}
.kind.active {
  background: #07c160;
  color: #fff;
}
.month-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 48rpx;
  margin-bottom: 20rpx;
}
.nav {
  font-size: 48rpx;
  color: #576b95;
  padding: 0 24rpx;
}
.month {
  font-size: 32rpx;
  color: #1a1a1a;
}
.total-card {
  background: #fff;
  border-radius: 16rpx;
  padding: 36rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  margin-bottom: 24rpx;
}
.total-label {
  font-size: 24rpx;
  color: #999;
}
.total-value {
  font-size: 48rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.empty {
  margin-top: 120rpx;
  text-align: center;
  color: #999;
  font-size: 28rpx;
}
.row {
  background: #fff;
  border-radius: 16rpx;
  padding: 28rpx 32rpx;
  margin-bottom: 16rpx;
}
.row-head {
  display: flex;
  justify-content: space-between;
  margin-bottom: 16rpx;
}
.row-name {
  font-size: 30rpx;
  color: #1a1a1a;
}
.row-amount {
  font-size: 26rpx;
  color: #666;
}
.bar-bg {
  height: 16rpx;
  background: #f0f0f0;
  border-radius: 8rpx;
  overflow: hidden;
}
.bar {
  height: 100%;
  border-radius: 8rpx;
}
.row-count {
  display: block;
  margin-top: 12rpx;
  font-size: 22rpx;
  color: #bbb;
}
</style>
