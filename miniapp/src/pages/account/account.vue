<script setup>
import { computed, ref, onUnmounted } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useAuthStore, getWxLoginCode } from '../../stores/auth'
import { sendCode, bindEmail, bindWechat, unbind, deleteAccount, updateNickname, updateProfile } from '../../api/auth'
import { AVATAR_COLORS, avatarColorOf, avatarInitial } from '../../utils/avatar'
const auth = useAuthStore()

const nickname = computed(() => auth.user?.nickname || '有余用户')
const userId = computed(() => auth.user?.id || '')

// 头像颜色（用户自选，回退品牌绿）与性别（MALE/FEMALE/''=保密）。
const avatarColor = computed(() => avatarColorOf(auth.user?.avatarColor))
const gender = computed(() => auth.user?.gender || '')

// 头像颜色弹窗：点头像上的编辑标识打开，选色后即保存并关闭。
const colorSheet = ref(false)
async function pickAvatarColor(c) {
  if (c === auth.user?.avatarColor) {
    colorSheet.value = false
    return
  }
  try {
    await updateProfile({ avatarColor: c })
    await auth.refreshUser().catch(() => {})
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  } finally {
    colorSheet.value = false
  }
}
async function setGender(g) {
  if (g === (auth.user?.gender || '')) return
  try {
    await updateProfile({ gender: g })
    await auth.refreshUser().catch(() => {})
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  }
}

// 性别挪到头卡，用 ♂/♀ 图标表示男女、保密用文字；点开底部选择。
const genderSheet = ref(false)
const genderText = computed(() => (gender.value === 'MALE' ? '♂' : gender.value === 'FEMALE' ? '♀' : '保密'))
const genderClass = computed(() => (gender.value === 'MALE' ? 'male' : gender.value === 'FEMALE' ? 'female' : 'secret'))
function chooseGender(g) {
  genderSheet.value = false
  setGender(g)
}

// 加入天数：以套餐起始时间近似（无则不展示）。
const joinDays = computed(() => {
  const s = auth.user?.planStartedAt
  if (!s) return null
  const start = new Date(String(s).replace(' ', 'T'))
  if (isNaN(start.getTime())) return null
  const d = Math.floor((Date.now() - start.getTime()) / 86400000)
  return d >= 0 ? d : null
})

function copyUserId() {
  if (!userId.value) return
  uni.setClipboardData({
    data: String(userId.value),
    success() {
      uni.showToast({ title: '已复制用户 ID', icon: 'none' })
    }
  })
}
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

