<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { listAccounts } from '../../api/account'
import { listCategories, flattenCategories } from '../../api/category'
import { createTransaction } from '../../api/transaction'

const TYPES = [
  { value: 'expense', label: '支出' },
  { value: 'income', label: '收入' },
  { value: 'transfer', label: '转账' }
]

const type = ref('expense')
const amount = ref('')
const note = ref('')
const submitting = ref(false)

const accounts = ref([])
const accountIndex = ref(0)
const destIndex = ref(0)

const categoryTree = ref({ expense: [], income: [] })
const categoryIndex = ref(0)

// 当前类型对应的可选分类（支出/收入各自独立；转账无分类）
const categoryOptions = computed(() => {
  if (type.value === 'transfer') return []
  return flattenCategories(categoryTree.value[type.value])
})

async function load() {
  try {
    const [accs, cats] = await Promise.all([listAccounts(), listCategories()])
    accounts.value = accs
    categoryTree.value = cats
    accountIndex.value = 0
    destIndex.value = accs.length > 1 ? 1 : 0
    categoryIndex.value = 0
  } catch (e) {
    uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  }
}

onShow(load)

function selectType(t) {
  type.value = t
  categoryIndex.value = 0
}

function onAccountChange(e) {
  accountIndex.value = Number(e.detail.value)
}
function onDestChange(e) {
  destIndex.value = Number(e.detail.value)
}
function onCategoryChange(e) {
  categoryIndex.value = Number(e.detail.value)
}

async function submit() {
  if (!amount.value || Number(amount.value) <= 0) {
    uni.showToast({ title: '请输入正确金额', icon: 'none' })
    return
  }
  if (!accounts.value.length) {
    uni.showToast({ title: '请先创建账户', icon: 'none' })
    return
  }

  const payload = { type: type.value, amount: amount.value, note: note.value.trim() || undefined }

  if (type.value === 'transfer') {
    const src = accounts.value[accountIndex.value]
    const dst = accounts.value[destIndex.value]
    if (src.id === dst.id) {
      uni.showToast({ title: '转账账户不能相同', icon: 'none' })
      return
    }
    payload.sourceAccountId = src.id
    payload.destinationAccountId = dst.id
  } else {
    payload.accountId = accounts.value[accountIndex.value].id
    const opt = categoryOptions.value[categoryIndex.value]
    if (opt) payload.categoryId = opt.id
  }

  submitting.value = true
  try {
    await createTransaction(payload)
    uni.showToast({ title: '已记录', icon: 'success' })
    amount.value = ''
    note.value = ''
  } catch (e) {
    uni.showToast({ title: e.message || '记录失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <view class="page">
    <view class="tabs">
      <view
        v-for="t in TYPES"
        :key="t.value"
        class="tab"
        :class="{ active: type === t.value }"
        @click="selectType(t.value)"
      >
        {{ t.label }}
      </view>
    </view>

    <view class="amount-row">
      <text class="cny">¥</text>
      <input v-model="amount" class="amount" type="digit" placeholder="0.00" />
    </view>

    <!-- 支出/收入：账户 + 分类 -->
    <template v-if="type !== 'transfer'">
      <picker class="row" :range="accounts" range-key="name" @change="onAccountChange">
        <text class="row-label">账户</text>
        <text class="row-value">{{ accounts[accountIndex]?.name || '请先创建账户' }}</text>
      </picker>
      <picker
        class="row"
        :range="categoryOptions"
        range-key="label"
        @change="onCategoryChange"
      >
        <text class="row-label">分类</text>
        <text class="row-value">{{ categoryOptions[categoryIndex]?.label || '无（可选）' }}</text>
      </picker>
    </template>

    <!-- 转账：源 + 目标 -->
    <template v-else>
      <picker class="row" :range="accounts" range-key="name" @change="onAccountChange">
        <text class="row-label">转出</text>
        <text class="row-value">{{ accounts[accountIndex]?.name || '-' }}</text>
      </picker>
      <picker class="row" :range="accounts" range-key="name" @change="onDestChange">
        <text class="row-label">转入</text>
        <text class="row-value">{{ accounts[destIndex]?.name || '-' }}</text>
      </picker>
    </template>

    <input v-model="note" class="note" placeholder="备注（可选）" maxlength="200" />

    <button class="submit" :loading="submitting" @click="submit">保存</button>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
}
.tabs {
  display: flex;
  background: #fff;
  border-radius: 16rpx;
  overflow: hidden;
  margin-bottom: 32rpx;
}
.tab {
  flex: 1;
  text-align: center;
  padding: 28rpx 0;
  font-size: 30rpx;
  color: #666;
}
.tab.active {
  background: #07c160;
  color: #fff;
}
.amount-row {
  display: flex;
  align-items: center;
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx 32rpx;
  margin-bottom: 24rpx;
}
.cny {
  font-size: 48rpx;
  color: #1a1a1a;
  margin-right: 16rpx;
}
.amount {
  flex: 1;
  font-size: 56rpx;
  color: #1a1a1a;
}
.row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #fff;
  border-radius: 16rpx;
  padding: 32rpx;
  margin-bottom: 20rpx;
}
.row-label {
  font-size: 30rpx;
  color: #666;
}
.row-value {
  font-size: 30rpx;
  color: #1a1a1a;
}
.note {
  background: #fff;
  border-radius: 16rpx;
  padding: 32rpx;
  font-size: 30rpx;
  margin-bottom: 40rpx;
}
.submit {
  background: #07c160;
  color: #fff;
  border-radius: 44rpx;
  font-size: 32rpx;
}
</style>
