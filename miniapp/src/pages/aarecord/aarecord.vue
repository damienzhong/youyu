<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { listSelectableAccounts, accountDisplayName, accountTypeLabel } from '../../api/account'
import { listCategories } from '../../api/category'
import { listMembers } from '../../api/ledger'
import { createAaExpense } from '../../api/aa'
import { useLedgerStore } from '../../stores/ledger'
import { useAuthStore } from '../../stores/auth'
import { categoryEmoji, formatAmount } from '../../utils/format'
import { safeBack } from '../../utils/nav'
import {
  toCents,
  centsToYuan,
  evenSharesByUser,
  isValidCustomSplit,
  payerImpact
} from '../../utils/aa'

const ledgerStore = useLedgerStore()
const authStore = useAuthStore()
const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'
const selfId = computed(() => authStore.user?.id ?? null)

// ---------- 金额 ----------
const amountText = ref('')
const amountValue = computed(() => {
  const n = Number(amountText.value)
  return Number.isFinite(n) && n > 0 ? Math.round(n * 100) / 100 : 0
})
const totalCents = computed(() => toCents(amountValue.value))

// ---------- 分类 ----------
const tree = ref({ expense: [] })
const categoryId = ref(null)
const expandedId = ref(null)
const parents = computed(() => tree.value.expense || [])
const expandedChildren = computed(() => {
  const p = parents.value.find((x) => x.id === expandedId.value)
  return p ? p.children || [] : []
})
function pickParent(p) {
  categoryId.value = p.id
  expandedId.value = p.children && p.children.length ? (expandedId.value === p.id ? null : p.id) : null
}
function pickChild(c) {
  categoryId.value = c.id
}

// ---------- 成员 ----------
const members = ref([])
function memberName(uid) {
  if (uid === selfId.value) return '我'
  const m = members.value.find((x) => x.userId === uid)
  return m ? m.displayName || '成员' : '成员'
}
function memberSeed(uid) {
  const m = members.value.find((x) => x.userId === uid)
  return (m && m.avatarSeed) || memberName(uid).slice(0, 1)
}

// ---------- 付款人 ----------
const payerUserId = ref(null) // 默认当前用户，load 后设置
const payerIsSelf = computed(() => payerUserId.value != null && payerUserId.value === selfId.value)
const payerSheet = ref(false)
function pickPayer(uid) {
  payerUserId.value = uid
  payerSheet.value = false
}

// ---------- 付款账户（付款人为本人时必选，复用 AccountBadge）----------
const accounts = ref([])
const payerAccountId = ref(null)
const accountSheet = ref(false)
const payerAccount = computed(() => accounts.value.find((a) => a.id === payerAccountId.value) || null)
function pickAccount(a) {
  payerAccountId.value = a.id
  accountSheet.value = false
}

// ---------- 参与分摊（多选，默认全体）----------
const participantIds = ref([]) // 保序：按 members 顺序
const partSheet = ref(false)
const participantCount = computed(() => participantIds.value.length)
function isParticipant(uid) {
  return participantIds.value.includes(uid)
}
function toggleParticipant(uid) {
  const i = participantIds.value.indexOf(uid)
  if (i >= 0) {
    participantIds.value.splice(i, 1)
  } else {
    // 保持与成员列表相同的顺序，便于均分余数校正稳定
    const ordered = members.value.map((m) => m.userId).filter((id) => id === uid || participantIds.value.includes(id))
    participantIds.value = ordered
  }
}
function selectAllParticipants() {
  participantIds.value = members.value.map((m) => m.userId)
}

// ---------- 分摊方式 ----------
const splitMode = ref('even') // even | custom
// 自定义分摊输入：{ [userId]: 元字符串 }
const customInput = ref({})
function customCentsOf(uid) {
  return toCents(customInput.value[uid])
}
// 各参与人份额（分）：均分实时算，自定义取输入。
const shareCentsByUser = computed(() => {
  if (splitMode.value === 'even') {
    return evenSharesByUser(totalCents.value, participantIds.value)
  }
  const map = {}
  for (const uid of participantIds.value) map[uid] = customCentsOf(uid)
  return map
})
function shareYuanOf(uid) {
  return centsToYuan(shareCentsByUser.value[uid] || 0)
}
// 自定义差额（分）：正=还差，负=超出。
const customSumCents = computed(() =>
  participantIds.value.reduce((a, uid) => a + customCentsOf(uid), 0)
)
const customDiffCents = computed(() => totalCents.value - customSumCents.value)
const customValid = computed(() =>
  splitMode.value !== 'custom' ||
  (totalCents.value > 0 && isValidCustomSplit(totalCents.value, participantIds.value.map(customCentsOf)))
)

