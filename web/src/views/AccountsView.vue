<script setup lang="ts">
/**
 * 资产页（原账户管理）。
 *
 * - 净资产 Hero：净资产 + 总资产 / 总负债；👁 一键隐藏金额（本地记住）。
 * - 账户分组：资金账户（现金/银行卡/支付宝/微信）、信贷账户（信用卡，余额为负=欠款），
 *   各组小计、可折叠；点账户行编辑。
 * - 新增/编辑账户表单：类型（图标网格）+ 名称（计数）+ 余额（仅新增可填）+ 计入总资产 +
 *   隐藏账户 + 备注。
 * - 删除（二次确认，有交易时后端 ACCOUNT_IN_USE 友好提示）。
 * - 当前账号 + 退出登录、数据导出入口。
 * 加载失败保留上次数据 + 重试（需求 11.5）。
 */
import { ref, computed, onMounted } from 'vue'
import { RouterLink } from 'vue-router'
import {
  fetchAccounts,
  createAccount,
  updateAccount,
  deleteAccount,
  validateAccountName,
  validateInitialBalance,
  toAccountErrorMessage,
  formatAmount,
  sumBalances,
  sumAssets,
  sumLiabilities,
  ACCOUNT_TYPE_LABELS,
  ACCOUNT_TYPE_OPTIONS,
  fetchLoans,
  createLoan,
  updateLoan,
  settleLoan,
  deleteLoan,
  validateCounterparty,
  validateAmount,
  toLoanErrorMessage,
  LOAN_DIRECTION_LABELS,
  type Account,
  type AccountType,
  type Loan,
  type LoanList,
  type LoanDirection,
} from '@/lib/ledger'

const loading = ref(true)
const loaded = ref(false)
const loadError = ref('')
const accounts = ref<Account[]>([])

const netAssets = computed(() => sumBalances(accounts.value))
const totalAssets = computed(() => sumAssets(accounts.value))
const totalLiabilities = computed(() => sumLiabilities(accounts.value))

// 金额隐私开关（本地记住）。
const hideAmounts = ref(localStorage.getItem('youyu_hide_assets') === '1')
function toggleHide() {
  hideAmounts.value = !hideAmounts.value
  localStorage.setItem('youyu_hide_assets', hideAmounts.value ? '1' : '0')
}
function money(v: string | number): string {
  return hideAmounts.value ? '****' : `¥${formatAmount(v)}`
}

// 账户类型图标 / 底色。
const ACCOUNT_ICON: Record<AccountType, { emoji: string; tint: string }> = {
  CASH: { emoji: '💵', tint: '#ecfdf3' },
  BANK_CARD: { emoji: '🏦', tint: '#eff6ff' },
  ALIPAY: { emoji: '💠', tint: '#eef4ff' },
  WECHAT: { emoji: '💬', tint: '#ecfdf3' },
  CREDIT_CARD: { emoji: '💳', tint: '#fff7e6' },
  INVESTMENT: { emoji: '📈', tint: '#f3ecff' },
}

// 分组：资金账户 / 信贷账户 / 投资理财。
const FUND_TYPES: AccountType[] = ['CASH', 'BANK_CARD', 'ALIPAY', 'WECHAT']
const fundAccounts = computed(() => accounts.value.filter((a) => FUND_TYPES.includes(a.type)))
const creditAccounts = computed(() => accounts.value.filter((a) => a.type === 'CREDIT_CARD'))
const investAccounts = computed(() => accounts.value.filter((a) => a.type === 'INVESTMENT'))
function sumOf(list: Account[]): string {
  const cents = list.reduce((acc, a) => acc + Math.round(Number(a.currentBalance) * 100), 0)
  return (cents / 100).toFixed(2)
}
const fundSubtotal = computed(() => sumOf(fundAccounts.value))
const creditSubtotal = computed(() => sumOf(creditAccounts.value))
const investSubtotal = computed(() => sumOf(investAccounts.value))

const collapsedFund = ref(false)
const collapsedCredit = ref(false)
const collapsedInvest = ref(false)

/** 信用卡可用余额 = 授信额度 + 当前余额（欠款为负）；无额度返回 null。 */
function availableCredit(acc: Account): string | null {
  if (acc.creditLimit == null || acc.creditLimit === '') return null
  const cents = Math.round(Number(acc.creditLimit) * 100) + Math.round(Number(acc.currentBalance) * 100)
  return (cents / 100).toFixed(2)
}

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [accs, loanData] = await Promise.all([fetchAccounts(), fetchLoans()])
    accounts.value = accs
    loans.value = loanData
    loaded.value = true
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败，请重试'
  } finally {
    loading.value = false
  }
}

