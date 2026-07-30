<script setup>
/**
 * 自定义底部导航（方案 B：满宽栏 + 中间凸起「记一笔」）。
 *
 * 用法：在各 tab 页根节点末尾放 <TabBar active="home" />。
 * - 4 个 tab（首页/明细/报表/我的）用 uni.switchTab 切换（pages.json tabBar 设 custom:true）。
 * - 中间凸起键跳「记一笔」记账页（navigateTo，非 tab 页）。
 * 依赖 easycom 自动注册（components/TabBar/TabBar.vue → <TabBar/>）。
 */
const props = defineProps({
  active: { type: String, default: 'home' } // home | records | report | me
})

const TABS = [
  { key: 'home', label: '首页', path: '/pages/index/index' },
  { key: 'records', label: '明细', path: '/pages/records/records' },
  { key: 'report', label: '报表', path: '/pages/report/report' },
  { key: 'me', label: '我的', path: '/pages/me/me' }
]

function switchTo(t) {
  if (t.key === props.active) return
  uni.switchTab({ url: t.path })
}
function goRecord() {
  uni.navigateTo({ url: '/pages/record/record' })
}
</script>

<template>
  <view class="tabbar-wrap">
    <view class="tabbar">
      <!-- 首页 -->
      <view class="tab" :class="{ on: active === 'home' }" @click="switchTo(TABS[0])">
        <svg class="ic" viewBox="0 0 24 24"><path d="M3 11l9-8 9 8" /><path d="M5 10v10h14V10" /></svg>
        <text class="t">首页</text>
      </view>
      <!-- 明细 -->
      <view class="tab" :class="{ on: active === 'records' }" @click="switchTo(TABS[1])">
        <svg class="ic" viewBox="0 0 24 24"><path d="M8 6h13M8 12h13M8 18h13" /><circle cx="4" cy="6" r="1.4" /><circle cx="4" cy="12" r="1.4" /><circle cx="4" cy="18" r="1.4" /></svg>
        <text class="t">明细</text>
      </view>
      <!-- 中间占位（凸起键覆盖其上） -->
      <view class="slot-mid"></view>
      <!-- 报表 -->
      <view class="tab" :class="{ on: active === 'report' }" @click="switchTo(TABS[2])">
        <svg class="ic" viewBox="0 0 24 24"><path d="M4 20V10M10 20V4M16 20v-7M22 20H2" /></svg>
        <text class="t">报表</text>
      </view>
      <!-- 我的 -->
      <view class="tab" :class="{ on: active === 'me' }" @click="switchTo(TABS[3])">
        <svg class="ic" viewBox="0 0 24 24"><circle cx="12" cy="8" r="4" /><path d="M4 21c0-4 4-6 8-6s8 2 8 6" /></svg>
        <text class="t">我的</text>
      </view>
    </view>

    <!-- 中间凸起「记一笔」：白色护城河 + 实心圆 + 文字 -->
    <view class="moat"></view>
    <view class="center" @click="goRecord">＋</view>
    <text class="center-label" @click="goRecord">记一笔</text>
  </view>
</template>

<style scoped>
.tabbar-wrap {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 500;
}
.tabbar {
  height: 100rpx;
  padding-bottom: env(safe-area-inset-bottom);
  background: #fff;
  border-top: 1rpx solid #eef0f2;
  box-shadow: 0 -6rpx 20rpx rgba(20, 24, 28, 0.05);
  display: flex;
  align-items: center;
}
.tab {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4rpx;
  color: #9aa2ad;
}
.tab .ic {
  width: 44rpx;
  height: 44rpx;
  fill: none;
  stroke: #9aa2ad;
  stroke-width: 1.9;
  stroke-linecap: round;
  stroke-linejoin: round;
}
.tab .t {
  font-size: 20rpx;
}
.tab.on {
  color: #12a150;
}
.tab.on .ic {
  stroke: #12a150;
}
.slot-mid {
  flex: 1;
}
/* 白色护城河：把凸起键托进栏里 */
.moat {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  bottom: calc(44rpx + env(safe-area-inset-bottom));
  width: 128rpx;
  height: 128rpx;
  border-radius: 50%;
  background: #fff;
  z-index: 1;
}
.center {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  bottom: calc(58rpx + env(safe-area-inset-bottom));
  width: 104rpx;
  height: 104rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, #18b85a, #0e8a44);
  color: #fff;
  text-align: center;
  line-height: 100rpx;
  font-size: 60rpx;
  font-weight: 300;
  box-shadow: 0 12rpx 24rpx rgba(18, 161, 80, 0.5);
  z-index: 2;
}
.center-label {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  bottom: calc(14rpx + env(safe-area-inset-bottom));
  font-size: 20rpx;
  color: #12a150;
  font-weight: 600;
  z-index: 2;
}
</style>
