<script setup lang="ts">
/**
 * 分类管理页（两级分类，支出/收入各自独立）。
 *
 * 功能（需求 5.1–5.9）：
 *  - 按 kind（支出/收入）切换展示，父分类下列出子分类。
 *  - 新增父分类 / 在父分类下新增子分类（名称 1–50）。
 *  - 重命名分类（保留关联）。
 *  - 删除分类（被引用/含子分类时后端返回 CATEGORY_IN_USE，展示友好提示）。
 * 加载失败保留上次数据 + 重试（需求 11.5）。
 */
import { ref, computed, onMounted } from 'vue'
import {
  fetchCategories,
  createCategory,
  updateCategory,
  deleteCategory,
  validateCategoryName,
  toCategoryErrorMessage,
  type Category,
  type CategoryKind,
} from '@/lib/ledger'

const loading = ref(true)
const loaded = ref(false)
const loadError = ref('')
const categories = ref<Category[]>([])

const kind = ref<CategoryKind>('EXPENSE')

onMounted(load)

async function load() {
  loading.value = true
  loadError.value = ''
  try {
    categories.value = await fetchCategories()
    loaded.value = true
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败，请重试'
  } finally {
    loading.value = false
  }
}

interface CategoryTree {
  parent: Category
  children: Category[]
}

/** 当前 kind 下的父分类 + 子分类树。 */
const tree = computed<CategoryTree[]>(() => {
  const list = categories.value.filter((c) => c.kind === kind.value)
  const parents = list.filter((c) => c.parentId == null)
  const childrenByParent = new Map<number, Category[]>()
  for (const c of list) {
    if (c.parentId != null) {
      const arr = childrenByParent.get(c.parentId) ?? []
      arr.push(c)
      childrenByParent.set(c.parentId, arr)
    }
  }
  return parents.map((p) => ({ parent: p, children: childrenByParent.get(p.id) ?? [] }))
})

// === 新增（父/子）弹窗 ===
const showForm = ref(false)
const formParentId = ref<number | null>(null)
const formParentName = ref('')
const formName = ref('')
const formError = ref('')
const submitting = ref(false)

/** 打开新增父分类弹窗。 */
function openCreateParent() {
  formParentId.value = null
  formParentName.value = ''
  formName.value = ''
  formError.value = ''
  showForm.value = true
}

/** 打开在指定父分类下新增子分类弹窗。 */
function openCreateChild(parent: Category) {
  formParentId.value = parent.id
  formParentName.value = parent.name
  formName.value = ''
  formError.value = ''
  showForm.value = true
}

function closeForm() {
  showForm.value = false
}

async function onSubmit() {
  formError.value = ''
  const nameErr = validateCategoryName(formName.value)
  if (nameErr) {
    formError.value = nameErr
    return
  }
  submitting.value = true
  try {
    await createCategory({
      kind: kind.value,
      name: formName.value.trim(),
      parentId: formParentId.value,
    })
    showForm.value = false
    await load()
  } catch (e) {
    formError.value = toCategoryErrorMessage(e)
  } finally {
    submitting.value = false
  }
}

// === 重命名弹窗 ===
const showRename = ref(false)
const renameId = ref<number | null>(null)
const renameName = ref('')
const renameError = ref('')
const renaming = ref(false)

function openRename(c: Category) {
  renameId.value = c.id
  renameName.value = c.name
  renameError.value = ''
  showRename.value = true
}

function closeRename() {
  showRename.value = false
}

async function onRename() {
  renameError.value = ''
  const nameErr = validateCategoryName(renameName.value)
  if (nameErr) {
    renameError.value = nameErr
    return
  }
  if (renameId.value == null) return
  renaming.value = true
  try {
    await updateCategory(renameId.value, renameName.value.trim())
    showRename.value = false
    await load()
  } catch (e) {
    renameError.value = toCategoryErrorMessage(e)
  } finally {
    renaming.value = false
  }
}

// === 删除确认 ===
const deleteTarget = ref<Category | null>(null)
const deleteError = ref('')
const deleting = ref(false)

function askDelete(c: Category) {
  deleteTarget.value = c
  deleteError.value = ''
}

function cancelDelete() {
  deleteTarget.value = null
  deleteError.value = ''
}

