<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import {
  listSelectableAccounts,
  getDefaultAccount,
  transferBetweenAccounts,
  accountTypeEmoji,
  accountTypeLabel
} from '../../api/account'
import { listCategories } from '../../api/category'
import { createTransaction, getTransaction, updateTransaction } from '../../api/transaction'
import { createLoan } from '../../api/loan'
import { listMembers } from '../../api/ledger'
import { listTemplates, createTemplate, deleteTemplate } from '../../api/template'
import { listProjects, createProject } from '../../api/project'
import { listMerchants, createMerchant } from '../../api/merchant'
import { listTags, createTag } from '../../api/tag'
import { useLedgerStore } from '../../stores/ledger'
import { useAuthStore } from '../../stores/auth'
import { categoryEmoji, formatAmount } from '../../utils/format'

const ledgerStore = useLedgerStore()
const authStore = useAuthStore()

const TYPES = [
  { value: 'expense', label: '支出' },
  { value: 'income', label: '收入' },
  { value: 'transfer', label: '转账' },
  { value: 'loan', label: '借贷' }
]
const type = ref('expense')
const isTransfer = computed(() => type.value === 'transfer')
const isLoan = computed(() => type.value === 'loan')
const accentClass = computed(() =>
  type.value === 'income' ? 'inc' : type.value === 'expense' ? 'exp' : 'tr'
)

// ---------- 金额（表达式）----------
const expr = ref('')
const amountDisplay = computed(() => (expr.value === '' ? '0.00' : expr.value))
const hasOp = computed(() => /[+\-−]/.test(expr.value.slice(1)))
function evalExpr(s) {
  if (!s) return 0
  let total = 0
  let sign = 1
  let num = ''
  for (const ch of s) {
    if (ch === '+' || ch === '-' || ch === '−') {
      total += sign * parseFloat(num || '0')
      sign = ch === '+' ? 1 : -1
      num = ''
    } else {
      num += ch
    }
  }
  total += sign * parseFloat(num || '0')
  return isNaN(total) ? 0 : total
}
const amountValue = computed(() => Math.round(evalExpr(expr.value) * 100) / 100)

function tapKey(k) {
  if (k === 'del') {
    expr.value = expr.value.slice(0, -1)
    return
  }
  if (k === '+' || k === '−') {
    if (!expr.value) return
    const last = expr.value.slice(-1)
    if (last === '+' || last === '−') expr.value = expr.value.slice(0, -1) + k
    else expr.value += k
    return
  }
  if (k === '.') {
    // 当前数字段不能有两个小数点
    const seg = expr.value.split(/[+\-−]/).pop()
    if (seg.includes('.')) return
    expr.value += expr.value === '' ? '0.' : '.'
    return
  }
  expr.value += k
}

// ---------- 分类（含子分类展开）----------
const tree = ref({ expense: [], income: [] })
const categoryId = ref(null)
const expandedId = ref(null)
const parents = computed(() => (type.value === 'income' ? tree.value.income : tree.value.expense))
const expandedChildren = computed(() => {
  const p = parents.value.find((x) => x.id === expandedId.value)
  return p ? p.children || [] : []
})
const PALETTE = ['#e5793a', '#8b78e0', '#2eb8a6', '#3aa0d0', '#e0609a', '#5b8def', '#f0a13b', '#3ba55d']
function catColor(name) {
  let h = 0
  for (let i = 0; i < (name || '').length; i++) h = (h + name.charCodeAt(i)) >>> 0
  return PALETTE[h % PALETTE.length]
}
function catEmoji(name) {
  return categoryEmoji(name, type.value)
}
function pickParent(p) {
  categoryId.value = p.id
  expandedId.value = p.children && p.children.length ? (expandedId.value === p.id ? null : p.id) : null
}
function pickChild(c) {
  categoryId.value = c.id
}

// ---------- 账户 ----------
const accounts = ref([])
const accountId = ref(null)
const destId = ref(null)
const accountById = (id) => accounts.value.find((a) => a.id === id)
const sourceAccount = computed(() => accountById(accountId.value))
const destAccount = computed(() => accountById(destId.value))
const sheetTarget = ref(null) // 'account' | 'source' | 'dest'
function pickAccount(a) {
  if (sheetTarget.value === 'dest') destId.value = a.id
  else accountId.value = a.id
  sheetTarget.value = null
}

