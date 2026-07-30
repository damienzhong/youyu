<script setup>
import { computed, ref, onUnmounted } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useAuthStore, getWxLoginCode } from '../../stores/auth'
import { sendCode, bindEmail, bindWechat, unbind, deleteAccount } from '../../api/auth'

const auth = useAuthStore()

const nickname = computed(() => auth.user?.nickname || '有余用户')
const plan = computed(() => {
  const p = auth.user?.plan
  return p === 'pro' ? '专业版' : p === 'lifetime' ? '终身版' : '免费版'
})
const planExpires = computed(() => (auth.user?.planExpiresAt || '').slice(0, 10))
const hasEmail = computed(() => !!auth.user?.hasEmail)
const hasWechat = computed(() => !!auth.user?.hasWechat)
const email = computed(() => auth.user?.email || '')
// 同时拥有两种身份时才允许解绑，避免必然触发 LAST_LOGIN_METHOD。
const canUnbind = computed(() => hasEmail.value && hasWechat.value)

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

onShow(() => {
  if (!auth.isLoggedIn) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  auth.refreshUser().catch(() => {})
})

// ---- 绑定邮箱 ----
const showBindEmail = ref(false)
const bindEmailValue = ref('')
const bindEmailCode = ref('')
const bindEmailLoading = ref(false)
const sendingBindCode = ref(false)
const cooldown = ref(0)
let cooldownTimer = null

const canSendBindCode = computed(() => cooldown.value === 0)
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

function toggleBindEmail() {
  showBindEmail.value = !showBindEmail.value
}

async function handleSendBindCode() {
  if (!canSendBindCode.value || sendingBindCode.value) return
  const e = bindEmailValue.value.trim()
  if (!EMAIL_RE.test(e)) {
    uni.showToast({ title: '请输入正确的邮箱', icon: 'none' })
    return
  }
  sendingBindCode.value = true
  try {
    await sendCode(e, 'BIND')
    startCooldown()
    uni.showToast({ title: '验证码已发送', icon: 'none' })
  } catch (err) {
    uni.showToast({ title: err.message || '发送失败', icon: 'none' })
  } finally {
    sendingBindCode.value = false
  }
}

async function handleBindEmail() {
  if (bindEmailLoading.value) return
  const e = bindEmailValue.value.trim()
  const c = bindEmailCode.value.trim()
  if (!EMAIL_RE.test(e)) {
    uni.showToast({ title: '请输入正确的邮箱', icon: 'none' })
    return
  }
  if (!/^\d{6}$/.test(c)) {
    uni.showToast({ title: '请输入 6 位验证码', icon: 'none' })
    return
  }
  bindEmailLoading.value = true
  try {
    await bindEmail(e, c)
    await auth.refreshUser().catch(() => {})
    showBindEmail.value = false
    bindEmailValue.value = ''
    bindEmailCode.value = ''
    uni.showToast({ title: '绑定成功', icon: 'success' })
  } catch (err) {
    uni.showToast({ title: err.message || '绑定失败', icon: 'none' })
  } finally {
    bindEmailLoading.value = false
  }
}

// ---- 绑定微信 ----
const bindWechatLoading = ref(false)
async function handleBindWechat() {
  if (bindWechatLoading.value) return
  bindWechatLoading.value = true
  try {
    const code = await getWxLoginCode()
    await bindWechat(code)
    await auth.refreshUser().catch(() => {})
    uni.showToast({ title: '绑定成功', icon: 'success' })
  } catch (err) {
    uni.showToast({ title: err.message || '绑定失败', icon: 'none' })
  } finally {
    bindWechatLoading.value = false
  }
}

// ---- 解绑 ----
function handleUnbind(type) {
  const label = type === 'email' ? '邮箱' : '微信'
  uni.showModal({
    title: `解绑${label}`,
    content: `确定解绑${label}？解绑后将无法用${label}登录当前账号。`,
    success: async (r) => {
      if (!r.confirm) return
      try {
        await unbind(type)
        await auth.refreshUser().catch(() => {})
        uni.showToast({ title: '已解绑', icon: 'success' })
      } catch (err) {
        uni.showToast({ title: err.message || '解绑失败', icon: 'none' })
      }
    }
  })
}

