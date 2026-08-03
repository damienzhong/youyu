<script setup>
import { ref, computed, onUnmounted } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { useAuthStore } from '../../stores/auth'
import { sendCode } from '../../api/auth'
import { listAccounts } from '../../api/account'
import { fetchInviterBrief } from '../../api/invite'
import {
  INVITE_LANDING_STATE,
  resolveInviteLanding,
  savePendingInviteCode
} from '../../utils/invite'

/**
 * 邀请落地页（需求 2.4、2.5、3.3、4.1、4.3、4.5、4.9、4.10、4.11）。
 *
 * 三个互斥页面态由 utils/invite.js 的纯函数 resolveInviteLanding 一次定死，页面只负责渲染：
 * - INVITER_SHOWN：写暂存 → 查邀请人昵称；昵称非空展示昵称，为空展示通用邀请提示
 * - DEFAULT：参数缺失/非法，或查询 404 / 429 / 网络错误 / 5s 超时。刻意不显示任何错误——
 *   邀请码的任何问题都不该让一个想注册的人看到报错；已写入的暂存与写入时刻保持不变（需求 4.5）
 * - LOGGED_IN：已登录提示 + 回到首页入口，不写也不改暂存（需求 4.9），且不发查询
 *
 * 登录入口仅复用既有两种方式（微信一键、邮箱验证码登录/注册合一），不新增任何注册方式（需求 4.10）。
 */

const auth = useAuthStore()
const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'

/** 邀请人展示信息查询的客户端等待上限（需求 4.5）。 */
const INVITER_QUERY_TIMEOUT_MS = 5000

const state = ref(INVITE_LANDING_STATE.DEFAULT)
const inviteCode = ref('')
const inviterNickname = ref('')

const isInviterShown = computed(() => state.value === INVITE_LANDING_STATE.INVITER_SHOWN)
const isLoggedInState = computed(() => state.value === INVITE_LANDING_STATE.LOGGED_IN)
/** 昵称为空值时展示不含昵称的通用邀请提示（需求 4.3）。 */
const hasNickname = computed(() => !!inviterNickname.value)
const inviterInitial = computed(() => (hasNickname.value ? inviterNickname.value.slice(0, 1) : '友'))

onLoad((options) => {
  // 解析（decodeURIComponent → trim → 大写）与「是否查询 / 是否写暂存」的判定都在纯函数里，
  // 页面不重复判定，避免两处规则漂移（Property 16 覆盖该纯逻辑）。
  const decided = resolveInviteLanding(options, auth.isLoggedIn)
  inviteCode.value = decided.code
  state.value = decided.state

  if (decided.shouldPersist) {
    // 写入失败（存储不可用）也照常展示两个登录入口，绝不阻断注册主路径（需求 4.13）。
    savePendingInviteCode(decided.code)
  }
  if (decided.shouldQuery) {
    loadInviterBrief(decided.code)
  }
})

/** 给公开查询加客户端超时：超过 5s 一律按失败降级为 DEFAULT（需求 4.5）。 */
function withTimeout(promise, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject({ code: 'TIMEOUT', message: '请求超时' }), ms)
    promise.then(
      (v) => {
        clearTimeout(timer)
        resolve(v)
      },
      (e) => {
        clearTimeout(timer)
        reject(e)
      }
    )
  })
}

/**
 * 查邀请人昵称。任何失败（NOT_FOUND / INVITE_RATE_LIMITED / 网络错误 / 超时）都退回 DEFAULT，
 * 不弹 toast、不显示错误文案，且不动已写入的暂存与写入时刻（需求 4.5）。
 */
async function loadInviterBrief(code) {
  try {
    const res = await withTimeout(fetchInviterBrief(code), INVITER_QUERY_TIMEOUT_MS)
    const nickname = res && res.nickname ? String(res.nickname).trim() : ''
    inviterNickname.value = nickname
    state.value = INVITE_LANDING_STATE.INVITER_SHOWN
  } catch (e) {
    inviterNickname.value = ''
    state.value = INVITE_LANDING_STATE.DEFAULT
  }
}

// ---- 登录入口：与 pages/login/login.vue 完全同构的两种方式 ----

