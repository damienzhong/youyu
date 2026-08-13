<script setup>
import { ref, computed, onUnmounted } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useAuthStore } from '../../stores/auth'
import { sendCode } from '../../api/auth'
import { listAccounts } from '../../api/account'
import { takePendingAchievementCode } from '../../utils/achievement'
// #ifdef MP-WEIXIN
import { STORAGE_KEYS } from '../../utils/config'
// #endif

const auth = useAuthStore()
const loading = ref(false)
// 平台分流：
//  - 微信小程序：进入即静默 wx.login 直接进应用（认证不涉及个人信息，合规）；隐私接口（相册/文件/
//    剪贴板）的同意交给微信系统隐私弹窗（manifest 已开 __usePrivacyCheck__）。不做协议默认/强制同意。
//  - H5 网页：不受微信审核约束，保留原「主动勾选同意协议」的登录页。
// 因此协议勾选框与其校验仅在 H5 生效。
const agreed = ref(false)
// #ifdef MP-WEIXIN
// 小程序静默登录仅尝试一次，避免失败时死循环。
const autoTried = ref(false)
// #endif

// 登录前校验（仅 H5）：未勾选同意协议则提示并中断；小程序端不设此门槛（见上）。
function ensureAgreed() {
  // #ifdef H5
  if (!agreed.value) {
    uni.showToast({ title: '请先阅读并勾选同意《用户协议》和《隐私政策》', icon: 'none' })
    return false
  }
  // #endif
  return true
}

// 登录后路由：无账户且未走过引导的新用户 → 新手引导；否则进首页。
async function routeAfterLogin() {
  // 经成就分享卡片进来的未登录用户，其 code 由成就页暂存在这里（需求 8.14）。
  // 一次性消费：若接着走新手引导就直接丢弃——零成就的新账号没有可高亮的项。
  const achievementCode = takePendingAchievementCode()

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

  if (achievementCode) {
    // 先 reLaunch 首页再 navigateTo 成就页：成就页是非 tabBar 页面，
    // 直接 reLaunch 过去会清空页面栈，用户回不到首页。
    uni.reLaunch({
      url: '/pages/home/home',
      success: () => {
        uni.navigateTo({
          url: `/pages/achievement/achievement?code=${encodeURIComponent(achievementCode)}`
        })
      }
    })
    return
  }
  uni.reLaunch({ url: '/pages/home/home' })
}

async function handleWxLogin() {
  if (loading.value) return
  if (!ensureAgreed()) return
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

// 欢迎页(pages[0])在首次进入且同意协议后会转发到本页；老用户/已同意者冷启动也会经欢迎页快速转发到这里。
// 已登录（本地有令牌）就直接转发进应用——修复「登录后重新进入小程序又回到登录页」：
// 根因是入口页从不为已登录用户转发，而非令牌未持久化。
onShow(() => {
  // 已登录（本地有令牌）直接进应用。
  if (auth.isLoggedIn) {
    routeAfterLogin()
    return
  }
  // #ifdef MP-WEIXIN
  // 微信小程序：静默 wx.login 直接登录进应用（认证不采集个人信息，合规）。
  // 除非用户此前主动退出；静默失败则安静停留在登录页，由用户手动选择，不弹错、不重试。
  if (autoTried.value || loading.value) return
  if (uni.getStorageSync(STORAGE_KEYS.signedOut)) return
  autoTried.value = true
  loading.value = true
  auth
    .loginWithWeixin()
    .then(routeAfterLogin)
    .catch(() => { /* 静默失败：留在登录页，等待用户手动登录 */ })
    .finally(() => { loading.value = false })
  // #endif
  // H5：不自动登录，展示带「主动勾选同意协议」的登录页（见模板）。
})

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
function openLegal(type) {
  uni.navigateTo({ url: `/pages/legal/legal?type=${type}` })
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
  if (!ensureAgreed()) return
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
      <text class="slogan">记好每一笔，日子更有余</text>
    </view>

    <!-- #ifdef H5 -->
    <!-- 协议勾选（仅 H5）：默认未勾选，用户主动勾选后才能登录 -->
    <view class="agree" @click="agreed = !agreed">
      <view class="cbox" :class="{ on: agreed }">
        <text v-if="agreed" class="tick">✓</text>
      </view>
      <text class="agree-text">我已阅读并同意
        <text class="tips-link" @click.stop="openLegal('user')">《用户协议》</text>
        与
        <text class="tips-link" @click.stop="openLegal('privacy')">《隐私政策》</text>
      </text>
    </view>
    <!-- #endif -->

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
/* 协议勾选行：置于登录按钮上方，默认未勾选 */
.agree {
  width: 560rpx;
  display: flex;
  align-items: center;
  gap: 14rpx;
  margin-bottom: 28rpx;
}
.cbox {
  flex: 0 0 auto;
  width: 36rpx;
  height: 36rpx;
  border: 2rpx solid #c0c4cc;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
}
.cbox.on {
  background: #07c160;
  border-color: #07c160;
}
.cbox .tick {
  color: #fff;
  font-size: 24rpx;
  line-height: 1;
}
.agree-text {
  font-size: 24rpx;
  color: #6b7280;
  line-height: 1.5;
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
  text-align: center;
  padding: 0 40rpx;
  line-height: 1.6;
}
.tips-link {
  color: #16a34a;
}
</style>
