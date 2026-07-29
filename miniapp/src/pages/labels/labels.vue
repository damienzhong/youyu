<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import { listProjects, createProject, updateProject, deleteProject } from '../../api/project'
import { listMerchants, createMerchant, renameMerchant, deleteMerchant } from '../../api/merchant'
import { listTags, createTag, renameTag, deleteTag } from '../../api/tag'

const TABS = [
  { key: 'project', label: '项目', icon: '📁' },
  { key: 'merchant', label: '商家', icon: '🏪' },
  { key: 'tag', label: '标签', icon: '🏷️' }
]
const tab = ref('project')
const items = ref([])
const loading = ref(false)

const cfg = {
  project: { list: listProjects, create: createProject, rename: (id, name) => updateProject(id, { name }), remove: deleteProject, ph: '如：装修、三亚旅行', max: 50 },
  merchant: { list: listMerchants, create: createMerchant, rename: renameMerchant, remove: deleteMerchant, ph: '如：星巴克、盒马', max: 50 },
  tag: { list: listTags, create: createTag, rename: renameTag, remove: deleteTag, ph: '如：报销、出差、必要', max: 30 }
}
const current = computed(() => cfg[tab.value])

async function load() {
  loading.value = true
  try {
    items.value = await current.value.list()
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}
onShow(load)
function switchTab(k) {
  if (tab.value === k) return
  tab.value = k
  items.value = []
  load()
}

// 新建 / 重命名
const sheet = ref({ visible: false, id: null, value: '' })
function openCreate() {
  sheet.value = { visible: true, id: null, value: '' }
}
function openRename(it) {
  sheet.value = { visible: true, id: it.id, value: it.name }
}
async function onConfirm(name) {
  const trimmed = (name || '').trim()
  sheet.value.visible = false
  if (!trimmed) return
  try {
    if (sheet.value.id == null) await current.value.create(trimmed)
    else await current.value.rename(sheet.value.id, trimmed)
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  }
}
// 项目归档 / 取消归档（仅项目维度）。
async function toggleArchive(it) {
  try {
    await updateProject(it.id, { archived: !it.archived })
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '操作失败', icon: 'none' })
  }
}
function confirmDelete(it) {
  uni.showModal({
    title: `删除${TABS.find((t) => t.key === tab.value).label}`,
    content: `确定删除「${it.name}」？${tab.value === 'tag' ? '会同时从相关流水上移除该标签。' : '相关流水的关联会清空。'}`,
    confirmColor: '#e5484d',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await current.value.remove(it.id)
        await load()
      } catch (e) {
        uni.showToast({ title: e.message || '删除失败', icon: 'none' })
      }
    }
  })
}
function openDetail(it) {
  uni.navigateTo({
    url: `/pages/labeldetail/labeldetail?dim=${tab.value}&id=${it.id}&name=${encodeURIComponent(it.name)}`
  })
}
</script>

<template>
  <view class="page">
    <view class="tabs">
      <view v-for="t in TABS" :key="t.key" class="tab" :class="{ on: tab === t.key }" @click="switchTab(t.key)">
        <text class="t-ic">{{ t.icon }}</text><text>{{ t.label }}</text>
      </view>
    </view>

    <view v-if="!items.length && !loading" class="empty">还没有{{ TABS.find((t) => t.key === tab).label }}，点右下角添加</view>

    <view v-else class="list">
      <view v-for="it in items" :key="it.id" class="row">
        <view class="r-main" @click="openDetail(it)">
          <text class="r-name">{{ it.name }}</text>
          <text v-if="it.archived" class="r-flag">已归档</text>
        </view>
        <text v-if="tab === 'project'" class="r-act" @click.stop="toggleArchive(it)">{{ it.archived ? '取消归档' : '归档' }}</text>
        <text class="r-act" @click.stop="openRename(it)">改名</text>
        <text class="r-act del" @click.stop="confirmDelete(it)">删除</text>
      </view>
    </view>

    <view style="height:140rpx;"></view>
    <view class="fab" @click="openCreate">＋</view>

    <InputSheet
      :visible="sheet.visible"
      :title="sheet.id == null ? '新建' + TABS.find((t) => t.key === tab).label : '重命名'"
      :placeholder="current.ph"
      :value="sheet.value"
      :maxlength="current.max"
      @update:visible="sheet.visible = $event"
      @confirm="onConfirm"
    />
  </view>
</template>

<style scoped>
.page { min-height: 100vh; padding: 24rpx; background: #eef0f2; }
.tabs { display: flex; background: #fff; border-radius: 18rpx; padding: 8rpx; margin-bottom: 24rpx; }
.tab { flex: 1; display: flex; align-items: center; justify-content: center; gap: 8rpx; padding: 20rpx 0; font-size: 28rpx; font-weight: 700; color: #5b6470; border-radius: 12rpx; }
.tab.on { background: #e6f6ec; color: #0e8a44; }
.t-ic { font-size: 30rpx; }
.empty { margin-top: 120rpx; text-align: center; color: #9aa2ad; font-size: 28rpx; }
.list { background: #fff; border-radius: 22rpx; overflow: hidden; box-shadow: 0 8rpx 24rpx rgba(20,24,28,0.05); }
.row { display: flex; align-items: center; padding: 30rpx 28rpx; border-top: 1rpx solid #f1f3f5; }
.list .row:first-child { border-top: none; }
.r-main { flex: 1; display: flex; align-items: center; gap: 12rpx; }
.r-name { font-size: 30rpx; color: #16181c; font-weight: 600; }
.r-flag { font-size: 20rpx; color: #9aa2ad; background: #f2f4f6; border-radius: 8rpx; padding: 2rpx 10rpx; }
.r-act { font-size: 26rpx; color: #5b6470; padding: 8rpx 14rpx; }
.r-act.del { color: #e5484d; }
.fab {
  position: fixed; right: 40rpx; bottom: calc(60rpx + env(safe-area-inset-bottom));
  width: 104rpx; height: 104rpx; border-radius: 50%;
  background: linear-gradient(135deg, #18b85a, #0e8a44); color: #fff;
  font-size: 62rpx; line-height: 104rpx; text-align: center;
  box-shadow: 0 14rpx 34rpx rgba(18,161,80,0.45); z-index: 200;
}
</style>
