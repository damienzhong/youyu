<script setup>
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useAuthStore } from '../../stores/auth'
import { useThemeStore } from '../../stores/theme'
import { useLedgerStore } from '../../stores/ledger'
import { useSyncStore } from '../../stores/sync'
import { avatarColorOf } from '../../utils/avatar'
import { fetchInviteInfo } from '../../api/invite'
import { fetchGrowthOverview } from '../../api/growth'
import { levelProgress } from '../../utils/growth'
import { formatAmount } from '../../utils/format'

const auth = useAuthStore()
const themeStore = useThemeStore()
const ledgerStore = useLedgerStore()
const syncStore = useSyncStore()
// 同步中心角标：优先显示需处理数，其次待同步数；均为 0 时不显示。
const syncBadge = computed(() => {
  if (syncStore.failedCount > 0) return `${syncStore.failedCount} 待处理`
  if (syncStore.pendingCount > 0) return `${syncStore.pendingCount} 待同步`
  return ''
})
const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'

// 已邀请人数：null 表示尚未取到（含请求失败），此时入口只显示标题与箭头（需求 2.6）
const invitedCount = ref(null)

// 成长概览（等级 / 经验 / 累计统计 / 徽章）：null 表示尚未取到（含请求失败）。
// 页头统计条与成长卡的取值全部由这一份概览派生，失败时统一降级为「—」，不渲染假的 0。
const growth = ref(null)

const nickname = computed(() => auth.user?.nickname || '有余用户')
const planLabel = computed(() => {
  const p = auth.user?.plan
  return p === 'pro' ? '专业版' : p === 'lifetime' ? '终身版' : '免费版'
})
const planExpires = computed(() => (auth.user?.planExpiresAt || '').slice(0, 10))
const avatarColor = computed(() => avatarColorOf(auth.user?.avatarColor))

// ---- 页头统计条：记账天数 / 累计笔数 / 当前连续（均来自成长概览）----
// 非负有限数才取，否则返回 null（渲染成「—」，不展示误导性的 0）。
function metric(v) {
  const n = Number(v)
  return Number.isFinite(n) && n >= 0 ? n : null
}
const statDays = computed(() => metric(growth.value?.totalRecordDays))
const statCount = computed(() => metric(growth.value?.totalRecordCount))
const statStreak = computed(() => metric(growth.value?.currentStreakDays))
function statText(n) {
  return n === null ? '—' : n.toLocaleString('en-US')
}

// ---- 成长卡 ----
const level = computed(() => {
  const n = Number(growth.value?.level)
  return Number.isFinite(n) && n >= 1 ? n : null
})
const maxLevelReached = computed(() => growth.value?.maxLevelReached === true)
const expToNextLevel = computed(() => {
  const n = Number(growth.value?.expToNextLevel)
  return Number.isFinite(n) && n >= 0 ? n : 0
})
// 升级进度 0–100%，交给纯函数处理满级 / 分母<=0 / 畸形字段等边界（需求 13.5、13.6）。
const progressPct = computed(() => Math.round(levelProgress(growth.value) * 100) + '%')
const levelHint = computed(() => {
  if (level.value === null) return '记一笔，开启成长'
  if (maxLevelReached.value) return '已达最高等级'
  return `还差 ${expToNextLevel.value} 经验升到 Lv${level.value + 1}`
})
const expText = computed(() => statText(metric(growth.value?.exp)))
// 徽章：已点亮 / 总数（按概览返回的 badges 派生，不额外发成就请求）。
const badges = computed(() => (Array.isArray(growth.value?.badges) ? growth.value.badges : []))
const badgeText = computed(() => {
  if (!badges.value.length) return '—'
  const unlocked = badges.value.filter((b) => b && b.unlocked === true).length
  return `${unlocked} / ${badges.value.length}`
})
const totalExpenseText = computed(() =>
  growth.value ? '¥' + formatAmount(growth.value?.totalExpense) : '—'
)

