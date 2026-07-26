<script setup lang="ts">
/**
 * 记一笔（QuickEntry）—— 响应式：移动端全屏计算器 / PC 首页风格两栏卡片。
 *
 * - 移动端（<768px）：全屏页（AppShell 隐藏底部 tab），顶部文字 tab + 分类彩色图标网格 +
 *   金额行 + 计算器键盘（数字 / 加减 / ⌫，保存再记 / 完成），键盘钉底。
 * - PC（≥768px）：沿用 AppShell 左侧边栏与首页容器；内容为标题 + 类型分段 + 两栏卡片
 *   （左：选择分类/转账账户；右：金额输入框 + 账户/日期/备注 + 保存再记/完成）。PC 用物理
 *   键盘输入金额，无数字键盘。
 *
 * 需求映射（design「快速记账流程」，6.1–6.7）：默认账户记忆、时间预填（UTC+8 落库）、
 * 金额 0.01–999,999,999.99 两位小数、分类/转账账户校验、失败保留输入、1 秒成功反馈、
 * 保存再记保留类型/账户/分类继续记、完成记完退出。
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { RouterLink, useRouter } from 'vue-router'
import { useDefaultsStore } from '@/stores/defaults'
import {
  fetchAccounts,
  fetchCategories,
  createTransaction,
  validateAmount,
  toEntryErrorMessage,
  categoryEmoji,
  formatAmount,
  ACCOUNT_TYPE_LABELS,
  type Account,
  type AccountType,
  type Category,
  type CategoryKind,
  type TransactionType,
  type CreateTransactionPayload,
} from '@/lib/ledger'

const router = useRouter()
const defaults = useDefaultsStore()

// 响应式断点：≥768px 走 PC 布局。
const isDesktop = ref(false)
let mq: MediaQueryList | null = null
function onMqChange() {
  isDesktop.value = mq?.matches ?? false
}

// 日期输入引用：桌面浏览器需主动调用 showPicker() 才能弹出原生日历。
const dateInput = ref<HTMLInputElement | null>(null)
function openDatePicker() {
  const el = dateInput.value
  if (el && typeof el.showPicker === 'function') {
    try {
      el.showPicker()
      return
    } catch {
      /* 退回 focus */
    }
  }
  el?.focus()
}

// === 数据加载 ===
const loading = ref(true)
const loadError = ref('')
const accounts = ref<Account[]>([])
const categories = ref<Category[]>([])

// === 表单状态 ===
const type = ref<TransactionType>('expense')
const note = ref('')
const occurredAt = ref(nowForInput())
const accountId = ref<number | null>(null)
const destinationAccountId = ref<number | null>(null)
const categoryId = ref<number | null>(null)

const submitting = ref(false)
const errorMsg = ref('')
const successMsg = ref('')

// === 计算器状态：acc（已累计）+ op（挂起运算符）+ operand（当前操作数字符串） ===
const acc = ref<number | null>(null)
const op = ref<'+' | '-' | null>(null)
const operand = ref('')

const displayValue = computed(() => {
  if (operand.value !== '') return operand.value
  if (op.value != null) return '0'
  if (acc.value != null) return trimNum(acc.value)
  return '0'
})
const exprHint = computed(() => (op.value && acc.value != null ? `${trimNum(acc.value)} ${op.value}` : ''))

function evaluate(): number {
  const cur = operand.value === '' ? null : Number(operand.value)
  if (acc.value == null) return cur ?? 0
  if (cur == null || op.value == null) return acc.value
  return op.value === '-' ? acc.value - cur : acc.value + cur
}
const amountForSubmit = computed(() => (Math.round(evaluate() * 100) / 100).toFixed(2))
const hasAmountInput = computed(() => operand.value !== '' || acc.value != null)

function trimNum(n: number): string {
  return n.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
}

// === 分类 ===
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
    return { parent: p, options: children && children.length ? children : [p] }
  })
})

const CAT_TINTS = [
  '#e9f7ef',
  '#e8f0fe',
  '#fff1e6',
  '#f3ecff',
  '#fdeaf3',
  '#e6f6ff',
  '#fff6e0',
  '#eafaf0',
  '#fdeae8',
  '#e6fbf7',
]

