<script setup lang="ts">
/**
 * 交易编辑弹窗（复用于流水列表页）。
 *
 * 复用 ledger 类型与校验；支出/收入可改金额/账户/分类/时间/备注，
 * 转账可改金额/转出转入账户/时间/备注（源≠目标）。
 * 提交调用 PUT /transactions/{id}（需求 4.6：后端回滚原影响并应用新影响）。
 * 校验失败或提交失败时保留输入、显示具体原因（需求 11.5 一致的错误处理）。
 */
import { ref, computed, watch } from 'vue'
import {
  updateTransaction,
  deleteTransaction,
  validateAmount,
  toEntryErrorMessage,
  ACCOUNT_TYPE_LABELS,
  type Account,
  type Category,
  type CategoryKind,
  type Transaction,
  type CreateTransactionPayload,
} from '@/lib/ledger'

const props = defineProps<{
  transaction: Transaction
  accounts: Account[]
  categories: Category[]
}>()

const emit = defineEmits<{
  (e: 'close'): void
  (e: 'saved'): void
}>()

const type = computed(() => props.transaction.type)
const isTransfer = computed(() => type.value === 'transfer')

const amount = ref('')
const note = ref('')
const occurredAt = ref('')
const accountId = ref<number | null>(null)
const destinationAccountId = ref<number | null>(null)
const categoryId = ref<number | null>(null)

const submitting = ref(false)
const errorMsg = ref('')

const categoryKind = computed<CategoryKind>(() => (type.value === 'income' ? 'INCOME' : 'EXPENSE'))
const visibleCategories = computed(() => props.categories.filter((c) => c.kind === categoryKind.value))

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

// 用传入交易初始化表单（弹窗每次打开都重置）。
watch(
  () => props.transaction,
  (tx) => {
    amount.value = tx.amount
    note.value = tx.note ?? ''
    occurredAt.value = toInputValue(tx.occurredAt)
    accountId.value = tx.accountId ?? tx.sourceAccountId ?? null
    destinationAccountId.value = tx.destinationAccountId ?? null
    categoryId.value = tx.categoryId ?? null
    errorMsg.value = ''
  },
  { immediate: true },
)

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
    await updateTransaction(props.transaction.id, payload)
    emit('saved')
  } catch (e) {
    errorMsg.value = toEntryErrorMessage(e)
  } finally {
    submitting.value = false
  }
}

/** 删除本笔流水（后端回滚原影响、调整账户余额，需求 4.6）。 */
async function onDelete() {
  if (typeof window !== 'undefined'
      && !window.confirm('确定删除这笔流水吗？删除后对应账户余额会随之调整。')) {
    return
  }
  errorMsg.value = ''
  submitting.value = true
  try {
    await deleteTransaction(props.transaction.id)
    emit('saved')
  } catch (e) {
    errorMsg.value = toEntryErrorMessage(e)
  } finally {
    submitting.value = false
  }
}

function pad(n: number): string {
  return String(n).padStart(2, '0')
}
/** ISO/后端时间 → datetime-local 值（精确到分钟，本地时区）。 */
function toInputValue(occurredAt: string): string {
  const d = new Date(occurredAt)
  if (Number.isNaN(d.getTime())) return occurredAt.slice(0, 16)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}
/**
 * datetime-local 值 → 落库时间。后端 occurredAt 为 LocalDateTime（无时区），
 * 整体按 Asia/Shanghai 处理，提交本地墙钟时间即可，不能带时区偏移。
 */
function toOccurredAt(local: string): string {
  return `${local}:00`
}
</script>

<template>
  <div class="modal-mask" @click.self="emit('close')">
    <div class="modal" role="dialog" aria-modal="true" aria-label="编辑交易">
      <header class="modal-head">
        <h2>编辑{{ isTransfer ? '转账' : type === 'income' ? '收入' : '支出' }}</h2>
        <button class="icon-btn" type="button" aria-label="关闭" @click="emit('close')">✕</button>
      </header>

      <div class="modal-body">
        <label class="field">
          <span>金额</span>
          <input v-model="amount" type="text" inputmode="decimal" placeholder="0.00" />
        </label>

        <label class="field">
          <span>{{ isTransfer ? '转出账户' : '账户' }}</span>
          <select v-model.number="accountId">
            <option v-for="a in accounts" :key="a.id" :value="a.id">
              {{ a.name }}（{{ ACCOUNT_TYPE_LABELS[a.type] }}）
            </option>
          </select>
        </label>

        <label v-if="isTransfer" class="field">
          <span>转入账户</span>
          <select v-model.number="destinationAccountId">
            <option v-for="a in accounts" :key="a.id" :value="a.id" :disabled="a.id === accountId">
              {{ a.name }}（{{ ACCOUNT_TYPE_LABELS[a.type] }}）
            </option>
          </select>
        </label>

        <div v-if="!isTransfer" class="field">
          <span>分类</span>
          <p v-if="categoryGroups.length === 0" class="text-muted small">
            还没有{{ type === 'income' ? '收入' : '支出' }}分类。
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
                @click="categoryId = opt.id"
              >
                {{ opt.name }}
              </button>
            </div>
          </div>
        </div>

        <label class="field">
          <span>时间</span>
          <input v-model="occurredAt" type="datetime-local" />
        </label>

        <label class="field">
          <span>备注（可选）</span>
          <input v-model="note" type="text" maxlength="200" placeholder="备注" />
        </label>

        <p v-if="errorMsg" class="banner banner-err" role="alert">{{ errorMsg }}</p>
      </div>

      <footer class="modal-foot">
        <button class="btn btn-danger" type="button" :disabled="submitting" @click="onDelete">删除</button>
        <button class="btn btn-ghost" type="button" @click="emit('close')">取消</button>
        <button class="btn" type="button" :disabled="submitting" @click="onSubmit">
          {{ submitting ? '保存中…' : '保存' }}
        </button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: flex-end;
  justify-content: center;
  z-index: 50;
  padding: 0;
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
  background: #fef2f2;
  color: var(--color-danger);
  border: 1px solid #f3d3d3;
}
.btn-danger:active {
  background: #fde3e3;
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
.small {
  font-size: 13px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.chip {
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
.cat-group {
  margin-bottom: 10px;
}
.cat-parent {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}
.banner {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--radius);
  font-size: 14px;
}
.banner-err {
  background: #fef2f2;
  color: var(--color-danger);
}
</style>
