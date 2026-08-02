<script setup>
import { ref, computed } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { getLoan, addRepayment, deleteRepayment, updateLoan, deleteLoan } from '../../api/loan'
import { listAccounts, accountDisplayName, accountTypeIcon } from '../../api/account'
import { formatAmount } from '../../utils/format'
import { safeBack } from '../../utils/nav'

const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'

const loanId = ref(null)
const loan = ref(null)
const repayments = ref([])
const loading = ref(false)
const gone = ref(false) // 已删除/不存在，避免重复拉取并自动返回

const isLend = computed(() => loan.value?.direction === 'LEND')
const L = computed(() => (isLend.value
  ? { remaining: '剩余待收', done: '已收金额', total: '借款总额', initial: '借出', repay: '收款',
      account: '收款钱包', due: '收款日期', addTitle: '添加收款' }
  : { remaining: '剩余待还', done: '已还金额', total: '借款总额', initial: '借入', repay: '还款',
      account: '还款账户', due: '还款日期', addTitle: '添加还款' }))

const remaining = computed(() => Number(loan.value?.remaining ?? 0))
const repaid = computed(() => Number(loan.value?.repaidAmount ?? 0))
const total = computed(() => Number(loan.value?.amount ?? 0))

onLoad((q) => {
  loanId.value = q && q.id ? Number(q.id) : null
})
onShow(load)

// 账户名映射（展示用，含全部账户）。
const accMap = ref({})
async function loadAccountNames() {
  try {
    const all = await listAccounts()
    accMap.value = Object.fromEntries(all.map((a) => [a.id, accountDisplayName(a)]))
  } catch (e) { /* ignore */ }
}
function acctName(id) {
  return id != null ? (accMap.value[id] || '账户') : '未记账户'
}

async function load() {
  if (loanId.value == null || gone.value) return
  loading.value = true
  try {
    await loadAccountNames()
    const r = await getLoan(loanId.value)
    loan.value = r.loan
    repayments.value = r.repayments || []
    uni.setNavigationBarTitle && uni.setNavigationBarTitle({ title: loan.value.counterparty })
  } catch (e) {
    if (e && e.code === 'HTTP_401') return
    // 记录已不存在（被删除/越权）：不停留在空白页，提示后自动返回。
    gone.value = true
    uni.showToast({ title: e.message || '加载失败', icon: 'none' })
    setTimeout(goBack, 500)
  } finally {
    loading.value = false
  }
}

function dateOf(iso) { return iso ? iso.slice(0, 10) : '' }

// 流水：初始出/入账 + 收款/还款，按日期倒序。
const entries = computed(() => {
  if (!loan.value) return []
  const list = []
  // 初始
  const sub = isLend.value
    ? `${acctName(loan.value.accountId)} → ${loan.value.counterparty}`
    : `${loan.value.counterparty} → ${acctName(loan.value.accountId)}`
  list.push({
    key: 'init', title: L.value.initial, sub,
    amount: isLend.value ? -total.value : total.value,
    date: dateOf(loan.value.occurredAt)
  })
  for (const r of repayments.value) {
    list.push({
      key: 'r' + r.id, id: r.id, title: L.value.repay, sub: acctName(r.accountId),
      amount: isLend.value ? Number(r.amount) : -Number(r.amount),
      date: dateOf(r.occurredAt), removable: true
    })
  }
  return list
})

// 点击流水条目 → 条目详情半弹窗（对齐竞品）。
const entrySheet = ref(false)
const activeEntry = ref(null)
const isInitEntry = computed(() => activeEntry.value && activeEntry.value.key === 'init')
const entryAmountLabel = computed(() =>
  isInitEntry.value ? '借款金额' : (isLend.value ? '收款金额' : '还款金额'))
const entryDateLabel = computed(() =>
  isInitEntry.value ? '借款日期' : (isLend.value ? '收款日期' : '还款日期'))
const entryTypeLabel = computed(() => activeEntry.value ? activeEntry.value.title : '')
const entryDueDate = computed(() =>
  isInitEntry.value && loan.value && loan.value.dueDate ? dateOf(loan.value.dueDate) : '')