interface CatOption {
  id: number
  name: string
  emoji: string
  tint: string
}
const categoryOptions = computed<CatOption[]>(() => {
  const opts: CatOption[] = []
  let i = 0
  for (const g of categoryGroups.value) {
    for (const o of g.options) {
      const matchText = o.id === g.parent.id ? o.name : `${g.parent.name}${o.name}`
      opts.push({
        id: o.id,
        name: o.name,
        emoji: categoryEmoji(matchText, categoryKind.value),
        tint: CAT_TINTS[i % CAT_TINTS.length]!,
      })
      i++
    }
  }
  return opts
})

const isTransfer = computed(() => type.value === 'transfer')

/** 记账可选账户：排除已隐藏账户（历史流水仍保留，仅不在此处提供选择）。 */
const visibleAccounts = computed(() => accounts.value.filter((a) => !a.hidden))

// === 账户展示 ===
const ACCOUNT_DOT: Record<AccountType, string> = {
  CASH: '#16a34a',
  BANK_CARD: '#0ea5e9',
  ALIPAY: '#1677ff',
  WECHAT: '#07c160',
  CREDIT_CARD: '#f59e0b',
  INVESTMENT: '#8b5cf6',
}
function accountDot(t: AccountType): string {
  return ACCOUNT_DOT[t] ?? '#94a3b8'
}
function accountById(id: number | null): Account | undefined {
  return id == null ? undefined : accounts.value.find((a) => a.id === id)
}
const currentAccount = computed(() => accountById(accountId.value))
const sourceAccount = computed(() => accountById(accountId.value))
const destAccount = computed(() => accountById(destinationAccountId.value))
const selectedCategory = computed(() => categoryOptions.value.find((c) => c.id === categoryId.value))

// === 账户选择底部面板 ===
type SheetTarget = 'account' | 'source' | 'dest' | null
const sheetTarget = ref<SheetTarget>(null)
function openSheet(t: Exclude<SheetTarget, null>) {
  sheetTarget.value = t
}
function pickAccount(a: Account) {
  if (sheetTarget.value === 'dest') {
    destinationAccountId.value = a.id
  } else {
    accountId.value = a.id
    if (isTransfer.value && destinationAccountId.value === a.id) {
      const other = accounts.value.find((x) => x.id !== a.id)
      destinationAccountId.value = other ? other.id : null
    }
  }
  sheetTarget.value = null
  errorMsg.value = ''
}

// === 日期展示 ===
const dateLabel = computed(() => {
  const [d, t] = occurredAt.value.split('T')
  if (!d) return '今天'
  const today = nowForInput().split('T')[0]
  const hhmm = t ?? ''
  if (d === today) return `今天 ${hhmm}`
  const [, m, day] = d.split('-')
  return `${Number(m)}月${Number(day)}日 ${hhmm}`
})

