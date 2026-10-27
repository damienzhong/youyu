<script setup>
/**
 * 截图记账：选图 → 逐张识别 → 核对（分组、改分类 / 金额、记住规则）→ 记入 → 可整批撤销。
 * 识别结果只是预览；入账复用 POST /imports/bills（externalId = shot:<批次>:<序号>）。
 */
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { listAccounts } from '../../api/account'
import { listCategories, flattenCategories } from '../../api/category'
import { importBills, saveCategoryRules } from '../../api/bill'
import { screenshotStatus, recognizeScreenshots, undoScreenshotBatch } from '../../api/screenshot'
import { formatAmount } from '../../utils/format'
import {
  mergeShots, groupRows, rowFlags, selectedTotals, newBatchId, nowLocalIso, displayTime, merchantKey
} from '../../utils/screenshot'

const CONSENT_KEY = 'youyu_vision_consent'

const step = ref('intro') // intro | recognizing | review | done
const status = ref({ enabled: true, remaining: 0, dailyLimit: 10, maxImages: 9 })
const consented = ref(!!uni.getStorageSync(CONSENT_KEY))
const accounts = ref([])
const expenseCats = ref([])
const incomeCats = ref([])
const loadErr = ref('')

async function load() {
  try {
    const [st, accs, cats] = await Promise.all([screenshotStatus(), listAccounts(), listCategories()])
    status.value = st
    accounts.value = (accs || []).filter((a) => !a.hidden)
    expenseCats.value = flattenCategories(cats.expense)
    incomeCats.value = flattenCategories(cats.income)
  } catch (e) {
    if (e && e.code !== 'HTTP_401') loadErr.value = e.message || '加载失败'
  }
}
onLoad(load)

const catLabel = computed(() => {
  const m = {}
  for (const c of [...expenseCats.value, ...incomeCats.value]) m[c.id] = c.label
  return m
})

// ---------------- 选图 ----------------
function agree() {
  uni.setStorageSync(CONSENT_KEY, '1')
  consented.value = true
}

function pick(source) {
  if (!consented.value) agree()
  if (!status.value.enabled) {
    uni.showToast({ title: '截图识别暂未开启', icon: 'none' })
    return
  }
  const max = Math.min(status.value.maxImages || 9, status.value.remaining || 0)
  if (max <= 0) {
    uni.showModal({ title: '今天的次数用完了', content: '每天可免费识别 ' + status.value.dailyLimit + ' 张，明天再来，或先手动记一笔。', showCancel: false })
    return
  }
  uni.chooseImage({
    count: source === 'camera' ? 1 : max,
    sizeType: ['compressed'],
    sourceType: [source === 'camera' ? 'camera' : 'album'],
    success: (res) => start(res.tempFilePaths || []),
    fail: () => {}
  })
}

// #ifdef H5
/** H5：画到 canvas，长边压到 1600px，转 JPEG base64。 */
function toBase64(path) {
  return new Promise((resolve, reject) => {
    const img = new Image()
    img.onload = () => {
      const scale = Math.min(1, 1600 / Math.max(img.width, img.height))
      const c = document.createElement('canvas')
      c.width = Math.round(img.width * scale)
      c.height = Math.round(img.height * scale)
      const ctx = c.getContext('2d')
      ctx.fillStyle = '#fff'
      ctx.fillRect(0, 0, c.width, c.height)
      ctx.drawImage(img, 0, 0, c.width, c.height)
      resolve(c.toDataURL('image/jpeg', 0.82).split(',')[1])
    }
    img.onerror = () => reject(new Error('图片读取失败'))
    img.src = path
  })
}
// #endif
// #ifndef H5
/** 小程序：先压缩（长边约 1600px），再按 base64 读出。 */
function toBase64(path) {
  return new Promise((resolve, reject) => {
    const read = (p) => uni.getFileSystemManager().readFile({
      filePath: p, encoding: 'base64', success: (r) => resolve(r.data), fail: () => reject(new Error('图片读取失败'))
    })
    uni.compressImage({ src: path, quality: 80, compressedWidth: 1600, success: (r) => read(r.tempFilePath), fail: () => read(path) })
  })
}
// #endif

// ---------------- 识别 ----------------
const total = ref(0)
const doneCount = ref(0)
const shots = ref([])
const cancelled = ref(false)
const failMsg = ref('')

async function start(paths) {
  if (!paths.length) return
  total.value = paths.length
  doneCount.value = 0
  shots.value = []
  cancelled.value = false
  failMsg.value = ''
  step.value = 'recognizing'
  for (const p of paths) {
    if (cancelled.value) break
    try {
      const data = await toBase64(p)
      const r = await recognizeScreenshots([{ data, mime: 'image/jpeg' }])
      shots.value.push(...(r.shots || []))
      status.value = { ...status.value, remaining: r.remaining }
    } catch (e) {
      shots.value.push({ status: 'FAILED', entries: [] })
      if (e && e.code === 'VISION_QUOTA_EXCEEDED') {
        failMsg.value = e.message
        break
      }
      if (e && e.code !== 'VISION_UNAVAILABLE') failMsg.value = e.message || '识别失败'
    }
    doneCount.value++
  }
  if (cancelled.value) {
    step.value = 'intro'
    return
  }
  buildReview()
}

