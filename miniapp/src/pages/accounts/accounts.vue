<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  listAccounts,
  createAccount,
  updateAccount,
  deleteAccount,
  accountTypeLabel,
  ACCOUNT_TYPES
} from '../../api/account'

const accounts = ref([])
const loading = ref(false)

// 净资产 = 计入总资产的账户当前余额之和
const netWorth = computed(() => {
  const sum = accounts.value
    .filter((a) => a.includeInTotal)
    .reduce((acc, a) => acc + Number(a.currentBalance), 0)
  return sum.toFixed(2)
})

// 表单：id 为空表示新建，非空表示编辑
const showForm = ref(false)
const form = ref({ id: null, name: '', typeIndex: 0, initialBalance: '', includeInTotal: true })
const submitting = ref(false)
const isEditing = computed(() => form.value.id !== null)

async function load() {
  loading.value = true
  try {
    accounts.value = await listAccounts()
  } catch (e) {
    uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

onShow(load)

function openCreate() {
  form.value = { id: null, name: '', typeIndex: 0, initialBalance: '', includeInTotal: true }
  showForm.value = true
}

function openEdit(acc) {
  const idx = Math.max(ACCOUNT_TYPES.findIndex((t) => t.value === acc.type), 0)
  form.value = {
    id: acc.id,
    name: acc.name,
    typeIndex: idx,
    initialBalance: '',
    includeInTotal: acc.includeInTotal,
    // 保留其余字段原值，避免更新时被重置
    _hidden: acc.hidden,
    _note: acc.note,
    _creditLimit: acc.creditLimit
  }
  showForm.value = true
}

function onTypeChange(e) {
  form.value.typeIndex = Number(e.detail.value)
}
function toggleInTotal(e) {
  form.value.includeInTotal = e.detail.value
}

async function submit() {
  const name = form.value.name.trim()
  if (!name) {
    uni.showToast({ title: '请输入账户名称', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    const type = ACCOUNT_TYPES[form.value.typeIndex].value
    if (isEditing.value) {
      await updateAccount(form.value.id, {
        name,
        type,
        includeInTotal: form.value.includeInTotal,
        hidden: form.value._hidden,
        note: form.value._note,
        creditLimit: form.value._creditLimit
      })
    } else {
      await createAccount({
        name,
        type,
        initialBalance: form.value.initialBalance === '' ? '0' : form.value.initialBalance,
        includeInTotal: form.value.includeInTotal
      })
    }
    showForm.value = false
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

function confirmDelete(acc) {
  uni.showModal({
    title: '删除账户',
    content: `确定删除「${acc.name}」？有交易记录的账户无法删除。`,
    success: async (r) => {
      if (!r.confirm) return
      try {
        await deleteAccount(acc.id)
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
    <view class="networth">
      <text class="nw-label">净资产</text>
      <text class="nw-value">{{ netWorth }}</text>
    </view>

    <view v-if="!accounts.length && !loading" class="empty">还没有账户，点右下角添加</view>

    <view
      v-for="acc in accounts"
      :key="acc.id"
      class="acc"
      @click="openEdit(acc)"
      @longpress="confirmDelete(acc)"
    >
      <view class="acc-main">
        <text class="acc-name">{{ acc.name }}</text>
        <text class="acc-type">
          {{ accountTypeLabel(acc.type) }}{{ acc.includeInTotal ? '' : ' · 不计入' }}
        </text>
      </view>
      <text class="acc-balance">{{ acc.currentBalance }}</text>
    </view>

    <text v-if="accounts.length" class="hint">点击编辑 · 长按删除</text>

    <view class="fab" @click="openCreate">＋</view>

    <!-- 新建/编辑账户弹层 -->
    <view v-if="showForm" class="mask" @click="showForm = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">{{ isEditing ? '编辑账户' : '新建账户' }}</text>
        <input v-model="form.name" class="field" placeholder="账户名称" maxlength="50" />
        <picker
          class="field picker"
          :range="ACCOUNT_TYPES"
          range-key="label"
          :value="form.typeIndex"
          @change="onTypeChange"
        >
          <text>类型：{{ ACCOUNT_TYPES[form.typeIndex].label }}</text>
        </picker>
        <input
          v-if="!isEditing"
          v-model="form.initialBalance"
          class="field"
          type="digit"
          placeholder="初始余额（默认 0）"
        />
        <view class="switch-row">
          <text>计入净资产</text>
          <switch :checked="form.includeInTotal" color="#07c160" @change="toggleInTotal" />
        </view>
        <button class="submit" :loading="submitting" @click="submit">保存</button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
}
.networth {
  background: #fff;
  border-radius: 20rpx;
  padding: 40rpx;
  margin-bottom: 24rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.nw-label {
  font-size: 24rpx;
  color: #999;
}
.nw-value {
  font-size: 52rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.empty {
  margin-top: 120rpx;
  text-align: center;
  color: #999;
  font-size: 28rpx;
}
.acc {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: #fff;
  border-radius: 16rpx;
  padding: 32rpx;
  margin-bottom: 20rpx;
}
.acc-main {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.acc-name {
  font-size: 32rpx;
  color: #1a1a1a;
}
.acc-type {
  font-size: 24rpx;
  color: #999;
}
.acc-balance {
  font-size: 34rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.hint {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: #bbb;
  margin: 8rpx 0 24rpx;
}
.fab {
  position: fixed;
  right: 48rpx;
  bottom: 80rpx;
  width: 96rpx;
  height: 96rpx;
  border-radius: 50%;
  background: #07c160;
  color: #fff;
  font-size: 56rpx;
  line-height: 96rpx;
  text-align: center;
  box-shadow: 0 6rpx 20rpx rgba(7, 193, 96, 0.4);
}
.mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.4);
  display: flex;
  align-items: flex-end;
}
.sheet {
  width: 100%;
  background: #fff;
  border-radius: 24rpx 24rpx 0 0;
  padding: 40rpx;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}
.sheet-title {
  font-size: 32rpx;
  font-weight: 600;
}
.field {
  background: #f5f5f5;
  border-radius: 12rpx;
  padding: 24rpx;
  font-size: 30rpx;
}
.picker {
  color: #333;
}
.switch-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 30rpx;
  color: #333;
  padding: 0 8rpx;
}
.submit {
  background: #07c160;
  color: #fff;
  border-radius: 44rpx;
  font-size: 32rpx;
}
</style>
