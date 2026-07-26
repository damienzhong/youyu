<script setup lang="ts">
/**
 * 记一笔（QuickEntry）—— 3 秒 / 3 次点击的快速记账入口。
 *
 * 设计要点（见 design「快速记账流程」，需求 6.1–6.7）：
 *  - 首屏即呈现数字键盘、支出/收入/转账切换与分类选择。
 *  - 默认账户：优先「上次记账账户」（defaults 缓存），否则账户列表排序第一（6.1/6.2）。
 *  - 交易时间预填当前系统时间（精确到分钟），按北京时间（UTC+8）落库。
 *  - 主路径点击预算：账户/时间已预填 → 选分类（1 次）→ 提交（1 次），不含金额键入（6.3）。
 *  - 校验：金额 0.01–999,999,999.99 且最多两位小数、支出/收入分类必选、转账源≠目标；
 *    校验或网络失败时阻止提交、显示具体原因、保留全部输入（6.6/6.7）。
 *  - 成功：1 秒内成功反馈，并记住本次账户作为下次默认（6.5）。
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useDefaultsStore } from '@/stores/defaults'
import {
  fetchAccounts,
  fetchCategories,
  createTransaction,
  validateAmount,
  toEntryErrorMessage,
  ACCOUNT_TYPE_LABELS,
  type Account,
  type Category,
  type CategoryKind,
  type TransactionType,
  type CreateTransactionPayload,
} from '@/lib/ledger'

const defaults = useDefaultsStore()

// === 数据加载 ===
const loading = ref(true)
const loadError = ref('')
const accounts = ref<Account[]>([])
const categories = ref<Category[]>([])

// === 表单状态 ===
const type = ref<TransactionType>('expense')
const amount = ref('')
const note = ref('')
const occurredAt = ref(nowForInput())
const accountId = ref<number | null>(null) // 支出/收入账户，或转账「转出」账户
const destinationAccountId = ref<number | null>(null) // 转账「转入」账户
const categoryId = ref<number | null>(null)

const submitting = ref(false)
const errorMsg = ref('')
const successMsg = ref('')

// === 分类：按当前类型（支出/收入）过滤并按父级分组 ===
const categoryKind = computed<CategoryKind>(() => (type.value === 'income' ? 'INCOME' : 'EXPENSE'))
const visibleCategories = computed(() => categories.value.filter((c) => c.kind === categoryKind.value))

interface CategoryGroup {
  parent: Category
  options: Category[]
}
const categoryGroups = computed<CategoryGroup[]>(() => {
  const list = visibleCategories.value
  const parents = list.filter((c) => c.parentId == null)
  const childrenByParent = new Map<number, Category[]>()
  for (const c of list) {
    if (c.parentId != null) {
      const arr = childrenByParent.get(c.parentId) ?? []
      arr.push(c)
      childrenByParent.set(c.parentId, arr)
    }
  }
  return parents.map((p) => {
    const children = childrenByParent.get(p.id)
    // 有子分类则选子分类；否则父分类本身作为可选项。
    return { parent: p, options: children && children.length ? children : [p] }
  })
})

const selectedCategoryName = computed(() => {
  const c = visibleCategories.value.find((x) => x.id === categoryId.value)
  return c ? c.name : ''
})

const isTransfer = computed(() => type.value === 'transfer')

// === 生命周期 ===
onMounted(load)
onUnmounted(() => {
  if (successTimer) clearTimeout(successTimer)
})

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    const [accs, cats] = await Promise.all([fetchAccounts(), fetchCategories()])
    accounts.value = accs
    categories.value = cats
    occurredAt.value = nowForInput()
    initDefaults()
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败，请重试'
  } finally {
    loading.value = false
  }
}

/** 预选默认账户：上次记账账户优先，否则排序第一（6.1/6.2）。 */
function initDefaults() {
  const firstAccount = accounts.value[0]
  if (!firstAccount) return
  const last = defaults.lastAccountId
  const useLast = last != null && accounts.value.some((a) => a.id === last)
  const first = useLast ? (last as number) : firstAccount.id
  accountId.value = first
  const other = accounts.value.find((a) => a.id !== first)
  destinationAccountId.value = other ? other.id : null
}

