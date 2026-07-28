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
import { formatAmount } from '../../utils/format'

const ACCOUNT_DOT = {
  CASH: '#16a34a', BANK_CARD: '#0ea5e9', ALIPAY: '#1677ff',
  WECHAT: '#07c160', CREDIT_CARD: '#f59e0b'
}
function accountDot(t) {
  return ACCOUNT_DOT[t] || '#94a3b8'
}

const accounts = ref([])
const loading = ref(false)

const netWorth = computed(() =>
  accounts.value.filter((a) => a.includeInTotal).reduce((s, a) => s + Number(a.currentBalance), 0)
)

const showForm = ref(false)
const form = ref({ id: null, name: '', typeIndex: 0, initialBalance: '', includeInTotal: true })
const submitting = ref(false)
const isEditing = computed(() => form.value.id !== null)

async function load() {
  loading.value = true
  try {
    accounts.value = await listAccounts()
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
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
    id: acc.id, name: acc.name, typeIndex: idx, initialBalance: '',
    includeInTotal: acc.includeInTotal, _hidden: acc.hidden, _note: acc.note, _creditLimit: acc.creditLimit
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
        name, type, includeInTotal: form.value.includeInTotal,
        hidden: form.value._hidden, note: form.value._note, creditLimit: form.value._creditLimit
      })
    } else {
      await createAccount({
        name, type,
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
    <!-- 净资产卡 -->
    <view class="networth">
      <text class="nw-label">净资产</text>
      <text class="nw-value" :class="{ neg: netWorth < 0 }">¥{{ formatAmount(netWorth) }}</text>
    </view>

    <view v-if="!accounts.length && !loading" class="empty">还没有账户，点右下角添加</view>

    <view class="acc-list" v-if="accounts.length">
      <view
        v-for="acc in accounts"
        :key="acc.id"
        class="acc"
        @click="openEdit(acc)"
        @longpress="confirmDelete(acc)"
      >
        <text class="acc-dot" :style="{ background: accountDot(acc.type) }"></text>
        <view class="acc-main">
          <text class="acc-name">{{ acc.name }}</text>
          <text class="acc-type">
            {{ accountTypeLabel(acc.type) }}{{ acc.includeInTotal ? '' : ' · 不计入' }}
          </text>
        </view>
        <text class="acc-balance" :class="{ neg: Number(acc.currentBalance) < 0 }">
          ¥{{ formatAmount(acc.currentBalance) }}
        </text>
      </view>
    </view>

    <text v-if="accounts.length" class="hint">点击编辑 · 长按删除</text>

    <view class="fab" @click="openCreate">＋</view>

    <!-- 新建/编辑弹层 -->
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
          <switch :checked="form.includeInTotal" color="#16a34a" @change="toggleInTotal" />
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
  border-radius: 28rpx;
  padding: 40rpx 36rpx;
  margin-bottom: 24rpx;
  color: #fff;
  background: linear-gradient(150deg, #22c55e, #16a34a 55%, #0b6b34);
  box-shadow: 0 20rpx 44rpx rgba(22, 163, 74, 0.26);
  display: flex;
  flex-direction: column;
  gap: 10rpx;
}
.nw-label {
  font-size: 26rpx;
  opacity: 0.9;
}
.nw-value {
  font-size: 64rpx;
  font-weight: 800;
}
.nw-value.neg {
  color: #fee2e2;
}
.empty {
  margin-top: 120rpx;
  text-align: center;
  color: #9ca3af;
  font-size: 28rpx;
}
.acc-list {
  background: #fff;
  border-radius: 24rpx;
  overflow: hidden;
}
.acc {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 32rpx;
  border-top: 1rpx solid #eef0f2;
}
.acc-list .acc:first-child {
  border-top: none;
}
.acc-dot {
  width: 20rpx;
  height: 20rpx;
  border-radius: 50%;
  flex: 0 0 auto;
}
.acc-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
}
.acc-name {
  font-size: 32rpx;
  color: #1f2937;
}
.acc-type {
  font-size: 24rpx;
  color: #9ca3af;
}
.acc-balance {
  font-size: 34rpx;
  font-weight: 800;
  color: #1f2937;
}
.acc-balance.neg {
  color: #dc2626;
}
.hint {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: #bbb;
  margin: 20rpx 0;
}
.fab {
  position: fixed;
  right: 48rpx;
  bottom: 80rpx;
  width: 96rpx;
  height: 96rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, #1eb257, #128a3f);
  color: #fff;
  font-size: 56rpx;
  line-height: 96rpx;
  text-align: center;
  box-shadow: 0 12rpx 30rpx rgba(22, 163, 74, 0.4);
}
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.4);
  display: flex;
  align-items: flex-end;
}
.sheet {
  width: 100%;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 40rpx;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}
.sheet-title {
  font-size: 32rpx;
  font-weight: 800;
}
.field {
  background: #f5f6f7;
  border-radius: 14rpx;
  padding: 26rpx;
  font-size: 30rpx;
}
.switch-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 30rpx;
  color: #1f2937;
  padding: 0 8rpx;
}
.submit {
  background: #16a34a;
  color: #fff;
  border-radius: 44rpx;
  font-size: 32rpx;
}
</style>
