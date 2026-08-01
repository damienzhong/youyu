<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import {
  listAccounts,
  createAccount,
  updateAccount,
  deleteAccount,
  setAccountVisibility,
  detachAccountFromLedger,
  listAccountLedgerLinks,
  transferAccountOwnership,
  accountTypeLabel,
  accountTypeIcon,
  composeAccountName,
  accountGroupOf,
  isCreditType,
  ACCOUNT_TYPES,
  ACCOUNT_GROUPS,
  BANKS,
  bankOf
} from '../../api/account'
import { listMembers } from '../../api/ledger'
import { adjustBalance } from '../../api/transaction'
import { useLedgerStore } from '../../stores/ledger'
import { useAuthStore } from '../../stores/auth'
import { formatAmount } from '../../utils/format'

const ledgerStore = useLedgerStore()
const authStore = useAuthStore()
const selfId = computed(() => authStore.user?.id ?? null)
const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'

const acc = ref(null) // 编辑时加载的账户实体
const links = ref([])

// ---------- 表单 ----------
function blankForm(type = 'CASH', name = '') {
  return {
    id: null, type, name, initialBalance: '', owed: '', creditLimit: '',
    billDay: null, repayDay: null, repayReminder: false,
    includeInTotal: true, _hidden: false, _note: null, _ownerId: null, _ledgerId: null,
    _issuingBank: '', _cardNo: ''
  }
}
const form = ref(blankForm())
const submitting = ref(false)
const showForm = ref(false) // 选完类型后展示表单主体（新建时先选类型）

const needsBank = computed(() => ['BANK_CARD', 'CREDIT_CARD'].includes(form.value.type))
const DAY_LABELS = Array.from({ length: 28 }, (_, i) => `每月 ${i + 1} 日`)
function onBillDayChange(e) { form.value.billDay = Number(e.detail.value) + 1 }
function onRepayDayChange(e) { form.value.repayDay = Number(e.detail.value) + 1 }
const isEditing = computed(() => form.value.id !== null)
const formIsCredit = computed(() => isCreditType(form.value.type))
const currentGroup = computed(() => accountGroupOf(form.value.type))
const balanceLabel = computed(() => {
  const g = currentGroup.value
  if (g === 'INVESTMENT') return '当前市值'
  if (g === 'PREPAID') return '当前余额'
  return '初始余额'
})
const iconBg = computed(() => {
  const g = currentGroup.value
  if (g === 'CREDIT') return '#fdece8'
  if (g === 'INVESTMENT') return '#f0ecfe'
  if (g === 'PREPAID') return '#e4f6f5'
  return '#e7f7ee'
})

onLoad(async (q) => {
  const editId = q && q.id ? Number(q.id) : null
  try {
    if (!ledgerStore.ledgers.length) await ledgerStore.load()
  } catch (e) { /* ignore */ }
  if (editId) {
    uni.setNavigationBarTitle && uni.setNavigationBarTitle({ title: '编辑账户' })
    try {
      const all = await listAccounts()
      acc.value = all.find((a) => a.id === editId) || null
    } catch (e) { acc.value = null }
    if (acc.value) {
      openEdit(acc.value)
      try { links.value = await listAccountLedgerLinks() } catch (e) { links.value = [] }
      buildPartList(editId)
    }
  } else {
    form.value = blankForm()
    if (q && q.type) pickType({ value: q.type, label: accountTypeLabel(q.type) })
    else typePickerOpen.value = true
  }
})

// ---------- 类型选择器 ----------
const typePickerOpen = ref(false)
const groupedTypes = computed(() =>
  ACCOUNT_GROUPS.map((g) => ({ ...g, types: ACCOUNT_TYPES.filter((t) => t.group === g.key) }))
)
function pickType(t) {
  typePickerOpen.value = false
  form.value = blankForm(t.value, t.label)
  showForm.value = true
}
function reopenTypePicker() { typePickerOpen.value = true }
function pickTypeForEdit(t) {
  if (isEditing.value) {
    typePickerOpen.value = false
    form.value.type = t.value
    showForm.value = true
  } else {
    pickType(t)
  }
}
function openEdit(a) {
  form.value = {
    id: a.id, type: a.type, name: a.name, initialBalance: '', owed: '',
    creditLimit: a.creditLimit != null ? String(a.creditLimit) : '',
    billDay: a.billDay ?? null, repayDay: a.repayDay ?? null, repayReminder: !!a.repayReminder,
    includeInTotal: a.includeInTotal, _hidden: a.hidden, _note: a.note,
    _ownerId: a.ownerId, _ledgerId: null,
    _issuingBank: a.issuingBank || '', _cardNo: a.cardNo || ''
  }
  showForm.value = true
}

