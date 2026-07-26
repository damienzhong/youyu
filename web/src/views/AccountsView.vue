<script setup lang="ts">
/**
 * 账户管理页（任务 10.5）。
 *
 * 功能（需求 3.5/3.6/3.7）：
 *  - 列出本人账户及当前余额，展示净资产合计；信用卡负余额正确显示（带负号、红色）。
 *  - 新增账户（名称、类型、初始余额；允许信用卡负余额）。
 *  - 编辑账户（名称/类型；余额由后端保留）。
 *  - 删除账户（含二次确认；有交易时后端返回 ACCOUNT_IN_USE，展示友好提示）。
 *  - 数据导出入口（跳转 /export）。
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

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    accounts.value = await fetchAccounts()
    loaded.value = true
  } catch (e) {
    // 保留上次数据，仅提示 + 重试。
    loadError.value = e instanceof Error ? e.message : '加载失败，请重试'
  } finally {
    loading.value = false
  }
}

// === 新增 / 编辑弹窗 ===
const showForm = ref(false)
const editingId = ref<number | null>(null)
const formName = ref('')
const formType = ref<AccountType>('CASH')
const formBalance = ref('0.00')
const formError = ref('')
const submitting = ref(false)

const isEditing = computed(() => editingId.value != null)

function openCreate() {
  editingId.value = null
  formName.value = ''
  formType.value = 'CASH'
  formBalance.value = '0.00'
  formError.value = ''
  showForm.value = true
}

function openEdit(acc: Account) {
  editingId.value = acc.id
  formName.value = acc.name
  formType.value = acc.type
  formBalance.value = acc.currentBalance
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
  // 仅新增时校验初始余额（编辑不改余额）。
  if (!isEditing.value) {
    const balErr = validateInitialBalance(formBalance.value)
    if (balErr) {
      formError.value = balErr
      return
    }
  }

  submitting.value = true
  try {
    if (isEditing.value && editingId.value != null) {
      await updateAccount(editingId.value, { name: formName.value.trim(), type: formType.value })
    } else {
      await createAccount({
        name: formName.value.trim(),
        type: formType.value,
        initialBalance: formBalance.value.trim(),
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

// === 删除确认 ===
const deleteTarget = ref<Account | null>(null)
const deleteError = ref('')
const deleting = ref(false)

function askDelete(acc: Account) {
  deleteTarget.value = acc
  deleteError.value = ''
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
    // 有交易时展示 ACCOUNT_IN_USE 友好提示，弹窗保持打开。
    deleteError.value = toAccountErrorMessage(e)
  } finally {
    deleting.value = false
  }
}

/** 余额是否为负（信用卡欠款等），用于红色样式。 */
function isNegative(balance: string): boolean {
  return Number(balance) < 0
}
</script>