// ---- 注销账号（二次确认）----
function handleDeleteAccount() {
  uni.showModal({
    title: '注销账号',
    content: '注销将永久删除你的账号与全部数据，且不可恢复。确定继续？',
    confirmColor: '#dc2626',
    success: (r) => {
      if (!r.confirm) return
      confirmDeleteAccount()
    }
  })
}

function confirmDeleteAccount() {
  if (hasEmail.value) {
    uni.showModal({
      title: '发送注销验证码',
      content: `将向 ${email.value} 发送注销验证码，用于二次验证。`,
      confirmColor: '#dc2626',
      success: async (r) => {
        if (!r.confirm) return
        try {
          await sendCode(email.value, 'DELETE')
          uni.showToast({ title: '验证码已发送', icon: 'none' })
        } catch (err) {
          uni.showToast({ title: err.message || '发送失败', icon: 'none' })
          return
        }
        promptDeleteCode()
      }
    })
  } else if (hasWechat.value) {
    uni.showModal({
      title: '确认注销',
      content: '将通过微信重新授权完成二次验证并注销账号。',
      confirmColor: '#dc2626',
      success: async (r) => {
        if (!r.confirm) return
        try {
          const wxCode = await getWxLoginCode()
          await doDeleteAccount({ wxCode })
        } catch (err) {
          uni.showToast({ title: err.message || '注销失败', icon: 'none' })
        }
      }
    })
  } else {
    uni.showToast({ title: '当前账号无可用验证方式', icon: 'none' })
  }
}

function promptDeleteCode() {
  uni.showModal({
    title: '输入注销验证码',
    editable: true,
    placeholderText: '6 位验证码',
    confirmColor: '#dc2626',
    success: async (r) => {
      if (!r.confirm) return
      const c = (r.content || '').trim()
      if (!/^\d{6}$/.test(c)) {
        uni.showToast({ title: '请输入 6 位验证码', icon: 'none' })
        return
      }
      await doDeleteAccount({ code: c })
    }
  })
}

