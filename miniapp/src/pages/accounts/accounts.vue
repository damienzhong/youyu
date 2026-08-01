<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  listAccounts,
  createAccount,
  updateAccount,
  deleteAccount,
  listRepayReminders,
  setAccountVisibility,
  detachAccountFromLedger,
  listAccountLedgerLinks,
  transferAccountOwnership,
  accountTypeLabel,
  accountTypeEmoji,
  accountTypeIcon,
  accountGroupLabel,
  accountGroupOf,
  isCreditType,
  ACCOUNT_TYPES,
  ACCOUNT_GROUPS,
  BANKS,
  bankOf
} from '../../api/account'
import { listLoans } from '../../api/loan'
import { listMembers } from '../../api/ledger'
import { adjustBalance } from '../../api/transaction'
import { useLedgerStore } from '../../stores/ledger'
import { useAuthStore } from '../../stores/auth'
import { formatAmount } from '../../utils/format'

const ledgerStore = useLedgerStore()
const authStore = useAuthStore()
const selfId = computed(() => authStore.user?.id ?? null)

const accounts = ref([])
const loading = ref(false)
const hideAmounts = ref(false)
const collapsed = ref({})
// 账户→账本参与关联（批量）：[{ accountId, ledgerId, visibleToOthers, showBalance }]
const links = ref([])

// 某账户参与的账本（连同名称/类型，从账本列表解析），用于卡片上的"参与账本"标签。
function ledgersOfAccount(accountId) {
  const all = ledgerStore.ledgers || []
  return links.value
    .filter((l) => l.accountId === accountId)
    .map((l) => all.find((g) => g.id === l.ledgerId))
    .filter(Boolean)
}

// 借贷汇总（仅具体账本显示；借贷为账本级台账）
const borrowOutstanding = ref('0.00')
const lendOutstanding = ref('0.00')
const reminders = ref([])
const showLoans = computed(() => !ledgerStore.isAll)
const hasLoan = computed(() => Number(borrowOutstanding.value) > 0 || Number(lendOutstanding.value) > 0)
function goLoans() {
  uni.navigateTo({ url: '/pages/loans/loans' })
}
// 转账为账户间动作，入口在资产页；进入记账页的转账模式。
function goTransfer() {
  const lid = ledgerStore.isAll ? null : ledgerStore.current?.id
  const q = lid ? `?type=transfer&ledgerId=${lid}` : '?type=transfer'
  uni.navigateTo({ url: `/pages/record/record${q}` })
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
    // 资产始终是「你自己的全部账户」，与当前选哪个账本无关（账本不持有资产）。
    accounts.value = await listAccounts()
    // 账本列表（用于把关联行解析成账本名/类型）与账户→账本关联，失败不阻断主列表。
    try {
      if (!ledgerStore.ledgers.length) await ledgerStore.load()
      links.value = await listAccountLedgerLinks()
    } catch (e) {
      links.value = []
    }
    try {
      reminders.value = await listRepayReminders()
    } catch (e) {
      /* 还款提醒加载失败不阻断资产页 */
    }
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

// ---------- 发卡银行选择器 ----------
const bankPickerOpen = ref(false)
const banks = BANKS
function openBankPicker() {
  bankPickerOpen.value = true
}
function pickBank(b) {
  form.value._issuingBank = b.label
  bankPickerOpen.value = false
}
function clearBank() {
  form.value._issuingBank = ''
  bankPickerOpen.value = false
}

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
function blankForm(type = 'CASH', name = '') {
  return {
    id: null, type, name, initialBalance: '', owed: '', creditLimit: '',
    billDay: null, repayDay: null, repayReminder: false,
    includeInTotal: true, _hidden: false, _note: null, _ledgerId: null,
    _issuingBank: '', _cardNo: ''
  }
}
const form = ref(blankForm())
// 仅储蓄卡 / 信用卡展示发卡银行与卡号
const needsBank = computed(() => ['BANK_CARD', 'CREDIT_CARD'].includes(form.value.type))
// 账单日/还款日候选（1-28，规避大小月边界）
const DAY_LABELS = Array.from({ length: 28 }, (_, i) => `每月 ${i + 1} 日`)
function onBillDayChange(e) { form.value.billDay = Number(e.detail.value) + 1 }
function onRepayDayChange(e) { form.value.repayDay = Number(e.detail.value) + 1 }
const isEditing = computed(() => form.value.id !== null)
const formIsCredit = computed(() => isCreditType(form.value.type))
const currentGroup = computed(() => accountGroupOf(form.value.type))
// 余额字段标题随分组变化
const balanceLabel = computed(() => {
  const g = currentGroup.value
  if (g === 'INVESTMENT') return '当前市值'
  if (g === 'PREPAID') return '当前余额'
  return '初始余额'
})
// 顶部图标底色随分组变化
const iconBg = computed(() => {
  const g = currentGroup.value
  if (g === 'CREDIT') return '#fdece8'
  if (g === 'INVESTMENT') return '#f0ecfe'
  if (g === 'PREPAID') return '#e4f6f5'
  return '#e7f7ee'
})

function openCreate() {
  // 先重置为干净的新建态，避免残留上一次编辑的账户信息（否则 isEditing 仍为 true）。
  form.value = blankForm()
  // 先选类型，再填详情（对齐竞品）
  typePickerOpen.value = true
}
function pickType(t) {
  typePickerOpen.value = false
  form.value = blankForm(t.value, t.label)
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
    id: acc.id, type: acc.type, name: acc.name, initialBalance: '', owed: '',
    creditLimit: acc.creditLimit != null ? String(acc.creditLimit) : '',
    billDay: acc.billDay ?? null, repayDay: acc.repayDay ?? null, repayReminder: !!acc.repayReminder,
    includeInTotal: acc.includeInTotal, _hidden: acc.hidden, _note: acc.note,
    _ownerId: acc.ownerId, _ledgerId: null,
    _issuingBank: acc.issuingBank || '', _cardNo: acc.cardNo || ''
  }
  showForm.value = true
  buildPartList(acc.id)
}