async function confirmDelete() {
  if (!deleteTarget.value) return
  deleting.value = true
  deleteError.value = ''
  try {
    await deleteCategory(deleteTarget.value.id)
    deleteTarget.value = null
    await load()
  } catch (e) {
    deleteError.value = toCategoryErrorMessage(e)
  } finally {
    deleting.value = false
  }
}
</script>

<template>
  <section class="categories">
    <header class="head">
      <h1>分类</h1>
    </header>

    <!-- 支出/收入切换 -->
    <div class="tabs">
      <button
        type="button"
        class="tab"
        :class="{ active: kind === 'EXPENSE' }"
        @click="kind = 'EXPENSE'"
      >
        支出
      </button>
      <button
        type="button"
        class="tab"
        :class="{ active: kind === 'INCOME' }"
        @click="kind = 'INCOME'"
      >
        收入
      </button>
    </div>

    <div v-if="loadError" class="banner banner-err" role="alert">
      <span>{{ loadError }}</span>
      <button class="link-btn" type="button" @click="load">重试</button>
    </div>

    <p v-if="loading && !loaded" class="text-muted loading">加载中…</p>

    <template v-if="loaded">
      <p v-if="tree.length === 0" class="card text-muted empty">
        还没有{{ kind === 'EXPENSE' ? '支出' : '收入' }}分类，点下方「新增父分类」创建一个吧。
      </p>

      <div v-for="node in tree" :key="node.parent.id" class="card cat-card">
        <div class="cat-parent-row">
          <span class="cat-parent-name">{{ node.parent.name }}</span>
          <div class="row-actions">
            <button class="link-btn" type="button" @click="openCreateChild(node.parent)">+ 子分类</button>
            <button class="link-btn" type="button" @click="openRename(node.parent)">重命名</button>
            <button class="link-btn danger" type="button" @click="askDelete(node.parent)">删除</button>
          </div>
        </div>

        <ul v-if="node.children.length" class="child-list">
          <li v-for="child in node.children" :key="child.id" class="child-item">
            <span class="child-name">{{ child.name }}</span>
            <div class="row-actions">
              <button class="link-btn" type="button" @click="openRename(child)">重命名</button>
              <button class="link-btn danger" type="button" @click="askDelete(child)">删除</button>
            </div>
          </li>
        </ul>
        <p v-else class="text-muted no-child">暂无子分类</p>
      </div>

      <button class="btn btn-block add-btn" type="button" @click="openCreateParent">新增父分类</button>
    </template>

    <!-- 新增弹窗 -->
    <div v-if="showForm" class="modal-mask" @click.self="closeForm">
      <div class="modal" role="dialog" aria-modal="true" aria-label="新增分类">
        <header class="modal-head">
          <h2>{{ formParentId == null ? '新增父分类' : `在「${formParentName}」下新增子分类` }}</h2>
          <button class="icon-btn" type="button" aria-label="关闭" @click="closeForm">✕</button>
        </header>
        <div class="modal-body">
          <label class="field">
            <span>名称</span>
            <input v-model="formName" type="text" maxlength="50" placeholder="分类名称" @keyup.enter="onSubmit" />
          </label>
          <p v-if="formError" class="banner banner-err" role="alert">{{ formError }}</p>
        </div>
        <footer class="modal-foot">
          <button class="btn btn-ghost" type="button" @click="closeForm">取消</button>
          <button class="btn" type="button" :disabled="submitting" @click="onSubmit">
            {{ submitting ? '保存中…' : '保存' }}
          </button>
        </footer>
      </div>
    </div>

    <!-- 重命名弹窗 -->
    <div v-if="showRename" class="modal-mask" @click.self="closeRename">
      <div class="modal" role="dialog" aria-modal="true" aria-label="重命名分类">
        <header class="modal-head">
          <h2>重命名分类</h2>
          <button class="icon-btn" type="button" aria-label="关闭" @click="closeRename">✕</button>
        </header>
        <div class="modal-body">
          <label class="field">
            <span>名称</span>
            <input v-model="renameName" type="text" maxlength="50" placeholder="分类名称" @keyup.enter="onRename" />
          </label>
          <p v-if="renameError" class="banner banner-err" role="alert">{{ renameError }}</p>
        </div>
        <footer class="modal-foot">
          <button class="btn btn-ghost" type="button" @click="closeRename">取消</button>
          <button class="btn" type="button" :disabled="renaming" @click="onRename">
            {{ renaming ? '保存中…' : '保存' }}
          </button>
        </footer>
      </div>
    </div>

    <!-- 删除确认弹窗 -->
    <div v-if="deleteTarget" class="modal-mask" @click.self="cancelDelete">
      <div class="modal" role="dialog" aria-modal="true" aria-label="删除分类">
        <header class="modal-head">
          <h2>删除分类</h2>
          <button class="icon-btn" type="button" aria-label="关闭" @click="cancelDelete">✕</button>
        </header>
        <div class="modal-body">
          <p>确定删除「{{ deleteTarget.name }}」吗？此操作不可撤销。</p>
          <p v-if="deleteError" class="banner banner-err" role="alert">{{ deleteError }}</p>
        </div>
        <footer class="modal-foot">
          <button class="btn btn-ghost" type="button" @click="cancelDelete">取消</button>
          <button class="btn btn-danger" type="button" :disabled="deleting" @click="confirmDelete">
            {{ deleting ? '删除中…' : '删除' }}
          </button>
        </footer>
      </div>
    </div>
  </section>
