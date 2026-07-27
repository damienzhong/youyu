<script setup>
import { ref, computed } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { listAccounts } from '../../api/account'
import { listCategories, flattenCategories } from '../../api/category'
import {
  createTransaction,
  getTransaction,
  updateTransaction
} from '../../api/transaction'

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

// 编辑模式：存在 editingId 时为改单，需保留原始 occurredAt
const editingId = ref(null)
const editingOccurredAt = ref(null)
const isEditing = computed(() => editingId.value !== null)

const categoryOptions = computed(() => {
  if (type.value === 'transfer') return []
  return flattenCategories(categoryTree.value[type.value])
})

onLoad((query) => {
  editingId.value = query && query.id ? Number(query.id) : null
  load()
})

async function load() {
  try {
    const [accs, cats] = await Promise.all([listAccounts(), listCategories()])
    accounts.value = accs
    categoryTree.value = cats
    accountIndex.value = 0
    destIndex.value = accs.length > 1 ? 1 : 0
    categoryIndex.value = 0

    if (isEditing.value) {
      await prefill()
      uni.setNavigationBarTitle({ title: '编辑记录' })
    }
  } catch (e) {
    uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  }
}

// 编辑：拉取原交易并回填表单与各选择器索引
async function prefill() {
  const tx = await getTransaction(editingId.value)
  type.value = tx.type
  amount.value = String(tx.amount)
  note.value = tx.note || ''
  editingOccurredAt.value = tx.occurredAt

  if (tx.type === 'transfer') {
    accountIndex.value = idxById(accounts.value, tx.sourceAccountId)
    destIndex.value = idxById(accounts.value, tx.destinationAccountId)
  } else {
    accountIndex.value = idxById(accounts.value, tx.accountId)
    categoryIndex.value = Math.max(
      categoryOptions.value.findIndex((o) => o.id === tx.categoryId),
      0
    )
  }
}

function idxById(list, id) {
  const i = list.findIndex((x) => x.id === id)
  return i >= 0 ? i : 0
}

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
  // 编辑时保留原始发生时间，避免被后端重置为当前时间
  if (isEditing.value && editingOccurredAt.value) {
    payload.occurredAt = editingOccurredAt.value
  }

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
    // 后端要求支出/收入必须有分类（需求 4.8），此处强制校验
    if (!categoryOptions.value.length) {
      uni.showModal({
        title: '还没有分类',
        content: '支出和收入需要选择分类，先去创建一个分类吧。',
        confirmText: '去创建',
        success: (r) => {
          if (r.confirm) uni.navigateTo({ url: '/pages/categories/categories' })
        }
      })
      return
    }
    const opt = categoryOptions.value[categoryIndex.value]
    if (!opt) {
      uni.showToast({ title: '请选择分类', icon: 'none' })
      return
    }
    payload.accountId = accounts.value[accountIndex.value].id
    payload.categoryId = opt.id
  }

  submitting.value = true
  try {
    if (isEditing.value) {
      await updateTransaction(editingId.value, payload)
      uni.showToast({ title: '已保存', icon: 'success' })
      setTimeout(() => uni.navigateBack(), 600)
    } else {
      await createTransaction(payload)
      uni.showToast({ title: '已记录', icon: 'success' })
      amount.value = ''
      note.value = ''
    }
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
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
        <text class="row-value">{{ categoryOptions[categoryIndex]?.label || '请先创建分类' }}</text>
      </picker>
    </template>

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

    <button class="submit" :loading="submitting" @click="submit">
      {{ isEditing ? '保存修改' : '保存' }}
    </button>
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