// === 类型切换 ===
function setType(t: TransactionType) {
  if (type.value === t) return
  type.value = t
  categoryId.value = null // 支出/收入分类相互独立，切换后重选
  errorMsg.value = ''
  if (t === 'transfer' && destinationAccountId.value === accountId.value) {
    const other = accounts.value.find((a) => a.id !== accountId.value)
    destinationAccountId.value = other ? other.id : null
  }
}

// === 数字键盘 ===
function tapDigit(d: string) {
  errorMsg.value = ''
  if (amount.value.includes('.')) {
    const dec = amount.value.split('.')[1] ?? ''
    if (dec.length >= 2) return // 最多两位小数
  } else if (amount.value.replace('.', '').length >= 9) {
    return // 整数位最多 9 位（上限 999,999,999）
  }
  if (amount.value === '0') {
    amount.value = d // 避免前导零
    return
  }
  amount.value += d
}

function tapDot() {
  errorMsg.value = ''
  if (amount.value.includes('.')) return
  amount.value = amount.value === '' ? '0.' : amount.value + '.'
}

function tapDelete() {
  errorMsg.value = ''
  amount.value = amount.value.slice(0, -1)
}

function selectCategory(id: number) {
  categoryId.value = id
  errorMsg.value = ''
}

// === 提交 ===
async function onSubmit() {
  errorMsg.value = ''

  const amountErr = validateAmount(amount.value)
  if (amountErr) {
    errorMsg.value = amountErr
    return
  }
  if (accountId.value == null) {
    errorMsg.value = isTransfer.value ? '请选择转出账户' : '请选择账户'
    return
  }

  let payload: CreateTransactionPayload
  if (isTransfer.value) {
    if (destinationAccountId.value == null) {
      errorMsg.value = '请选择转入账户'
      return
    }
    if (destinationAccountId.value === accountId.value) {
      errorMsg.value = '转出与转入账户不能相同'
      return
    }
    payload = {
      type: 'transfer',
      amount: amount.value.trim(),
      sourceAccountId: accountId.value,
      destinationAccountId: destinationAccountId.value,
      occurredAt: toOccurredAt(occurredAt.value),
      note: note.value.trim() || undefined,
    }
  } else {
    if (categoryId.value == null) {
      errorMsg.value = '请选择分类'
      return
    }
    payload = {
      type: type.value === 'income' ? 'income' : 'expense',
      amount: amount.value.trim(),
      accountId: accountId.value,
      categoryId: categoryId.value,
      occurredAt: toOccurredAt(occurredAt.value),
      note: note.value.trim() || undefined,
    }
  }

  submitting.value = true
  try {
    await createTransaction(payload)
    defaults.rememberAccount(accountId.value) // 记住本次账户为下次默认（6.5）
    showSuccess()
    resetForNext()
  } catch (e) {
    // 系统/网络错误：提示失败并保留全部输入，不清空（6.7）。
    errorMsg.value = toEntryErrorMessage(e)
  } finally {
    submitting.value = false
  }
}

let successTimer: ReturnType<typeof setTimeout> | undefined
function showSuccess() {
  successMsg.value = '记账成功'
  if (successTimer) clearTimeout(successTimer)
  successTimer = setTimeout(() => {
    successMsg.value = ''
  }, 1000)
}

/** 成功后为下一笔准备：清金额/分类/备注、刷新时间，保留账户与类型。 */
function resetForNext() {
  amount.value = ''
  categoryId.value = null
  note.value = ''
  occurredAt.value = nowForInput()
}

// === 时间辅助 ===
function pad(n: number): string {
  return String(n).padStart(2, '0')
}
/** 当前本地时间，格式化为 datetime-local 值（精确到分钟）。 */
function nowForInput(): string {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}
/** 把 datetime-local 值视作北京时间（UTC+8），补秒与时区偏移用于落库。 */
function toOccurredAt(local: string): string {
  return `${local}:00+08:00`
}
</script>

