<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { listAccounts } from '../../api/account'
import { listCategories, flattenAll, flattenCategories } from '../../api/category'
import { importBills, classifyBills, saveCategoryRules } from '../../api/bill'
import { listProjects } from '../../api/project'
import { listTags } from '../../api/tag'
import { detectSource, parseBillText } from '../../utils/billImport'
import { formatAmount } from '../../utils/format'

const accounts = ref([])
const flatCats = ref([])
const expenseCats = ref([])
const incomeCats = ref([])

const accIdx = ref(0)
const expIdx = ref(0)
const incIdx = ref(0)

// 默认项目/标签（整批统一归类，可选）
const projects = ref([])
const tags = ref([])
const projIdx = ref(0) // 0 = 不设置
const selectedTagIds = ref([])
const projectOptions = computed(() => [{ id: null, name: '不设置' }, ...projects.value])
function toggleTag(id) {
  const i = selectedTagIds.value.indexOf(id)
  if (i >= 0) selectedTagIds.value.splice(i, 1)
  else selectedTagIds.value.push(id)
}

const parsed = ref(null)
const importing = ref(false)

const SOURCE_LABEL = { alipay: '支付宝', wechat: '微信' }

// ---------------- 自动分类：来源标签 / 逐笔核对 / 记住规则 ----------------
const SOURCE_TAG = {
  RULE: { text: '按习惯', cls: 'habit' },
  HABIT: { text: '按习惯', cls: 'habit' },
  MERCHANT: { text: '按商户', cls: 'merchant' },
  KEYWORD: { text: '按名称', cls: 'merchant' },
  EDITED: { text: '已修改', cls: 'edited' },
  NONE: { text: '默认分类', cls: 'none' }
}
const PAGE = 100
const classifying = ref(false)
const showCount = ref(PAGE)
// 「以后都这样记」：`${type}|${商户键}` → { type, counterparty, categoryId }，导入成功后一并保存
const pendingRules = ref({})

/** 与后端 CategoryClassifier.merchantKey 同口径：去括号门店信息、去空白、小写。 */
function merchantKey(s) {
  return String(s || '').replace(/[(（【[][^)）】\]]*[)）】\]]/g, '').replace(/\s+/g, '').toLowerCase()
}

function fmtTime(t) {
  const m = /^\d{4}-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(t || '')
  return m ? `${m[1]}/${m[2]} ${m[3]}:${m[4]}` : ''
}

const catLabel = computed(() => {
  const map = {}
  for (const c of [...expenseCats.value, ...incomeCats.value]) map[c.id] = c.label
  return map
})

/** 后端按「规则 → 历史习惯 → 商户库 → 分类名」推荐分类；失败时保留本地关键字匹配，不打断导入。 */
async function classifyParsed() {
  const list = parsed.value?.entries || []
  if (!list.length) return
  classifying.value = true
  try {
    const r = await classifyBills(list.map((e) => ({ type: e.type, counterparty: e.counterparty, text: e.matchText })))
    const results = (r && r.results) || []
    list.forEach((e, i) => {
      const x = results[i]
      if (x && x.categoryId != null) {
        e.categoryId = x.categoryId
        e.source = x.source
      }
    })
  } catch (e) {
    // 静默降级：沿用本地关键字匹配
  } finally {
    classifying.value = false
  }
}

const rows = computed(() => {
  if (!parsed.value) return []
  const defExp = expenseCats.value[expIdx.value]
  const defInc = incomeCats.value[incIdx.value]
  const all = parsed.value.entries.map((e, idx) => {
    const none = e.categoryId == null
    const def = e.type === 'income' ? defInc : defExp
    const tag = SOURCE_TAG[e.source] || SOURCE_TAG.NONE
    const cat = none ? (def ? def.label : '未分类') : (catLabel.value[e.categoryId] || '未知分类')
    return {
      idx,
      title: e.counterparty || e.note || '未命名流水',
      sub: `${fmtTime(e.occurredAt)} · ${cat}`,
      tagText: tag.text,
      tagCls: tag.cls,
      none,
      inc: e.type === 'income',
      amount: (e.type === 'income' ? '+' : '-') + e.amount
    }
  })
  // 未匹配的排最前，先让用户看一眼
  return [...all.filter((r) => r.none), ...all.filter((r) => !r.none)]
})
const visibleRows = computed(() => rows.value.slice(0, showCount.value))
const stats = computed(() => {
  const s = { habit: 0, merchant: 0, edited: 0, none: 0 }
  for (const e of parsed.value?.entries || []) {
    if (e.categoryId == null) s.none++
    else if (e.source === 'RULE' || e.source === 'HABIT') s.habit++
    else if (e.source === 'EDITED') s.edited++
    else s.merchant++
  }
  return s
})