function cancel() {
  cancelled.value = true
}

// ---------------- 核对 ----------------
const rows = ref([])
const meta = ref({})
// 每张截图各自的记入账户（下标对应 accounts），一批里可能混着微信和支付宝的截图
const shotAcc = ref([])
const shotSuggested = ref([])
const pendingRules = ref({})
const shotStats = computed(() => {
  const s = { ok: 0, notBill: 0, failed: 0, dropped: 0 }
  for (const x of shots.value) {
    if (x.status === 'OK') s.ok++
    else if (x.status === 'NOT_BILL') s.notBill++
    else s.failed++
    s.dropped += x.dropped || 0
  }
  return s
})

function buildReview() {
  const merged = mergeShots(shots.value)
  rows.value = merged.rows
  meta.value = merged.meta
  pendingRules.value = {}
  // 每张截图按自己识别出的付款方式选账户；识别不出的跟随本批最常见的那个，再不行用第一个账户
  const idxOf = (id) => accounts.value.findIndex((a) => String(a.id) === String(id))
  const votes = {}
  for (const s of shots.value) if (s.suggestedAccountId) votes[s.suggestedAccountId] = (votes[s.suggestedAccountId] || 0) + 1
  const best = Object.keys(votes).sort((a, b) => votes[b] - votes[a])[0]
  const fallback = Math.max(0, idxOf(best))
  shotAcc.value = shots.value.map((s) => (idxOf(s.suggestedAccountId) >= 0 ? idxOf(s.suggestedAccountId) : fallback))
  shotSuggested.value = shots.value.map((s) => idxOf(s.suggestedAccountId) >= 0)
  step.value = 'review'
}

/** 有流水的截图；都记入同一个账户时合并成一行选择。 */
const accRows = computed(() => {
  const used = [...new Set(rows.value.map((r) => r.shot))].sort((a, b) => a - b)
  const list = used.map((si, n) => ({
    si,
    label: `截图 ${n + 1}${shots.value[si] && shots.value[si].app ? ' · ' + shots.value[si].app : ''}`,
    idx: shotAcc.value[si] ?? 0,
    suggested: !!shotSuggested.value[si]
  }))
  // 合并成一行的条件：都记同一个账户，且要么都识别出了付款方式、要么都没识别出（避免把支付宝的截图默默记进微信）
  const same = list.every((x) => x.idx === (list[0] && list[0].idx))
  const mixed = list.some((x) => x.suggested) && list.some((x) => !x.suggested)
  return { single: same && !mixed, list }
})
function setAcc(si, idx) {
  const next = shotAcc.value.slice()
  const sug = shotSuggested.value.slice()
  const targets = si == null ? accRows.value.list.map((x) => x.si) : [si]
  for (const t of targets) {
    next[t] = idx
    sug[t] = false
  }
  shotAcc.value = next
  shotSuggested.value = sug
}

const groups = computed(() => groupRows(rows.value))
const totals = computed(() => selectedTotals(rows.value))
const groupList = computed(() => [
  { key: "attention", title: "⚠️ 需要你看一眼", list: groups.value.attention },
  { key: "ready", title: "✓ 已分好类", list: groups.value.ready },
  { key: "duplicate", title: "⧉ 疑似重复 · 默认不记", list: groups.value.duplicate },
  { key: "excluded", title: "↷ 不计入", list: groups.value.excluded }
])

function toggle(r) {
  if (!r.type) {
    uni.showToast({ title: '转账类流水暂不支持直接记入', icon: 'none' })
    return
  }
  r.state = r.state === 'record' ? 'skip' : 'record'
}

function rowSub(r) {
  const parts = [displayTime(r.occurredAt, r.dateOnly)]
  if (r.reason === 'duplicate' && r.duplicate) return `账本里已有：${displayTime(r.duplicate.occurredAt)} ¥${formatAmount(r.duplicate.amount)}`
  if (r.reason === 'offset') return r.direction === 'REFUND' ? '与原支出抵消，两笔都不记' : '已全额退款，与退款抵消'
  if (r.reason === 'transfer') return '账户之间的转账，不计支出'
  if (r.direction === 'REFUND' && r.state === 'skip') return '没找到原支出，默认不记'
  if (r.partialRefund) parts.push(`已扣除退款 ${r.partialRefund}`)
  parts.push(r.categoryId != null ? catLabel.value[r.categoryId] || '未知分类' : '默认分类')
  return parts.join(' · ')
}
function rowTag(r) {
  const f = rowFlags(r)
  if (f.amount) return { text: '金额请核对', cls: 'warn' }
  if (r.categorySource === 'EDITED') return { text: '已修改', cls: 'edited' }
  if (r.categoryId == null) return { text: '待选分类', cls: 'warn' }
  if (r.categorySource === 'AI') return { text: '✨ AI 推测', cls: 'ai' }
  if (f.time) return { text: r.occurredAt ? '只有日期' : '时间未识别', cls: 'warn' }
  if (r.categorySource === 'RULE' || r.categorySource === 'HABIT') return { text: '按习惯', cls: 'habit' }
  return { text: '按商户', cls: 'merchant' }
}