<template>
  <section class="quick">
    <h1 class="sr-title">记一笔</h1>

    <!-- 加载失败：提示 + 重试（需求 11.5，保留上次数据） -->
    <div v-if="loadError" class="banner banner-err" role="alert">
      <span>{{ loadError }}</span>
      <button class="link-btn" type="button" @click="load">重试</button>
    </div>

    <p v-if="loading" class="text-muted loading">加载中…</p>

    <template v-else>
      <!-- 无账户引导 -->
      <div v-if="accounts.length === 0" class="card empty">
        <p>还没有账户，先去添加一个账户再记账吧。</p>
        <RouterLink class="btn" to="/accounts">去添加账户</RouterLink>
      </div>

      <template v-else>
        <!-- 类型切换 -->
        <div class="type-toggle" role="tablist" aria-label="交易类型">
          <button
            type="button"
            role="tab"
            class="type-btn"
            :class="{ active: type === 'expense' }"
            :aria-selected="type === 'expense'"
            @click="setType('expense')"
          >
            支出
          </button>
          <button
            type="button"
            role="tab"
            class="type-btn"
            :class="{ active: type === 'income' }"
            :aria-selected="type === 'income'"
            @click="setType('income')"
          >
            收入
          </button>
          <button
            type="button"
            role="tab"
            class="type-btn"
            :class="{ active: type === 'transfer' }"
            :aria-selected="type === 'transfer'"
            @click="setType('transfer')"
          >
            转账
          </button>
        </div>

        <!-- 金额显示 -->
        <div class="amount-display" :class="{ empty: !amount }">
          <span class="currency">¥</span>
          <span class="amount-value">{{ amount || '0' }}</span>
        </div>

        <!-- 账户选择：支出/收入单账户；转账为「转出 → 转入」 -->
        <div class="section">
          <div class="section-label">{{ isTransfer ? '转出账户' : '账户' }}</div>
          <div class="chips">
            <button
              v-for="a in accounts"
              :key="a.id"
              type="button"
              class="chip"
              :class="{ active: accountId === a.id }"
              @click="accountId = a.id"
            >
              {{ a.name }}
              <small class="chip-sub">{{ ACCOUNT_TYPE_LABELS[a.type] }}</small>
            </button>
          </div>
        </div>

        <div v-if="isTransfer" class="section">
          <div class="section-label">转入账户</div>
          <div class="chips">
            <button
              v-for="a in accounts"
              :key="a.id"
              type="button"
              class="chip"
              :class="{ active: destinationAccountId === a.id, disabled: a.id === accountId }"
              :disabled="a.id === accountId"
              @click="destinationAccountId = a.id"
            >
              {{ a.name }}
              <small class="chip-sub">{{ ACCOUNT_TYPE_LABELS[a.type] }}</small>
            </button>
          </div>
        </div>

        <!-- 分类选择：仅支出/收入 -->
        <div v-if="!isTransfer" class="section">
          <div class="section-label">
            分类
            <span v-if="selectedCategoryName" class="section-hint">已选：{{ selectedCategoryName }}</span>
          </div>
          <p v-if="categoryGroups.length === 0" class="text-muted small">
            还没有{{ type === 'income' ? '收入' : '支出' }}分类，<RouterLink to="/categories">去添加</RouterLink>。
          </p>
          <div v-for="g in categoryGroups" :key="g.parent.id" class="cat-group">
            <div class="cat-parent">{{ g.parent.name }}</div>
            <div class="chips">
              <button
                v-for="opt in g.options"
                :key="opt.id"
                type="button"
                class="chip"
                :class="{ active: categoryId === opt.id }"
                @click="selectCategory(opt.id)"
              >
                {{ opt.name }}
              </button>
            </div>
          </div>
        </div>

        <!-- 时间与备注 -->
        <div class="section grid-2">
          <label class="field">
            <span>时间</span>
            <input v-model="occurredAt" type="datetime-local" />
          </label>
          <label class="field">
            <span>备注（可选）</span>
            <input v-model="note" type="text" maxlength="200" placeholder="备注" />
          </label>
        </div>

        <!-- 反馈条 -->
        <p v-if="errorMsg" class="banner banner-err" role="alert">{{ errorMsg }}</p>
        <p v-if="successMsg" class="banner banner-ok" role="status">{{ successMsg }}</p>

        <!-- 数字键盘 -->
        <div class="keypad" role="group" aria-label="数字键盘">
          <button v-for="d in ['1','2','3','4','5','6','7','8','9']" :key="d" type="button" class="key" @click="tapDigit(d)">
            {{ d }}
          </button>
          <button type="button" class="key" @click="tapDot()">.</button>
          <button type="button" class="key" @click="tapDigit('0')">0</button>
          <button type="button" class="key key-del" aria-label="删除" @click="tapDelete()">⌫</button>
        </div>

        <button class="btn btn-block submit" type="button" :disabled="submitting" @click="onSubmit">
          {{ submitting ? '提交中…' : '保存' }}
        </button>
      </template>
    </template>
  </section>