// 核对一笔
const editIdx = ref(-1)
const editCatId = ref(null)
const editRemember = ref(true)
const editEntry = computed(() => (editIdx.value >= 0 && parsed.value ? parsed.value.entries[editIdx.value] : null))
const editOptions = computed(() => {
  if (!editEntry.value) return []
  return editEntry.value.type === 'income' ? incomeCats.value : expenseCats.value
})
const editSameCount = computed(() => {
  const e = editEntry.value
  if (!e || !e.counterparty) return 1
  const k = merchantKey(e.counterparty)
  return parsed.value.entries.filter((x) => x.type === e.type && merchantKey(x.counterparty) === k).length
})
function openEdit(idx) {
  const e = parsed.value.entries[idx]
  editIdx.value = idx
  editCatId.value = e.categoryId
  editRemember.value = !!e.counterparty
}
function closeEdit() {
  editIdx.value = -1
}
function saveEdit() {
  const e = editEntry.value
  if (!e || editCatId.value == null) {
    closeEdit()
    return
  }
  const remember = editRemember.value && !!e.counterparty
  const k = merchantKey(e.counterparty)
  const targets = remember
    ? parsed.value.entries.filter((x) => x.type === e.type && merchantKey(x.counterparty) === k)
    : [e]
  for (const x of targets) {
    x.categoryId = editCatId.value
    x.source = 'EDITED'
  }
  const ruleKey = `${e.type}|${k}`
  const next = { ...pendingRules.value }
  if (remember) next[ruleKey] = { type: e.type, counterparty: e.counterparty, categoryId: editCatId.value }
  else delete next[ruleKey]
  pendingRules.value = next
  closeEdit()
  if (targets.length > 1) uni.showToast({ title: `已一起改 ${targets.length} 笔同商户流水`, icon: 'none' })
}

async function load() {
  try {
    const [accs, cats, ps, ts] = await Promise.all([
      listAccounts(), listCategories(), listProjects(), listTags()
    ])
    accounts.value = accs
    flatCats.value = flattenAll(cats)
    expenseCats.value = flattenCategories(cats.expense)
    incomeCats.value = flattenCategories(cats.income)
    projects.value = ps.filter((p) => !p.archived)
    tags.value = ts
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  }
}

onShow(load)

function pickFile() {
  // #ifdef H5
  uni.chooseFile({
    count: 1,
    extension: ['.csv'],
    success: async (res) => {
      const file = res.tempFiles && res.tempFiles[0]
      if (!file) return
      try {
        const buf = await file.arrayBuffer()
        handleBuffer(buf)
      } catch (e) {
        uni.showToast({ title: '读取文件失败', icon: 'none' })
      }
    }
  })
  // #endif

  // #ifndef H5
  wx.chooseMessageFile({
    count: 1,
    type: 'file',
    extension: ['csv'],
    success: (res) => {
      const fp = res.tempFiles[0].path
      uni.getFileSystemManager().readFile({
        filePath: fp,
        encoding: 'utf-8',
        success: (r) => handleText(r.data),
        fail: () => uni.showToast({ title: '读取文件失败', icon: 'none' })
      })
    }
  })
  // #endif
}

// #ifdef H5
function decode(buf, enc) {
  try {
    return new TextDecoder(enc).decode(buf)
  } catch (e) {
    return ''
  }
}
function handleBuffer(buf) {
  let text = decode(buf, 'utf-8')
  if (!detectSource(text)) {
    const gbk = decode(buf, 'gbk')
    if (detectSource(gbk)) text = gbk
  }
  handleText(text)
}
// #endif

