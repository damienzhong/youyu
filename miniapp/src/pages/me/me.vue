<script setup>
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useAuthStore } from '../../stores/auth'
import { fetchInviteInfo } from '../../api/invite'

const auth = useAuthStore()

// 已邀请人数：null 表示尚未取到（含请求失败），此时入口只显示标题与箭头（需求 2.6）
const invitedCount = ref(null)

const nickname = computed(() => auth.user?.nickname || '有余用户')
const planLabel = computed(() => {
  const p = auth.user?.plan
  return p === 'pro' ? '专业版' : p === 'lifetime' ? '终身版' : '免费版'
})

onShow(() => {
  uni.hideTabBar({ animation: false, fail() {} })
  if (!auth.isLoggedIn) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  auth.refreshUser().catch(() => {})
  // 人数只是锦上添花：失败静默（不弹错误、不影响页面其余部分），入口保持只有标题与箭头
  fetchInviteInfo()
    .then((res) => {
      const n = Number(res?.invitedCount)
      invitedCount.value = Number.isFinite(n) && n >= 0 ? n : null
    })
    .catch(() => {
      invitedCount.value = null
    })
})

// 快捷宫格（管理类高频入口）
const grid = [
  { key: 'ledgers', icon: 'book', label: '账本', url: '/pages/ledgers/ledgers' },
  { key: 'budget', icon: 'budget', label: '预算', url: '/pages/budget/budget' },
  { key: 'categories', icon: 'tag', label: '分类', url: '/pages/categories/categories' },
  { key: 'loans', icon: 'loan', label: '借贷', url: '/pages/loans/loans' }
]

// 分组列表
const groups = [
  {
    title: '记账工具',
    items: [
      { key: 'bills', icon: 'import', label: '账单导入', desc: '支付宝 / 微信', url: '/pages/billimport/billimport' },
      { key: 'data', icon: 'export', label: '数据导出 / 导入', desc: '', url: '/pages/data/data' },
      { key: 'recycle', icon: 'recycle', label: '回收站', desc: '30 天可恢复', url: '/pages/recycle/recycle' }
    ]
  },
  {
    title: '标签体系',
    items: [
      { key: 'labels', icon: 'folder', label: '项目 / 商家 / 标签', desc: '', url: '/pages/labels/labels' }
    ]
  }
]

function go(url) {
  uni.navigateTo({ url })
}
function goAccount() {
  uni.navigateTo({ url: '/pages/account/account' })
}
function about() {
  uni.showModal({ title: '有余', content: '记好每一笔，日子有余\n版本 v0.1.0', showCancel: false })
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
    <!-- 个人卡：点击进账号设置 -->
    <view class="profile" @click="goAccount">
      <view class="avatar">{{ nickname.slice(0, 1) }}</view>
      <view class="p-main">
        <view class="p-name">{{ nickname }} <text class="p-badge">{{ planLabel }}</text></view>
        <text class="p-sub">管理账号与登录方式</text>
      </view>
      <text class="p-arrow">›</text>
    </view>

    <!-- 快捷宫格 -->
    <view class="grid">
      <view v-for="g in grid" :key="g.key" class="g" @click="go(g.url)">
        <view class="tile"><AppIcon :name="g.icon" :size="44" /></view>
        <text class="g-t">{{ g.label }}</text>
      </view>
    </view>

    <!-- 邀请入口：人数是动态的，故不并入静态 groups -->
    <view class="sect">邀请</view>
    <view class="card">
      <view class="row" @click="go('/pages/invite/invite')">
        <view class="r-ic t-green"><AppIcon name="members" :size="36" /></view>
        <text class="r-t">邀请好友</text>
        <text v-if="invitedCount !== null" class="r-v r-v-invite">已邀请 {{ invitedCount }} 人</text>
        <text class="arrow">›</text>
      </view>
    </view>

    <!-- 分组功能列表 -->
    <template v-for="grp in groups" :key="grp.title">
      <view class="sect">{{ grp.title }}</view>
      <view class="card">
        <view v-for="it in grp.items" :key="it.key" class="row" @click="go(it.url)">
          <view class="r-ic"><AppIcon :name="it.icon" :size="36" /></view>
          <text class="r-t">{{ it.label }}</text>
          <text v-if="it.desc" class="r-v">{{ it.desc }}</text>
          <text class="arrow">›</text>
        </view>
      </view>
    </template>

    <!-- 关于 -->
    <view class="sect">关于</view>
    <view class="card">
      <view class="row" @click="about">
        <view class="r-ic"><AppIcon name="info" :size="36" /></view>
        <text class="r-t">关于有余</text>
        <text class="r-v">v0.1.0</text>
        <text class="arrow">›</text>
      </view>
    </view>

    <view class="logout" @click="logout">退出登录</view>

    <view style="height:180rpx;"></view>
    <TabBar active="me" />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f2f4f6;
  padding: 24rpx 24rpx 0;
}
/* 个人卡 */
.profile {
  display: flex;
  align-items: center;
  gap: 24rpx;
  background: linear-gradient(135deg, #22c55e, #0f8a45 70%);
  border-radius: 24rpx;
  padding: 34rpx 30rpx;
  color: #fff;
  box-shadow: 0 16rpx 34rpx rgba(18, 161, 80, 0.28);
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
.p-main {
  flex: 1;
}
.p-name {
  font-size: 34rpx;
  font-weight: 800;
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.p-badge {
  font-size: 20rpx;
  font-weight: 600;
  background: rgba(255, 255, 255, 0.22);
  border-radius: 999rpx;
  padding: 3rpx 14rpx;
}
.p-sub {
  font-size: 24rpx;
  opacity: 0.9;
  margin-top: 8rpx;
}
.p-arrow {
  font-size: 40rpx;
  opacity: 0.8;
}
/* 快捷宫格 */
.grid {
  display: flex;
  background: #fff;
  border-radius: 20rpx;
  padding: 28rpx 8rpx;
  margin-top: 24rpx;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
}
.g {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14rpx;
}
.tile {
  width: 84rpx;
  height: 84rpx;
  border-radius: 24rpx;
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
}
.g-t {
  font-size: 24rpx;
  color: #4b5563;
}
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
.r-t {
  flex: 1;
  font-size: 30rpx;
  color: #25292e;
}
.r-v {
  font-size: 26rpx;
  color: #9aa2ad;
}
.r-v-invite {
  color: #12a150;
  font-weight: 700;
}
.arrow {
  color: #c7ccd2;
  font-size: 34rpx;
  margin-left: 4rpx;
}
/* tile / icon tints */
.t-green { background: #e7f7ee; }
.t-blue { background: #e8f0fe; }
.t-orange { background: #fdf0e6; }
.t-purple { background: #f0ecfe; }
.t-teal { background: #e4f6f5; }
.t-pink { background: #fdeaf0; }
.t-gray { background: #eef1f4; }
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
