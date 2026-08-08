/**
 * 主题令牌（单一事实源）。每个主题给出一组 CSS 变量取值，App.vue 里以 `.theme-<id>` 类承载，
 * 页面根节点挂上该类后，变量即向下层 scoped 样式级联，所有引用 var(--c-*) 的样式随主题切换。
 *
 * 约束（本期）：为控制风险，主题只改「品牌色 / 页头渐变 / 页面底色 / 品牌浅色」等有限令牌，
 * 卡片一律白底、正文深色、收支语义色固定（金融惯例），因此不需要逐页反色即可安全切换。
 * 深色模式（反转卡片/正文）作为后续独立增强。
 *
 * 令牌说明：
 *   --c-brand        品牌主色（按钮、选中态、强调）
 *   --c-brand-strong 品牌深色（按下态 / 更强调）
 *   --c-brand-weak   品牌浅底（chip 背景、弱强调块）
 *   --c-brand-ink    品牌浅底上的文字色（比主色更深，保证对比度）
 *   --c-hero         页头渐变（首页/账本/资产/我的/报表统一取此值，解决页头不一致）
 *   --c-page-bg      页面底色
 *   --c-tabbar-active 原生 tabBar 选中色（十六进制，走 uni.setTabBarStyle）
 */

/** tabBar 未选中色与背景（各主题一致，仅选中色随主题）。 */
export const TABBAR_INACTIVE = '#9aa2ad'
export const TABBAR_BG = '#ffffff'