function handleText(text) {
  const src = detectSource(text)
  if (!src) {
    uni.showModal({
      title: '无法识别账单',
      content: '请确认是支付宝或微信导出的 CSV 文件。支付宝为 GBK 编码，小程序端可能无法解析，建议用网页版导入。',
      showCancel: false
    })
    return
  }
  try {
    parsed.value = parseBillText(text, src, flatCats.value)
    pendingRules.value = {}
    showCount.value = PAGE
    if (!parsed.value.entries.length) {
      uni.showToast({ title: '未解析到可导入的流水', icon: 'none' })
    } else {
      classifyParsed()
    }
  } catch (e) {
    uni.showToast({ title: e.message || '解析失败', icon: 'none' })
  }
}

function reset() {
  parsed.value = null
  pendingRules.value = {}
  editIdx.value = -1
}

const canImport = computed(() => parsed.value && parsed.value.entries.length && accounts.value.length)

async function doImport() {
  if (!canImport.value || importing.value) return
  if (parsed.value.expenseCount && !expenseCats.value.length) {
    uni.showToast({ title: '请先创建支出分类', icon: 'none' })
    return
  }
  if (parsed.value.incomeCount && !incomeCats.value.length) {
    uni.showToast({ title: '请先创建收入分类', icon: 'none' })
    return
  }
  importing.value = true
  try {
    const payload = {
      accountId: accounts.value[accIdx.value].id,
      defaultExpenseCategoryId: expenseCats.value[expIdx.value]?.id,
      defaultIncomeCategoryId: incomeCats.value[incIdx.value]?.id,
      projectId: projectOptions.value[projIdx.value]?.id ?? null,
      tagIds: selectedTagIds.value.slice(),
      entries: parsed.value.entries.map((e) => ({
        type: e.type,
        amount: e.amount,
        occurredAt: e.occurredAt,
        note: e.note,
        externalId: e.externalId,
        categoryId: e.categoryId
      }))
    }
    const r = await importBills(payload)
    // 导入成功后再保存「以后都这样记」的规则；失败不影响已导入的流水
    let ruleNote = ''
    const rules = Object.values(pendingRules.value)
    if (rules.length) {
      try {
        const s = await saveCategoryRules(rules)
        ruleNote = `\n已记住 ${s.saved} 个商户的分类，下次导入自动按此分类`
      } catch (e) {
        ruleNote = '\n商户分类规则保存失败，下次需要重新设置'
      }
    }
    uni.showModal({
      title: '导入完成',
      content: `成功 ${r.imported} 笔 · 重复跳过 ${r.skippedDuplicate} · 非法跳过 ${r.skippedInvalid}${ruleNote}`,
      showCancel: false,
      success: () => reset()
    })
  } catch (e) {
    uni.showToast({ title: e.message || '导入失败', icon: 'none' })
  } finally {
    importing.value = false
  }
}
</script>