// ---------- 本笔影响（付款人为本人）----------
const impact = computed(() => {
  const myShare = shareCentsByUser.value[payerUserId.value] || 0
  return payerImpact(totalCents.value, myShare)
})

// ---------- 备注 / 日期 ----------
const note = ref('')
const occurredDate = ref(todayStr())
function todayStr() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}
const dateLabel = computed(() => (occurredDate.value === todayStr() ? '今天' : occurredDate.value.slice(5)))
function onDateChange(e) {
  occurredDate.value = e.detail.value
}
function occurredAtIso() {
  if (occurredDate.value === todayStr()) {
    const d = new Date()
    const p = (n) => String(n).padStart(2, '0')
    return `${occurredDate.value}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  }
  return `${occurredDate.value}T12:00:00`
}

onLoad(async () => {
  try {
    if (!ledgerStore.ledgers.length) await ledgerStore.load()
  } catch (e) {
    /* ignore */
  }
  await load()
})

async function load() {
  const lid = ledgerStore.currentLedgerId
  try {
    const [accs, cats, mem] = await Promise.all([
      listSelectableAccounts(),
      listCategories(),
      listMembers(lid)
    ])
    // 付款账户只能是本人账户（后端按 payer 校验归属）；过滤出本人账户。
    accounts.value = (accs || []).filter((a) => a.ownerId == null || a.ownerId === selfId.value)
    tree.value = cats || { expense: [] }
    members.value = mem || []
    // 默认付款人=当前用户（若不是成员则回退第一位成员）。
    const selfIsMember = members.value.some((m) => m.userId === selfId.value)
    payerUserId.value = selfIsMember ? selfId.value : (members.value[0]?.userId ?? null)
    // 默认参与人=全体成员。
    selectAllParticipants()
    // 默认付款账户=可选集第一个。
    payerAccountId.value = accounts.value[0]?.id ?? null
  } catch (e) {
    uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  }
}

// 切到自定义时，用当前均分结果预填输入，便于微调。
function setSplitMode(mode) {
  if (splitMode.value === mode) return
  if (mode === 'custom') {
    const even = evenSharesByUser(totalCents.value, participantIds.value)
    const map = {}
    for (const uid of participantIds.value) map[uid] = centsToYuan(even[uid] || 0)
    customInput.value = map
  }
  splitMode.value = mode
}

const submitting = ref(false)
async function submit() {
  if (submitting.value) return
  if (!(amountValue.value > 0)) {
    uni.showToast({ title: '请输入正确金额', icon: 'none' })
    return
  }
  if (!categoryId.value) {
    uni.showToast({ title: '请选择分类', icon: 'none' })
    return
  }
  if (!participantIds.value.length) {
    uni.showToast({ title: '请选择参与分摊成员', icon: 'none' })
    return
  }
  if (payerIsSelf.value && !payerAccountId.value) {
    uni.showToast({ title: '请选择付款账户', icon: 'none' })
    return
  }
  if (splitMode.value === 'custom' && !customValid.value) {
    const diff = customDiffCents.value
    const tip = diff > 0 ? `还差 ¥${centsToYuan(diff)}` : `超出 ¥${centsToYuan(-diff)}`
    uni.showToast({ title: `自定义分摊需等于总额（${tip}）`, icon: 'none' })
    return
  }

  const payload = {
    amount: String(amountValue.value),
    categoryId: categoryId.value,
    payerUserId: payerUserId.value,
    occurredAt: occurredAtIso(),
    note: note.value.trim() || undefined,
    splitMode: splitMode.value,
    participants: participantIds.value.slice()
  }
  if (payerIsSelf.value) payload.payerAccountId = payerAccountId.value
  if (splitMode.value === 'custom') {
    payload.customShares = participantIds.value.map((uid) => ({
      userId: uid,
      amount: (Number(customInput.value[uid]) || 0).toFixed(2)
    }))
  }

  submitting.value = true
  try {
    await createAaExpense(payload)
    uni.showToast({ title: '已记录', icon: 'success' })
    setTimeout(() => safeBack('/pages/index/index'), 500)
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

function goBack() {
  safeBack('/pages/index/index')
}
</script>

<template>
  <view class="aar">
    <view class="statusbar" :style="{ height: statusBarHeight }"></view>
    <view class="rnav">
      <text class="nb back" @click="goBack">‹</text>
      <text class="title">记一笔（AA）</text>
      <view class="nb spacer"></view>
    </view>

    <scroll-view scroll-y class="main">
      <!-- 金额 -->
      <view class="amtbox">
        <text class="cny">¥</text>
        <input
          class="amt"
          v-model="amountText"
          type="digit"
          placeholder="0.00"
          placeholder-class="amt-ph"
        />
      </view>

      <!-- 分类九宫格 -->
      <view class="sec-hd">分类</view>
      <view v-if="!parents.length" class="empty">
        还没有支出分类，
        <text class="link" @click="uni.navigateTo({ url: '/pages/categories/categories' })">去添加</text>
      </view>
      <view v-else class="cgrid">
        <template v-for="p in parents" :key="p.id">
          <view class="cat" :class="{ on: categoryId === p.id }" @click="pickParent(p)">
            <view class="cic">
              <CategoryIcon :icon="p.icon" :name="p.name" kind="expense" :color="p.iconColor" :size="46" :round="true" />
              <text v-if="p.children && p.children.length" class="subdot">{{ expandedId === p.id ? '▴' : '▾' }}</text>
            </view>
            <text class="cl" :class="{ on: categoryId === p.id }">{{ p.name }}</text>
          </view>
        </template>
      </view>
      <view v-if="expandedChildren.length" class="subwrap">
        <view v-for="c in expandedChildren" :key="c.id" class="cat" :class="{ on: categoryId === c.id }" @click="pickChild(c)">
          <view class="cic sub"><CategoryIcon :icon="c.icon" :name="c.name" kind="expense" :color="c.iconColor" :size="40" :round="true" /></view>
          <text class="cl" :class="{ on: categoryId === c.id }">{{ c.name }}</text>
        </view>
      </view>

      <!-- 备注 -->
      <view class="frow">
        <text class="fk">备注</text>
        <input class="fv-input" v-model="note" placeholder="添加备注" placeholder-class="ph" :maxlength="50" />
      </view>
      <!-- 日期 -->
      <picker class="frow picker" mode="date" :value="occurredDate" @change="onDateChange">
        <text class="fk">日期</text>
        <text class="fv">{{ dateLabel }} ›</text>
      </picker>

      <!-- 付款人 -->
      <view class="frow" @click="payerSheet = true">
        <text class="fk">付款人</text>
        <view class="fv row">
          <view class="avatar sm">{{ memberSeed(payerUserId) }}</view>
          <text>{{ memberName(payerUserId) }} ▾</text>
        </view>
      </view>

      <!-- 付款账户（仅付款人为本人时要求）-->
      <view v-if="payerIsSelf" class="frow" @click="accountSheet = true">
        <text class="fk">付款账户</text>
        <view class="fv row">
          <template v-if="payerAccount">
            <AccountBadge :account="payerAccount" :size="44" />
            <text>{{ accountDisplayName(payerAccount) }} ▾</text>
          </template>
          <text v-else class="muted">选择账户 ▾</text>
        </view>
      </view>

      <!-- 参与分摊 -->
      <view class="frow" @click="partSheet = true">
        <text class="fk">参与分摊</text>
        <text class="fv">{{ participantCount }} 人 ›</text>
      </view>

      <!-- 分摊方式 -->
      <view class="seg">
        <text class="s" :class="{ on: splitMode === 'even' }" @click="setSplitMode('even')">均分</text>
        <text class="s" :class="{ on: splitMode === 'custom' }" @click="setSplitMode('custom')">自定义</text>
      </view>

      <!-- 分摊明细 -->
      <view class="splits">
        <view v-for="uid in participantIds" :key="uid" class="mem">
          <view class="avatar sm">{{ memberSeed(uid) }}</view>
          <text class="nm">{{ memberName(uid) }}</text>
          <template v-if="splitMode === 'custom'">
            <view class="amt-in">
              <text class="cny sm">¥</text>
              <input class="cshare" v-model="customInput[uid]" type="digit" placeholder="0.00" placeholder-class="ph" />
            </view>
          </template>
          <text v-else class="val tabnum">¥{{ shareYuanOf(uid) }}</text>
        </view>
        <view v-if="splitMode === 'custom'" class="diff" :class="{ ok: customDiffCents === 0, bad: customDiffCents !== 0 }">
          <text v-if="customDiffCents === 0">✓ 合计等于总额</text>
          <text v-else-if="customDiffCents > 0">还差 ¥{{ centsToYuan(customDiffCents) }}</text>
          <text v-else>超出 ¥{{ centsToYuan(-customDiffCents) }}</text>
        </view>
      </view>

      <!-- 本笔对你的影响（付款人为本人）-->
      <view v-if="payerIsSelf" class="impact">
        <text class="hd">本笔对你的影响</text>
        <view class="ln">
          <text class="t">{{ payerAccount ? accountDisplayName(payerAccount) : '付款账户' }}</text>
          <text class="neg">−¥{{ centsToYuan(impact.accountDeductCents) }}</text>
        </view>
        <view class="sub">
          <view class="ln">
            <text class="t">· 我的消费（计入支出）</text>
            <text class="neg">¥{{ centsToYuan(impact.myConsumptionCents) }}</text>
          </view>
          <view class="ln">
            <text class="t">· 借出给他人（应收）</text>
            <text class="lend">¥{{ centsToYuan(impact.lentCents) }}</text>
          </view>
        </view>
      </view>
      <view v-else class="impact muted-box">
        <text class="hd">本笔说明</text>
        <view class="ln"><text class="t">付款人非本人，仅形成你对 TA 的应付，不动你的账户。</text></view>
      </view>

      <view class="pad"></view>
    </scroll-view>

    <!-- 保存 -->
    <view class="footer">
      <text class="save" :class="{ busy: submitting }" @click="submit">{{ submitting ? '保存中…' : '保存' }}</text>
    </view>

    <!-- 付款人选择 -->
    <view v-if="payerSheet" class="mask" @click="payerSheet = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">选择付款人</text>
        <scroll-view scroll-y class="slist" :show-scrollbar="false">
          <view v-for="m in members" :key="m.userId" class="sitem" @click="pickPayer(m.userId)">
            <view class="avatar">{{ m.avatarSeed || (m.displayName || '成').slice(0, 1) }}</view>
            <text class="si-nm">{{ m.userId === selfId ? (m.displayName ? m.displayName + '（我）' : '我') : (m.displayName || '成员') }}</text>
            <text v-if="payerUserId === m.userId" class="tick">✓</text>
          </view>
        </scroll-view>
      </view>
    </view>

    <!-- 付款账户选择 -->
    <view v-if="accountSheet" class="mask" @click="accountSheet = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">选择付款账户</text>
        <view v-if="!accounts.length" class="empty">
          还没有可用账户，
          <text class="link" @click="uni.switchTab({ url: '/pages/accounts/accounts' })">去创建</text>
        </view>
        <scroll-view v-else scroll-y class="slist" :show-scrollbar="false">
          <view v-for="a in accounts" :key="a.id" class="sitem" @click="pickAccount(a)">
            <AccountBadge :account="a" :size="60" />
            <view class="si-name">
              <text class="si-nm">{{ accountDisplayName(a) }}</text>
              <text class="si-type">{{ accountTypeLabel(a.type) }}</text>
            </view>
            <text v-if="a.canSeeBalance !== false" class="si-bal" :class="{ neg: Number(a.currentBalance) < 0 }">¥{{ formatAmount(a.currentBalance) }}</text>
            <text v-if="payerAccountId === a.id" class="tick">✓</text>
          </view>
        </scroll-view>
      </view>
    </view>

    <!-- 参与分摊多选 -->
    <view v-if="partSheet" class="mask" @click="partSheet = false">
      <view class="sheet" @click.stop>
        <view class="sheet-hd">
          <text class="sheet-title">参与分摊成员</text>
          <text class="all" @click="selectAllParticipants">全选</text>
        </view>
        <scroll-view scroll-y class="slist" :show-scrollbar="false">
          <view v-for="m in members" :key="m.userId" class="sitem" @click="toggleParticipant(m.userId)">
            <view class="avatar">{{ m.avatarSeed || (m.displayName || '成').slice(0, 1) }}</view>
            <text class="si-nm">{{ m.userId === selfId ? (m.displayName ? m.displayName + '（我）' : '我') : (m.displayName || '成员') }}</text>
            <text class="check" :class="{ on: isParticipant(m.userId) }">{{ isParticipant(m.userId) ? '☑' : '☐' }}</text>
          </view>
        </scroll-view>
        <view class="sheet-done" @click="partSheet = false">完成（{{ participantCount }} 人）</view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.aar { min-height: 100vh; background: #f5f6f8; display: flex; flex-direction: column; }
.statusbar { width: 100%; }
.rnav { display: flex; align-items: center; height: 88rpx; padding: 0 12rpx; }
.nb { width: 88rpx; text-align: center; }
.back { font-size: 52rpx; color: #1f2329; }
.title { flex: 1; text-align: center; font-size: 32rpx; font-weight: 600; color: #1f2329; }
.spacer { width: 88rpx; }

.main { flex: 1; padding: 0 24rpx; }

.amtbox { display: flex; align-items: baseline; justify-content: center; gap: 6rpx; padding: 24rpx 0 12rpx; }
.cny { font-size: 40rpx; color: #1f2329; font-weight: 600; }
.cny.sm { font-size: 26rpx; }
.amt { font-size: 72rpx; font-weight: 700; color: #1f2329; min-width: 200rpx; text-align: center; font-variant-numeric: tabular-nums; }
.amt-ph { color: #c8ccd2; }

.sec-hd { font-size: 24rpx; color: #8a94a6; margin: 8rpx 4rpx; }
.cgrid { display: flex; flex-wrap: wrap; background: #fff; border-radius: 16rpx; padding: 12rpx 8rpx; }
.cat { width: 20%; display: flex; flex-direction: column; align-items: center; padding: 12rpx 0; }
.cic { position: relative; }
.subdot { position: absolute; right: -8rpx; bottom: -2rpx; font-size: 20rpx; color: #8a94a6; }
.cl { font-size: 22rpx; color: #5b6470; margin-top: 8rpx; }
.cl.on { color: #12a150; font-weight: 600; }
.cat.on .cic { transform: scale(1.02); }
.subwrap { display: flex; flex-wrap: wrap; background: #f0f2f5; border-radius: 16rpx; padding: 8rpx; margin-top: 8rpx; }
.subwrap .cat { width: 20%; }
.empty { background: #fff; border-radius: 16rpx; padding: 32rpx; text-align: center; color: #8a94a6; font-size: 26rpx; }
.link { color: #12a150; }

.frow { display: flex; align-items: center; justify-content: space-between; background: #fff; border-radius: 16rpx; padding: 24rpx; margin-top: 16rpx; min-height: 96rpx; box-sizing: border-box; }
.fk { font-size: 28rpx; color: #1f2329; }
.fv { font-size: 28rpx; color: #5b6470; display: flex; align-items: center; }
.fv.row { gap: 12rpx; }
.fv-input { flex: 1; text-align: right; font-size: 28rpx; color: #1f2329; }
.ph { color: #c8ccd2; }
.muted { color: #c8ccd2; }

.avatar { width: 56rpx; height: 56rpx; border-radius: 50%; background: #e6f4ec; color: #12a150; display: flex; align-items: center; justify-content: center; font-size: 26rpx; font-weight: 600; }
.avatar.sm { width: 44rpx; height: 44rpx; font-size: 22rpx; }

.seg { display: flex; background: #eceef1; border-radius: 14rpx; padding: 6rpx; margin-top: 16rpx; }
.seg .s { flex: 1; text-align: center; padding: 16rpx 0; font-size: 28rpx; color: #5b6470; border-radius: 10rpx; }
.seg .s.on { background: #fff; color: #12a150; font-weight: 600; }

.splits { background: #fff; border-radius: 16rpx; padding: 8rpx 24rpx; margin-top: 16rpx; }
.mem { display: flex; align-items: center; gap: 16rpx; padding: 20rpx 0; border-bottom: 1rpx solid #f0f2f5; }
.mem:last-child { border-bottom: none; }
.mem .nm { flex: 1; font-size: 28rpx; color: #1f2329; }
.mem .val { font-size: 28rpx; color: #1f2329; }
.tabnum { font-variant-numeric: tabular-nums; }
.amt-in { display: flex; align-items: center; gap: 4rpx; }
.cshare { width: 160rpx; text-align: right; font-size: 28rpx; color: #1f2329; }
.diff { padding: 16rpx 0 8rpx; font-size: 24rpx; text-align: right; }
.diff.ok { color: #12a150; }
.diff.bad { color: #e5533d; }

.impact { background: #f7f9fb; border: 1rpx solid #e9edf2; border-radius: 16rpx; padding: 20rpx 24rpx; margin-top: 16rpx; }
.impact .hd { font-size: 24rpx; color: #8a94a6; margin-bottom: 12rpx; }
.impact .ln { display: flex; justify-content: space-between; align-items: center; padding: 6rpx 0; }
.impact .ln .t { font-size: 26rpx; color: #5b6470; }
.impact .sub { margin-top: 8rpx; padding-top: 8rpx; border-top: 1rpx dashed #e0e4ea; }
.impact .neg { color: #e5544b; font-variant-numeric: tabular-nums; }
.impact .lend { color: #3a7bd5; font-variant-numeric: tabular-nums; }
.muted-box .t { font-size: 24rpx; color: #8a94a6; }

.pad { height: 40rpx; }

.footer { padding: 16rpx 24rpx calc(16rpx + env(safe-area-inset-bottom)); background: #fff; border-top: 1rpx solid #f0f2f5; }
.save { display: block; text-align: center; background: #12a150; color: #fff; border-radius: 44rpx; padding: 26rpx 0; font-size: 30rpx; font-weight: 600; }
.save.busy { opacity: 0.6; }

.mask { position: fixed; inset: 0; background: rgba(0,0,0,0.4); z-index: 50; display: flex; align-items: flex-end; }
.sheet { width: 100%; background: #fff; border-radius: 24rpx 24rpx 0 0; padding: 24rpx 24rpx calc(24rpx + env(safe-area-inset-bottom)); max-height: 76vh; display: flex; flex-direction: column; }
.sheet-hd { display: flex; align-items: center; justify-content: space-between; }
.sheet-title { font-size: 30rpx; font-weight: 600; color: #1f2329; padding: 8rpx 0 16rpx; }
.all { font-size: 26rpx; color: #12a150; }
.slist { max-height: 56vh; }
.sitem { display: flex; align-items: center; gap: 16rpx; padding: 20rpx 8rpx; border-bottom: 1rpx solid #f0f2f5; }
.si-name { flex: 1; display: flex; flex-direction: column; }
.si-nm { font-size: 28rpx; color: #1f2329; flex: 1; }
.si-type { font-size: 22rpx; color: #8a94a6; }
.si-bal { font-size: 26rpx; color: #1f2329; font-variant-numeric: tabular-nums; }
.si-bal.neg { color: #e5533d; }
.tick { color: #12a150; font-size: 30rpx; margin-left: 12rpx; }
.check { font-size: 34rpx; color: #c8ccd2; margin-left: 12rpx; }
.check.on { color: #12a150; }
.sheet-done { text-align: center; background: #12a150; color: #fff; border-radius: 44rpx; padding: 24rpx 0; font-size: 30rpx; font-weight: 600; margin-top: 16rpx; }
</style>
