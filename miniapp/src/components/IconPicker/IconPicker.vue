<script setup>
/**
 * 分类图标选择器：顶部预览磁贴 + 背景配色行 + 场景分组 tab + 图标网格。
 *
 * 用法：<IconPicker v-model:icon="form.icon" v-model:color="form.iconColor" :kind="form.kind" />
 * easycom 自动注册（components/IconPicker/IconPicker.vue → <IconPicker/>）。
 *
 * 交互：选色/选图标实时更新预览与双向绑定（emit update:icon / update:color）；
 * 纯本地渲染，不发任何请求。预览磁贴复用 CategoryIcon（实色底 + 白描边图标），
 * 网格图标以 iconDataUri 生成的 background-image 渲染（H5 / 小程序通用）。
 */
import { computed, ref } from 'vue'
import { ICON_GROUPS, ICON_COLORS, DEFAULT_ICON_COLOR, iconDataUri, isHexColor } from '../../utils/icons'

const props = defineProps({
  // 当前选中的图标 key（v-model:icon）
  icon: { type: String, default: '' },
  // 当前选中的背景色 hex（v-model:color），空/非法回退默认色
  color: { type: String, default: '' },
  // 分类种类：expense | income（预览缺省图标推断用）
  kind: { type: String, default: 'expense' }
})

const emit = defineEmits(['update:icon', 'update:color'])

// 分组 tab：默认展示第一组；若当前 icon 命中某组则定位到该组
const activeGroup = ref(findGroupIndex(props.icon))

function findGroupIndex(key) {
  if (!key) return 0
  const idx = ICON_GROUPS.findIndex((g) => g.keys.includes(key))
  return idx >= 0 ? idx : 0
}

// 当前生效背景色：合法 hex 原样，否则默认品牌绿（用于预览与选中态磁贴）
const effectiveColor = computed(() => (isHexColor(props.color) ? props.color : DEFAULT_ICON_COLOR))

// 当前分组的图标 key 列表
const groupKeys = computed(() => ICON_GROUPS[activeGroup.value]?.keys ?? [])

// 网格未选中态图标（灰色描边）作为背景图
function tileIconStyle(key) {
  return {
    backgroundImage: iconDataUri(key, '#8a94a6'),
    backgroundRepeat: 'no-repeat',
    backgroundPosition: 'center',
    backgroundSize: '100% 100%'
  }
}

// 网格选中态图标（白色描边）作为背景图
function activeIconStyle(key) {
  return {
    backgroundImage: iconDataUri(key, '#ffffff'),
    backgroundRepeat: 'no-repeat',
    backgroundPosition: 'center',
    backgroundSize: '100% 100%'
  }
}

function pickColor(c) {
  emit('update:color', c)
}

function pickIcon(key) {
  emit('update:icon', key)
}

function switchGroup(idx) {
  activeGroup.value = idx
}
</script>

<template>
  <view class="icon-picker">
    <!-- 顶部预览磁贴：当前背景色 + 所选图标（白色描边） -->
    <view class="ip-preview-row">
      <CategoryIcon :icon="icon" :kind="kind" :color="effectiveColor" :size="42" />
      <text class="ip-preview-hint">预览效果</text>
    </view>

    <!-- 背景配色行 -->
    <text class="ip-label">背景颜色</text>
    <view class="ip-colors">
      <view
        v-for="c in ICON_COLORS"
        :key="c"
        class="ip-sw"
        :class="{ on: effectiveColor.toLowerCase() === c.toLowerCase() }"
        :style="{ backgroundColor: c, color: c }"
        @click="pickColor(c)"
      ></view>
    </view>

    <!-- 场景分组 tab -->
    <text class="ip-label">选择图标</text>
    <scroll-view scroll-x class="ip-tabs" :show-scrollbar="false">
      <view class="ip-tabs-inner">
        <view
          v-for="(g, i) in ICON_GROUPS"
          :key="g.label"
          class="ip-gtab"
          :class="{ on: activeGroup === i }"
          @click="switchGroup(i)"
        >{{ g.label }}</view>
      </view>
    </scroll-view>

    <!-- 图标网格 -->
    <view class="ip-grid">
      <view
        v-for="key in groupKeys"
        :key="key"
        class="ip-cell"
        @click="pickIcon(key)"
      >
        <view
          class="ip-tile"
          :class="{ on: icon === key }"
          :style="icon === key ? { backgroundColor: effectiveColor } : null"
        >
          <view class="ip-glyph" :style="icon === key ? activeIconStyle(key) : tileIconStyle(key)"></view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.icon-picker {
  width: 100%;
  box-sizing: border-box;
}

/* 预览行 */
.ip-preview-row {
  display: flex;
  align-items: center;
  gap: 24rpx;
  background: #f7f8fa;
  border-radius: 20rpx;
  padding: 24rpx 28rpx;
}
.ip-preview-hint {
  font-size: 26rpx;
  color: #8a94a6;
}

/* 分组/配色标题 */
.ip-label {
  display: block;
  font-size: 24rpx;
  font-weight: 700;
  color: #5b6470;
  margin: 24rpx 4rpx 12rpx;
}

/* 配色行 */
.ip-colors {
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx;
  padding: 4rpx;
}
.ip-sw {
  width: 56rpx;
  height: 56rpx;
  border-radius: 50%;
  position: relative;
  flex: 0 0 auto;
}
.ip-sw.on::after {
  content: '';
  position: absolute;
  inset: -7rpx;
  border-radius: 50%;
  border: 4rpx solid #fff;
  box-shadow: 0 0 0 4rpx currentColor;
}

/* 分组 tabs */
.ip-tabs {
  width: 100%;
  white-space: nowrap;
}
.ip-tabs-inner {
  display: inline-flex;
  gap: 14rpx;
  padding: 4rpx 4rpx 12rpx;
}
.ip-gtab {
  flex: 0 0 auto;
  font-size: 24rpx;
  font-weight: 700;
  color: #4b5563;
  background: #f4f5f7;
  border-radius: 999rpx;
  padding: 12rpx 24rpx;
}
.ip-gtab.on {
  background: #12a150;
  color: #fff;
}

/* 图标网格 */
.ip-grid {
  display: flex;
  flex-wrap: wrap;
  margin-top: 8rpx;
}
.ip-cell {
  width: 20%;
  display: flex;
  justify-content: center;
  padding: 12rpx 0;
}
.ip-tile {
  width: 92rpx;
  height: 92rpx;
  border-radius: 26rpx;
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
}
.ip-glyph {
  width: 46rpx;
  height: 46rpx;
  display: block;
}
</style>