// === 新增 / 编辑表单 ===
const showForm = ref(false)
const editingId = ref<number | null>(null)
const formName = ref('')
const formType = ref<AccountType>('CASH')
const formBalance = ref('0.00')
const formIncludeInTotal = ref(true)
const formHidden = ref(false)
const formNote = ref('')
const formCreditLimit = ref('')
const formCurrentBalance = ref('0.00') // 编辑时展示（只读）
const formError = ref('')
const submitting = ref(false)
const isEditing = computed(() => editingId.value != null)

function openCreate() {
  editingId.value = null
  formName.value = ''
  formType.value = 'CASH'
  formBalance.value = '0.00'
  formIncludeInTotal.value = true
  formHidden.value = false
  formNote.value = ''
  formCreditLimit.value = ''
  formError.value = ''
  showForm.value = true
}

function openEdit(acc: Account) {
  editingId.value = acc.id
  formName.value = acc.name
  formType.value = acc.type
  formCurrentBalance.value = acc.currentBalance
  formIncludeInTotal.value = acc.includeInTotal !== false
  formHidden.value = !!acc.hidden
  formNote.value = acc.note ?? ''
  formCreditLimit.value = acc.creditLimit ?? ''
  formError.value = ''
  showForm.value = true
}

function closeForm() {
  showForm.value = false
}

async function onSubmit() {
  formError.value = ''
  const nameErr = validateAccountName(formName.value)
  if (nameErr) {
    formError.value = nameErr
    return
  }
  if (!isEditing.value) {
    const balErr = validateInitialBalance(formBalance.value)
    if (balErr) {
      formError.value = balErr
      return
    }
  }
  if (formNote.value.length > 200) {
    formError.value = '备注最多 200 个字符'
    return
  }
  // 信用卡授信额度（可选）：填了就校验非负、两位小数。
  const isCredit = formType.value === 'CREDIT_CARD'
  let creditLimit: string | null = null
  if (isCredit && formCreditLimit.value.trim()) {
    const v = formCreditLimit.value.trim()
    if (!/^\d+(\.\d{1,2})?$/.test(v)) {
      formError.value = '授信额度需为非负数且最多两位小数'
      return
    }
    creditLimit = v
  }

  submitting.value = true
  try {
    const extras = {
      includeInTotal: formIncludeInTotal.value,
      hidden: formHidden.value,
      note: formNote.value.trim() || null,
      creditLimit,
    }
    if (isEditing.value && editingId.value != null) {
      await updateAccount(editingId.value, { name: formName.value.trim(), type: formType.value, ...extras })
    } else {
      await createAccount({
        name: formName.value.trim(),
        type: formType.value,
        initialBalance: formBalance.value.trim(),
        ...extras,
      })
    }
    showForm.value = false
    await load()
  } catch (e) {
    formError.value = toAccountErrorMessage(e)
  } finally {
    submitting.value = false
  }
}

// === 删除 ===
const deleteTarget = ref<Account | null>(null)
const deleteError = ref('')
const deleting = ref(false)
function askDelete(acc: Account) {
  deleteTarget.value = acc
  deleteError.value = ''
}
/** 从编辑表单里发起删除：先关表单再弹确认，避免弹窗叠加。 */
function deleteFromEdit() {
  const acc = accounts.value.find((a) => a.id === editingId.value)
  if (!acc) return
  showForm.value = false
  askDelete(acc)
}
function cancelDelete() {
  deleteTarget.value = null
  deleteError.value = ''
}
async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  deleteError.value = ''
  try {
    await deleteAccount(deleteTarget.value.id)
    deleteTarget.value = null
    await load()
  } catch (e) {
    deleteError.value = toAccountErrorMessage(e)
  } finally {
    deleting.value = false
  }
}

function isNegative(balance: string): boolean {
  return Number(balance) < 0
}

// =========================== 借贷往来 ===========================
const loans = ref<LoanList | null>(null)
const showLoans = ref(false) // 借贷管理抽屉
function openLoans() {
  showLoans.value = true
}
function closeLoans() {
  showLoans.value = false
}

// 借贷新增/编辑表单
const showLoanForm = ref(false)
const loanEditingId = ref<number | null>(null)
const loanDirection = ref<LoanDirection>('BORROW')
const loanCounterparty = ref('')
const loanAmount = ref('')
const loanNote = ref('')
const loanError = ref('')
const loanSubmitting = ref(false)
const isLoanEditing = computed(() => loanEditingId.value != null)

