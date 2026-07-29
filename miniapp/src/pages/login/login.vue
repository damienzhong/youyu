<script setup>
import { ref } from 'vue'
import { useAuthStore } from '../../stores/auth'
import { listAccounts } from '../../api/account'

const auth = useAuthStore()
const loading = ref(false)

// 登录后路由：无账户且未走过引导的新用户 → 新手引导；否则进首页。
async function routeAfterLogin() {
  if (!uni.getStorageSync('youyu_onboarded')) {
    try {
      const accs = await listAccounts()
      if (!accs || !accs.length) {
        uni.reLaunch({ url: '/pages/onboarding/onboarding' })
        return
      }
    } catch (e) {
      /* 拉账户失败则按老用户处理 */
    }
  }
  uni.reLaunch({ url: '/pages/index/index' })
}

const showPwd = ref(false)
const isRegister = ref(false)
const username = ref('')
const password = ref('')
const pwdLoading = ref(false)

async function handleLogin() {
  if (loading.value) return
  loading.value = true
  try {
    await auth.loginWithWeixin()
    await routeAfterLogin()
  } catch (e) {
    uni.showToast({ title: e.message || '登录失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

async function handlePwdSubmit() {
  const u = username.value.trim()
  if (!u || !password.value) {
    uni.showToast({ title: '请输入账号和密码', icon: 'none' })
    return
  }
  pwdLoading.value = true
  try {
    if (isRegister.value) await auth.registerAndLogin(u, password.value)
    else await auth.loginWithPassword(u, password.value)
    await routeAfterLogin()
  } catch (e) {
    uni.showToast({ title: e.message || '操作失败', icon: 'none' })
  } finally {
    pwdLoading.value = false
  }
}
</script>

<template>
  <view class="login">
    <view class="brand">
      <view class="brand-mk">¥</view>
      <text class="title">有余</text>
      <text class="slogan">记好每一笔，日子有余</text>
    </view>

    <button class="wx-btn" :loading="loading" @click="handleLogin">微信一键登录</button>

    <text class="toggle" @click="showPwd = !showPwd">
      {{ showPwd ? '收起账号登录' : '用账号密码登录' }}
    </text>

    <view v-if="showPwd" class="pwd-box">
      <input v-model="username" class="field" placeholder="账号" maxlength="64" />
      <input v-model="password" class="field" password placeholder="密码（8-64 位）" maxlength="64" />
      <button class="pwd-btn" :loading="pwdLoading" @click="handlePwdSubmit">
        {{ isRegister ? '注册并登录' : '登录' }}
      </button>
      <text class="switch" @click="isRegister = !isRegister">
        {{ isRegister ? '已有账号？去登录' : '没有账号？去注册' }}
      </text>
    </view>

    <text class="tips">登录即表示同意用户协议与隐私政策</text>
  </view>
</template>

<style scoped>
.login {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 150rpx;
  min-height: 100vh;
  box-sizing: border-box;
  background: #f7f8f7;
}
.brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 100rpx;
}
.brand-mk {
  width: 120rpx;
  height: 120rpx;
  border-radius: 34rpx;
  background: linear-gradient(150deg, #22c55e, #16a34a 60%, #0b6b34);
  color: #fff;
  font-size: 64rpx;
  font-weight: 800;
  text-align: center;
  line-height: 120rpx;
  box-shadow: 0 16rpx 36rpx rgba(22, 163, 74, 0.32);
  margin-bottom: 28rpx;
}
.title {
  font-size: 60rpx;
  font-weight: 800;
  color: #1f2937;
}
.slogan {
  margin-top: 14rpx;
  font-size: 28rpx;
  color: #6b7280;
}
.wx-btn {
  width: 560rpx;
  background-color: #07c160;
  color: #fff;
  border-radius: 44rpx;
  font-size: 32rpx;
}
.toggle {
  margin-top: 32rpx;
  font-size: 26rpx;
  color: #16a34a;
  font-weight: 600;
}
.pwd-box {
  width: 560rpx;
  margin-top: 32rpx;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}
.field {
  background: #fff;
  border-radius: 14rpx;
  padding: 26rpx;
  font-size: 30rpx;
}
.pwd-btn {
  background: #16a34a;
  color: #fff;
  border-radius: 44rpx;
  font-size: 30rpx;
}
.switch {
  text-align: center;
  font-size: 24rpx;
  color: #9ca3af;
}
.tips {
  margin-top: 48rpx;
  font-size: 22rpx;
  color: #bbb;
}
</style>
