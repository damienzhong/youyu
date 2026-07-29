<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  listCategories,
  createCategory,
  renameCategory,
  deleteCategory
} from '../../api/category'
import { categoryEmoji } from '../../utils/format'

const KINDS = [
  { value: 'EXPENSE', label: '支出', emojiKind: 'expense' },
  { value: 'INCOME', label: '收入', emojiKind: 'income' }
]

const kind = ref('EXPENSE')
const tree = ref({ expense: [], income: [] })
const loading = ref(false)

// 自绘输入底部卡片
const sheet = ref({ visible: false, title: '', placeholder: '', value: '', onConfirm: null })
function openSheet(opts) {
  sheet.value = { visible: true, placeholder: '', value: '', onConfirm: null, ...opts }
}
async function onSheetConfirm(text) {
  if (!text) return
  const cb = sheet.value.onConfirm
  sheet.value.visible = false
  if (cb) await cb(text)
}

const roots = computed(() => (kind.value === 'EXPENSE' ? tree.value.expense : tree.value.income))
const emojiKind = computed(() => (kind.value === 'EXPENSE' ? 'expense' : 'income'))

async function load() {
  loading.value = true
  try {
    tree.value = await listCategories()
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

onShow(load)

function addParent() {
  openSheet({
    title: `新建${kind.value === 'EXPENSE' ? '支出' : '收入'}分类`,
    placeholder: '分类名称',
    onConfirm: (name) => mutate(() => createCategory({ kind: kind.value, name }))
  })
}
function addChild(parent) {
  openSheet({
    title: `在「${parent.name}」下新建子分类`,
    placeholder: '子分类名称',
    onConfirm: (name) => mutate(() => createCategory({ kind: kind.value, name, parentId: parent.id }))
  })
}
function rename(node) {
  openSheet({
    title: '重命名分类',
    placeholder: '分类名称',
    value: node.name,
    onConfirm: (name) => {
      if (name === node.name) return
      return mutate(() => renameCategory(node.id, name))
    }
  })
}
function remove(node) {
  uni.showModal({
    title: '删除分类',
    content: `确定删除「${node.name}」？有交易引用或含子分类时无法删除。`,
    success: async (r) => {
      if (!r.confirm) return
      await mutate(() => deleteCategory(node.id))
    }
  })
}
async function mutate(fn) {
  try {
    await fn()
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '操作失败', icon: 'none' })
  }
}
</script>

<template>
  <view class="page">
    <view class="kinds">
      <view
        v-for="k in KINDS"
        :key="k.value"
        class="kind"
        :class="{ active: kind === k.value }"
        @click="kind = k.value"
      >
        {{ k.label }}
      </view>
    </view>

    <view v-if="!roots.length && !loading" class="empty">还没有分类，点右下角添加</view>

    <view v-for="parent in roots" :key="parent.id" class="parent">
      <view class="parent-head">
        <text class="p-ic">{{ categoryEmoji(parent.name, emojiKind) }}</text>
        <text class="parent-name">{{ parent.name }}</text>
        <view class="ops">
          <text class="op" @click="addChild(parent)">＋子</text>
          <text class="op" @click="rename(parent)">改名</text>
          <text class="op danger" @click="remove(parent)">删除</text>
        </view>
      </view>
      <view v-for="child in parent.children" :key="child.id" class="child">
        <text class="c-ic">{{ categoryEmoji(parent.name + child.name, emojiKind) }}</text>
        <text class="child-name">{{ child.name }}</text>
        <view class="ops">
          <text class="op" @click="rename(child)">改名</text>
          <text class="op danger" @click="remove(child)">删除</text>
        </view>
      </view>
    </view>

    <view class="fab" @click="addParent">＋</view>

    <InputSheet
      :visible="sheet.visible"
      :title="sheet.title"
      :placeholder="sheet.placeholder"
      :value="sheet.value"
      @update:visible="sheet.visible = $event"
      @confirm="onSheetConfirm"
    />
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
}
.kinds {
  display: flex;
  background: #fff;
  border-radius: 20rpx;
  overflow: hidden;
  margin-bottom: 24rpx;
}
.kind {
  flex: 1;
  text-align: center;
  padding: 28rpx 0;
  font-size: 30rpx;
  color: #6b7280;
}
.kind.active {
  background: #12a150;
  color: #fff;
  font-weight: 700;
}
.empty {
  margin-top: 160rpx;
  text-align: center;
  color: #9ca3af;
  font-size: 28rpx;
}
.parent {
  background: #fff;
  border-radius: 24rpx;
  padding: 8rpx 32rpx;
  margin-bottom: 20rpx;
}
.parent-head {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 26rpx 0;
  border-bottom: 1rpx solid #eef0f2;
}
.p-ic {
  width: 60rpx;
  height: 60rpx;
  border-radius: 16rpx;
  background: #eafaf0;
  text-align: center;
  line-height: 60rpx;
  font-size: 30rpx;
}
.parent-name {
  flex: 1;
  font-size: 32rpx;
  font-weight: 700;
  color: #1f2937;
}
.child {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 22rpx 0 22rpx 24rpx;
}
.c-ic {
  width: 48rpx;
  height: 48rpx;
  border-radius: 12rpx;
  background: #f5f6f7;
  text-align: center;
  line-height: 48rpx;
  font-size: 26rpx;
}
.child-name {
  flex: 1;
  font-size: 28rpx;
  color: #4b5563;
}
.ops {
  display: flex;
  gap: 24rpx;
}
.op {
  font-size: 24rpx;
  color: #12a150;
}
.op.danger {
  color: #f0553d;
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
