<script setup>
import { onShow } from '@dcloudio/uni-app'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()

// 未登录则回登录页，避免直达首页。
onShow(() => {
  if (!auth.isLoggedIn) {
    uni.reLaunch({ url: '/pages/login/login' })
  }
})

function handleLogout() {
  auth.logout()
  uni.reLaunch({ url: '/pages/login/login' })
}
</script>

<template>
  <view class="index">
    <view class="card">
      <text class="hello">欢迎回来</text>
      <text class="uid">用户 ID：{{ auth.user?.id ?? '-' }}</text>
      <text class="plan">套餐：{{ auth.user?.plan ?? '-' }}</text>
    </view>
    <text class="placeholder">记账主界面待接入（账户 / 交易 / 报表）</text>
    <button class="logout" @click="handleLogout">退出登录</button>
  </view>
</template>

<style scoped>
.index {
  padding: 40rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
}
.card {
  width: 100%;
  background: #fff;
  border-radius: 16rpx;
  padding: 40rpx;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  box-sizing: border-box;
}
.hello {
  font-size: 36rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.uid,
.plan {
  font-size: 28rpx;
  color: #666;
}
.placeholder {
  margin: 60rpx 0;
  font-size: 26rpx;
  color: #999;
}
.logout {
  width: 400rpx;
  font-size: 30rpx;
  color: #07c160;
  background: #fff;
  border: 1rpx solid #07c160;
  border-radius: 40rpx;
}
</style>
