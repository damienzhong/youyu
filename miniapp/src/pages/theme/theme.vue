<script setup>
import { useThemeStore } from '../../stores/theme'
import { themeGroups } from '../../utils/theme'

const themeStore = useThemeStore()
const groups = themeGroups()

// 选择即生效并持久化：切换后当前页与所有 tab 页随之更新。
function pick(id) {
  if (id === themeStore.themeId) return
  themeStore.setTheme(id)
}
</script>

<template>
  <!-- 根节点挂当前主题变量，让顶部预览即时反映所选主题 -->
  <view class="page" :style="themeStore.current.vars">
    <!-- 当前主题大图预览 -->
    <view class="preview">
      <view class="pv-hero">
        <text class="pv-hero-k">当前主题 · {{ themeStore.current.name }}</text>
        <text class="pv-hero-v">¥ 12,345.67</text>
        <text class="pv-hero-s">记好每一笔，日子更有余</text>
      </view>
      <view class="pv-body">
        <view class="pv-btn">主要按钮</view>
        <view class="pv-chip">标签 · 强调</view>
      </view>
    </view>

    <!-- 分组网格：基础 / 高级，每组两列，卡片预览各自主题色 -->
    <template v-for="grp in groups" :key="grp.key">
      <text class="sect">{{ grp.title }}</text>
      <view class="grid">
        <view
          v-for="t in grp.items"
          :key="t.id"
          class="card"
          :class="{ on: t.id === themeStore.themeId }"
          :style="t.id === themeStore.themeId ? { borderColor: t.vars['--c-brand'] } : null"
          @click="pick(t.id)"
        >
          <!-- 卡片顶部：该主题的页头渐变 + 品牌色圆点 -->
          <view class="c-hero" :style="{ background: t.vars['--c-hero'] }">
            <text class="c-dot" :style="{ background: t.vars['--c-brand'] }"></text>
            <text
              v-if="t.id === themeStore.themeId"
              class="c-check"
              :style="{ color: t.vars['--c-brand'] }"
            >✓</text>
          </view>
          <view class="c-foot">
            <text class="c-name">{{ t.name }}</text>
          </view>
        </view>
      </view>
    </template>

    <text class="tip">主题会同步应用到首页、账本、资产、我的与报表等页面。深色模式将在后续版本提供。</text>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: var(--c-page-bg, #eef0f2);
  padding: 24rpx;
}
/* 当前主题大图预览 */
.preview {
  background: #fff;
  border-radius: 24rpx;
  overflow: hidden;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.06);
}
.pv-hero {
  background: var(--c-hero, linear-gradient(150deg, #1fbf63, #0f8a45 78%));
  color: #fff;
  padding: 34rpx 32rpx 38rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.pv-hero-k { font-size: 24rpx; opacity: 0.9; }
.pv-hero-v { font-size: 54rpx; font-weight: 800; letter-spacing: -0.02em; }
.pv-hero-s { font-size: 24rpx; opacity: 0.85; }
.pv-body {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 26rpx 32rpx;
}
.pv-btn {
  background: var(--c-brand, #12a150);
  color: #fff;
  font-weight: 700;
  font-size: 28rpx;
  border-radius: 999rpx;
  padding: 16rpx 38rpx;
}
.pv-chip {
  background: var(--c-brand-weak, #e6f6ec);
  color: var(--c-brand-ink, #0e8a44);
  font-weight: 700;
  font-size: 26rpx;
  border-radius: 999rpx;
  padding: 12rpx 26rpx;
}
.sect {
  display: block;
  font-size: 24rpx;
  color: #9aa2ad;
  padding: 30rpx 8rpx 14rpx;
}
/* 两列网格 */
.grid {
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx;
}
.card {
  width: calc((100% - 20rpx) / 2);
  background: #fff;
  border-radius: 22rpx;
  overflow: hidden;
  box-shadow: 0 6rpx 18rpx rgba(20, 24, 28, 0.06);
  border: 3rpx solid transparent;
  box-sizing: border-box;
}
.c-hero {
  height: 120rpx;
  position: relative;
  display: flex;
  align-items: flex-end;
  padding: 16rpx;
}
.c-dot {
  width: 40rpx;
  height: 40rpx;
  border-radius: 50%;
  border: 4rpx solid rgba(255, 255, 255, 0.9);
  box-shadow: 0 2rpx 6rpx rgba(20, 24, 28, 0.18);
}
.c-check {
  position: absolute;
  top: 12rpx;
  right: 14rpx;
  width: 40rpx;
  height: 40rpx;
  border-radius: 50%;
  background: #fff;
  text-align: center;
  line-height: 40rpx;
  font-size: 26rpx;
  font-weight: 800;
  box-shadow: 0 2rpx 6rpx rgba(20, 24, 28, 0.18);
}
.c-foot {
  padding: 20rpx 22rpx;
}
.c-name {
  font-size: 28rpx;
  font-weight: 700;
  color: #16181c;
}
.tip {
  display: block;
  font-size: 22rpx;
  color: #9aa2ad;
  line-height: 1.7;
  padding: 28rpx 12rpx 12rpx;
}
</style>
