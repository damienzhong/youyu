<script setup>
import { ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  listAccounts,
  createAccount,
  deleteAccount,
  accountTypeLabel,
  ACCOUNT_TYPES
} from '../../api/account'

const accounts = ref([])
const loading = ref(false)

// 新建表单
const showForm = ref(false)
const form = ref({ name: '', typeIndex: 0, initialBalance: '' })
const submitting = ref(false)

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

function openForm() {
  form.value = { name: '', typeIndex: 0, initialBalance: '' }
  showForm.value = true
}

function onTypeChange(e) {
  form.value.typeIndex = Number(e.detail.value)
}

async function submit() {
  const name = form.value.name.trim()
  if (!name) {
    uni.showToast({ title: '请输入账户名称', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    await createAccount({
      name,
      type: ACCOUNT_TYPES[form.value.typeIndex].value,
      initialBalance: form.value.initialBalance === '' ? '0' : form.value.initialBalance
    })
    showForm.value = false
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '创建失败', icon: 'none' })
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
    <view v-if="!accounts.length && !loading" class="empty">还没有账户，点右下角添加</view>

    <view v-for="acc in accounts" :key="acc.id" class="acc" @longpress="confirmDelete(acc)">
      <view class="acc-main">
        <text class="acc-name">{{ acc.name }}</text>
        <text class="acc-type">{{ accountTypeLabel(acc.type) }}</text>
      </view>
      <text class="acc-balance">{{ acc.currentBalance }}</text>
    </view>

    <view class="fab" @click="openForm">＋</view>

    <!-- 新建账户弹层 -->
    <view v-if="showForm" class="mask" @click="showForm = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">新建账户</text>
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
          v-model="form.initialBalance"
          class="field"
          type="digit"
          placeholder="初始余额（默认 0）"
        />
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
.empty {
  margin-top: 200rpx;
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
.submit {
  background: #07c160;
  color: #fff;
  border-radius: 44rpx;
  font-size: 32rpx;
}
</style>