// 核对一笔
const edit = ref(null)
const editAmount = ref('')
const editCatId = ref(null)
const editRemember = ref(true)
const editOptions = computed(() => (edit.value && edit.value.type === 'income' ? incomeCats.value : expenseCats.value))
function openEdit(r) {
  if (!r.type) return toggle(r)
  edit.value = r
  editAmount.value = r.amount
  editCatId.value = r.categoryId
  editRemember.value = !!r.merchant
}
function closeEdit() {
  edit.value = null
}
function saveEdit() {
  const r = edit.value
  const n = Number(editAmount.value)
  if (!Number.isFinite(n) || n <= 0 || !/^\d+(\.\d{1,2})?$/.test(String(editAmount.value).trim())) {
    uni.showToast({ title: '金额最多两位小数', icon: 'none' })
    return
  }
  if (Number(r.amount) !== n) {
    r.amount = n.toFixed(2)
    r.amountUncertain = false
  } else {
    r.amountUncertain = false
  }
  if (editCatId.value != null && editCatId.value !== r.categoryId) {
    r.categoryId = editCatId.value
    r.categorySource = 'EDITED'
  } else if (editCatId.value != null && r.categorySource === 'AI') {
    r.categorySource = 'EDITED' // 用户确认过 AI 的推测
  }
  const k = `${r.type}|${merchantKey(r.merchant)}`
  const next = { ...pendingRules.value }
  if (editRemember.value && r.merchant && r.categorySource === 'EDITED') {
    next[k] = { type: r.type, counterparty: r.merchant, categoryId: r.categoryId }
    // 本批同商户的其它流水一起改
    for (const x of rows.value) {
      if (x !== r && x.type === r.type && merchantKey(x.merchant) === merchantKey(r.merchant)) {
        x.categoryId = r.categoryId
        x.categorySource = 'EDITED'
      }
    }
  } else delete next[k]
  pendingRules.value = next
  if (!r.occurredAt) r.occurredAt = nowLocalIso()
  r.state = 'record'
  closeEdit()
}
function skipEdit() {
  edit.value.state = 'skip'
  closeEdit()
}

// ---------------- 记入 ----------------
const importing = ref(false)
const result = ref(null) // { batch, imported, expense, income, bars, learned, from, to }

/** 兜底分类：优先名为「其他」的，其次第一个。 */
function fallbackCat(list) {
  return (list.find((c) => c.name === '其他') || list[0] || {}).id
}

async function doImport() {
  if (importing.value || !totals.value.count) return
  if (!accounts.value.length) {
    uni.showToast({ title: '请先添加一个账户', icon: 'none' })
    return
  }
  const picked = rows.value.filter((r) => r.state === 'record' && r.type)
  const batch = newBatchId()
  importing.value = true
  try {
    const now = nowLocalIso()
    // 按记入账户分组提交（同一批次号，便于整批撤销）
    const byAcc = new Map()
    picked.forEach((x, i) => {
      const acc = accounts.value[shotAcc.value[x.shot] ?? 0] || accounts.value[0]
      if (!byAcc.has(acc.id)) byAcc.set(acc.id, { acc, entries: [] })
      byAcc.get(acc.id).entries.push({
        type: x.type,
        amount: x.amount,
        occurredAt: x.occurredAt || now,
        note: x.note,
        externalId: `shot:${batch}:${i}`,
        categoryId: x.categoryId
      })
    })
    const r = { imported: 0, skippedDuplicate: 0, skippedInvalid: 0 }
    for (const g of byAcc.values()) {
      const one = await importBills({
        accountId: g.acc.id,
        defaultExpenseCategoryId: fallbackCat(expenseCats.value),
        defaultIncomeCategoryId: fallbackCat(incomeCats.value),
        projectId: null,
        tagIds: [],
        entries: g.entries
      })
      r.imported += one.imported
      r.skippedDuplicate += one.skippedDuplicate
      r.skippedInvalid += one.skippedInvalid
    }
    const rules = Object.values(pendingRules.value)
    let learned = []
    if (rules.length) {
      try {
        await saveCategoryRules(rules)
        learned = rules.map((x) => `${x.counterparty} → ${catLabel.value[x.categoryId] || ''}`)
      } catch (e) {
        learned = []
      }
    }
    // 这批花在哪（按分类聚合支出）
    const byCat = {}
    for (const x of picked) {
      if (x.type !== 'expense') continue
      const name = x.categoryId != null ? (catLabel.value[x.categoryId] || '其他').split(' / ').pop() : '其他'
      byCat[name] = (byCat[name] || 0) + Math.round(Number(x.amount) * 100)
    }
    const bars = Object.entries(byCat).sort((a, b) => b[1] - a[1]).map(([name, c]) => ({ name, amount: (c / 100).toFixed(2) }))
    const maxBar = bars.length ? Number(bars[0].amount) : 1
    bars.forEach((b) => { b.pct = Math.max(6, Math.round((Number(b.amount) / maxBar) * 100)) })
    const days = picked.map((x) => (x.occurredAt || now).slice(0, 10)).sort()
    result.value = {
      batch, imported: r.imported, skipped: r.skippedDuplicate + r.skippedInvalid,
      expense: totals.value.expense, income: totals.value.income, bars, learned,
      from: days[0], to: days[days.length - 1], account: [...byAcc.values()].map((g) => g.acc.name).join('、')
    }
    uni.setStorageSync('youyu_vision_used', '1') // 首页截图记账横幅从此收起
    step.value = 'done'
  } catch (e) {
    uni.showToast({ title: e.message || '记入失败', icon: 'none' })
  } finally {
    importing.value = false
  }
}

