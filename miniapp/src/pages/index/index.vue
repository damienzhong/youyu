<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useAuthStore } from '../../stores/auth'
import { listTransactionsByMonth } from '../../api/transaction'

const auth = useAuthStore()

const monthlyExpense = ref('0.00')
const monthlyIncome = ref('0.00')
const currentMonth = ref('')

function thisMonth() {
  const d = new Date()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  return `${d.getFullYear()}-${m}`
}

async function loadSummary() {
  const month = thisMonth()
  currentMonth.value = month
  try {
    const txs = await listTransactionsByMonth(month)
    let expense = 0
    let income = 0
    for (const t of txs) {
      if (t.type === 'expense') expense += Number(t.amount)
      else if (t.type === 'income') income += Number(t.amount)
    }
    monthlyExpense.value = expense.toFixed(2)
    monthlyIncome.value = income.toFixed(2)
  } catch (e) {
    // 首页概览失败不打断使用，静默处理
  }
}

onShow(() => {
  if (!auth.isLoggedIn) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  loadSummary()
})

function goRecord() {
  uni.navigateTo({ url: '/pages/record/record' })
}
function goRecords() {
  uni.navigateTo({ url: '/pages/records/records' })
}
function goAccounts() {
  uni.navigateTo({ url: '/pages/accounts/accounts' })
}
function handleLogout() {
  auth.logout()
  uni.reLaunch({ url: '/pages/login/login' })
}
</script>

<template>
  <view class="index">
    <view class="summary">
      <text class="month">{{ currentMonth }} 本月</text>
      <view class="figures">
        <view class="fig">
          <text class="label">支出</text>
          <text class="expense">{{ monthlyExpense }}</text>
        </view>
        <view class="fig">
          <text class="label">收入</text>
          <text class="income">{{ monthlyIncome }}</text>
        </view>
      </view>
    </view>

    <view class="actions">
      <view class="action primary" @click="goRecord">记一笔</view>
      <view class="action" @click="goRecords">本月明细</view>
      <view class="action" @click="goAccounts">账户管理</view>
    </view>

    <text class="placeholder">分类报表待接入</text>
    <button class="logout" @click="handleLogout">退出登录</button>
  </view>
</template>

<style scoped>
.index {
  padding: 32rpx;
  display: flex;
  flex-direction: column;
}
.summary {
  background: #fff;
  border-radius: 20rpx;
  padding: 40rpx;
}
.month {
  font-size: 26rpx;
  color: #999;
}
.figures {
  display: flex;
  margin-top: 24rpx;
}
.fig {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.label {
  font-size: 24rpx;
  color: #999;
}
.expense {
  font-size: 44rpx;
  font-weight: 600;
  color: #e64340;
}
.income {
  font-size: 44rpx;
  font-weight: 600;
  color: #07c160;
}
.actions {
  display: flex;
  gap: 24rpx;
  margin: 40rpx 0;
}
.action {
  flex: 1;
  text-align: center;
  padding: 32rpx 0;
  background: #fff;
  border-radius: 16rpx;
  font-size: 30rpx;
  color: #333;
}
.action.primary {
  background: #07c160;
  color: #fff;
}
.placeholder {
  text-align: center;
  font-size: 26rpx;
  color: #999;
  margin: 40rpx 0;
}
.logout {
  width: 400rpx;
  align-self: center;
  font-size: 30rpx;
  color: #07c160;
  background: #fff;
  border: 1rpx solid #07c160;
  border-radius: 40rpx;
}
</style>
