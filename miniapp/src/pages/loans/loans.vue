<script setup>
import { ref, computed } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import {
  listLoans,
  createLoan,
  updateLoan,
  settleLoan,
  deleteLoan
} from '../../api/loan'
import { listSelectableAccounts, accountDisplayName, accountTypeIcon } from '../../api/account'
import { formatAmount } from '../../utils/format'

// 方向：本页按单一方向（借入 BORROW / 借出 LEND）展示，由资产页两张卡片带入。
const direction = ref('BORROW')
const isLend = computed(() => direction.value === 'LEND')

// 文案随方向切换（对齐竞品）。
const L = computed(() => (isLend.value
  ? { title: '借出', name: '借款人姓名', account: '借出账户', due: '收款日期',
      inc: '借出待收金额是否计入总资产', outstanding: '待收总额', done: '已收总额', total: '借出总额' }
  : { title: '借入', name: '出借人姓名', account: '存入账户', due: '还款日期',
      inc: '借入待还金额是否计入总资产', outstanding: '待还总额', done: '已还总额', total: '借入总额' }))

const loans = ref([])
const loading = ref(false)

onLoad((q) => {
  if (q && (q.direction === 'LEND' || q.direction === 'BORROW')) direction.value = q.direction
  uni.setNavigationBarTitle({ title: isLend.value ? '借出 / 待收' : '借入 / 待还' })
})

const shown = computed(() => loans.value.filter((l) => l.direction === direction.value))
const totalAll = computed(() => shown.value.reduce((s, l) => s + Number(l.amount), 0))
const outstanding = computed(() =>
  shown.value.filter((l) => !l.settled).reduce((s, l) => s + Number(l.amount), 0))
const done = computed(() => totalAll.value - outstanding.value)

