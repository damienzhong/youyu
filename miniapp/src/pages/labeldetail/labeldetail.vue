<script setup>
import { ref, computed } from 'vue'
import { onLoad, onShow } from '@dcloudio/uni-app'
import { filterTransactions } from '../../api/transaction'
import { listCategories } from '../../api/category'
import { formatAmount, categoryEmoji, dayKeyOf, dayLabel } from '../../utils/format'
import { guessIcon } from '../../utils/icons'

const dim = ref('project')
const id = ref(null)
const name = ref('')

const expenseTotal = ref('0.00')
const incomeTotal = ref('0.00')
const count = ref(0)
const txs = ref([])
const catMap = ref({})
const loading = ref(false)

const DIM_LABEL = { project: '项目', merchant: '商家', tag: '标签' }
const dimLabel = computed(() => DIM_LABEL[dim.value] || '')

// 按日分组展示
const groups = computed(() => {
  const m = new Map()
  for (const t of txs.value) {
    const k = dayKeyOf(t.occurredAt)
    if (!m.has(k)) m.set(k, [])
    m.get(k).push(t)
  }
  return [...m.entries()].map(([key, list]) => ({ key, label: dayLabel(key), list }))
})

function iconKeyOf(t) {
  if (t.type === 'transfer') return 'transfer'
  return guessIcon(labelOf(t), t.type)
}
function labelOf(t) {
  if (t.type === 'transfer') return '转账'
  const nm = catMap.value[t.categoryId]
  return nm || t.note || (t.type === 'income' ? '收入' : '支出')
}
function signOf(t) {
  return t.type === 'income' ? '+' : t.type === 'expense' ? '-' : ''
}
function amtClass(t) {
  return t.type === 'income' ? 'inc' : t.type === 'expense' ? 'exp' : 'tr'
}

async function load() {
  if (id.value == null) return
  loading.value = true
  try {
    const [res, cats] = await Promise.all([
      filterTransactions(dim.value, id.value),
      listCategories()
    ])
    expenseTotal.value = res.expenseTotal
    incomeTotal.value = res.incomeTotal
    count.value = res.count
    txs.value = res.transactions
    const map = {}
    for (const kind of ['expense', 'income']) {
      for (const p of cats[kind] || []) {
        map[p.id] = p.name
        for (const c of p.children || []) map[c.id] = c.name
      }
    }
    catMap.value = map
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

onLoad((q) => {
  dim.value = q.dim || 'project'
  id.value = q.id != null ? Number(q.id) : null
  name.value = q.name ? decodeURIComponent(q.name) : ''
  uni.setNavigationBarTitle({ title: name.value || dimLabel.value })
})
onShow(load)

function openTx(t) {
  uni.navigateTo({ url: `/pages/record/record?id=${t.id}` })
}
// 跳到「明细」tab 并按本维度项筛选（tabBar 页无法带参，经本地存储投递）。
function viewInRecords() {
  try {
    uni.setStorageSync('youyu_records_filter', {
      dim: dim.value,
      id: id.value,
      name: name.value
    })
  } catch (e) {
    /* ignore */
  }
  uni.navigateTo({ url: '/pages/records/records' })
}
</script>

<template>
  <view class="page">
    <!-- 汇总卡 -->
    <view class="sum">
      <text class="s-title">{{ dimLabel }}：{{ name }}</text>
      <view class="s-row">
        <view class="s-tile">
          <text class="s-k">支出</text>
          <text class="s-v exp">¥{{ formatAmount(expenseTotal) }}</text>
        </view>
        <view class="s-tile">
          <text class="s-k">收入</text>
          <text class="s-v inc">¥{{ formatAmount(incomeTotal) }}</text>
        </view>
        <view class="s-tile">
          <text class="s-k">笔数</text>
          <text class="s-v">{{ count }}</text>
        </view>
      </view>
      <view v-if="txs.length" class="s-action" @click="viewInRecords">在明细中筛选查看 ›</view>
    </view>

    <view v-if="!txs.length && !loading" class="empty">该{{ dimLabel }}下还没有流水</view>

    <view v-for="g in groups" :key="g.key" class="group">
      <text class="g-date">{{ g.label }}</text>
      <view class="card">
        <view v-for="t in g.list" :key="t.id" class="tx" @click="openTx(t)">
          <CategoryIcon :icon="iconKeyOf(t)" :size="38" />
          <view class="tx-main">
            <text class="tx-name">{{ labelOf(t) }}</text>
            <text v-if="t.note" class="tx-note">{{ t.note }}</text>
          </view>
          <text class="tx-amt" :class="amtClass(t)">{{ signOf(t) }}{{ formatAmount(t.amount) }}</text>
        </view>
      </view>
    </view>
    <view style="height:40rpx;"></view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; padding: 24rpx; background: #eef0f2; }
.sum { background: #fff; border-radius: 24rpx; padding: 32rpx; margin-bottom: 24rpx; box-shadow: 0 8rpx 24rpx rgba(20,24,28,0.05); }
.s-title { display: block; font-size: 28rpx; font-weight: 800; color: #16181c; margin-bottom: 20rpx; }
.s-row { display: flex; }
.s-tile { flex: 1; display: flex; flex-direction: column; gap: 8rpx; align-items: center; }
.s-k { font-size: 24rpx; color: #9aa2ad; }
.s-v { font-size: 34rpx; font-weight: 800; color: #16181c; }
.s-v.exp { color: #f0553d; }
.s-v.inc { color: #12a150; }
.s-action {
  margin-top: 22rpx;
  text-align: center;
  padding: 20rpx;
  border-radius: 14rpx;
  background: #e6f6ec;
  color: #0e8a44;
  font-weight: 700;
  font-size: 26rpx;
}
.empty { margin-top: 120rpx; text-align: center; color: #9aa2ad; font-size: 28rpx; }
.group { margin-bottom: 20rpx; }
.g-date { display: block; font-size: 24rpx; color: #5b6470; padding: 8rpx 12rpx; }
.card { background: #fff; border-radius: 22rpx; overflow: hidden; box-shadow: 0 8rpx 24rpx rgba(20,24,28,0.05); }
.tx { display: flex; align-items: center; gap: 20rpx; padding: 26rpx 28rpx; border-top: 1rpx solid #f1f3f5; }
.card .tx:first-child { border-top: none; }
.tx-ic { width: 72rpx; height: 72rpx; border-radius: 20rpx; background: #f4f5f7; display: flex; align-items: center; justify-content: center; flex: 0 0 auto; }
.tx-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.tx-name { font-size: 30rpx; color: #16181c; font-weight: 600; }
.tx-note { font-size: 22rpx; color: #9aa2ad; }
.tx-amt { font-size: 32rpx; font-weight: 800; color: #16181c; }
.tx-amt.exp { color: #f0553d; }
.tx-amt.inc { color: #12a150; }
.tx-amt.tr { color: #8a94a6; }
</style>