const undoing = ref(false)
function undo() {
  uni.showModal({
    title: '撤销这批流水？',
    content: `刚记入的 ${result.value.imported} 笔会移到回收站，账户余额同步回滚。`,
    confirmText: '撤销',
    success: async (m) => {
      if (!m.confirm) return
      undoing.value = true
      try {
        const r = await undoScreenshotBatch(result.value.batch)
        uni.showToast({ title: `已撤销 ${r.undone} 笔`, icon: 'none' })
        reset()
      } catch (e) {
        uni.showToast({ title: e.message || '撤销失败', icon: 'none' })
      } finally {
        undoing.value = false
      }
    }
  })
}

function reset() {
  step.value = 'intro'
  rows.value = []
  shots.value = []
  result.value = null
  load()
}
function goRecords() {
  uni.navigateTo({ url: '/pages/records/records' })
}
function goManual() {
  uni.redirectTo({ url: '/pages/record/record' })
}
function fmtDay(d) {
  return d ? `${Number(d.slice(5, 7))}月${Number(d.slice(8, 10))}日` : ''
}
</script>

<template>
  <view class="page">
    <!-- ① 介绍 / 首次说明 / 选图 -->
    <view v-if="step === 'intro'" class="intro">
      <view class="card hero">
        <text class="h-t">截图记账</text>
        <text class="h-s">自动识别金额、时间、商户，并按你的习惯分好类</text>
        <view class="egs">
          <view class="eg"><text class="eg-i">✅</text><text class="eg-l">支付成功页</text></view>
          <view class="eg"><text class="eg-i">🧾</text><text class="eg-l">账单列表</text></view>
          <view class="eg"><text class="eg-i">📦</text><text class="eg-l">订单详情</text></view>
        </view>
      </view>
      <view class="card pv">
        <view class="pv-r"><text class="pv-i">🔒</text><view class="pv-x"><text class="pv-t">截图只用于这一次识别</text><text class="pv-s">识别完立即丢弃，不保存原图，不用于训练模型</text></view></view>
        <view class="pv-r"><text class="pv-i">✋</text><view class="pv-x"><text class="pv-t">结果先给你核对</text><text class="pv-s">你点「记入」之前，不会写进账本</text></view></view>
        <view class="pv-r"><text class="pv-i">🎁</text><view class="pv-x"><text class="pv-t">每天免费识别 {{ status.dailyLimit }} 张</text><text class="pv-s">今日剩余 {{ status.remaining }} 张 · 识别失败不计入</text></view></view>
      </view>
      <text v-if="loadErr" class="err">{{ loadErr }}</text>
      <text v-else-if="!status.enabled" class="err">截图识别暂未开启</text>
      <view class="btns">
        <button class="btn ghost" @click="pick('camera')">📸 拍照</button>
        <button class="btn pri" @click="pick('album')">🖼 从相册选</button>
      </view>
      <text v-if="!consented" class="agree">继续即表示同意截图交由阿里云百炼识别，识别后不留存</text>
    </view>

    <!-- ② 识别中 -->
    <view v-else-if="step === 'recognizing'" class="scan">
      <view class="stack"><view class="s2"></view><view class="s1"><view v-for="i in 7" :key="i" class="bl"></view><view class="line"></view></view></view>
      <text class="sc-t">正在识别 {{ total }} 张截图</text>
      <text class="sc-s">已完成 {{ doneCount }} / {{ total }}</text>
      <view class="steps">
        <view class="st" :class="doneCount > 0 ? 'ok' : 'ing'"><text class="st-i">{{ doneCount > 0 ? '✓' : '' }}</text><text>读取截图文字</text></view>
        <view class="st" :class="doneCount > 0 ? 'ok' : ''"><text class="st-i">{{ doneCount > 0 ? '✓' : '' }}</text><text>拆分出每一笔流水</text></view>
        <view class="st ing"><text class="st-i"></text><text>按你的习惯分类、查重复</text></view>
      </view>
      <view class="sc-foot">
        <text class="note">🔒 截图仅用于本次识别，完成后即丢弃</text>
        <button class="btn ghost" @click="cancel">取消识别</button>
      </view>
    </view>

    <!-- ③ 核对 -->
    <view v-else-if="step === 'review'" class="review">
      <view v-if="!rows.length" class="card empty">
        <text class="em-i">🤔</text>
        <text class="em-t">{{ shotStats.notBill ? '这张图里没找到流水' : '没识别出流水' }}</text>
        <text class="em-s">{{ failMsg || (shotStats.failed ? '识别服务暂时不可用，本次不计入额度' : '换一张带金额和时间的账单截图试试') }}</text>
        <view class="supp">
          <text>✓ 微信 / 支付宝支付成功页</text><text>✓ 微信 / 支付宝账单列表</text>
          <text>✓ 银行 App 交易明细</text><text>✓ 美团 / 淘宝订单详情</text>
        </view>
        <view class="btns">
          <button class="btn ghost" @click="goManual">手动记一笔</button>
          <button class="btn pri" @click="reset">换一张截图</button>
        </view>
        <text class="quota">今日还可识别 {{ status.remaining }} 张</text>
      </view>

      <template v-else>
        <view class="card sum">
          <view class="sum-l1">
            <text class="sum-t">将记入 {{ totals.count }} 笔</text>
            <text class="sum-v">-{{ formatAmount(totals.expense) }}</text>
          </view>
          <text class="sum-m">
            {{ meta.images }} 张截图 · 识别 {{ meta.recognized }} 笔<text v-if="meta.overlap"> · 重叠 {{ meta.overlap }} 笔已合并</text><text v-if="meta.duplicates"> · {{ meta.duplicates }} 笔重复</text><text v-if="meta.offsetPairs"> · {{ meta.offsetPairs }} 对退款抵消</text><text v-if="meta.transfers"> · {{ meta.transfers }} 笔转账</text>
          </text>
          <text v-if="Number(totals.income) > 0" class="sum-m">另有收入 +{{ formatAmount(totals.income) }}</text>
          <text v-if="shotStats.dropped" class="sum-w">有 {{ shotStats.dropped }} 笔金额看不清已略过，可手动补记</text>
          <text v-if="failMsg" class="sum-w">{{ failMsg }}</text>
          <picker v-if="accRows.single" class="acc" :range="accounts" range-key="name" :value="accRows.list[0] ? accRows.list[0].idx : 0" @change="setAcc(null, Number($event.detail.value))">
