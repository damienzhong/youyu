<script setup>
import { ref, computed } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { useLedgerStore } from '../../stores/ledger'
import { useAuthStore } from '../../stores/auth'
import { listMembers } from '../../api/ledger'
import { listSelectableAccounts, accountDisplayName, accountTypeLabel } from '../../api/account'
import {
  getAaSettlement,
  getAaOverview,
  settleAa,
  revertAaSettlement
} from '../../api/aa'
import { formatAmount } from '../../utils/format'
import { safeBack } from '../../utils/nav'

/**
 * AA 账本 · 结算页（任务 7.5；需求 1.2、2.1、3.1、3.6、5.2、6.1、8.3、8.5）。
 *
 * 三块内容，视觉对齐 design/aa-ledger-prototype.html「⑤ 结算」步骤：
 * 1) 每人净额：应收（正）/ 应付（负），金额格式化展示（需求 5.2）。
 * 2) 建议转账（最少笔数）：GET /api/aa/{ledgerId}/settlement 派生（需求 5.3、5.4）。
 *    - 涉及本人（from 或 to 为我）：可「结清」，弹出本人账户选择（复用 AccountBadge），
 *      按角色带正确字段调 settleAa：
 *        · 我是收款方（to===我）→ fromUserId = 该建议 fromUserId；
 *        · 我是付款方（from===我）→ toUserId   = 该建议 toUserId；
 *      amount = 建议金额，myAccountId = 所选账户（需求 6.1、6.2、6.3）。
 *    - 双方均非本人：展示但不可操作，提示「由双方各自结清」（需求 6.5）。
 * 3) 已结清记录（来自概览流水中的 aa_settlement 条目）：可「撤销」本人结清的记录，
 *    调 revertAaSettlement(id)（需求 6.5 / 9.2b 的撤销能力）。
 *
 * 撤销 UX 选择：把「撤销」入口就近放在结算页的「已结清记录」列表，与建议转账同屏，
 * 让用户在同一处完成结清与回退，闭环清晰；概览流水页仅作展示。后端仅允许结清人本人撤销
 * （否则 LEDGER_FORBIDDEN），本页对该错误给出明确提示。
 *
 * 归档（只读）账本：结清 / 撤销入口禁用，仅可查看（需求 8.3 / 9.5，错误码 AA_LEDGER_ARCHIVED）。
 * 账本按请求头 X-Ledger-Id 隔离：进入本页时把当前账本切到 ?id，使 settle/revert 落到正确账本
 * （与 createAaExpense 同口径，见 utils/request）。
 */

const ledgerStore = useLedgerStore()
const authStore = useAuthStore()
const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'

const ledgerId = ref(null)
const selfId = computed(() => authStore.user?.id ?? authStore.user?.userId ?? null)

const loadState = ref('loading') // loading | loaded | error
const nets = ref([]) // [{ userId, net }]
const transfers = ref([]) // [{ fromUserId, toUserId, amount }]
const allSettled = ref(false)
const members = ref([])
const settledRecords = ref([]) // 概览流水中的 aa_settlement 条目
const accounts = ref([]) // 本人账户（结清可选）

// 账本元信息：归档态 / 名称（取自账本 store，直达本页兜底拉取）。
const ledger = computed(() => ledgerStore.ledgers.find((l) => l.id === ledgerId.value) || null)
const isArchived = computed(() => ledger.value?.archived === true)
const ledgerName = computed(() => ledger.value?.name || 'AA 账本')

// ---- 成员解析：userId → 昵称 / 头像字 / 颜色 ----
const AVATAR_COLORS = ['#12a150', '#3a7bd5', '#e0609a', '#f0a13b', '#7c5cff', '#e5544b', '#0ea5a5', '#d97706']
function memberOf(uid) {
  return members.value.find((m) => m.userId === uid) || null
}
function nameOf(uid) {
  if (uid === selfId.value) return '我'
  const m = memberOf(uid)
  return m ? (m.displayName || '用户' + uid) : '用户' + uid
}
function avatarChar(uid) {
  const m = memberOf(uid)
  const s = String((m && (m.avatarSeed || m.displayName)) || (uid === selfId.value ? '我' : '')).trim()
  return s ? Array.from(s)[0] : '友'
}
function avatarColor(uid) {
  return AVATAR_COLORS[(Number(uid) || 0) % AVATAR_COLORS.length]
}