onShow(() => {
  uni.hideTabBar({ animation: false, fail() {} })
  if (!auth.isLoggedIn) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  syncStore.refresh()
  auth.refreshUser().catch(() => {})
  // 人数只是锦上添花：失败静默（不弹错误、不影响页面其余部分），入口保持只有标题与箭头。
  fetchInviteInfo()
    .then((res) => {
      const n = Number(res?.invitedCount)
      invitedCount.value = Number.isFinite(n) && n >= 0 ? n : null
    })
    .catch(() => {
      invitedCount.value = null
    })
  // 成长概览驱动页头统计条与成长卡；失败静默降级为「—」（不渲染假数据）。
  // 注意：GET /api/growth 是写入型 GET（服务端在本请求内顺带结算），因此每次进入「我的」页都会
  // 触发一次结算尝试；服务端 10 秒结算节流（需求 10.14）正是为这类调用点准备的，用户在「我的」与
  // 成长页之间来回切换实际只会结算一次。不要把这次调用挪到比 onShow 更高频的时机（例如每次 tab 切换）。
  fetchGrowthOverview()
    .then((res) => {
      growth.value = res || null
    })
    .catch(() => {
      growth.value = null
    })
})

// 分组列表（静态入口）：账本 / 预算 / 分类 / 借贷 等管理入口已在「账本」页快捷栏与「更多」中提供，
// 「我的」不再重复，聚焦账号、成长、通知、数据与设置。
const tools = [
  { key: 'bills', icon: 'import', label: '账单导入', desc: '支付宝 / 微信', url: '/pages/billimport/billimport' },
  { key: 'data', icon: 'export', label: '数据导出 / 导入', desc: '', url: '/pages/data/data' },
  { key: 'recycle', icon: 'recycle', label: '回收站', desc: '30 天可恢复', url: '/pages/recycle/recycle' },
  { key: 'labels', icon: 'folder', label: '项目 / 商家 / 标签', desc: '', url: '/pages/labels/labels' }
]

