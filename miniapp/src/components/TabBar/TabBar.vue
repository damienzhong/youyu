<script setup>
/**
 * 自定义底部导航（方案 B：满宽栏 + 中间凸起「记一笔」）。
 *
 * 用法：在各 tab 页根节点末尾放 <TabBar active="home" />。
 * - 4 个 tab（首页/账本/资产/我的）用 uni.switchTab 切换（active: home|ledger|assets|me）。
 * - 中间凸起键跳「记一笔」记账页（navigateTo，非 tab 页）。
 * - 图标用统一线性图标集（AppIcon，SVG data-URI，H5/小程序通用），选中态品牌绿。
 * 依赖 easycom 自动注册（components/TabBar/TabBar.vue → <TabBar/>）。
 */
import { computed } from 'vue'
import { useThemeStore } from '../../stores/theme'

const props = defineProps({
  active: { type: String, default: 'home' } // home | assets | report | me
})

const themeStore = useThemeStore()
// 选中态图标色随主题（AppIcon 把颜色烧进 SVG data-URI，只能走 JS，不能靠 CSS 变量）。
const ACTIVE = computed(() => themeStore.current.vars['--c-brand'])
const INACTIVE = '#9aa2ad'

// 一级 tab：首页(总览) / 账本(记账明细) / 资产 / 我的；报表降为账本页快捷入口。
const TABS = [
  { key: 'home', label: '首页', icon: 'home', path: '/pages/home/home' },
  { key: 'ledger', label: '账本', icon: 'book', path: '/pages/index/index' },
  { key: 'assets', label: '资产', icon: 'diamond', path: '/pages/accounts/accounts' },
  { key: 'me', label: '我的', icon: 'user', path: '/pages/me/me' }
]

function switchTo(t) {
  if (t.key === props.active) return
  if (t.push) {
    uni.navigateTo({ url: t.path })
  } else {
    uni.switchTab({ url: t.path })
  }
}
// 中间凸起键：含义随所在页上下文变化。
// 资产页 → 添加账户（广播事件，由资产页监听打开账户类型选择）；其余页 → 记一笔。
const centerLabel = computed(() => (props.active === 'assets' ? '添加账户' : '记一笔'))
function onCenter() {
  if (props.active === 'assets') {
    uni.$emit('assets:addAccount')
  } else {
    uni.navigateTo({ url: '/pages/record/record' })
  }
}
</script>

<template>
  <view class="tabbar-wrap">
    <view class="tabbar">
      <view class="tab" :class="{ on: active === 'home' }" @click="switchTo(TABS[0])">
        <AppIcon class="ic" name="home" :size="42" :color="active === 'home' ? ACTIVE : INACTIVE" /><text class="t">首页</text>
      </view>
      <view class="tab" :class="{ on: active === 'ledger' }" @click="switchTo(TABS[1])">
        <AppIcon class="ic" name="book" :size="42" :color="active === 'ledger' ? ACTIVE : INACTIVE" /><text class="t">账本</text>
      </view>
      <!-- 中间：图标区留白给凸起键，标签随页面上下文变化，与其它标签基线对齐 -->
      <view class="tab center-slot" @click="onCenter">
        <view class="ic-space"></view>
        <text class="t hl">{{ centerLabel }}</text>
      </view>
      <view class="tab" :class="{ on: active === 'assets' }" @click="switchTo(TABS[2])">
        <AppIcon class="ic" name="diamond" :size="42" :color="active === 'assets' ? ACTIVE : INACTIVE" /><text class="t">资产</text>
      </view>
      <view class="tab" :class="{ on: active === 'me' }" @click="switchTo(TABS[3])">
        <AppIcon class="ic" name="user" :size="42" :color="active === 'me' ? ACTIVE : INACTIVE" /><text class="t">我的</text>
      </view>
    </view>

    <!-- 中间凸起键：白色护城河 + 实心圆（含义随页面上下文） -->
    <view class="moat"></view>
    <view class="center" @click="onCenter"><text class="plus">＋</text></view>
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
  color: var(--c-brand, #12a150);
  font-weight: 600;
}
/* 中间槽：图标区留白（与其它 tab 图标同高），使「记一笔」标签与其它标签对齐 */
.center-slot .ic-space {
  width: 40rpx;
  height: 40rpx;
}
.center-slot .t.hl {
  color: var(--c-brand, #12a150);
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
  background: var(--c-hero, linear-gradient(135deg, #18b85a, #0e8a44));
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 10rpx 22rpx rgba(20, 24, 28, 0.28);
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