// ---------- 发卡银行 ----------
const bankPickerOpen = ref(false)
const banks = BANKS
function openBankPicker() { bankPickerOpen.value = true }
function pickBank(b) { form.value._issuingBank = b.label; bankPickerOpen.value = false }
function clearBank() { form.value._issuingBank = ''; bankPickerOpen.value = false }

// ---------- 参与账本 / 可见性 / 转交 ----------
const isOwnAccount = computed(
  () => isEditing.value && (form.value._ownerId == null || form.value._ownerId === selfId.value)
)
const canManageSharing = computed(
  () => isOwnAccount.value && !ledgerStore.isAll && ledgerStore.current?.type === 'COLLABORATIVE'
)
const partList = ref([])
function buildPartList(accId) {
  const mine = links.value.filter((l) => l.accountId === accId)
  partList.value = (ledgerStore.ledgers || []).map((g) => {
    const link = mine.find((l) => l.ledgerId === g.id)
    return {
      id: g.id, name: g.name, type: g.type,
      participates: !!link,
      visibleToOthers: link ? link.visibleToOthers : true,
      showBalance: link ? link.showBalance : false
    }
  })
}
async function toggleParticipate(p, val) {
  const accId = form.value.id
  try {
    if (val) {
      await setAccountVisibility(accId, { ledgerId: p.id, visibleToOthers: p.visibleToOthers, showBalance: p.showBalance })
      p.participates = true
    } else {
      const r = await detachAccountFromLedger(accId, p.id)
      p.participates = false
      if (r && r.hasHistory) uni.showToast({ title: '已移出，历史流水保留', icon: 'none' })
    }
    links.value = await listAccountLedgerLinks()
  } catch (e) {
    p.participates = !val
    uni.showToast({ title: e.message || '操作失败', icon: 'none' })
  }
}
async function onPartVisChange(p, field, val) {
  p[field] = val
  try {
    await setAccountVisibility(form.value.id, { ledgerId: p.id, visibleToOthers: p.visibleToOthers, showBalance: p.showBalance })
    links.value = await listAccountLedgerLinks()
  } catch (e) {
    uni.showToast({ title: e.message || '设置失败', icon: 'none' })
  }
}
async function openTransferOwner() {
  let ms = []
  try { ms = (await listMembers(ledgerStore.current.id)).filter((m) => m.userId !== selfId.value) } catch (e) { ms = [] }
  if (!ms.length) { uni.showToast({ title: '暂无可转交的成员', icon: 'none' }); return }
  uni.showActionSheet({
    itemList: ms.map((m) => m.displayName || `成员${m.userId}`),
    success: ({ tapIndex }) => confirmTransferOwner(ms[tapIndex])
  })
}
function confirmTransferOwner(m) {
  uni.showModal({
    title: '转交账户',
    content: `确定把「${form.value.name}」转交给 ${m.displayName || '成员'}？转交后该账户归属对方。`,
    confirmColor: '#e5484d',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await transferAccountOwnership(form.value.id, m.userId)
        uni.showToast({ title: '已转交', icon: 'success' })
        setTimeout(() => uni.navigateBack(), 400)
      } catch (e) {
        uni.showToast({ title: e.message || '转交失败', icon: 'none' })
      }
    }
  })
}