// ---------- 参与账本：账户在各账本的参与 / 可见性 / 转交 ----------
// 仅本人账户可管理参与关系（后端以 owner 为边界）。
const isOwnAccount = computed(
  () => isEditing.value && (form.value._ownerId == null || form.value._ownerId === selfId.value)
)
// 转交仅在当前为协作账本、且账户归属自己时提供（需要成员上下文）。
const canManageSharing = computed(
  () =>
    isOwnAccount.value &&
    !ledgerStore.isAll &&
    ledgerStore.current?.type === 'COLLABORATIVE'
)

// 编辑态下，账户在全部账本的参与状态（含协作可见性标志），从批量关联解析。
const partList = ref([])
function buildPartList(accId) {
  const mine = links.value.filter((l) => l.accountId === accId)
  partList.value = (ledgerStore.ledgers || []).map((g) => {
    const link = mine.find((l) => l.ledgerId === g.id)
    return {
      id: g.id,
      name: g.name,
      type: g.type,
      participates: !!link,
      visibleToOthers: link ? link.visibleToOthers : true,
      // 隐私优先：默认不向成员显示余额
      showBalance: link ? link.showBalance : false
    }
  })
}

async function toggleParticipate(p, val) {
  const accId = form.value.id
  try {
    if (val) {
      await setAccountVisibility(accId, {
        ledgerId: p.id, visibleToOthers: p.visibleToOthers, showBalance: p.showBalance
      })
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
    await setAccountVisibility(form.value.id, {
      ledgerId: p.id, visibleToOthers: p.visibleToOthers, showBalance: p.showBalance
    })
    links.value = await listAccountLedgerLinks()
  } catch (e) {
    uni.showToast({ title: e.message || '设置失败', icon: 'none' })
  }
}

async function openTransferOwner() {
  let ms = []
  try {
    ms = (await listMembers(ledgerStore.current.id)).filter((m) => m.userId !== selfId.value)
  } catch (e) {
    ms = []
  }
  if (!ms.length) {
    uni.showToast({ title: '暂无可转交的成员', icon: 'none' })
    return
  }
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
        showForm.value = false
        uni.showToast({ title: '已转交', icon: 'success' })
        await load()
      } catch (e) {
        uni.showToast({ title: e.message || '转交失败', icon: 'none' })
      }
    }
  })
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
    const note = form.value._note ? form.value._note.trim() : undefined
    // 发卡行/卡号仅储蓄卡/信用卡有意义；空串用于清除（后端置 null）。
    const bank = {
      issuingBank: needsBank.value ? (form.value._issuingBank || '') : '',
      cardNo: needsBank.value && form.value._cardNo ? form.value._cardNo.trim() : ''
    }
    // 信用卡账单/还款字段（仅信贷账户传递）
    const billing = formIsCredit.value
      ? { billDay: form.value.billDay, repayDay: form.value.repayDay, repayReminder: form.value.repayReminder }
      : {}
    if (isEditing.value) {
      await updateAccount(form.value.id, {
        name, type: form.value.type, includeInTotal: form.value.includeInTotal,
        hidden: form.value._hidden, note, creditLimit, ...billing, ...bank
      }, form.value._ledgerId)
    } else {
      // 信贷账户：当前欠款以负余额入账（欠款为正 → 初始余额为负），其余按余额/市值直填。
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
    showForm.value = false
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

// ---------- 余额调整 ----------
const adjustSheet = ref(false)
const adjustCurrent = ref('0.00')
// true=按“欠款”正数输入（信贷账户），false=按“目标余额”输入（其它账户）
const adjustOwed = ref(false)

// 正在编辑的账户实体与其当前余额/欠款（用于基本信息里直接展示与调整）。
const editingAccount = computed(() => accounts.value.find((a) => a.id === form.value.id) || null)
const editingBalance = computed(() =>
  editingAccount.value ? Number(editingAccount.value.currentBalance) : 0
)
// 信贷账户以“欠款”正数呈现（= 负余额取正）。
const editingAmountDisplay = computed(() =>
  formIsCredit.value ? formatAmount(-editingBalance.value) : formatAmount(editingBalance.value)
)

// 账户页已从底部 tab 移出、改为从首页「资产」push 进入，底部不再有 tab 栏，
// 故只需在弹层打开时隐藏右下角 FAB，避免遮挡弹层内容。
const anySheetOpen = computed(
  () => typePickerOpen.value || showForm.value || adjustSheet.value || bankPickerOpen.value
)
function openAdjust() {
  const bal = editingBalance.value
  adjustOwed.value = formIsCredit.value
  // 信贷按“欠款”正数呈现当前值；其它按余额。
  adjustCurrent.value = adjustOwed.value ? formatAmount(-bal) : formatAmount(bal)
  adjustSheet.value = true
}
async function onAdjustConfirm(v) {
  adjustSheet.value = false
  const raw = (v || '').trim()
  if (raw === '') return
  const num = Number(raw)
  if (isNaN(num)) {
    uni.showToast({ title: '请输入正确金额', icon: 'none' })
    return
  }
  // 信贷：输入的是欠款（正数）→ 目标余额取负；其它：输入即目标余额。
  const target = adjustOwed.value ? -Math.abs(num) : num
  try {
    await adjustBalance({ accountId: form.value.id, balance: String(target) }, form.value._ledgerId)
    showForm.value = false
    uni.showToast({ title: '已校准余额', icon: 'success' })
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '调整失败', icon: 'none' })
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

    <!-- 作用域说明：账户独立于账本、被所有账本共用 -->
    <view class="scope-note">
      <text class="sn-ic">💡</text>
      <text class="sn-t">这里是你的全部账户，被所有账本共用；余额与选哪个账本无关。</text>
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

    <!-- 信用卡还款提醒 -->
    <view v-if="reminders.length" class="repay">
      <view class="repay-head"><text class="rp-title">信用卡还款</text></view>
      <view v-for="r in reminders" :key="r.accountId" class="repay-row">
        <view class="rp-ic"><AppIcon name="card" :size="34" color="#e5563d" /></view>
        <view class="rp-main">
          <text class="rp-name">{{ r.name }}</text>
          <text class="rp-sub">每月 {{ r.repayDay }} 日还款</text>
        </view>
        <view class="rp-right">
          <text class="rp-days" :class="{ soon: r.daysUntil <= 3 }">{{ r.daysUntil === 0 ? '今天' : r.daysUntil + ' 天后' }}</text>
          <text class="rp-owed">待还 {{ money(r.owed) }}</text>
        </view>
      </view>
    </view>

    <!-- 账户转账入口（账户间动作，脱离账本） -->
    <view v-if="accounts.length > 1" class="xfer-row" @click="goTransfer">
      <view class="xfer-ic"><AppIcon name="transfer" :size="38" /></view>
      <text class="xfer-t">账户转账</text>
      <text class="xfer-caret">›</text>
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
          <view class="acc-ic"><AppIcon :name="accountTypeIcon(a.type)" :size="42" /></view>
          <view class="acc-main">
            <text class="acc-name">{{ a.name }}<text v-if="!a.includeInTotal" class="acc-flag"> · 不计入</text></text>
            <text v-if="availableOf(a) != null" class="acc-sub">可用 {{ money(availableOf(a)) }}</text>
            <text v-else-if="!ledgersOfAccount(a.id).length" class="acc-sub">{{ accountTypeLabel(a.type) }}</text>
            <view v-if="ledgersOfAccount(a.id).length" class="acc-chips">
              <text
                v-for="lg in ledgersOfAccount(a.id)"
                :key="lg.id"
                class="acc-chip"
                :class="lg.type === 'COLLABORATIVE' ? 'collab' : 'personal'"
              >{{ lg.name }}</text>
            </view>
          </view>
          <text class="acc-bal" :class="{ neg: Number(a.currentBalance) < 0 }">{{ money(a.currentBalance) }}</text>
        </view>
      </view>
    </view>

    <view style="height:210rpx;"></view>
    <view v-if="!anySheetOpen" class="fab" @click="openCreate">＋</view>

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

    <!-- 发卡银行选择器（层级高于编辑表单，避免被其遮住） -->
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

    <!-- 新建/编辑表单（按类型自适应） -->
    <view v-if="showForm" class="mask" @click="showForm = false">
      <view class="sheet form-sheet" @click.stop>
        <view class="form-head">
          <text class="fh-cancel" @click="showForm = false">取消</text>
          <text class="fh-title">{{ isEditing ? '编辑账户' : '新建账户' }}</text>
          <text class="fh-save" @click="submit">保存</text>
        </view>

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
          <view class="frow">
            <text class="fk">名称</text>
            <input v-model="form.name" class="finput" placeholder="账户名称" maxlength="50" />
          </view>

          <!-- 发卡银行 / 卡号（储蓄卡·信用卡）——紧随名称 -->
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

          <!-- 新建：信贷=额度+当前欠款；其它=余额/市值 -->
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
          <!-- 编辑：信贷可改额度；金额（欠款/余额）在此直接可调 -->
          <template v-else>
            <view v-if="formIsCredit" class="frow">
              <text class="fk">信用额度</text>
              <input v-model="form.creditLimit" class="finput" type="digit" placeholder="可选" />
            </view>
            <view class="frow" @click="openAdjust">
              <text class="fk">{{ formIsCredit ? '当前欠款' : '当前余额' }}</text>
              <text class="fv">¥{{ editingAmountDisplay }} · 调整 ›</text>
            </view>
          </template>
        </view>

        <!-- 账单与还款（信贷账户专属） -->
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
            <text class="fk">{{ formIsCredit ? '计入净资产（负债）' : '计入净资产' }}</text>
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

        <!-- 参与账本：账户在各账本的参与与协作可见性（仅本人账户可管理） -->
        <view v-if="isOwnAccount" class="form-body">
          <view class="fsec">参与账本</view>
          <view class="fhint" style="padding-bottom:12rpx;">账户是你的资产，可同时在多个账本使用。关掉某账本后，该账本记账时不再能选这张卡，历史流水与余额保留。</view>
          <block v-for="p in partList" :key="p.id">
            <view class="frow">
              <view class="pl-main">
                <text class="fk">{{ p.name }}</text>
                <text class="pl-tag" :class="p.type === 'COLLABORATIVE' ? 'collab' : 'personal'">
                  {{ p.type === 'COLLABORATIVE' ? '协作' : '个人' }}
                </text>
              </view>
              <switch :checked="p.participates" color="#12a150"
                @change="toggleParticipate(p, $event.detail.value)" />
            </view>
            <!-- 协作账本且已参与：附可见性子开关 -->
            <template v-if="p.participates && p.type === 'COLLABORATIVE'">
              <view class="frow sub">
                <text class="fk sub">对成员可见</text>
                <switch :checked="p.visibleToOthers" color="#12a150"
                  @change="onPartVisChange(p, 'visibleToOthers', $event.detail.value)" />
              </view>
              <view class="frow sub">
                <text class="fk sub">显示余额给成员</text>
                <switch :checked="p.showBalance" color="#12a150"
                  @change="onPartVisChange(p, 'showBalance', $event.detail.value)" />
              </view>
            </template>
          </block>
        </view>

        <!-- 转交账户（协作账本内成员之间） -->
        <view v-if="canManageSharing" class="form-body">
          <view class="frow" @click="openTransferOwner">
            <text class="fk">转交账户</text>
            <text class="fv">转交给其他成员 ›</text>
          </view>
        </view>

        <button class="big-save" @click="submit">保存</button>
        <button v-if="isEditing" class="del" @click="confirmDelete">删除账户</button>
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

    <TabBar active="assets" />
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
.scope-note {
  display: flex;
  align-items: flex-start;
  gap: 12rpx;
  background: #e6f6ec;
  border-radius: 16rpx;
  padding: 18rpx 22rpx;
  margin-bottom: 24rpx;
}
.sn-ic {
  font-size: 26rpx;
  line-height: 1.5;
}
.sn-t {
  flex: 1;
  font-size: 24rpx;
  color: #0e8a44;
  font-weight: 600;
  line-height: 1.5;
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
.repay {
  background: #fff;
  border-radius: 22rpx;
  padding: 8rpx 28rpx 12rpx;
  margin-bottom: 24rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.repay-head {
  padding: 20rpx 0 12rpx;
}
.rp-title {
  font-size: 26rpx;
  font-weight: 800;
  color: #16181c;
}
.repay-row {
  display: flex;
  align-items: center;
  gap: 18rpx;
  padding: 20rpx 0;
  border-top: 1rpx solid #f1f3f5;
}
.rp-ic {
  width: 64rpx;
  height: 64rpx;
  border-radius: 18rpx;
  background: #fdece8;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}
.rp-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.rp-name {
  font-size: 28rpx;
  font-weight: 600;
  color: #16181c;
}
.rp-sub {
  font-size: 22rpx;
  color: #9aa2ad;
}
.rp-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6rpx;
}
.rp-days {
  font-size: 26rpx;
  font-weight: 700;
  color: #5b6470;
}
.rp-days.soon {
  color: #e5563d;
}
.rp-owed {
  font-size: 22rpx;
  color: #9aa2ad;
}
.xfer-row {
  display: flex;
  align-items: center;
  background: #fff;
  border-radius: 20rpx;
  padding: 24rpx 28rpx;
  margin: 0 24rpx 20rpx;
}
.xfer-ic {
  margin-right: 16rpx;
  flex: 0 0 auto;
}
.xfer-t {
  flex: 1;
  font-size: 28rpx;
  color: #2b2f36;
}
.xfer-caret {
  color: #c0c4cc;
  font-size: 34rpx;
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
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
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
.acc-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8rpx;
}
.acc-chip {
  font-size: 20rpx;
  font-weight: 700;
  padding: 2rpx 12rpx;
  border-radius: 999rpx;
}
.acc-chip.personal {
  background: #eef1f5;
  color: #5b6470;
}
.acc-chip.collab {
  background: #e6f6ec;
  color: #0e8a44;
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
  bottom: calc(180rpx + env(safe-area-inset-bottom));
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
/* 叠在编辑表单之上的二级弹层（如发卡银行选择器） */
.mask-top {
  z-index: 80;
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
  border-radius: 24rpx;
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
}
.tt-label {
  font-size: 22rpx;
  color: #4b5563;
}
/* 发卡银行：表单内展示 + 选择器 */
.bank-val {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.bank-badge {
  width: 40rpx;
  height: 40rpx;
  border-radius: 50%;
  color: #fff;
  font-size: 22rpx;
  font-weight: 700;
  text-align: center;
  line-height: 40rpx;
}
.bank-sheet {
  max-height: 82vh;
}
.bank-scroll {
  max-height: 62vh;
}
.bank-grid {
  display: flex;
  flex-wrap: wrap;
}
.bk {
  width: 25%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10rpx;
  padding: 20rpx 0;
}
.bk-badge {
  width: 84rpx;
  height: 84rpx;
  border-radius: 50%;
  color: #fff;
  font-size: 38rpx;
  font-weight: 800;
  text-align: center;
  line-height: 84rpx;
}
.bk-label {
  font-size: 22rpx;
  color: #4b5563;
}
.bank-clear {
  margin-top: 16rpx;
  text-align: center;
  padding: 24rpx;
  font-size: 28rpx;
  color: #9aa2ad;
  border-top: 1rpx solid #eceef1;
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
.fsec {
  font-size: 24rpx;
  color: #9aa2ad;
  padding: 18rpx 0 6rpx;
}
.pl-main {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.pl-tag {
  font-size: 20rpx;
  font-weight: 700;
  padding: 2rpx 12rpx;
  border-radius: 999rpx;
}
.pl-tag.personal {
  background: #eef1f5;
  color: #5b6470;
}
.pl-tag.collab {
  background: #e6f6ec;
  color: #0e8a44;
}
.frow.sub {
  padding: 20rpx 0 20rpx 24rpx;
}
.fk.sub {
  font-size: 26rpx;
  color: #9aa2ad;
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
.fv.ph {
  color: #c2c7cd;
  font-weight: 400;
}
.finput {
  flex: 1;
  text-align: right;
  font-size: 30rpx;
  color: #16181c;
}
.del {
  margin-top: 20rpx;
  background: #fff;
  color: #e5484d;
  border-radius: 44rpx;
  font-size: 30rpx;
  border: 1rpx solid #f1d4d4;
}
/* 新建/编辑：自适应表单 */
.form-sheet {
  max-height: 90vh;
  overflow-y: auto;
}
.hero {
  display: flex;
  justify-content: center;
  padding: 8rpx 0 16rpx;
}
.hero-ic {
  width: 120rpx;
  height: 120rpx;
  border-radius: 32rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 60rpx;
  box-shadow: 0 8rpx 22rpx rgba(0, 0, 0, 0.06);
}
.frow.col {
  flex-direction: column;
  align-items: stretch;
  gap: 8rpx;
}
.frow-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.fhint {
  font-size: 22rpx;
  color: #9aa2ad;
}
.big-save {
  margin-top: 24rpx;
  background: linear-gradient(135deg, #18b85a, #0e8a44);
  color: #fff;
  border-radius: 44rpx;
  font-size: 30rpx;
  font-weight: 700;
  box-shadow: 0 12rpx 24rpx rgba(18, 161, 80, 0.35);
}
</style>
