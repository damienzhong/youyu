<script setup lang="ts">
/**
 * 账单导入（支付宝 / 微信 CSV）。
 *
 * 三步：① 选择/上传文件（自动识别来源与编码）→ ② 预览 + 映射（目标账户 / 默认分类）→ ③ 结果。
 * CSV 在浏览器端解析（见 lib/billImport），归一化后批量提交 /imports/bills；后端按 externalId 去重。
 */
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import {
  fetchAccounts,
  fetchCategories,
  importBills,
  formatAmount,
  categoryEmoji,
  type Account,
  type Category,
} from '@/lib/ledger'
import { readBillFile, type ParsedBill, type BillSource } from '@/lib/billImport'

const router = useRouter()
function goBack() {
  if (step.value === 'preview') {
    reset()
    return
  }
  if (window.history.length > 1) router.back()
  else router.push('/')
}

type Step = 'pick' | 'preview' | 'done'
const step = ref<Step>('pick')

const accounts = ref<Account[]>([])
const categories = ref<Category[]>([])
const loadError = ref('')

const expenseCats = computed(() => categories.value.filter((c) => c.kind === 'EXPENSE'))
const incomeCats = computed(() => categories.value.filter((c) => c.kind === 'INCOME'))

onMounted(async () => {
  try {
    const [accs, cats] = await Promise.all([fetchAccounts(), fetchCategories()])
    accounts.value = accs
    categories.value = cats
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败，请重试'
  }
})

// ===== Step 1：选择文件 =====
const fileInput = ref<HTMLInputElement | null>(null)
const parsing = ref(false)
const parseError = ref('')
const parsed = ref<ParsedBill | null>(null)

function pickFile() {
  parseError.value = ''
  fileInput.value?.click()
}
async function onFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // 允许重复选择同一文件
  if (!file) return
  parsing.value = true
  parseError.value = ''
  try {
    const result = await readBillFile(file, categories.value)
    if (result.entries.length === 0) {
      parseError.value = '没有可导入的收支记录（可能全是转账/提现等中性交易）'
      parsing.value = false
      return
    }
    parsed.value = result
    // 默认目标账户：优先按来源猜（支付宝/微信），否则第一个账户。
    targetAccountId.value = guessAccount(result.source) ?? accounts.value[0]?.id ?? null
    defaultExpenseId.value = expenseCats.value[0]?.id ?? null
    defaultIncomeId.value = incomeCats.value[0]?.id ?? null
    step.value = 'preview'
  } catch (e) {
    parseError.value = e instanceof Error ? e.message : '解析失败，请确认文件格式'
  } finally {
    parsing.value = false
  }
}
function guessAccount(source: BillSource): number | null {
  const kw = source === 'alipay' ? ['支付宝', '支付宝'] : ['微信', '零钱']
  const hit = accounts.value.find((a) => kw.some((k) => a.name.includes(k)) || (source === 'alipay' ? a.type === 'ALIPAY' : a.type === 'WECHAT'))
  return hit?.id ?? null
}

// ===== Step 2：映射 =====
const targetAccountId = ref<number | null>(null)
const defaultExpenseId = ref<number | null>(null)
const defaultIncomeId = ref<number | null>(null)
const importing = ref(false)
const importError = ref('')

const sourceLabel = computed(() => (parsed.value?.source === 'alipay' ? '支付宝' : '微信'))
const previewRows = computed(() => parsed.value?.entries.slice(0, 8) ?? [])
const moreCount = computed(() => Math.max(0, (parsed.value?.entries.length ?? 0) - 8))

function emojiFor(row: { type: 'expense' | 'income'; note: string; categoryName: string }): string {
  return categoryEmoji(row.categoryName || row.note, row.type === 'income' ? 'INCOME' : 'EXPENSE')
}

// ===== Step 3：结果 =====
const result = ref<{ imported: number; skippedDuplicate: number; skippedInvalid: number } | null>(null)