// ---------- 余额调整 ----------
const adjustSheet = ref(false)
const adjustCurrent = ref('0.00')
const adjustOwed = ref(false)
const editingBalance = computed(() => (acc.value ? Number(acc.value.currentBalance) : 0))
const editingAmountDisplay = computed(() =>
  formIsCredit.value ? formatAmount(-editingBalance.value) : formatAmount(editingBalance.value)
)
function openAdjust() {
  const bal = editingBalance.value
  adjustOwed.value = formIsCredit.value
  adjustCurrent.value = adjustOwed.value ? formatAmount(-bal) : formatAmount(bal)
  adjustSheet.value = true
}
async function onAdjustConfirm(v) {
  adjustSheet.value = false
  const raw = (v || '').trim()
  if (raw === '') return
  const num = Number(raw)
  if (isNaN(num)) { uni.showToast({ title: '请输入正确金额', icon: 'none' }); return }
  const target = adjustOwed.value ? -Math.abs(num) : num
  try {
    await adjustBalance({ accountId: form.value.id, balance: String(target) }, form.value._ledgerId)
    uni.showToast({ title: '已校准余额', icon: 'success' })
    setTimeout(() => uni.navigateBack(), 400)
  } catch (e) {
    uni.showToast({ title: e.message || '调整失败', icon: 'none' })
  }
}

// ---------- 保存 / 删除 ----------
async function submit() {
  submitting.value = true
  try {
    const creditLimit = formIsCredit.value && form.value.creditLimit !== '' ? form.value.creditLimit : undefined
    const note = form.value._note ? form.value._note.trim() : undefined
    const issuingBank = needsBank.value ? (form.value._issuingBank || '') : ''
    const cardNo = needsBank.value && form.value._cardNo ? form.value._cardNo.trim() : ''
    const bank = { issuingBank, cardNo }
    // 名称由属性自动拼装：发卡行+类型+（卡号后四位），或直接类型名。
    const name = composeAccountName({ type: form.value.type, issuingBank, cardNo })
    const billing = formIsCredit.value
      ? { billDay: form.value.billDay, repayDay: form.value.repayDay, repayReminder: form.value.repayReminder }
      : {}
    if (isEditing.value) {
      await updateAccount(form.value.id, {
        name, type: form.value.type, includeInTotal: form.value.includeInTotal,
        hidden: form.value._hidden, note, creditLimit, ...billing, ...bank
      }, form.value._ledgerId)
    } else {
      let initialBalance
      if (formIsCredit.value) {
        initialBalance = form.value.owed === '' ? '0' : String(-Math.abs(Number(form.value.owed)))
      } else {
        initialBalance = form.value.initialBalance === '' ? '0' : form.value.initialBalance
      }
      await createAccount({
        name, type: form.value.type, initialBalance,
        includeInTotal: form.value.includeInTotal,
        hidden: form.value._hidden, note, creditLimit, ...billing, ...bank
      })
    }
    uni.showToast({ title: '已保存', icon: 'success' })
    setTimeout(() => uni.navigateBack(), 400)
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}
function confirmDelete() {
  uni.showModal({
    title: '删除账户',
    content: `确定删除「${form.value.name}」？有交易记录的账户无法删除。`,
    confirmColor: '#e5484d',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await deleteAccount(form.value.id, form.value._ledgerId)
        uni.showToast({ title: '已删除', icon: 'success' })
        setTimeout(() => uni.navigateBack(), 400)
      } catch (e) {
        uni.showToast({ title: e.message || '删除失败', icon: 'none' })
      }
    }
  })
}
function goBack() { uni.navigateBack() }
</script>

