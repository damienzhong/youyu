<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { personalityTags, shiftMonth } from '../../api/report'
import { tagToDisplay } from '../../utils/personalityTags'
import { useLedgerStore } from '../../stores/ledger'
import { currentMonth, monthLabel } from '../../utils/format'

/**
 * 消费人格标签（趣味人格标签）独立页：复用只读端点 GET /api/reports/personality-tags
 * 与纯逻辑 utils/personalityTags.js#tagToDisplay。默认展示上一个完整月
 * （进行中的当月按设计走鼓励兜底，独立页里默认看已完结月更有内容）。
 */

const ledgerStore = useLedgerStore()
// 默认上一个完整月：当月为 partial，v1 全部标签依赖完整月 → 会走兜底。
const month = ref(shiftMonth(currentMonth(), -1))
const loading = ref(true)
const data = ref(null)
const isAll = computed(() => ledgerStore.isAll)
const tags = computed(() => (data.value?.tags || []).map(tagToDisplay))
const statusText = computed(() => (data.value?.monthStatus === 'final' ? '已完结' : '进行中'))

async function load() {
  if (isAll.value) { loading.value = false; return }
  loading.value = true
  try {
    data.value = await personalityTags(month.value)
  } catch (e) {
    data.value = null
  } finally {
    loading.value = false
  }
}
onLoad(() => { load() })
</script>

<template>
  <view class="page">
    <view class="head">
      <text class="h-title">🏷️ 消费人格</text>
      <text class="h-sub">{{ monthLabel(month) }} · {{ statusText }}</text>
    </view>

    <view v-if="isAll" class="empty">请切换到具体账本查看你的消费人格</view>
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="!data" class="empty">暂时看不了，稍后再试</view>
    <view v-else-if="data.isFallback" class="fallback">{{ data.fallbackText }}</view>
    <view v-else class="tags">
      <view v-for="(t, i) in tags" :key="i" class="tag">
        <text class="tg-emoji">{{ t.emoji }}</text>
        <view class="tg-main">
          <text class="tg-title">{{ t.title }}</text>
          <text class="tg-text">{{ t.text }}</text>
        </view>
      </view>
    </view>

    <view style="height:40rpx;"></view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; background: #eef0f2; padding: 24rpx; }
.head { padding: 8rpx 8rpx 20rpx; }
.h-title { font-size: 40rpx; font-weight: 800; color: #16181c; }
.h-sub { display: block; font-size: 24rpx; color: #9aa2ad; margin-top: 8rpx; }
.empty { margin-top: 120rpx; text-align: center; color: #9aa2ad; font-size: 28rpx; }
.fallback {
  background: #fff; border-radius: 22rpx; padding: 40rpx 32rpx;
  font-size: 30rpx; color: #5b6470; line-height: 1.7; text-align: center;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.tags { display: flex; flex-direction: column; gap: 16rpx; }
.tag {
  display: flex; align-items: center; gap: 22rpx;
  background: #fff; border-radius: 22rpx; padding: 28rpx 28rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.tg-emoji {
  width: 80rpx; height: 80rpx; border-radius: 22rpx; flex: 0 0 auto;
  background: #fdf3e2; display: flex; align-items: center; justify-content: center;
  font-size: 44rpx;
}
.tg-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 8rpx; }
.tg-title { font-size: 32rpx; font-weight: 800; color: #16181c; }
.tg-text { font-size: 26rpx; color: #5b6470; line-height: 1.6; }
</style>