function openLoanCreate(direction: LoanDirection) {
  loanEditingId.value = null
  loanDirection.value = direction
  loanCounterparty.value = ''
  loanAmount.value = ''
  loanNote.value = ''
  loanError.value = ''
  showLoanForm.value = true
}
function openLoanEdit(loan: Loan) {
  loanEditingId.value = loan.id
  loanDirection.value = loan.direction
  loanCounterparty.value = loan.counterparty
  loanAmount.value = loan.amount
  loanNote.value = loan.note ?? ''
  loanError.value = ''
  showLoanForm.value = true
}
function closeLoanForm() {
  showLoanForm.value = false
}

async function reloadLoans() {
  try {
    loans.value = await fetchLoans()
  } catch {
    /* 借贷刷新失败不阻断，保留现有 */
  }
}

async function onLoanSubmit() {
  loanError.value = ''
  const cpErr = validateCounterparty(loanCounterparty.value)
  if (cpErr) {
    loanError.value = cpErr
    return
  }
  const amtErr = validateAmount(loanAmount.value)
  if (amtErr) {
    loanError.value = amtErr
    return
  }
  if (loanNote.value.length > 200) {
    loanError.value = '备注最多 200 个字符'
    return
  }
  loanSubmitting.value = true
  try {
    const payload = {
      direction: loanDirection.value,
      counterparty: loanCounterparty.value.trim(),
      amount: loanAmount.value.trim(),
      note: loanNote.value.trim() || null,
    }
    if (isLoanEditing.value && loanEditingId.value != null) {
      await updateLoan(loanEditingId.value, payload)
    } else {
      await createLoan(payload)
    }
    showLoanForm.value = false
    await reloadLoans()
  } catch (e) {
    loanError.value = toLoanErrorMessage(e)
  } finally {
    loanSubmitting.value = false
  }
}

/** 切换结清状态。 */
async function onToggleSettle(loan: Loan) {
  try {
    await settleLoan(loan.id, !loan.settled)
    await reloadLoans()
  } catch {
    /* 失败静默，下次刷新纠正 */
  }
}

async function onDeleteLoan(loan: Loan) {
  try {
    await deleteLoan(loan.id)
    await reloadLoans()
  } catch {
    /* 失败静默 */
  }
}

/** 借贷发生日期短标签。 */
function loanDate(iso: string): string {
  const d = new Date(iso)
  if (Number.isNaN(d.getTime())) return ''
  return `${d.getMonth() + 1}月${d.getDate()}日`
}
</script>