<template>
  <view class="page">
    <!-- 自定义头 -->
    <view class="nav" :style="{ paddingTop: statusBarHeight }">
      <text class="nav-cancel" @click="goBack">取消</text>
      <text class="nav-title">{{ isEditing ? '编辑账户' : '新建账户' }}</text>
      <text class="nav-save" @click="submit">保存</text>
    </view>

    <scroll-view scroll-y class="body" v-if="showForm">
      <!-- 顶部图标预览 -->
      <view class="hero">
        <view class="hero-ic" :style="{ background: iconBg }"><AppIcon :name="accountTypeIcon(form.type)" :size="56" /></view>
      </view>

      <!-- 基本信息 -->
      <view class="form-body">
        <view class="frow" @click="reopenTypePicker">
          <text class="fk">类型</text>
          <text class="fv">{{ accountTypeLabel(form.type) }} ›</text>
        </view>
        <template v-if="needsBank">
          <view class="frow" @click="openBankPicker">
            <text class="fk">发卡银行</text>
            <view v-if="form._issuingBank" class="bank-val">
              <text class="bank-badge" :style="{ background: (bankOf(form._issuingBank) || {}).color || '#8a94a6' }">{{ (bankOf(form._issuingBank) || {}).short || '银' }}</text>
              <text class="fv">{{ form._issuingBank }} ›</text>
            </view>
            <text v-else class="fv ph">选择银行 ›</text>
          </view>
          <view class="frow">
            <text class="fk">卡号</text>
            <input v-model="form._cardNo" class="finput" placeholder="选填，建议填后四位" maxlength="30" />
          </view>
        </template>

        <template v-if="!isEditing">
          <template v-if="formIsCredit">
            <view class="frow">
              <text class="fk">信用额度</text>
              <input v-model="form.creditLimit" class="finput" type="digit" placeholder="0.00" />
            </view>
            <view class="frow col">
              <view class="frow-top">
                <text class="fk">当前欠款</text>
                <input v-model="form.owed" class="finput" type="digit" placeholder="0.00" />
              </view>
              <text class="fhint">正数代表欠款，将计入总负债</text>
            </view>
          </template>
          <view v-else class="frow">
            <text class="fk">{{ balanceLabel }}</text>
            <input v-model="form.initialBalance" class="finput" type="digit" placeholder="0.00" />
          </view>
        </template>
        <template v-else>
          <view v-if="formIsCredit" class="frow">
            <text class="fk">信用额度</text>
            <input v-model="form.creditLimit" class="finput" type="digit" placeholder="可选" />
          </view>
          <view class="frow" @click="openAdjust">
            <text class="fk">{{ formIsCredit ? '当前欠款' : '账户余额' }}</text>
            <text class="fv">¥{{ editingAmountDisplay }} · 调整 ›</text>
          </view>
        </template>
      </view>

      <!-- 账单与还款（信贷）-->
      <view v-if="formIsCredit" class="form-body">
        <picker mode="selector" :range="DAY_LABELS" @change="onBillDayChange">
          <view class="frow">
            <text class="fk">账单日</text>
            <text class="fv" :class="{ ph: !form.billDay }">{{ form.billDay ? '每月 ' + form.billDay + ' 日 ›' : '未设置 ›' }}</text>
          </view>
        </picker>
        <picker mode="selector" :range="DAY_LABELS" @change="onRepayDayChange">
          <view class="frow">
            <text class="fk">还款日</text>
            <text class="fv" :class="{ ph: !form.repayDay }">{{ form.repayDay ? '每月 ' + form.repayDay + ' 日 ›' : '未设置 ›' }}</text>
          </view>
        </picker>
        <view class="frow col">
          <view class="frow-top">
            <text class="fk">还款提醒</text>
            <switch :checked="form.repayReminder" color="#12a150" @change="form.repayReminder = $event.detail.value" />
          </view>
          <text class="fhint">开启后还款日在记账日历高亮提醒</text>
        </view>
      </view>

      <!-- 更多设置 -->
      <view class="form-body">
        <view class="frow">
          <text class="fk">{{ formIsCredit ? '计入净资产（负债）' : '余额计入总资产' }}</text>
          <switch :checked="form.includeInTotal" color="#12a150" @change="form.includeInTotal = $event.detail.value" />
        </view>
        <view class="frow col">
          <view class="frow-top">
            <text class="fk">隐藏账户</text>
            <switch :checked="form._hidden" color="#12a150" @change="form._hidden = $event.detail.value" />
          </view>
          <text class="fhint">开启后，选账户弹窗不显示此账户</text>
        </view>
        <view class="frow">
          <text class="fk">备注</text>
          <input v-model="form._note" class="finput" placeholder="选填" maxlength="200" />
        </view>
      </view>

      <!-- 参与账本 -->
      <view v-if="isOwnAccount" class="form-body">
        <view class="fsec">参与账本</view>
        <view class="fhint" style="padding-bottom:12rpx;">账户是你的资产，可同时在多个账本使用。关掉某账本后，该账本记账时不再能选这张卡，历史流水与余额保留。</view>
        <block v-for="p in partList" :key="p.id">
          <view class="frow">
            <view class="pl-main">
              <text class="fk">{{ p.name }}</text>
              <text class="pl-tag" :class="p.type === 'COLLABORATIVE' ? 'collab' : 'personal'">{{ p.type === 'COLLABORATIVE' ? '协作' : '个人' }}</text>
            </view>
            <switch :checked="p.participates" color="#12a150" @change="toggleParticipate(p, $event.detail.value)" />
          </view>
          <template v-if="p.participates && p.type === 'COLLABORATIVE'">
            <view class="frow sub">
              <text class="fk sub">对成员可见</text>
              <switch :checked="p.visibleToOthers" color="#12a150" @change="onPartVisChange(p, 'visibleToOthers', $event.detail.value)" />
            </view>
            <view class="frow sub">
              <text class="fk sub">显示余额给成员</text>
              <switch :checked="p.showBalance" color="#12a150" @change="onPartVisChange(p, 'showBalance', $event.detail.value)" />
            </view>
          </template>
        </block>
      </view>

      <!-- 转交账户 -->
      <view v-if="canManageSharing" class="form-body">
        <view class="frow" @click="openTransferOwner">
          <text class="fk">转交账户</text>
          <text class="fv">转交给其他成员 ›</text>
        </view>
      </view>

      <button class="big-save" @click="submit">保存</button>
      <button v-if="isEditing" class="del" @click="confirmDelete">删除账户</button>
      <view style="height:40rpx;"></view>
    </scroll-view>

    <!-- 类型选择器 -->
    <view v-if="typePickerOpen" class="mask" @click="typePickerOpen = false">
      <view class="sheet type-sheet" @click.stop>
        <text class="sheet-title">选择账户类型</text>
        <scroll-view scroll-y class="type-scroll">
          <view v-for="g in groupedTypes" :key="g.key" class="tg">
            <text class="tg-title">{{ g.label }}</text>
            <view class="tg-grid">
              <view v-for="t in g.types" :key="t.value" class="tt" @click="pickTypeForEdit(t)">
                <view class="tt-ic"><AppIcon :name="accountTypeIcon(t.value)" :size="40" /></view>
                <text class="tt-label">{{ t.label }}</text>
              </view>
            </view>
          </view>
        </scroll-view>
      </view>
    </view>

    <!-- 发卡银行选择器 -->
    <view v-if="bankPickerOpen" class="mask mask-top" @click="bankPickerOpen = false">
      <view class="sheet bank-sheet" @click.stop>
        <text class="sheet-title">选择发卡银行</text>
        <scroll-view scroll-y class="bank-scroll">
          <view class="bank-grid">
            <view v-for="b in banks" :key="b.label" class="bk" @click="pickBank(b)">
              <text class="bk-badge" :style="{ background: b.color }">{{ b.short }}</text>
              <text class="bk-label">{{ b.label }}</text>
            </view>
          </view>
        </scroll-view>
        <view class="bank-clear" @click="clearBank">不设置 / 清除</view>
      </view>
    </view>

    <InputSheet
      :visible="adjustSheet"
      :title="adjustOwed ? '调整欠款' : '余额调整'"
      type="digit"
      :placeholder="adjustOwed ? '调整后的欠款' : '目标余额'"
      confirmText="校准"
      :tip="adjustOwed
        ? `当前欠款 ¥${adjustCurrent}，输入调整后的欠款金额，系统将自动补一笔差额流水。`
        : `当前余额 ¥${adjustCurrent}，输入调整后的目标余额，系统将自动补一笔差额流水。`"
      @update:visible="adjustSheet = $event"
      @confirm="onAdjustConfirm"
    />
  </view>
