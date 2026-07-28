<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { listAccounts, accountTypeLabel } from '../../api/account'
import { listCategories, flattenCategories } from '../../api/category'
import {
  createTransaction,
  getTransaction,
  updateTransaction
} from '../../api/transaction'
import { useLedgerStore } from '../../stores/ledger'
import { categoryEmoji, formatAmount } from '../../utils/format'

const ledgerStore = useLedgerStore()

const TYPES = [
  { value: 'expense', label: '支出' },
  { value: 'income', label: '收入' },
  { value: 'transfer', label: '转账' }
]

const CAT_TINTS = [
  '#e9f7ef', '#e8f0fe', '#fff1e6', '#f3ecff', '#fdeaf3',
  '#e6f6ff', '#fff6e0', '#eafaf0', '#fdeae8', '#e6fbf7'
]
const ACCOUNT_DOT = {
  CASH: '#16a34a', BANK_CARD: '#0ea5e9', ALIPAY: '#1677ff',
  WECHAT: '#07c160', CREDIT_CARD: '#f59e0b'
}
function accountDot(t) {
  return ACCOUNT_DOT[t] || '#94a3b8'
}

const type = ref('expense')
const amount = ref('')
const note = ref('')
const submitting = ref(false)

const accounts = ref([])
const categoryTree = ref({ expense: [], income: [] })
const accountId = ref(null)
const destId = ref(null)
const categoryId = ref(null)

const editingId = ref(null)
const editingOccurredAt = ref(null)
const isEditing = computed(() => editingId.value !== null)

// 目标账本：编辑时取该笔流水自己的账本；新增时若处于「全部」则让用户选择归到哪个账本。
const targetLedgerId = ref(null)
const ledgerChoices = ref([])
const ledgerChoiceIndex = ref(0)
// 是否需要显示「归属账本」选择（新增 + 全部模式）
const showLedgerPicker = computed(() => !isEditing.value && ledgerStore.isAll)

// 账户选择底部面板：'account' | 'source' | 'dest' | null
const sheetTarget = ref(null)

const isTransfer = computed(() => type.value === 'transfer')

// 分类网格选项（当前类型），带 emoji 与配色
const categoryOptions = computed(() => {
  const nodes = type.value === 'income' ? categoryTree.value.income : categoryTree.value.expense
  return flattenCategories(nodes).map((o, i) => ({
    id: o.id,
    label: o.label,
    emoji: categoryEmoji(o.label, type.value),
    tint: CAT_TINTS[i % CAT_TINTS.length]
  }))
})

const accountById = (id) => accounts.value.find((a) => a.id === id)
const sourceAccount = computed(() => accountById(accountId.value))
const destAccount = computed(() => accountById(destId.value))

onLoad(async (q) => {
  editingId.value = q && q.id ? Number(q.id) : null
  // 编辑：用该笔流水自己的账本；新增+全部：先选一个目标账本（默认账本）。
  if (q && q.ledgerId) {
    targetLedgerId.value = Number(q.ledgerId)
  } else if (!isEditing.value && ledgerStore.isAll) {
    try {
      if (!ledgerStore.ledgers.length) await ledgerStore.load()
    } catch (e) {
      /* ignore */
    }
    ledgerChoices.value = ledgerStore.ledgers
    const defIdx = Math.max(ledgerChoices.value.findIndex((l) => l.isDefault), 0)
    ledgerChoiceIndex.value = defIdx
    targetLedgerId.value = ledgerChoices.value[defIdx]?.id ?? null
  }
  load()
})

async function load() {
  try {
    const [accs, cats] = await Promise.all([
      listAccounts(targetLedgerId.value),
      listCategories(targetLedgerId.value)
    ])
    accounts.value = accs
    categoryTree.value = cats
    accountId.value = accs[0]?.id ?? null
    destId.value = accs.length > 1 ? accs[1].id : null
    if (isEditing.value) {
      await prefill()
      uni.setNavigationBarTitle({ title: '编辑记录' })
    }
  } catch (e) {
    uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  }
}

