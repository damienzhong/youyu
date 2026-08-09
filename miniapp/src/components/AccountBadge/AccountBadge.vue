<script setup>
/**
 * 账户徽标（安静风格）：统一浅底圆角块。
 * - 品牌（App/银行）：品牌色字徽，或真实 Logo（资源包，白底原色）。
 * - 通用类型：语义色线图标。
 * 颜色整体柔化一档（向白混合），加载失败自动回退品牌色字徽，永不空图标。size 单位 rpx。
 */
import { ref, computed, watch } from 'vue'
import { accountBrand } from '../../utils/brand'

const props = defineProps({
  account: { type: Object, default: () => ({}) },
  size: { type: Number, default: 64 } // rpx
})

// 颜色柔化：向白混合 ratio（0.18≈一档），让整体更淡雅；真实 Logo（图片）不受影响。
function soften(hex, ratio = 0.18) {
  if (typeof hex !== 'string' || !/^#[0-9a-fA-F]{6}$/.test(hex)) return hex || '#7c8698'
  const n = parseInt(hex.slice(1), 16)
  const mix = (c) => Math.round(c + (255 - c) * ratio)
  const r = mix(n >> 16 & 255), g = mix(n >> 8 & 255), b = mix(n & 255)
  return '#' + [r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('')
}

const brand = computed(() => accountBrand(props.account))
const err = ref(false)
watch(() => brand.value.logo, () => { err.value = false })

const logoOk = computed(() => !!brand.value.logo && !err.value)
const tint = computed(() => soften(brand.value.color || '#5b6470'))
const box = computed(() => ({
  width: props.size + 'rpx',
  height: props.size + 'rpx',
  borderRadius: Math.round(props.size * 0.28) + 'rpx'
}))
const charSize = computed(() => Math.round(props.size * 0.4) + 'rpx')
const glyphSize = computed(() => Math.round(props.size * 0.52))
</script>

<template>
  <view class="ab" :style="box">
    <image v-if="logoOk" class="ab-img" :src="brand.logo" mode="aspectFit" @error="err = true" />
    <text v-else-if="brand.char" class="ab-ch" :style="{ fontSize: charSize, color: tint }">{{ brand.char }}</text>
    <AppIcon v-else :name="brand.iconKey" :size="glyphSize" :color="tint" />
  </view>
</template>

<style scoped>
.ab {
  flex: 0 0 auto;
  display: flex; align-items: center; justify-content: center;
  background: #f4f5f7;
}
.ab-img { width: 60%; height: 60%; }
.ab-ch { font-weight: 700; letter-spacing: -0.02em; }
</style>
