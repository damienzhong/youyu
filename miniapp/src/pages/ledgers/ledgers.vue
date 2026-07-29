<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { useLedgerStore } from '../../stores/ledger'
import {
  createLedger,
  renameLedger,
  deleteLedger,
  createInvite,
  joinLedger,
  listMembers,
  removeMember
} from '../../api/ledger'
import { useAuthStore } from '../../stores/auth'

const ledgerStore = useLedgerStore()
const authStore = useAuthStore()
const loading = ref(false)

const ledgers = computed(() => ledgerStore.ledgers)
const currentId = computed(() => ledgerStore.currentLedgerId)
const myUserId = computed(() => authStore.user?.id ?? authStore.user?.userId ?? null)

// 自绘输入底部卡片
const sheet = ref({ visible: false, title: '', placeholder: '', value: '', confirmText: '保存', tip: '', onConfirm: null })
function openSheet(opts) {
  sheet.value = { visible: true, placeholder: '', value: '', confirmText: '保存', tip: '', onConfirm: null, ...opts }
}
async function onSheetConfirm(text) {
  if (!text) return
  const cb = sheet.value.onConfirm
  sheet.value.visible = false
  if (cb) await cb(text)
}

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

function switchTo(l) {
  if (l.id === currentId.value) return
  ledgerStore.setCurrent(l.id)
  uni.reLaunch({ url: '/pages/index/index' })
}

// 新建账本：先选类型，再输入名称。
function addLedger() {
  uni.showActionSheet({
    itemList: ['独立账本（自己记账）', '协作账本（可邀请他人共同记账）'],
    success: ({ tapIndex }) => {
      const type = tapIndex === 1 ? 'COLLABORATIVE' : 'INDEPENDENT'
      openSheet({
        title: type === 'COLLABORATIVE' ? '新建协作账本' : '新建账本',
        placeholder: '账本名称',
        onConfirm: (name) => mutate(() => createLedger(name, type))
      })
    }
  })
}

// 加入协作账本：输入邀请码。
function joinByCode() {
  openSheet({
    title: '加入协作账本',
    placeholder: '输入邀请码',
    confirmText: '加入',
    tip: '向账本创建者获取邀请码',
    onConfirm: async (code) => {
      try {
        const l = await joinLedger(code)
        await ledgerStore.load()
        uni.showToast({ title: `已加入「${l.name}」`, icon: 'success' })
      } catch (e) {
        uni.showToast({ title: e.message || '加入失败', icon: 'none' })
      }
    }
  })
}

function rename(l) {
  openSheet({
    title: '重命名账本',
    placeholder: '账本名称',
    value: l.name,
    onConfirm: (name) => {
      if (name === l.name) return
      return mutate(() => renameLedger(l.id, name))
    }
  })
}