<template>
  <section class="assets">
    <header class="head">
      <h1>资产</h1>
      <RouterLink class="export-link" to="/export">数据导出</RouterLink>
    </header>

    <div v-if="loadError" class="banner banner-err" role="alert">
      <span>{{ loadError }}</span>
      <button class="link-btn" type="button" @click="load">重试</button>
    </div>

    <p v-if="loading && !loaded" class="text-muted loading">加载中…</p>

    <template v-if="loaded">
      <!-- 净资产 Hero -->
      <div class="hero">
        <div class="hero-lbl">
          净资产
          <button type="button" class="eye" :aria-label="hideAmounts ? '显示金额' : '隐藏金额'" @click="toggleHide">
            {{ hideAmounts ? '🙈' : '👁' }}
          </button>
        </div>
        <div class="hero-net num" :class="{ neg: isNegative(netAssets) }">{{ money(netAssets) }}</div>
        <div class="hero-row">
          <div class="cell"><div class="k">总资产</div><div class="v num">{{ money(totalAssets) }}</div></div>
          <div class="cell r"><div class="k">总负债</div><div class="v num">{{ money(totalLiabilities) }}</div></div>
        </div>
      </div>

      <!-- 借贷往来汇总（点开管理） -->
      <button v-if="loans" type="button" class="loan-row card" @click="openLoans">
        <div class="lr-cell">
          <div class="lr-k">借入 / 待还</div>
          <div class="lr-v num" :class="{ hot: Number(loans.borrowOutstanding) > 0 }">
            {{ money(loans.borrowOutstanding) }}
          </div>
        </div>
        <div class="lr-div"></div>
        <div class="lr-cell">
          <div class="lr-k">借出 / 待收</div>
          <div class="lr-v num" :class="{ hot: Number(loans.lendOutstanding) > 0 }">
            {{ money(loans.lendOutstanding) }}
          </div>
        </div>
        <span class="lr-chev">›</span>
      </button>

      <p v-if="accounts.length === 0" class="card text-muted empty">
        还没有账户，点下方「添加账户」创建一个吧。
      </p>

      <template v-else>
        <!-- 资金账户 -->
        <div v-if="fundAccounts.length" class="group card">
          <button type="button" class="g-head" @click="collapsedFund = !collapsedFund">
            <span class="gt">资金账户</span>
            <span class="g-right">
              <span class="sub num" :class="{ neg: isNegative(fundSubtotal) }">{{ money(fundSubtotal) }}</span>
              <span class="chev">{{ collapsedFund ? '▸' : '▾' }}</span>
            </span>
          </button>
          <template v-if="!collapsedFund">
            <button
              v-for="acc in fundAccounts"
              :key="acc.id"
              type="button"
              class="acc"
              @click="openEdit(acc)"
            >
              <span class="ic" :style="{ background: ACCOUNT_ICON[acc.type].tint }">{{ ACCOUNT_ICON[acc.type].emoji }}</span>
              <span class="nm">
                {{ acc.name }}
                <small>
                  {{ ACCOUNT_TYPE_LABELS[acc.type] }}
                  <em v-if="acc.hidden" class="tag">已隐藏</em>
                  <em v-if="!acc.includeInTotal" class="tag">不计入</em>
                </small>
              </span>
              <span class="bal num" :class="{ neg: isNegative(acc.currentBalance) }">{{ money(acc.currentBalance) }}</span>
            </button>
          </template>
        </div>

        <!-- 信贷账户 -->
        <div v-if="creditAccounts.length" class="group card">
          <button type="button" class="g-head" @click="collapsedCredit = !collapsedCredit">
            <span class="gt">信贷账户</span>
            <span class="g-right">
              <span class="sub num" :class="{ neg: isNegative(creditSubtotal) }">{{ money(creditSubtotal) }}</span>
              <span class="chev">{{ collapsedCredit ? '▸' : '▾' }}</span>
            </span>
          </button>
          <template v-if="!collapsedCredit">
            <button
              v-for="acc in creditAccounts"
              :key="acc.id"
              type="button"
              class="acc"
              @click="openEdit(acc)"
            >
              <span class="ic" :style="{ background: ACCOUNT_ICON[acc.type].tint }">{{ ACCOUNT_ICON[acc.type].emoji }}</span>
              <span class="nm">
                {{ acc.name }}
                <small>
                  {{ ACCOUNT_TYPE_LABELS[acc.type] }}
                  <em v-if="acc.hidden" class="tag">已隐藏</em>
                  <em v-if="!acc.includeInTotal" class="tag">不计入</em>
                </small>
              </span>
              <span class="bal-wrap">
                <span class="bal num" :class="{ neg: isNegative(acc.currentBalance) }">{{ money(acc.currentBalance) }}</span>
                <small v-if="availableCredit(acc) != null" class="avail">可用 {{ money(availableCredit(acc)!) }}</small>
              </span>
            </button>
          </template>
        </div>

        <!-- 投资理财 -->
        <div v-if="investAccounts.length" class="group card">
          <button type="button" class="g-head" @click="collapsedInvest = !collapsedInvest">
            <span class="gt">投资理财</span>
            <span class="g-right">
              <span class="sub num" :class="{ neg: isNegative(investSubtotal) }">{{ money(investSubtotal) }}</span>
              <span class="chev">{{ collapsedInvest ? '▸' : '▾' }}</span>
            </span>
          </button>
          <template v-if="!collapsedInvest">
            <button
              v-for="acc in investAccounts"
              :key="acc.id"
              type="button"
              class="acc"
              @click="openEdit(acc)"
            >
              <span class="ic" :style="{ background: ACCOUNT_ICON[acc.type].tint }">{{ ACCOUNT_ICON[acc.type].emoji }}</span>
              <span class="nm">
                {{ acc.name }}
                <small>
                  {{ ACCOUNT_TYPE_LABELS[acc.type] }}
                  <em v-if="acc.hidden" class="tag">已隐藏</em>
                  <em v-if="!acc.includeInTotal" class="tag">不计入</em>
                </small>
              </span>
              <span class="bal num" :class="{ neg: isNegative(acc.currentBalance) }">{{ money(acc.currentBalance) }}</span>
            </button>
          </template>
        </div>
      </template>

      <button class="btn btn-block add-btn" type="button" @click="openCreate">＋ 添加账户</button>
    </template>

    <!-- 新增 / 编辑表单 -->
    <div v-if="showForm" class="modal-mask" @click.self="closeForm">
      <div class="modal" role="dialog" aria-modal="true" :aria-label="isEditing ? '编辑账户' : '添加账户'">
        <header class="modal-head">
          <button class="txt-btn" type="button" @click="closeForm">取消</button>
          <h2>{{ isEditing ? '编辑账户' : '添加账户' }}</h2>
          <button class="txt-btn ok" type="button" :disabled="submitting" @click="onSubmit">
            {{ submitting ? '保存中…' : '保存' }}
          </button>
        </header>

        <div class="modal-body">
          <div class="field">
            <span class="flabel">账户类型</span>
            <div class="type-grid">
              <button
                v-for="t in ACCOUNT_TYPE_OPTIONS"
                :key="t"
                type="button"
                class="type"
                :class="{ active: formType === t }"
                @click="formType = t"
              >
                <span class="tc" :style="{ background: ACCOUNT_ICON[t].tint }">{{ ACCOUNT_ICON[t].emoji }}</span>
                <span class="tn">{{ ACCOUNT_TYPE_LABELS[t] }}</span>
              </button>
            </div>
          </div>

          <label class="field">
            <span class="flabel">账户名称 <small class="text-muted">{{ formName.length }}/50</small></span>
            <input v-model="formName" type="text" maxlength="50" placeholder="如：招商银行储蓄卡" />
          </label>

          <label v-if="!isEditing" class="field">
            <span class="flabel">账户余额</span>
            <input v-model="formBalance" type="text" inputmode="decimal" placeholder="0.00" />
            <span class="hint text-muted">信用卡等可填负数表示欠款。</span>
          </label>
          <div v-else class="field">
            <span class="flabel">账户余额</span>
            <div class="ro-balance num">¥{{ formatAmount(formCurrentBalance) }}</div>
            <span class="hint text-muted">余额由流水决定，如需修正请增删对应流水。</span>
          </div>

          <label v-if="formType === 'CREDIT_CARD'" class="field">
            <span class="flabel">授信额度 <small class="text-muted">可选</small></span>
            <input v-model="formCreditLimit" type="text" inputmode="decimal" placeholder="如：50000.00" />
            <span class="hint text-muted">填写后展示「可用余额 = 授信额度 − 已用」。</span>
          </label>

          <div class="field switch-row">
            <div>
              <div class="flabel">计入总资产</div>
              <div class="sub text-muted">关闭后，该账户余额不计入首页/资产页的净资产</div>
            </div>
            <button type="button" class="sw" :class="{ on: formIncludeInTotal }" role="switch"
              :aria-checked="formIncludeInTotal" @click="formIncludeInTotal = !formIncludeInTotal"></button>
          </div>

          <div class="field switch-row">
            <div>
              <div class="flabel">隐藏账户</div>
              <div class="sub text-muted">开启后，记账选择账户时不显示此账户（历史流水保留）</div>
            </div>
            <button type="button" class="sw" :class="{ on: formHidden }" role="switch"
              :aria-checked="formHidden" @click="formHidden = !formHidden"></button>
          </div>

          <label class="field">
            <span class="flabel">备注 <small class="text-muted">{{ formNote.length }}/200</small></span>
            <textarea v-model="formNote" maxlength="200" rows="2" placeholder="备注（可选）"></textarea>
          </label>

          <p v-if="formError" class="banner banner-err" role="alert">{{ formError }}</p>

          <button v-if="isEditing" class="del-account" type="button" @click="deleteFromEdit">
            删除该账户
          </button>
        </div>
      </div>
    </div>

    <!-- 删除确认 -->
    <div v-if="deleteTarget" class="modal-mask" @click.self="cancelDelete">
      <div class="modal small" role="dialog" aria-modal="true" aria-label="删除账户">
        <header class="modal-head">
          <span class="sp"></span>
          <h2>删除账户</h2>
          <span class="sp"></span>
        </header>
        <div class="modal-body">
          <p>确定删除「{{ deleteTarget.name }}」吗？此操作不可撤销。</p>
          <p v-if="deleteError" class="banner banner-err" role="alert">{{ deleteError }}</p>
        </div>
        <footer class="modal-foot">
          <button class="btn btn-ghost" type="button" @click="cancelDelete">取消</button>
          <button class="btn btn-danger" type="button" :disabled="deleting" @click="confirmDelete">
            {{ deleting ? '删除中…' : '删除' }}
          </button>
        </footer>
      </div>
    </div>

    <!-- 借贷管理抽屉 -->
    <div v-if="showLoans" class="modal-mask" @click.self="closeLoans">
      <div class="modal" role="dialog" aria-modal="true" aria-label="借贷往来">
        <header class="modal-head">
          <button class="txt-btn" type="button" @click="closeLoans">关闭</button>
          <h2>借贷往来</h2>
          <span class="sp"></span>
        </header>
        <div class="modal-body">
          <div class="loan-sum">
            <div class="ls-cell">
              <div class="ls-k">借入 / 待还</div>
              <div class="ls-v num">¥{{ formatAmount(loans?.borrowOutstanding ?? '0') }}</div>
            </div>
            <div class="ls-cell">
              <div class="ls-k">借出 / 待收</div>
              <div class="ls-v num">¥{{ formatAmount(loans?.lendOutstanding ?? '0') }}</div>
            </div>
          </div>

          <div class="loan-actions">
            <button type="button" class="la-btn borrow" @click="openLoanCreate('BORROW')">＋ 记一笔借入</button>
            <button type="button" class="la-btn lend" @click="openLoanCreate('LEND')">＋ 记一笔借出</button>
          </div>

          <p v-if="!loans || loans.loans.length === 0" class="text-muted loan-empty">
            还没有借贷记录。借入=别人借给你（待还），借出=你借给别人（待收）。
          </p>

          <ul v-else class="loan-list">
            <li v-for="loan in loans.loans" :key="loan.id" class="loan-item" :class="{ done: loan.settled }">
              <span class="li-dir" :class="loan.direction.toLowerCase()">
                {{ LOAN_DIRECTION_LABELS[loan.direction] }}
              </span>
              <div class="li-main" @click="openLoanEdit(loan)">
                <div class="li-cp">
                  {{ loan.counterparty }}
                  <em v-if="loan.settled" class="li-tag">已结清</em>
                </div>
                <div class="li-sub text-muted">
                  {{ loanDate(loan.occurredAt) }}<template v-if="loan.note"> · {{ loan.note }}</template>
                </div>
              </div>
              <div class="li-right">
                <span class="li-amt num">¥{{ formatAmount(loan.amount) }}</span>
                <span class="li-ops">
                  <button type="button" class="li-op" @click.stop="onToggleSettle(loan)">
                    {{ loan.settled ? '恢复' : '结清' }}
                  </button>
                  <button type="button" class="li-op del" @click.stop="onDeleteLoan(loan)">删除</button>
                </span>
              </div>
            </li>
          </ul>
        </div>
      </div>
    </div>

    <!-- 借贷新增/编辑表单 -->
    <div v-if="showLoanForm" class="modal-mask" @click.self="closeLoanForm">
      <div class="modal" role="dialog" aria-modal="true" :aria-label="isLoanEditing ? '编辑借贷' : '新增借贷'">
        <header class="modal-head">
          <button class="txt-btn" type="button" @click="closeLoanForm">取消</button>
          <h2>{{ isLoanEditing ? '编辑借贷' : '新增借贷' }}</h2>
          <button class="txt-btn ok" type="button" :disabled="loanSubmitting" @click="onLoanSubmit">
            {{ loanSubmitting ? '保存中…' : '保存' }}
          </button>
        </header>
        <div class="modal-body">
          <div class="field">
            <span class="flabel">类型</span>
            <div class="seg">
              <button
                type="button"
                class="seg-btn"
                :class="{ active: loanDirection === 'BORROW' }"
                @click="loanDirection = 'BORROW'"
              >
                借入（待还）
              </button>
              <button
                type="button"
                class="seg-btn"
                :class="{ active: loanDirection === 'LEND' }"
                @click="loanDirection = 'LEND'"
              >
                借出（待收）
              </button>
            </div>
          </div>

          <label class="field">
            <span class="flabel">对方 <small class="text-muted">{{ loanCounterparty.length }}/50</small></span>
            <input v-model="loanCounterparty" type="text" maxlength="50" placeholder="如：张三 / 公司" />
          </label>

          <label class="field">
            <span class="flabel">金额</span>
            <input v-model="loanAmount" type="text" inputmode="decimal" placeholder="0.00" />
          </label>

          <label class="field">
            <span class="flabel">备注 <small class="text-muted">{{ loanNote.length }}/200</small></span>
            <textarea v-model="loanNote" maxlength="200" rows="2" placeholder="备注（可选）"></textarea>
          </label>

          <p v-if="loanError" class="banner banner-err" role="alert">{{ loanError }}</p>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.num {
  font-variant-numeric: tabular-nums;
}
.assets {
  padding-bottom: 24px;
}
.head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 16px;
}
.head h1 {
  margin: 0;
  font-size: 22px;
}
.export-link {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-primary);
}
.loading {
  padding: 24px 0;
}

