<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  listCategories,
  createCategory,
  renameCategory,
  deleteCategory
} from '../../api/category'

const KINDS = [
  { value: 'EXPENSE', label: '支出' },
  { value: 'INCOME', label: '收入' }
]

const kind = ref('EXPENSE')
const tree = ref({ expense: [], income: [] })
const loading = ref(false)

const roots = computed(() =>
  kind.value === 'EXPENSE' ? tree.value.expense : tree.value.income
)

async function load() {
  loading.value = true
  try {
    tree.value = await listCategories()
  } catch (e) {
    uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

onShow(load)

// 新建父分类
function addParent() {
  uni.showModal({
    title: `新建${kind.value === 'EXPENSE' ? '支出' : '收入'}分类`,
    editable: true,
    placeholderText: '分类名称',
    success: async (r) => {
      if (!r.confirm || !r.content?.trim()) return
      await mutate(() => createCategory({ kind: kind.value, name: r.content.trim() }))
    }
  })
}

// 在某父分类下新建子分类
function addChild(parent) {
  uni.showModal({
    title: `在「${parent.name}」下新建子分类`,
    editable: true,
    placeholderText: '子分类名称',
    success: async (r) => {
      if (!r.confirm || !r.content?.trim()) return
      await mutate(() =>
        createCategory({ kind: kind.value, name: r.content.trim(), parentId: parent.id })
      )
    }
  })
}

function rename(node) {
  uni.showModal({
    title: '重命名',
    editable: true,
    content: node.name,
    success: async (r) => {
      if (!r.confirm || !r.content?.trim() || r.content.trim() === node.name) return
      await mutate(() => renameCategory(node.id, r.content.trim()))
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

// 统一执行写操作并刷新，错误弹 toast
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
        <text class="parent-name">{{ parent.name }}</text>
        <view class="ops">
          <text class="op" @click="addChild(parent)">＋子</text>
          <text class="op" @click="rename(parent)">改名</text>
          <text class="op danger" @click="remove(parent)">删除</text>
        </view>
      </view>
      <view v-for="child in parent.children" :key="child.id" class="child">
        <text class="child-name">{{ child.name }}</text>
        <view class="ops">
          <text class="op" @click="rename(child)">改名</text>
          <text class="op danger" @click="remove(child)">删除</text>
        </view>
      </view>
    </view>

    <view class="fab" @click="addParent">＋</view>
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
  border-radius: 16rpx;
  overflow: hidden;
  margin-bottom: 20rpx;
}
.kind {
  flex: 1;
  text-align: center;
  padding: 26rpx 0;
  font-size: 30rpx;
  color: #666;
}
.kind.active {
  background: #07c160;
  color: #fff;
}
.empty {
  margin-top: 160rpx;
  text-align: center;
  color: #999;
  font-size: 28rpx;
}
.parent {
  background: #fff;
  border-radius: 16rpx;
  padding: 8rpx 32rpx;
  margin-bottom: 20rpx;
}
.parent-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 24rpx 0;
  border-bottom: 1rpx solid #f0f0f0;
}
.parent-name {
  font-size: 32rpx;
  font-weight: 600;
  color: #1a1a1a;
}
.child {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20rpx 0 20rpx 32rpx;
}
.child-name {
  font-size: 28rpx;
  color: #555;
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
  color: #e64340;
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
</style>
