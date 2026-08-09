<script setup>
import { computed } from 'vue'
import { ACCOUNT_GROUPS, ACCOUNT_TYPES, accountTypeIcon } from '../../api/account'

const props = defineProps({
  visible: { type: Boolean, default: false }
})
const emit = defineEmits(['update:visible', 'pick'])

const groupedTypes = computed(() =>
  ACCOUNT_GROUPS.map((g) => ({ ...g, types: ACCOUNT_TYPES.filter((t) => t.group === g.key) }))
)

function close() {
  emit('update:visible', false)
}
function pick(t) {
  emit('pick', t)
}
</script>

<template>
  <view v-if="visible" class="mask" @click="close">
    <view class="sheet type-sheet" @click.stop>
      <text class="sheet-title">选择账户类型</text>
      <scroll-view scroll-y class="type-scroll">
        <view v-for="g in groupedTypes" :key="g.key" class="tg">
          <text class="tg-title">{{ g.label }}</text>
          <view class="tg-grid">
            <view v-for="t in g.types" :key="t.value" class="tt" @click="pick(t)">
              <AccountBadge :account="{ type: t.value }" :size="84" />
              <text class="tt-label">{{ t.label }}</text>
            </view>
          </view>
        </view>
      </scroll-view>
    </view>
  </view>
</template>

<style scoped>
.mask {
  position: fixed; inset: 0;
  background: rgba(15, 23, 42, 0.42);
  display: flex; align-items: flex-end;
  z-index: 600;
}
.sheet {
  width: 100%; background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 32rpx 32rpx calc(32rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.sheet-title { display: block; text-align: center; font-size: 30rpx; font-weight: 800; color: #16181c; margin-bottom: 20rpx; }
.type-sheet { max-height: 84vh; }
.type-scroll { max-height: 70vh; }
.tg { margin-bottom: 10rpx; }
.tg-title { font-size: 24rpx; font-weight: 700; color: #5b6470; padding: 12rpx 4rpx; }
.tg-grid { display: flex; flex-wrap: wrap; }
.tt { width: 20%; display: flex; flex-direction: column; align-items: center; gap: 10rpx; padding: 18rpx 0; }
.tt-ic { width: 84rpx; height: 84rpx; border-radius: 24rpx; background: #f4f5f7; display: flex; align-items: center; justify-content: center; }
.tt-label { font-size: 22rpx; color: #4b5563; }
</style>