/* 净资产 Hero */
.hero {
  position: relative;
  overflow: hidden;
  border-radius: 20px;
  color: #fff;
  padding: 20px;
  margin-bottom: 16px;
  background: linear-gradient(150deg, #22c55e, #16a34a 55%, #0b6b34);
  box-shadow: 0 14px 30px rgba(22, 163, 74, 0.28);
}
.hero::after {
  content: '';
  position: absolute;
  width: 180px;
  height: 180px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.1);
  top: -80px;
  right: -40px;
  pointer-events: none;
}
.hero-lbl {
  position: relative;
  font-size: 13px;
  opacity: 0.95;
  display: flex;
  align-items: center;
  gap: 8px;
}
.hero-lbl .eye {
  border: none;
  background: none;
  cursor: pointer;
  font-size: 15px;
  line-height: 1;
}
.hero-net {
  position: relative;
  font-size: 34px;
  font-weight: 850;
  letter-spacing: -0.02em;
  margin-top: 4px;
  overflow-wrap: anywhere;
}
.hero-net.neg {
  color: #fee2e2;
}
.hero-row {
  position: relative;
  display: flex;
  justify-content: space-between;
  margin-top: 16px;
}
.hero-row .k {
  font-size: 12px;
  opacity: 0.85;
}
.hero-row .v {
  font-size: 16px;
  font-weight: 700;
  margin-top: 2px;
}
.hero-row .cell.r {
  text-align: right;
}