// ---- 净额展示 ----
function netNum(v) {
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
}

// ---- 建议转账：是否涉及本人、我的角色 ----
function meInvolved(t) {
  return t.fromUserId === selfId.value || t.toUserId === selfId.value
}
function iAmReceiver(t) {
  return t.toUserId === selfId.value
}

onLoad((options) => {
  const raw = options && options.id != null ? Number(options.id) : null
  ledgerId.value = Number.isFinite(raw) ? raw : ledgerStore.currentLedgerId
  // 切到目标账本，使 settle/revert 的 X-Ledger-Id 头指向本账本（与记账同口径）。
  if (ledgerId.value != null && ledgerId.value !== ledgerStore.currentLedgerId) {
    ledgerStore.setCurrent(ledgerId.value)
  }
})

onShow(async () => {
  if (!ledgerStore.ledgers.length) {
    try {
      await ledgerStore.load()
    } catch (e) {
      /* 账本列表拉取失败不阻断结算展示 */
    }
  }
  await load()
})

async function load() {
  if (ledgerId.value == null) {
    loadState.value = 'error'
    return
  }
  loadState.value = 'loading'
  try {
    const [settle, overview, mem, accs] = await Promise.all([
      getAaSettlement(ledgerId.value),
      getAaOverview(ledgerId.value),
      listMembers(ledgerId.value),
      listSelectableAccounts(ledgerId.value)
    ])
    nets.value = settle?.nets || []
    transfers.value = settle?.suggestedTransfers || []
    allSettled.value = !!settle?.allSettled
    members.value = mem || []
    // 已结清记录：概览流水中 type=aa_settlement 的条目（未撤销的才会出现在流水里）。
    settledRecords.value = (overview?.transactions || []).filter((t) => t.type === 'aa_settlement')
    // 结清可选账户只能是本人账户（后端按结清人校验归属）。
    accounts.value = (accs || []).filter((a) => a.ownerId == null || a.ownerId === selfId.value)
    loadState.value = 'loaded'
  } catch (e) {
    loadState.value = 'error'
    uni.showToast({ title: (e && e.message) || '加载失败', icon: 'none' })
  }
}

// ---- 结清：选择本人账户 ----
const accountSheet = ref(false)
const pendingTransfer = ref(null)
const submitting = ref(false)

function openSettle(t) {
  if (isArchived.value || !meInvolved(t) || submitting.value) return
  pendingTransfer.value = t
  if (!accounts.value.length) {
    uni.showModal({
      title: '暂无可用账户',
      content: '结清需要选择一个你本人的账户用于收 / 付。请先到「资产」创建账户。',
      showCancel: false,
      confirmText: '知道了'
    })
    return
  }
  accountSheet.value = true
}

async function confirmSettle(account) {
  const t = pendingTransfer.value
  if (!t || submitting.value) return
  accountSheet.value = false
  const payload = { amount: String(t.amount), myAccountId: account.id }
  if (iAmReceiver(t)) {
    // 我是收款方：带 fromUserId，本人账户 +金额、应收 −金额。
    payload.fromUserId = t.fromUserId
  } else {
    // 我是付款方：带 toUserId，本人账户 −金额、应付 −金额。
    payload.toUserId = t.toUserId
  }
  submitting.value = true
  try {
    await settleAa(payload)
    uni.showToast({ title: '已结清', icon: 'success' })
    pendingTransfer.value = null
    await load()
  } catch (e) {
    handleWriteError(e, '结清失败')
  } finally {
    submitting.value = false
  }
}

