<script setup>
import { computed, ref } from 'vue'
import { onLoad, onReachBottom, onShareAppMessage } from '@dcloudio/uni-app'
import { fetchInviteInfo, fetchInviteQrCode, fetchInvitees } from '../../api/invite'
import {
  buildInviteLink,
  buildInviteSharePayload,
  hasMoreInvitees,
  inviteListStateAfterLoad,
  inviteStatusLabel,
  mergeInvitees,
  nextInviteListRequest,
  INVITE_LIST_STATE,
  INVITE_PAGE_SIZE
} from '../../utils/invite'

/**
 * 邀请好友页（需求 2.2、2.3、2.7、2.8、2.9、2.10、3.8、3.11、7.12、7.13、7.14）。
 *
 * 刻意维护**三条互相独立的状态机**（info / qr / list），不做整体 loading：
 * 二维码依赖微信接口，它挂了绝不能连坐邀请码、复制与转发（需求 3.8）；
 * 列表挂了也不能抹掉已展示的邀请码与链接（需求 7.12）。
 */

// 分页大小、分享标题、状态文案与分页决策统一取自 utils/invite.js（Property 17 在那里直接测纯逻辑）。
const INFO_TIMEOUT_MS = 10000 // 需求 2.8
const LIST_TIMEOUT_MS = 2000 // 需求 7.12

// ---- info：邀请码 / 邀请链接 / 已邀请人数 ----
const infoState = ref('loading') // loading | ready | error
const inviteCode = ref('')
const inviteLink = ref('')
const invitedCount = ref(0)

// ---- qr：二维码 ----
const qrState = ref('idle') // idle | loading | ready | failed
const qrBase64 = ref('')

// ---- list：被邀请人列表 ----
const listState = ref('loading') // loading | loaded | empty | error
const items = ref([])
const total = ref(0)
const nextPage = ref(0)
const lastAttemptPage = ref(0)
const loadingMore = ref(false)

// 三条状态机各自的请求序号：重试时丢弃迟到的旧响应，避免覆盖新结果。
let infoSeq = 0
let qrSeq = 0
let listSeq = 0

/** 客户端超时：底层请求仍会跑完，靠序号守卫忽略其迟到结果。 */
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

async function loadInfo() {
  const seq = ++infoSeq
  infoState.value = 'loading'
  try {
    const res = await withTimeout(fetchInviteInfo(), INFO_TIMEOUT_MS)
    if (seq !== infoSeq) return
    inviteCode.value = String(res?.inviteCode || '').trim()
    inviteLink.value =
      String(res?.inviteLink || '').trim() ||
      (inviteCode.value ? buildInviteLink(inviteCode.value) : '')
    invitedCount.value = Number(res?.invitedCount) || 0
    infoState.value = 'ready'
    // 二维码要用到邀请码，故 info 就绪后才发起（需求 3.12 的服务端惰性补齐也依赖这个顺序）。
    loadQrCode()
  } catch (e) {
    if (seq !== infoSeq) return
    infoState.value = 'error'
  }
}

async function loadQrCode() {
  const seq = ++qrSeq
  qrState.value = 'loading'
  try {
    const res = await fetchInviteQrCode()
    if (seq !== qrSeq) return
    const b64 = String(res?.imageBase64 || '').trim()
    if (!b64) {
      qrState.value = 'failed'
      return
    }
    qrBase64.value = b64
    qrState.value = 'ready'
  } catch (e) {
    if (seq !== qrSeq) return
    qrState.value = 'failed'
  }
}

async function loadList(page) {
  const seq = ++listSeq
  const isFirst = page === 0
  lastAttemptPage.value = page
  if (isFirst) listState.value = INVITE_LIST_STATE.LOADING
  else loadingMore.value = true
  try {
    const res = await withTimeout(fetchInvitees(page, INVITE_PAGE_SIZE), LIST_TIMEOUT_MS)
    if (seq !== listSeq) return
    total.value = Number(res?.total) || 0
    items.value = mergeInvitees(items.value, res?.items, page)
    nextPage.value = page + 1
    listState.value = inviteListStateAfterLoad(total.value)
  } catch (e) {
    // 失败只切状态，已加载的记录一行不动（需求 7.12）。
    if (seq !== listSeq) return
    listState.value = INVITE_LIST_STATE.ERROR
  } finally {
    if (seq === listSeq) loadingMore.value = false
  }
}

onLoad(() => {
  // info 与列表并发发起：两者互不依赖，任何一方失败都不阻塞另一方。
  loadInfo()
  loadList(0)
})