function remove(l) {
  const owned = ledgers.value.filter((x) => x.role === 'OWNER')
  if (owned.length <= 1 && l.role === 'OWNER') {
    uni.showToast({ title: '至少保留一个自己的账本', icon: 'none' })
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

// 邀请：生成邀请码并复制。
async function invite(l) {
  try {
    const { code } = await createInvite(l.id)
    uni.showModal({
      title: '邀请码',
      content: `${code}\n\n有效期 7 天。分享给对方，在「账本 - 加入协作账本」输入即可加入。`,
      confirmText: '复制',
      success: (r) => {
        if (r.confirm) uni.setClipboardData({ data: code })
      }
    })
  } catch (e) {
    uni.showToast({ title: e.message || '生成邀请码失败', icon: 'none' })
  }
}

// 成员管理弹层
const showMembers = ref(false)
const membersOf = ref(null)
const members = ref([])
async function openMembers(l) {
  membersOf.value = l
  members.value = []
  showMembers.value = true
  try {
    members.value = await listMembers(l.id)
  } catch (e) {
    uni.showToast({ title: e.message || '加载成员失败', icon: 'none' })
  }
}
function kick(m) {
  const isSelf = m.userId === myUserId.value
  uni.showModal({
    title: isSelf ? '退出账本' : '移除成员',
    content: isSelf ? '退出后将不再能访问该账本。' : `确定移除成员「${m.displayName || m.userId}」？`,
    confirmColor: '#dc2626',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await removeMember(membersOf.value.id, m.userId)
        if (isSelf) {
          showMembers.value = false
          if (membersOf.value.id === currentId.value) ledgerStore.setCurrent(null)
          await ledgerStore.load()
        } else {
          members.value = await listMembers(membersOf.value.id)
        }
      } catch (e) {
        uni.showToast({ title: e.message || '操作失败', icon: 'none' })
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

function roleLabel(l) {
  if (l.type !== 'COLLABORATIVE') return ''
  return l.role === 'OWNER' ? '创建者' : '协作成员'
}
</script>

<template>
  <view class="page">
    <text class="hint">点击切换当前账本。独立账本自己记账；协作账本可邀请他人共同记账。</text>

    <view class="join-row" @click="joinByCode">
      <text class="join-icon">🔗</text>
      <text class="join-text">输入邀请码加入协作账本</text>
    </view>

    <view class="list">
      <view
        v-for="l in ledgers"
        :key="l.id"
        class="item"
        :class="{ active: l.id === currentId }"
        @click="switchTo(l)"
      >
        <view class="item-top">
          <view class="item-main">
            <text class="name">{{ l.name }}</text>
            <text v-if="l.type === 'COLLABORATIVE'" class="tag collab">协作</text>
            <text v-if="l.isDefault" class="badge">默认</text>
            <text v-if="l.id === currentId" class="cur">当前</text>
          </view>
          <text v-if="roleLabel(l)" class="role">{{ roleLabel(l) }}</text>
        </view>
        <view class="ops">
          <template v-if="l.type === 'COLLABORATIVE' && l.role === 'OWNER'">
            <text class="op" @click.stop="invite(l)">邀请</text>
            <text class="op" @click.stop="openMembers(l)">成员</text>
          </template>
          <template v-else-if="l.type === 'COLLABORATIVE'">
            <text class="op" @click.stop="openMembers(l)">成员</text>
          </template>
          <text v-if="l.role === 'OWNER'" class="op" @click.stop="rename(l)">改名</text>
          <text v-if="l.role === 'OWNER'" class="op danger" @click.stop="remove(l)">删除</text>
          <text v-else class="op danger" @click.stop="kick({ userId: myUserId })">退出</text>
        </view>
      </view>
    </view>

    <view class="fab" @click="addLedger">＋</view>

    <InputSheet
      :visible="sheet.visible"
      :title="sheet.title"
      :placeholder="sheet.placeholder"
      :value="sheet.value"
      :confirm-text="sheet.confirmText"
      :tip="sheet.tip"
      @update:visible="sheet.visible = $event"
      @confirm="onSheetConfirm"
    />

    <!-- 成员弹层 -->
    <view v-if="showMembers" class="mask" @click="showMembers = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">{{ membersOf?.name }} · 成员</text>
        <view v-if="!members.length" class="m-empty">加载中…</view>
        <view v-for="m in members" :key="m.userId" class="m-item">
          <view class="m-main">
            <text class="m-name">{{ m.displayName || ('用户 ' + m.userId) }}</text>
            <text class="m-role">{{ m.role === 'OWNER' ? '创建者' : '协作成员' }}</text>
          </view>
          <text
            v-if="m.role !== 'OWNER' && (membersOf?.role === 'OWNER' || m.userId === myUserId)"
            class="m-kick"
            @click="kick(m)"
          >{{ m.userId === myUserId ? '退出' : '移除' }}</text>
        </view>
      </view>
    </view>
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
  padding: 8rpx 8rpx 16rpx;
}
.join-row {
  display: flex;
  align-items: center;
  gap: 14rpx;
  background: #fff;
  border-radius: 20rpx;
  padding: 26rpx 32rpx;
  margin-bottom: 20rpx;
}
.join-icon {
  font-size: 32rpx;
}
.join-text {
  font-size: 28rpx;
  color: #16a34a;
  font-weight: 600;
}
.list {
  background: #fff;
  border-radius: 24rpx;
  padding: 0 32rpx;
}
.item {
  padding: 28rpx 0;
  border-top: 1rpx solid #eef0f2;
}
.list .item:first-child {
  border-top: none;
}
.item.active .name {
  color: #16a34a;
}
.item-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
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
.tag {
  font-size: 20rpx;
  border-radius: 999rpx;
  padding: 2rpx 12rpx;
}
.tag.collab {
  color: #b45309;
  background: #fef3c7;
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
.role {
  font-size: 22rpx;
  color: #9ca3af;
}
.ops {
  display: flex;
  gap: 24rpx;
  margin-top: 16rpx;
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
  gap: 8rpx;
}
.sheet-title {
  font-size: 32rpx;
  font-weight: 800;
  margin-bottom: 12rpx;
}
.m-empty {
  color: #9ca3af;
  font-size: 26rpx;
  padding: 20rpx 0;
}
.m-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx 0;
  border-top: 1rpx solid #eef0f2;
}
.m-item:first-of-type {
  border-top: none;
}
.m-main {
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.m-name {
  font-size: 30rpx;
  color: #1f2937;
}
.m-role {
  font-size: 22rpx;
  color: #9ca3af;
}
.m-kick {
  font-size: 26rpx;
  color: #dc2626;
}
</style>