// ---- 撤销结算 ----
async function revert(rec) {
  if (isArchived.value || submitting.value) return
  uni.showModal({
    title: '撤销结清',
    content: `撤销「${nameOf(rec.fromUserId)} → ${nameOf(rec.toUserId)}」的 ¥${formatAmount(rec.amount)} 结清？将回滚你的账户增减并恢复相应债务。`,
    confirmColor: '#e5544b',
    success: async (r) => {
      if (!r.confirm) return
      submitting.value = true
      try {
        await revertAaSettlement(rec.id)
        uni.showToast({ title: '已撤销', icon: 'none' })
        await load()
      } catch (e) {
        handleWriteError(e, '撤销失败')
      } finally {
        submitting.value = false
      }
    }
  })
}

/** 结清 / 撤销错误码 → 明确提示。 */
function handleWriteError(e, fallback) {
  const code = e && e.code
  if (code === 'AA_LEDGER_ARCHIVED') {
    uni.showModal({
      title: '账本已归档',
      content: '账本处于只读状态，无法结清或撤销。可先解档后再操作。',
      showCancel: false,
      confirmText: '知道了'
    })
    return
  }
  if (code === 'AA_SETTLEMENT_INVALID') {
    uni.showModal({
      title: '结算信息已变更',
      content: '这条结算的金额 / 对象已不再有效（可能已被撤销或债务已变化）。请刷新后重试。',
      showCancel: false,
      confirmText: '刷新',
      success: () => load()
    })
    return
  }
  if (code === 'LEDGER_FORBIDDEN') {
    uni.showToast({ title: '只能撤销你本人结清的记录', icon: 'none' })
    return
  }
  uni.showToast({ title: (e && e.message) || fallback, icon: 'none' })
}

function goBack() {
  safeBack('/pages/index/index')
}
</script>

