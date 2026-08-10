<script setup>
/**
 * 同步中心（Offline_Sync_System）：待同步/需处理统计、失败项处理、立即同步、仅 Wi-Fi 开关、缓存清理。
 */
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useNetStore } from '../../stores/net'
import { useSyncStore } from '../../stores/sync'
import * as outbox from '../../utils/offline/outbox'
import { runSync } from '../../utils/offline/sync'
import { clearCache, cacheSize } from '../../utils/offline/cache'
import { formatAmount } from '../../utils/format'

const net = useNetStore()
const sync = useSyncStore()

const items = ref([])
const cache = ref({ count: 0, bytes: 0 })

function reload() {
  try { items.value = outbox.list() } catch (e) { items.value = [] }
  cache.value = cacheSize()
  sync.refresh()
}

onShow(() => { reload() })

const failedItems = computed(() => items.value.filter((i) => i.status === 'FAILED'))

const lastSyncLabel = computed(() => {
  if (!sync.lastSyncAt) return '暂无'
  const d = new Date(sync.lastSyncAt)
  const pad = (n) => String(n).padStart(2, '0')
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
})

const cacheLabel = computed(() => {
  const kb = cache.value.bytes / 1024
  return kb < 1024 ? `${kb.toFixed(1)} KB` : `${(kb / 1024).toFixed(2)} MB`
})

async function syncNow() {
  if (!net.online) {
    uni.showToast({ title: '当前离线，无法同步', icon: 'none' })
    return
  }
  uni.showLoading({ title: '同步中…' })
  try {
    await runSync({ manual: true })
  } finally {
    uni.hideLoading()
    reload()
  }
}

function retryOne(it) {
  outbox.retry(it.clientToken)
  reload()
  syncNow()
}

function removeOne(it) {
  uni.showModal({
    title: '删除待同步记录',
    content: '删除后这笔账将不会被上传，且无法恢复。',
    confirmText: '删除',
    confirmColor: '#e5563d',
    success: (r) => {
      if (r.confirm) {
        outbox.removeByToken(it.clientToken)
        reload()
      }
    }
  })
}

function toggleWifiOnly(e) {
  net.setWifiOnly(e.detail.value)
}

function doClearCache() {
  uni.showModal({
    title: '清理离线缓存',
    content: '仅清理用于离线查看的数据快照，不影响待同步的记账。',
    confirmText: '清理',
    success: (r) => {
      if (r.confirm) {
        const n = clearCache()
        reload()
        uni.showToast({ title: `已清理 ${n} 项`, icon: 'none' })
      }
    }
  })
}

function catLabel(it) {
  const p = it.payload || {}
  const sign = p.type === 'income' ? '+' : '-'
  return `${sign}¥${formatAmount(Number(p.amount || 0))}`
}
</script>

<template>
  <view class="page">
    <view class="hero" :class="{ off: !net.online }">
      <text class="title">🛰️ 同步中心</text>
      <view class="stat">
        <view class="box"><text class="num">{{ sync.pendingCount }}</text><text class="lab">待同步</text></view>
        <view class="box"><text class="num">{{ sync.failedCount }}</text><text class="lab">需处理</text></view>
        <view class="box"><text class="num sm">{{ lastSyncLabel }}</text><text class="lab">上次同步</text></view>
      </view>
    </view>

    <view v-if="failedItems.length" class="sec">需处理</view>
    <view v-if="failedItems.length" class="card">
      <view v-for="it in failedItems" :key="it.clientToken" class="row">
        <view class="ri">⚠️</view>
        <view class="rt">
          <text class="rname">{{ catLabel(it) }}<text v-if="it.payload && it.payload.note"> · {{ it.payload.note }}</text></text>
          <text class="rsub">{{ it.failReason || '同步失败' }}</text>
        </view>
        <text class="btn retry" @click="retryOne(it)">重试</text>
        <text class="btn del" @click="removeOne(it)">删除</text>
      </view>
    </view>

    <view class="sec">操作</view>
    <view class="card">
      <view class="row tap" @click="syncNow">
        <view class="ri">🔄</view>
        <view class="rt"><text class="rname">立即同步</text><text class="rsub">手动触发一次全量同步</text></view>
        <text class="chev">›</text>
      </view>
      <view class="row">
        <view class="ri">📶</view>
        <view class="rt"><text class="rname">仅 Wi-Fi 下同步</text><text class="rsub">省流量，蜂窝网络下暂缓自动同步</text></view>
        <switch :checked="net.wifiOnly" color="#12a150" @change="toggleWifiOnly" />
      </view>
      <view class="row tap" @click="doClearCache">
        <view class="ri">🗂️</view>
        <view class="rt"><text class="rname">离线缓存</text><text class="rsub">已缓存 {{ cache.count }} 项 · 占用 {{ cacheLabel }}</text></view>
        <text class="chev">清理 ›</text>
      </view>
    </view>

    <view class="bigbtn" @click="syncNow">🔄 立即同步</view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; background: #eef0f2; padding-bottom: 60rpx; }
.hero { background: linear-gradient(155deg,#1fbf63,#0f8a45 78%); color: #fff; padding: 44rpx 36rpx 52rpx; }
.hero.off { background: linear-gradient(155deg,#6b7480,#4a525c 80%); }
.title { font-size: 36rpx; font-weight: 800; }
.stat { display: flex; gap: 20rpx; margin-top: 28rpx; }
.box { flex: 1; background: rgba(255,255,255,.16); border-radius: 24rpx; padding: 24rpx; }
.num { font-size: 44rpx; font-weight: 800; display: block; }
.num.sm { font-size: 30rpx; }
.lab { font-size: 22rpx; opacity: .85; margin-top: 6rpx; display: block; }
.sec { font-size: 24rpx; font-weight: 800; color: #9aa2ad; letter-spacing: .08em; padding: 28rpx 32rpx 10rpx; }
.card { background: #fff; border-radius: 20rpx; margin: 0 24rpx; overflow: hidden; box-shadow: 0 6rpx 18rpx rgba(20,24,28,.05); }
.row { display: flex; align-items: center; gap: 20rpx; padding: 26rpx 28rpx; border-top: 1rpx solid #f1f3f5; }
.row:first-child { border-top: none; }
.ri { width: 64rpx; height: 64rpx; border-radius: 18rpx; background: #f6f7f9; display: flex; align-items: center; justify-content: center; font-size: 30rpx; flex: 0 0 auto; }
.rt { flex: 1; min-width: 0; }
.rname { font-size: 28rpx; font-weight: 600; color: #16181c; }
.rsub { font-size: 22rpx; color: #9aa2ad; margin-top: 4rpx; display: block; }
.chev { color: #9aa2ad; font-size: 26rpx; }
.btn { font-size: 22rpx; font-weight: 800; padding: 8rpx 20rpx; border-radius: 12rpx; flex: 0 0 auto; margin-left: 10rpx; }
.btn.retry { color: #0e8a44; border: 1rpx solid #b7e3c6; }
.btn.del { color: #e5563d; border: 1rpx solid #f6c5bb; }
.bigbtn { margin: 32rpx 24rpx; height: 96rpx; border-radius: 26rpx; background: #12a150; color: #fff; font-size: 30rpx; font-weight: 800; display: flex; align-items: center; justify-content: center; gap: 12rpx; box-shadow: 0 12rpx 26rpx rgba(18,161,80,.26); }
</style>