// === 生命周期 ===
onMounted(() => {
  mq = window.matchMedia('(min-width: 768px)')
  isDesktop.value = mq.matches
  mq.addEventListener('change', onMqChange)
  load()
})
onUnmounted(() => {
  if (successTimer) clearTimeout(successTimer)
  mq?.removeEventListener('change', onMqChange)
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

function initDefaults() {
  const list = visibleAccounts.value
  const firstAccount = list[0]
  if (!firstAccount) return
  const last = defaults.lastAccountId
  const useLast = last != null && list.some((a) => a.id === last)
  const first = useLast ? (last as number) : firstAccount.id
  accountId.value = first
  const other = list.find((a) => a.id !== first)
  destinationAccountId.value = other ? other.id : null
}

function setType(t: TransactionType) {
  if (type.value === t) return
  type.value = t
  categoryId.value = null
  errorMsg.value = ''
  if (t === 'transfer' && destinationAccountId.value === accountId.value) {
    const other = accounts.value.find((a) => a.id !== accountId.value)
    destinationAccountId.value = other ? other.id : null
  }
}

// === 计算器输入（移动端键盘） ===
function tapDigit(d: string) {
  errorMsg.value = ''
  if (operand.value.includes('.')) {
    const dec = operand.value.split('.')[1] ?? ''
    if (dec.length >= 2) return
  } else if (operand.value.replace('.', '').length >= 9) {
    return
  }
  if (operand.value === '0') {
    operand.value = d
    return
  }
  operand.value += d
}
function tapDot() {
  errorMsg.value = ''
  if (operand.value.includes('.')) return
  operand.value = operand.value === '' ? '0.' : operand.value + '.'
}
function tapOp(symbol: '+' | '-') {
  errorMsg.value = ''
  if (operand.value !== '') {
    const cur = Number(operand.value)
    acc.value = acc.value == null ? cur : op.value === '-' ? acc.value - cur : acc.value + cur
    operand.value = ''
  } else if (acc.value == null) {
    return
  }
  op.value = symbol
}
function tapDelete() {
  errorMsg.value = ''
  if (operand.value !== '') {
    operand.value = operand.value.slice(0, -1)
  } else if (op.value != null) {
    op.value = null
  } else if (acc.value != null) {
    acc.value = null
  }
}

// === 金额输入（PC 物理键盘）：清洗为合法小数，重置计算器累计态 ===
function onAmountInput(e: Event) {
  const el = e.target as HTMLInputElement
  let v = el.value.replace(/[^\d.]/g, '')
  const firstDot = v.indexOf('.')
  if (firstDot !== -1) {
    const intPart = v.slice(0, firstDot).replace(/\./g, '')
    const decPart = v.slice(firstDot + 1).replace(/\./g, '')
    v = `${intPart.slice(0, 9)}.${decPart.slice(0, 2)}`
  } else {
    v = v.slice(0, 9)
  }
  operand.value = v
  acc.value = null
  op.value = null
  el.value = v
  errorMsg.value = ''
}

function selectCategory(id: number) {
  categoryId.value = id
  errorMsg.value = ''
}

// === 提交 ===
function buildPayload(): CreateTransactionPayload | null {
  if (!hasAmountInput.value) {
    errorMsg.value = '请输入金额'
    return null
  }
  const amountStr = amountForSubmit.value
  const amountErr = validateAmount(amountStr)
  if (amountErr) {
    errorMsg.value = amountErr
    return null
  }
  if (accountId.value == null) {
    errorMsg.value = isTransfer.value ? '请选择转出账户' : '请选择账户'
    return null
  }
  if (isTransfer.value) {
    if (destinationAccountId.value == null) {
      errorMsg.value = '请选择转入账户'
      return null
    }
    if (destinationAccountId.value === accountId.value) {
      errorMsg.value = '转出与转入账户不能相同'
      return null
    }
    return {
      type: 'transfer',
      amount: amountStr,
      sourceAccountId: accountId.value,
      destinationAccountId: destinationAccountId.value,
      occurredAt: toOccurredAt(occurredAt.value),
      note: note.value.trim() || undefined,
    }
  }
  if (categoryId.value == null) {
    errorMsg.value = '请选择分类'
    return null
  }
  return {
    type: type.value === 'income' ? 'income' : 'expense',
    amount: amountStr,
    accountId: accountId.value,
    categoryId: categoryId.value,
    occurredAt: toOccurredAt(occurredAt.value),
    note: note.value.trim() || undefined,
  }
}

async function submit(again: boolean) {
  errorMsg.value = ''
  const payload = buildPayload()
  if (!payload) return
  submitting.value = true
  try {
    await createTransaction(payload)
    defaults.rememberAccount(accountId.value!)
    if (again) {
      showSuccess()
      resetForNext()
    } else {
      close()
    }
  } catch (e) {
    errorMsg.value = toEntryErrorMessage(e)
  } finally {
    submitting.value = false
  }
}

let successTimer: ReturnType<typeof setTimeout> | undefined
function showSuccess() {
  successMsg.value = '已记一笔，继续～'
  if (successTimer) clearTimeout(successTimer)
  successTimer = setTimeout(() => {
    successMsg.value = ''
  }, 1200)
}

function resetForNext() {
  acc.value = null
  op.value = null
  operand.value = ''
  note.value = ''
  occurredAt.value = nowForInput()
}

function close() {
  if (window.history.length > 1) router.back()
  else router.push('/')
}

// === 时间辅助 ===
function pad(n: number): string {
  return String(n).padStart(2, '0')
}
function nowForInput(): string {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}
function toOccurredAt(local: string): string {
  return `${local}:00`
}
</script>

<template>
  <!-- ============ 移动端：全屏计算器 ============ -->
  <section v-if="!isDesktop" class="quick-screen" :class="type">
    <div class="qwrap">
      <header class="qtop">
        <button class="ic-btn" type="button" aria-label="关闭" @click="close">✕</button>
        <div class="qtabs" role="tablist" aria-label="交易类型">
          <button
            v-for="t in (['expense','income','transfer'] as TransactionType[])"
            :key="t"
            type="button"
            role="tab"
            class="qtab"
            :class="{ active: type === t }"
            :aria-selected="type === t"
            @click="setType(t)"
          >
            {{ t === 'expense' ? '支出' : t === 'income' ? '收入' : '转账' }}
          </button>
        </div>
        <RouterLink class="ic-btn" to="/categories" aria-label="管理分类">＋</RouterLink>
      </header>

      <div v-if="loadError" class="banner banner-err" role="alert">
        <span>{{ loadError }}</span>
        <button class="link-btn" type="button" @click="load">重试</button>
      </div>
      <p v-if="loading" class="text-muted loading">加载中…</p>

      <template v-else>
        <div v-if="accounts.length === 0" class="empty">
          <p class="text-muted">还没有账户，先添加一个再记账吧。</p>
          <RouterLink class="btn" to="/accounts">去添加账户</RouterLink>
        </div>

        <template v-else>
          <!-- 分类网格 -->
          <div v-if="!isTransfer" class="cats">
            <p v-if="categoryOptions.length === 0" class="text-muted cats-empty">
              还没有{{ type === 'income' ? '收入' : '支出' }}分类，<RouterLink to="/categories">去添加</RouterLink>。
            </p>
            <button
              v-for="opt in categoryOptions"
              :key="opt.id"
              type="button"
              class="cat"
              :class="{ active: categoryId === opt.id }"
              @click="selectCategory(opt.id)"
            >
              <span class="cat-circle" :style="{ background: opt.tint }">{{ opt.emoji }}</span>
              <span class="cat-nm">{{ opt.name }}</span>
            </button>
          </div>

          <!-- 转账两账户 -->
          <div v-else class="transfer">
            <button type="button" class="acc-pick" @click="openSheet('source')">
              <span class="ai out">↗</span>
              <span class="at">{{ sourceAccount ? sourceAccount.name : '选择转出账户' }}</span>
              <span class="av out num">-{{ hasAmountInput ? amountForSubmit : '0.00' }}</span>
            </button>
            <div class="swap" aria-hidden="true">⇅</div>
            <button type="button" class="acc-pick" @click="openSheet('dest')">
              <span class="ai in">↘</span>
              <span class="at">{{ destAccount ? destAccount.name : '选择转入账户' }}</span>
              <span class="av in num">+{{ hasAmountInput ? amountForSubmit : '0.00' }}</span>
            </button>
          </div>

          <!-- 备注 + 金额 + 键盘 -->
          <div class="entry-side">
            <div class="notebar">
              <div class="note-line">
                <input v-model="note" type="text" maxlength="200" placeholder="添加备注" aria-label="备注" />
                <span v-if="exprHint" class="expr num">{{ exprHint }}</span>
                <span class="amt num">{{ displayValue }}</span>
              </div>
              <div class="meta-line">
                <button type="button" class="chip date-chip" @click="openDatePicker">
                  <span>📅 {{ dateLabel }}</span>
                  <input ref="dateInput" v-model="occurredAt" type="datetime-local" aria-label="交易时间" tabindex="-1" />
                </button>
                <button v-if="!isTransfer" type="button" class="chip acc-chip" @click="openSheet('account')">
                  <span class="dot" v-if="currentAccount" :style="{ background: accountDot(currentAccount.type) }"></span>
                  {{ currentAccount ? currentAccount.name : '选择账户' }}
                  <span class="caret">▾</span>
                </button>
              </div>
              <p v-if="errorMsg" class="feedback err" role="alert">{{ errorMsg }}</p>
              <p v-else-if="successMsg" class="feedback ok" role="status">{{ successMsg }}</p>
            </div>

            <div class="pad" role="group" aria-label="数字键盘">
              <button type="button" class="k" @click="tapDigit('1')">1</button>
              <button type="button" class="k" @click="tapDigit('2')">2</button>
              <button type="button" class="k" @click="tapDigit('3')">3</button>
              <button type="button" class="k del" aria-label="删除" @click="tapDelete()">⌫</button>
              <button type="button" class="k" @click="tapDigit('4')">4</button>
              <button type="button" class="k" @click="tapDigit('5')">5</button>
              <button type="button" class="k" @click="tapDigit('6')">6</button>
              <button type="button" class="k op" aria-label="加" @click="tapOp('+')">＋</button>
              <button type="button" class="k" @click="tapDigit('7')">7</button>
              <button type="button" class="k" @click="tapDigit('8')">8</button>
              <button type="button" class="k" @click="tapDigit('9')">9</button>
              <button type="button" class="k op" aria-label="减" @click="tapOp('-')">－</button>
              <button type="button" class="k again" :disabled="submitting" @click="submit(true)">保存再记</button>
              <button type="button" class="k" @click="tapDigit('0')">0</button>
              <button type="button" class="k" @click="tapDot()">.</button>
              <button type="button" class="k done" :disabled="submitting" @click="submit(false)">完成</button>
            </div>
          </div>
        </template>
      </template>
    </div>
  </section>

  <!-- ============ PC：首页风格两栏卡片 ============ -->
  <section v-else class="quick-pc" :class="type">
    <header class="pc-head">
      <div>
        <h1>记一笔</h1>
        <p class="text-muted pc-sub">记好每一笔，日子有余</p>
      </div>
      <div class="pc-seg" role="tablist" aria-label="交易类型">
        <button
          v-for="t in (['expense','income','transfer'] as TransactionType[])"
          :key="t"
          type="button"
          role="tab"
          class="pc-seg-btn"
          :class="{ active: type === t }"
          :aria-selected="type === t"
          @click="setType(t)"
        >
          {{ t === 'expense' ? '支出' : t === 'income' ? '收入' : '转账' }}
        </button>
      </div>
    </header>

    <div v-if="loadError" class="banner banner-err" role="alert">
      <span>{{ loadError }}</span>
      <button class="link-btn" type="button" @click="load">重试</button>
    </div>
    <p v-if="loading" class="text-muted loading">加载中…</p>

    <template v-else>
      <div v-if="accounts.length === 0" class="card empty">
        <p class="text-muted">还没有账户，先添加一个再记账吧。</p>
        <RouterLink class="btn" to="/accounts">去添加账户</RouterLink>
      </div>

      <div v-else class="pc-grid">
        <!-- 左卡：分类 / 转账账户 -->
        <div class="card pc-left">
          <h2>{{ isTransfer ? '转账账户' : '选择分类' }}</h2>

          <template v-if="!isTransfer">
            <p v-if="categoryOptions.length === 0" class="text-muted small">
              还没有{{ type === 'income' ? '收入' : '支出' }}分类，<RouterLink to="/categories">去添加</RouterLink>。
            </p>
            <div v-else class="cats pc-cats">
              <button
                v-for="opt in categoryOptions"
                :key="opt.id"
                type="button"
                class="cat"
                :class="{ active: categoryId === opt.id }"
                @click="selectCategory(opt.id)"
              >
                <span class="cat-circle" :style="{ background: opt.tint }">{{ opt.emoji }}</span>
                <span class="cat-nm">{{ opt.name }}</span>
              </button>
            </div>
          </template>

          <div v-else class="transfer">
            <button type="button" class="acc-pick" @click="openSheet('source')">
              <span class="ai out">↗</span>
              <span class="at">{{ sourceAccount ? sourceAccount.name : '选择转出账户' }}</span>
              <span class="av out num">-{{ hasAmountInput ? amountForSubmit : '0.00' }}</span>
            </button>
            <div class="swap" aria-hidden="true">⇅</div>
            <button type="button" class="acc-pick" @click="openSheet('dest')">
              <span class="ai in">↘</span>
              <span class="at">{{ destAccount ? destAccount.name : '选择转入账户' }}</span>
              <span class="av in num">+{{ hasAmountInput ? amountForSubmit : '0.00' }}</span>
            </button>
          </div>
        </div>

        <!-- 右卡：金额 + 详情 + 保存 -->
        <div class="card pc-right">
          <h2>金额与详情</h2>

          <div class="amount-field">
            <span class="cur">¥</span>
            <input
              class="num"
              type="text"
              inputmode="decimal"
              placeholder="0.00"
              :value="operand"
              @input="onAmountInput"
              aria-label="金额"
            />
          </div>

          <div v-if="!isTransfer" class="pc-field">
            <label>分类</label>
            <div class="pc-ctrl static">
              <template v-if="selectedCategory">
                <span class="cat-mini" :style="{ background: selectedCategory.tint }">{{ selectedCategory.emoji }}</span>
                {{ selectedCategory.name }}
              </template>
              <span v-else class="text-muted">在左侧选择分类</span>
            </div>
          </div>

          <div v-if="!isTransfer" class="pc-field">
            <label>账户</label>
            <button type="button" class="pc-ctrl select" @click="openSheet('account')">
              <span class="dot" v-if="currentAccount" :style="{ background: accountDot(currentAccount.type) }"></span>
              {{ currentAccount ? currentAccount.name : '选择账户' }}
            </button>
          </div>

          <div class="pc-field">
            <label>日期</label>
            <button type="button" class="pc-ctrl" @click="openDatePicker">
              <span>📅 {{ dateLabel }}</span>
              <input ref="dateInput" v-model="occurredAt" type="datetime-local" aria-label="交易时间" tabindex="-1" />
            </button>
          </div>

          <div class="pc-field">
            <label>备注</label>
            <input class="pc-ctrl" v-model="note" type="text" maxlength="200" placeholder="加个备注（可选）" />
          </div>

          <p v-if="errorMsg" class="feedback err" role="alert">{{ errorMsg }}</p>
          <p v-else-if="successMsg" class="feedback ok" role="status">{{ successMsg }}</p>

          <div class="pc-actions">
            <button type="button" class="btn-line" :disabled="submitting" @click="submit(true)">保存再记</button>
            <button type="button" class="btn-solid" :disabled="submitting" @click="submit(false)">
              {{ submitting ? '提交中…' : '完成' }}
            </button>
          </div>
        </div>
      </div>
    </template>

    <!-- 账户选择对话框（PC 居中） -->
    <div v-if="sheetTarget" class="sheet-mask" @click.self="sheetTarget = null">
      <div class="sheet" role="dialog" aria-label="选择账户">
        <div class="sheet-head">
          {{ sheetTarget === 'dest' ? '选择转入账户' : sheetTarget === 'source' ? '选择转出账户' : '选择账户' }}
        </div>
        <button
          v-for="a in visibleAccounts"
          :key="a.id"
          type="button"
          class="sheet-item"
          :disabled="sheetTarget === 'dest' && a.id === accountId"
          @click="pickAccount(a)"
        >
          <span class="dot" :style="{ background: accountDot(a.type) }"></span>
          <span class="s-name">{{ a.name }}<small>{{ ACCOUNT_TYPE_LABELS[a.type] }}</small></span>
          <span class="s-bal num" :class="{ neg: Number(a.currentBalance) < 0 }">¥{{ formatAmount(a.currentBalance) }}</span>
        </button>
        <button type="button" class="sheet-cancel" @click="sheetTarget = null">取消</button>
      </div>
    </div>
  </section>

  <!-- 移动端账户选择底部面板 -->
  <div v-if="!isDesktop && sheetTarget" class="sheet-mask" @click.self="sheetTarget = null">
    <div class="sheet" role="dialog" aria-label="选择账户">
      <div class="sheet-head">
        {{ sheetTarget === 'dest' ? '选择转入账户' : sheetTarget === 'source' ? '选择转出账户' : '选择账户' }}
      </div>
      <button
        v-for="a in visibleAccounts"
        :key="a.id"
        type="button"
        class="sheet-item"
        :disabled="sheetTarget === 'dest' && a.id === accountId"
        @click="pickAccount(a)"
      >
        <span class="dot" :style="{ background: accountDot(a.type) }"></span>
        <span class="s-name">{{ a.name }}<small>{{ ACCOUNT_TYPE_LABELS[a.type] }}</small></span>
        <span class="s-bal num" :class="{ neg: Number(a.currentBalance) < 0 }">¥{{ formatAmount(a.currentBalance) }}</span>
      </button>
      <button type="button" class="sheet-cancel" @click="sheetTarget = null">取消</button>
    </div>
  </div>
</template>

<style scoped>
.num {
  font-variant-numeric: tabular-nums;
}

/* ==================== 移动端全屏 ==================== */
.quick-screen {
  --accent: var(--color-danger);
  height: 100dvh;
  background: var(--color-surface);
  display: flex;
  justify-content: center;
}
.quick-screen.income,
.quick-screen.transfer {
  --accent: var(--color-primary);
}
.qwrap {
  width: 100%;
  max-width: 480px;
  background: var(--color-surface);
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.qtop {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  padding: calc(10px + var(--safe-top)) 8px 6px;
}
.ic-btn {
  width: 40px;
  height: 40px;
  border: none;
  background: none;
  font-size: 19px;
  color: var(--color-text);
  display: flex;
  align-items: center;
  justify-content: center;
}
.qtabs {
  flex: 1;
  display: flex;
  justify-content: center;
  gap: 26px;
}
.qtab {
  position: relative;
  border: none;
  background: none;
  font-size: 16px;
  font-weight: 600;
  color: var(--color-muted);
  padding: 6px 2px;
}
.qtab.active {
  color: var(--color-text);
  font-weight: 800;
}
.qtab.active::after {
  content: '';
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  bottom: -2px;
  width: 22px;
  height: 3px;
  border-radius: 2px;
  background: var(--color-primary);
}
.loading {
  padding: 40px 0;
  text-align: center;
}
.empty {
  display: grid;
  gap: 14px;
  place-content: center;
  justify-items: center;
  padding: 40px 24px;
}
.cats {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 14px 6px 8px;
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 16px 0;
  align-content: start;
}
.cats-empty {
  grid-column: 1 / -1;
  text-align: center;
  padding: 24px 0;
}
.cat {
  border: none;
  background: none;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
  padding: 2px 0;
}
.cat-circle {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  transition: box-shadow 0.15s;
}
.cat.active .cat-circle {
  box-shadow: 0 0 0 3px var(--color-surface), 0 0 0 6px var(--color-primary);
}
.cat-nm {
  font-size: 12px;
  color: #4b5563;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cat.active .cat-nm {
  color: var(--color-primary-dark);
  font-weight: 700;
}
.transfer {
  padding: 22px 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  align-items: center;
}
.quick-screen .transfer {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
}
.acc-pick {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--color-border);
  border-radius: 14px;
  background: var(--color-surface);
  box-shadow: 0 2px 8px rgba(15, 23, 42, 0.04);
}
.acc-pick .ai {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  background: #ecfdf3;
  color: var(--color-primary);
}
.acc-pick .at {
  flex: 1;
  text-align: left;
  font-weight: 600;
}
.acc-pick .av.out {
  color: var(--color-danger);
  font-weight: 800;
}
.acc-pick .av.in {
  color: var(--color-primary);
  font-weight: 800;
}
.swap {
  width: 38px;
  height: 38px;
  border-radius: 50%;
  border: 1.5px solid var(--color-border);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-muted);
  font-size: 17px;
}
.entry-side {
  display: contents;
}
.notebar {
  flex: 0 0 auto;
  border-top: 1px solid var(--color-border);
  padding: 12px 16px 8px;
}
.note-line {
  display: flex;
  align-items: baseline;
  gap: 10px;
}
.note-line input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  font-size: 15px;
  background: none;
  color: var(--color-text);
}
.expr {
  font-size: 14px;
  color: var(--color-muted);
}
.amt {
  font-size: 26px;
  font-weight: 800;
  color: var(--accent);
  white-space: nowrap;
}
.meta-line {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 10px;
  flex-wrap: wrap;
}
.chip {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border-radius: 9px;
  background: var(--color-bg);
  border: none;
  font-size: 13px;
  color: #4b5563;
}
.date-chip {
  cursor: pointer;
}
.date-chip input {
  position: absolute;
  inset: 0;
  opacity: 0;
  border: none;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
.acc-chip .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.acc-chip .caret {
  color: var(--color-muted);
  font-size: 11px;
}
.feedback {
  margin: 8px 0 0;
  font-size: 13px;
}
.feedback.err {
  color: var(--color-danger);
}
.feedback.ok {
  color: var(--color-primary-dark);
}
.pad {
  flex: 0 0 auto;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1px;
  background: var(--color-border);
  padding: 1px 0 calc(1px + var(--safe-bottom));
}
.k {
  height: 56px;
  border: none;
  background: var(--color-surface);
  font-size: 22px;
  font-weight: 600;
  color: var(--color-text);
  display: flex;
  align-items: center;
  justify-content: center;
}
.k:active {
  background: #f0f2f5;
}
.k.op {
  color: #4b5563;
  font-size: 24px;
}
.k.del {
  color: #4b5563;
}
.k.again {
  font-size: 14px;
  font-weight: 700;
  color: #4b5563;
}
.k.done {
  background: var(--accent);
  color: #fff;
  font-size: 18px;
  font-weight: 800;
}
.k.done:active {
  filter: brightness(0.94);
}
.k:disabled {
  opacity: 0.6;
}

/* ==================== PC 两栏卡片 ==================== */
.quick-pc {
  --accent: var(--color-danger);
}
.quick-pc.income,
.quick-pc.transfer {
  --accent: var(--color-primary);
}
.pc-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
}
.pc-head h1 {
  margin: 0;
  font-size: 24px;
}
.pc-sub {
  margin: 4px 0 0;
  font-size: 13px;
}
.pc-seg {
  display: flex;
  gap: 4px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 12px;
  padding: 4px;
}
.pc-seg-btn {
  padding: 8px 20px;
  border: none;
  border-radius: 9px;
  background: none;
  color: var(--color-muted);
  font-weight: 700;
  font-size: 15px;
  cursor: pointer;
}
.pc-seg-btn.active {
  background: var(--accent);
  color: #fff;
}
.quick-pc {
  width: 100%;
}
.pc-grid {
  width: 100%;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  gap: 22px;
  align-items: start;
}
.pc-left {
  min-width: 0;
}
.card h2 {
  margin: 0 0 16px;
  font-size: 15px;
}
.small {
  font-size: 13px;
}
.pc-cats {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 18px 8px;
}
.quick-pc .transfer {
  padding: 0;
}
.amount-field {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 12px 14px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  margin-bottom: 16px;
}
.amount-field .cur {
  font-size: 22px;
  color: var(--color-muted);
}
.amount-field input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  font-size: 34px;
  font-weight: 800;
  color: var(--accent);
  background: none;
}
.pc-field {
  margin-bottom: 14px;
}
.pc-field label {
  display: block;
  font-size: 13px;
  color: var(--color-muted);
  font-weight: 600;
  margin-bottom: 6px;
}
.pc-ctrl {
  position: relative;
  width: 100%;
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: var(--color-surface);
  font: inherit;
  color: var(--color-text);
  display: flex;
  align-items: center;
  gap: 8px;
  text-align: left;
}
button.pc-ctrl {
  cursor: pointer;
}
.pc-ctrl.select::after {
  content: '▾';
  margin-left: auto;
  color: var(--color-muted);
}
.pc-ctrl .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.pc-ctrl.static {
  background: var(--color-bg);
}
.pc-ctrl input[type='datetime-local'] {
  position: absolute;
  inset: 0;
  opacity: 0;
  border: none;
  width: 100%;
  height: 100%;
  pointer-events: none;
}
.cat-mini {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
}
.pc-actions {
  display: flex;
  gap: 10px;
  margin-top: 20px;
}
.btn-line,
.btn-solid {
  flex: 1;
  height: 48px;
  border: none;
  border-radius: 12px;
  font-weight: 800;
  font-size: 15px;
  cursor: pointer;
}
.btn-line {
  background: var(--color-bg);
  color: #4b5563;
}
.btn-solid {
  background: var(--accent);
  color: #fff;
  box-shadow: 0 8px 20px color-mix(in srgb, var(--accent) 26%, transparent);
}
.btn-line:disabled,
.btn-solid:disabled {
  opacity: 0.6;
}

