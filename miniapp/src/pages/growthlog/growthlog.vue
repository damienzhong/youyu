<script setup>
import { computed, ref } from 'vue'
import { onLoad, onReachBottom } from '@dcloudio/uni-app'
import { fetchGrowthEvents } from '../../api/growth'
import { growthEventLabel, hasMoreGrowthEvents, GROWTH_PAGE_SIZE, GROWTH_TIMEOUT_MS } from '../../utils/growth'

/**
 * 经验明细页（需求 13.10、13.11、13.12、13.14）。
 *
 * 单一列表状态机：LOADING → EMPTY / LOADED / ERROR，LOADED → LOADING_MORE → LOADED。
 * 本页**只读**、不触发结算——fetchGrowthEvents 对应服务端 GET /api/growth/events，
 * 不做结算，故返回数据可能比成长页概览旧，属预期行为（需求 10.11）。
 *
 * 请求序号机制沿用邀请页写法：每次请求自增 seq，响应回来时 seq 不匹配即丢弃，
 * 避免重试时迟到的旧响应覆盖新结果。
 */

const LIST_STATE = { LOADING: 'loading', EMPTY: 'empty', LOADED: 'loaded', ERROR: 'error' }

const listState = ref(LIST_STATE.LOADING)
const items = ref([])
const total = ref(0)
const nextPage = ref(0)
const lastAttemptPage = ref(0)
const loadingMore = ref(false)

// 请求序号：重试时丢弃迟到的旧响应。
let listSeq = 0

/** 客户端超时：底层请求仍会跑完，靠序号守卫忽略其迟到结果（需求 13.14）。 */
function withTimeout(promise, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject({ code: 'TIMEOUT', message: '请求超时' }), ms)
    promise.then(
      (v) => {
        clearTimeout(timer)
        resolve(v)
      },
      (e) => {
        clearTimeout(timer)
        reject(e)
      }
    )
  })
}

async function loadPage(page) {
  const seq = ++listSeq
  const isFirst = page === 0
  lastAttemptPage.value = page
  if (isFirst) listState.value = LIST_STATE.LOADING
  else loadingMore.value = true
  try {
    const res = await withTimeout(fetchGrowthEvents(page, GROWTH_PAGE_SIZE), GROWTH_TIMEOUT_MS)
    if (seq !== listSeq) return
    total.value = Number(res?.total) || 0
    const list = Array.isArray(res?.items) ? res.items : []
    // 首屏替换、后续追加；重试从失败那一页重发时首屏亦替换，保证不重复。
    items.value = isFirst ? list : [...items.value, ...list]
    nextPage.value = page + 1
    listState.value = total.value > 0 ? LIST_STATE.LOADED : LIST_STATE.EMPTY
  } catch (e) {
    // 失败只切状态，已加载的记录一行不动（需求 13.12）。
    if (seq !== listSeq) return
    listState.value = LIST_STATE.ERROR
  } finally {
    if (seq === listSeq) loadingMore.value = false
  }
}

onLoad(() => {
  loadPage(0)
})

// 已加载条数达到总条数即停止请求（需求 13.10）。
const hasMore = computed(() => hasMoreGrowthEvents(items.value.length, total.value))
onReachBottom(() => {
  if (listState.value !== LIST_STATE.LOADED) return
  if (loadingMore.value) return
  if (!hasMore.value) return
  loadPage(nextPage.value)
})

function labelOf(it) {
  return growthEventLabel(it && it.eventType, it && it.eventKey)
}
function expText(it) {
  const n = Number(it && it.expAmount)
  return Number.isFinite(n) ? `+${n}` : '+0'
}
function timeOf(it) {
  const s = String((it && it.createdAt) || '')
  const date = s.slice(0, 10)
  const time = s.slice(11, 16)
  return [date, time].filter(Boolean).join(' ')
}
function goRecord() {
  uni.navigateTo({ url: '/pages/record/record' })
}
</script>

