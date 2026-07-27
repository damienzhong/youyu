<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { listAccounts } from '../../api/account'
import { listCategories, buildCategoryLabelMap } from '../../api/category'
import { listTransactionsByMonth, deleteTransaction } from '../../api/transaction'

const month = ref(thisMonth())
const transactions = ref([])
const accountMap = ref({})
const categoryMap = ref({})
const loading = ref(false)

function thisMonth() {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
}

const TYPE_META = {
  expense: { label: '支出', sign: '-', color: '#e64340' },
  income: { label: '收入', sign: '+', color: '#07c160' },
  transfer: { label: '转账', sign: '', color: '#576b95' }
}

async function load() {
  loading.value = true
  try {
    const [accs, cats, txs] = await Promise.all([
      listAccounts(),
      listCategories(),
      listTransactionsByMonth(month.value)
    ])
    accountMap.value = Object.fromEntries(accs.map((a) => [a.id, a.name]))
    categoryMap.value = buildCategoryLabelMap(cats)
    transactions.value = txs
  } catch (e) {
    uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

onShow(load)

// 按天分组（交易已按时间倒序返回），每组含当天列表
const grouped = computed(() => {
  const groups = []
  let cur = null
  for (const t of transactions.value) {
    const day = (t.occurredAt || '').slice(0, 10)
    if (!cur || cur.day !== day) {
      cur = { day, items: [] }
      groups.push(cur)
    }
    cur.items.push(t)
  }
  return groups
})

function titleOf(t) {
  if (t.type === 'transfer') {
    return `${accountMap.value[t.sourceAccountId] || '?'} → ${accountMap.value[t.destinationAccountId] || '?'}`
  }
  return categoryMap.value[t.categoryId] || TYPE_META[t.type].label
}

function subtitleOf(t) {
  if (t.type === 'transfer') return t.note || '转账'
  const acc = accountMap.value[t.accountId] || ''
  return t.note ? `${acc} · ${t.note}` : acc
}

function timeOf(t) {
  return (t.occurredAt || '').slice(11, 16)
}

function confirmDelete(t) {
  uni.showModal({
    title: '删除记录',
    content: '删除后会同步回滚账户余额，确定删除？',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await deleteTransaction(t.id)
        await load()
      } catch (e) {
        uni.showToast({ title: e.message || '删除失败', icon: 'none' })
      }
    }
  })
}
</script>

<template>
  <view class="page">
    <view v-if="!transactions.length && !loading" class="empty">{{ month }} 暂无记录</view>

    <view v-for="g in grouped" :key="g.day" class="group">
      <text class="day">{{ g.day }}</text>
      <view
        v-for="t in g.items"
        :key="t.id"
        class="item"
        @longpress="confirmDelete(t)"
      >
        <view class="item-main">
          <text class="item-title">{{ titleOf(t) }}</text>
          <text class="item-sub">{{ timeOf(t) }} · {{ subtitleOf(t) }}</text>
        </view>
        <text class="item-amount" :style="{ color: TYPE_META[t.type].color }">
          {{ TYPE_META[t.type].sign }}{{ t.amount }}
        </text>
      </view>
    </view>

    <text v-if="transactions.length" class="hint">长按记录可删除</text>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
}
.empty {
  margin-top: 200rpx;
  text-align: center;
  color: #999;
  font-size: 28rpx;
}
.group {
  margin-bottom: 24rpx;
}
.day {
  display: block;
  font-size: 24rpx;
  color: #999;
  margin: 12rpx 8rpx;
}
.item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #fff;
  border-radius: 16rpx;
  padding: 28rpx 32rpx;
  margin-bottom: 12rpx;
}
.item-main {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.item-title {
  font-size: 30rpx;
  color: #1a1a1a;
}
.item-sub {
  font-size: 24rpx;
  color: #999;
}
.item-amount {
  font-size: 32rpx;
  font-weight: 600;
}
.hint {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: #bbb;
  margin: 24rpx 0;
}
</style>
