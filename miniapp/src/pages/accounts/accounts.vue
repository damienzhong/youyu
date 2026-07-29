<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  listAccounts,
  createAccount,
  updateAccount,
  deleteAccount,
  accountTypeLabel,
  accountTypeEmoji,
  accountGroupLabel,
  isCreditType,
  ACCOUNT_TYPES,
  ACCOUNT_GROUPS
} from '../../api/account'
import { listAllAccounts } from '../../api/aggregate'
import { listLoans } from '../../api/loan'
import { useLedgerStore } from '../../stores/ledger'
import { formatAmount } from '../../utils/format'

const ledgerStore = useLedgerStore()

const accounts = ref([])
const loading = ref(false)
const hideAmounts = ref(false)
const collapsed = ref({})

// 借贷汇总（仅具体账本显示；借贷为账本级台账）
const borrowOutstanding = ref('0.00')
const lendOutstanding = ref('0.00')
const showLoans = computed(() => !ledgerStore.isAll)
const hasLoan = computed(() => Number(borrowOutstanding.value) > 0 || Number(lendOutstanding.value) > 0)
function goLoans() {
  uni.navigateTo({ url: '/pages/loans/loans' })
}

// 计入净资产、非隐藏的账户参与统计
const counted = computed(() => accounts.value.filter((a) => a.includeInTotal && !a.hidden))
const netWorth = computed(() => counted.value.reduce((s, a) => s + Number(a.currentBalance), 0))
const totalAssets = computed(() =>
  counted.value.reduce((s, a) => s + Math.max(Number(a.currentBalance), 0), 0)
)
const totalLiab = computed(() =>
  counted.value.reduce((s, a) => s + Math.min(Number(a.currentBalance), 0), 0)
)

// 按分组聚合（仅展示有账户的组），组内保持后端排序
const groups = computed(() => {
  return ACCOUNT_GROUPS.map((g) => {
    const items = accounts.value.filter((a) => (a.group || 'FUNDS') === g.key)
    const subtotal = items.reduce((s, a) => s + Number(a.currentBalance), 0)
    return { ...g, items, subtotal }
  }).filter((g) => g.items.length)
})

function availableOf(a) {
  if (!isCreditType(a.type) || a.creditLimit == null) return null
  return Number(a.creditLimit) + Number(a.currentBalance)
}
function money(v) {
  return hideAmounts.value ? '****' : formatAmount(v)
}
function toggleGroup(key) {
  collapsed.value[key] = !collapsed.value[key]
}

