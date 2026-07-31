<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  listAccounts,
  createAccount,
  updateAccount,
  deleteAccount,
  listRepayReminders,
  getAccountVisibility,
  setAccountVisibility,
  transferAccountOwnership,
  accountTypeLabel,
  accountTypeEmoji,
  accountGroupLabel,
  accountGroupOf,
  isCreditType,
  ACCOUNT_TYPES,
  ACCOUNT_GROUPS
} from '../../api/account'
import { listAllAccounts } from '../../api/aggregate'
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
    accounts.value = ledgerStore.isAll ? await listAllAccounts() : await listAccounts()
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
  id: null, type: 'CASH', name: '', initialBalance: '', owed: '', creditLimit: '',
  billDay: null, repayDay: null, repayReminder: false,
  includeInTotal: true, _hidden: false, _note: null, _ledgerId: null
})
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
  // 先选类型，再填详情（对齐竞品）
  typePickerOpen.value = true
}
function pickType(t) {
  typePickerOpen.value = false
  form.value = {
    id: null, type: t.value, name: t.label, initialBalance: '', owed: '', creditLimit: '',
    billDay: null, repayDay: null, repayReminder: false,
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
async function openEdit(acc) {
  form.value = {
    id: acc.id, type: acc.type, name: acc.name, initialBalance: '', owed: '',
    creditLimit: acc.creditLimit != null ? String(acc.creditLimit) : '',
    billDay: acc.billDay ?? null, repayDay: acc.repayDay ?? null, repayReminder: !!acc.repayReminder,
    includeInTotal: acc.includeInTotal, _hidden: acc.hidden, _note: acc.note,
    _ownerId: acc.ownerId, _ledgerId: null
  }
  showForm.value = true
  // 协作账本内、且账户归属自己时，加载其在本账本的可见性设置。
  vis.value = { participates: false, visibleToOthers: true, showBalance: true }
  if (canManageSharing.value) {
    try {
      vis.value = await getAccountVisibility(acc.id)
    } catch (e) {
      /* 读取失败用默认 */
    }
  }
}

// ---------- 协作账本：账户共享可见性 / 转交 ----------
const vis = ref({ participates: false, visibleToOthers: true, showBalance: true })
const canManageSharing = computed(
  () =>
    isEditing.value &&
    !ledgerStore.isAll &&
    ledgerStore.current?.type === 'COLLABORATIVE' &&
    form.value._ownerId != null &&
    form.value._ownerId === selfId.value
)

async function onVisChange(field, val) {
  vis.value[field] = val
  try {
    await setAccountVisibility(form.value.id, {
      visibleToOthers: vis.value.visibleToOthers,
      showBalance: vis.value.showBalance
    })
    vis.value.participates = true
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
    // 信用卡账单/还款字段（仅信贷账户传递）
    const billing = formIsCredit.value
      ? { billDay: form.value.billDay, repayDay: form.value.repayDay, repayReminder: form.value.repayReminder }
      : {}
    if (isEditing.value) {
      await updateAccount(form.value.id, {
        name, type: form.value.type, includeInTotal: form.value.includeInTotal,
        hidden: form.value._hidden, note, creditLimit, ...billing
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
        hidden: form.value._hidden, note, creditLimit, ...billing
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

// 账户页已从底部 tab 移出、改为从首页「资产」push 进入，底部不再有 tab 栏，
// 故只需在弹层打开时隐藏右下角 FAB，避免遮挡弹层内容。
const anySheetOpen = computed(
  () => typePickerOpen.value || showForm.value || adjustSheet.value
)
function openAdjust() {
  const acc = accounts.value.find((a) => a.id === form.value.id)
  adjustCurrent.value = acc ? String(acc.currentBalance) : '0.00'
  adjustSheet.value = true
}
async function onAdjustConfirm(v) {
  adjustSheet.value = false
  const raw = (v || '').trim()
  if (raw === '') return
  const target = Number(raw)
  if (isNaN(target)) {
    uni.showToast({ title: '请输入正确金额', icon: 'none' })
    return
  }
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
        <text class="rp-ic">💳</text>
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
      <text class="xfer-ic">🔁</text>
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
                <text class="tt-ic">{{ t.emoji }}</text>
                <text class="tt-label">{{ t.label }}</text>
              </view>
            </view>
          </view>
        </scroll-view>
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
          <view class="hero-ic" :style="{ background: iconBg }">{{ accountTypeEmoji(form.type) }}</view>
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
          <!-- 编辑：信贷仍可改额度 -->
          <view v-else-if="formIsCredit" class="frow">
            <text class="fk">信用额度</text>
            <input v-model="form.creditLimit" class="finput" type="digit" placeholder="可选" />
          </view>
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
          <view v-if="isEditing" class="frow" @click="openAdjust">
            <text class="fk">余额调整</text>
            <text class="fv">校准到目标余额 ›</text>
          </view>
        </view>

        <!-- 协作账本：账户共享设置（仅账户 owner 可见） -->
        <view v-if="canManageSharing" class="form-body">
          <view class="fsec">共享设置（本账本）</view>
          <view class="frow">
            <text class="fk">对成员可见</text>
            <switch :checked="vis.visibleToOthers" color="#12a150"
              @change="onVisChange('visibleToOthers', $event.detail.value)" />
          </view>
          <view class="frow">
            <text class="fk">显示余额给成员</text>
            <switch :checked="vis.showBalance" color="#12a150"
              @change="onVisChange('showBalance', $event.detail.value)" />
          </view>
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
      title="余额调整"
      type="digit"
      placeholder="目标余额"
      confirmText="校准"
      :tip="`当前余额 ¥${adjustCurrent}，输入调整后的目标余额，系统将自动补一笔差额流水。`"
      @update:visible="adjustSheet = $event"
      @confirm="onAdjustConfirm"
    />
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
  text-align: center;
  line-height: 64rpx;
  font-size: 32rpx;
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
  font-size: 34rpx;
  margin-right: 16rpx;
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
.fsec {
  font-size: 24rpx;
  color: #9aa2ad;
  padding: 18rpx 0 6rpx;
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