<view class="acc-in">
            <text class="acc-k">记入账户</text>
            <text class="acc-v">{{ accRows.list[0] && accounts[accRows.list[0].idx] ? accounts[accRows.list[0].idx].name : '请先添加账户' }}<text v-if="accRows.list[0] && accRows.list[0].suggested" class="tag merchant">截图识别</text><text v-else class="tag warn">请确认</text> ›</text></view>

          </picker>
          <template v-else>
            <text class="acc-h">这批截图的付款方式不一致或没认出来，请分别确认记入哪个账户：</text>
            <picker v-for="a in accRows.list" :key="a.si" class="acc" :range="accounts" range-key="name" :value="a.idx" @change="setAcc(a.si, Number($event.detail.value))">
<view class="acc-in">
              <text class="acc-k">{{ a.label }}</text>
              <text class="acc-v">{{ accounts[a.idx] ? accounts[a.idx].name : '请先添加账户' }}<text v-if="a.suggested" class="tag merchant">截图识别</text><text v-else class="tag warn">请确认</text> ›</text></view>

            </picker>
          </template>
        </view>

        <template v-for="g in groupList" :key="g.key">
          <view v-if="g.list.length" class="gh"><text>{{ g.title }} · {{ g.list.length }}</text></view>
          <view v-if="g.list.length" class="card lst">
            <view v-for="r in g.list" :key="r.key" class="it" :class="{ flag: g.key === 'attention', off: r.state !== 'record' }">
              <view class="ck" :class="{ on: r.state === 'record' }" @click.stop="toggle(r)"><text v-if="r.state === 'record'">✓</text></view>
              <view class="it-main" @click="openEdit(r)">
                <view class="it-top">
                  <text class="it-name">{{ r.merchant || r.item || '未命名流水' }}</text>
                  <text v-if="g.key === 'attention' || g.key === 'ready'" class="tag" :class="rowTag(r).cls">{{ rowTag(r).text }}</text>
                </view>
                <text class="it-sub">{{ rowSub(r) }}</text>
              </view>
              <text class="it-amt" :class="{ inc: r.type === 'income', tr: !r.type, warn: r.amountUncertain }" @click="openEdit(r)">{{ r.type === 'income' ? '+' : '-' }}{{ r.amount }}</text>
            </view>
          </view>
        </template>
        <view style="height: 200rpx"></view>
        <view class="bar">
          <view class="bar-sel"><text class="bs-k">已选</text><text class="bs-v">{{ totals.count }} 笔</text></view>
          <button class="btn pri" :loading="importing" :disabled="!totals.count" @click="doImport">全部记入</button>
        </view>
      </template>
    </view>

    <!-- ④ 完成 -->
    <view v-else-if="step === 'done' && result" class="done">
      <view class="fin">
        <view class="badge">✓</view>
        <text class="fin-t">已记入 {{ result.imported }} 笔</text>
        <text class="fin-s">支出 ¥{{ formatAmount(result.expense) }} · {{ result.account }}<text v-if="result.from"> · {{ fmtDay(result.from) }}<text v-if="result.to !== result.from">–{{ fmtDay(result.to) }}</text></text></text>
        <text v-if="result.skipped" class="fin-s">另有 {{ result.skipped }} 笔重复或无效，已跳过</text>
      </view>
      <view v-if="result.bars.length" class="card bars">
        <text class="bh">这批花在哪</text>
        <view v-for="b in result.bars" :key="b.name" class="brow">
          <text class="bn">{{ b.name }}</text>
          <view class="bt"><view class="bi" :style="{ width: b.pct + '%' }"></view></view>
          <text class="bv">{{ formatAmount(b.amount) }}</text>
        </view>
      </view>
      <view v-if="result.learned.length" class="learn">
        <text>🧠 记住了 {{ result.learned.length }} 个商家：{{ result.learned.join('、') }}。下次截图或导入账单，会直接按这个分。</text>
      </view>
      <view class="undo"><text>记错了？</text><text class="undo-a" @click="undo">撤销这 {{ result.imported }} 笔</text><text>（24 小时内）</text></view>
      <view class="btns foot">
        <button class="btn ghost" @click="reset">继续识别</button>
        <button class="btn pri" @click="goRecords">查看明细</button>
      </view>
    </view>

    <!-- 核对一笔 -->
    <view v-if="edit" class="mask" @click="closeEdit">
      <view class="sheet" @click.stop>
        <text class="sheet-t">核对这一笔</text>
        <view class="sh-entry">
          <view class="sh-m"><text class="sh-n">{{ edit.merchant || '未命名流水' }}</text><text class="sh-s">{{ edit.item || '' }} {{ displayTime(edit.occurredAt, edit.dateOnly) }}</text></view>
        </view>
        <view class="amt-row" :class="{ warn: edit.amountUncertain }">
          <text class="amt-k">金额 ¥</text>
          <input class="amt-in" type="digit" v-model="editAmount" />
          <text v-if="edit.amountUncertain" class="amt-w">截图上看不太清，请核对</text>
        </view>
        <view class="sh-k"><text>分类</text><text v-if="edit.categorySource === 'AI'" class="tag ai">✨ AI 推测</text></view>
        <scroll-view scroll-y class="cats">
          <view class="chips">
            <text v-for="c in editOptions" :key="c.id" class="cc" :class="{ on: editCatId === c.id }" @click="editCatId = c.id">{{ c.label }}</text>
          </view>
        </scroll-view>
        <view v-if="edit.merchant && editCatId != null && editCatId !== edit.categoryId" class="remember">
          <view class="rm-x"><text class="rm-t">以后「{{ edit.merchant }}」都记为「{{ catLabel[editCatId] }}」</text><text class="rm-s">本批同商户一起改，以后导入账单也会按这个分</text></view>
          <switch :checked="editRemember" color="#12a150" @change="editRemember = $event.detail.value" />
        </view>
        <view class="btns">
          <button class="btn ghost" @click="skipEdit">这笔不记</button>
          <button class="btn pri" @click="saveEdit">保存</button>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; background: #f2f4f6; padding: 24rpx 28rpx 40rpx; box-sizing: border-box; color: #16181c; }
