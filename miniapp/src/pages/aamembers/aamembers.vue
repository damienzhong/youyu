<script setup>
import { ref, computed, nextTick } from 'vue'
import { onLoad, onShow, onShareAppMessage, onPullDownRefresh } from '@dcloudio/uni-app'
import { useLedgerStore } from '../../stores/ledger'
import { useAuthStore } from '../../stores/auth'
import { createInvite, listMembers, removeMember } from '../../api/ledger'
import { buildInviteLink, INVITE_SHARE_TITLE } from '../../utils/invite'
import { qrMatrix } from '../../utils/qrcode'

/**
 * AA 账本 · 成员页（任务 7.2；需求 2.1、2.5、2.6、8.3）。
 *
 * 复用既有账本邀请机制（api/ledger.js 的 createInvite / listMembers / removeMember），
 * 不新增后端接口：
 * - 邀请：OWNER 生成账本邀请码（POST /api/ledgers/{id}/invite），据此拼出邀请链接并渲染二维码，
 *   支持复制码 / 复制链接 / 微信转发（受邀人打开后需先注册 / 登录才能加入，需求 2.1、2.2）。
 * - 成员列表：GET /api/ledgers/{id}/members 返回 displayName / avatarSeed / role / owner，
 *   展示昵称 / 头像与「创建者」标识（需求 2.5）。
 * - 退出 / 移除：DELETE /api/ledgers/{id}/members/{userId}；后端以 AA_MEMBER_UNSETTLED(409)
 *   拦截未结清成员，本页转成「请先结清」明确提示（需求 2.6）；OWNER 不可被移除
 *   （MEMBER_OWNER_IMMUTABLE），故对 owner 隐藏退出 / 移除入口（需求 2.8）。
 * - 已归档（只读）账本：成员页仍可查看，但邀请 / 退出 / 移除入口隐藏 / 禁用（需求 8.3）。
 *
 * 视觉对齐 design/aa-ledger-prototype.html「② 成员」步骤。
 */

const ledgerStore = useLedgerStore()
const authStore = useAuthStore()

const ledgerId = ref(null)
const members = ref([])
const listState = ref('loading') // loading | loaded | error

const myUserId = computed(() => authStore.user?.id ?? authStore.user?.userId ?? null)

// 当前账本元信息（类型 / 角色 / 归档态 / 名称）取自账本 store；直达本页时兜底拉取一次。
const ledger = computed(
  () => ledgerStore.ledgers.find((l) => l.id === ledgerId.value) || null
)
const isOwner = computed(() => ledger.value?.role === 'OWNER')
const isArchived = computed(() => ledger.value?.archived === true)
const ledgerName = computed(() => ledger.value?.name || 'AA 账本')

onLoad((options) => {
  const raw = options && options.id != null ? Number(options.id) : null
  ledgerId.value = Number.isFinite(raw) ? raw : ledgerStore.currentLedgerId
})

onShow(async () => {
  if (!ledgerStore.ledgers.length) {
    try {
      await ledgerStore.load()
    } catch (e) {
      /* 账本列表拉取失败不阻断成员展示 */
    }
  }
  await loadMembers()
})

onPullDownRefresh(async () => {
  try {
    await ledgerStore.load()
  } catch (e) {
    /* ignore */
  }
  await loadMembers()
  uni.stopPullDownRefresh()
})

async function loadMembers() {
  if (ledgerId.value == null) {
    listState.value = 'error'
    return
  }
  listState.value = 'loading'
  try {
    members.value = await listMembers(ledgerId.value)
    listState.value = 'loaded'
  } catch (e) {
    listState.value = 'error'
    uni.showToast({ title: e.message || '加载成员失败', icon: 'none' })
  }
}

// ---- 头像：无头像图片，按 avatarSeed / 昵称首字 + 稳定色渲染（与分享卡片口径一致）----
const AVATAR_COLORS = [
  '#12a150', '#3a7bd5', '#e0609a', '#f0a13b', '#7c5cff', '#e5544b', '#0ea5a5', '#d97706'
]
function avatarChar(m) {
  const s = String(m.avatarSeed || m.displayName || '').trim()
  return s ? Array.from(s)[0] : '友'
}
function avatarColor(m) {
  const key = Number(m.userId) || 0
  return AVATAR_COLORS[key % AVATAR_COLORS.length]
}
function nameOf(m) {
  return m.displayName || '用户' + m.userId
}

