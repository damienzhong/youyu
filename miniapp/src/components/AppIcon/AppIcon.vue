<script setup>
/**
 * 统一图标组件：把内置图标 key 渲染成方形图标（SVG data-URI 背景，H5/小程序通用）。
 *
 * 用法：<AppIcon name="food" :size="44" />；选中态传 active 或直接给 color。
 * easycom 自动注册（components/AppIcon/AppIcon.vue → <AppIcon/>）。
 */
import { computed } from 'vue'
import { iconDataUri, ICON_DEFAULT_COLOR, ICON_ACTIVE_COLOR } from '../../utils/icons'

const props = defineProps({
  // 图标 key（内置图标集）
  name: { type: String, default: 'receipt' },
  // 图标边长（rpx）
  size: { type: Number, default: 44 },
  // 显式颜色；优先级高于 active
  color: { type: String, default: '' },
  // 选中态（品牌绿）
  active: { type: Boolean, default: false }
})

const tint = computed(
  () => props.color || (props.active ? ICON_ACTIVE_COLOR : ICON_DEFAULT_COLOR)
)
const style = computed(() => ({
  width: props.size + 'rpx',
  height: props.size + 'rpx',
  backgroundImage: iconDataUri(props.name, tint.value),
  backgroundRepeat: 'no-repeat',
  backgroundPosition: 'center',
  backgroundSize: '100% 100%'
}))
</script>

<template>
  <view class="app-icon" :style="style"></view>
</template>

<style scoped>
.app-icon {
  display: inline-block;
  flex: 0 0 auto;
}
</style>
