<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { listAccounts } from '../../api/account'
import { accountDisplayName } from '../../api/account'
import { listCategories, buildCategoryLabelMap, buildCategoryIconMap } from '../../api/category'
import { resolveIcon } from '../../utils/icons'
import { listRecycle, restoreTransaction, purgeTransaction } from '../../api/transaction'
import { formatAmount, categoryEmoji, dayLabel, dayKeyOf } from '../../utils/format'

const items = ref([])
const accountMap = ref({})
const categoryMap = ref({})
const categoryIconMap = ref({})
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const [accs, cats, txs] = await Promise.all([listAccounts(), listCategories(), listRecycle()])
    accountMap.value = Object.fromEntries(accs.map((a) => [a.id, accountDisplayName(a)]))
    categoryMap.value = buildCategoryLabelMap(cats)
    categoryIconMap.value = buildCategoryIconMap(cats)
    items.value = txs
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}
onShow(load)

function titleOf(t) {
  if (t.type === 'transfer') return `${accountMap.value[t.sourceAccountId] || '?'} → ${accountMap.value[t.destinationAccountId] || '?'}`
  return categoryMap.value[t.categoryId] || (t.type === 'income' ? '收入' : '支出')
}
function iconOf(t) {
  if (t.type === 'transfer') return '🔁'
  return categoryEmoji(categoryMap.value[t.categoryId], t.type)
}
function iconKeyOf(t) {
  if (t.type === 'transfer') return 'transfer'
  return resolveIcon(categoryIconMap.value[t.categoryId], categoryMap.value[t.categoryId], t.type)
}
function subOf(t) {
  const d = dayKeyOf(t.occurredAt)
  const acc = t.type !== 'transfer' ? (accountMap.value[t.accountId] || '') : ''
  return [dayLabel(d), acc, t.note].filter(Boolean).join(' · ')
}
function signed(t) {
  if (t.type === 'expense') return `-${formatAmount(t.amount)}`
  if (t.type === 'income') return `+${formatAmount(t.amount)}`
  return formatAmount(t.amount)
}

async function restore(t) {
  try {
    await restoreTransaction(t.id)
    uni.showToast({ title: '已恢复', icon: 'success' })
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '恢复失败', icon: 'none' })
  }
}
function purge(t) {
  uni.showModal({
    title: '彻底删除',
    content: '将永久删除该记录，无法再恢复。确定？',
    confirmColor: '#e5484d',
    success: async (r) => {
      if (!r.confirm) return
      try { await purgeTransaction(t.id); await load() }
      catch (e) { uni.showToast({ title: e.message || '删除失败', icon: 'none' }) }
    }
  })
}
</script>

<template>
  <view class="page">
    <view class="tip">删除的流水会先进回收站，可随时恢复。恢复会重新计入账户余额。</view>
    <view v-if="!items.length && !loading" class="empty"><text class="big">🗑️</text><text>回收站是空的</text></view>
    <view v-else class="card">
      <view v-for="t in items" :key="t.id" class="row">
        <view class="ico"><AppIcon :name="iconKeyOf(t)" :size="40" /></view>
        <view class="info">
          <text class="name">{{ titleOf(t) }}</text>
          <text class="sub">{{ subOf(t) }}</text>
        </view>
        <view class="right">
          <text class="amt" :class="t.type">{{ signed(t) }}</text>
          <view class="acts">
            <text class="act restore" @click="restore(t)">恢复</text>
            <text class="act del" @click="purge(t)">彻底删除</text>
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; background: #eef0f2; padding: 24rpx; }
.tip { font-size: 22rpx; color: #9aa2ad; line-height: 1.6; padding: 8rpx 12rpx 20rpx; }
.empty { display: flex; flex-direction: column; align-items: center; color: #9aa2ad; font-size: 28rpx; padding: 140rpx 0; }
.empty .big { font-size: 90rpx; opacity: .5; margin-bottom: 20rpx; }
.card { background: #fff; border-radius: 20rpx; overflow: hidden; box-shadow: 0 6rpx 18rpx rgba(20,24,28,0.05); }
.row { display: flex; align-items: center; gap: 18rpx; padding: 24rpx 26rpx; border-top: 1rpx solid #f1f3f5; }
.card .row:first-child { border-top: none; }
.ico { width: 68rpx; height: 68rpx; border-radius: 20rpx; background: #f4f5f7; display: flex; align-items: center; justify-content: center; flex: 0 0 auto; }
.info { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 6rpx; }
.name { font-size: 30rpx; font-weight: 600; color: #16181c; }
.sub { font-size: 22rpx; color: #9aa2ad; }
.right { text-align: right; }
.amt { font-size: 30rpx; font-weight: 800; }
.amt.expense { color: #f0553d; }
.amt.income { color: #12a150; }
.amt.transfer { color: #8a94a6; }
.acts { display: flex; gap: 14rpx; margin-top: 10rpx; justify-content: flex-end; }
.act { font-size: 24rpx; padding: 6rpx 16rpx; border-radius: 999rpx; }
.act.restore { color: #0e8a44; background: #e6f6ec; font-weight: 700; }
.act.del { color: #e5484d; background: #fdecea; }
</style>
