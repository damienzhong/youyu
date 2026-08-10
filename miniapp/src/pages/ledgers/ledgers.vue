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
  removeMember,
  archiveLedger,
  unarchiveLedger
} from '../../api/ledger'
import { isUnsettledArchiveError } from '../../utils/aa'
import { listAccounts, accountTypeIcon, accountDisplayName } from '../../api/account'
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

// 账本类型（新建时选择）：个人 / 家庭（协作）/ AA。
// AA 账本用于多人分摊，自动算「谁欠谁」并给出结算方案；不设月预算。
const LEDGER_TYPES = [
  { type: 'PERSONAL', icon: '👤', title: '个人账本', desc: '只有自己记账' },
  { type: 'COLLABORATIVE', icon: '🏠', title: '家庭账本', desc: '共记一本账，看家庭合计与预算' },
  { type: 'AA', icon: '🤝', title: 'AA 账本', desc: '多人分摊，自动算「谁欠谁」并给出结算方案' }
]

function typeTitle(type) {
  return LEDGER_TYPES.find((t) => t.type === type)?.title || '账本'
}

// 新建账本：先选类型（卡片），再输入名称，最后选择纳入的账户。
const typeSel = ref({ visible: false })
function addLedger() {
  typeSel.value = { visible: true }
}

// 选择类型 → 关闭类型卡片 → 输入名称。AA 账本不涉及月预算入口。
function pickType(type) {
  typeSel.value.visible = false
  openSheet({
    title: `新建${typeTitle(type)}`,
    placeholder: '账本名称',
    onConfirm: (name) => openAccountSelect(name, type)
  })
}

// 账户多选（新建账本时选择纳入的账户，默认全选）。
const acctSel = ref({ visible: false, name: '', type: '', accounts: [], selected: {} })
async function openAccountSelect(name, type) {
  let accs = []
  try {
    accs = await listAccounts()
  } catch (e) {
    accs = []
  }
  // 无账户则直接创建（后端默认全选，等价空集）。
  if (!accs.length) {
    return mutate(() => createLedger(name, type, []))
  }
  const selected = {}
  accs.forEach((a) => {
    selected[a.id] = true
  })
  acctSel.value = { visible: true, name, type, accounts: accs, selected }
}
function toggleAcct(id) {
  acctSel.value.selected[id] = !acctSel.value.selected[id]
}
const acctSelCount = computed(
  () => acctSel.value.accounts.filter((a) => acctSel.value.selected[a.id]).length
)
async function confirmAccountSelect() {
  const { name, type, accounts, selected } = acctSel.value
  const ids = accounts.filter((a) => selected[a.id]).map((a) => a.id)
  acctSel.value.visible = false
  await mutate(() => createLedger(name, type, ids))
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

// AA 账本：成员管理（邀请链接 / 二维码、成员列表、退出 / 移除含未结清拦截）走独立页面。
function openAaMembers(l) {
  uni.navigateTo({ url: `/pages/aamembers/aamembers?id=${l.id}` })
}

// AA 账本归档 / 解档（仅 OWNER，需求 8.3–8.5）。归档使账本只读、移入「已归档」分组、可随时解档。
function archive(l) {
  uni.showModal({
    title: '归档账本',
    content: `归档「${l.name}」后转为只读（不可记账 / 编辑 / 结清），移入「已归档」分组，历史与导出保留，可随时解档。`,
    confirmText: '归档',
    confirmColor: '#16a34a',
    success: (r) => {
      if (r.confirm) doArchive(l, false)
    }
  })
}

// 未结清时后端返回 AA_LEDGER_UNSETTLED，二次确认后带 force=true 重试（需求 8.4）。
async function doArchive(l, force) {
  try {
    await archiveLedger(l.id, force)
    await ledgerStore.load()
    uni.showToast({ title: '已归档', icon: 'success' })
  } catch (e) {
    if (isUnsettledArchiveError(e)) {
      uni.showModal({
        title: '仍有未结清金额',
        content: '该账本仍有成员未结清（应收 / 应付非 0）。仍要归档吗？归档后只读，可随时解档后再结清。',
        confirmText: '仍要归档',
        confirmColor: '#dc2626',
        success: (r) => {
          if (r.confirm) doArchive(l, true)
        }
      })
      return
    }
    uni.showToast({ title: e.message || '归档失败', icon: 'none' })
  }
}

function unarchive(l) {
  uni.showModal({
    title: '解档账本',
    content: `解档「${l.name}」后恢复可编辑，可继续记账、编辑与结算。`,
    confirmText: '解档',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await unarchiveLedger(l.id)
        await ledgerStore.load()
        uni.showToast({ title: '已解档', icon: 'success' })
      } catch (e) {
        uni.showToast({ title: e.message || '解档失败', icon: 'none' })
      }
    }
  })
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
  if (l.type !== 'COLLABORATIVE' && l.type !== 'AA') return ''
  return l.role === 'OWNER' ? '创建者' : l.type === 'AA' ? '参与成员' : '协作成员'
}
</script>