const loading = ref(false)

// 登录后路由：无账户且未走过引导的新用户 → 新手引导；否则进首页（与登录页一致）。
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
    // 邀请码的携带由 stores/auth.js 从暂存中取，对本页透明（需求 4.6）。
    await auth.loginWithWeixin()
    await routeAfterLogin()
  } catch (e) {
    uni.showToast({ title: e.message || '登录失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

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

function goHome() {
  uni.reLaunch({ url: '/pages/index/index' })
}
</script>

<template>
  <view class="land" :style="{ paddingTop: `calc(${statusBarHeight} + 56rpx)` }">
    <!-- 品牌区（三态共用） -->
    <view class="brand">
      <view class="logo">有</view>
      <text class="nm">有余</text>
      <text class="sl">把钱记清楚，把日子过明白</text>
    </view>

    <!-- INVITER_SHOWN：邀请人卡片 + 绑定说明条 -->
    <template v-if="isInviterShown">
      <view class="inviter">
        <view class="av">{{ inviterInitial }}</view>
        <view class="m">
          <text class="l1">邀请你的好友</text>
          <text v-if="hasNickname" class="l2"><text class="em">{{ inviterNickname }}</text> 邀请你一起记账</text>
          <text v-else class="l2">有好友邀请你一起记账</text>
        </view>
      </view>
      <view class="bind">
        <text class="bind-t">注册成功后自动记录这层邀请关系</text>
        <text class="bind-d">无需手动填码。已有账号登录不会绑定。</text>
      </view>
    </template>

    <!-- LOGGED_IN：置灰邀请人卡片 + 已登录说明 + 回到首页 -->
    <template v-else-if="isLoggedInState">
      <view v-if="inviteCode" class="inviter dim">
        <view class="av gy">友</view>
        <view class="m">
          <text class="l1">邀请你的好友</text>
          <text class="l2">有好友邀请你一起记账</text>
        </view>
      </view>
      <view class="logged">
        <text class="t">你已登录有余</text>
        <text class="d">邀请关系只在注册新账号时建立，你的账号已存在，本次不会绑定。</text>
        <view class="cta" @click="goHome">回到首页</view>
      </view>
      <view class="lgn">
        <text class="tos">如需邀请好友，可在「我的 → 邀请好友」生成你自己的邀请码</text>
      </view>
    </template>

    <!-- DEFAULT：通用欢迎卡（不显示任何错误） -->
    <template v-else>
      <view class="welcome">
        <text class="t">欢迎使用有余</text>
        <text class="d">登录后即可开始记账，不影响任何功能使用</text>
      </view>
    </template>

    <!-- 登录入口：仅微信一键与邮箱验证码两种（需求 4.10） -->
    <view v-if="!isLoggedInState" class="lgn">
      <button class="wx" :loading="loading" @click="handleWxLogin">微信一键登录 / 注册</button>

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

      <text class="tos">登录即表示同意用户协议与隐私政策</text>
    </view>
  </view>
</template>

<style scoped>
.land {
  min-height: 100vh;
  box-sizing: border-box;
  padding: 0 44rpx calc(52rpx + env(safe-area-inset-bottom));
  background: linear-gradient(170deg, #e9f8ef 0%, #f2f4f6 42%);
  display: flex;
  flex-direction: column;
}

/* 品牌区 */
.brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin-bottom: 44rpx;
}
.logo {
  width: 120rpx;
  height: 120rpx;
  border-radius: 36rpx;
  background: linear-gradient(135deg, #18b85a, #0e8a44);
  color: #fff;
  font-size: 56rpx;
  font-weight: 800;
  text-align: center;
  line-height: 120rpx;
  box-shadow: 0 20rpx 48rpx rgba(18, 161, 80, 0.32);
  margin-bottom: 20rpx;
}
.nm {
  font-size: 38rpx;
  font-weight: 800;
  color: #25292e;
}
.sl {
  margin-top: 8rpx;
  font-size: 24rpx;
  color: #6b7280;
}

/* 邀请人卡片 */
.inviter {
  background: #fff;
  border-radius: 32rpx;
  padding: 32rpx;
  display: flex;
  align-items: center;
  gap: 24rpx;
  box-shadow: 0 16rpx 44rpx rgba(20, 24, 28, 0.06);
  margin-bottom: 28rpx;
}
.inviter.dim {
  opacity: 0.55;
}
.av {
  width: 84rpx;
  height: 84rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, #18b85a, #0e8a44);
  color: #fff;
  font-size: 32rpx;
  font-weight: 700;
  text-align: center;
  line-height: 84rpx;
  flex-shrink: 0;
}
.av.gy {
  background: #cfd4da;
}
.m {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.l1 {
  font-size: 24rpx;
  color: #9aa2ad;
}
.l2 {
  margin-top: 6rpx;
  font-size: 31rpx;
  font-weight: 700;
  color: #25292e;
}
.em {
  color: #12a150;
}

/* 绑定说明条 */
.bind {
  background: #fff8e9;
  border: 1rpx solid #f6e2b8;
  border-radius: 24rpx;
  padding: 22rpx 26rpx;
  margin-bottom: 28rpx;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.bind-t {
  font-size: 24rpx;
  font-weight: 700;
  color: #7a5410;
}
.bind-d {
  font-size: 23rpx;
  color: #946a1c;
  line-height: 1.65;
}

/* DEFAULT 通用欢迎卡 */
.welcome {
  background: #fff;
  border-radius: 32rpx;
  padding: 40rpx 36rpx;
  text-align: center;
  box-shadow: 0 16rpx 44rpx rgba(20, 24, 28, 0.06);
  display: flex;
  flex-direction: column;
  gap: 12rpx;
}
.welcome .t {
  font-size: 30rpx;
  font-weight: 700;
  color: #25292e;
}
.welcome .d {
  font-size: 25rpx;
  color: #9aa2ad;
  line-height: 1.7;
}

/* LOGGED_IN 卡片 */
.logged {
  background: #fff;
  border-radius: 32rpx;
  padding: 44rpx 36rpx;
  text-align: center;
  box-shadow: 0 16rpx 44rpx rgba(20, 24, 28, 0.06);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
}
.logged .t {
  font-size: 30rpx;
  font-weight: 700;
  color: #25292e;
}
.logged .d {
  font-size: 25rpx;
  color: #9aa2ad;
  line-height: 1.7;
}
.cta {
  margin-top: 20rpx;
  width: 100%;
  background: #12a150;
  color: #fff;
  border-radius: 26rpx;
  padding: 26rpx 0;
  font-size: 30rpx;
  font-weight: 700;
  text-align: center;
  box-shadow: 0 16rpx 36rpx rgba(18, 161, 80, 0.3);
}

/* 登录区（沉在底部） */
.lgn {
  margin-top: auto;
  padding-top: 48rpx;
}
.wx {
  background: #12a150;
  color: #fff;
  border-radius: 26rpx;
  font-size: 31rpx;
  font-weight: 700;
  box-shadow: 0 16rpx 36rpx rgba(18, 161, 80, 0.3);
}
.divider {
  margin-top: 28rpx;
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.divider .line {
  flex: 1;
  height: 1rpx;
  background: #e0e5e9;
}
.divider-text {
  font-size: 24rpx;
  color: #9aa2ad;
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
  margin-top: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}
.field {
  background: #fff;
  border-radius: 18rpx;
  padding: 26rpx;
  font-size: 29rpx;
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
  border: 1rpx solid #12a150;
  border-radius: 18rpx;
  font-size: 26rpx;
  padding: 0;
  line-height: 82rpx;
  height: 82rpx;
}
.code-btn[disabled] {
  color: #9aa2ad;
  border-color: #e4e7ea;
  background: #f3f4f6;
}
.email-btn {
  background: #fff;
  color: #25292e;
  border: 1rpx solid #e4e7ea;
  border-radius: 26rpx;
  font-size: 30rpx;
  font-weight: 600;
}
.tos {
  display: block;
  margin-top: 28rpx;
  text-align: center;
  font-size: 22rpx;
  color: #9aa2ad;
  line-height: 1.6;
}
</style>