function openEntry(e) {
  activeEntry.value = e
  entrySheet.value = true
}
function editFromEntry() {
  entrySheet.value = false
  openEdit()
}
function deleteFromEntry() {
  const e = activeEntry.value
  entrySheet.value = false
  if (!e) return
  if (e.key === 'init') confirmDeleteLoan()
  else confirmDeleteRepayment(e)
}

function goBack() {
  safeBack('/pages/accounts/accounts')
}

// ---------- 账户选择 ----------
const accounts = ref([])
async function loadSelectable() {
  // 借贷为用户级，账户可选本人全部账户（与账本无关）。
  try { accounts.value = await listAccounts() } catch (e) { accounts.value = [] }
}
const acctSheet = ref(false)
const acctName2 = computed(() => {
  const a = accounts.value.find((x) => x.id === rForm.value.accountId)
  return a ? accountDisplayName(a) : '选择账户'
})
function pickAccount(a) { rForm.value.accountId = a.id; acctSheet.value = false }

// ---------- 添加收款/还款 ----------
function todayStr() {
  const d = new Date()
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`
}
const rSheet = ref(false)
const submitting = ref(false)
const rForm = ref({ amount: '', accountId: null, date: todayStr(), note: '' })
async function openAddRepayment() {
  rForm.value = { amount: '', accountId: null, date: todayStr(), note: '' }
  await loadSelectable()
  if (accounts.value.length) rForm.value.accountId = accounts.value[0].id
  rSheet.value = true
}
// H5 内置日期选择器日列不随月份收缩，按当月最大天数收敛，避免非法日期（如 2/31）。
function clampDate(iso) {
  const [y, m, d] = String(iso).split('-').map(Number)
  const last = new Date(y, m, 0).getDate()
  const dd = Math.min(d, last)
  return `${y}-${String(m).padStart(2, '0')}-${String(dd).padStart(2, '0')}`
}
function onRDateChange(e) { rForm.value.date = clampDate(e.detail.value) }
async function submitRepayment() {
  if (!rForm.value.amount || Number(rForm.value.amount) <= 0) {
    uni.showToast({ title: '请输入正确金额', icon: 'none' })
    return
  }
  if (submitting.value) return
  submitting.value = true
  try {
    await addRepayment(loanId.value, {
      amount: rForm.value.amount,
      accountId: rForm.value.accountId,
      occurredAt: `${rForm.value.date}T12:00:00`,
      note: rForm.value.note.trim() || undefined
    })
    rSheet.value = false
    uni.showToast({ title: '已保存', icon: 'success' })
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

function confirmDeleteRepayment(entry) {
  uni.showModal({
    title: `删除${L.value.repay}`,
    content: '删除后会回补账户余额，确定删除？',
    confirmColor: '#e5484d',
    success: async (res) => {
      if (!res.confirm) return
      try {
        await deleteRepayment(loanId.value, entry.id)
        await load()
      } catch (e) {
        uni.showToast({ title: e.message || '删除失败', icon: 'none' })
      }
    }
  })
}

// ---------- 更多：修改 / 删除 ----------
function openMore() {
  uni.showActionSheet({
    itemList: ['修改', '删除'],
    success: ({ tapIndex }) => {
      if (tapIndex === 0) openEdit()
      else confirmDeleteLoan()
    }
  })
}
function confirmDeleteLoan() {
  uni.showModal({
    title: '删除记录',
    content: '删除后会同步回补账户余额（含收款/还款），确定删除？',
    confirmColor: '#e5484d',
    success: async (res) => {
      if (!res.confirm) return
      try {
        await deleteLoan(loanId.value)
        gone.value = true // 标记已删除：抑制 onShow 再次拉取导致的“记录不存在”空白页
        uni.showToast({ title: '已删除', icon: 'success' })
        setTimeout(goBack, 400)
      } catch (e) {
        uni.showToast({ title: e.message || '删除失败', icon: 'none' })
      }
    }
  })
}

// 编辑（对方/金额/账户/日期/到期/计入/备注）
const eSheet = ref(false)
const eForm = ref({})
async function openEdit() {
  await loadSelectable()
  eForm.value = {
    counterparty: loan.value.counterparty,
    amount: String(loan.value.amount),
    accountId: loan.value.accountId != null ? loan.value.accountId : (accounts.value[0] ? accounts.value[0].id : null),
    occurredDate: dateOf(loan.value.occurredAt) || todayStr(),
    dueDate: loan.value.dueDate ? dateOf(loan.value.dueDate) : '',
    includeInTotal: loan.value.includeInTotal !== false,
    note: loan.value.note || ''
  }
  eSheet.value = true
}
const eAcctSheet = ref(false)
const eAcctName = computed(() => {
  const a = accounts.value.find((x) => x.id === eForm.value.accountId)
  return a ? accountDisplayName(a) : '选择账户'
})
function ePickAccount(a) { eForm.value.accountId = a.id; eAcctSheet.value = false }
function onEOccurredChange(e) { eForm.value.occurredDate = clampDate(e.detail.value) }
function onEDueChange(e) { eForm.value.dueDate = clampDate(e.detail.value) }
function eClearDue() { eForm.value.dueDate = '' }
async function submitEdit() {
  const cp = (eForm.value.counterparty || '').trim()
  if (!cp) { uni.showToast({ title: '请输入对方姓名', icon: 'none' }); return }
  if (!eForm.value.amount || Number(eForm.value.amount) <= 0) {
    uni.showToast({ title: '请输入正确金额', icon: 'none' }); return
  }
  if (submitting.value) return
  submitting.value = true
  try {
    await updateLoan(loanId.value, {
      direction: loan.value.direction,
      counterparty: cp,
      amount: eForm.value.amount,
      accountId: eForm.value.accountId,
      occurredAt: `${eForm.value.occurredDate}T12:00:00`,
      dueDate: eForm.value.dueDate ? `${eForm.value.dueDate}T12:00:00` : undefined,
      includeInTotal: eForm.value.includeInTotal,
      note: eForm.value.note.trim() || undefined
    })
    eSheet.value = false
    uni.showToast({ title: '已保存', icon: 'success' })
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="page" :class="isLend ? 'lend' : 'borrow'">
    <!-- 头部 -->
    <view class="hero">
      <view class="hero-nav" :style="{ paddingTop: statusBarHeight }">
        <text class="hn-back" @click="goBack">‹</text>
        <text class="hn-title">{{ loan ? loan.counterparty : '借贷详情' }}</text>
        <text class="hn-more" @click="openMore">•••</text>
      </view>
      <view class="hero-body" v-if="loan">
        <text class="h-k">{{ L.remaining }}（元）</text>
        <text class="h-v">{{ formatAmount(remaining) }}</text>
        <view class="h-foot">
          <text>{{ L.done }}：{{ formatAmount(repaid) }}</text>
          <text>{{ L.total }}：{{ formatAmount(total) }}</text>
        </view>
      </view>
    </view>

    <!-- 流水 -->
    <view class="card" v-if="loan">
      <view v-for="e in entries" :key="e.key" class="tx" @click="openEntry(e)">
        <view class="tx-ic" :class="isLend ? 'lend' : 'borrow'">
          <AppIcon :name="e.key === 'init' ? (isLend ? 'transfer' : 'wallet') : 'yuan'" :size="38" color="#fff" />
        </view>
        <view class="tx-main">
          <text class="tx-title">{{ e.title }}</text>
          <text class="tx-sub">{{ e.sub }}</text>
        </view>
        <view class="tx-right">
          <text class="tx-amt" :class="e.amount >= 0 ? 'inc' : 'exp'">{{ e.amount >= 0 ? '+' : '-' }}{{ formatAmount(Math.abs(e.amount)) }}</text>
          <text class="tx-date">{{ e.date }}</text>
        </view>
      </view>
    </view>

    <view style="height:180rpx;"></view>

    <!-- 底部添加收款/还款 -->
    <view v-if="loan && !loan.settled" class="addbar" @click="openAddRepayment">
      <text class="add-plus">＋</text><text>{{ L.addTitle }}</text>
    </view>
    <view v-else-if="loan" class="addbar done"><text>已结清</text></view>

    <!-- 添加收款/还款 弹窗 -->
    <view v-if="rSheet" class="mask" @click="rSheet = false">
      <view class="sheet" @click.stop>
        <view class="form-head">
          <text class="fh-cancel" @click="rSheet = false">取消</text>
          <text class="fh-title">{{ L.addTitle }}</text>
          <text class="fh-save" @click="submitRepayment">保存</text>
        </view>
        <view class="form-body">
          <view class="frow">
            <text class="fk">{{ isLend ? '收款金额' : '还款金额' }}</text>
            <input v-model="rForm.amount" class="finput" type="digit" placeholder="0.00" />
          </view>
          <view class="frow" @click="acctSheet = true">
            <text class="fk">{{ L.account }}</text>
            <text class="fv" :class="{ ph: !rForm.accountId }">{{ acctName2 }} ›</text>
          </view>
          <picker mode="date" :value="rForm.date" @change="onRDateChange">
            <view class="frow"><text class="fk">{{ L.due }}</text><text class="fv">{{ rForm.date }} ›</text></view>
          </picker>
        </view>
        <textarea v-model="rForm.note" class="note" placeholder="备注" maxlength="200" />
      </view>
    </view>

    <!-- 收款钱包/还款账户 选择 -->
    <view v-if="acctSheet" class="mask mask-top" @click="acctSheet = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">选择{{ L.account }}</text>
        <view v-if="!accounts.length" class="sempty">该账本暂无可用账户</view>
        <view v-for="a in accounts" :key="a.id" class="sitem" @click="pickAccount(a)">
          <view class="si-ic"><AppIcon :name="accountTypeIcon(a.type)" :size="40" /></view>
          <text class="si-nm">{{ accountDisplayName(a) }}</text>
          <text class="radio" :class="{ on: rForm.accountId === a.id }"></text>
        </view>
      </view>
    </view>

    <!-- 编辑借贷 弹窗 -->
    <view v-if="eSheet" class="mask" @click="eSheet = false">
      <view class="sheet" @click.stop>
        <view class="form-head">
          <text class="fh-cancel" @click="eSheet = false">取消</text>
          <text class="fh-title">编辑{{ isLend ? '借出' : '借入' }}</text>
          <text class="fh-save" @click="submitEdit">保存</text>
        </view>
        <input v-model="eForm.counterparty" class="name-input" placeholder="对方姓名" maxlength="50" />
        <view class="form-body">
          <view class="frow"><text class="fk">借款金额</text><input v-model="eForm.amount" class="finput" type="digit" placeholder="0.00" /></view>
          <view class="frow" @click="eAcctSheet = true"><text class="fk">{{ isLend ? '借出账户' : '存入账户' }}</text><text class="fv" :class="{ ph: !eForm.accountId }">{{ eAcctName }} ›</text></view>
          <picker mode="date" :value="eForm.occurredDate" @change="onEOccurredChange">
            <view class="frow"><text class="fk">借款日期</text><text class="fv">{{ eForm.occurredDate }} ›</text></view>
          </picker>
          <picker mode="date" :value="eForm.dueDate || todayStr()" @change="onEDueChange">
            <view class="frow">
              <text class="fk">{{ L.due }}</text>
              <view class="due-right">
                <text class="fv" :class="{ ph: !eForm.dueDate }">{{ eForm.dueDate || '选填' }} ›</text>
                <text v-if="eForm.dueDate" class="due-clear" @click.stop="eClearDue">✕</text>
              </view>
            </view>
          </picker>
          <view class="frow">
            <text class="fk small">{{ isLend ? '借出待收金额是否计入总资产' : '借入待还金额是否计入总资产' }}</text>
            <switch :checked="eForm.includeInTotal" color="#12a150" @change="eForm.includeInTotal = $event.detail.value" />
          </view>
        </view>
        <textarea v-model="eForm.note" class="note" placeholder="备注" maxlength="200" />
      </view>
    </view>

    <!-- 编辑里的账户选择 -->
    <view v-if="eAcctSheet" class="mask mask-top" @click="eAcctSheet = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">选择{{ isLend ? '借出账户' : '存入账户' }}</text>
        <view v-for="a in accounts" :key="a.id" class="sitem" @click="ePickAccount(a)">
          <view class="si-ic"><AppIcon :name="accountTypeIcon(a.type)" :size="40" /></view>
          <text class="si-nm">{{ accountDisplayName(a) }}</text>
          <text class="radio" :class="{ on: eForm.accountId === a.id }"></text>
        </view>
      </view>
    </view>

    <!-- 流水条目详情（对齐竞品：借出/收款详情） -->
    <view v-if="entrySheet && activeEntry" class="mask" @click="entrySheet = false">
      <view class="sheet ed-sheet" @click.stop>
        <text class="sheet-title">{{ entryTypeLabel }}详情</text>
        <view class="ed-body">
          <view class="ed-row">
            <text class="ed-k">{{ entryAmountLabel }}</text>
            <text class="ed-v" :class="activeEntry.amount >= 0 ? 'inc' : 'exp'">{{ formatAmount(Math.abs(activeEntry.amount)) }}</text>
          </view>
          <view class="ed-row">
            <text class="ed-k">类型</text>
            <view class="ed-type" :class="isLend ? 'lend' : 'borrow'">
              <AppIcon :name="isInitEntry ? (isLend ? 'transfer' : 'wallet') : 'yuan'" :size="30" color="#fff" />
            </view>
          </view>
          <view class="ed-row">
            <text class="ed-k">账户</text>
            <text class="ed-v plain">{{ activeEntry.sub }}</text>
          </view>
          <view class="ed-row">
            <text class="ed-k">{{ entryDateLabel }}</text>
            <text class="ed-v plain">{{ activeEntry.date }}</text>
          </view>
          <view v-if="entryDueDate" class="ed-row">
            <text class="ed-k">{{ isLend ? '收款日期' : '还款日期' }}</text>
            <text class="ed-v plain">{{ entryDueDate }}</text>
          </view>
        </view>
        <view class="ed-actions">
          <text v-if="isInitEntry" class="ed-btn" @click="editFromEntry">修改</text>
          <text v-if="isInitEntry" class="ed-sep"></text>
          <text class="ed-btn del" @click="deleteFromEntry">删除</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; background: #eef0f2; }
.hero { color: #fff; padding-bottom: 44rpx; }
.page.borrow .hero { background: linear-gradient(150deg, #6b74c9, #4a54b3 72%); }
.page.lend .hero { background: linear-gradient(150deg, #7b86d6, #5b66bf 72%); }
.hero-nav { display: flex; align-items: center; justify-content: space-between; padding: 12rpx 24rpx 4rpx; }
.hn-back { font-size: 48rpx; line-height: 1; width: 60rpx; }
.hn-title { font-size: 32rpx; font-weight: 700; max-width: 60%; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.hn-more { font-size: 30rpx; width: 60rpx; text-align: right; }
.hero-body { padding: 18rpx 40rpx 0; text-align: center; }
.h-k { font-size: 24rpx; opacity: 0.9; }
.h-v { display: block; font-size: 68rpx; font-weight: 800; letter-spacing: -0.02em; margin: 8rpx 0 18rpx; }
.h-foot { display: flex; justify-content: space-between; font-size: 24rpx; opacity: 0.92; }
/* 流水卡 */
.card { margin: -24rpx 24rpx 0; background: #fff; border-radius: 20rpx; padding: 8rpx 26rpx; box-shadow: 0 8rpx 24rpx rgba(20,24,28,0.06); }
.tx { display: flex; align-items: center; gap: 20rpx; padding: 24rpx 0; border-bottom: 1rpx solid #f1f3f5; }
.tx:last-of-type { border-bottom: none; }
.tx-ic { width: 68rpx; height: 68rpx; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex: 0 0 auto; }
.tx-ic.borrow { background: #5b66bf; }
.tx-ic.lend { background: #b58a6a; }
.tx-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.tx-title { font-size: 30rpx; font-weight: 600; color: #16181c; }
.tx-sub { font-size: 22rpx; color: #9aa2ad; }
.tx-right { text-align: right; display: flex; flex-direction: column; gap: 6rpx; }
.tx-amt { font-size: 30rpx; font-weight: 800; }
.tx-amt.inc { color: #0f8a45; }
.tx-amt.exp { color: #e5563d; }
.tx-date { font-size: 22rpx; color: #9aa2ad; }
.hint { display: block; text-align: center; font-size: 22rpx; color: #bbb; padding: 16rpx 0; }
/* 底部 */
.addbar {
  position: fixed; left: 0; right: 0; bottom: 0; z-index: 20;
  display: flex; align-items: center; justify-content: center; gap: 10rpx;
  background: #fff; border-top: 1rpx solid #eef0f2;
  padding: 26rpx 0 calc(26rpx + env(safe-area-inset-bottom));
  font-size: 30rpx; font-weight: 700; color: #12a150;
  box-shadow: 0 -6rpx 20rpx rgba(20,24,28,0.05);
}
.addbar.done { color: #9aa2ad; font-weight: 600; }
.add-plus { font-size: 36rpx; line-height: 1; }
/* 弹窗 */
.mask { position: fixed; inset: 0; background: rgba(15,23,42,0.42); display: flex; align-items: flex-end; z-index: 60; }
.mask-top { z-index: 80; }
.sheet { width: 100%; background: #fff; border-radius: 28rpx 28rpx 0 0; padding: 28rpx 32rpx calc(32rpx + env(safe-area-inset-bottom)); box-sizing: border-box; }
.form-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12rpx; }
.fh-cancel { font-size: 28rpx; color: #9aa2ad; }
.fh-title { font-size: 30rpx; font-weight: 800; }
.fh-save { font-size: 28rpx; color: #12a150; font-weight: 700; }
.name-input { font-size: 34rpx; font-weight: 700; color: #16181c; padding: 12rpx 4rpx 20rpx; border-bottom: 1rpx solid #eceef1; }
.form-body { padding: 0; }
.frow { display: flex; align-items: center; justify-content: space-between; padding: 28rpx 4rpx; border-bottom: 1rpx solid #f1f3f5; }
.fk { font-size: 30rpx; color: #5b6470; }
.fk.small { font-size: 26rpx; flex: 1; }
.fv { font-size: 30rpx; color: #16181c; font-weight: 600; }
.fv.ph { color: #c2c7cd; font-weight: 400; }
.finput { flex: 1; text-align: right; font-size: 30rpx; color: #16181c; }
.due-right { display: flex; align-items: center; gap: 16rpx; }
.due-clear { font-size: 26rpx; color: #c0c4cc; }
.note { width: 100%; box-sizing: border-box; margin-top: 20rpx; background: #f6f7f9; border-radius: 16rpx; padding: 22rpx; font-size: 28rpx; min-height: 120rpx; }
.sheet-title { display: block; text-align: center; font-size: 30rpx; font-weight: 800; color: #16181c; margin-bottom: 16rpx; }
.sempty { text-align: center; color: #9aa2ad; font-size: 26rpx; padding: 40rpx 0; }
.sitem { display: flex; align-items: center; gap: 18rpx; padding: 24rpx 8rpx; border-top: 1rpx solid #f1f3f5; }
.si-ic { width: 64rpx; height: 64rpx; border-radius: 18rpx; background: #f4f5f7; display: flex; align-items: center; justify-content: center; }
.si-nm { flex: 1; font-size: 30rpx; color: #16181c; }
.radio { width: 36rpx; height: 36rpx; border-radius: 50%; border: 2rpx solid #cfd4da; box-sizing: border-box; }
.radio.on { border-color: #12a150; background: radial-gradient(circle at center, #12a150 40%, transparent 42%); }
/* 条目详情 */
.ed-body { padding: 4rpx 0 8rpx; }
.ed-row { display: flex; align-items: center; justify-content: space-between; padding: 26rpx 4rpx; border-bottom: 1rpx solid #f1f3f5; }
.ed-row:last-child { border-bottom: none; }
.ed-k { font-size: 28rpx; color: #8a94a6; }
.ed-v { font-size: 32rpx; font-weight: 700; color: #16181c; }
.ed-v.plain { font-weight: 600; font-size: 30rpx; }
.ed-v.inc { color: #0f8a45; }
.ed-v.exp { color: #e5563d; }
.ed-type { width: 56rpx; height: 56rpx; border-radius: 50%; display: flex; align-items: center; justify-content: center; }
.ed-type.borrow { background: #5b66bf; }
.ed-type.lend { background: #b58a6a; }
.ed-actions { display: flex; align-items: center; margin-top: 20rpx; border-top: 1rpx solid #eef0f2; }
.ed-btn { flex: 1; text-align: center; padding: 30rpx 0; font-size: 30rpx; font-weight: 700; color: #2b2f36; }
.ed-btn.del { color: #e5484d; }
.ed-sep { width: 1rpx; height: 44rpx; background: #eceef1; }
</style>