// ---- 邀请：生成账本邀请码 → 链接 + 二维码 ----
const inviteSheet = ref(false)
const inviteBusy = ref(false)
const inviteCode = ref('')
const inviteLink = computed(() => (inviteCode.value ? buildInviteLink(inviteCode.value) : ''))
const qrState = ref('idle') // idle | ready | failed
const QR_CANVAS_ID = 'aaInviteQr'

async function openInvite() {
  if (isArchived.value || !isOwner.value || inviteBusy.value) return
  inviteBusy.value = true
  try {
    const res = await createInvite(ledgerId.value)
    inviteCode.value = String(res?.code || '').trim()
    inviteSheet.value = true
    // 打开后再绘制二维码：等待 canvas 挂载。
    await nextTick()
    drawQr()
  } catch (e) {
    uni.showToast({ title: e.message || '生成邀请链接失败', icon: 'none' })
  } finally {
    inviteBusy.value = false
  }
}

/** 把邀请链接编码为二维码并绘到 canvas；任何异常降级为仅展示链接 / 邀请码（需求 2.1）。 */
function drawQr() {
  qrState.value = 'idle'
  const link = inviteLink.value
  if (!link) {
    qrState.value = 'failed'
    return
  }
  try {
    const m = qrMatrix(link, { ecLevel: 'M' })
    const ctx = uni.createCanvasContext(QR_CANVAS_ID)
    const sizePx = 220 // 与 .qr-canvas 的 css 尺寸一致（px）
    const quiet = 2
    const cell = sizePx / (m.count + quiet * 2)
    ctx.setFillStyle('#ffffff')
    ctx.fillRect(0, 0, sizePx, sizePx)
    ctx.setFillStyle('#16181c')
    for (let r = 0; r < m.count; r++) {
      for (let c = 0; c < m.count; c++) {
        if (!m.isDark(r, c)) continue
        const x = (c + quiet) * cell
        const y = (r + quiet) * cell
        // +1 覆盖亚像素缝隙，保证扫码稳定。
        ctx.fillRect(Math.floor(x), Math.floor(y), Math.ceil(cell) + 1, Math.ceil(cell) + 1)
      }
    }
    ctx.draw(false, () => {
      qrState.value = 'ready'
    })
    qrState.value = 'ready'
  } catch (e) {
    qrState.value = 'failed'
  }
}

function copyCode() {
  if (!inviteCode.value) return
  uni.setClipboardData({
    data: inviteCode.value,
    success: () => uni.showToast({ title: '邀请码已复制', icon: 'none' })
  })
}
function copyLink() {
  if (!inviteLink.value) return
  uni.setClipboardData({
    data: inviteLink.value,
    success: () => uni.showToast({ title: '邀请链接已复制', icon: 'none' })
  })
}
function closeInvite() {
  inviteSheet.value = false
}

// 微信转发：把邀请链接作为分享路径（受邀人打开后先登录再加入）。
onShareAppMessage(() => ({
  title: `${INVITE_SHARE_TITLE}（${ledgerName.value}）`,
  path: inviteLink.value || '/pages/index/index'
}))

// ---- 退出 / 移除 ----
function canKick(m) {
  if (isArchived.value) return false
  if (m.owner || m.role === 'OWNER') return false // 创建者不可退出 / 移除（需求 2.8）
  const isSelf = m.userId === myUserId.value
  return isSelf || isOwner.value
}
function kickLabel(m) {
  return m.userId === myUserId.value ? '退出' : '移除'
}

function kick(m) {
  if (!canKick(m)) return
  const isSelf = m.userId === myUserId.value
  uni.showModal({
    title: isSelf ? '退出账本' : '移除成员',
    content: isSelf
      ? '退出后你将不再参与该账本的新记账，历史记录会保留。'
      : `确定移除「${nameOf(m)}」？其历史流水与分摊记录将保留，仅不再参与新记账。`,
    confirmColor: '#e5544b',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await removeMember(ledgerId.value, m.userId)
        if (isSelf) {
          uni.showToast({ title: '已退出', icon: 'none' })
          if (ledgerId.value === ledgerStore.currentLedgerId) ledgerStore.setCurrent(null)
          try {
            await ledgerStore.load()
          } catch (e) {
            /* ignore */
          }
          setTimeout(() => uni.navigateBack(), 400)
        } else {
          await loadMembers()
        }
      } catch (e) {
        handleKickError(e)
      }
    }
  })
}