/* ==================== 通用 ==================== */
.banner {
  margin: 12px 16px 0;
  padding: 10px 12px;
  border-radius: var(--radius);
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  background: #fef2f2;
  color: var(--color-danger);
}
.link-btn {
  border: none;
  background: none;
  color: inherit;
  text-decoration: underline;
  font-weight: 600;
}
.btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-height: 44px;
  padding: 0 18px;
  border-radius: var(--radius);
  background: var(--color-primary);
  color: #fff;
  font-weight: 600;
}
.card.empty {
  display: grid;
  gap: 14px;
  place-items: center;
  padding: 40px 24px;
}

/* 账户选择面板 */
.sheet-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.35);
  display: flex;
  align-items: flex-end;
  justify-content: center;
  z-index: 60;
}
.sheet {
  width: 100%;
  max-width: 480px;
  background: var(--color-surface);
  border-radius: 18px 18px 0 0;
  padding: 8px 12px calc(12px + var(--safe-bottom));
  max-height: 70%;
  overflow-y: auto;
}
.sheet-head {
  text-align: center;
  font-weight: 700;
  padding: 12px 0;
  color: var(--color-muted);
  font-size: 14px;
}
.sheet-item {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 13px 8px;
  border: none;
  background: none;
  border-top: 1px solid var(--color-border);
  font-size: 15px;
  text-align: left;
  cursor: pointer;
}
.sheet-item .dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  flex: 0 0 auto;
}
.sheet-item .s-name {
  flex: 1;
  font-weight: 600;
}
.sheet-item .s-name small {
  color: var(--color-muted);
  font-weight: 400;
  margin-left: 6px;
}
.sheet-item .s-bal {
  font-weight: 700;
}
.sheet-item .s-bal.neg {
  color: var(--color-danger);
}
.sheet-item:disabled {
  opacity: 0.4;
}
.sheet-cancel {
  width: 100%;
  margin-top: 8px;
  padding: 13px 0;
  border: none;
  background: var(--color-bg);
  border-radius: 12px;
  font-weight: 700;
  color: var(--color-text);
  cursor: pointer;
}
@media (min-width: 768px) {
  .sheet-mask {
    align-items: center;
  }
  .sheet {
    max-width: 420px;
    border-radius: 16px;
    max-height: 70vh;
  }
}
</style>
