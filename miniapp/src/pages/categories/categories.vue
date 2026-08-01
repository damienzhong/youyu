<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  listCategories,
  createCategory,
  renameCategory,
  deleteCategory
} from '../../api/category'
import { ICON_KEYS, resolveIcon, guessIcon } from '../../utils/icons'

const KINDS = [
  { value: 'EXPENSE', label: '支出' },
  { value: 'INCOME', label: '收入' }
]

const kind = ref('EXPENSE')
const tree = ref({ expense: [], income: [] })
const loading = ref(false)

const roots = computed(() => (kind.value === 'EXPENSE' ? tree.value.expense : tree.value.income))
const emojiKind = computed(() => (kind.value === 'EXPENSE' ? 'expense' : 'income'))
const iconKeys = ICON_KEYS

// 分类图标 key：优先分类自身 icon，否则按名称推断。
function catIcon(node) {
  return resolveIcon(node.icon, node.name, emojiKind.value)
}

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

// ---------- 新建 / 编辑分类（名称 + 图标）----------
const editor = ref({
  visible: false,
  mode: 'create', // create | edit
  title: '',
  name: '',
  icon: 'receipt',
  manualIcon: false, // 用户是否手动选过图标（未选则随名称自动推断）
  parentId: null,
  editId: null
})
const submitting = ref(false)

function openCreateParent() {
  editor.value = {
    visible: true, mode: 'create', title: `新建${kind.value === 'EXPENSE' ? '支出' : '收入'}分类`,
    name: '', icon: guessIcon('', emojiKind.value), manualIcon: false, parentId: null, editId: null
  }
}
function openCreateChild(parent) {
  editor.value = {
    visible: true, mode: 'create', title: `在「${parent.name}」下新建子分类`,
    name: '', icon: guessIcon('', emojiKind.value), manualIcon: false, parentId: parent.id, editId: null
  }
}
function openEdit(node) {
  editor.value = {
    visible: true, mode: 'edit', title: '编辑分类',
    name: node.name, icon: catIcon(node), manualIcon: true, parentId: node.parentId ?? null, editId: node.id
  }
}
function closeEditor() {
  editor.value.visible = false
}
// 名称输入时，若用户没手动选图标，则随名称自动推断预览。
function onNameInput(e) {
  const v = e.detail.value
  editor.value.name = v
  if (!editor.value.manualIcon) editor.value.icon = guessIcon(v, emojiKind.value)
}
function pickIcon(key) {
  editor.value.icon = key
  editor.value.manualIcon = true
}
async function submitEditor() {
  const name = (editor.value.name || '').trim()
  if (!name) {
    uni.showToast({ title: '请输入分类名称', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    if (editor.value.mode === 'create') {
      await createCategory({ kind: kind.value, name, parentId: editor.value.parentId, icon: editor.value.icon })
    } else {
      await renameCategory(editor.value.editId, name, editor.value.icon)
    }
    editor.value.visible = false
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '操作失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

function remove(node) {
  uni.showModal({
    title: '删除分类',
    content: `确定删除「${node.name}」？有交易引用或含子分类时无法删除。`,
    confirmColor: '#e5484d',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await deleteCategory(node.id)
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
        <view class="p-ic"><AppIcon :name="catIcon(parent)" :size="34" /></view>
        <text class="parent-name">{{ parent.name }}</text>
        <view class="ops">
          <text class="op" @click="openCreateChild(parent)">＋子</text>
          <text class="op" @click="openEdit(parent)">编辑</text>
          <text class="op danger" @click="remove(parent)">删除</text>
        </view>
      </view>
      <view v-for="child in parent.children" :key="child.id" class="child">
        <view class="c-ic"><AppIcon :name="catIcon(child)" :size="28" /></view>
        <text class="child-name">{{ child.name }}</text>
        <view class="ops">
          <text class="op" @click="openEdit(child)">编辑</text>
          <text class="op danger" @click="remove(child)">删除</text>
        </view>
      </view>
    </view>

    <view class="fab" @click="openCreateParent">＋</view>

    <!-- 新建 / 编辑分类：名称 + 图标选择器 -->
    <view v-if="editor.visible" class="mask" @click="closeEditor">
      <view class="sheet" @click.stop>
        <view class="sheet-head">
          <text class="sh-c" @click="closeEditor">取消</text>
          <text class="sh-t">{{ editor.title }}</text>
          <text class="sh-s" @click="submitEditor">保存</text>
        </view>

        <view class="preview">
          <view class="pv-tile"><AppIcon :name="editor.icon" :size="40" active /></view>
          <text class="pv-nm">{{ editor.name || '分类名称' }}</text>
        </view>

        <view class="field">
          <text class="fk">名称</text>
          <input class="fi" :value="editor.name" placeholder="分类名称" maxlength="50" @input="onNameInput" />
        </view>

        <text class="lib-t">选择图标</text>
        <scroll-view scroll-y class="lib">
          <view class="lib-grid">
            <view
              v-for="k in iconKeys"
              :key="k"
              class="li"
              @click="pickIcon(k)"
            >
              <view class="li-tile" :class="{ on: editor.icon === k }">
                <AppIcon :name="k" :size="40" :active="editor.icon === k" />
              </view>
            </view>
          </view>
        </scroll-view>

        <button class="save" :disabled="submitting" @click="submitEditor">保存</button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  background: #f2f4f6;
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
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
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
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
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

/* 编辑分类弹层 */
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.4);
  display: flex;
  align-items: flex-end;
  z-index: 50;
}
.sheet {
  width: 100%;
  background: #f5f6f8;
  border-radius: 28rpx 28rpx 0 0;
  padding-bottom: calc(24rpx + env(safe-area-inset-bottom));
  max-height: 84vh;
  display: flex;
  flex-direction: column;
}
.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 26rpx 32rpx 20rpx;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
}
.sh-c { font-size: 28rpx; color: #8a919b; }
.sh-t { font-size: 32rpx; font-weight: 700; }
.sh-s { font-size: 28rpx; color: #12a150; font-weight: 700; }
.preview {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14rpx;
  padding: 32rpx 0;
  background: #fff;
}
.pv-tile {
  width: 108rpx;
  height: 108rpx;
  border-radius: 32rpx;
  background: #e7f7ee;
  display: flex;
  align-items: center;
  justify-content: center;
}
.pv-nm { font-size: 30rpx; font-weight: 600; color: #1f2329; }
.field {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 26rpx 32rpx;
  background: #fff;
  margin-top: 16rpx;
}
.fk { font-size: 28rpx; color: #5b6470; width: 88rpx; }
.fi { flex: 1; font-size: 30rpx; color: #1f2329; }
.lib-t {
  display: block;
  font-size: 24rpx;
  color: #8a919b;
  padding: 24rpx 32rpx 12rpx;
}
.lib {
  background: #fff;
  max-height: 460rpx;
  padding: 8rpx 20rpx 20rpx;
}
.lib-grid {
  display: flex;
  flex-wrap: wrap;
}
.li {
  width: 16.66%;
  box-sizing: border-box;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 12rpx 0;
}
.li-tile {
  width: 76rpx;
  height: 76rpx;
  border-radius: 20rpx;
  background: #f4f5f7;
  display: flex;
  align-items: center;
  justify-content: center;
}
.li-tile.on {
  background: #e7f7ee;
  box-shadow: 0 0 0 3rpx #12a150 inset;
}
.save {
  margin: 24rpx 32rpx 0;
  background: #12a150;
  color: #fff;
  font-size: 30rpx;
  font-weight: 700;
  border-radius: 16rpx;
  padding: 22rpx 0;
}
</style>