</template>

<style scoped>
.quick {
  padding-bottom: 24px;
}
.sr-title {
  font-size: 20px;
  margin: 0 0 12px;
}
.loading {
  padding: 24px 0;
}
.empty {
  text-align: center;
  display: grid;
  gap: 12px;
  justify-items: center;
}

.type-toggle {
  display: flex;
  gap: 6px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  padding: 4px;
  margin-bottom: 16px;
}
.type-btn {
  flex: 1;
  min-height: 40px;
  border: none;
  border-radius: 9px;
  background: transparent;
  color: var(--color-muted);
  font-weight: 600;
}
.type-btn.active {
  background: var(--color-primary);
  color: #fff;
}

.amount-display {
  display: flex;
  align-items: baseline;
  gap: 6px;
  padding: 12px 4px 16px;
  border-bottom: 2px solid var(--color-primary);
  margin-bottom: 16px;
}
.amount-display.empty .amount-value {
  color: var(--color-muted);
}
.currency {
  font-size: 22px;
  color: var(--color-muted);
}
.amount-value {
  font-size: 40px;
  font-weight: 700;
  line-height: 1;
  overflow-wrap: anywhere;
}

.section {
  margin-bottom: 16px;
}
.section-label {
  font-size: 14px;
  color: var(--color-muted);
  margin-bottom: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}
.section-hint {
  color: var(--color-primary);
  font-weight: 600;
}
.small {
  font-size: 13px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 8px 12px;
  min-height: 40px;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: var(--color-surface);
  color: var(--color-text);
  font-size: 14px;
}
.chip.active {
  border-color: var(--color-primary);
  background: #ecfdf5;
  color: var(--color-primary-dark);
  font-weight: 600;
}
.chip.disabled,
.chip:disabled {
  opacity: 0.4;
}
.chip-sub {
  font-size: 11px;
  color: var(--color-muted);
}

.cat-group {
  margin-bottom: 10px;
}
.cat-parent {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}

.grid-2 {
  display: grid;
  gap: 12px;
  grid-template-columns: 1fr;
}
@media (min-width: 520px) {
  .grid-2 {
    grid-template-columns: 1fr 1fr;
  }
}
.field {
  display: block;
}
.field span {
  display: block;
  margin-bottom: 6px;
  font-size: 14px;
  color: var(--color-muted);
}
.field input {
  width: 100%;
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  font: inherit;
  background: var(--color-surface);
}

.banner {
  margin: 0 0 14px;
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
.banner-ok {
  background: #ecfdf5;
  color: var(--color-primary-dark);
}
.link-btn {
  border: none;
  background: none;
  color: inherit;
  text-decoration: underline;
  font-weight: 600;
}

.keypad {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
  margin-bottom: 16px;
}
.key {
  min-height: 56px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
  font-size: 22px;
  font-weight: 600;
}
.key:active {
  background: #f1f5f2;
}
.key-del {
  color: var(--color-danger);
}

.submit {
  min-height: 52px;
  font-size: 17px;
}
</style>
