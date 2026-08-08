import { defineStore } from 'pinia'
import { STORAGE_KEYS } from '../utils/config'
import {
  THEMES,
  DEFAULT_THEME_ID,
  themeById,
  themeClass,
  TABBAR_INACTIVE,
  TABBAR_BG
} from '../utils/theme'

/**
 * 主题状态：持有当前主题 id，落地本地存储。页面根节点绑定 `themeClass` 后，
 * App.vue 里 `.theme-<id>` 定义的 CSS 变量向下级联，驱动页头渐变 / 页面底色 / 品牌色随主题切换。
 * 原生 tabBar 选中色不受 CSS 变量控制，由本 store 通过 uni.setTabBarStyle 同步。
 */
export const useThemeStore = defineStore('theme', {
  state: () => ({
    themeId: uni.getStorageSync(STORAGE_KEYS.themeId) || DEFAULT_THEME_ID
  }),

  getters: {
    /** 可选主题清单（供选择器渲染）。 */
    themes: () => THEMES,
    /** 当前主题对象。 */
    current: (state) => themeById(state.themeId),
    /** 当前主题类名（挂到页面根节点）。 */
    themeClass: (state) => themeClass(state.themeId)
  },

  actions: {
    /** 切换主题：更新状态 + 持久化 + 同步原生 tabBar。 */
    setTheme(id) {
      const t = themeById(id)
      this.themeId = t.id
      uni.setStorageSync(STORAGE_KEYS.themeId, t.id)
      this.applyTabBar()
    },

    /** 把当前主题的选中色写入原生 tabBar（启动与切换时调用；无 tabBar 页面静默失败）。 */
    applyTabBar() {
      const t = themeById(this.themeId)
      uni.setTabBarStyle({
        selectedColor: t.vars['--c-tabbar-active'],
        color: TABBAR_INACTIVE,
        backgroundColor: TABBAR_BG,
        fail() {}
      })
    }
  }
})
