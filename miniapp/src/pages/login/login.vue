<script setup>
import { ref, computed, onUnmounted } from 'vue'
import { useAuthStore } from '../../stores/auth'
import { sendCode } from '../../api/auth'
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

async function handleWxLogin() {
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

// 邮箱验证码登录/注册合一（默认折叠，微信一键为主路径，需要时展开）
const showEmail = ref(false)
const email = ref('')
const code = ref('')
const emailLoading = ref(false)
const sending = ref(false)
const cooldown = ref(0)
let cooldownTimer = null

function toggleEmail() {
  showEmail.value = !showEmail.value
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

const canSend = computed(() => cooldown.value === 0)
const sendLabel = computed(() => (cooldown.value > 0 ? `${cooldown.value}s` : '发送验证码'))

function startCooldown() {
  cooldown.value = 60
  cooldownTimer = setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0) {
      clearInterval(cooldownTimer)
      cooldownTimer = null
    }
  }, 1000)
}

onUnmounted(() => {
  if (cooldownTimer) clearInterval(cooldownTimer)
})

async function handleSendCode() {
  if (!canSend.value || sending.value) return
  const e = email.value.trim()
  if (!EMAIL_RE.test(e)) {
    uni.showToast({ title: '请输入正确的邮箱', icon: 'none' })
    return
  }
  sending.value = true
  try {
    await sendCode(e, 'LOGIN')
    startCooldown()
    uni.showToast({ title: '验证码已发送', icon: 'none' })
  } catch (err) {
    uni.showToast({ title: err.message || '发送失败', icon: 'none' })
  } finally {
    sending.value = false
  }
}

async function handleEmailLogin() {
  if (emailLoading.value) return
  const e = email.value.trim()
  const c = code.value.trim()
  if (!EMAIL_RE.test(e)) {
    uni.showToast({ title: '请输入正确的邮箱', icon: 'none' })
    return
  }
  if (!/^\d{6}$/.test(c)) {
    uni.showToast({ title: '请输入 6 位验证码', icon: 'none' })
    return
  }
  emailLoading.value = true
  try {
    await auth.loginWithEmail(e, c)
    await routeAfterLogin()
  } catch (err) {
    uni.showToast({ title: err.message || '登录失败', icon: 'none' })
  } finally {
    emailLoading.value = false
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

    <button class="wx-btn" :loading="loading" @click="handleWxLogin">微信一键登录</button>

    <view class="divider" @click="toggleEmail">
      <view class="line"></view>
      <text class="divider-text">
        或使用邮箱登录 / 注册
        <text class="chevron">{{ showEmail ? '▲' : '▼' }}</text>
      </text>
      <view class="line"></view>
    </view>

    <view v-if="showEmail" class="email-box">
      <input
        v-model="email"
        class="field"
        type="text"
        confirm-type="next"
        placeholder="邮箱"
        maxlength="128"
      />
      <view class="code-row">
        <input
          v-model="code"
          class="field code-field"
          type="number"
          placeholder="6 位验证码"
          maxlength="6"
        />
        <button class="code-btn" :disabled="!canSend || sending" @click="handleSendCode">
          {{ sendLabel }}
        </button>
      </view>
      <button class="email-btn" :loading="emailLoading" @click="handleEmailLogin">
        登录 / 注册
      </button>
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
.divider {
  width: 560rpx;
  margin-top: 40rpx;
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.divider .line {
  flex: 1;
  height: 1px;
  background: #e5e7eb;
}
.divider-text {
  font-size: 24rpx;
  color: #9ca3af;
  display: flex;
  align-items: center;
  gap: 8rpx;
  white-space: nowrap;
}
.chevron {
  font-size: 18rpx;
  color: #c0c4cc;
}
.email-box {
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
.code-row {
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.code-field {
  flex: 1;
}
.code-btn {
  flex-shrink: 0;
  width: 200rpx;
  background: #fff;
  color: #16a34a;
  border: 1px solid #16a34a;
  border-radius: 14rpx;
  font-size: 26rpx;
  padding: 0;
  line-height: 82rpx;
  height: 82rpx;
}
.code-btn[disabled] {
  color: #9ca3af;
  border-color: #e5e7eb;
  background: #f3f4f6;
}
.email-btn {
  background: #16a34a;
  color: #fff;
  border-radius: 44rpx;
  font-size: 30rpx;
}
.tips {
  margin-top: 48rpx;
  font-size: 22rpx;
  color: #bbb;
}
</style>
