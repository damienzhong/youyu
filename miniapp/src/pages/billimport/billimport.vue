<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { listAccounts } from '../../api/account'
import { listCategories, flattenAll, flattenCategories } from '../../api/category'
import { importBills } from '../../api/bill'
import { detectSource, parseBillText } from '../../utils/billImport'
import { formatAmount } from '../../utils/format'

const accounts = ref([])
const flatCats = ref([])
const expenseCats = ref([])
const incomeCats = ref([])

const accIdx = ref(0)
const expIdx = ref(0)
const incIdx = ref(0)

const parsed = ref(null)
const importing = ref(false)

const SOURCE_LABEL = { alipay: '支付宝', wechat: '微信' }

async function load() {
  try {
    const [accs, cats] = await Promise.all([listAccounts(), listCategories()])
    accounts.value = accs
    flatCats.value = flattenAll(cats)
    expenseCats.value = flattenCategories(cats.expense)
    incomeCats.value = flattenCategories(cats.income)
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
    if (!parsed.value.entries.length) {
      uni.showToast({ title: '未解析到可导入的流水', icon: 'none' })
    }
  } catch (e) {
    uni.showToast({ title: e.message || '解析失败', icon: 'none' })
  }
}

function reset() {
  parsed.value = null
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
    uni.showModal({
      title: '导入完成',
      content: `成功 ${r.imported} 笔 · 重复跳过 ${r.skippedDuplicate} · 非法跳过 ${r.skippedInvalid}`,
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
          支持导入支付宝、微信导出的账单 CSV。系统会自动识别收支、匹配分类，重复流水按订单号自动去重。
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
        <text class="config-note">未自动匹配到分类的流水将归入上面的默认分类。</text>
      </view>

      <view class="actions">
        <button class="btn" @click="reset">重新选择</button>
        <button class="btn primary" :loading="importing" :disabled="!canImport" @click="doImport">
          导入 {{ parsed.entries.length }} 笔
        </button>
      </view>
    </template>
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
</style>
