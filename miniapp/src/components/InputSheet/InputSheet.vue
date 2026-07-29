<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  visible: { type: Boolean, default: false },
  title: { type: String, default: '' },
  placeholder: { type: String, default: '' },
  value: { type: String, default: '' },
  confirmText: { type: String, default: '保存' },
  maxlength: { type: Number, default: 50 },
  tip: { type: String, default: '' },
  type: { type: String, default: 'text' }
})
const emit = defineEmits(['update:visible', 'confirm'])

const text = ref('')
watch(
  () => props.visible,
  (v) => {
    if (v) text.value = props.value || ''
  }
)

function close() {
  emit('update:visible', false)
}
function confirm() {
  emit('confirm', (text.value || '').trim())
}
</script>

<template>
  <view v-if="visible" class="isheet-mask" @click="close">
    <view class="isheet" @click.stop>
      <view class="ish-head">
        <text class="ish-cancel" @click="close">取消</text>
        <text class="ish-title">{{ title }}</text>
        <text class="ish-save" @click="confirm">{{ confirmText }}</text>
      </view>
      <input
        class="ish-input"
        v-model="text"
        :type="type"
        :placeholder="placeholder"
        :maxlength="maxlength"
        focus
        confirm-type="done"
        @confirm="confirm"
      />
      <text v-if="tip" class="ish-tip">{{ tip }}</text>
    </view>
  </view>
</template>

<style scoped>
.isheet-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.42);
  display: flex;
  align-items: flex-end;
  z-index: 60;
}
.isheet {
  width: 100%;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 28rpx 32rpx calc(32rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.ish-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20rpx;
}
.ish-cancel {
  font-size: 28rpx;
  color: #9aa2ad;
}
.ish-title {
  font-size: 30rpx;
  font-weight: 800;
  color: #16181c;
}
.ish-save {
  font-size: 28rpx;
  color: #12a150;
  font-weight: 700;
}
.ish-input {
  background: #f6f7f9;
  border-radius: 16rpx;
  padding: 28rpx 26rpx;
  font-size: 32rpx;
  color: #16181c;
}
.ish-tip {
  display: block;
  font-size: 22rpx;
  color: #9aa2ad;
  margin-top: 16rpx;
  line-height: 1.6;
}
</style>