async function load() {
  loading.value = true
  try {
    const r = await listLoans()
    loans.value = r.loans || []
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}
onShow(load)

function dateOf(iso) {
  return iso ? iso.slice(0, 10) : ''
}

// ---------- 账户选择 ----------
const accounts = ref([])
const accountById = (id) => accounts.value.find((a) => a.id === id)
async function loadAccounts() {
  try {
    accounts.value = await listSelectableAccounts()
  } catch (e) {
    accounts.value = []
  }
}
const acctSheet = ref(false)
function openAcctSheet() { acctSheet.value = true }
function pickAccount(a) {
  form.value.accountId = a.id
  acctSheet.value = false
}
const accountName = computed(() => {
  const a = accountById(form.value.accountId)
  return a ? accountDisplayName(a) : '选择账户'
})

// ---------- 表单 ----------
const showForm = ref(false)
const submitting = ref(false)
function todayStr() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
const form = ref(blankForm())
const isEditing = computed(() => form.value.id !== null)
function blankForm() {
  return {
    id: null, counterparty: '', amount: '', accountId: null,
    occurredDate: todayStr(), dueDate: '', includeInTotal: true, note: ''
  }
}

async function openCreate() {
  form.value = blankForm()
  await loadAccounts()
  // 默认选中首个可用账户（对齐竞品：账户必选）。
  if (accounts.value.length) form.value.accountId = accounts.value[0].id
  showForm.value = true
}
async function openEdit(l) {
  await loadAccounts()
  form.value = {
    id: l.id,
    counterparty: l.counterparty,
    amount: String(l.amount),
    accountId: l.accountId != null ? l.accountId : (accounts.value[0] ? accounts.value[0].id : null),
    occurredDate: (l.occurredAt || todayStr()).slice(0, 10),
    dueDate: l.dueDate ? l.dueDate.slice(0, 10) : '',
    includeInTotal: l.includeInTotal !== false,
    note: l.note || ''
  }
  showForm.value = true
}

function onOccurredChange(e) { form.value.occurredDate = e.detail.value }
function onDueChange(e) { form.value.dueDate = e.detail.value }
function clearDue() { form.value.dueDate = '' }

async function submit() {
  const counterparty = form.value.counterparty.trim()
  if (!counterparty) {
    uni.showToast({ title: `请输入${L.value.name}`, icon: 'none' })
    return
  }
  if (!form.value.amount || Number(form.value.amount) <= 0) {
    uni.showToast({ title: '请输入正确金额', icon: 'none' })
    return
  }
  if (!form.value.accountId) {
    uni.showToast({ title: `请选择${L.value.account}`, icon: 'none' })
    return
  }
  if (submitting.value) return
  submitting.value = true
  try {
    const payload = {
      direction: direction.value,
      counterparty,
      amount: form.value.amount,
      accountId: form.value.accountId,
      occurredAt: `${form.value.occurredDate}T12:00:00`,
      dueDate: form.value.dueDate ? `${form.value.dueDate}T12:00:00` : undefined,
      includeInTotal: form.value.includeInTotal,
      note: form.value.note.trim() || undefined
    }
    if (isEditing.value) await updateLoan(form.value.id, payload)
    else await createLoan(payload)
    showForm.value = false
    uni.showToast({ title: '已保存', icon: 'success' })
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

async function toggleSettle(l) {
  try {
    await settleLoan(l.id, !l.settled)
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '操作失败', icon: 'none' })
  }
}

function confirmDelete() {
  const id = form.value.id
  uni.showModal({
    title: '删除记录',
    content: '删除后若未结清会同步回补账户余额，确定删除？',
    confirmColor: '#e5484d',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await deleteLoan(id)
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
  <view class="page" :class="isLend ? 'lend' : 'borrow'">
    <!-- 汇总头 -->
    <view class="hero">
      <text class="h-k">{{ L.outstanding }}（元）</text>
      <text class="h-v">{{ formatAmount(outstanding) }}</text>
      <view class="h-foot">
        <text>{{ L.done }} {{ formatAmount(done) }}</text>
        <text>{{ L.total }} {{ formatAmount(totalAll) }}</text>
      </view>
    </view>

    <view v-if="!shown.length && !loading" class="empty">
      还没有{{ L.title }}记录，点下方「添加」
    </view>

    <view class="list" v-if="shown.length">
      <view v-for="l in shown" :key="l.id" class="item" :class="{ settled: l.settled }" @click="openEdit(l)">
        <view class="i-ic" :class="isLend ? 'lend' : 'borrow'">
          <AppIcon :name="isLend ? 'transfer' : 'wallet'" :size="40" color="#fff" />
        </view>
        <view class="i-main">
          <text class="i-name">{{ l.counterparty }}</text>
          <text class="i-sub">
            {{ dateOf(l.occurredAt) }}<text v-if="l.dueDate"> · {{ L.due }} {{ dateOf(l.dueDate) }}</text>
          </text>
        </view>
        <view class="i-right">
          <text v-if="!l.includeInTotal" class="i-flag">不计入</text>
          <text class="i-amt">{{ formatAmount(l.amount) }}</text>
          <text class="i-act" @click.stop="toggleSettle(l)">{{ l.settled ? '恢复' : (isLend ? '收款' : '还款') }}</text>
        </view>
      </view>
    </view>

    <view style="height:160rpx;"></view>

    <!-- 底部添加 -->
    <view class="addbar" @click="openCreate">
      <text class="add-plus">＋</text><text>添加{{ L.title }}</text>
    </view>

    <!-- 表单弹窗 -->
    <view v-if="showForm" class="mask" @click="showForm = false">
      <view class="sheet" @click.stop>
        <view class="form-head">
          <text class="fh-cancel" @click="showForm = false">取消</text>
          <text class="fh-title">{{ isEditing ? '编辑' : '添加' }}{{ L.title }}</text>
          <text class="fh-save" @click="submit">保存</text>
        </view>
        <input v-model="form.counterparty" class="name-input" :placeholder="L.name" maxlength="50" />
        <view class="form-body">
          <view class="frow">
            <text class="fk">借款金额</text>
            <input v-model="form.amount" class="finput" type="digit" placeholder="0.00" />
          </view>
          <view class="frow" @click="openAcctSheet">
            <text class="fk">{{ L.account }}</text>
            <text class="fv" :class="{ ph: !form.accountId }">{{ accountName }} ›</text>
          </view>
          <picker mode="date" :value="form.occurredDate" @change="onOccurredChange">
            <view class="frow">
              <text class="fk">借款日期</text>
              <text class="fv">{{ form.occurredDate }} ›</text>
            </view>
          </picker>
          <picker mode="date" :value="form.dueDate || todayStr()" @change="onDueChange">
            <view class="frow">
              <text class="fk">{{ L.due }}</text>
              <view class="due-right">
                <text class="fv" :class="{ ph: !form.dueDate }">{{ form.dueDate || '选填' }} ›</text>
                <text v-if="form.dueDate" class="due-clear" @click.stop="clearDue">✕</text>
              </view>
            </view>
          </picker>
          <view class="frow col">
            <view class="frow-top">
              <text class="fk small">{{ L.inc }}</text>
              <switch :checked="form.includeInTotal" color="#12a150" @change="form.includeInTotal = $event.detail.value" />
            </view>
          </view>
        </view>
        <textarea v-model="form.note" class="note" placeholder="备注" maxlength="200" />
        <button v-if="isEditing" class="del" @click="confirmDelete">删除记录</button>
      </view>
    </view>

    <!-- 账户选择 -->
    <view v-if="acctSheet" class="mask mask-top" @click="acctSheet = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">选择{{ L.account }}</text>
        <view v-if="!accounts.length" class="sempty">该账本暂无可用账户</view>
        <view v-for="a in accounts" :key="a.id" class="sitem" @click="pickAccount(a)">
          <view class="si-ic"><AppIcon :name="accountTypeIcon(a.type)" :size="40" /></view>
          <text class="si-nm">{{ accountDisplayName(a) }}</text>
          <text class="radio" :class="{ on: form.accountId === a.id }"></text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; background: #eef0f2; }
/* 汇总头 */
.hero { padding: 40rpx 40rpx 44rpx; color: #fff; }
.page.borrow .hero { background: linear-gradient(150deg, #6b74c9, #4a54b3 72%); }
.page.lend .hero { background: linear-gradient(150deg, #7b86d6, #5b66bf 72%); }
.h-k { font-size: 24rpx; opacity: 0.9; }
.h-v { display: block; font-size: 70rpx; font-weight: 800; letter-spacing: -0.02em; margin: 8rpx 0 18rpx; }
.h-foot { display: flex; justify-content: space-between; font-size: 24rpx; opacity: 0.92; }
.empty { text-align: center; color: #9aa2ad; font-size: 28rpx; margin-top: 100rpx; }
/* 列表 */
.list { margin: 20rpx 24rpx 0; background: #fff; border-radius: 22rpx; overflow: hidden; box-shadow: 0 8rpx 24rpx rgba(20,24,28,0.05); }
.item { display: flex; align-items: center; gap: 20rpx; padding: 26rpx 28rpx; border-top: 1rpx solid #f1f3f5; }
.list .item:first-child { border-top: none; }
.item.settled { opacity: 0.5; }
.i-ic { width: 72rpx; height: 72rpx; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex: 0 0 auto; }
.i-ic.borrow { background: #5b66bf; }
.i-ic.lend { background: #b58a6a; }
.i-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.i-name { font-size: 30rpx; font-weight: 600; color: #16181c; }
.i-sub { font-size: 22rpx; color: #9aa2ad; }
.i-right { display: flex; flex-direction: column; align-items: flex-end; gap: 6rpx; }
.i-flag { font-size: 20rpx; color: #9aa2ad; background: #f0f2f5; border-radius: 999rpx; padding: 2rpx 12rpx; }
.i-amt { font-size: 32rpx; font-weight: 800; color: #16181c; }
.i-act { font-size: 22rpx; color: #576b95; background: #f4f6f8; border-radius: 999rpx; padding: 4rpx 16rpx; }
/* 底部添加 */
.addbar {
  position: fixed; left: 0; right: 0; bottom: 0; z-index: 20;
  display: flex; align-items: center; justify-content: center; gap: 10rpx;
  background: #fff; border-top: 1rpx solid #eef0f2;
  padding: 26rpx 0 calc(26rpx + env(safe-area-inset-bottom));
  font-size: 30rpx; font-weight: 700; color: #12a150;
  box-shadow: 0 -6rpx 20rpx rgba(20,24,28,0.05);
}
.add-plus { font-size: 36rpx; line-height: 1; }
/* 弹窗 */
.mask { position: fixed; inset: 0; background: rgba(15,23,42,0.42); display: flex; align-items: flex-end; z-index: 60; }
.mask-top { z-index: 80; }
.sheet { width: 100%; background: #fff; border-radius: 28rpx 28rpx 0 0; padding: 28rpx 32rpx calc(32rpx + env(safe-area-inset-bottom)); box-sizing: border-box; }
.form-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 20rpx; }
.fh-cancel { font-size: 28rpx; color: #9aa2ad; }
.fh-title { font-size: 30rpx; font-weight: 800; }
.fh-save { font-size: 28rpx; color: #12a150; font-weight: 700; }
.name-input { font-size: 34rpx; font-weight: 700; color: #16181c; padding: 12rpx 4rpx 20rpx; border-bottom: 1rpx solid #eceef1; }
.form-body { background: #fff; border-radius: 0; padding: 0; }
.frow { display: flex; align-items: center; justify-content: space-between; padding: 28rpx 4rpx; border-bottom: 1rpx solid #f1f3f5; }
.frow.col { flex-direction: column; align-items: stretch; }
.frow-top { display: flex; align-items: center; justify-content: space-between; }
.fk { font-size: 30rpx; color: #5b6470; }
.fk.small { font-size: 26rpx; }
.fv { font-size: 30rpx; color: #16181c; font-weight: 600; }
.fv.ph { color: #c2c7cd; font-weight: 400; }
.finput { flex: 1; text-align: right; font-size: 30rpx; color: #16181c; }
.due-right { display: flex; align-items: center; gap: 16rpx; }
.due-clear { font-size: 26rpx; color: #c0c4cc; }
.note { width: 100%; box-sizing: border-box; margin-top: 20rpx; background: #f6f7f9; border-radius: 16rpx; padding: 22rpx; font-size: 28rpx; min-height: 120rpx; }
.del { margin-top: 24rpx; background: #fff; color: #e5484d; border-radius: 44rpx; font-size: 30rpx; border: 1rpx solid #f1d4d4; }
.sheet-title { display: block; text-align: center; font-size: 30rpx; font-weight: 800; color: #16181c; margin-bottom: 16rpx; }
.sempty { text-align: center; color: #9aa2ad; font-size: 26rpx; padding: 40rpx 0; }
.sitem { display: flex; align-items: center; gap: 18rpx; padding: 24rpx 8rpx; border-top: 1rpx solid #f1f3f5; }
.si-ic { width: 64rpx; height: 64rpx; border-radius: 18rpx; background: #f4f5f7; display: flex; align-items: center; justify-content: center; }
.si-nm { flex: 1; font-size: 30rpx; color: #16181c; }
.radio { width: 36rpx; height: 36rpx; border-radius: 50%; border: 2rpx solid #cfd4da; box-sizing: border-box; }
.radio.on { border-color: #12a150; background: radial-gradient(circle at center, #12a150 40%, transparent 42%); }
</style>
