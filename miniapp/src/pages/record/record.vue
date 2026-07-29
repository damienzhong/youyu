<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { listAccounts, accountTypeEmoji, accountTypeLabel } from '../../api/account'
import { listCategories } from '../../api/category'
import { createTransaction, getTransaction, updateTransaction } from '../../api/transaction'
import { createLoan } from '../../api/loan'
import { useLedgerStore } from '../../stores/ledger'
import { categoryEmoji, formatAmount } from '../../utils/format'

const ledgerStore = useLedgerStore()

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
  load()
}

onLoad(async (q) => {
  editingId.value = q && q.id ? Number(q.id) : null
  if (q && q.ledgerId) targetLedgerId.value = Number(q.ledgerId)
  else if (!isEditing.value && ledgerStore.isAll) {
    try {
      if (!ledgerStore.ledgers.length) await ledgerStore.load()
    } catch (e) {
      /* ignore */
    }
    const def = ledgerStore.ledgers.find((l) => l.isDefault) || ledgerStore.ledgers[0]
    targetLedgerId.value = def ? def.id : null
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
    tree.value = cats
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

async function prefill() {
  const tx = await getTransaction(editingId.value, targetLedgerId.value)
  type.value = tx.type
  expr.value = String(tx.amount)
  note.value = tx.note || ''
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
  if (isTransfer.value) {
    if (accountId.value === destId.value) {
      uni.showToast({ title: '转账账户不能相同', icon: 'none' })
      return
    }
    payload.sourceAccountId = accountId.value
    payload.destinationAccountId = destId.value
  } else {
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
  }

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
      <view v-if="!isTransfer && !isLoan" class="chip" @click="sheetTarget = 'account'">
        {{ accountTypeEmoji(sourceAccount?.type) }} {{ sourceAccount ? sourceAccount.name : '选择账户' }}
      </view>
      <picker mode="date" :value="occurredDate" @change="onDateChange">
        <view class="chip">📅 {{ dateLabel }}</view>
      </picker>
      <view class="chip" @click="noteSheet = true">📝 {{ note ? note : '备注' }}</view>
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
        <view v-for="a in accounts" :key="a.id" class="sitem" @click="pickAccount(a)">
          <text class="si-ic">{{ accountTypeEmoji(a.type) }}</text>
          <view class="si-name"><text>{{ a.name }}</text><text class="si-type">{{ accountTypeLabel(a.type) }}</text></view>
          <text class="si-bal" :class="{ neg: Number(a.currentBalance) < 0 }">¥{{ formatAmount(a.currentBalance) }}</text>
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

    <InputSheet :visible="noteSheet" title="备注" placeholder="添加备注" :value="note" @update:visible="noteSheet = $event" @confirm="onNoteConfirm" />
    <InputSheet :visible="cpSheet" title="对方" placeholder="姓名 / 备注" :value="counterparty" @update:visible="cpSheet = $event" @confirm="onCpConfirm" />
  </view>
</template>

<style scoped>
.rec {
  --accent: #f0553d;
  min-height: 100vh;
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
  min-height: 240rpx;
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
.radio { width: 36rpx; height: 36rpx; border-radius: 50%; border: 3rpx solid #d1d5db; box-sizing: border-box; }
.radio.on { border-color: #12a150; background: radial-gradient(circle at center, #12a150 0, #12a150 9rpx, #fff 10rpx, #fff 100%); }
</style>
