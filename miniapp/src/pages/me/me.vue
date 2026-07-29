<script setup>
import { computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()

const username = computed(() => auth.user?.username || '微信用户')
const plan = computed(() => {
  const p = auth.user?.plan
  return p === 'pro' ? '专业版' : p === 'lifetime' ? '终身版' : '免费版'
})

onShow(() => {
  if (!auth.isLoggedIn) uni.reLaunch({ url: '/pages/login/login' })
})

const entries = [
  { key: 'ledgers', icon: '📚', label: '账本管理', url: '/pages/ledgers/ledgers' },
  { key: 'budget', icon: '🧮', label: '预算管理', url: '/pages/budget/budget' },
  { key: 'loans', icon: '🤝', label: '借贷往来', url: '/pages/loans/loans' },
  { key: 'bills', icon: '📥', label: '账单导入', url: '/pages/billimport/billimport' },
  { key: 'categories', icon: '🏷️', label: '分类管理', url: '/pages/categories/categories' },
  { key: 'data', icon: '🗂️', label: '数据导出 / 导入', url: '/pages/data/data' }
]
function go(url) {
  uni.navigateTo({ url })
}
function logout() {
  uni.showModal({
    title: '退出登录',
    content: '确定退出当前账号？',
    success: (r) => {
      if (!r.confirm) return
      auth.logout()
      uni.reLaunch({ url: '/pages/login/login' })
    }
  })
}
</script>

<template>
  <view class="page">
    <!-- 账号卡 -->
    <view class="profile">
      <view class="avatar">{{ username.slice(0, 1) }}</view>
      <view class="p-info">
        <text class="p-name">{{ username }}</text>
        <text class="p-plan">{{ plan }}</text>
      </view>
    </view>

    <!-- 功能入口 -->
    <view class="menu">
      <view v-for="(e, i) in entries" :key="e.key" class="menu-item" :class="{ first: i === 0 }" @click="go(e.url)">
        <text class="mi-ic">{{ e.icon }}</text>
        <text class="mi-label">{{ e.label }}</text>
        <text class="mi-arrow">›</text>
      </view>
    </view>

    <button class="logout" @click="logout">退出登录</button>

    <text class="ver">有余 · 记好每一笔，日子有余</text>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
}
.profile {
  display: flex;
  align-items: center;
  gap: 24rpx;
  background: linear-gradient(150deg, #22c55e, #16a34a 55%, #0b6b34);
  border-radius: 28rpx;
  padding: 40rpx 36rpx;
  color: #fff;
  box-shadow: 0 20rpx 44rpx rgba(22, 163, 74, 0.26);
  margin-bottom: 24rpx;
}
.avatar {
  width: 96rpx;
  height: 96rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.24);
  text-align: center;
  line-height: 96rpx;
  font-size: 44rpx;
  font-weight: 800;
}
.p-info {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.p-name {
  font-size: 36rpx;
  font-weight: 800;
}
.p-plan {
  font-size: 24rpx;
  opacity: 0.9;
}
.menu {
  background: #fff;
  border-radius: 24rpx;
  padding: 0 32rpx;
  margin-bottom: 32rpx;
}
.menu-item {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 32rpx 0;
  border-top: 1rpx solid #eef0f2;
}
.menu-item.first {
  border-top: none;
}
.mi-ic {
  font-size: 36rpx;
}
.mi-label {
  flex: 1;
  font-size: 30rpx;
  color: #1f2937;
}
.mi-arrow {
  color: #c0c4cc;
  font-size: 34rpx;
}
.logout {
  background: #fff;
  color: #dc2626;
  border-radius: 44rpx;
  font-size: 30rpx;
}
.ver {
  display: block;
  text-align: center;
  margin-top: 40rpx;
  font-size: 22rpx;
  color: #bbb;
}
</style>