// 已加载条数达到总条数即停止请求（需求 7.13）。
const hasMore = computed(() => hasMoreInvitees(items.value.length, total.value))
onReachBottom(() => {
  const next = nextInviteListRequest({
    listState: listState.value,
    loadingMore: loadingMore.value,
    loaded: items.value.length,
    total: total.value,
    nextPage: nextPage.value
  })
  if (!next.shouldRequest) return
  loadList(next.page)
})

onShareAppMessage(() => {
  // info 未就绪时退化为不带 code 的落地页路径，并提示邀请码尚未就绪（需求 2.9）。
  const payload = buildInviteSharePayload(inviteLink.value)
  if (payload.degraded) {
    uni.showToast({ title: '邀请码尚未就绪', icon: 'none', duration: 1500 })
  }
  return { title: payload.title, path: payload.path }
})

// 战绩三栏依赖列表返回的 total，故只在列表确实有记录时展示：
// 无任何邀请关系时按原型与设计只显示「0 人 + 还没有好友通过你的邀请加入」，不渲染 0/0/0 三栏。
const statsReady = computed(() => listState.value === INVITE_LIST_STATE.LOADED)
const invalidCount = computed(() => Math.max(0, total.value - invitedCount.value))
const qrSrc = computed(() => (qrBase64.value ? `data:image/png;base64,${qrBase64.value}` : ''))

function nicknameOf(item) {
  const n = item && item.nickname != null ? String(item.nickname).trim() : ''
  // 昵称为空只影响展示（灰色斜体占位），不改数据（需求 7.7）。
  return n || (item && item.status === 'INVALID' ? '昵称不可见' : '未设置昵称')
}
function hasNickname(item) {
  return !!(item && item.nickname != null && String(item.nickname).trim())
}
function avatarText(item) {
  return hasNickname(item) ? String(item.nickname).trim().slice(0, 1) : '·'
}
function registerLabel(item) {
  const s = String((item && item.registerTime) || '')
  const date = s.slice(0, 10)
  const time = s.slice(11, 16)
  return [date, time].filter(Boolean).join(' ') + (date ? ' 注册' : '')
}

/** 剪贴板写入原文；失败时留在原页，文本继续展示供手动选取（需求 2.3、2.10）。 */
function copyText(text, okTitle) {
  if (!text) return
  uni.setClipboardData({
    data: text,
    success() {
      uni.showToast({ title: okTitle, icon: 'success', duration: 1500 })
    },
    fail() {
      uni.showToast({ title: '复制失败，可长按文本手动复制', icon: 'none', duration: 1500 })
    }
  })
}
function copyCode() {
  copyText(inviteCode.value, '邀请码已复制')
}
function copyLink() {
  copyText(inviteLink.value, '链接已复制')
}

/** base64 → 临时文件 → 保存到相册；拒绝授权只提示，页面展示不变（需求 3.11）。 */
function saveQrToAlbum() {
  if (qrState.value !== 'ready' || !qrBase64.value) return
  const fsm = typeof uni.getFileSystemManager === 'function' ? uni.getFileSystemManager() : null
  const dir = (uni.env && uni.env.USER_DATA_PATH) || ''
  if (!fsm || !dir) {
    uni.showToast({ title: '当前环境不支持保存', icon: 'none', duration: 1500 })
    return
  }
  const filePath = `${dir}/youyu-invite-qrcode.png`
  fsm.writeFile({
    filePath,
    data: qrBase64.value,
    encoding: 'base64',
    success() {
      uni.saveImageToPhotosAlbum({
        filePath,
        success() {
          uni.showToast({ title: '已保存到相册', icon: 'success', duration: 1500 })
        },
        fail(err) {
          const msg = String((err && (err.errMsg || err.message)) || '')
          const denied = /deny|auth/i.test(msg)
          uni.showToast({
            title: denied ? '需要相册权限才能保存' : '保存失败，请稍后重试',
            icon: 'none',
            duration: 1500
          })
        }
      })
    },
    fail() {
      uni.showToast({ title: '保存失败，请稍后重试', icon: 'none', duration: 1500 })
    }
  })
}
</script>