// 新增+全部：切换归属账本 → 重新加载该账本的账户与分类
function onLedgerChoiceChange(e) {
  const idx = Number(e.detail.value)
  ledgerChoiceIndex.value = idx
  targetLedgerId.value = ledgerChoices.value[idx]?.id ?? null
  categoryId.value = null
  load()
}

async function prefill() {
  const tx = await getTransaction(editingId.value, targetLedgerId.value)
  type.value = tx.type
  amount.value = String(tx.amount)
  note.value = tx.note || ''
  editingOccurredAt.value = tx.occurredAt
  if (tx.type === 'transfer') {
    accountId.value = tx.sourceAccountId
    destId.value = tx.destinationAccountId
  } else {
    accountId.value = tx.accountId
    categoryId.value = tx.categoryId
  }
}

function setType(t) {
  if (type.value === t) return
  type.value = t
  categoryId.value = null
  if (t === 'transfer' && destId.value === accountId.value) {
    const other = accounts.value.find((a) => a.id !== accountId.value)
    destId.value = other ? other.id : null
  }
}

function pickAccount(a) {
  if (sheetTarget.value === 'dest') destId.value = a.id
  else accountId.value = a.id
  sheetTarget.value = null
}

async function submit() {
  if (!amount.value || Number(amount.value) <= 0) {
    uni.showToast({ title: '请输入正确金额', icon: 'none' })
    return
  }
  if (!accounts.value.length) {
    uni.showToast({ title: '请先创建账户', icon: 'none' })
    return
  }
  const payload = { type: type.value, amount: amount.value, note: note.value.trim() || undefined }
  if (isEditing.value && editingOccurredAt.value) payload.occurredAt = editingOccurredAt.value

  if (isTransfer.value) {
    if (accountId.value === destId.value) {
      uni.showToast({ title: '转账账户不能相同', icon: 'none' })
      return
    }
    payload.sourceAccountId = accountId.value
    payload.destinationAccountId = destId.value
  } else {
    if (!categoryOptions.value.length) {
      uni.showModal({
        title: '还没有分类',
        content: '支出和收入需要选择分类，先去创建一个吧。',
        confirmText: '去创建',
        success: (r) => {
          if (r.confirm) uni.navigateTo({ url: '/pages/categories/categories' })
        }
      })
      return
    }
    if (!categoryId.value) {
      uni.showToast({ title: '请选择分类', icon: 'none' })
      return
    }
    payload.accountId = accountId.value
    payload.categoryId = categoryId.value
  }

  submitting.value = true
  try {
    if (isEditing.value) {
      await updateTransaction(editingId.value, payload, targetLedgerId.value)
      uni.showToast({ title: '已保存', icon: 'success' })
      setTimeout(() => uni.navigateBack(), 600)
    } else {
      await createTransaction(payload, targetLedgerId.value)
      uni.showToast({ title: '已记录', icon: 'success' })
      amount.value = ''
      note.value = ''
    }
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="page" :class="type">
    <!-- 类型 tab -->
    <view class="tabs">
      <view
        v-for="t in TYPES"
        :key="t.value"
        class="tab"
        :class="{ active: type === t.value }"
        @click="setType(t.value)"
      >
        {{ t.label }}
      </view>
    </view>

    <!-- 归属账本（仅在「全部」下新增时选择记到哪个账本） -->
    <picker
      v-if="showLedgerPicker"
      class="ledger-row"
      :range="ledgerChoices"
      range-key="name"
      :value="ledgerChoiceIndex"
      @change="onLedgerChoiceChange"
    >
      <text class="lr-k">记到账本</text>
      <text class="lr-v">{{ ledgerChoices[ledgerChoiceIndex]?.name || '默认账本' }} ›</text>
    </picker>

    <!-- 金额 -->
    <view class="amount-card">
      <text class="cur">¥</text>
      <input v-model="amount" class="amount" type="digit" placeholder="0.00" />
    </view>

    <!-- 支出/收入：分类网格 -->
    <template v-if="!isTransfer">
      <view v-if="!categoryOptions.length" class="cats-empty">
        还没有{{ type === 'income' ? '收入' : '支出' }}分类，
        <text class="link" @click="uni.navigateTo({ url: '/pages/categories/categories' })">去添加</text>
      </view>
      <view v-else class="cats">
        <view
          v-for="opt in categoryOptions"
          :key="opt.id"
          class="cat"
          :class="{ active: categoryId === opt.id }"
          @click="categoryId = opt.id"
        >
          <text class="cat-circle" :style="{ background: opt.tint }">{{ opt.emoji }}</text>
          <text class="cat-nm">{{ opt.label }}</text>
        </view>
      </view>

      <!-- 账户 chip -->
      <view class="row" @click="sheetTarget = 'account'">
        <text class="row-label">账户</text>
        <view class="row-value">
          <text class="dot" v-if="sourceAccount" :style="{ background: accountDot(sourceAccount.type) }"></text>
          <text>{{ sourceAccount ? sourceAccount.name : '选择账户' }}</text>
          <text class="caret">▾</text>
        </view>
      </view>
    </template>

    <!-- 转账：两账户 -->
    <template v-else>
      <view class="transfer">
        <view class="acc-pick" @click="sheetTarget = 'source'">
          <text class="ai out">↗</text>
          <text class="at">{{ sourceAccount ? sourceAccount.name : '选择转出账户' }}</text>
          <text class="av out">-{{ amount ? formatAmount(amount) : '0.00' }}</text>
        </view>
        <text class="swap">⇅</text>
        <view class="acc-pick" @click="sheetTarget = 'dest'">
          <text class="ai in">↘</text>
          <text class="at">{{ destAccount ? destAccount.name : '选择转入账户' }}</text>
          <text class="av in">+{{ amount ? formatAmount(amount) : '0.00' }}</text>
        </view>
      </view>
    </template>

    <input v-model="note" class="note" placeholder="添加备注（可选）" maxlength="200" />

    <button class="submit" :class="type" :loading="submitting" @click="submit">
      {{ isEditing ? '保存修改' : '保存' }}
    </button>

    <!-- 账户选择底部面板 -->
    <view v-if="sheetTarget" class="mask" @click="sheetTarget = null">
      <view class="sheet" @click.stop>
        <text class="sheet-title">
          {{ sheetTarget === 'dest' ? '选择转入账户' : sheetTarget === 'source' ? '选择转出账户' : '选择账户' }}
        </text>
        <view
          v-for="a in accounts"
          :key="a.id"
          class="sheet-item"
          @click="pickAccount(a)"
        >
          <text class="dot" :style="{ background: accountDot(a.type) }"></text>
          <view class="s-name">
            <text>{{ a.name }}</text>
            <text class="s-type">{{ accountTypeLabel(a.type) }}</text>
          </view>
          <text class="s-bal" :class="{ neg: Number(a.currentBalance) < 0 }">¥{{ formatAmount(a.currentBalance) }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  --accent: #dc2626;
  min-height: 100vh;
  padding: 24rpx;
}
.page.income,
.page.transfer {
  --accent: #16a34a;
}

/* 类型 tab（下划线激活） */
.tabs {
  display: flex;
  justify-content: center;
  gap: 56rpx;
  padding: 16rpx 0 24rpx;
}
.tab {
  position: relative;
  font-size: 32rpx;
  color: #9ca3af;
  padding: 8rpx 4rpx;
}
.tab.active {
  color: #1f2937;
  font-weight: 800;
}
.tab.active::after {
  content: '';
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  bottom: -4rpx;
  width: 44rpx;
  height: 6rpx;
  border-radius: 4rpx;
  background: #16a34a;
}

/* 归属账本行 */
.ledger-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #fff;
  border-radius: 20rpx;
  padding: 30rpx 32rpx;
  margin-bottom: 20rpx;
}
.lr-k {
  font-size: 30rpx;
  color: #6b7280;
}
.lr-v {
  font-size: 30rpx;
  color: #16a34a;
  font-weight: 600;
}

/* 金额 */
.amount-card {
  display: flex;
  align-items: center;
  background: #fff;
  border-radius: 24rpx;
  padding: 28rpx 36rpx;
  margin-bottom: 24rpx;
}
.cur {
  font-size: 52rpx;
  color: var(--accent);
  font-weight: 700;
  margin-right: 16rpx;
}
.amount {
  flex: 1;
  font-size: 64rpx;
  font-weight: 800;
  color: var(--accent);
}

/* 分类网格 */
.cats {
  display: flex;
  flex-wrap: wrap;
  background: #fff;
  border-radius: 24rpx;
  padding: 28rpx 12rpx;
  margin-bottom: 24rpx;
}
.cat {
  width: 20%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
  padding: 14rpx 0;
}
.cat-circle {
  width: 96rpx;
  height: 96rpx;
  border-radius: 50%;
  text-align: center;
  line-height: 96rpx;
  font-size: 44rpx;
}
.cat.active .cat-circle {
  box-shadow: 0 0 0 4rpx #fff, 0 0 0 8rpx #16a34a;
}
.cat-nm {
  font-size: 22rpx;
  color: #4b5563;
  max-width: 130rpx;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.cat.active .cat-nm {
  color: #15803d;
  font-weight: 700;
}
.cats-empty {
  background: #fff;
  border-radius: 24rpx;
  padding: 48rpx;
  text-align: center;
  color: #6b7280;
  font-size: 28rpx;
  margin-bottom: 24rpx;
}
.link {
  color: #16a34a;
  font-weight: 600;
}

/* 账户行 */
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #fff;
  border-radius: 20rpx;
  padding: 32rpx;
  margin-bottom: 24rpx;
}
.row-label {
  font-size: 30rpx;
  color: #6b7280;
}
.row-value {
  display: flex;
  align-items: center;
  gap: 12rpx;
  font-size: 30rpx;
  color: #1f2937;
}
.caret {
  color: #9ca3af;
  font-size: 22rpx;
}
.dot {
  width: 16rpx;
  height: 16rpx;
  border-radius: 50%;
}

/* 转账 */
.transfer {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 20rpx;
  margin-bottom: 24rpx;
}
.acc-pick {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 20rpx;
  background: #fff;
  border-radius: 20rpx;
  padding: 28rpx;
  box-sizing: border-box;
}
.ai {
  width: 60rpx;
  height: 60rpx;
  border-radius: 16rpx;
  text-align: center;
  line-height: 60rpx;
  font-size: 30rpx;
  background: #ecfdf3;
  color: #16a34a;
}
.at {
  flex: 1;
  font-size: 30rpx;
  font-weight: 600;
  color: #1f2937;
}
.av.out { color: #dc2626; font-weight: 800; }
.av.in { color: #16a34a; font-weight: 800; }
.swap {
  width: 60rpx;
  height: 60rpx;
  border-radius: 50%;
  border: 2rpx solid #e5e7eb;
  text-align: center;
  line-height: 60rpx;
  color: #9ca3af;
}

.note {
  background: #fff;
  border-radius: 20rpx;
  padding: 32rpx;
  font-size: 30rpx;
  margin-bottom: 32rpx;
}
.submit {
  background: #16a34a;
  color: #fff;
  border-radius: 44rpx;
  font-size: 32rpx;
}
.submit.expense {
  background: #dc2626;
}

/* 底部面板 */
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.4);
  display: flex;
  align-items: flex-end;
}
.sheet {
  width: 100%;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 36rpx 32rpx calc(36rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.sheet-title {
  display: block;
  font-size: 30rpx;
  font-weight: 800;
  margin-bottom: 20rpx;
}
.sheet-item {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 26rpx 8rpx;
  border-top: 1rpx solid #eef0f2;
}
.sheet-item:first-of-type {
  border-top: none;
}
.s-name {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}
.s-type {
  font-size: 22rpx;
  color: #9ca3af;
}
.s-bal {
  font-size: 30rpx;
  font-weight: 700;
  color: #1f2937;
}
.s-bal.neg {
  color: #dc2626;
}
</style>
