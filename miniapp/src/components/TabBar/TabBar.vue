<script setup>
/**
 * 自定义底部导航（方案 B：满宽栏 + 中间凸起「记一笔」）。
 *
 * 用法：在各 tab 页根节点末尾放 <TabBar active="home" />。
 * - 4 个 tab（首页/明细/报表/我的）用 uni.switchTab 切换（pages.json tabBar 设 custom:true）。
 * - 中间凸起键跳「记一笔」记账页（navigateTo，非 tab 页）。
 * - 图标用 emoji（在 uni-app H5/小程序均可靠渲染；内联 svg 受 scoped 样式限制不显示）。
 * 依赖 easycom 自动注册（components/TabBar/TabBar.vue → <TabBar/>）。
 */
const props = defineProps({
  active: { type: String, default: 'home' } // home | records | report | me
})

const TABS = [
  { key: 'home', label: '首页', icon: '🏠', path: '/pages/index/index' },
  { key: 'records', label: '明细', icon: '📋', path: '/pages/records/records' },
  { key: 'report', label: '报表', icon: '📊', path: '/pages/report/report' },
  { key: 'me', label: '我的', icon: '👤', path: '/pages/me/me' }
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
        <text class="ic">🏠</text><text class="t">首页</text>
      </view>
      <view class="tab" :class="{ on: active === 'records' }" @click="switchTo(TABS[1])">
        <text class="ic">📋</text><text class="t">明细</text>
      </view>
      <!-- 中间：图标区留白给凸起键，只放「记一笔」标签，与其它标签基线对齐 -->
      <view class="tab center-slot" @click="goRecord">
        <view class="ic-space"></view>
        <text class="t hl">记一笔</text>
      </view>
      <view class="tab" :class="{ on: active === 'report' }" @click="switchTo(TABS[2])">
        <text class="ic">📊</text><text class="t">报表</text>
      </view>
      <view class="tab" :class="{ on: active === 'me' }" @click="switchTo(TABS[3])">
        <text class="ic">👤</text><text class="t">我的</text>
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
  font-size: 40rpx;
  line-height: 1;
  filter: grayscale(35%);
  opacity: 0.55;
}
.tab .t {
  font-size: 20rpx;
  line-height: 1;
  color: #9aa2ad;
}
.tab.on .ic {
  filter: none;
  opacity: 1;
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