</template>

<style scoped>
.categories {
  padding-bottom: 24px;
}
.head {
  margin-bottom: 16px;
}
.head h1 {
  margin: 0;
  font-size: 22px;
  color: var(--color-primary);
}
.loading {
  padding: 24px 0;
}

.tabs {
  display: flex;
  gap: 8px;
  margin-bottom: 16px;
}
.tab {
  flex: 1;
  min-height: 40px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  background: var(--color-surface);
  color: var(--color-muted);
  font-weight: 600;
}
.tab.active {
  border-color: var(--color-primary);
  background: #ecfdf5;
  color: var(--color-primary-dark);
}

.empty {
  text-align: center;
}

.cat-card {
  margin-bottom: 12px;
}
.cat-parent-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
.cat-parent-name {
  font-size: 16px;
  font-weight: 700;
  overflow-wrap: anywhere;
}
.row-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.child-list {
  list-style: none;
  margin: 12px 0 0;
  padding: 12px 0 0;
  border-top: 1px solid var(--color-border);
  display: grid;
  gap: 10px;
}
.child-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-left: 12px;
}
.child-name {
  font-size: 15px;
  overflow-wrap: anywhere;
}
.no-child {
  margin: 12px 0 0;
  font-size: 13px;
}

.add-btn {
  margin-top: 4px;
}

.link-btn {
  border: none;
  background: none;
  color: var(--color-primary);
  font-weight: 600;
  font-size: 14px;
  padding: 0;
}
.link-btn.danger {
  color: var(--color-danger);
}

/* 弹窗（与其它页一致）。 */
.modal-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: flex-end;
  justify-content: center;
  z-index: 50;
}
.modal {
  background: var(--color-surface);
  width: 100%;
  max-width: 520px;
  max-height: 90vh;
  display: flex;
  flex-direction: column;
  border-radius: 16px 16px 0 0;
  overflow: hidden;
}
@media (min-width: 640px) {
  .modal-mask {
    align-items: center;
    padding: 16px;
  }
  .modal {
    border-radius: 16px;
  }
}
.modal-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  border-bottom: 1px solid var(--color-border);
}
.modal-head h2 {
  margin: 0;
  font-size: 16px;
  overflow-wrap: anywhere;
}
.icon-btn {
  border: none;
  background: none;
  font-size: 18px;
  color: var(--color-muted);
  min-width: 32px;
  min-height: 32px;
}
.modal-body {
  padding: 16px;
  overflow-y: auto;
  display: grid;
  gap: 14px;
}
.modal-foot {
  display: flex;
  gap: 12px;
  padding: 12px 16px calc(12px + var(--safe-bottom));
  border-top: 1px solid var(--color-border);
}
.modal-foot .btn {
  flex: 1;
}
.btn-ghost {
  background: var(--color-surface);
  color: var(--color-text);
  border: 1px solid var(--color-border);
}
.btn-ghost:active {
  background: #f1f5f2;
}
.btn-danger {
  background: var(--color-danger);
}
.btn-danger:active {
  background: #b91c1c;
}
.field {
  display: block;
}
.field > span {
  display: block;
  margin-bottom: 6px;
  font-size: 14px;
  color: var(--color-muted);
}
.field input {
  width: 100%;
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius);
  font: inherit;
  background: var(--color-surface);
}
.banner {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--radius);
  font-size: 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}
.banner-err {
  background: #fef2f2;
  color: var(--color-danger);
}
</style>
