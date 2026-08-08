<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { aiInsights, shiftMonth } from '../../api/report'
import { insightToDisplay } from '../../utils/insights'
import { useLedgerStore } from '../../stores/ledger'
import { currentMonth, monthLabel } from '../../utils/format'

/**
 * 趣味分析（AI 趣味分析）独立页：复用只读端点 GET /api/reports/ai-insights
 * 与纯逻辑 utils/insights.js#insightToDisplay。默认展示上一个完整月
 * （进行中的当月按设计走鼓励兜底，独立页里默认看已完结月更有内容）。
 */

const ledgerStore = useLedgerStore()
const month = ref(shiftMonth(currentMonth(), -1))
const loading = ref(true)
const data = ref(null)
const isAll = computed(() => ledgerStore.isAll)
const insights = computed(() => (data.value?.insights || []).map(insightToDisplay))
const statusText = computed(() => (data.value?.monthStatus === 'final' ? '已完结' : '进行中'))

async function load() {
  if (isAll.value) { loading.value = false; return }
  loading.value = true
  try {
    data.value = await aiInsights(month.value)
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
      <text class="h-title">🧋 趣味分析</text>
      <text class="h-sub">{{ monthLabel(month) }} · {{ statusText }}</text>
    </view>

    <view v-if="isAll" class="empty">请切换到具体账本查看趣味分析</view>
    <view v-else-if="loading" class="empty">加载中…</view>
    <view v-else-if="!data" class="empty">暂时看不了，稍后再试</view>
    <view v-else-if="data.isFallback" class="fallback">{{ data.fallbackText }}</view>
    <view v-else class="list">
      <view v-for="(it, i) in insights" :key="i" class="ins" :class="it.tone">
        <text class="ins-ic">{{ it.icon }}</text>
        <view class="ins-main">
          <text v-if="it.dimensionName" class="ins-dim">{{ it.dimensionName }}</text>
          <text class="ins-text">{{ it.text }}</text>
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
.list { display: flex; flex-direction: column; gap: 16rpx; }
.ins {
  display: flex; align-items: flex-start; gap: 20rpx;
  background: #fff; border-radius: 22rpx; padding: 28rpx 28rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
  border-left: 8rpx solid #12a150;
}
.ins.reminder { border-left-color: #f4a72b; }
.ins-ic { font-size: 40rpx; flex: 0 0 auto; }
.ins-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.ins-dim { font-size: 24rpx; color: #9aa2ad; }
.ins-text { font-size: 30rpx; color: #16181c; line-height: 1.6; font-weight: 600; }
</style>