/** 主题清单（顺序即选择器展示顺序）。id 落地存储，务必稳定。 */
export const THEMES = [
  {
    id: 'forest',
    name: '有余绿',
    // 选择器上的取色预览（主色 + 页头两端）
    swatch: ['#12a150', '#1fbf63', '#0f8a45'],
    vars: {
      '--c-brand': '#12a150',
      '--c-brand-strong': '#0e8a44',
      '--c-brand-weak': '#e6f6ec',
      '--c-brand-ink': '#0e8a44',
      '--c-hero': 'linear-gradient(150deg, #1fbf63, #0f8a45 78%)',
      '--c-page-bg': '#eef0f2',
      '--c-tabbar-active': '#12a150'
    }
  },
  {
    id: 'sky',
    name: '晴空蓝',
    swatch: ['#0ea5e9', '#38bdf8', '#0284c7'],
    vars: {
      '--c-brand': '#0ea5e9',
      '--c-brand-strong': '#0284c7',
      '--c-brand-weak': '#e3f4fd',
      '--c-brand-ink': '#0369a1',
      '--c-hero': 'linear-gradient(150deg, #38bdf8, #0284c7 78%)',
      '--c-page-bg': '#eef1f5',
      '--c-tabbar-active': '#0ea5e9'
    }
  },
  {
    id: 'sunset',
    name: '日暮橙',
    swatch: ['#f97316', '#fb923c', '#ea580c'],
    vars: {
      '--c-brand': '#f97316',
      '--c-brand-strong': '#ea580c',
      '--c-brand-weak': '#fff0e6',
      '--c-brand-ink': '#c2410c',
      '--c-hero': 'linear-gradient(150deg, #fb923c, #ea580c 78%)',
      '--c-page-bg': '#f4f1ee',
      '--c-tabbar-active': '#f97316'
    }
  },
  {
    id: 'violet',
    name: '静谧紫',
    swatch: ['#7c6cf0', '#8b78e0', '#6d5de6'],
    vars: {
      '--c-brand': '#7c6cf0',
      '--c-brand-strong': '#6d5de6',
      '--c-brand-weak': '#eeecfe',
      '--c-brand-ink': '#5b4bd6',
      '--c-hero': 'linear-gradient(150deg, #8b78e0, #6d5de6 78%)',
      '--c-page-bg': '#eeeef4',
      '--c-tabbar-active': '#7c6cf0'
    }
  },
  // —— 高级系（深色页头 + 精致强调色；卡片仍白底、正文深色）——
  {
    id: 'obsidian',
    name: '曜石黑',
    swatch: ['#12a150', '#2b3a34', '#1f2a30'],
    vars: {
      '--c-brand': '#12a150',
      '--c-brand-strong': '#0e8a44',
      '--c-brand-weak': '#e9eef0',
      '--c-brand-ink': '#26333a',
      '--c-hero': 'linear-gradient(150deg, #2b3a34, #1f2a30 72%)',
      '--c-page-bg': '#eef0f2',
      '--c-tabbar-active': '#1f2a30'
    }
  },
  {
    id: 'midnight',
    name: '午夜蓝',
    swatch: ['#3b82f6', '#243b55', '#0f2027'],
    vars: {
      '--c-brand': '#3b82f6',
      '--c-brand-strong': '#2563eb',
      '--c-brand-weak': '#e8f0fe',
      '--c-brand-ink': '#1d4ed8',
      '--c-hero': 'linear-gradient(150deg, #243b55, #0f2027 80%)',
      '--c-page-bg': '#eef0f4',
      '--c-tabbar-active': '#243b55'
    }
  },
  {
    id: 'champagne',
    name: '香槟金',
    swatch: ['#b3873a', '#3a3226', '#1c1712'],
    vars: {
      '--c-brand': '#b3873a',
      '--c-brand-strong': '#9a6f28',
      '--c-brand-weak': '#f6efe1',
      '--c-brand-ink': '#8a6a2a',
      '--c-hero': 'linear-gradient(150deg, #3a3226, #1c1712 78%)',
      '--c-page-bg': '#f1efea',
      '--c-tabbar-active': '#1c1712'
    }
  },
  {
    id: 'pine',
    name: '墨玉绿',
    swatch: ['#0f8a45', '#154d3a', '#0a2a20'],
    vars: {
      '--c-brand': '#0f8a45',
      '--c-brand-strong': '#0b6b34',
      '--c-brand-weak': '#e7f2ec',
      '--c-brand-ink': '#0b5a2f',
      '--c-hero': 'linear-gradient(150deg, #154d3a, #0a2a20 80%)',
      '--c-page-bg': '#edf1ef',
      '--c-tabbar-active': '#0a2a20'
    }
  },
  {
    id: 'wine',
    name: '勃艮第',
    swatch: ['#b23a4b', '#4a1f2b', '#2a0f16'],
    vars: {
      '--c-brand': '#b23a4b',
      '--c-brand-strong': '#97303f',
      '--c-brand-weak': '#f8e9ec',
      '--c-brand-ink': '#8f2f3c',
      '--c-hero': 'linear-gradient(150deg, #4a1f2b, #2a0f16 80%)',
      '--c-page-bg': '#f2eef0',
      '--c-tabbar-active': '#2a0f16'
    }
  },
  {
    id: 'slate',
    name: '黛山灰',
    swatch: ['#5b7fb0', '#3b4252', '#232833'],
    vars: {
      '--c-brand': '#5b7fb0',
      '--c-brand-strong': '#47679a',
      '--c-brand-weak': '#eaeff6',
      '--c-brand-ink': '#3f5b86',
      '--c-hero': 'linear-gradient(150deg, #3b4252, #232833 80%)',
      '--c-page-bg': '#eef0f3',
      '--c-tabbar-active': '#232833'
    }
  },
  {
    id: 'teal',
    name: '孔雀青',
    swatch: ['#0e9aa7', '#0b3a44', '#06262d'],
    vars: {
      '--c-brand': '#0e9aa7',
      '--c-brand-strong': '#0b7e89',
      '--c-brand-weak': '#e2f4f5',
      '--c-brand-ink': '#0b6d77',
      '--c-hero': 'linear-gradient(150deg, #0b3a44, #06262d 82%)',
      '--c-page-bg': '#eef2f2',
      '--c-tabbar-active': '#06262d'
    }
  }
]

/** 默认主题 id（品牌绿）。 */
export const DEFAULT_THEME_ID = 'forest'

/** 基础系主题 id（其余归入高级系）。 */
const BASIC_IDS = ['forest', 'sky', 'sunset', 'violet']

/** 分组后的主题（供选择器分「基础 / 高级」两组网格展示）。 */
export function themeGroups() {
  return [
    { key: 'basic', title: '基础', items: THEMES.filter((t) => BASIC_IDS.includes(t.id)) },
    { key: 'premium', title: '高级', items: THEMES.filter((t) => !BASIC_IDS.includes(t.id)) }
  ]
}

/** 按 id 取主题，找不到回退默认。 */
export function themeById(id) {
  return THEMES.find((t) => t.id === id) || THEMES.find((t) => t.id === DEFAULT_THEME_ID)
}

/** 主题类名（挂到页面根节点，与 App.vue 全局样式里的 .theme-<id> 对应）。 */
export function themeClass(id) {
  return `theme-${themeById(id).id}`
}
