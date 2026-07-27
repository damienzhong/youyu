<script setup>
import { ref } from 'vue'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const loading = ref(false)

// 账号密码登录（浏览器/H5 联调或备用），默认折叠
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
    uni.reLaunch({ url: '/pages/index/index' })
  } catch (e) {
    uni.showToast({ title: e.message || '登录失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

async function handlePwdSubmit() {
  const u = username.value.trim()
  if (!u || !password.value) {
    uni.showToast({ title: '请输入账号和口令', icon: 'none' })
    return
  }
  pwdLoading.value = true
  try {
    if (isRegister.value) {
      await auth.registerAndLogin(u, password.value)
    } else {
      await auth.loginWithPassword(u, password.value)
    }
    uni.reLaunch({ url: '/pages/index/index' })
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
      <text class="title">有余</text>
      <text class="slogan">记好每一笔，日子有余</text>
    </view>

    <button class="wx-btn" :loading="loading" @click="handleLogin">
      微信一键登录
    </button>

    <text class="toggle" @click="showPwd = !showPwd">
      {{ showPwd ? '收起账号登录' : '用账号密码登录' }}
    </text>

    <!-- 账号密码登录/注册（无微信环境下联调用） -->
    <view v-if="showPwd" class="pwd-box">
      <input v-model="username" class="field" placeholder="账号" maxlength="64" />
      <input
        v-model="password"
        class="field"
        password
        placeholder="口令（8-64 位）"
        maxlength="64"
      />
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
  padding-top: 140rpx;
  min-height: 100vh;
  box-sizing: border-box;
}
.brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 100rpx;
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
.toggle {
  margin-top: 32rpx;
  font-size: 26rpx;
  color: #576b95;
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
  border-radius: 12rpx;
  padding: 24rpx;
  font-size: 30rpx;
}
.pwd-btn {
  background: #1a1a1a;
  color: #fff;
  border-radius: 44rpx;
  font-size: 30rpx;
}
.switch {
  text-align: center;
  font-size: 24rpx;
  color: #999;
}
.tips {
  margin-top: 48rpx;
  font-size: 22rpx;
  color: #aaa;
}
</style>