// ---------- 备注 / 日期 / 对方 ----------
const note = ref('')
const noteSheet = ref(false)
function onNoteConfirm(v) {
  note.value = v
  noteSheet.value = false
}
const counterparty = ref('')
const cpSheet = ref(false)
function onCpConfirm(v) {
  counterparty.value = v
  cpSheet.value = false
}
const loanDir = ref('BORROW')

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
  // 今天用当前时刻，历史日期用当日中午，避免时区边界
  if (occurredDate.value === todayStr()) {
    const d = new Date()
    const p = (n) => String(n).padStart(2, '0')
    return `${occurredDate.value}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  }
  return `${occurredDate.value}T12:00:00`
}

// ---------- 编辑 / 全部模式目标账本 ----------
const editingId = ref(null)
const isEditing = computed(() => editingId.value !== null)
const targetLedgerId = ref(null)
const showLedgerPicker = computed(() => !isEditing.value && ledgerStore.isAll)
const targetLedgerName = computed(() => {
  const l = (ledgerStore.ledgers || []).find((x) => x.id === targetLedgerId.value)
  return l ? l.name : '默认账本'
})
const showLedgerSheet = ref(false)
function pickTargetLedger(id) {
  targetLedgerId.value = id
  showLedgerSheet.value = false
  categoryId.value = null
  expandedId.value = null
  createdBy.value = null
  projectId.value = null
  merchantId.value = null
  tagIds.value = []
  load()
}

// ---------- 协作代记（记账人）----------
// 目标账本：全部模式/带参用 targetLedgerId 对应的账本，否则用当前账本。
const effectiveLedger = computed(() => {
  if (targetLedgerId.value) {
    return (ledgerStore.ledgers || []).find((l) => l.id === targetLedgerId.value) || null
  }
  return ledgerStore.current
})
// 仅协作账本、且新增（非编辑）时可指定记账人。
const isCollaborative = computed(
  () => !isEditing.value && effectiveLedger.value?.type === 'COLLABORATIVE'
)
const selfId = computed(() => authStore.user?.id ?? null)
const members = ref([])
const memberSheet = ref(false)
const createdBy = ref(null) // null = 记为自己
const recorderName = computed(() => {
  const uid = createdBy.value
  if (uid == null || uid === selfId.value) return '我'
  const m = members.value.find((x) => x.userId === uid)
  return m ? m.displayName || '成员' : '成员'
})
async function openMemberSheet() {
  const lid = effectiveLedger.value?.id
  if (!lid) return
  try {
    members.value = await listMembers(lid)
  } catch (e) {
    members.value = []
  }
  memberSheet.value = true
}
function pickMember(uid) {
  createdBy.value = uid
  memberSheet.value = false
}
function memberLabel(m) {
  if (m.userId === selfId.value) return m.displayName ? `${m.displayName}（我）` : '我'
  return m.displayName || '成员'
}

// ---------- 项目 ----------
const projects = ref([])
const projectId = ref(null)
const projectSheet = ref(false)
const projNameSheet = ref(false)
const projectName = computed(() => {
  const p = projects.value.find((x) => x.id === projectId.value)
  return p ? p.name : ''
})
async function loadProjects() {
  try {
    projects.value = await listProjects(targetLedgerId.value)
  } catch (e) {
    projects.value = []
  }
}
async function openProjectSheet() {
  await loadProjects()
  projectSheet.value = true
}
function pickProject(id) {
  projectId.value = id
  projectSheet.value = false
}
async function onProjNameConfirm(name) {
  projNameSheet.value = false
  const trimmed = (name || '').trim()
  if (!trimmed) return
  try {
    const p = await createProject(trimmed, targetLedgerId.value)
    await loadProjects()
    projectId.value = p.id
    uni.showToast({ title: '已创建项目', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: e.message || '创建失败', icon: 'none' })
  }
}

// ---------- 商家 ----------
const merchants = ref([])
const merchantId = ref(null)
const merchantSheet = ref(false)
const merchNameSheet = ref(false)
const merchantName = computed(() => {
  const m = merchants.value.find((x) => x.id === merchantId.value)
  return m ? m.name : ''
})
async function loadMerchants() {
  try {
    merchants.value = await listMerchants(targetLedgerId.value)
  } catch (e) {
    merchants.value = []
  }
}
async function openMerchantSheet() {
  await loadMerchants()
  merchantSheet.value = true
}
function pickMerchant(id) {
  merchantId.value = id
  merchantSheet.value = false
}
async function onMerchNameConfirm(name) {
  merchNameSheet.value = false
  const trimmed = (name || '').trim()
  if (!trimmed) return
  try {
    const m = await createMerchant(trimmed, targetLedgerId.value)
    await loadMerchants()
    merchantId.value = m.id
    uni.showToast({ title: '已添加商家', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: e.message || '添加失败', icon: 'none' })
  }
}

// ---------- 标签（多选）----------
const tags = ref([])
const tagIds = ref([])
const tagSheet = ref(false)
const tagNameSheet = ref(false)
const tagCount = computed(() => tagIds.value.length)
async function loadTags() {
  try {
    tags.value = await listTags(targetLedgerId.value)
  } catch (e) {
    tags.value = []
  }
}
async function openTagSheet() {
  await loadTags()
  tagSheet.value = true
}
function toggleTag(id) {
  const i = tagIds.value.indexOf(id)
  if (i >= 0) tagIds.value.splice(i, 1)
  else tagIds.value.push(id)
}
async function onTagNameConfirm(name) {
  tagNameSheet.value = false
  const trimmed = (name || '').trim()
  if (!trimmed) return
  try {
    const t = await createTag(trimmed, targetLedgerId.value)
    await loadTags()
    if (!tagIds.value.includes(t.id)) tagIds.value.push(t.id)
  } catch (e) {
    uni.showToast({ title: e.message || '添加失败', icon: 'none' })
  }
}

// ---------- 模板 ----------
const templates = ref([])
const templateSheet = ref(false)
const tplNameSheet = ref(false)
async function loadTemplates() {
  try {
    templates.value = await listTemplates(targetLedgerId.value)
  } catch (e) {
    templates.value = []
  }
}
async function openTemplateSheet() {
  await loadTemplates()
  templateSheet.value = true
}
function tplTypeLabel(t) {
  return t === 'income' ? '收入' : t === 'transfer' ? '转账' : '支出'
}
function applyTemplate(t) {
  templateSheet.value = false
  setType(t.type)
  if (t.amount != null) expr.value = String(t.amount)
  note.value = t.note || ''
  if (t.type === 'transfer') {
    if (t.sourceAccountId && accountById(t.sourceAccountId)) accountId.value = t.sourceAccountId
    if (t.destinationAccountId && accountById(t.destinationAccountId)) destId.value = t.destinationAccountId
  } else {
    if (t.accountId && accountById(t.accountId)) accountId.value = t.accountId
    if (t.categoryId) categoryId.value = t.categoryId
  }
  uni.showToast({ title: `已套用「${t.name}」`, icon: 'none' })
}
async function removeTemplate(id) {
  try {
    await deleteTemplate(id, targetLedgerId.value)
    await loadTemplates()
  } catch (e) {
    uni.showToast({ title: e.message || '删除失败', icon: 'none' })
  }
}
// 存为模板：先收集当前表单为 payload，再弹出命名输入。
function startSaveTemplate() {
  if (isTransfer.value) {
    if (!accountId.value || !destId.value) {
      uni.showToast({ title: '请先选择转账账户', icon: 'none' })
      return
    }
  } else if (!categoryId.value) {
    uni.showToast({ title: '请先选择分类', icon: 'none' })
    return
  }
  templateSheet.value = false
  tplNameSheet.value = true
}
async function onTplNameConfirm(name) {
  tplNameSheet.value = false
  const trimmed = (name || '').trim()
  if (!trimmed) return
  const amount = amountValue.value
  const payload = {
    name: trimmed,
    type: type.value,
    amount: amount > 0 ? String(amount) : undefined,
    note: note.value.trim() || undefined
  }
  if (isTransfer.value) {
    payload.sourceAccountId = accountId.value
    payload.destinationAccountId = destId.value
  } else {
    payload.accountId = accountId.value
    payload.categoryId = categoryId.value
  }
  try {
    await createTemplate(payload, targetLedgerId.value)
    uni.showToast({ title: '已存为模板', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  }
}

onLoad(async (q) => {
  editingId.value = q && q.id ? Number(q.id) : null
  // 支持从账户/资产页以 ?type=transfer 直接进入转账模式。
  if (q && q.type && ['expense', 'income', 'transfer'].includes(q.type)) {
    type.value = q.type
  }
  // 确保账本列表就绪，用于判断目标账本是否为协作账本（协作代记入口）。
  try {
    if (!ledgerStore.ledgers.length) await ledgerStore.load()
  } catch (e) {
    /* ignore */
  }
  if (q && q.ledgerId) targetLedgerId.value = Number(q.ledgerId)
  else if (!isEditing.value && ledgerStore.isAll) {
    const def = ledgerStore.ledgers.find((l) => l.isDefault) || ledgerStore.ledgers[0]
    targetLedgerId.value = def ? def.id : null
  }
  load()
})

async function load() {
  try {
    // 记账账户来自当前账本的可选集（本人纳入 + 他人暴露）；余额不可见时字段为 null。
    const [accs, cats] = await Promise.all([
      listSelectableAccounts(targetLedgerId.value),
      listCategories(targetLedgerId.value)
    ])
    accounts.value = accs
    tree.value = cats
    // 默认账户：上一笔在此账本记账用的账户（后端记忆，回退可选集第一）。
    let defId = accs[0]?.id ?? null
    try {
      const d = await getDefaultAccount(targetLedgerId.value)
      if (d && d.id != null) defId = d.id
    } catch (e) {
      /* 无默认时用可选集第一 */
    }
    accountId.value = defId
    destId.value = accs.find((a) => a.id !== defId)?.id ?? null
    loadProjects()
    loadMerchants()
    loadTags()
    if (isEditing.value) {
      await prefill()
      uni.setNavigationBarTitle({ title: '编辑记录' })
    }
  } catch (e) {
    uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  }
}

async function prefill() {
  const tx = await getTransaction(editingId.value, targetLedgerId.value)
  type.value = tx.type
  expr.value = String(tx.amount)
  note.value = tx.note || ''
  projectId.value = tx.projectId != null ? tx.projectId : null
  merchantId.value = tx.merchantId != null ? tx.merchantId : null
  tagIds.value = Array.isArray(tx.tagIds) ? tx.tagIds.slice() : []
  if (tx.occurredAt) occurredDate.value = tx.occurredAt.slice(0, 10)
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
  expandedId.value = null
  if (t === 'transfer' && destId.value === accountId.value) {
    const other = accounts.value.find((a) => a.id !== accountId.value)
    destId.value = other ? other.id : null
  }
}

const submitting = ref(false)
async function submit(cont) {
  const amount = amountValue.value
  if (!amount || amount <= 0) {
    uni.showToast({ title: '请输入正确金额', icon: 'none' })
    return
  }

  // 借贷：写入借贷台账
  if (isLoan.value) {
    if (!counterparty.value.trim()) {
      uni.showToast({ title: '请填写对方', icon: 'none' })
      return
    }
    await run(() =>
      createLoan({
        direction: loanDir.value,
        counterparty: counterparty.value.trim(),
        amount: String(amount),
        note: note.value.trim() || undefined
      })
    , cont)
    return
  }

  if (!accounts.value.length) {
    uni.showToast({ title: '请先创建账户', icon: 'none' })
    return
  }
  const payload = { type: type.value, amount: String(amount), occurredAt: occurredAtIso(), note: note.value.trim() || undefined }
  // 协作代记：指定了非自己的记账人时带上（后端二次校验：协作账本且为成员才生效）。
  if (isCollaborative.value && createdBy.value != null && createdBy.value !== selfId.value) {
    payload.createdBy = createdBy.value
  }
  // 所属项目 / 商家 / 标签（可空）。
  if (projectId.value != null) payload.projectId = projectId.value
  if (merchantId.value != null) payload.merchantId = merchantId.value
  if (tagIds.value.length) payload.tagIds = tagIds.value.slice()
  // 转账：账户间动作，脱离账本，独立提交（不支持编辑/协作代记）。
  if (isTransfer.value) {
    if (accountId.value === destId.value) {
      uni.showToast({ title: '转账账户不能相同', icon: 'none' })
      return
    }
    await run(() => transferBetweenAccounts({
      sourceAccountId: accountId.value,
      destinationAccountId: destId.value,
      amount: String(amount),
      occurredAt: occurredAtIso(),
      note: note.value.trim() || undefined
    }), cont)
    return
  }

  // 收支：需分类。
  if (!parents.value.length) {
    uni.showModal({
      title: '还没有分类',
      content: '支出/收入需要选择分类，先去创建一个吧。',
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

  if (isEditing.value) {
    await run(() => updateTransaction(editingId.value, payload, targetLedgerId.value), false)
  } else {
    await run(() => createTransaction(payload, targetLedgerId.value), cont)
  }
}

async function run(fn, cont) {
  submitting.value = true
  try {
    await fn()
    if (isEditing.value) {
      uni.showToast({ title: '已保存', icon: 'success' })
      setTimeout(() => uni.navigateBack(), 500)
    } else if (cont) {
      expr.value = ''
      note.value = ''
      counterparty.value = ''
      uni.showToast({ title: '已记 1 笔，继续', icon: 'none' })
    } else {
      uni.showToast({ title: '已记录', icon: 'success' })
      setTimeout(() => uni.navigateBack(), 500)
    }
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}
function goBack() {
  uni.navigateBack()
}
</script>

<template>
  <view class="rec" :class="accentClass">
    <!-- 顶部 -->
    <view class="rnav">
      <text class="x" @click="goBack">✕</text>
      <text class="title" v-if="isEditing">编辑记录</text>
      <text class="save" @click="submit(false)">{{ isEditing ? '保存' : '完成' }}</text>
    </view>
    <scroll-view scroll-x class="types" :show-scrollbar="false">
      <text v-for="t in TYPES" :key="t.value" class="ty" :class="{ on: type === t.value }" @click="setType(t.value)">{{ t.label }}</text>
    </scroll-view>

    <!-- 金额条 -->
    <view class="amt">
      <text class="cur">¥</text>
      <text class="amtv" :class="{ ph: expr === '' }">{{ amountDisplay }}<text class="cursor"></text></text>
    </view>

    <!-- 主区 -->
    <scroll-view scroll-y class="main">
      <!-- 支出/收入：分类九宫格 -->
      <template v-if="!isTransfer && !isLoan">
        <view v-if="!parents.length" class="cempty">
          还没有{{ type === 'income' ? '收入' : '支出' }}分类，
          <text class="link" @click="uni.navigateTo({ url: '/pages/categories/categories' })">去添加</text>
        </view>
        <view v-else class="cgrid">
          <template v-for="p in parents" :key="p.id">
            <view class="cat" :class="{ on: categoryId === p.id }" @click="pickParent(p)">
              <view class="cic" :style="{ background: catColor(p.name) }">
                {{ catEmoji(p.name) }}
                <text v-if="p.children && p.children.length" class="subdot">{{ expandedId === p.id ? '▴' : '▾' }}</text>
              </view>
              <text class="cl">{{ p.name }}</text>
            </view>
          </template>
        </view>
        <!-- 子分类 -->
        <view v-if="expandedChildren.length" class="subwrap">
          <view v-for="c in expandedChildren" :key="c.id" class="cat" :class="{ on: categoryId === c.id }" @click="pickChild(c)">
            <view class="cic sub" :style="{ background: catColor(c.name) }">{{ catEmoji(c.name) }}</view>
            <text class="cl">{{ c.name }}</text>
          </view>
        </view>
      </template>

      <!-- 转账 -->
      <template v-else-if="isTransfer">
        <view class="xfer">
          <view class="xcard" @click="sheetTarget = 'source'">
            <text class="xic out">↗</text>
            <view class="xinfo"><text class="xk">转出</text><text class="xn">{{ sourceAccount ? sourceAccount.name : '选择账户' }}</text></view>
          </view>
          <text class="swap">⇅</text>
          <view class="xcard" @click="sheetTarget = 'dest'">
            <text class="xic in">↘</text>
            <view class="xinfo"><text class="xk">转入</text><text class="xn">{{ destAccount ? destAccount.name : '选择账户' }}</text></view>
          </view>
        </view>
      </template>

      <!-- 借贷 -->
      <template v-else>
        <view class="seg">
          <text class="s" :class="{ on: loanDir === 'BORROW' }" @click="loanDir = 'BORROW'">借入（待还）</text>
          <text class="s" :class="{ on: loanDir === 'LEND' }" @click="loanDir = 'LEND'">借出（待收）</text>
        </view>
        <view class="lrow" @click="cpSheet = true">
          <text class="lk">对方</text><text class="lv">{{ counterparty || '填写姓名 ›' }}</text>
        </view>
      </template>
    </scroll-view>

    <!-- chips -->
    <scroll-view scroll-x class="chips" :show-scrollbar="false">
      <view v-if="showLedgerPicker" class="chip on" @click="showLedgerSheet = true">📓 记到：{{ targetLedgerName }}</view>
      <view v-if="isCollaborative && !isLoan" class="chip" :class="{ on: createdBy != null && createdBy !== selfId }" @click="openMemberSheet">👤 记账人：{{ recorderName }}</view>
      <view v-if="!isTransfer && !isLoan" class="chip" @click="sheetTarget = 'account'">
        {{ accountTypeEmoji(sourceAccount?.type) }} {{ sourceAccount ? sourceAccount.name : '选择账户' }}
      </view>
      <picker mode="date" :value="occurredDate" @change="onDateChange">
        <view class="chip">📅 {{ dateLabel }}</view>
      </picker>
      <view class="chip" @click="noteSheet = true">📝 {{ note ? note : '备注' }}</view>
      <view v-if="!isLoan" class="chip" :class="{ on: projectId != null }" @click="openProjectSheet">📁 {{ projectName || '项目' }}</view>
      <view v-if="!isTransfer && !isLoan" class="chip" :class="{ on: merchantId != null }" @click="openMerchantSheet">🏪 {{ merchantName || '商家' }}</view>
      <view v-if="!isLoan" class="chip" :class="{ on: tagCount > 0 }" @click="openTagSheet">🏷️ {{ tagCount > 0 ? `标签·${tagCount}` : '标签' }}</view>
      <view v-if="!isLoan && !isEditing" class="chip" @click="openTemplateSheet">⭐ 模板</view>
    </scroll-view>

    <!-- 键盘 -->
    <view class="kp">
      <text class="key" @click="tapKey('7')">7</text><text class="key" @click="tapKey('8')">8</text><text class="key" @click="tapKey('9')">9</text><text class="key op" @click="tapKey('del')">⌫</text>
      <text class="key" @click="tapKey('4')">4</text><text class="key" @click="tapKey('5')">5</text><text class="key" @click="tapKey('6')">6</text><text class="key op" @click="tapKey('−')">−</text>
      <text class="key" @click="tapKey('1')">1</text><text class="key" @click="tapKey('2')">2</text><text class="key" @click="tapKey('3')">3</text><text class="key op" @click="tapKey('+')">＋</text>
      <text v-if="!isEditing" class="key mini" @click="submit(true)">保存再记</text>
      <text v-else class="key mini"> </text>
      <text class="key" @click="tapKey('0')">0</text><text class="key" @click="tapKey('.')">.</text>
      <text class="key done" :class="accentClass" @click="submit(false)">{{ isEditing ? '保存' : '完成' }}</text>
    </view>

    <!-- 账户选择 -->
    <view v-if="sheetTarget" class="mask" @click="sheetTarget = null">
      <view class="sheet" @click.stop>
        <text class="sheet-title">{{ sheetTarget === 'dest' ? '选择转入账户' : sheetTarget === 'source' ? '选择转出账户' : '选择账户' }}</text>
        <view v-if="!accounts.length" class="sempty">
          该账本还没有可用账户，
          <text class="link" @click="uni.navigateTo({ url: '/pages/accounts/accounts' })">去创建</text>
        </view>
        <view v-for="a in accounts" :key="a.id" class="sitem" @click="pickAccount(a)">
          <text class="si-ic">{{ accountTypeEmoji(a.type) }}</text>
          <view class="si-name"><text>{{ a.name }}</text><text class="si-type">{{ accountTypeLabel(a.type) }}</text></view>
          <text v-if="a.canSeeBalance === false" class="si-bal masked">余额隐藏</text>
          <text v-else class="si-bal" :class="{ neg: Number(a.currentBalance) < 0 }">¥{{ formatAmount(a.currentBalance) }}</text>
        </view>
      </view>
    </view>

    <!-- 目标账本选择 -->
    <view v-if="showLedgerSheet" class="mask" @click="showLedgerSheet = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">记到哪个账本</text>
        <view v-for="l in ledgerStore.ledgers" :key="l.id" class="sitem" @click="pickTargetLedger(l.id)">
          <text class="si-ic">📓</text>
          <view class="si-name"><text>{{ l.name }}</text></view>
          <text class="radio" :class="{ on: l.id === targetLedgerId }"></text>
        </view>
      </view>
    </view>

    <!-- 记账人选择（协作账本代记）-->
    <view v-if="memberSheet" class="mask" @click="memberSheet = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">这笔记在谁名下</text>
        <view class="sitem" @click="pickMember(null)">
          <text class="si-ic">🙋</text>
          <view class="si-name"><text>我</text></view>
          <text class="radio" :class="{ on: createdBy == null || createdBy === selfId }"></text>
        </view>
        <template v-for="m in members" :key="m.userId">
          <view v-if="m.userId !== selfId" class="sitem" @click="pickMember(m.userId)">
            <text class="si-ic">👤</text>
            <view class="si-name"><text>{{ memberLabel(m) }}</text><text class="si-type">{{ m.role === 'OWNER' ? '创建者' : '成员' }}</text></view>
            <text class="radio" :class="{ on: createdBy === m.userId }"></text>
          </view>
        </template>
      </view>
    </view>

    <!-- 模板：套用 / 删除 / 存为模板 -->
    <view v-if="templateSheet" class="mask" @click="templateSheet = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">记账模板</text>
        <view v-if="!templates.length" class="tpl-empty">还没有模板，先记好一笔后点「存为模板」吧</view>
        <view v-for="t in templates" :key="t.id" class="titem">
          <view class="ti-main" @click="applyTemplate(t)">
            <text class="ti-name">{{ t.name }}</text>
            <text class="ti-meta">{{ tplTypeLabel(t.type) }}{{ t.amount != null ? ' · ¥' + t.amount : '' }}</text>
          </view>
          <text class="ti-del" @click.stop="removeTemplate(t.id)">删除</text>
        </view>
        <view class="tpl-save" @click="startSaveTemplate">＋ 将当前内容存为模板</view>
      </view>
    </view>

    <!-- 项目选择 / 新建 -->
    <view v-if="projectSheet" class="mask" @click="projectSheet = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">归到哪个项目</text>
        <view class="sitem" @click="pickProject(null)">
          <text class="si-ic">🚫</text>
          <view class="si-name"><text>不归项目</text></view>
          <text class="radio" :class="{ on: projectId == null }"></text>
        </view>
        <view v-for="p in projects" :key="p.id" class="sitem" @click="pickProject(p.id)">
          <text class="si-ic">📁</text>
          <view class="si-name"><text>{{ p.name }}</text><text v-if="p.archived" class="si-type">已归档</text></view>
          <text class="radio" :class="{ on: projectId === p.id }"></text>
        </view>
        <view class="tpl-save" @click="projectSheet = false; projNameSheet = true">＋ 新建项目</view>
      </view>
    </view>

    <!-- 商家选择 / 新建 -->
    <view v-if="merchantSheet" class="mask" @click="merchantSheet = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">选择商家</text>
        <view class="sitem" @click="pickMerchant(null)">
          <text class="si-ic">🚫</text>
          <view class="si-name"><text>不记商家</text></view>
          <text class="radio" :class="{ on: merchantId == null }"></text>
        </view>
        <view v-for="m in merchants" :key="m.id" class="sitem" @click="pickMerchant(m.id)">
          <text class="si-ic">🏪</text>
          <view class="si-name"><text>{{ m.name }}</text></view>
          <text class="radio" :class="{ on: merchantId === m.id }"></text>
        </view>
        <view class="tpl-save" @click="merchantSheet = false; merchNameSheet = true">＋ 新建商家</view>
      </view>
    </view>

    <!-- 标签多选 -->
    <view v-if="tagSheet" class="mask" @click="tagSheet = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">选择标签（可多选）</text>
        <view v-if="!tags.length" class="tpl-empty">还没有标签，点下方新建</view>
        <view class="tagwrap">
          <text
            v-for="t in tags"
            :key="t.id"
            class="tagchip"
            :class="{ on: tagIds.includes(t.id) }"
            @click="toggleTag(t.id)"
          >{{ t.name }}</text>
        </view>
        <view class="tpl-save" @click="tagSheet = false; tagNameSheet = true">＋ 新建标签</view>
        <view class="tag-done" @click="tagSheet = false">完成</view>
      </view>
    </view>

    <InputSheet :visible="tagNameSheet" title="新建标签" placeholder="如：报销、出差、必要" :maxlength="30" @update:visible="tagNameSheet = $event" @confirm="onTagNameConfirm" />
    <InputSheet :visible="merchNameSheet" title="新建商家" placeholder="如：星巴克、盒马" @update:visible="merchNameSheet = $event" @confirm="onMerchNameConfirm" />
    <InputSheet :visible="projNameSheet" title="新建项目" placeholder="如：装修、三亚旅行" @update:visible="projNameSheet = $event" @confirm="onProjNameConfirm" />
    <InputSheet :visible="tplNameSheet" title="模板名称" placeholder="如：早餐、地铁通勤" @update:visible="tplNameSheet = $event" @confirm="onTplNameConfirm" />
    <InputSheet :visible="noteSheet" title="备注" placeholder="添加备注" :value="note" @update:visible="noteSheet = $event" @confirm="onNoteConfirm" />
    <InputSheet :visible="cpSheet" title="对方" placeholder="姓名 / 备注" :value="counterparty" @update:visible="cpSheet = $event" @confirm="onCpConfirm" />
  </view>
</template>

<style scoped>
.rec {
  --accent: #f0553d;
  height: 100vh;        /* 锁定为一屏，避免整页下拉 */
  overflow: hidden;
  background: #fff;
  display: flex;
  flex-direction: column;
}
.rec.inc { --accent: #12a150; }
.rec.tr { --accent: #8a94a6; }

.rnav {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 88rpx;
  padding: 0 28rpx;
}
.rnav .x { font-size: 40rpx; color: #5b6470; }
.rnav .title { font-size: 32rpx; font-weight: 800; }
.rnav .save { font-size: 28rpx; color: var(--accent); font-weight: 700; }

.types {
  white-space: nowrap;
  padding: 0 20rpx 12rpx;
}
.ty {
  display: inline-block;
  font-size: 32rpx;
  font-weight: 700;
  color: #9aa2ad;
  padding: 8rpx 22rpx;
}
.ty.on {
  color: #16181c;
}
.ty.on::after {
  content: '';
  display: block;
  height: 6rpx;
  width: 40rpx;
  margin: 6rpx auto 0;
  border-radius: 4rpx;
  background: var(--accent);
}

.amt {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 16rpx 32rpx 22rpx;
  border-bottom: 1rpx solid #f1f3f5;
}
.cur { font-size: 40rpx; color: #5b6470; font-weight: 700; }
.amtv { flex: 1; font-size: 68rpx; font-weight: 800; color: #16181c; letter-spacing: -0.02em; }
.amtv.ph { color: #c7ccd2; }
.cursor { display: inline-block; width: 3rpx; height: 52rpx; background: var(--accent); margin-left: 4rpx; vertical-align: -8rpx; }

.main {
  flex: 1;
  min-height: 0;        /* 允许在 flex 中收缩，仅本区内部滚动，键盘钉底 */
}
.cgrid { display: flex; flex-wrap: wrap; padding: 16rpx 8rpx 4rpx; }
.cat { width: 20%; display: flex; flex-direction: column; align-items: center; gap: 10rpx; padding: 16rpx 0; }
.cic {
  width: 96rpx; height: 96rpx; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  font-size: 46rpx; position: relative;
}
.cic.sub { width: 80rpx; height: 80rpx; font-size: 38rpx; }
.cat.on .cic { box-shadow: 0 0 0 4rpx #fff, 0 0 0 8rpx var(--accent); }
.subdot {
  position: absolute; right: -2rpx; bottom: -2rpx;
  width: 30rpx; height: 30rpx; border-radius: 50%;
  background: #fff; color: #9aa2ad; font-size: 18rpx;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 2rpx 6rpx rgba(0,0,0,0.15);
}
.cl { font-size: 22rpx; color: #5b6470; max-width: 120rpx; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.cat.on .cl { color: #16181c; font-weight: 700; }
.subwrap {
  display: flex; flex-wrap: wrap;
  background: #f6f7f9; border-radius: 18rpx; margin: 4rpx 20rpx 12rpx; padding: 10rpx 4rpx;
}
.subwrap .cat { width: 20%; }
.cempty { padding: 60rpx 40rpx; text-align: center; color: #6b7280; font-size: 28rpx; }
.link { color: #12a150; font-weight: 700; }

.xfer { display: flex; flex-direction: column; align-items: center; gap: 16rpx; padding: 24rpx; }
.xcard { width: 100%; display: flex; align-items: center; gap: 18rpx; background: #f6f7f9; border-radius: 18rpx; padding: 26rpx; }
.xic { width: 60rpx; height: 60rpx; border-radius: 16rpx; text-align: center; line-height: 60rpx; font-size: 30rpx; }
.xic.out { background: #fdece8; color: #f0553d; }
.xic.in { background: #e6f6ec; color: #12a150; }
.xinfo { display: flex; flex-direction: column; gap: 4rpx; }
.xk { font-size: 22rpx; color: #9aa2ad; }
.xn { font-size: 30rpx; font-weight: 700; color: #16181c; }
.swap { width: 60rpx; height: 60rpx; border-radius: 50%; background: #fff; box-shadow: 0 6rpx 16rpx rgba(0,0,0,0.08); text-align: center; line-height: 60rpx; color: #8a94a6; margin: -6rpx 0; }

.seg { display: flex; background: #f6f7f9; border-radius: 14rpx; padding: 6rpx; margin: 20rpx 24rpx; }
.seg .s { flex: 1; text-align: center; padding: 18rpx 0; font-size: 28rpx; font-weight: 700; color: #5b6470; border-radius: 10rpx; }
.seg .s.on { background: #fff; color: #16181c; box-shadow: 0 2rpx 8rpx rgba(0,0,0,0.06); }
.lrow { display: flex; justify-content: space-between; align-items: center; margin: 0 24rpx; padding: 28rpx 0; border-top: 1rpx solid #f1f3f5; }
.lk { font-size: 30rpx; color: #5b6470; }
.lv { font-size: 30rpx; font-weight: 600; color: #16181c; }

.chips { white-space: nowrap; padding: 12rpx 20rpx; border-top: 1rpx solid #f1f3f5; }
.chip {
  display: inline-flex; align-items: center;
  background: #f6f7f9; border-radius: 999rpx; padding: 12rpx 22rpx; margin-right: 12rpx;
  font-size: 24rpx; color: #5b6470;
}
.chip.on { background: #e6f6ec; color: #0e8a44; font-weight: 700; }

.kp {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 2rpx;
  background: #e4e7ec;
  padding-bottom: env(safe-area-inset-bottom);
}
.key {
  background: #fbfbfc; height: 108rpx;
  display: flex; align-items: center; justify-content: center;
  font-size: 44rpx; font-weight: 600; color: #16181c;
}
.key.op { color: #5b6470; }
.key.mini { font-size: 26rpx; color: #5b6470; font-weight: 700; }
.key.done { background: var(--accent); color: #fff; font-weight: 800; font-size: 32rpx; }

.mask { position: fixed; inset: 0; background: rgba(15,23,42,0.42); display: flex; align-items: flex-end; z-index: 50; }
.sheet { width: 100%; background: #fff; border-radius: 28rpx 28rpx 0 0; padding: 32rpx 32rpx calc(32rpx + env(safe-area-inset-bottom)); box-sizing: border-box; }
.sheet-title { display: block; text-align: center; font-size: 30rpx; font-weight: 800; margin-bottom: 16rpx; }
.sitem { display: flex; align-items: center; gap: 20rpx; padding: 24rpx 8rpx; border-top: 1rpx solid #f1f3f5; }
.sitem:first-of-type { border-top: none; }
.si-ic { width: 60rpx; height: 60rpx; border-radius: 16rpx; background: #f6f7f9; text-align: center; line-height: 60rpx; font-size: 32rpx; }
.si-name { flex: 1; display: flex; flex-direction: column; gap: 4rpx; }
.si-type { font-size: 22rpx; color: #9aa2ad; }
.si-bal { font-size: 30rpx; font-weight: 700; }
.si-bal.neg { color: #e5484d; }
.si-bal.masked { font-size: 24rpx; font-weight: 500; color: #9aa2ad; }
.sempty { padding: 48rpx 24rpx; text-align: center; color: #6b7280; font-size: 28rpx; }
.radio { width: 36rpx; height: 36rpx; border-radius: 50%; border: 3rpx solid #d1d5db; box-sizing: border-box; }
.radio.on { border-color: #12a150; background: radial-gradient(circle at center, #12a150 0, #12a150 9rpx, #fff 10rpx, #fff 100%); }

.tpl-empty { text-align: center; color: #9aa2ad; font-size: 26rpx; padding: 40rpx 20rpx; }
.titem { display: flex; align-items: center; padding: 22rpx 8rpx; border-top: 1rpx solid #f1f3f5; }
.titem:first-of-type { border-top: none; }
.ti-main { flex: 1; display: flex; flex-direction: column; gap: 6rpx; }
.ti-name { font-size: 30rpx; font-weight: 700; color: #16181c; }
.ti-meta { font-size: 24rpx; color: #9aa2ad; }
.ti-del { font-size: 26rpx; color: #e5484d; padding: 8rpx 12rpx; }
.tpl-save { margin-top: 20rpx; text-align: center; padding: 24rpx; border-radius: 16rpx; background: #e6f6ec; color: #0e8a44; font-weight: 700; font-size: 28rpx; }

.tagwrap { display: flex; flex-wrap: wrap; gap: 16rpx; padding: 8rpx 4rpx 4rpx; }
.tagchip { padding: 14rpx 26rpx; border-radius: 999rpx; background: #f2f4f6; color: #5b6470; font-size: 26rpx; border: 1rpx solid transparent; }
.tagchip.on { background: #e6f6ec; color: #0e8a44; font-weight: 700; border-color: #12a150; }
.tag-done { margin-top: 16rpx; text-align: center; padding: 22rpx; border-radius: 16rpx; background: #12a150; color: #fff; font-weight: 700; font-size: 28rpx; }
</style>