<template>
  <view class="page">
    <!-- 首屏加载 -->
    <text v-if="listState === 'loading' && !items.length" class="more">加载中…</text>

    <!-- 空状态：不渲染列表区域（需求 13.11） -->
    <view v-else-if="listState === 'empty'" class="empty">
      <view class="ic"><AppIcon name="badge" :size="56" color="#12a150" /></view>
      <text class="t">还没有经验记录</text>
      <text class="d">记下第一笔账，就能开始积累经验、点亮徽章。</text>
      <view class="pill" @click="goRecord">去记一笔</view>
    </view>

    <!-- 列表 -->
    <template v-else>
      <view class="sect">经验明细（{{ total }}）</view>
      <view v-if="items.length" class="card">
        <view v-for="it in items" :key="it.id" class="row">
          <view class="r-ic"><AppIcon name="star" :size="40" color="#12a150" /></view>
          <view class="r-main">
            <text class="r-name">{{ labelOf(it) }}</text>
            <text class="r-time">{{ timeOf(it) }}</text>
          </view>
          <text class="r-exp">{{ expText(it) }}</text>
        </view>
      </view>

      <!-- 加载更多失败：失败文案 + 重试，已加载记录保留（需求 13.12） -->
      <view v-if="listState === 'error'" class="listfail">
        <text class="lf-t">经验明细加载失败</text>
        <text class="rt" @click="loadPage(lastAttemptPage)">重试</text>
      </view>
      <text v-else-if="loadingMore" class="more">加载中…</text>
      <text v-else-if="items.length && !hasMore" class="more">没有更多了</text>
    </template>

    <!-- 首屏失败（尚无任何记录时）：整屏失败态 + 重试 -->
    <view v-if="listState === 'error' && !items.length" class="fail-card">
      <AppIcon name="warning" :size="52" color="#c7ccd2" />
      <text class="f-t">经验明细加载失败</text>
      <text class="f-d">网络不太顺畅，稍后再试一次</text>
      <text class="retry" @click="loadPage(lastAttemptPage)">重试</text>
    </view>

    <view style="height: 60rpx"></view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f2f4f6;
  padding: 24rpx 24rpx 0;
}
.sect {
  font-size: 24rpx;
  color: #9aa2ad;
  padding: 20rpx 8rpx 12rpx;
}
.card {
  background: #fff;
  border-radius: 20rpx;
  overflow: hidden;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
}
.row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 24rpx 28rpx;
  border-top: 1rpx solid #eef0f2;
}
.card .row:first-child {
  border-top: none;
}
.r-ic {
  width: 72rpx;
  height: 72rpx;
  border-radius: 20rpx;
  background: #e7f7ee;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}
.r-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.r-name {
  font-size: 29rpx;
  font-weight: 600;
  color: #25292e;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.r-time {
  font-size: 22rpx;
  color: #9aa2ad;
}
.r-exp {
  font-size: 30rpx;
  font-weight: 800;
  color: #12a150;
  flex: 0 0 auto;
}
/* 加载更多 / 没有更多 */
.more {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: #9aa2ad;
  padding: 24rpx 0 8rpx;
}
/* 加载更多失败 */
.listfail {
  margin-top: 20rpx;
  background: #fff;
  border-radius: 20rpx;
  padding: 30rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 20rpx;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
}
.listfail .lf-t {
  font-size: 26rpx;
  color: #9aa2ad;
}
.rt {
  font-size: 24rpx;
  color: #12a150;
  font-weight: 600;
  border: 1rpx solid #12a150;
  border-radius: 999rpx;
  padding: 6rpx 26rpx;
}
/* 首屏失败卡 */
.fail-card {
  background: #fff;
  border-radius: 24rpx;
  margin-top: 24rpx;
  padding: 52rpx 30rpx;
  text-align: center;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
}
.fail-card .f-t {
  font-size: 30rpx;
  font-weight: 700;
  color: #25292e;
}
.fail-card .f-d {
  font-size: 24rpx;
  color: #9aa2ad;
}
.retry {
  margin-top: 10rpx;
  font-size: 26rpx;
  color: #12a150;
  font-weight: 600;
  border: 1rpx solid #12a150;
  border-radius: 999rpx;
  padding: 8rpx 32rpx;
}
/* 空状态 */
.empty {
  background: #fff;
  border-radius: 24rpx;
  margin-top: 24rpx;
  padding: 64rpx 40rpx;
  text-align: center;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
  display: flex;
  flex-direction: column;
  align-items: center;
}
.empty .ic {
  width: 116rpx;
  height: 116rpx;
  border-radius: 50%;
  background: #e7f7ee;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 24rpx;
}
.empty .t {
  font-size: 30rpx;
  font-weight: 700;
  color: #25292e;
  margin-bottom: 10rpx;
}
.empty .d {
  font-size: 24rpx;
  color: #9aa2ad;
  line-height: 1.7;
}
.pill {
  display: inline-block;
  margin-top: 24rpx;
  font-size: 25rpx;
  color: #12a150;
  font-weight: 600;
  border: 1rpx solid #12a150;
  border-radius: 999rpx;
  padding: 10rpx 34rpx;
}
</style>