<template>
  <view class="page">
    <!-- 战绩卡：主数字为已邀请人数（需求 2.7） -->
    <view v-if="infoState === 'ready'" class="hero">
      <view class="n">
        <text class="num">{{ invitedCount }}</text><text class="unit">人</text>
      </view>
      <text class="lbl">{{
        invitedCount > 0 ? '位好友通过你的邀请加入有余' : '还没有好友通过你的邀请加入'
      }}</text>
      <view v-if="statsReady" class="split">
        <view class="sp"><text class="b">{{ total }}</text><text class="k">累计邀请</text></view>
        <view class="sp"><text class="b">{{ invitedCount }}</text><text class="k">有效在册</text></view>
        <view class="sp"><text class="b">{{ invalidCount }}</text><text class="k">已注销</text></view>
      </view>
    </view>

    <!-- info 失败：不展示邀请码 / 链接 / 转发入口（需求 2.8） -->
    <view v-else-if="infoState === 'error'" class="fail-card">
      <AppIcon name="warning" :size="52" color="#c7ccd2" />
      <text class="f-t">邀请信息加载失败</text>
      <text class="f-d">网络不太顺畅，稍后再试一次</text>
      <text class="retry" @click="loadInfo">重试</text>
    </view>
    <view v-else class="fail-card">
      <text class="f-d">正在加载邀请信息…</text>
    </view>

    <!-- 邀请码 -->
    <view v-if="infoState === 'ready'" class="codebox">
      <text class="k">我的邀请码</text>
      <text class="code" selectable>{{ inviteCode }}</text>
      <view class="pill" @click="copyCode">复制邀请码</view>
    </view>

    <!-- 二维码：失败只降级这一块 -->
    <view v-if="infoState === 'ready'" class="qrbox">
      <image v-if="qrState === 'ready'" class="qr" :src="qrSrc" mode="widthFix" />
      <view v-else-if="qrState === 'loading'" class="qr-fail">
        <text>二维码生成中…</text>
      </view>
      <view v-else class="qr-fail">
        <AppIcon name="warning" :size="46" color="#c7ccd2" />
        <text>二维码暂时生成失败</text>
        <text class="rt" @click="loadQrCode">重试</text>
      </view>
      <text v-if="qrState === 'ready'" class="hint">微信扫码直达 · 扫码后自动带上你的邀请码</text>
      <text v-else class="hint">不影响转发与复制邀请码</text>
      <view v-if="qrState === 'ready'" class="pill" @click="saveQrToAlbum">保存到相册</view>
    </view>

    <!-- 转发与复制链接 -->
    <template v-if="infoState === 'ready'">
      <button class="cta" open-type="share">微信转发给好友</button>
      <view class="cta ghost" @click="copyLink">复制邀请链接</view>
      <text class="linktext" selectable>{{ inviteLink }}</text>
    </template>

    <!-- 邀请记录 -->
    <view v-if="listState === 'empty'" class="empty">
      <view class="ic"><AppIcon name="members" :size="56" color="#12a150" /></view>
      <text class="t">还没有邀请记录</text>
      <text class="d">把邀请卡片转发给正在记账的朋友，他注册成功后就会出现在这里。</text>
    </view>
    <text v-else-if="listState === 'loading' && !items.length" class="more">加载中…</text>
    <template v-else>
      <view class="sect">邀请记录（{{ total }}）</view>
      <view v-if="items.length" class="card">
        <view v-for="it in items" :key="it.inviteId" class="ivt">
          <view class="av" :class="{ gy: !hasNickname(it) }">{{ avatarText(it) }}</view>
          <view class="m">
            <text class="nm" :class="{ none: !hasNickname(it) }">{{ nicknameOf(it) }}</text>
            <text class="tm">{{ registerLabel(it) }}</text>
          </view>
          <text class="st" :class="it.status === 'INVALID' ? 'no' : 'ok'">{{
            inviteStatusLabel(it.status)
          }}</text>
        </view>
      </view>
      <!-- 列表失败：失败文案 + 重试，已加载记录保留 -->
      <view v-if="listState === 'error'" class="listfail">
        <text class="lf-t">邀请记录加载失败</text>
        <text class="rt" @click="loadList(lastAttemptPage)">重试</text>
      </view>
      <text v-else-if="loadingMore || listState === 'loading'" class="more">加载中…</text>
      <text v-else-if="items.length && !hasMore" class="more">没有更多了</text>
    </template>

    <view style="height: 60rpx"></view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f2f4f6;
  padding: 24rpx 24rpx 0;
}
/* 战绩卡 */
.hero {
  background: linear-gradient(135deg, #22c55e, #0f8a45 72%);
  border-radius: 28rpx;
  padding: 34rpx 30rpx;
  color: #fff;
  text-align: center;
  box-shadow: 0 20rpx 40rpx rgba(18, 161, 80, 0.28);
}
.hero .n {
  display: flex;
  align-items: baseline;
  justify-content: center;
}
.hero .num {
  font-size: 86rpx;
  font-weight: 800;
  line-height: 1.05;
}
.hero .unit {
  font-size: 28rpx;
  font-weight: 600;
  margin-left: 8rpx;
  opacity: 0.9;
}
.hero .lbl {
  display: block;
  font-size: 24rpx;
  opacity: 0.92;
  margin-top: 10rpx;
}
.hero .split {
  display: flex;
  margin-top: 28rpx;
  border-top: 1rpx solid rgba(255, 255, 255, 0.22);
  padding-top: 22rpx;
}
.hero .sp {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}
.hero .sp .b {
  font-size: 34rpx;
  font-weight: 800;
}
.hero .sp .k {
  font-size: 22rpx;
  opacity: 0.9;
}
/* 失败卡 */
.fail-card {
  background: #fff;
  border-radius: 24rpx;
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
/* 邀请码 */
.codebox {
  background: #fff;
  border-radius: 24rpx;
  margin-top: 24rpx;
  padding: 30rpx;
  text-align: center;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
}
.codebox .k {
  font-size: 24rpx;
  color: #9aa2ad;
}
.codebox .code {
  display: block;
  margin-top: 14rpx;
  font-family: Menlo, monospace;
  font-size: 54rpx;
  font-weight: 800;
  letter-spacing: 10rpx;
  color: #111827;
}
.pill {
  display: inline-block;
  margin-top: 22rpx;
  font-size: 25rpx;
  color: #12a150;
  font-weight: 600;
  border: 1rpx solid #12a150;
  border-radius: 999rpx;
  padding: 10rpx 34rpx;
}
/* 二维码 */
.qrbox {
  background: #fff;
  border-radius: 24rpx;
  margin-top: 20rpx;
  padding: 34rpx 30rpx;
  text-align: center;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
}
.qr {
  width: 300rpx;
  height: 300rpx;
  margin: 0 auto 20rpx;
}
.qr-fail {
  width: 300rpx;
  height: 300rpx;
  margin: 0 auto 20rpx;
  border: 1rpx dashed #d6dade;
  border-radius: 24rpx;
  background: #fafbfc;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 14rpx;
  font-size: 24rpx;
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
.qrbox .hint {
  display: block;
  font-size: 23rpx;
  color: #9aa2ad;
  line-height: 1.6;
}
/* 主按钮 */
.cta {
  margin-top: 24rpx;
  background: linear-gradient(135deg, #18b85a, #0e8a44);
  color: #fff;
  border-radius: 24rpx;
  padding: 28rpx;
  text-align: center;
  font-size: 32rpx;
  font-weight: 700;
  line-height: 1.4;
  border: none;
  box-shadow: 0 14rpx 28rpx rgba(18, 161, 80, 0.32);
}
.cta::after {
  border: none;
}
.cta.ghost {
  background: #fff;
  color: #12a150;
  border: 1rpx solid #d8ece1;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
  margin-top: 18rpx;
}
.linktext {
  display: block;
  margin: 16rpx 8rpx 0;
  font-size: 22rpx;
  color: #9aa2ad;
  word-break: break-all;
}
/* 邀请记录 */
.sect {
  font-size: 24rpx;
  color: #9aa2ad;
  padding: 30rpx 8rpx 12rpx;
}
.card {
  background: #fff;
  border-radius: 20rpx;
  overflow: hidden;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
}
.ivt {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 24rpx 28rpx;
  border-top: 1rpx solid #eef0f2;
}
.card .ivt:first-child {
  border-top: none;
}
.av {
  width: 72rpx;
  height: 72rpx;
  border-radius: 50%;
  background: #e7f7ee;
  color: #12a150;
  font-size: 28rpx;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}
.av.gy {
  background: #eef1f4;
  color: #9aa2ad;
}
.ivt .m {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.ivt .nm {
  font-size: 29rpx;
  font-weight: 600;
  color: #25292e;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.ivt .nm.none {
  color: #9aa2ad;
  font-weight: 500;
  font-style: italic;
}
.ivt .tm {
  font-size: 22rpx;
  color: #9aa2ad;
}
.st {
  font-size: 22rpx;
  font-weight: 600;
  border-radius: 999rpx;
  padding: 6rpx 18rpx;
  flex: 0 0 auto;
}
.st.ok {
  background: #e7f7ee;
  color: #12a150;
}
.st.no {
  background: #eef1f4;
  color: #8b929b;
}
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
.more {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: #9aa2ad;
  padding: 24rpx 0 8rpx;
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
</style>