<template>
  <view class="page">
    <!-- 未解析：说明 + 选文件 -->
    <template v-if="!parsed">
      <view class="card">
        <text class="c-title">账单导入</text>
        <text class="c-desc">
          支持导入支付宝、微信导出的账单 CSV。系统会按你的记账习惯和常见商户自动分好类，导入前可逐笔核对；重复流水按订单号自动去重。
        </text>
        <button class="btn primary" @click="pickFile">选择账单 CSV 文件</button>
      </view>
      <view class="card tips-card">
        <text class="t-title">如何导出账单</text>
        <text class="t-line">· 支付宝：我的 → 账单 → 右上角 → 开具交易流水证明 → 用于个人对账</text>
        <text class="t-line">· 微信：我 → 服务 → 钱包 → 账单 → 常见问题 → 下载账单 → 用于个人对账</text>
        <text class="t-note">支付宝账单为 GBK 编码，小程序端可能无法解析，建议用网页版导入。</text>
      </view>
    </template>

    <!-- 已解析：预览 + 配置 -->
    <template v-else>
      <view class="card preview">
        <view class="pv-head">
          <text class="pv-source">{{ SOURCE_LABEL[parsed.source] }}账单</text>
          <text class="pv-range" v-if="parsed.from">{{ parsed.from }} ~ {{ parsed.to }}</text>
        </view>
        <view class="pv-figs">
          <view class="fig">
            <text class="fig-k">支出 {{ parsed.expenseCount }} 笔</text>
            <text class="fig-v exp">¥{{ formatAmount(parsed.expenseTotal) }}</text>
          </view>
          <view class="fig">
            <text class="fig-k">收入 {{ parsed.incomeCount }} 笔</text>
            <text class="fig-v inc">¥{{ formatAmount(parsed.incomeTotal) }}</text>
          </view>
        </view>
        <text class="pv-skip" v-if="parsed.neutralCount || parsed.invalidCount">
          已跳过中性行 {{ parsed.neutralCount }} · 无法解析 {{ parsed.invalidCount }}
        </text>
      </view>

      <view class="card config">
        <picker class="row" :range="accounts" range-key="name" :value="accIdx" @change="accIdx = Number($event.detail.value)">
          <text class="row-k">导入到账户</text>
          <text class="row-v">{{ accounts[accIdx]?.name || '请先创建账户' }} ›</text>
        </picker>
        <picker
          v-if="parsed.expenseCount"
          class="row"
          :range="expenseCats"
          range-key="label"
          :value="expIdx"
          @change="expIdx = Number($event.detail.value)"
        >
          <text class="row-k">默认支出分类</text>
          <text class="row-v">{{ expenseCats[expIdx]?.label || '无支出分类' }} ›</text>
        </picker>
        <picker
          v-if="parsed.incomeCount"
          class="row"
          :range="incomeCats"
          range-key="label"
          :value="incIdx"
          @change="incIdx = Number($event.detail.value)"
        >
          <text class="row-k">默认收入分类</text>
          <text class="row-v">{{ incomeCats[incIdx]?.label || '无收入分类' }} ›</text>
        </picker>
        <picker
          v-if="projects.length"
          class="row"
          :range="projectOptions"
          range-key="name"
          :value="projIdx"
          @change="projIdx = Number($event.detail.value)"
        >
          <text class="row-k">默认项目</text>
          <text class="row-v">{{ projectOptions[projIdx]?.name || '不设置' }} ›</text>
        </picker>
        <view v-if="tags.length" class="tagrow">
          <text class="row-k">默认标签</text>
          <view class="tagwrap">
            <text
              v-for="t in tags"
              :key="t.id"
              class="tagchip"
              :class="{ on: selectedTagIds.includes(t.id) }"
              @click="toggleTag(t.id)"
            >{{ t.name }}</text>
          </view>
        </view>
        <text class="config-note">没匹配到分类的流水将归入上面的默认分类；项目/标签将统一应用到本次导入的全部流水。</text>
      </view>

      <!-- 逐笔核对：分类来源 + 点开改分类 -->
      <view class="card list-card">
        <view class="lc-head">
          <text class="lc-title">逐笔核对</text>
          <text class="lc-hint">{{ classifying ? '正在按你的习惯分类…' : '点一笔可改分类' }}</text>
        </view>
        <view class="lc-stats">
          <text class="st habit">按习惯 {{ stats.habit }}</text>
          <text class="st merchant">按商户 {{ stats.merchant }}</text>
          <text v-if="stats.edited" class="st edited">已修改 {{ stats.edited }}</text>
          <text v-if="stats.none" class="st none">待确认 {{ stats.none }}</text>
        </view>
        <view v-for="r in visibleRows" :key="r.idx" class="it" :class="{ flag: r.none }" @click="openEdit(r.idx)">
          <view class="it-main">
            <view class="it-top">
              <text class="it-title">{{ r.title }}</text>
              <text class="src" :class="r.tagCls">{{ r.tagText }}</text>
            </view>
            <text class="it-sub">{{ r.sub }}</text>
          </view>
          <text class="it-amt" :class="{ inc: r.inc }">{{ r.amount }}</text>
          <text class="it-arrow">›</text>
        </view>
        <view v-if="rows.length > visibleRows.length" class="more" @click="showCount += PAGE">
          显示更多（还有 {{ rows.length - visibleRows.length }} 笔）
        </view>
      </view>

      <view class="actions">
        <button class="btn" @click="reset">重新选择</button>
        <button class="btn primary" :loading="importing" :disabled="!canImport" @click="doImport">
          导入 {{ parsed.entries.length }} 笔
        </button>
      </view>
    </template>
    <!-- 核对一笔：改分类，可「以后都这样记」 -->
    <view v-if="editEntry" class="mask" @click="closeEdit">
      <view class="sheet" @click.stop>
        <text class="sheet-title">核对这一笔</text>
        <view class="sh-entry">
          <view class="sh-main">
            <text class="sh-name">{{ editEntry.counterparty || editEntry.note || '未命名流水' }}</text>
            <text class="sh-note" v-if="editEntry.note">{{ editEntry.note }}</text>
          </view>
          <text class="sh-amt" :class="{ inc: editEntry.type === 'income' }">{{ (editEntry.type === 'income' ? '+' : '-') + editEntry.amount }}</text>
        </view>
        <text class="sh-k">分类</text>
        <scroll-view scroll-y class="sh-cats">
          <view class="chips">
            <text
              v-for="c in editOptions"
              :key="c.id"
              class="cc"
              :class="{ on: editCatId === c.id }"
              @click="editCatId = c.id"
            >{{ c.label }}</text>
          </view>
        </scroll-view>
        <view v-if="editEntry.counterparty && editCatId != null" class="remember">
          <view class="rm-text">
            <text class="rm-t">以后「{{ editEntry.counterparty }}」都记为「{{ catLabel[editCatId] }}」</text>
            <text class="rm-s">{{ editSameCount > 1 ? `本次 ${editSameCount} 笔同商户流水一起改；` : '' }}以后导入账单也会按这个分</text>
          </view>
          <switch :checked="editRemember" color="#12a150" @change="editRemember = $event.detail.value" />
        </view>
        <view class="sh-actions">
          <button class="btn" @click="closeEdit">取消</button>
          <button class="btn primary" :disabled="editCatId == null" @click="saveEdit">保存</button>
        </view>
      </view>
    </view>
  </view>