function go(url) {
  uni.navigateTo({ url })
}
function goGrowth() {
  uni.navigateTo({ url: '/pages/growth/growth' })
}
function goTheme() {
  uni.navigateTo({ url: '/pages/theme/theme' })
}
function goAccount() {
  uni.navigateTo({ url: '/pages/account/account' })
}
function about() {
  uni.showModal({ title: '有余', content: '记好每一笔，日子更有余\n版本 v0.1.2', showCancel: false })
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
  <view class="page" :style="themeStore.current.vars">
    <!-- 沉浸式页头：个人卡 + 成就统计条 -->
    <view class="hero" :style="{ paddingTop: `calc(${statusBarHeight} + 28rpx)` }">
      <view class="id" @click="goAccount">
        <view class="avatar" :style="{ background: avatarColor }">{{ nickname.slice(0, 1) }}</view>
        <view class="id-main">
          <view class="id-name">{{ nickname }} <text class="id-badge">{{ planLabel }}</text></view>
          <text class="id-sub">管理账号与登录方式</text>
        </view>
        <text class="id-arrow">›</text>
      </view>
      <view class="stats">
        <view class="st"><text class="n">{{ statText(statDays) }}</text><text class="l">记账天数</text></view>
        <view class="st"><text class="n">{{ statText(statCount) }}</text><text class="l">累计笔数</text></view>
        <view class="st"><text class="n">{{ statText(statStreak) }}</text><text class="l">当前连续</text></view>
      </view>
    </view>

    <view class="wrap">
      <!-- 成长卡：压住页头下沿，单绿点缀 -->
      <view class="growth" @click="goGrowth">
        <view class="g-top">
          <view class="ring" :style="{ background: `conic-gradient(var(--c-brand, #12a150) 0 ${progressPct}, #eef0ea 0)` }">
            <text class="ring-in">{{ level === null ? 'Lv—' : 'Lv' + level }}</text>
          </view>
          <view class="g-mid">
            <text class="g-title">我的成长</text>
            <view class="g-bar"><view class="g-bar-in" :style="{ width: progressPct }"></view></view>
            <text class="g-hint">{{ levelHint }}</text>
          </view>
          <text class="g-arrow">›</text>
        </view>
        <view class="g-foot">
          <view class="gf"><text class="n">{{ expText }}</text><text class="l">累计经验</text></view>
          <view class="gf"><text class="n">{{ badgeText }}</text><text class="l">已点亮徽章</text></view>
          <view class="gf"><text class="n">{{ totalExpenseText }}</text><text class="l">累计支出</text></view>
        </view>
      </view>

      <!-- 会员卡：套餐状态 + 有效期 + 价值主张（无付费墙，纯状态展示） -->
      <view class="memcard">
        <view class="mem-ic"><AppIcon name="diamond" :size="40" color="#a9781a" /></view>
        <view class="mem-main">
          <text class="mem-plan">{{ planLabel }}</text>
          <text class="mem-sub">干净无广告 · 数据随时导出<text v-if="planExpires"> · 有效期至 {{ planExpires }}</text></text>
        </view>
      </view>

      <!-- 常用 -->
      <view class="sect">常用</view>
      <view class="card">
        <view class="row" @click="go('/pages/invite/invite')">
          <view class="r-ic on"><AppIcon name="members" :size="36" /></view>
          <text class="r-t">邀请好友</text>
          <text v-if="invitedCount !== null" class="r-v r-v-hot">已邀请 {{ invitedCount }} 人</text>
          <text class="arrow">›</text>
        </view>
        <view class="row" @click="go('/pages/reminder/reminder')">
          <view class="r-ic"><AppIcon name="bell" :size="36" /></view>
          <text class="r-t">记账提醒</text>
          <text class="arrow">›</text>
        </view>
        <view class="row" @click="go('/pages/recurring/recurring')">
          <view class="r-ic"><AppIcon name="calendar" :size="36" /></view>
          <text class="r-t">周期记账</text>
          <text class="arrow">›</text>
        </view>
        <view class="row" @click="go('/pages/sync/sync')">
          <view class="r-ic"><AppIcon name="import" :size="36" /></view>
          <text class="r-t">同步中心</text>
          <text v-if="syncBadge" class="r-v">{{ syncBadge }}</text>
          <text class="arrow">›</text>
        </view>
      </view>

      <!-- 数据与工具 -->
      <view class="sect">数据与工具</view>
      <view class="card">
        <view v-for="it in tools" :key="it.key" class="row" @click="go(it.url)">
          <view class="r-ic"><AppIcon :name="it.icon" :size="36" /></view>
          <text class="r-t">{{ it.label }}</text>
          <text v-if="it.desc" class="r-v">{{ it.desc }}</text>
          <text class="arrow">›</text>
        </view>
      </view>

      <!-- 个性化与关于 -->
      <view class="sect">个性化与关于</view>
      <view class="card">
        <view class="row" @click="goTheme">
          <view class="r-ic"><AppIcon name="star" :size="36" /></view>
          <text class="r-t">主题皮肤</text>
          <text class="r-v r-v-hot">{{ themeStore.current.name }}</text>
          <text class="arrow">›</text>
        </view>
        <view class="row" @click="about">
          <view class="r-ic"><AppIcon name="info" :size="36" /></view>
          <text class="r-t">关于有余</text>
          <text class="r-v">v0.1.2</text>
          <text class="arrow">›</text>
        </view>
      </view>

      <view class="logout" @click="logout">退出登录</view>

      <view style="height:180rpx;"></view>
    </view>
    <TabBar active="me" />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--c-page-bg, #f2f4f6);
}
.wrap { padding: 0 24rpx; }