async function doImport() {
  if (!parsed.value || targetAccountId.value == null) {
    importError.value = '请选择导入目标账户'
    return
  }
  importing.value = true
  importError.value = ''
  try {
    result.value = await importBills({
      accountId: targetAccountId.value,
      defaultExpenseCategoryId: defaultExpenseId.value,
      defaultIncomeCategoryId: defaultIncomeId.value,
      entries: parsed.value.entries.map((e) => ({
        type: e.type,
        amount: e.amount,
        occurredAt: e.occurredAt,
        note: e.note,
        externalId: e.externalId,
        categoryId: e.categoryId,
      })),
    })
    step.value = 'done'
  } catch (e) {
    importError.value = e instanceof Error ? e.message : '导入失败，请重试'
  } finally {
    importing.value = false
  }
}

function reset() {
  parsed.value = null
  result.value = null
  parseError.value = ''
  importError.value = ''
  step.value = 'pick'
}
</script>

<template>
  <section class="import">
    <header class="appbar">
      <button type="button" class="ab-btn" aria-label="返回" @click="goBack">←</button>
      <h1 class="ab-title">
        导入账单<template v-if="step === 'preview'"> · {{ sourceLabel }}</template>
      </h1>
      <span class="ab-btn" aria-hidden="true"></span>
    </header>

    <div v-if="loadError" class="banner banner-err">{{ loadError }}</div>

    <!-- Step 1 -->
    <template v-if="step === 'pick'">
      <div class="card">
        <h2>选择账单来源</h2>
        <button type="button" class="src" @click="pickFile">
          <span class="lg" style="background:#e6f0ff">💠</span>
          <span class="t"><span class="n">支付宝</span><span class="d">交易记录明细 CSV</span></span>
          <span class="chev">›</span>
        </button>
        <button type="button" class="src" @click="pickFile">
          <span class="lg" style="background:#e7faec">💬</span>
          <span class="t"><span class="n">微信支付</span><span class="d">微信支付账单明细 CSV</span></span>
          <span class="chev">›</span>
        </button>
        <button type="button" class="drop" :disabled="parsing" @click="pickFile">
          <span class="ic">📄</span>
          <span class="dt">{{ parsing ? '解析中…' : '点击选择 CSV 文件' }}</span>
          <span class="ds">自动识别来源与编码</span>
        </button>
        <input ref="fileInput" type="file" accept=".csv,text/csv" hidden @change="onFileChange" />
        <p v-if="parseError" class="banner banner-err">{{ parseError }}</p>
      </div>

      <div class="card">
        <h2>如何导出账单？</h2>
        <p class="tip">
          <b>支付宝</b>：我的 → 账单 → 右上「…」→ 开具交易流水证明 → 用于个人对账，邮箱收 CSV。<br />
          <b>微信</b>：我 → 服务 → 钱包 → 账单 → 常见问题 → 下载账单 → 用于个人对账，邮箱收 CSV。<br />
          压缩包有密码（可在对应公众号查看），解压得到 CSV 后选它即可。转账/提现等中性交易会自动跳过。
        </p>
      </div>
    </template>

    <!-- Step 2 -->
    <template v-else-if="step === 'preview' && parsed">
      <div class="card">
        <div class="chips">
          <div class="chip exp">
            <div class="n">{{ parsed.expenseCount }}</div>
            <div class="l">支出 ¥{{ formatAmount(parsed.expenseTotal) }}</div>
          </div>
          <div class="chip inc">
            <div class="n">{{ parsed.incomeCount }}</div>
            <div class="l">收入 ¥{{ formatAmount(parsed.incomeTotal) }}</div>
          </div>
          <div class="chip skip">
            <div class="n">{{ parsed.neutralCount + parsed.invalidCount }}</div>
            <div class="l">跳过</div>
          </div>
        </div>

        <label class="field">
          <span class="flabel">导入到账户</span>
          <select v-model="targetAccountId" class="sel">
            <option v-for="a in accounts" :key="a.id" :value="a.id">{{ a.name }}</option>
          </select>
        </label>
        <label class="field">
          <span class="flabel">默认支出分类<small class="text-muted">（未匹配上时用）</small></span>
          <select v-model="defaultExpenseId" class="sel">
            <option v-for="c in expenseCats" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
        </label>
        <label class="field">
          <span class="flabel">默认收入分类<small class="text-muted">（未匹配上时用）</small></span>
          <select v-model="defaultIncomeId" class="sel">
            <option v-for="c in incomeCats" :key="c.id" :value="c.id">{{ c.name }}</option>
          </select>
        </label>
        <p v-if="parsed.from" class="text-muted range-tip">
          账单区间 {{ parsed.from }} ~ {{ parsed.to }}；重复账单（同单号）导入时自动跳过。
        </p>
      </div>

      <div class="card">
        <h2>预览（前 {{ previewRows.length }} 笔）</h2>
        <ul class="rows">
          <li v-for="(row, i) in previewRows" :key="i" class="prow">
            <span class="ic">{{ emojiFor(row) }}</span>
            <div class="mid">
              <div class="nm">{{ row.note || (row.type === 'income' ? '收入' : '支出') }}</div>
              <div class="sb">
                {{ row.occurredAt.slice(5, 16).replace('T', ' ') }}
                <template v-if="row.categoryName"> · {{ row.categoryName }}</template>
                <template v-else> · 用默认分类</template>
              </div>
            </div>
            <span class="amt" :class="row.type">
              {{ row.type === 'expense' ? '-' : '+' }}{{ formatAmount(row.amount) }}
            </span>
          </li>
        </ul>
        <p v-if="moreCount > 0" class="text-muted more">… 还有 {{ moreCount }} 笔</p>
      </div>

      <p v-if="importError" class="banner banner-err">{{ importError }}</p>
      <button class="btn-primary" type="button" :disabled="importing" @click="doImport">
        {{ importing ? '导入中…' : `导入 ${parsed.entries.length} 笔` }}
      </button>
      <button class="btn-ghost" type="button" :disabled="importing" @click="reset">取消</button>
    </template>

    <!-- Step 3 -->
    <template v-else-if="step === 'done' && result">
      <div class="card done">
        <div class="big">✅</div>
        <div class="h">导入完成</div>
        <div class="s">
          成功导入 <b>{{ result.imported }}</b> 笔<br />
          <template v-if="result.skippedDuplicate || result.skippedInvalid">
            跳过 {{ result.skippedDuplicate + result.skippedInvalid }} 笔（重复
            {{ result.skippedDuplicate }} · 异常 {{ result.skippedInvalid }}）
          </template>
        </div>
      </div>
      <div class="card">
        <div class="kv"><span>成功导入</span><span class="v ok">{{ result.imported }} 笔</span></div>
        <div class="kv"><span>跳过 · 重复账单</span><span class="v">{{ result.skippedDuplicate }} 笔</span></div>
        <div class="kv"><span>跳过 · 异常</span><span class="v">{{ result.skippedInvalid }} 笔</span></div>
      </div>
      <button class="btn-primary" type="button" @click="router.push('/')">回首页查看流水</button>
      <button class="btn-ghost" type="button" @click="reset">再导一份</button>
    </template>
  </section>
