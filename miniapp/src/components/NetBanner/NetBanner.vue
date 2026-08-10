<script setup>
/**
 * 全局网络 / 同步横幅（Offline_Sync_System）。挂在首页与流水页顶部。
 * 四态：离线（灰）/ 同步中（绿·进度）/ 需处理（红·失败）/ 已同步（绿·短暂淡出）。
 * 全部由 net / sync store 驱动；无待同步且在线且无失败时不渲染。
 */
import { ref, computed, watch, onMounted, onUnmounted } from 'vue'
import { useNetStore } from '../../stores/net'
import { useSyncStore } from '../../stores/sync'

const net = useNetStore()
const sync = useSyncStore()

function refresh() { sync.refresh() }
onMounted(() => {
  refresh()
  try { uni.$on('offline:sync-done', refresh) } catch (e) {}
})
onUnmounted(() => {
  try { uni.$off('offline:sync-done', refresh) } catch (e) {}
})

// 「已同步」短暂提示：监听同步结束（syncing true→false）且无失败时点亮 2s。
const justDone = ref(false)
let doneTimer = null
watch(
  () => sync.syncing,
  (now, prev) => {
    if (prev && !now && sync.failedCount === 0) {
      justDone.value = true
      if (doneTimer) clearTimeout(doneTimer)
      doneTimer = setTimeout(() => { justDone.value = false }, 2000)
    }
  }
)

const state = computed(() => {
  if (!net.online) return 'offline'
  if (sync.syncing) return 'syncing'
  if (sync.failedCount > 0) return 'failed'
  if (justDone.value) return 'done'
  return 'hidden'
})

const text = computed(() => {
  switch (state.value) {
    case 'offline':
      return sync.pendingCount > 0
        ? `当前离线 · ${sync.pendingCount} 笔待同步`
        : '当前离线 · 记账将在联网后自动同步'
    case 'syncing':
      return `正在同步 ${sync.progress.total || sync.pendingCount} 笔记录…`
    case 'failed':
      return `${sync.failedCount} 笔需要处理`
    case 'done':
      return '已同步'
    default:
      return ''
  }
})

function goSyncCenter() {
  uni.navigateTo({ url: '/pages/sync/sync' })
}
</script>

<template>
  <view v-if="state !== 'hidden'" class="netbar" :class="state" @click="state === 'failed' ? goSyncCenter() : null">
    <text v-if="state === 'offline'" class="ico">📴</text>
    <view v-else-if="state === 'syncing'" class="spin" />
    <text v-else-if="state === 'failed'" class="ico">⚠️</text>
    <text v-else class="ico">✅</text>
    <text class="txt">{{ text }}</text>
    <text v-if="state === 'failed'" class="act">查看</text>
  </view>
</template>

<style scoped>
.netbar { display: flex; align-items: center; gap: 12rpx; padding: 14rpx 28rpx; font-size: 24rpx; font-weight: 700; }
.netbar.offline { background: #4a525c; color: #fff; }
.netbar.syncing { background: #eaf6ee; color: #0e8a44; }
.netbar.failed { background: #fdece9; color: #e5563d; }
.netbar.done { background: #12a150; color: #fff; }
.ico { font-size: 26rpx; }
.txt { flex: 1; }
.act { font-size: 24rpx; font-weight: 800; text-decoration: underline; }
.spin { width: 26rpx; height: 26rpx; border: 3rpx solid rgba(18,161,80,.25); border-top-color: #12a150; border-radius: 50%; animation: nb-spin .8s linear infinite; }
@keyframes nb-spin { to { transform: rotate(360deg); } }
</style>
