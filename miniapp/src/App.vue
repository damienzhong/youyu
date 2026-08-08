<script setup>
import { onLaunch } from '@dcloudio/uni-app'
import { useThemeStore } from './stores/theme'

onLaunch(() => {
  // 启动即把当前主题的选中色同步到原生 tabBar（CSS 变量管不到原生 tabBar）。
  // 首帧 tabBar 可能尚未就绪，setTabBarStyle 内部 fail 静默；各 tabBar 页 onShow 会再兜底应用一次。
  try {
    useThemeStore().applyTabBar()
  } catch (e) {
    /* ignore */
  }
})
</script>

<template>
  <!-- uni-app 根组件不渲染额外结构，页面由 pages.json 驱动 -->
</template>

<style>
/* page 底色为中性回退：各页根节点会用 var(--c-page-bg) 覆盖可视区域，
   这里只保证 overscroll/安全区等露出的 page 背景不突兀。 */
page {
  background-color: #eef0f2;
  font-family: -apple-system, BlinkMacSystemFont, 'Helvetica Neue', Helvetica, sans-serif;
}
</style>