.card { background: #fff; border-radius: 28rpx; box-shadow: 0 8rpx 28rpx rgba(20, 24, 28, 0.05); }
.btns { display: flex; gap: 20rpx; margin-top: 28rpx; }
.btns .btn { flex: 1; }
.btn { height: 92rpx; line-height: 92rpx; border-radius: 26rpx; font-size: 30rpx; font-weight: 800; border: none; }
.btn::after { border: none; }
.btn.pri { background: linear-gradient(135deg, #18b85a, #0e8a44); color: #fff; box-shadow: 0 14rpx 30rpx rgba(18, 161, 80, 0.26); }
.btn.ghost { background: #e9ecef; color: #5b6470; }
.btn[disabled] { opacity: 0.5; }
.tag { display: inline-block; font-size: 20rpx; font-weight: 800; border-radius: 10rpx; padding: 2rpx 10rpx; margin-left: 10rpx; vertical-align: 2rpx; }
.tag.habit { color: #0e8a44; background: #e6f6ec; }
.tag.merchant { color: #5b6470; background: #f1f3f5; }
.tag.edited { color: #2f6fd6; background: #e8f0fd; }
.tag.warn { color: #b86e00; background: #fff5e3; }
.tag.ai { color: #4a3bd6; background: #efedff; }

/* ① 介绍 */
.hero { padding: 40rpx 32rpx 32rpx; text-align: center; }
.h-t { display: block; font-size: 36rpx; font-weight: 800; }
.h-s { display: block; font-size: 24rpx; color: #5b6470; margin-top: 10rpx; }
.egs { display: flex; gap: 16rpx; margin-top: 28rpx; }
.eg { flex: 1; background: #f6f7f9; border-radius: 22rpx; padding: 22rpx 0; display: flex; flex-direction: column; align-items: center; gap: 10rpx; }
.eg-i { font-size: 44rpx; }
.eg-l { font-size: 22rpx; color: #5b6470; font-weight: 700; }
.pv { margin-top: 24rpx; padding: 12rpx 28rpx; }
.pv-r { display: flex; gap: 20rpx; padding: 22rpx 0; border-top: 1rpx solid #eceef1; }
.pv-r:first-child { border-top: none; }
.pv-i { width: 56rpx; height: 56rpx; border-radius: 16rpx; background: #f6f7f9; text-align: center; line-height: 56rpx; font-size: 28rpx; flex: 0 0 auto; }
.pv-x { display: flex; flex-direction: column; gap: 4rpx; }
.pv-t { font-size: 27rpx; font-weight: 700; }
.pv-s { font-size: 22rpx; color: #9aa2ad; }
.err { display: block; text-align: center; color: #e5484d; font-size: 24rpx; margin-top: 20rpx; }
.agree { display: block; text-align: center; font-size: 22rpx; color: #9aa2ad; margin-top: 20rpx; }

/* ② 识别中 */
.scan { display: flex; flex-direction: column; align-items: center; padding-top: 60rpx; min-height: 90vh; }
.stack { position: relative; width: 300rpx; height: 440rpx; }
.s1, .s2 { position: absolute; width: 256rpx; height: 420rpx; border-radius: 28rpx; background: #fff; box-shadow: 0 20rpx 50rpx rgba(20, 24, 28, 0.12); }
.s2 { left: 44rpx; top: 20rpx; transform: rotate(6deg); }
.s1 { left: 0; top: 0; transform: rotate(-4deg); overflow: hidden; padding: 30rpx 22rpx; box-sizing: border-box; display: flex; flex-direction: column; gap: 26rpx; }
.bl { height: 22rpx; border-radius: 11rpx; background: #e9ecef; }
.line { position: absolute; left: -12rpx; right: -12rpx; height: 80rpx; top: 0; background: linear-gradient(180deg, rgba(109, 92, 255, 0), rgba(109, 92, 255, 0.28) 80%, rgba(109, 92, 255, 0.9)); border-bottom: 4rpx solid #7d6dff; animation: scan 2.2s ease-in-out infinite; }
@keyframes scan { 0% { top: -80rpx; } 100% { top: 420rpx; } }
.sc-t { font-size: 34rpx; font-weight: 800; margin-top: 50rpx; }
.sc-s { font-size: 24rpx; color: #9aa2ad; margin-top: 8rpx; }
.steps { margin-top: 44rpx; width: 480rpx; display: flex; flex-direction: column; gap: 24rpx; }
.st { display: flex; align-items: center; gap: 20rpx; font-size: 26rpx; color: #9aa2ad; }
.st-i { width: 36rpx; height: 36rpx; border-radius: 50%; border: 4rpx solid #d5d9df; box-sizing: border-box; text-align: center; line-height: 28rpx; font-size: 20rpx; color: #fff; }
.st.ok { color: #16181c; }
.st.ok .st-i { background: #12a150; border-color: #12a150; }
.st.ing { color: #16181c; font-weight: 700; }
.st.ing .st-i { border-color: #7d6dff; border-top-color: transparent; animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.sc-foot { margin-top: auto; width: 100%; }
.note { display: block; font-size: 22rpx; color: #5b6470; background: #fff; border-radius: 20rpx; padding: 20rpx 24rpx; margin-bottom: 20rpx; }
.sc-foot .btn { width: 100%; }

/* ③ 核对 */
.empty { padding: 50rpx 32rpx 32rpx; display: flex; flex-direction: column; align-items: center; }
.em-i { font-size: 80rpx; }
.em-t { font-size: 32rpx; font-weight: 800; margin-top: 16rpx; }
.em-s { font-size: 24rpx; color: #9aa2ad; margin-top: 10rpx; text-align: center; }
.supp { display: flex; flex-wrap: wrap; gap: 12rpx; margin-top: 28rpx; justify-content: center; }
.supp text { font-size: 22rpx; color: #5b6470; background: #f6f7f9; border-radius: 16rpx; padding: 12rpx 18rpx; }
.empty .btns { width: 100%; }
.quota { font-size: 22rpx; color: #9aa2ad; margin-top: 18rpx; }
.sum { padding: 26rpx 28rpx 6rpx; }
.sum-l1 { display: flex; justify-content: space-between; align-items: baseline; }
.sum-t { font-size: 30rpx; font-weight: 800; }
.sum-v { font-size: 44rpx; font-weight: 800; color: #e5563d; }
.sum-m { display: block; font-size: 22rpx; color: #9aa2ad; margin-top: 6rpx; }
.sum-w { display: block; font-size: 22rpx; color: #b86e00; margin-top: 6rpx; }
.acc { display: block; border-top: 2rpx dashed #eceef1; margin-top: 18rpx; padding: 22rpx 0; font-size: 26rpx; }
.acc-in { display: flex; justify-content: space-between; align-items: center; gap: 16rpx; }
.acc-k { color: #5b6470; }
.acc-h { display: block; font-size: 22rpx; color: #b86e00; padding-top: 18rpx; }
.acc-v { font-weight: 800; }
.gh { padding: 26rpx 12rpx 12rpx; font-size: 23rpx; color: #9aa2ad; font-weight: 700; }
.lst { overflow: hidden; }
.it { display: flex; align-items: center; gap: 18rpx; padding: 22rpx 24rpx; border-top: 1rpx solid #eceef1; }
.it:first-child { border-top: none; }
.it.flag { background: #fffaf0; }
.it.off .it-main, .it.off .it-amt { opacity: 0.45; }
.ck { width: 40rpx; height: 40rpx; border-radius: 50%; border: 4rpx solid #cfd4da; box-sizing: border-box; flex: 0 0 auto; color: #fff; font-size: 22rpx; font-weight: 900; text-align: center; line-height: 32rpx; }
.ck.on { background: #12a150; border-color: #12a150; }
.it-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.it-top { display: flex; align-items: center; min-width: 0; }
.it-name { font-size: 28rpx; font-weight: 700; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.it-sub { font-size: 22rpx; color: #9aa2ad; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.it-amt { font-size: 29rpx; font-weight: 800; color: #e5563d; flex: 0 0 auto; }
.it-amt.inc { color: #12a150; }
.it-amt.tr { color: #8a94a6; }
.it-amt.warn { border-bottom: 4rpx dotted #f4a72b; }
.bar { position: fixed; left: 0; right: 0; bottom: 0; display: flex; align-items: center; gap: 24rpx; background: #fff; padding: 20rpx 28rpx calc(20rpx + env(safe-area-inset-bottom)); box-shadow: 0 -6rpx 20rpx rgba(20, 24, 28, 0.06); }
.bar-sel { display: flex; flex-direction: column; }
.bs-k { font-size: 22rpx; color: #9aa2ad; }
.bs-v { font-size: 30rpx; font-weight: 800; }
.bar .btn { flex: 1; }

/* ④ 完成 */
.fin { display: flex; flex-direction: column; align-items: center; padding: 50rpx 0 12rpx; }
.badge { width: 120rpx; height: 120rpx; border-radius: 50%; background: #e6f6ec; color: #12a150; font-size: 60rpx; text-align: center; line-height: 120rpx; }
.fin-t { font-size: 38rpx; font-weight: 800; margin-top: 24rpx; }
.fin-s { font-size: 24rpx; color: #9aa2ad; margin-top: 8rpx; }
.bars { margin-top: 28rpx; padding: 24rpx 28rpx 12rpx; }
.bh { display: block; font-size: 24rpx; color: #5b6470; font-weight: 700; margin-bottom: 10rpx; }
.brow { display: flex; align-items: center; gap: 16rpx; padding: 10rpx 0; font-size: 24rpx; }
.bn { width: 110rpx; color: #5b6470; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.bt { flex: 1; height: 16rpx; border-radius: 8rpx; background: #f1f3f5; overflow: hidden; }
.bi { height: 100%; border-radius: 8rpx; background: #12a150; }
.bv { width: 130rpx; text-align: right; font-weight: 700; }
.learn { margin-top: 24rpx; padding: 22rpx 26rpx; border-radius: 26rpx; background: #efedff; font-size: 24rpx; line-height: 1.65; color: #3e3399; }
.undo { text-align: center; font-size: 24rpx; color: #9aa2ad; margin-top: 26rpx; }
.undo-a { color: #e5484d; font-weight: 700; }
.foot { margin-top: 40rpx; }

/* 核对一笔 */
.mask { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.42); display: flex; align-items: flex-end; z-index: 50; }
.sheet { width: 100%; background: #fff; border-radius: 32rpx 32rpx 0 0; padding: 32rpx 32rpx calc(32rpx + env(safe-area-inset-bottom)); box-sizing: border-box; }
.sheet-t { display: block; text-align: center; font-size: 30rpx; font-weight: 800; }
.sh-entry { margin-top: 22rpx; background: #f6f7f9; border-radius: 20rpx; padding: 20rpx 24rpx; }
.sh-m { display: flex; flex-direction: column; gap: 6rpx; }
.sh-n { font-size: 28rpx; font-weight: 700; }
.sh-s { font-size: 22rpx; color: #9aa2ad; }
.amt-row { display: flex; align-items: center; flex-wrap: wrap; gap: 12rpx; margin-top: 20rpx; padding: 18rpx 24rpx; border-radius: 20rpx; background: #f6f7f9; }
.amt-row.warn { background: #fff5e3; }
.amt-k { font-size: 26rpx; color: #5b6470; }
.amt-in { flex: 1; font-size: 40rpx; font-weight: 800; color: #e5563d; height: 60rpx; }
.amt-w { width: 100%; font-size: 22rpx; color: #b86e00; }
.sh-k { display: flex; justify-content: space-between; align-items: center; font-size: 24rpx; color: #5b6470; margin: 24rpx 0 14rpx; }
.cats { max-height: 34vh; }
.chips { display: flex; flex-wrap: wrap; gap: 14rpx; }
.cc { padding: 12rpx 22rpx; border-radius: 999rpx; background: #f2f4f6; color: #5b6470; font-size: 25rpx; border: 2rpx solid transparent; }
.cc.on { background: #e6f6ec; color: #0e8a44; font-weight: 700; border-color: #12a150; }
.remember { display: flex; align-items: center; gap: 16rpx; margin-top: 20rpx; padding: 20rpx 24rpx; border-radius: 20rpx; background: #f6f7f9; }
.rm-x { flex: 1; display: flex; flex-direction: column; gap: 4rpx; }
.rm-t { font-size: 25rpx; font-weight: 700; }
.rm-s { font-size: 21rpx; color: #9aa2ad; }
</style>
