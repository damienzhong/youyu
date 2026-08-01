<script setup>
/**
 * 自定义底部导航（方案 B：满宽栏 + 中间凸起「记一笔」）。
 *
 * 用法：在各 tab 页根节点末尾放 <TabBar active="home" />。
 * - 4 个 tab（首页/资产/报表/我的）用 uni.switchTab 切换（pages.json tabBar 设 custom:true）。
 * - 中间凸起键跳「记一笔」记账页（navigateTo，非 tab 页）。
 * - 图标用统一线性图标集（AppIcon，SVG data-URI，H5/小程序通用），选中态品牌绿。
 * 依赖 easycom 自动注册（components/TabBar/TabBar.vue → <TabBar/>）。
 */
const props = defineProps({
  active: { type: String, default: 'home' } // home | assets | report | me
})

const ACTIVE = '#12a150'
const INACTIVE = '#9aa2ad'

const TABS = [
  { key: 'home', label: '首页', icon: 'home', path: '/pages/index/index' },
  { key: 'assets', label: '资产', icon: 'diamond', path: '/pages/accounts/accounts' },
  { key: 'report', label: '报表', icon: 'chart', path: '/pages/report/report' },
  { key: 'me', label: '我的', icon: 'user', path: '/pages/me/me' }
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
      <view class="tab" :class="{ on: active === 'home' }" @click="switchTo(TABS[0])">
        <AppIcon class="ic" name="home" :size="42" :color="active === 'home' ? ACTIVE : INACTIVE" /><text class="t">首页</text>
      </view>
      <view class="tab" :class="{ on: active === 'assets' }" @click="switchTo(TABS[1])">
        <AppIcon class="ic" name="diamond" :size="42" :color="active === 'assets' ? ACTIVE : INACTIVE" /><text class="t">资产</text>
      </view>
      <!-- 中间：图标区留白给凸起键，只放「记一笔」标签，与其它标签基线对齐 -->
      <view class="tab center-slot" @click="goRecord">
        <view class="ic-space"></view>
        <text class="t hl">记一笔</text>
      </view>
      <view class="tab" :class="{ on: active === 'report' }" @click="switchTo(TABS[2])">
        <AppIcon class="ic" name="chart" :size="42" :color="active === 'report' ? ACTIVE : INACTIVE" /><text class="t">报表</text>
      </view>
      <view class="tab" :class="{ on: active === 'me' }" @click="switchTo(TABS[3])">
        <AppIcon class="ic" name="user" :size="42" :color="active === 'me' ? ACTIVE : INACTIVE" /><text class="t">我的</text>
      </view>
    </view>

    <!-- 中间凸起「记一笔」：白色护城河 + 实心圆 -->
    <view class="moat"></view>
    <view class="center" @click="goRecord"><text class="plus">＋</text></view>
  </view>
</template>

<style scoped>
.tabbar-wrap {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 500;
  /* 顶部留白容纳凸起键，避免被裁；空白区不挡上方内容点击 */
  padding-top: 48rpx;
  overflow: visible;
  pointer-events: none;
}
.tabbar {
  height: 100rpx;
  padding-bottom: env(safe-area-inset-bottom);
  background: #fff;
  border-top: 1rpx solid #eef0f2;
  box-shadow: 0 -6rpx 20rpx rgba(20, 24, 28, 0.05);
  display: flex;
  align-items: flex-end;
  pointer-events: auto;
}
.tab {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  gap: 4rpx;
  padding-bottom: 12rpx;
}
.tab .ic {
  line-height: 1;
}
.tab .t {
  font-size: 20rpx;
  line-height: 1;
  color: #9aa2ad;
}
.tab.on .t {
  color: #12a150;
  font-weight: 600;
}
/* 中间槽：图标区留白（与其它 tab 图标同高），使「记一笔」标签与其它标签对齐 */
.center-slot .ic-space {
  width: 40rpx;
  height: 40rpx;
}
.center-slot .t.hl {
  color: #12a150;
  font-weight: 600;
}
/* 白色护城河：把凸起键托进栏里 */
.moat {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  bottom: calc(38rpx + env(safe-area-inset-bottom));
  width: 100rpx;
  height: 100rpx;
  border-radius: 50%;
  background: #fff;
  z-index: 1;
}
.center {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  bottom: calc(48rpx + env(safe-area-inset-bottom));
  width: 80rpx;
  height: 80rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, #18b85a, #0e8a44);
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 10rpx 22rpx rgba(18, 161, 80, 0.45);
  z-index: 2;
  pointer-events: auto;
}
.center .plus {
  color: #fff;
  font-size: 46rpx;
  font-weight: 300;
  line-height: 1;
  margin-top: -4rpx;
}
</style>