</template>


<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
}
.card {
  background: #fff;
  border-radius: 24rpx;
  padding: 40rpx 36rpx;
  margin-bottom: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}
.c-title {
  font-size: 32rpx;
  font-weight: 800;
  color: #1f2937;
}
.c-desc {
  font-size: 26rpx;
  color: #6b7280;
  line-height: 1.6;
}
.btn {
  background: #f0f2f5;
  color: #4b5563;
  border-radius: 44rpx;
  font-size: 30rpx;
}
.btn.primary {
  background: #16a34a;
  color: #fff;
}
.tips-card {
  gap: 12rpx;
}
.t-title {
  font-size: 28rpx;
  font-weight: 700;
  color: #1f2937;
}
.t-line {
  font-size: 24rpx;
  color: #6b7280;
  line-height: 1.6;
}
.t-note {
  font-size: 22rpx;
  color: #f59e0b;
  margin-top: 8rpx;
}

/* 预览 */
.pv-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
}
.pv-source {
  font-size: 30rpx;
  font-weight: 800;
  color: #1f2937;
}
.pv-range {
  font-size: 24rpx;
  color: #9ca3af;
}
.pv-figs {
  display: flex;
  gap: 16rpx;
}
.fig {
  flex: 1;
  background: #f7f8f7;
  border-radius: 16rpx;
  padding: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.fig-k {
  font-size: 24rpx;
  color: #6b7280;
}
.fig-v {
  font-size: 34rpx;
  font-weight: 800;
}
.fig-v.exp { color: #dc2626; }
.fig-v.inc { color: #16a34a; }
.pv-skip {
  font-size: 22rpx;
  color: #9ca3af;
}

/* 配置 */
.config {
  gap: 0;
  padding: 8rpx 36rpx;
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 30rpx 0;
  border-top: 1rpx solid #eef0f2;
}
.config .row:first-child {
  border-top: none;
}
.row-k {
  font-size: 28rpx;
  color: #6b7280;
}
.row-v {
  font-size: 28rpx;
  color: #1f2937;
}
.tagrow {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  padding: 30rpx 0;
  border-top: 1rpx solid #eef0f2;
}
.tagwrap {
  display: flex;
  flex-wrap: wrap;
  gap: 16rpx;
}
.tagchip {
  padding: 12rpx 24rpx;
  border-radius: 999rpx;
  background: #f2f4f6;
  color: #5b6470;
  font-size: 24rpx;
  border: 1rpx solid transparent;
}
.tagchip.on {
  background: #e6f6ec;
  color: #0e8a44;
  font-weight: 700;
  border-color: #12a150;
}
.config-note {
  display: block;
  font-size: 22rpx;
  color: #9ca3af;
  padding: 20rpx 0 24rpx;
}
.actions {
  display: flex;
  gap: 20rpx;
}
.actions .btn {
  flex: 1;
}

/* 逐笔核对 */
.list-card {
  gap: 0;
  padding: 32rpx 36rpx 16rpx;
}
.lc-head { display: flex; justify-content: space-between; align-items: baseline; }
.lc-title { font-size: 30rpx; font-weight: 800; color: #1f2937; }
.lc-hint { font-size: 22rpx; color: #9ca3af; }
.lc-stats { display: flex; flex-wrap: wrap; gap: 12rpx; padding: 18rpx 0 12rpx; }
.st, .src { font-size: 20rpx; font-weight: 700; border-radius: 8rpx; padding: 4rpx 12rpx; }
.habit { color: #0e8a44; background: #e6f6ec; }
.merchant { color: #5b6470; background: #f1f3f5; }
.edited { color: #2f6fd6; background: #e8f0fd; }
.none { color: #b86e00; background: #fff5e3; }
.it { display: flex; align-items: center; gap: 16rpx; padding: 22rpx 0; border-top: 1rpx solid #eef0f2; }
.it.flag { background: #fffaf0; margin: 0 -36rpx; padding: 22rpx 36rpx; }
.it-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.it-top { display: flex; align-items: center; gap: 12rpx; min-width: 0; }
.it-title { font-size: 28rpx; font-weight: 700; color: #1f2937; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.src { flex: 0 0 auto; }
.it-sub { font-size: 22rpx; color: #9ca3af; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.it-amt { font-size: 28rpx; font-weight: 800; color: #e5563d; flex: 0 0 auto; }
.it-amt.inc { color: #12a150; }
.it-arrow { font-size: 30rpx; color: #c4c9d0; }
.more { text-align: center; font-size: 26rpx; color: #0e8a44; font-weight: 700; padding: 24rpx 0 12rpx; border-top: 1rpx solid #eef0f2; }

/* 核对一笔 */
.mask { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.42); display: flex; align-items: flex-end; z-index: 50; }
.sheet { width: 100%; background: #fff; border-radius: 28rpx 28rpx 0 0; padding: 32rpx 32rpx calc(32rpx + env(safe-area-inset-bottom)); box-sizing: border-box; display: flex; flex-direction: column; gap: 20rpx; }
.sheet-title { text-align: center; font-size: 30rpx; font-weight: 800; color: #1f2937; }
.sh-entry { display: flex; align-items: center; gap: 16rpx; background: #f6f7f9; border-radius: 16rpx; padding: 20rpx 24rpx; }
.sh-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.sh-name { font-size: 28rpx; font-weight: 700; color: #1f2937; }
.sh-note { font-size: 22rpx; color: #9ca3af; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.sh-amt { font-size: 34rpx; font-weight: 800; color: #e5563d; }
.sh-amt.inc { color: #12a150; }
.sh-k { font-size: 24rpx; color: #6b7280; }
.sh-cats { max-height: 40vh; }
.chips { display: flex; flex-wrap: wrap; gap: 16rpx; }
.cc { padding: 14rpx 24rpx; border-radius: 999rpx; background: #f2f4f6; color: #5b6470; font-size: 26rpx; border: 2rpx solid transparent; }
.cc.on { background: #e6f6ec; color: #0e8a44; font-weight: 700; border-color: #12a150; }
.remember { display: flex; align-items: center; gap: 16rpx; background: #f6f7f9; border-radius: 16rpx; padding: 20rpx 24rpx; }
.rm-text { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.rm-t { font-size: 26rpx; color: #1f2937; font-weight: 600; }
.rm-s { font-size: 22rpx; color: #9ca3af; }
.sh-actions { display: flex; gap: 20rpx; }
.sh-actions .btn { flex: 1; }
</style>