<template>
  <view class="page">
    <view class="statusbar" :style="{ height: statusBarHeight }"></view>
    <view class="rnav">
      <text class="nb back" @click="goBack">‹</text>
      <text class="title">结算 · {{ ledgerName }}</text>
      <view class="nb spacer"></view>
    </view>

    <scroll-view scroll-y class="main">
      <!-- 归档只读横幅 -->
      <view v-if="isArchived" class="archived-bar">
        <text class="ab-ic">🔒</text>
        <text class="ab-tx">账本已归档，仅可查看。结清与撤销已停用。</text>
      </view>

      <view v-if="loadState === 'loading'" class="empty">加载中…</view>
      <view v-else-if="loadState === 'error'" class="empty">加载失败，请返回重试</view>

      <template v-else>
        <!-- 已全部结清 -->
        <view v-if="allSettled" class="settled-hero">
          <view class="sh-check">✓</view>
          <text class="sh-title">已全部结清</text>
          <text class="sh-sub">每人净额均为 0，无待结算转账</text>
        </view>

        <!-- 每人净额 -->
        <view class="card">
          <text class="sec">每人净额</text>
          <view v-for="n in nets" :key="n.userId" class="mem">
            <view class="av" :style="{ background: avatarColor(n.userId) }">{{ avatarChar(n.userId) }}</view>
            <text class="mem-name">{{ nameOf(n.userId) }}</text>
            <text class="net" :class="netNum(n.net) >= 0 ? 'pos' : 'neg'">
              {{ netNum(n.net) >= 0 ? '应收 ' : '应付 ' }}¥{{ formatAmount(Math.abs(netNum(n.net))) }}
            </text>
          </view>
        </view>

        <!-- 建议转账 -->
        <view v-if="transfers.length" class="card">
          <text class="sec">建议转账（最少 {{ transfers.length }} 笔结清）</text>
          <view v-for="(t, i) in transfers" :key="i" class="tr">
            <view class="tr-row">
              <view class="av sm" :style="{ background: avatarColor(t.fromUserId) }">{{ avatarChar(t.fromUserId) }}</view>
              <text class="tr-arrow">→</text>
              <view class="av sm" :style="{ background: avatarColor(t.toUserId) }">{{ avatarChar(t.toUserId) }}</view>
              <text class="tr-desc">{{ nameOf(t.fromUserId) }} 转给 {{ nameOf(t.toUserId) }}</text>
              <text class="tr-amt">¥{{ formatAmount(t.amount) }}</text>
            </view>
            <view class="tr-act">
              <text
                v-if="meInvolved(t)"
                class="btn-settle"
                :class="{ disabled: isArchived || submitting }"
                @click="openSettle(t)"
              >结清</text>
              <text v-else class="tr-hint">由双方各自结清</text>
            </view>
            <view v-if="meInvolved(t)" class="tr-note">
              {{ iAmReceiver(t)
                ? '结清后：所选账户 +' + '¥' + formatAmount(t.amount) + '，你的应收 −¥' + formatAmount(t.amount)
                : '结清后：所选账户 −' + '¥' + formatAmount(t.amount) + '，你的应付 −¥' + formatAmount(t.amount) }}
            </view>
          </view>
        </view>

        <!-- 已结清记录（可撤销本人结清的） -->
        <view v-if="settledRecords.length" class="card">
          <text class="sec">已结清记录</text>
          <view v-for="rec in settledRecords" :key="rec.id" class="tr">
            <view class="tr-row">
              <view class="av sm" :style="{ background: avatarColor(rec.fromUserId) }">{{ avatarChar(rec.fromUserId) }}</view>
              <text class="tr-arrow">→</text>
              <view class="av sm" :style="{ background: avatarColor(rec.toUserId) }">{{ avatarChar(rec.toUserId) }}</view>
              <text class="tr-desc">{{ nameOf(rec.fromUserId) }} 转给 {{ nameOf(rec.toUserId) }}</text>
              <text class="tr-amt done">¥{{ formatAmount(rec.amount) }}</text>
            </view>
            <view class="tr-act">
              <text
                class="btn-revert"
                :class="{ disabled: isArchived || submitting }"
                @click="revert(rec)"
              >撤销</text>
            </view>
          </view>
          <text class="rec-tip">仅可撤销你本人结清的记录；撤销后相应债务会恢复。</text>
        </view>
      </template>

      <view class="pad"></view>
    </scroll-view>

    <!-- 结清账户选择 -->
    <view v-if="accountSheet" class="mask" @click="accountSheet = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">{{ pendingTransfer && iAmReceiver(pendingTransfer) ? '选择收款账户' : '选择付款账户' }}</text>
        <scroll-view scroll-y class="slist" :show-scrollbar="false">
          <view v-for="a in accounts" :key="a.id" class="sitem" @click="confirmSettle(a)">
            <AccountBadge :account="a" :size="60" />
            <view class="si-name">
              <text class="si-nm">{{ accountDisplayName(a) }}</text>
              <text class="si-type">{{ accountTypeLabel(a.type) }}</text>
            </view>
            <text v-if="a.canSeeBalance !== false" class="si-bal" :class="{ neg: Number(a.currentBalance) < 0 }">¥{{ formatAmount(a.currentBalance) }}</text>
          </view>
        </scroll-view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; background: #f5f6f8; display: flex; flex-direction: column; }