.empty {
  text-align: center;
}

/* 分组 */
.group {
  padding: 0;
  overflow: hidden;
  margin-bottom: 14px;
}
.g-head {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 15px 16px;
  border: none;
  background: none;
  cursor: pointer;
}
.g-head .gt {
  font-size: 15px;
  font-weight: 700;
}
.g-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.g-right .sub {
  font-size: 15px;
  font-weight: 800;
}
.g-right .sub.neg {
  color: var(--color-danger);
}
.g-right .chev {
  color: var(--color-muted);
  font-size: 12px;
}
.acc {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 13px 16px;
  border: none;
  border-top: 1px solid var(--color-border);
  background: none;
  text-align: left;
  cursor: pointer;
}
.acc:active {
  background: var(--color-bg);
}
.acc .ic {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  flex: 0 0 auto;
}
.acc .nm {
  flex: 1;
  min-width: 0;
  font-size: 15px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.acc .nm small {
  display: block;
  font-size: 12px;
  color: var(--color-muted);
  font-weight: 400;
  margin-top: 1px;
}
.acc .nm .tag {
  font-style: normal;
  margin-left: 6px;
  padding: 1px 6px;
  border-radius: 6px;
  background: var(--color-bg);
  color: var(--color-muted);
  font-size: 11px;
}
.acc .bal {
  font-size: 16px;
  font-weight: 800;
  white-space: nowrap;
}
.acc .bal.neg {
  color: var(--color-danger);
}

.add-btn {
  margin-top: 4px;
}

.link-btn {
  border: none;
  background: none;
  color: var(--color-primary);
  font-weight: 600;
  font-size: 14px;
  padding: 0;
}

/* 弹窗 */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: flex-end;
  justify-content: center;
  z-index: 50;
}
.modal {
  background: var(--color-surface);
  width: 100%;
  max-width: 520px;
  max-height: 92vh;
  display: flex;
  flex-direction: column;
  border-radius: 16px 16px 0 0;
  overflow: hidden;
}
@media (min-width: 640px) {
  .modal-mask {
    align-items: center;
    padding: 16px;
  }
  .modal {
    border-radius: 16px;
  }
}
.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--color-border);
}
.modal-head h2 {
  margin: 0;
  font-size: 16px;
  font-weight: 800;
}
.txt-btn {
  border: none;
  background: none;
  font-size: 14px;
  color: var(--color-muted);
  cursor: pointer;
}
.txt-btn.ok {
  color: var(--color-primary);
  font-weight: 700;
}
.txt-btn:disabled {
  opacity: 0.5;
}
.modal-head .sp {
  width: 28px;
}
.modal-body {
  padding: 16px;
  overflow-y: auto;
  display: grid;
  gap: 16px;
}
.field {
  display: block;
}
.flabel {
  display: block;
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
}
.field input,
.field textarea {
  width: 100%;
  min-height: 44px;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  font: inherit;
  background: var(--color-surface);
  resize: none;
}
.ro-balance {
  font-size: 22px;
  font-weight: 800;
}
.hint {
  display: block;
  margin-top: 6px;
  font-size: 13px;
}

