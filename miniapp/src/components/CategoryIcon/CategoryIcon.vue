<script setup>
/**
 * 分类图标磁贴：全 App 统一入口（实色圆角磁贴 + 白色描边图标，H5/小程序通用）。
 *
 * 用法：<CategoryIcon :icon="cat.icon" :name="cat.name" :kind="cat.kind" :color="cat.iconColor" :size="42" />
 * easycom 自动注册（components/CategoryIcon/CategoryIcon.vue → <CategoryIcon/>）。
 *
 * 逻辑：
 *  - resolvedKey = resolveIcon(icon, name, kind)：空/未知 key 按名称推断，再兜底 receipt/income。
 *  - bg = isHexColor(color) ? color : DEFAULT_ICON_COLOR：空/非法色回退品牌绿。
 * 不发任何请求；对空/非法输入安全兜底、不报错。
 */
import { computed } from 'vue'
import { resolveIcon, iconDataUri, isHexColor, DEFAULT_ICON_COLOR } from '../../utils/icons'

const props = defineProps({
  // 分类图标 key（内置图标集）
  icon: { type: String, default: '' },
  // 分类名称（icon 缺省时按名称推断）
  name: { type: String, default: '' },
  // 分类种类：expense | income
  kind: { type: String, default: 'expense' },
  // 分类背景色（categories.icon_color），空/非法回退默认色
  color: { type: String, default: '' },
  // 图标像素边长
  size: { type: Number, default: 42 }
})

// 归一化图标 key：空/未知 → 名称推断 → receipt/income 兜底
const resolvedKey = computed(() => resolveIcon(props.icon, props.name, props.kind))

// 背景色：合法 hex 原样，否则默认品牌绿
const bg = computed(() => (isHexColor(props.color) ? props.color : DEFAULT_ICON_COLOR))

// 容器略大于图标，圆角约 28%
const tileSize = computed(() => Math.round(props.size * 1.9))
const style = computed(() => ({
  width: tileSize.value + 'rpx',
  height: tileSize.value + 'rpx',
  borderRadius: Math.round(tileSize.value * 0.28) + 'rpx',
  backgroundColor: bg.value
}))

// 白色描边图标，作为容器内的背景图
const iconStyle = computed(() => ({
  width: props.size + 'rpx',
  height: props.size + 'rpx',
  backgroundImage: iconDataUri(resolvedKey.value, '#ffffff'),
  backgroundRepeat: 'no-repeat',
  backgroundPosition: 'center',
  backgroundSize: '100% 100%'
}))
</script>

<template>
  <view class="category-icon" :style="style">
    <view class="category-icon__glyph" :style="iconStyle"></view>
  </view>
</template>

<style scoped>
.category-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}
.category-icon__glyph {
  display: block;
}
</style>