async function doDeleteAccount(params) {
  uni.showLoading({ title: '注销中', mask: true })
  try {
    await deleteAccount(params)
    uni.hideLoading()
    auth.logout()
    uni.reLaunch({ url: '/pages/login/login' })
  } catch (err) {
    uni.hideLoading()
    uni.showToast({ title: err.message || '注销失败', icon: 'none' })
  }
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
    <!-- 个人信息 -->
    <view class="sect">个人信息</view>
    <view class="card">
      <view class="row">
        <view class="r-ic t-green"><text>👤</text></view>
        <text class="r-t">昵称</text>
        <text class="r-v">{{ nickname }}</text>
      </view>
      <view class="row">
        <view class="r-ic t-purple"><text>🎫</text></view>
        <text class="r-t">套餐</text>
        <text class="r-v">{{ plan }}<text v-if="planExpires"> · 到期 {{ planExpires }}</text></text>
      </view>
    </view>

    <!-- 登录方式 -->
    <view class="sect">登录方式（至少保留一种）</view>
    <view class="card">
      <!-- 邮箱 -->
      <view class="row">
        <view class="r-ic t-blue"><text>✉️</text></view>
        <view class="r-main">
          <text class="r-t">邮箱</text>
          <text class="r-sub">{{ hasEmail ? email : '未绑定' }}</text>
        </view>
        <text v-if="hasEmail && canUnbind" class="act unbind" @click="handleUnbind('email')">解绑</text>
        <text v-else-if="!hasEmail" class="act bind" @click="toggleBindEmail">绑定</text>
      </view>
      <!-- 绑定邮箱面板 -->
      <view v-if="!hasEmail && showBindEmail" class="bind-panel">
        <input v-model="bindEmailValue" class="field" type="text" placeholder="邮箱" maxlength="128" />
        <view class="code-row">
          <input v-model="bindEmailCode" class="field code-field" type="number" placeholder="6 位验证码" maxlength="6" />
          <button class="code-btn" :disabled="!canSendBindCode || sendingBindCode" @click="handleSendBindCode">
            {{ sendLabel }}
          </button>
        </view>
        <button class="confirm-btn" :loading="bindEmailLoading" @click="handleBindEmail">确认绑定</button>
      </view>
      <!-- 微信 -->
      <view class="row">
        <view class="r-ic t-teal"><text>💬</text></view>
        <view class="r-main">
          <text class="r-t">微信</text>
          <text class="r-sub">{{ hasWechat ? '已绑定' : '未绑定' }}</text>
        </view>
        <text v-if="hasWechat && canUnbind" class="act unbind" @click="handleUnbind('wechat')">解绑</text>
        <text v-else-if="!hasWechat" class="act bind" @click="handleBindWechat">绑定</text>
      </view>
    </view>

    <!-- 危险操作 -->
    <view class="sect">危险操作</view>
    <view class="card">
      <view class="row danger" @click="handleDeleteAccount">
        <view class="r-ic t-red"><text>⚠️</text></view>
        <view class="r-main">
          <text class="r-t danger-t">注销账号</text>
          <text class="r-sub">永久删除，不可恢复</text>
        </view>
        <text class="arrow">›</text>
      </view>
    </view>

    <view class="logout" @click="logout">退出登录</view>
    <view style="height:40rpx;"></view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f2f4f6;
  padding: 16rpx 24rpx 24rpx;
}
.sect {
  font-size: 24rpx;
  color: #9aa2ad;
  padding: 20rpx 8rpx 12rpx;
}
.card {
  background: #fff;
  border-radius: 24rpx;
  overflow: hidden;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
}
.row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 26rpx 28rpx;
  border-top: 1rpx solid #eef0f2;
}
.card .row:first-child {
  border-top: none;
}
.r-ic {
  width: 64rpx;
  height: 64rpx;
  border-radius: 18rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 32rpx;
  flex: 0 0 auto;
}
.t-green { background: #e7f7ee; }
.t-blue { background: #e8f0fe; }
.t-purple { background: #f0ecfe; }
.t-teal { background: #e4f6f5; }
.t-red { background: #fdeceb; }
.r-t {
  font-size: 30rpx;
  color: #25292e;
}
.r-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.r-v {
  flex: 1;
  text-align: right;
  font-size: 26rpx;
  color: #9aa2ad;
}
.r-sub {
  font-size: 24rpx;
  color: #9aa2ad;
}
.arrow {
  color: #c7ccd2;
  font-size: 34rpx;
}
.act {
  font-size: 26rpx;
  padding: 8rpx 26rpx;
  border-radius: 999rpx;
}
.act.bind {
  color: #12a150;
  border: 1rpx solid #12a150;
}
.act.unbind {
  color: #6b7280;
  border: 1rpx solid #d1d5db;
}
.danger-t {
  color: #e5484d;
}
.bind-panel {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
  padding: 8rpx 28rpx 28rpx;
  border-top: 1rpx solid #eef0f2;
}
.field {
  background: #f5f6f5;
  border-radius: 14rpx;
  padding: 24rpx;
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
  color: #12a150;
  border: 1px solid #12a150;
  border-radius: 14rpx;
  font-size: 26rpx;
  padding: 0;
  line-height: 78rpx;
  height: 78rpx;
}
.code-btn[disabled] {
  color: #9ca3af;
  border-color: #e5e7eb;
  background: #f3f4f6;
}
.confirm-btn {
  background: #12a150;
  color: #fff;
  border-radius: 44rpx;
  font-size: 30rpx;
}
.logout {
  margin-top: 24rpx;
  background: #fff;
  border-radius: 18rpx;
  text-align: center;
  padding: 28rpx;
  color: #e5484d;
  font-size: 30rpx;
  font-weight: 600;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
}
</style>
