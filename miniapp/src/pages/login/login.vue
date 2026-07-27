<script setup>
import { ref } from 'vue'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const loading = ref(false)

async function handleLogin() {
  if (loading.value) return
  loading.value = true
  try {
    await auth.loginWithWeixin()
    uni.reLaunch({ url: '/pages/index/index' })
  } catch (e) {
    uni.showToast({ title: e.message || '登录失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <view class="login">
    <view class="brand">
      <text class="title">有余</text>
      <text class="slogan">记好每一笔，日子有余</text>
    </view>
    <button class="wx-btn" :loading="loading" @click="handleLogin">
      微信一键登录
    </button>
    <text class="tips">登录即表示同意用户协议与隐私政策</text>
  </view>
</template>

<style scoped>
.login {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 160rpx;
  min-height: 100vh;
  box-sizing: border-box;
}
.brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 120rpx;
}
.title {
  font-size: 64rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.slogan {
  margin-top: 16rpx;
  font-size: 28rpx;
  color: #888;
}
.wx-btn {
  width: 560rpx;
  background-color: #07c160;
  color: #fff;
  border-radius: 44rpx;
  font-size: 32rpx;
}
.tips {
  margin-top: 32rpx;
  font-size: 22rpx;
  color: #aaa;
}
</style>
