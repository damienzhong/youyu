<script setup>
import { onLaunch, onShow } from '@dcloudio/uni-app'
import { useThemeStore } from './stores/theme'
import { useNetStore } from './stores/net'
import { runSync } from './utils/offline/sync'

onLaunch(() => {
  // 启动即把当前主题的选中色同步到原生 tabBar（CSS 变量管不到原生 tabBar）。
  // 首帧 tabBar 可能尚未就绪，setTabBarStyle 内部 fail 静默；各 tabBar 页 onShow 会再兜底应用一次。
  try {
    useThemeStore().applyTabBar()
  } catch (e) {
    /* ignore */
  }

  // 初始化网络态并订阅变化：网络恢复（离线→在线）时自动触发一次同步（受「仅 Wi-Fi」偏好约束）。
  try {
    useNetStore().init((change) => {
      if (change && !change.wasOnline && change.online) {
        runSync().catch(() => {})
      }
    })
  } catch (e) {
    /* ignore */
  }
})

onShow(() => {
  // App 回到前台：若队列非空且在线，尝试补一次同步。
  runSync().catch(() => {})
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