<template>
  <section class="accounts">
    <header class="head">
      <h1>账户</h1>
      <RouterLink class="export-link" to="/export">数据导出</RouterLink>
    </header>

    <div v-if="loadError" class="banner banner-err" role="alert">
      <span>{{ loadError }}</span>
      <button class="link-btn" type="button" @click="load">重试</button>
    </div>

    <p v-if="loading && !loaded" class="text-muted loading">加载中…</p>

    <template v-if="loaded">
      <!-- 净资产合计 -->
      <div class="card net">
        <span class="net-label">净资产</span>
        <span class="net-val" :class="{ neg: isNegative(netAssets) }">¥{{ formatAmount(netAssets) }}</span>
      </div>

      <p v-if="accounts.length === 0" class="card text-muted empty">
        还没有账户，点下方「新增账户」创建一个吧。
      </p>

      <ul v-else class="acc-list card">
        <li v-for="acc in accounts" :key="acc.id" class="acc-item">
          <div class="acc-info">
            <div class="acc-name">{{ acc.name }}</div>
            <div class="acc-type text-muted">{{ ACCOUNT_TYPE_LABELS[acc.type] }}</div>
          </div>
          <div class="acc-right">
            <span class="acc-balance" :class="{ neg: isNegative(acc.currentBalance) }">
              ¥{{ formatAmount(acc.currentBalance) }}
            </span>
            <div class="acc-actions">
              <button class="link-btn" type="button" @click="openEdit(acc)">编辑</button>
              <button class="link-btn danger" type="button" @click="askDelete(acc)">删除</button>
            </div>
          </div>
        </li>
      </ul>

      <button class="btn btn-block add-btn" type="button" @click="openCreate">新增账户</button>
    </template>

    <!-- 新增 / 编辑弹窗 -->
    <div v-if="showForm" class="modal-mask" @click.self="closeForm">
      <div class="modal" role="dialog" aria-modal="true" :aria-label="isEditing ? '编辑账户' : '新增账户'">
        <header class="modal-head">
          <h2>{{ isEditing ? '编辑账户' : '新增账户' }}</h2>
          <button class="icon-btn" type="button" aria-label="关闭" @click="closeForm">✕</button>
        </header>

        <div class="modal-body">
          <label class="field">
            <span>名称</span>
            <input v-model="formName" type="text" maxlength="50" placeholder="如：招商银行储蓄卡" />
          </label>

          <label class="field">
            <span>类型</span>
            <select v-model="formType">
              <option v-for="t in ACCOUNT_TYPE_OPTIONS" :key="t" :value="t">
                {{ ACCOUNT_TYPE_LABELS[t] }}
              </option>
            </select>
          </label>

          <label v-if="!isEditing" class="field">
            <span>初始余额</span>
            <input v-model="formBalance" type="text" inputmode="decimal" placeholder="0.00" />
            <span class="hint text-muted">信用卡等可填负数表示欠款。</span>
          </label>
          <p v-else class="hint text-muted keep-balance">
            当前余额 ¥{{ formatAmount(formBalance) }}，编辑名称/类型不会改变余额。
          </p>

          <p v-if="formError" class="banner banner-err" role="alert">{{ formError }}</p>
        </div>

        <footer class="modal-foot">
          <button class="btn btn-ghost" type="button" @click="closeForm">取消</button>
          <button class="btn" type="button" :disabled="submitting" @click="onSubmit">
            {{ submitting ? '保存中…' : '保存' }}
          </button>
        </footer>
      </div>
    </div>

    <!-- 删除确认弹窗 -->
    <div v-if="deleteTarget" class="modal-mask" @click.self="cancelDelete">
      <div class="modal" role="dialog" aria-modal="true" aria-label="删除账户">
        <header class="modal-head">
          <h2>删除账户</h2>
          <button class="icon-btn" type="button" aria-label="关闭" @click="cancelDelete">✕</button>
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
.accounts {
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
  color: var(--color-primary);
}
.export-link {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-primary);
}
.loading {
  padding: 24px 0;
}

.net {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.net-label {
  font-size: 14px;
  color: var(--color-muted);
}
.net-val {
  font-size: 22px;
  font-weight: 700;
  overflow-wrap: anywhere;
}
.net-val.neg {
  color: var(--color-danger);
}

.empty {
  text-align: center;
}

.acc-list {
  list-style: none;
  margin: 0 0 16px;
  padding: 0;
}
.acc-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--color-border);
}
.acc-item:last-child {
  border-bottom: none;
}
.acc-info {
  min-width: 0;
  flex: 1;
}
.acc-name {
  font-size: 15px;
  font-weight: 600;
  overflow-wrap: anywhere;
}
.acc-type {
  font-size: 13px;
  margin-top: 2px;
}
.acc-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}
.acc-balance {
  font-size: 16px;
  font-weight: 700;
  white-space: nowrap;
}
.acc-balance.neg {
  color: var(--color-danger);
}
.acc-actions {
  display: flex;
  gap: 12px;
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
.link-btn.danger {
  color: var(--color-danger);
}

/* 弹窗（与 TransactionEditModal 一致的底部抽屉 → 桌面居中）。 */
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
  max-height: 90vh;
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
  padding: 16px;
  border-bottom: 1px solid var(--color-border);
}
.modal-head h2 {
  margin: 0;
  font-size: 17px;
}
.icon-btn {
  border: none;
  background: none;
  font-size: 18px;
  color: var(--color-muted);
  min-width: 32px;
  min-height: 32px;
}
.modal-body {
  padding: 16px;
  overflow-y: auto;
  display: grid;
  gap: 14px;
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
.btn-ghost:active {
  background: #f1f5f2;
}
.btn-danger {
  background: var(--color-danger);
}
.btn-danger:active {
  background: #b91c1c;
}
.field {
  display: block;
}
.field > span {
  display: block;
  margin-bottom: 6px;
  font-size: 14px;
  color: var(--color-muted);
}
.field input,
.field select {
  width: 100%;
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  font: inherit;
  background: var(--color-surface);
}
.hint {
  font-size: 13px;
}
.hint.keep-balance {
  margin: 0;
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