<template>
  <view class="page">
    <text class="hint">点击切换当前账本。个人账本仅自己记账；家庭账本可邀请他人共同记账；AA 账本用于多人分摊，自动算「谁欠谁」并给出结算方案。</text>

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
            <text v-if="l.type === 'AA'" class="tag aa">AA</text>
            <text v-if="l.archived" class="tag archived">已归档</text>
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
          <template v-else-if="l.type === 'AA'">
            <text class="op" @click.stop="openAaMembers(l)">成员</text>
            <text v-if="l.role === 'OWNER' && !l.archived" class="op" @click.stop="archive(l)">归档</text>
            <text v-if="l.role === 'OWNER' && l.archived" class="op" @click.stop="unarchive(l)">解档</text>
          </template>
          <text v-if="l.role === 'OWNER'" class="op" @click.stop="rename(l)">改名</text>
          <text v-if="l.role === 'OWNER'" class="op danger" @click.stop="remove(l)">删除</text>
          <text v-else-if="l.type === 'AA'" class="op danger" @click.stop="openAaMembers(l)">退出</text>
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

    <!-- 新建账本：选择类型 -->
    <view v-if="typeSel.visible" class="mask" @click="typeSel.visible = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">选择账本类型</text>
        <view class="types">
          <view
            v-for="t in LEDGER_TYPES"
            :key="t.type"
            class="tcard"
            @click="pickType(t.type)"
          >
            <view class="tc-ic">{{ t.icon }}</view>
            <view class="tc-main">
              <text class="tc-title">{{ t.title }}</text>
              <text class="tc-desc">{{ t.desc }}</text>
            </view>
            <text class="tc-go">›</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 新建账本：账户多选 -->
    <view v-if="acctSel.visible" class="mask" @click="acctSel.visible = false">
      <view class="sheet" @click.stop>
        <text class="sheet-title">选择纳入的账户</text>
        <text class="as-tip">选中的账户将在此账本可用（默认全选，可稍后调整）</text>
        <scroll-view scroll-y class="as-list">
          <view v-for="a in acctSel.accounts" :key="a.id" class="as-item" @click="toggleAcct(a.id)">
            <view class="as-ic"><AppIcon :name="accountTypeIcon(a.type)" :size="38" /></view>
            <text class="as-name">{{ accountDisplayName(a) }}</text>
            <text class="as-check">{{ acctSel.selected[a.id] ? '✓' : '' }}</text>
          </view>
        </scroll-view>
        <button class="as-confirm" @click="confirmAccountSelect">创建账本（已选 {{ acctSelCount }}）</button>
      </view>
    </view>

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
.tag.aa {
  color: #0e8a44;
  background: #e6f6ec;
}
.tag.archived {
  color: #6b7280;
  background: #eceef1;
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
.as-tip {
  display: block;
  font-size: 24rpx;
  color: #9ca3af;
  margin-bottom: 12rpx;
}
/* 新建账本：类型选择卡片（对齐 design/aa-ledger-prototype.html） */
.types {
  display: flex;
  flex-direction: column;
  gap: 20rpx;
  margin-top: 12rpx;
}
.tcard {
  display: flex;
  align-items: center;
  gap: 22rpx;
  border: 2rpx solid #eef0f2;
  border-radius: 24rpx;
  padding: 26rpx 28rpx;
}
.tcard:active {
  border-color: #12a150;
  background: #f2fbf5;
}
.tc-ic {
  width: 76rpx;
  height: 76rpx;
  border-radius: 20rpx;
  background: #eef1f5;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 38rpx;
  flex: 0 0 auto;
}
.tc-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.tc-title {
  font-size: 30rpx;
  font-weight: 700;
  color: #16181c;
}
.tc-desc {
  font-size: 24rpx;
  color: #5b6470;
}
.tc-go {
  font-size: 40rpx;
  color: #c4c9d0;
  flex: 0 0 auto;
}
.as-list {
  max-height: 560rpx;
}
.as-item {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 22rpx 0;
  border-bottom: 1rpx solid #f0f0f0;
}
.as-ic {
  width: 56rpx;
  height: 56rpx;
  border-radius: 16rpx;
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}
.as-name {
  flex: 1;
  font-size: 30rpx;
  color: #16181c;
}
.as-check {
  width: 40rpx;
  text-align: center;
  font-size: 32rpx;
  color: #16a34a;
  font-weight: 800;
}
.as-confirm {
  margin-top: 20rpx;
  background: #16a34a;
  color: #fff;
  font-size: 30rpx;
  border-radius: 16rpx;
  padding: 20rpx 0;
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
