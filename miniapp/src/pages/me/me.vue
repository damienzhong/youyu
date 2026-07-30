<script setup>
import { computed, ref, onUnmounted } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useAuthStore, getWxLoginCode } from '../../stores/auth'
import { sendCode, bindEmail, bindWechat, unbind, deleteAccount } from '../../api/auth'

const auth = useAuthStore()

const username = computed(() => auth.user?.nickname || '有余用户')
const plan = computed(() => {
  const p = auth.user?.plan
  return p === 'pro' ? '专业版' : p === 'lifetime' ? '终身版' : '免费版'
})

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
  // 进页面刷新一次用户摘要，保证绑定状态与后端一致（失败静默）。
  auth.refreshUser().catch(() => {})
})

const entries = [
  { key: 'ledgers', icon: '📚', label: '账本管理', url: '/pages/ledgers/ledgers' },
  { key: 'budget', icon: '🧮', label: '预算管理', url: '/pages/budget/budget' },
  { key: 'loans', icon: '🤝', label: '借贷往来', url: '/pages/loans/loans' },
  { key: 'bills', icon: '📥', label: '账单导入', url: '/pages/billimport/billimport' },
  { key: 'categories', icon: '🏷️', label: '分类管理', url: '/pages/categories/categories' },
  { key: 'labels', icon: '📁', label: '项目 / 商家 / 标签', url: '/pages/labels/labels' },
  { key: 'recycle', icon: '🗑️', label: '回收站', url: '/pages/recycle/recycle' },
  { key: 'data', icon: '🗂️', label: '数据导出 / 导入', url: '/pages/data/data' }
]
function go(url) {
  uni.navigateTo({ url })
}

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

// 第二重确认 + 二次验证
function confirmDeleteAccount() {
  if (hasEmail.value) {
    // 邮箱用户：发送 DELETE 验证码并输入
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
    // 纯微信用户：重新授权
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
    // 注销失败不登出，提示错误（如 DELETE_BLOCKED_COLLAB）
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
    <!-- 账号卡 -->
    <view class="profile">
      <view class="avatar">{{ username.slice(0, 1) }}</view>
      <view class="p-info">
        <text class="p-name">{{ username }}</text>
        <text class="p-plan">{{ plan }}</text>
      </view>
    </view>

    <!-- 账号与安全 -->
    <view class="section-title">账号与安全</view>
    <view class="menu">
      <!-- 邮箱 -->
      <view class="menu-item first sec-item">
        <text class="mi-ic">✉️</text>
        <view class="sec-main">
          <text class="mi-label">邮箱</text>
          <text class="sec-sub">{{ hasEmail ? email : '未绑定' }}</text>
        </view>
        <text v-if="hasEmail && canUnbind" class="act act-unbind" @click="handleUnbind('email')">解绑</text>
        <text v-else-if="!hasEmail" class="act act-bind" @click="toggleBindEmail">绑定</text>
      </view>

      <!-- 绑定邮箱面板 -->
      <view v-if="!hasEmail && showBindEmail" class="bind-panel">
        <input
          v-model="bindEmailValue"
          class="field"
          type="text"
          placeholder="邮箱"
          maxlength="128"
        />
        <view class="code-row">
          <input
            v-model="bindEmailCode"
            class="field code-field"
            type="number"
            placeholder="6 位验证码"
            maxlength="6"
          />
          <button class="code-btn" :disabled="!canSendBindCode || sendingBindCode" @click="handleSendBindCode">
            {{ sendLabel }}
          </button>
        </view>
        <button class="confirm-btn" :loading="bindEmailLoading" @click="handleBindEmail">
          确认绑定
        </button>
      </view>

      <!-- 微信 -->
      <view class="menu-item sec-item">
        <text class="mi-ic">💬</text>
        <view class="sec-main">
          <text class="mi-label">微信</text>
          <text class="sec-sub">{{ hasWechat ? '已绑定' : '未绑定' }}</text>
        </view>
        <text v-if="hasWechat && canUnbind" class="act act-unbind" @click="handleUnbind('wechat')">解绑</text>
        <text v-else-if="!hasWechat" class="act act-bind" @click="handleBindWechat">绑定</text>
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
    <button class="danger" @click="handleDeleteAccount">注销账号</button>

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
.section-title {
  font-size: 24rpx;
  color: #9ca3af;
  padding: 8rpx 12rpx 12rpx;
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
.sec-item {
  align-items: center;
}
.sec-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.sec-sub {
  font-size: 24rpx;
  color: #9ca3af;
}
.mi-ic {
  font-size: 36rpx;
}
.mi-label {
  flex: 1;
  font-size: 30rpx;
  color: #1f2937;
}
.act {
  font-size: 26rpx;
  padding: 8rpx 24rpx;
  border-radius: 999rpx;
}
.act-bind {
  color: #16a34a;
  border: 1rpx solid #16a34a;
}
.act-unbind {
  color: #6b7280;
  border: 1rpx solid #d1d5db;
}
.bind-panel {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
  padding: 8rpx 0 28rpx;
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
  color: #16a34a;
  border: 1px solid #16a34a;
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
  background: #16a34a;
  color: #fff;
  border-radius: 44rpx;
  font-size: 30rpx;
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
.danger {
  margin-top: 20rpx;
  background: #fff;
  color: #dc2626;
  border: 1rpx solid #f0b4b4;
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