// ---- 修改昵称 ----
function editNickname() {
  uni.showModal({
    title: '修改昵称',
    editable: true,
    placeholderText: '1-64 个字符',
    content: nickname.value === '有余用户' ? '' : nickname.value,
    success: async (r) => {
      if (!r.confirm) return
      const nn = (r.content || '').trim()
      if (!nn || nn.length > 64) {
        uni.showToast({ title: '昵称长度需为 1 到 64 个字符', icon: 'none' })
        return
      }
      try {
        await updateNickname(nn)
        await auth.refreshUser().catch(() => {})
        uni.showToast({ title: '已保存', icon: 'success' })
      } catch (err) {
        uni.showToast({ title: err.message || '保存失败', icon: 'none' })
      }
    }
  })
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
    <view class="wrap">
      <!-- 身份卡：白底轻量。点头像换头像色、点昵称改名 -->
      <view class="profile">
        <view class="avatar-wrap" @click="colorSheet = true">
          <view class="avatar" :style="{ background: avatarColor }">{{ avatarInitial(nickname) }}</view>
          <view class="avatar-edit"><AppIcon name="edit" :size="20" color="#5b6470" /></view>
        </view>
        <view class="pinfo">
          <view class="nameline">
            <text class="name" @click="editNickname">{{ nickname }}</text>
            <AppIcon name="edit" :size="26" color="#c0c4cc" @click="editNickname" />
            <view class="gchip" :class="genderClass" @click="genderSheet = true">{{ genderText }}</view>
          </view>
          <text class="planchip" :class="{ pro: plan !== '免费版' }">{{ plan }}</text>
        </view>
      </view>
      <!-- 登录方式 -->
      <view class="sect">登录方式（至少保留一种）</view>
      <view class="card">
        <!-- 邮箱 -->
        <view class="lrow">
          <view class="tile mail"><AppIcon name="mail" :size="34" color="#5b6470" /></view>
          <view class="rmain">
            <text class="rt">邮箱</text>
            <text class="rsub">{{ hasEmail ? email : '未绑定' }}</text>
          </view>
          <view class="rright">
            <text v-if="hasEmail" class="okpill">✓ 已绑定</text>
            <text v-if="hasEmail && canUnbind" class="pill unbind" @click="handleUnbind('email')">解绑</text>
            <text v-else-if="!hasEmail" class="pill bind" @click="toggleBindEmail">绑定</text>
          </view>
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
        <view class="lrow">
          <view class="tile wechat"><AppIcon name="chat" :size="34" color="#5b6470" /></view>
          <view class="rmain">
            <text class="rt">微信</text>
            <text class="rsub">{{ hasWechat ? '已绑定' : '未绑定' }}</text>
          </view>
          <view class="rright">
            <text v-if="hasWechat" class="okpill">✓ 已绑定</text>
            <text v-if="hasWechat && canUnbind" class="pill unbind" @click="handleUnbind('wechat')">解绑</text>
            <text v-else-if="!hasWechat" class="pill bind" @click="handleBindWechat">绑定</text>
          </view>
        </view>
      </view>

      <!-- 账号信息 -->
      <view class="sect">账号信息</view>
      <view class="card">
        <view class="lrow" @click="copyUserId">
          <view class="tile id"><AppIcon name="badge" :size="34" color="#5b6470" /></view>
          <view class="rmain"><text class="rt">用户 ID</text><text class="rsub">反馈问题时提供给客服</text></view>
          <text class="copy">#{{ userId }} · 复制</text>
        </view>
        <view v-if="joinDays !== null" class="lrow">
          <view class="tile join"><AppIcon name="calendar" :size="34" color="#5b6470" /></view>
          <view class="rmain"><text class="rt">加入有余</text></view>
          <text class="rval">{{ joinDays }} 天</text>
        </view>
      </view>

      <!-- 危险操作 -->
      <view class="sect">危险操作</view>
      <view class="card">
        <view class="lrow" @click="handleDeleteAccount">
          <view class="tile danger"><AppIcon name="warning" :size="34" color="#e5563d" /></view>
          <view class="rmain"><text class="rt danger">注销账号</text><text class="rsub">永久删除账号与全部数据，不可恢复</text></view>
          <text class="arrow">›</text>
        </view>
      </view>

      <view class="logout" @click="logout">退出登录</view>
      <view style="height:40rpx;"></view>
    </view>

    <!-- 性别选择弹层 -->
    <view v-if="genderSheet" class="mask" @click="genderSheet = false">
      <view class="sheet" @click.stop>
        <view class="sheet-h">
          <text class="sheet-t">性别</text>
          <text class="sheet-x" @click="genderSheet = false">✕</text>
        </view>
        <view class="gopts">
          <view class="gopt" :class="{ on: gender === 'MALE' }" @click="chooseGender('MALE')">
            <text class="gsym male">♂</text><text class="glabel">男</text>
          </view>
          <view class="gopt" :class="{ on: gender === 'FEMALE' }" @click="chooseGender('FEMALE')">
            <text class="gsym female">♀</text><text class="glabel">女</text>
          </view>
          <view class="gopt" :class="{ on: gender === '' }" @click="chooseGender('')">
            <text class="gsym secret">–</text><text class="glabel">保密</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 头像颜色选择弹层 -->
    <view v-if="colorSheet" class="mask" @click="colorSheet = false">
      <view class="sheet" @click.stop>
        <view class="sheet-h">
          <text class="sheet-t">头像颜色</text>
          <text class="sheet-x" @click="colorSheet = false">✕</text>
        </view>
        <view class="sheet-prev">
          <view class="sp-av" :style="{ background: avatarColor }">{{ avatarInitial(nickname) }}</view>
          <text class="sheet-s">在家庭账本里区分你的记账</text>
        </view>
        <view class="dots">
          <view
            v-for="c in AVATAR_COLORS"
            :key="c"
            class="dot"
            :class="{ on: c === avatarColor }"
            :style="{ background: c }"
            @click="pickAvatarColor(c)"
          >{{ c === avatarColor ? '✓' : '' }}</view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; background: #eef0f2; }

/* 身份卡（白底轻量，替代绿色大头块） */
.profile { display: flex; align-items: center; gap: 22rpx; background: #fff; border-radius: 24rpx; padding: 28rpx 26rpx; margin-top: 24rpx; box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05); }
.avatar { width: 104rpx; height: 104rpx; border-radius: 50%; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 44rpx; font-weight: 800; flex: 0 0 auto; }
.pinfo { flex: 1; min-width: 0; }
.nameline { display: flex; align-items: center; gap: 10rpx; }
.name { font-size: 38rpx; font-weight: 800; color: #16181c; max-width: 380rpx; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.planchip { display: inline-block; margin-top: 12rpx; font-size: 22rpx; font-weight: 700; background: #eef0f2; color: #6b7280; border-radius: 999rpx; padding: 4rpx 18rpx; }
.planchip.pro { background: #fdf3d6; color: #a9791a; }

.wrap { padding: 4rpx 24rpx 24rpx; }
.sect { font-size: 24rpx; font-weight: 700; color: #9aa2ad; padding: 26rpx 8rpx 14rpx; }

/* 头像编辑标识：右下角小圆钮 */
.avatar-wrap { position: relative; flex: 0 0 auto; }
.avatar-edit { position: absolute; right: -4rpx; bottom: -4rpx; width: 40rpx; height: 40rpx; border-radius: 50%; background: #fff; border: 2rpx solid rgba(255,255,255,0.9); display: flex; align-items: center; justify-content: center; box-shadow: 0 4rpx 10rpx rgba(20,24,28,0.18); }

/* 头卡性别 chip */
.gchip { min-width: 40rpx; height: 40rpx; padding: 0 14rpx; border-radius: 999rpx; display: inline-flex; align-items: center; justify-content: center; font-size: 28rpx; font-weight: 800; line-height: 1; }
.gchip.male { background: #e8f1ff; color: #3a7afe; }
.gchip.female { background: #fdecf3; color: #e0609a; }
.gchip.secret { background: #f2f4f6; color: #9aa2ad; font-size: 22rpx; font-weight: 600; }

/* 性别选择弹层 */
.gopts { display: flex; gap: 20rpx; padding: 20rpx 6rpx 12rpx; }
.gopt { flex: 1; display: flex; flex-direction: column; align-items: center; gap: 10rpx; padding: 24rpx 0; border-radius: 18rpx; background: #f6f7f9; border: 2rpx solid transparent; }
.gopt.on { border-color: #12a150; background: #e6f6ec; }
.gsym { font-size: 48rpx; font-weight: 800; line-height: 1; }
.gsym.male { color: #3a7afe; }
.gsym.female { color: #e0609a; }
.gsym.secret { color: #9aa2ad; }
.glabel { font-size: 26rpx; color: #5b6470; font-weight: 700; }

/* 头像颜色弹窗（底部弹层） */
.mask { position: fixed; inset: 0; background: rgba(20,24,28,0.45); z-index: 900; display: flex; align-items: flex-end; }
.sheet { width: 100%; background: #fff; border-radius: 28rpx 28rpx 0 0; padding: 28rpx 30rpx calc(28rpx + env(safe-area-inset-bottom)); }
.sheet-h { display: flex; align-items: center; justify-content: space-between; }
.sheet-t { font-size: 32rpx; font-weight: 800; }
.sheet-x { font-size: 34rpx; color: #9aa2ad; padding: 4rpx 12rpx; }
.sheet-prev { display: flex; align-items: center; gap: 18rpx; margin: 20rpx 0 8rpx; }
.sp-av { width: 84rpx; height: 84rpx; border-radius: 50%; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 38rpx; font-weight: 800; flex: 0 0 auto; }
.sheet-s { font-size: 24rpx; color: #9aa2ad; }
.dots { display: flex; flex-wrap: wrap; gap: 28rpx; padding: 24rpx 6rpx 12rpx; }
.dot { width: 88rpx; height: 88rpx; border-radius: 50%; display: flex; align-items: center; justify-content: center; color: #fff; font-size: 38rpx; font-weight: 800; }
.dot.on { box-shadow: 0 0 0 6rpx #fff, 0 0 0 10rpx var(--c-brand, #12a150); }

.card { background: #fff; border-radius: 24rpx; overflow: hidden; box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05); }
.lrow { display: flex; align-items: center; gap: 22rpx; padding: 26rpx 28rpx; border-top: 1rpx solid #eef0f2; }
.card .lrow:first-child { border-top: none; }
.tile { width: 76rpx; height: 76rpx; border-radius: 22rpx; display: flex; align-items: center; justify-content: center; flex: 0 0 auto; }
.tile.mail, .tile.wechat, .tile.id, .tile.join { background: #f2f4f6; }
.tile.danger { background: #fdeceb; }
.rmain { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.rt { font-size: 30rpx; font-weight: 600; color: #25292e; }
.rt.danger { color: #e5563d; }
.rsub { font-size: 24rpx; color: #9aa2ad; }
.rright { display: flex; align-items: center; gap: 16rpx; flex: 0 0 auto; }
.rval { font-size: 26rpx; color: #9aa2ad; }
.okpill { font-size: 24rpx; font-weight: 600; color: #0e8a44; }
.pill { font-size: 26rpx; font-weight: 700; padding: 10rpx 30rpx; border-radius: 999rpx; }
.pill.bind { color: #fff; background: #12a150; }
.pill.unbind { color: #9aa2ad; padding: 10rpx 12rpx; }
.copy { font-size: 24rpx; color: #0e8a44; border: 1rpx solid #12a150; border-radius: 999rpx; padding: 6rpx 18rpx; flex: 0 0 auto; }
.arrow { color: #c7ccd2; font-size: 34rpx; }

/* 绑定邮箱面板 */
.bind-panel { display: flex; flex-direction: column; gap: 20rpx; padding: 8rpx 28rpx 28rpx; border-top: 1rpx solid #eef0f2; }
.field { background: #f5f6f5; border-radius: 14rpx; padding: 24rpx; font-size: 30rpx; }
.code-row { display: flex; align-items: center; gap: 20rpx; }
.code-field { flex: 1; }
.code-btn { flex-shrink: 0; width: 200rpx; background: #fff; color: #12a150; border: 1px solid #12a150; border-radius: 14rpx; font-size: 26rpx; padding: 0; line-height: 78rpx; height: 78rpx; }
.code-btn[disabled] { color: #9ca3af; border-color: #e5e7eb; background: #f3f4f6; }
.confirm-btn { background: #12a150; color: #fff; border-radius: 44rpx; font-size: 30rpx; }

.logout { margin-top: 30rpx; background: #fff; border-radius: 18rpx; text-align: center; padding: 28rpx; color: #e5563d; font-size: 30rpx; font-weight: 700; box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05); }
</style>