.statusbar { width: 100%; }
.rnav { display: flex; align-items: center; height: 88rpx; padding: 0 12rpx; }
.nb { width: 88rpx; text-align: center; }
.back { font-size: 52rpx; color: #1f2329; }
.title { flex: 1; text-align: center; font-size: 32rpx; font-weight: 600; color: #1f2329; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.spacer { width: 88rpx; }

.main { flex: 1; padding: 0 24rpx; }

.archived-bar { display: flex; align-items: center; gap: 12rpx; background: #fff8e6; border: 1rpx solid #f3e2b3; border-radius: 20rpx; padding: 20rpx 24rpx; margin: 8rpx 0 20rpx; }
.ab-ic { font-size: 30rpx; }
.ab-tx { font-size: 24rpx; color: #7a5b16; flex: 1; }

.empty { background: #fff; border-radius: 24rpx; padding: 60rpx 0; text-align: center; color: #9aa2ad; font-size: 26rpx; margin-top: 16rpx; }

.settled-hero { background: linear-gradient(150deg, #12a150, #0b7d3c); color: #fff; border-radius: 24rpx; padding: 36rpx 24rpx; display: flex; flex-direction: column; align-items: center; gap: 8rpx; margin: 8rpx 0 20rpx; }
.sh-check { width: 92rpx; height: 92rpx; border-radius: 50%; background: rgba(255,255,255,0.2); font-size: 48rpx; text-align: center; line-height: 92rpx; margin-bottom: 6rpx; }
.sh-title { font-size: 36rpx; font-weight: 800; }
.sh-sub { font-size: 24rpx; opacity: 0.9; }

.card { background: #fff; border-radius: 24rpx; padding: 12rpx 32rpx 24rpx; margin-bottom: 20rpx; }
.sec { display: block; font-size: 25rpx; color: #9aa2ad; padding: 20rpx 0 8rpx; }

.mem { display: flex; align-items: center; gap: 20rpx; padding: 20rpx 0; border-top: 1rpx solid #eef0f2; }
.mem:first-of-type { border-top: none; }
.mem-name { flex: 1; font-size: 30rpx; color: #16181c; }
.net { font-size: 28rpx; font-weight: 700; font-variant-numeric: tabular-nums; }
.net.pos { color: #12a150; }
.net.neg { color: #e5544b; }

.av { width: 72rpx; height: 72rpx; border-radius: 50%; color: #fff; font-size: 28rpx; font-weight: 700; text-align: center; line-height: 72rpx; flex: 0 0 auto; }
.av.sm { width: 52rpx; height: 52rpx; font-size: 24rpx; line-height: 52rpx; }

.tr { padding: 20rpx 0; border-top: 1rpx solid #eef0f2; }
.tr:first-of-type { border-top: none; }
.tr-row { display: flex; align-items: center; gap: 12rpx; }
.tr-arrow { color: #c4c9d0; font-size: 28rpx; }
.tr-desc { flex: 1; font-size: 27rpx; color: #5b6470; min-width: 0; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.tr-amt { font-size: 30rpx; font-weight: 800; color: #16181c; font-variant-numeric: tabular-nums; }
.tr-amt.done { color: #9aa2ad; text-decoration: line-through; }
.tr-act { display: flex; justify-content: flex-end; margin-top: 12rpx; }
.tr-hint { font-size: 23rpx; color: #9aa2ad; }
.btn-settle { font-size: 26rpx; color: #12a150; font-weight: 700; border: 1rpx solid #bfe6cd; border-radius: 999rpx; padding: 8rpx 28rpx; }
.btn-revert { font-size: 26rpx; color: #e5544b; font-weight: 700; border: 1rpx solid #f3c7c3; border-radius: 999rpx; padding: 8rpx 28rpx; }
.btn-settle.disabled, .btn-revert.disabled { opacity: 0.4; }
.tr-note { margin-top: 10rpx; background: #f7f9fb; border: 1rpx solid #e9edf2; border-radius: 12rpx; padding: 12rpx 16rpx; font-size: 23rpx; color: #5b6470; }
.rec-tip { display: block; font-size: 22rpx; color: #9aa2ad; padding-top: 16rpx; line-height: 1.6; }

.pad { height: 40rpx; }

.mask { position: fixed; inset: 0; background: rgba(15,23,42,0.45); z-index: 50; display: flex; align-items: flex-end; }
.sheet { width: 100%; background: #fff; border-radius: 28rpx 28rpx 0 0; padding: 24rpx 24rpx calc(24rpx + env(safe-area-inset-bottom)); max-height: 76vh; display: flex; flex-direction: column; }
.sheet-title { font-size: 30rpx; font-weight: 700; color: #1f2329; padding: 8rpx 0 16rpx; }
.slist { max-height: 60vh; }
.sitem { display: flex; align-items: center; gap: 16rpx; padding: 20rpx 8rpx; border-bottom: 1rpx solid #f0f2f5; }
.si-name { flex: 1; display: flex; flex-direction: column; }
.si-nm { font-size: 28rpx; color: #1f2329; }
.si-type { font-size: 22rpx; color: #8a94a6; }
.si-bal { font-size: 26rpx; color: #1f2329; font-variant-numeric: tabular-nums; }
.si-bal.neg { color: #e5533d; }
</style>
