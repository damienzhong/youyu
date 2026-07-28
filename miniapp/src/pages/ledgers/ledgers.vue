<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useLedgerStore } from '../../stores/ledger'
import { createLedger, renameLedger, deleteLedger } from '../../api/ledger'

const ledgerStore = useLedgerStore()
const loading = ref(false)

const ledgers = computed(() => ledgerStore.ledgers)
const currentId = computed(() => ledgerStore.currentLedgerId)

async function load() {
  loading.value = true
  try {
    await ledgerStore.load()
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

onShow(load)

// 切换当前账本 → 重启到首页，全局数据按新账本刷新
function switchTo(l) {
  if (l.id === currentId.value) return
  ledgerStore.setCurrent(l.id)
  uni.reLaunch({ url: '/pages/index/index' })
}

function addLedger() {
  uni.showModal({
    title: '新建账本',
    editable: true,
    placeholderText: '账本名称',
    success: async (r) => {
      if (!r.confirm || !r.content?.trim()) return
      await mutate(() => createLedger(r.content.trim()))
    }
  })
}

function rename(l) {
  uni.showModal({
    title: '重命名账本',
    editable: true,
    content: l.name,
    success: async (r) => {
      if (!r.confirm || !r.content?.trim() || r.content.trim() === l.name) return
      await mutate(() => renameLedger(l.id, r.content.trim()))
    }
  })
}

function remove(l) {
  if (ledgers.value.length <= 1) {
    uni.showToast({ title: '至少保留一个账本', icon: 'none' })
    return
  }
  uni.showModal({
    title: '删除账本',
    content: `确定删除「${l.name}」？该账本下的账户、分类、交易、预算将一并清除，且不可恢复。`,
    confirmColor: '#dc2626',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await deleteLedger(l.id)
        if (l.id === currentId.value) ledgerStore.setCurrent(null)
        await ledgerStore.load()
      } catch (e) {
        uni.showToast({ title: e.message || '删除失败', icon: 'none' })
      }
    }
  })
}

async function mutate(fn) {
  try {
    await fn()
    await ledgerStore.load()
  } catch (e) {
    uni.showToast({ title: e.message || '操作失败', icon: 'none' })
  }
}
</script>

<template>
  <view class="page">
    <text class="hint">点击切换当前账本；每个账本是完全独立的一套账。</text>

    <view class="list">
      <view
        v-for="l in ledgers"
        :key="l.id"
        class="item"
        :class="{ active: l.id === currentId }"
        @click="switchTo(l)"
      >
        <view class="item-main">
          <text class="name">{{ l.name }}</text>
          <text v-if="l.isDefault" class="badge">默认</text>
          <text v-if="l.id === currentId" class="cur">当前</text>
        </view>
        <view class="ops">
          <text class="op" @click.stop="rename(l)">改名</text>
          <text class="op danger" @click.stop="remove(l)">删除</text>
        </view>
      </view>
    </view>

    <view class="fab" @click="addLedger">＋</view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
}
.hint {
  display: block;
  font-size: 24rpx;
  color: #9ca3af;
  padding: 8rpx 8rpx 20rpx;
}
.list {
  background: #fff;
  border-radius: 24rpx;
  padding: 0 32rpx;
}
.item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 32rpx 0;
  border-top: 1rpx solid #eef0f2;
}
.list .item:first-child {
  border-top: none;
}
.item.active .name {
  color: #16a34a;
}
.item-main {
  display: flex;
  align-items: center;
  gap: 14rpx;
}
.name {
  font-size: 32rpx;
  font-weight: 600;
  color: #1f2937;
}
.badge {
  font-size: 20rpx;
  color: #6b7280;
  background: #f0f2f5;
  border-radius: 999rpx;
  padding: 2rpx 12rpx;
}
.cur {
  font-size: 20rpx;
  color: #fff;
  background: #16a34a;
  border-radius: 999rpx;
  padding: 2rpx 12rpx;
}
.ops {
  display: flex;
  gap: 24rpx;
}
.op {
  font-size: 24rpx;
  color: #576b95;
}
.op.danger {
  color: #dc2626;
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
</style>
