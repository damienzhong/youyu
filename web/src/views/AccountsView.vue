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
  type Account,
  type AccountType,
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
}

// 分组：资金账户 vs 信贷账户。
const fundAccounts = computed(() => accounts.value.filter((a) => a.type !== 'CREDIT_CARD'))
const creditAccounts = computed(() => accounts.value.filter((a) => a.type === 'CREDIT_CARD'))
function sumOf(list: Account[]): string {
  const cents = list.reduce((acc, a) => acc + Math.round(Number(a.currentBalance) * 100), 0)
  return (cents / 100).toFixed(2)
}
const fundSubtotal = computed(() => sumOf(fundAccounts.value))
const creditSubtotal = computed(() => sumOf(creditAccounts.value))

const collapsedFund = ref(false)
const collapsedCredit = ref(false)

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    accounts.value = await fetchAccounts()
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

  submitting.value = true
  try {
    const extras = {
      includeInTotal: formIncludeInTotal.value,
      hidden: formHidden.value,
      note: formNote.value.trim() || null,
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
</style>