</template>

<style scoped>
.import {
  padding-bottom: 40px;
}
.appbar {
  position: sticky;
  top: 0;
  z-index: 30;
  display: flex;
  align-items: center;
  gap: 4px;
  margin: -16px calc(-1 * clamp(12px, 4vw, 32px)) 14px;
  padding: calc(6px + var(--safe-top)) 6px 6px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
}
.ab-btn {
  flex: 0 0 auto;
  width: 40px;
  height: 40px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  background: none;
  font-size: 20px;
  color: var(--color-text);
  cursor: pointer;
}
.ab-title {
  flex: 1;
  margin: 0;
  text-align: center;
  font-size: 17px;
  font-weight: 700;
}
@media (min-width: 768px) {
  .appbar {
    margin-top: 0;
  }
}

.card {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 16px;
  padding: 16px;
  margin-bottom: 14px;
}
.card h2 {
  margin: 0 0 12px;
  font-size: 15px;
}

/* Step1 */
.src {
  width: 100%;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--color-border);
  border-radius: 14px;
  background: var(--color-surface);
  cursor: pointer;
  margin-bottom: 12px;
  text-align: left;
}
.src:active {
  background: var(--color-bg);
}
.src .lg {
  width: 44px;
  height: 44px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 22px;
  flex: 0 0 auto;
}
.src .t {
  flex: 1;
  min-width: 0;
}
.src .t .n {
  display: block;
  font-size: 15px;
  font-weight: 700;
}
.src .t .d {
  display: block;
  font-size: 12px;
  color: var(--color-muted);
  margin-top: 2px;
}
.src .chev {
  color: var(--color-muted);
}
.drop {
  width: 100%;
  border: 2px dashed #cbd5e1;
  border-radius: 14px;
  padding: 24px 16px;
  text-align: center;
  color: var(--color-muted);
  background: var(--color-surface);
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.drop .ic {
  font-size: 30px;
}
.drop .dt {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text);
}
.drop .ds {
  font-size: 12px;
}
.tip {
  font-size: 13px;
  line-height: 1.8;
  color: var(--color-muted);
}