/* 类型网格 */
.type-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px 8px;
}
.type {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  border: none;
  background: none;
  cursor: pointer;
}
.type .tc {
  width: 46px;
  height: 46px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 21px;
  border: 2px solid transparent;
}
.type.active .tc {
  border-color: var(--color-primary);
}
.type .tn {
  font-size: 12px;
  color: #4b5563;
}
.type.active .tn {
  color: var(--color-primary-dark);
  font-weight: 700;
}

/* 开关 */
.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.switch-row .sub {
  font-size: 12px;
  margin-top: 2px;
}
.sw {
  flex: 0 0 auto;
  width: 46px;
  height: 28px;
  border-radius: 999px;
  background: #d7dbe0;
  position: relative;
  border: none;
  cursor: pointer;
  transition: background 0.2s;
}
.sw.on {
  background: var(--color-primary);
}
.sw::after {
  content: '';
  position: absolute;
  top: 3px;
  left: 3px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #fff;
  transition: left 0.2s;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2);
}
.sw.on::after {
  left: 21px;
}
.del-account {
  width: 100%;
  padding: 12px 0;
  border: none;
  background: #fef2f2;
  color: var(--color-danger);
  border-radius: 12px;
  font-weight: 700;
  cursor: pointer;
}

.modal-foot {
  display: flex;
  gap: 12px;
  padding: 12px 16px calc(12px + var(--safe-bottom));
  border-top: 1px solid var(--color-border);
}
.modal-foot .btn {
  flex: 1;
}
.btn-ghost {
  background: var(--color-surface);
  color: var(--color-text);
  border: 1px solid var(--color-border);
}
.btn-danger {
  background: var(--color-danger);
}