/** 未结清拦截 → 明确提示先结清（需求 2.6）；创建者不可移除 → 对应提示（需求 2.8）。 */
function handleKickError(e) {
  const code = e && e.code
  if (code === 'AA_MEMBER_UNSETTLED') {
    uni.showModal({
      title: '还有未结清金额',
      content: '该成员仍有应收或应付未结清。请先在结算页把 TA 的净额结清（净额为 0）后，再退出或移除。',
      showCancel: false,
      confirmText: '知道了'
    })
    return
  }
  if (code === 'MEMBER_OWNER_IMMUTABLE') {
    uni.showToast({ title: '不能移除账本创建者', icon: 'none' })
    return
  }
  uni.showToast({ title: (e && e.message) || '操作失败', icon: 'none' })
}
</script>

<template>
  <view class="page">
    <!-- 归档只读横幅（需求 8.3） -->
    <view v-if="isArchived" class="archived-bar">
      <text class="ab-ic">🔒</text>
      <text class="ab-tx">账本已归档，仅可查看。邀请与退出 / 移除已停用。</text>
    </view>

    <!-- 成员列表 -->
    <view class="card">
      <text class="sub">共 {{ members.length }} 人 · 均为有余注册用户</text>

      <view v-if="listState === 'loading'" class="empty">加载中…</view>
      <view v-else-if="listState === 'error'" class="empty">加载失败，下拉重试</view>

      <view v-for="m in members" :key="m.userId" class="mem">
        <view class="av" :style="{ background: avatarColor(m) }">{{ avatarChar(m) }}</view>
        <view class="mem-main">
          <text class="mem-name">{{ nameOf(m) }}</text>
          <text v-if="m.owner || m.role === 'OWNER'" class="badge">创建者</text>
          <text v-else-if="m.userId === myUserId" class="badge me">我</text>
        </view>
        <text v-if="canKick(m)" class="kick" @click="kick(m)">{{ kickLabel(m) }}</text>
        <text v-else class="mem-hint">{{ (m.owner || m.role === 'OWNER') ? '' : '已加入' }}</text>
      </view>
    </view>

    <!-- 邀请入口（仅创建者、未归档）-->
    <view v-if="isOwner && !isArchived" class="card invite-entry" @click="openInvite">
      <view class="ie-ic">＋</view>
      <view class="ie-main">
        <text class="ie-title">邀请成员</text>
        <text class="ie-desc">生成邀请链接 / 二维码</text>
      </view>
      <text class="ie-go">›</text>
    </view>
    <view v-if="isOwner && !isArchived" class="invite-btn" :class="{ busy: inviteBusy }" @click="openInvite">
      {{ inviteBusy ? '生成中…' : '生成邀请链接' }}
    </view>

    <view class="note">
      受邀人打开链接后需先<text class="b">注册 / 登录有余</text>才能加入；参与人必须是注册用户。
    </view>

    <!-- 邀请弹层：二维码 + 邀请码 + 链接 -->
    <view v-if="inviteSheet" class="mask" @click="closeInvite">
      <view class="sheet" @click.stop>
        <text class="sheet-title">邀请加入「{{ ledgerName }}」</text>

        <view class="qrbox">
          <canvas
            v-show="qrState !== 'failed'"
            :canvas-id="QR_CANVAS_ID"
            class="qr-canvas"
          ></canvas>
          <view v-if="qrState === 'failed'" class="qr-fail">
            <text class="qf-ic">🔗</text>
            <text>二维码不可用，可直接复制邀请码 / 链接</text>
          </view>
          <text v-if="qrState === 'ready'" class="qr-hint">扫码打开有余 · 需先注册 / 登录再加入</text>
        </view>

        <view class="code-row">
          <text class="cr-label">邀请码</text>
          <text class="cr-code">{{ inviteCode || '—' }}</text>
          <text class="cr-copy" @click="copyCode">复制</text>
        </view>
        <view class="code-row link">
          <text class="cr-label">链接</text>
          <text class="cr-link">{{ inviteLink || '—' }}</text>
          <text class="cr-copy" @click="copyLink">复制</text>
        </view>

        <button class="share-btn" open-type="share">分享给好友</button>
        <text class="sheet-tip">邀请码 7 天内有效。对方也可在「账本 → 加入账本」输入邀请码加入。</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  box-sizing: border-box;
  background: #f5f6f8;
}
.archived-bar {
  display: flex;
  align-items: center;
  gap: 12rpx;
  background: #fff8e6;
  border: 1rpx solid #f3e2b3;
  border-radius: 20rpx;
  padding: 20rpx 24rpx;
  margin-bottom: 20rpx;
}
.ab-ic {
  font-size: 30rpx;
}
.ab-tx {
  font-size: 24rpx;
  color: #7a5b16;
  flex: 1;
}
.card {
  background: #fff;
  border-radius: 24rpx;
  padding: 12rpx 32rpx 20rpx;
  margin-bottom: 20rpx;
}
.sub {
  display: block;
  font-size: 25rpx;
  color: #9aa2ad;
  padding: 16rpx 0 8rpx;
}
.empty {
  font-size: 26rpx;
  color: #9aa2ad;
  padding: 24rpx 0;
}
.mem {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 20rpx 0;
  border-top: 1rpx solid #eef0f2;
}
.mem:first-of-type {
  border-top: none;
}
.av {
  width: 76rpx;
  height: 76rpx;
  border-radius: 50%;
  color: #fff;
  font-size: 30rpx;
  font-weight: 700;
  text-align: center;
  line-height: 76rpx;
  flex: 0 0 auto;
}
.mem-main {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.mem-name {
  font-size: 30rpx;
  color: #16181c;
  font-weight: 500;
}
.badge {
  font-size: 20rpx;
  color: #0e8a44;
  background: #e6f6ec;
  border-radius: 999rpx;
  padding: 2rpx 14rpx;
  font-weight: 700;
}
.badge.me {
  color: #3a7bd5;
  background: #e8f0fc;
}
.kick {
  font-size: 26rpx;
  color: #e5544b;
  flex: 0 0 auto;
  padding: 6rpx 8rpx;
}
.mem-hint {
  font-size: 23rpx;
  color: #c4c9d0;
  flex: 0 0 auto;
}
.invite-entry {
  display: flex;
  align-items: center;
  gap: 22rpx;
  padding: 26rpx 32rpx;
}
.ie-ic {
  width: 76rpx;
  height: 76rpx;
  border-radius: 50%;
  background: #eef1f5;
  color: #9aa2ad;
  font-size: 40rpx;
  text-align: center;
  line-height: 76rpx;
  flex: 0 0 auto;
}
.ie-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}
.ie-title {
  font-size: 30rpx;
  font-weight: 700;
  color: #16181c;
}
.ie-desc {
  font-size: 24rpx;
  color: #5b6470;
}
.ie-go {
  font-size: 40rpx;
  color: #c4c9d0;
}
.invite-btn {
  background: #eef6f0;
  color: #12a150;
  font-size: 30rpx;
  font-weight: 700;
  text-align: center;
  border-radius: 16rpx;
  padding: 24rpx 0;
  margin-bottom: 20rpx;
}
.invite-btn.busy {
  opacity: 0.6;
}
.note {
  background: #fff8e6;
  border: 1rpx solid #f3e2b3;
  border-radius: 16rpx;
  padding: 20rpx 24rpx;
  font-size: 24rpx;
  color: #7a5b16;
  line-height: 1.7;
}
.note .b {
  font-weight: 700;
}
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: flex-end;
  z-index: 50;
}
.sheet {
  width: 100%;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 40rpx;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 16rpx;
}
.sheet-title {
  font-size: 32rpx;
  font-weight: 800;
  color: #16181c;
}
.qrbox {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
  padding: 12rpx 0 4rpx;
}
.qr-canvas {
  width: 220px;
  height: 220px;
}
.qr-fail {
  width: 220px;
  height: 160px;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12rpx;
  color: #9aa2ad;
  font-size: 24rpx;
  background: #f7f9fb;
  border-radius: 16rpx;
}
.qf-ic {
  font-size: 44rpx;
}
.qr-hint {
  font-size: 22rpx;
  color: #9aa2ad;
}
.code-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  background: #f7f9fb;
  border-radius: 14rpx;
  padding: 20rpx 22rpx;
}
.cr-label {
  font-size: 24rpx;
  color: #9aa2ad;
  flex: 0 0 auto;
}
.cr-code {
  flex: 1;
  font-size: 32rpx;
  font-weight: 800;
  letter-spacing: 2rpx;
  color: #16181c;
  font-variant-numeric: tabular-nums;
}
.cr-link {
  flex: 1;
  font-size: 22rpx;
  color: #5b6470;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.cr-copy {
  font-size: 26rpx;
  color: #12a150;
  font-weight: 700;
  flex: 0 0 auto;
}
.share-btn {
  margin-top: 8rpx;
  background: #12a150;
  color: #fff;
  font-size: 30rpx;
  font-weight: 700;
  border-radius: 16rpx;
  padding: 22rpx 0;
}
.sheet-tip {
  font-size: 22rpx;
  color: #9aa2ad;
  line-height: 1.7;
  text-align: center;
}
</style>