/* Step2 */
.chips {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}
.chip {
  flex: 1;
  border-radius: 12px;
  padding: 10px;
  text-align: center;
}
.chip .n {
  font-size: 18px;
  font-weight: 800;
}
.chip .l {
  font-size: 11px;
  margin-top: 2px;
}
.chip.exp {
  background: #fef2f2;
  color: var(--color-danger);
}
.chip.inc {
  background: #ecfdf5;
  color: var(--color-primary-dark);
}
.chip.skip {
  background: var(--color-bg);
  color: #64748b;
}
.field {
  display: block;
  margin-bottom: 12px;
}
.flabel {
  display: block;
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 6px;
}
.sel {
  width: 100%;
  min-height: 42px;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  padding: 8px 12px;
  font: inherit;
  background: var(--color-surface);
}
.range-tip {
  font-size: 12px;
  margin: 2px 0 0;
}
.rows {
  list-style: none;
  margin: 0;
  padding: 0;
}
.prow {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 0;
  border-top: 1px solid var(--color-border);
}
.prow:first-child {
  border-top: none;
}
.prow .ic {
  width: 32px;
  height: 32px;
  border-radius: 9px;
  background: var(--color-bg);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  flex: 0 0 auto;
}
.prow .mid {
  flex: 1;
  min-width: 0;
}
.prow .nm {
  font-size: 14px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.prow .sb {
  font-size: 11px;
  color: var(--color-muted);
  margin-top: 2px;
}
.prow .amt {
  font-weight: 800;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.prow .amt.expense {
  color: var(--color-danger);
}
.prow .amt.income {
  color: var(--color-primary);
}
.more {
  text-align: center;
  font-size: 12px;
  margin: 10px 0 0;
}

/* Step3 */
.done {
  text-align: center;
  padding: 22px 16px;
}
.done .big {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  background: #ecfdf5;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 36px;
  margin: 0 auto 14px;
}
.done .h {
  font-size: 20px;
  font-weight: 800;
}
.done .s {
  font-size: 14px;
  color: var(--color-muted);
  margin-top: 8px;
  line-height: 1.8;
}
.kv {
  display: flex;
  justify-content: space-between;
  padding: 11px 0;
  border-top: 1px solid var(--color-border);
  font-size: 14px;
}
.kv:first-child {
  border-top: none;
}
.kv .v {
  font-weight: 700;
}
.kv .v.ok {
  color: var(--color-primary);
}

.btn-primary,
.btn-ghost {
  width: 100%;
  padding: 13px;
  border-radius: 12px;
  font-size: 15px;
  font-weight: 700;
  cursor: pointer;
  margin-bottom: 10px;
}
.btn-primary {
  border: none;
  background: linear-gradient(135deg, #1eb257, #128a3f);
  color: #fff;
}
.btn-primary:disabled {
  opacity: 0.6;
}
.btn-ghost {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  color: var(--color-text);
}
.banner {
  margin: 0 0 12px;
  padding: 10px 12px;
  border-radius: var(--radius);
  font-size: 14px;
}
.banner-err {
  background: #fef2f2;
  color: var(--color-danger);
}
</style>