async function load() {
  loading.value = true
  try {
    accounts.value = ledgerStore.isAll ? await listAllAccounts() : await listAccounts()
    if (showLoans.value) {
      try {
        const r = await listLoans()
        borrowOutstanding.value = r.borrowOutstanding
        lendOutstanding.value = r.lendOutstanding
      } catch (e) {
        /* 借贷加载失败不阻断资产页 */
      }
    }
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}
onShow(load)

// ---------- 类型选择器（分组九宫格） ----------
const typePickerOpen = ref(false)
const groupedTypes = computed(() =>
  ACCOUNT_GROUPS.map((g) => ({
    ...g,
    types: ACCOUNT_TYPES.filter((t) => t.group === g.key)
  }))
)

// ---------- 新建 / 编辑表单 ----------
const showForm = ref(false)
const submitting = ref(false)
const form = ref({
  id: null, type: 'CASH', name: '', initialBalance: '', creditLimit: '',
  includeInTotal: true, _hidden: false, _note: null, _ledgerId: null
})
const isEditing = computed(() => form.value.id !== null)
const formIsCredit = computed(() => isCreditType(form.value.type))

function openCreate() {
  // 先选类型，再填详情（对齐竞品）
  typePickerOpen.value = true
}
function pickType(t) {
  typePickerOpen.value = false
  form.value = {
    id: null, type: t.value, name: t.label, initialBalance: '', creditLimit: '',
    includeInTotal: true, _hidden: false, _note: null, _ledgerId: null
  }
  showForm.value = true
}
function reopenTypePicker() {
  // 编辑时更换类型
  typePickerOpen.value = true
}
function pickTypeForEdit(t) {
  // 若正在编辑，更换类型但保留其它字段
  if (isEditing.value) {
    typePickerOpen.value = false
    form.value.type = t.value
    showForm.value = true
  } else {
    pickType(t)
  }
}
function openEdit(acc) {
  form.value = {
    id: acc.id, type: acc.type, name: acc.name, initialBalance: '',
    creditLimit: acc.creditLimit != null ? String(acc.creditLimit) : '',
    includeInTotal: acc.includeInTotal, _hidden: acc.hidden, _note: acc.note, _ledgerId: acc.ledgerId
  }
  showForm.value = true
}

async function submit() {
  const name = form.value.name.trim()
  if (!name) {
    uni.showToast({ title: '请输入账户名称', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    const creditLimit = formIsCredit.value && form.value.creditLimit !== ''
      ? form.value.creditLimit : undefined
    if (isEditing.value) {
      await updateAccount(form.value.id, {
        name, type: form.value.type, includeInTotal: form.value.includeInTotal,
        hidden: form.value._hidden, note: form.value._note, creditLimit
      }, form.value._ledgerId)
    } else {
      await createAccount({
        name, type: form.value.type,
        initialBalance: form.value.initialBalance === '' ? '0' : form.value.initialBalance,
        includeInTotal: form.value.includeInTotal, creditLimit
      })
    }
    showForm.value = false
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

function confirmDelete() {
  const acc = { id: form.value.id, name: form.value.name, ledgerId: form.value._ledgerId }
  uni.showModal({
    title: '删除账户',
    content: `确定删除「${acc.name}」？有交易记录的账户无法删除。`,
    confirmColor: '#e5484d',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await deleteAccount(acc.id, acc.ledgerId)
        showForm.value = false
        await load()
      } catch (e) {
        uni.showToast({ title: e.message || '删除失败', icon: 'none' })
      }
    }
  })
}
</script>

<template>
  <view class="page">
    <!-- 净资产卡（深色，区别于品牌绿） -->
    <view class="networth">
      <view class="nw-top">
        <text class="nw-label">净资产 <text class="eye" @click="hideAmounts = !hideAmounts">{{ hideAmounts ? '🙈' : '👁' }}</text></text>
      </view>
      <text class="nw-value" :class="{ neg: netWorth < 0 }">{{ money(netWorth) }}</text>
      <view class="nw-foot">
        <text>总资产 {{ money(totalAssets) }}</text>
        <text>总负债 {{ money(Math.abs(totalLiab)) }}</text>
      </view>
    </view>

    <!-- 借贷往来（借入待还 / 借出待收） -->
    <view v-if="showLoans" class="loan-row" @click="goLoans">
      <view class="loan-tile">
        <text class="lt-k">借入 / 待还</text>
        <text class="lt-v exp">{{ money(borrowOutstanding) }}</text>
      </view>
      <view class="loan-sep"></view>
      <view class="loan-tile">
        <text class="lt-k">借出 / 待收</text>
        <text class="lt-v inc">{{ money(lendOutstanding) }}</text>
      </view>
      <text class="loan-caret">›</text>
    </view>

    <view v-if="!accounts.length && !loading" class="empty">还没有账户，点右下角添加</view>

    <!-- 分组账户 -->
    <view v-for="g in groups" :key="g.key" class="group">
      <view class="group-head" @click="toggleGroup(g.key)">
        <text class="gh-title">{{ g.label }}</text>
        <view class="gh-right">
          <text class="gh-sum" :class="{ neg: g.subtotal < 0 }">{{ money(g.subtotal) }}</text>
          <text class="gh-caret">{{ collapsed[g.key] ? '▾' : '▴' }}</text>
        </view>
      </view>
      <view v-if="!collapsed[g.key]" class="acc-list">
        <view v-for="a in g.items" :key="a.id" class="acc" @click="openEdit(a)">
          <text class="acc-ic">{{ accountTypeEmoji(a.type) }}</text>
          <view class="acc-main">
            <text class="acc-name">{{ a.name }}<text v-if="!a.includeInTotal" class="acc-flag"> · 不计入</text></text>
            <text v-if="availableOf(a) != null" class="acc-sub">可用 {{ money(availableOf(a)) }}</text>
            <text v-else class="acc-sub">{{ accountTypeLabel(a.type) }}</text>
          </view>
          <text class="acc-bal" :class="{ neg: Number(a.currentBalance) < 0 }">{{ money(a.currentBalance) }}</text>
        </view>
      </view>
    </view>

    <view style="height:140rpx;"></view>
    <view class="fab" @click="openCreate">＋</view>

    <!-- 类型选择器 -->
    <view v-if="typePickerOpen" class="mask" @click="typePickerOpen = false">
      <view class="sheet type-sheet" @click.stop>
        <text class="sheet-title">选择账户类型</text>
        <scroll-view scroll-y class="type-scroll">
          <view v-for="g in groupedTypes" :key="g.key" class="tg">
            <text class="tg-title">{{ g.label }}</text>
            <view class="tg-grid">
              <view v-for="t in g.types" :key="t.value" class="tt" @click="pickTypeForEdit(t)">
                <text class="tt-ic">{{ t.emoji }}</text>
                <text class="tt-label">{{ t.label }}</text>
              </view>
            </view>
          </view>
        </scroll-view>
      </view>
    </view>

    <!-- 新建/编辑表单 -->
    <view v-if="showForm" class="mask" @click="showForm = false">
      <view class="sheet" @click.stop>
        <view class="form-head">
          <text class="fh-cancel" @click="showForm = false">取消</text>
          <text class="fh-title">{{ isEditing ? '编辑账户' : '新建账户' }}</text>
          <text class="fh-save" @click="submit">保存</text>
        </view>
        <view class="form-body">
          <view class="frow" @click="reopenTypePicker">
            <text class="fk">类型</text>
            <text class="fv">{{ accountTypeEmoji(form.type) }} {{ accountTypeLabel(form.type) }} ›</text>
          </view>
          <view class="frow">
            <text class="fk">名称</text>
            <input v-model="form.name" class="finput" placeholder="账户名称" maxlength="50" />
          </view>
          <view v-if="!isEditing" class="frow">
            <text class="fk">初始余额</text>
            <input v-model="form.initialBalance" class="finput" type="digit" placeholder="0.00" />
          </view>
          <view v-if="formIsCredit" class="frow">
            <text class="fk">授信额度</text>
            <input v-model="form.creditLimit" class="finput" type="digit" placeholder="可选" />
          </view>
          <view class="frow">
            <text class="fk">计入净资产</text>
            <switch :checked="form.includeInTotal" color="#12a150" @change="form.includeInTotal = $event.detail.value" />
          </view>
        </view>
        <button v-if="isEditing" class="del" @click="confirmDelete">删除账户</button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  background: #eef0f2;
}
/* 净资产卡：深色，突出资产、区别品牌绿 */
.networth {
  border-radius: 26rpx;
  padding: 36rpx;
  margin-bottom: 24rpx;
  color: #fff;
  background: linear-gradient(150deg, #2b3a34, #1f2a30 70%);
  box-shadow: 0 18rpx 40rpx rgba(31, 42, 48, 0.28);
}
.nw-label {
  font-size: 24rpx;
  opacity: 0.85;
}
.eye {
  font-size: 24rpx;
  margin-left: 8rpx;
}
.nw-value {
  display: block;
  font-size: 68rpx;
  font-weight: 800;
  letter-spacing: -0.02em;
  margin: 8rpx 0 20rpx;
}
.nw-value::before {
  content: '¥';
  font-size: 36rpx;
  opacity: 0.8;
  margin-right: 6rpx;
}
.nw-value.neg {
  color: #fecaca;
}
.nw-foot {
  display: flex;
  justify-content: space-between;
  font-size: 24rpx;
  opacity: 0.9;
}
.loan-row {
  display: flex;
  align-items: center;
  background: #fff;
  border-radius: 22rpx;
  padding: 24rpx 28rpx;
  margin-bottom: 24rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.loan-tile {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.lt-k {
  font-size: 24rpx;
  color: #9aa2ad;
}
.lt-v {
  font-size: 34rpx;
  font-weight: 800;
}
.lt-v::before {
  content: '¥';
  font-size: 22rpx;
  opacity: 0.7;
  margin-right: 2rpx;
}
.lt-v.exp {
  color: #e5563d;
}
.lt-v.inc {
  color: #0f8a45;
}
.loan-sep {
  width: 1rpx;
  height: 56rpx;
  background: #eceef1;
  margin: 0 8rpx;
}
.loan-caret {
  color: #c0c4cc;
  font-size: 34rpx;
  margin-left: 12rpx;
}
.empty {
  margin-top: 120rpx;
  text-align: center;
  color: #9aa2ad;
  font-size: 28rpx;
}
.group {
  margin-bottom: 20rpx;
}
.group-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8rpx 12rpx 14rpx;
}
.gh-title {
  font-size: 26rpx;
  font-weight: 700;
  color: #5b6470;
}
.gh-right {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.gh-sum {
  font-size: 28rpx;
  font-weight: 800;
  color: #16181c;
}
.gh-sum.neg {
  color: #e5484d;
}
.gh-caret {
  font-size: 22rpx;
  color: #9aa2ad;
}
.acc-list {
  background: #fff;
  border-radius: 22rpx;
  overflow: hidden;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.acc {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 26rpx 28rpx;
  border-top: 1rpx solid #f1f3f5;
}
.acc-list .acc:first-child {
  border-top: none;
}
.acc-ic {
  width: 76rpx;
  height: 76rpx;
  border-radius: 22rpx;
  background: #f4f6f8;
  text-align: center;
  line-height: 76rpx;
  font-size: 38rpx;
  flex: 0 0 auto;
}
.acc-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.acc-name {
  font-size: 30rpx;
  color: #16181c;
  font-weight: 600;
}
.acc-flag {
  font-size: 22rpx;
  color: #9aa2ad;
  font-weight: 400;
}
.acc-sub {
  font-size: 22rpx;
  color: #9aa2ad;
}
.acc-bal {
  font-size: 32rpx;
  font-weight: 800;
  color: #16181c;
}
.acc-bal.neg {
  color: #e5484d;
}
.fab {
  position: fixed;
  right: 40rpx;
  bottom: calc(136rpx + env(safe-area-inset-bottom));
  width: 104rpx;
  height: 104rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, #18b85a, #0e8a44);
  color: #fff;
  font-size: 62rpx;
  line-height: 104rpx;
  text-align: center;
  box-shadow: 0 14rpx 34rpx rgba(18, 161, 80, 0.45);
  z-index: 200;
}
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.42);
  display: flex;
  align-items: flex-end;
  z-index: 50;
}
.sheet {
  width: 100%;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 32rpx 32rpx calc(32rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.sheet-title {
  display: block;
  text-align: center;
  font-size: 30rpx;
  font-weight: 800;
  color: #16181c;
  margin-bottom: 20rpx;
}
/* 类型九宫格 */
.type-sheet {
  max-height: 84vh;
}
.type-scroll {
  max-height: 70vh;
}
.tg {
  margin-bottom: 10rpx;
}
.tg-title {
  font-size: 24rpx;
  font-weight: 700;
  color: #5b6470;
  padding: 12rpx 4rpx;
}
.tg-grid {
  display: flex;
  flex-wrap: wrap;
}
.tt {
  width: 20%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10rpx;
  padding: 18rpx 0;
}
.tt-ic {
  width: 84rpx;
  height: 84rpx;
  border-radius: 50%;
  background: #f4f6f8;
  text-align: center;
  line-height: 84rpx;
  font-size: 40rpx;
}
.tt-label {
  font-size: 22rpx;
  color: #4b5563;
}
/* 表单 */
.form-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8rpx;
}
.fh-cancel {
  font-size: 28rpx;
  color: #9aa2ad;
}
.fh-title {
  font-size: 30rpx;
  font-weight: 800;
}
.fh-save {
  font-size: 28rpx;
  color: #12a150;
  font-weight: 700;
}
.form-body {
  background: #f6f7f9;
  border-radius: 18rpx;
  padding: 0 24rpx;
  margin-top: 16rpx;
}
.frow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 0;
  border-top: 1rpx solid #eceef1;
}
.frow:first-child {
  border-top: none;
}
.fk {
  font-size: 30rpx;
  color: #5b6470;
}
.fv {
  font-size: 30rpx;
  color: #16181c;
  font-weight: 600;
}
.finput {
  flex: 1;
  text-align: right;
  font-size: 30rpx;
  color: #16181c;
}
.del {
  margin-top: 24rpx;
  background: #fff;
  color: #e5484d;
  border-radius: 44rpx;
  font-size: 30rpx;
  border: 1rpx solid #f1d4d4;
}
</style>