/* 沉浸式页头 */
.hero {
  background: var(--c-hero, linear-gradient(135deg, #22c55e, #0f8a45 70%));
  padding: 30rpx 30rpx 56rpx;
  color: #fff;
  position: relative;
  overflow: hidden;
}
.hero::after {
  content: '';
  position: absolute;
  right: -60rpx; top: -50rpx;
  width: 300rpx; height: 300rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
}
.id, .stats { position: relative; z-index: 2; }
.id {
  display: flex;
  align-items: center;
  gap: 24rpx;
}
.avatar {
  width: 92rpx;
  height: 92rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.24);
  text-align: center;
  line-height: 92rpx;
  font-size: 42rpx;
  font-weight: 800;
}
.id-main { flex: 1; }
.id-name {
  font-size: 34rpx;
  font-weight: 800;
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.id-badge {
  font-size: 20rpx;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.22);
  border-radius: 999rpx;
  padding: 3rpx 14rpx;
}
.id-sub {
  font-size: 24rpx;
  opacity: 0.9;
  margin-top: 8rpx;
}
.id-arrow {
  font-size: 40rpx;
  opacity: 0.8;
}
.stats {
  display: flex;
  margin-top: 34rpx;
}
.st {
  flex: 1;
  text-align: center;
  position: relative;
}
.st + .st::before {
  content: '';
  position: absolute;
  left: 0; top: 8rpx; bottom: 8rpx;
  width: 1rpx;
  background: rgba(255, 255, 255, 0.2);
}
.st .n {
  display: block;
  font-size: 40rpx;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
}
.st .l {
  display: block;
  font-size: 22rpx;
  opacity: 0.82;
  margin-top: 4rpx;
}

/* 成长卡 */
.growth {
  background: #fff;
  border-radius: 20rpx;
  margin-top: -28rpx;
  position: relative;
  z-index: 3;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
  padding: 28rpx;
}
.g-top {
  display: flex;
  align-items: center;
  gap: 24rpx;
}
.ring {
  width: 104rpx;
  height: 104rpx;
  flex: 0 0 auto;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}
.ring-in {
  width: 80rpx;
  height: 80rpx;
  border-radius: 50%;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28rpx;
  font-weight: 800;
  color: var(--c-brand, #12a150);
}
.g-mid { flex: 1; }
.g-title {
  font-size: 30rpx;
  font-weight: 700;
  color: #25292e;
}
.g-bar {
  height: 12rpx;
  background: #eef0f2;
  border-radius: 999rpx;
  margin-top: 14rpx;
  overflow: hidden;
}
.g-bar-in {
  height: 100%;
  background: var(--c-brand, #12a150);
  border-radius: 999rpx;
}
.g-hint {
  display: block;
  font-size: 22rpx;
  color: #9aa2ad;
  margin-top: 12rpx;
}
.g-arrow {
  color: #c7ccd2;
  font-size: 40rpx;
}
.g-foot {
  display: flex;
  margin-top: 26rpx;
  padding-top: 24rpx;
  border-top: 1rpx solid #eef0f2;
}
.gf {
  flex: 1;
  text-align: center;
  position: relative;
}
.gf + .gf::before {
  content: '';
  position: absolute;
  left: 0; top: 4rpx; bottom: 4rpx;
  width: 1rpx;
  background: #eef0f2;
}
.gf .n {
  display: block;
  font-size: 30rpx;
  font-weight: 800;
  color: #25292e;
  font-variant-numeric: tabular-nums;
}
.gf .l {
  display: block;
  font-size: 22rpx;
  color: #9aa2ad;
  margin-top: 4rpx;
}

/* 会员卡：金色点缀，压在成长卡下方 */
.memcard {
  display: flex;
  align-items: center;
  gap: 20rpx;
  margin-top: 20rpx;
  padding: 24rpx 28rpx;
  border-radius: 20rpx;
  background: linear-gradient(135deg, #fff4d6, #ffe6a8);
  box-shadow: 0 12rpx 28rpx rgba(201, 151, 31, 0.16);
  position: relative;
  overflow: hidden;
}
.memcard::after {
  content: '';
  position: absolute;
  right: -50rpx; top: -50rpx;
  width: 200rpx; height: 200rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.35);
}
.mem-ic {
  width: 76rpx; height: 76rpx;
  border-radius: 22rpx;
  background: rgba(255, 255, 255, 0.55);
  display: flex; align-items: center; justify-content: center;
  flex: 0 0 auto;
  position: relative; z-index: 2;
}
.mem-main { flex: 1; min-width: 0; position: relative; z-index: 2; }
.mem-plan { font-size: 32rpx; font-weight: 800; color: #5b4300; }
.mem-sub { display: block; font-size: 22rpx; color: #7a5c14; margin-top: 6rpx; }

/* 分组 */
.sect {
  font-size: 24rpx;
  color: #9aa2ad;
  padding: 26rpx 8rpx 12rpx;
}
.card {
  background: #fff;
  border-radius: 20rpx;
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
  width: 60rpx;
  height: 60rpx;
  border-radius: 16rpx;
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}
.r-ic.on { background: var(--c-brand-weak, #e7f7ee); }
.r-t {
  flex: 1;
  font-size: 30rpx;
  color: #25292e;
}
.r-v {
  font-size: 26rpx;
  color: #9aa2ad;
}
.r-v-hot {
  color: var(--c-brand, #12a150);
  font-weight: 700;
}
.arrow {
  color: #c7ccd2;
  font-size: 34rpx;
  margin-left: 4rpx;
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