</template>

<style scoped>
.page { min-height: 100vh; background: #eef0f2; }
.nav {
  display: flex; align-items: center; justify-content: space-between;
  background: #fff; padding-left: 28rpx; padding-right: 28rpx; padding-bottom: 20rpx;
}
.nav-cancel { font-size: 30rpx; color: #9aa2ad; }
.nav-title { font-size: 32rpx; font-weight: 800; color: #16181c; }
.nav-save { font-size: 30rpx; color: #12a150; font-weight: 700; }
.body { height: calc(100vh - 100rpx); padding: 0 24rpx; box-sizing: border-box; }
.hero { display: flex; justify-content: center; padding: 24rpx 0 8rpx; }
.hero-ic {
  width: 120rpx; height: 120rpx; border-radius: 32rpx;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 8rpx 22rpx rgba(0, 0, 0, 0.06);
}
.form-body { background: #fff; border-radius: 18rpx; padding: 0 24rpx; margin-top: 16rpx; }
.frow { display: flex; align-items: center; justify-content: space-between; padding: 28rpx 0; border-top: 1rpx solid #eceef1; }
.frow:first-child { border-top: none; }
.fsec { font-size: 24rpx; color: #9aa2ad; padding: 18rpx 0 6rpx; }
.pl-main { display: flex; align-items: center; gap: 12rpx; }
.pl-tag { font-size: 20rpx; font-weight: 700; padding: 2rpx 12rpx; border-radius: 999rpx; }
.pl-tag.personal { background: #eef1f5; color: #5b6470; }
.pl-tag.collab { background: #e6f6ec; color: #0e8a44; }
.frow.sub { padding: 20rpx 0 20rpx 24rpx; }
.fk.sub { font-size: 26rpx; color: #9aa2ad; }
.fk { font-size: 30rpx; color: #5b6470; }
.fv { font-size: 30rpx; color: #16181c; font-weight: 600; }
.fv.ph { color: #c2c7cd; font-weight: 400; }
.finput { flex: 1; text-align: right; font-size: 30rpx; color: #16181c; }
.frow.col { flex-direction: column; align-items: stretch; gap: 8rpx; }
.frow-top { display: flex; align-items: center; justify-content: space-between; }
.fhint { font-size: 22rpx; color: #9aa2ad; }
.big-save {
  margin-top: 24rpx; background: linear-gradient(135deg, #18b85a, #0e8a44);
  color: #fff; border-radius: 44rpx; font-size: 30rpx; font-weight: 700;
  box-shadow: 0 12rpx 24rpx rgba(18, 161, 80, 0.35);
}
.del { margin-top: 20rpx; background: #fff; color: #e5484d; border-radius: 44rpx; font-size: 30rpx; border: 1rpx solid #f1d4d4; }
.mask { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.42); display: flex; align-items: flex-end; z-index: 50; }
.mask-top { z-index: 80; }
.sheet { width: 100%; background: #fff; border-radius: 28rpx 28rpx 0 0; padding: 32rpx 32rpx calc(32rpx + env(safe-area-inset-bottom)); box-sizing: border-box; }
.sheet-title { display: block; text-align: center; font-size: 30rpx; font-weight: 800; color: #16181c; margin-bottom: 20rpx; }
.type-sheet { max-height: 84vh; }
.type-scroll { max-height: 70vh; }
.tg { margin-bottom: 10rpx; }
.tg-title { font-size: 24rpx; font-weight: 700; color: #5b6470; padding: 12rpx 4rpx; }
.tg-grid { display: flex; flex-wrap: wrap; }
.tt { width: 20%; display: flex; flex-direction: column; align-items: center; gap: 10rpx; padding: 18rpx 0; }
.tt-ic { width: 84rpx; height: 84rpx; border-radius: 24rpx; background: #f4f5f7; display: flex; align-items: center; justify-content: center; }
.tt-label { font-size: 22rpx; color: #4b5563; }
.bank-val { display: flex; align-items: center; gap: 12rpx; }
.bank-badge { width: 40rpx; height: 40rpx; border-radius: 50%; color: #fff; font-size: 22rpx; font-weight: 700; text-align: center; line-height: 40rpx; }
.bank-sheet { max-height: 82vh; }
.bank-scroll { max-height: 62vh; }
.bank-grid { display: flex; flex-wrap: wrap; }
.bk { width: 25%; display: flex; flex-direction: column; align-items: center; gap: 10rpx; padding: 20rpx 0; }
.bk-badge { width: 84rpx; height: 84rpx; border-radius: 50%; color: #fff; font-size: 38rpx; font-weight: 800; text-align: center; line-height: 84rpx; }
.bk-label { font-size: 22rpx; color: #4b5563; }
.bank-clear { margin-top: 16rpx; text-align: center; padding: 24rpx; font-size: 28rpx; color: #9aa2ad; border-top: 1rpx solid #eceef1; }
</style>