.banner {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--radius);
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.banner-err {
  background: #fef2f2;
  color: var(--color-danger);
}

/* 余额 + 可用余额 */
.bal-wrap {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
}
.bal-wrap .avail {
  font-size: 11px;
  color: var(--color-muted);
  margin-top: 2px;
}

/* 借贷汇总行 */
.loan-row {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 14px 16px;
  margin-bottom: 14px;
  border: none;
  background: var(--color-surface);
  cursor: pointer;
  text-align: left;
}
.loan-row .lr-cell {
  flex: 1;
  min-width: 0;
}
.loan-row .lr-k {
  font-size: 12px;
  color: var(--color-muted);
}
.loan-row .lr-v {
  font-size: 17px;
  font-weight: 800;
  margin-top: 2px;
}
.loan-row .lr-v.hot {
  color: var(--color-primary-dark);
}
.loan-row .lr-div {
  width: 1px;
  align-self: stretch;
  background: var(--color-border);
  margin: 2px 12px;
}
.loan-row .lr-chev {
  color: var(--color-muted);
  font-size: 18px;
  margin-left: 6px;
}

/* 借贷抽屉 */
.loan-sum {
  display: flex;
  gap: 12px;
}
.loan-sum .ls-cell {
  flex: 1;
  padding: 12px 14px;
  border-radius: 12px;
  background: var(--color-bg);
}
.loan-sum .ls-k {
  font-size: 12px;
  color: var(--color-muted);
}
.loan-sum .ls-v {
  font-size: 18px;
  font-weight: 800;
  margin-top: 2px;
}
.loan-actions {
  display: flex;
  gap: 12px;
}
.la-btn {
  flex: 1;
  padding: 11px 0;
  border-radius: 11px;
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  font-weight: 700;
  font-size: 14px;
  cursor: pointer;
}
.la-btn.borrow {
  color: var(--color-primary-dark);
  border-color: #bbf7d0;
  background: #f0fdf4;
}
.la-btn.lend {
  color: #b45309;
  border-color: #fde68a;
  background: #fffbeb;
}
.loan-empty {
  font-size: 13px;
  line-height: 1.6;
  text-align: center;
  padding: 8px 4px;
}
.loan-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 10px;
}
.loan-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
}
.loan-item.done {
  opacity: 0.6;
}
.li-dir {
  flex: 0 0 auto;
  font-size: 12px;
  font-weight: 700;
  padding: 3px 8px;
  border-radius: 8px;
}
.li-dir.borrow {
  color: var(--color-primary-dark);
  background: #ecfdf5;
}
.li-dir.lend {
  color: #b45309;
  background: #fffbeb;
}
.li-main {
  flex: 1;
  min-width: 0;
  cursor: pointer;
}
.li-cp {
  font-size: 15px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.li-tag {
  font-style: normal;
  margin-left: 6px;
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 6px;
  background: var(--color-bg);
  color: var(--color-muted);
}
.li-sub {
  font-size: 12px;
  margin-top: 2px;
  overflow-wrap: anywhere;
}
.li-right {
  flex: 0 0 auto;
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4px;
}
.li-amt {
  font-size: 15px;
  font-weight: 800;
}
.li-ops {
  display: flex;
  gap: 6px;
}
.li-op {
  border: none;
  background: var(--color-bg);
  color: var(--color-text);
  font-size: 12px;
  padding: 3px 9px;
  border-radius: 7px;
  cursor: pointer;
}
.li-op.del {
  color: var(--color-danger);
}

/* 借贷类型分段 */
.seg {
  display: flex;
  border: 1px solid var(--color-border);
  border-radius: 11px;
  overflow: hidden;
}
.seg-btn {
  flex: 1;
  padding: 11px 0;
  border: none;
  background: var(--color-surface);
  font-size: 14px;
  font-weight: 600;
  color: var(--color-muted);
  cursor: pointer;
}
.seg-btn.active {
  background: var(--color-primary);
  color: #fff;
}
</style>
