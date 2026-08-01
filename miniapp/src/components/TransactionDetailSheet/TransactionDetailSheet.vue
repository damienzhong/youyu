<script setup>
import { ref, computed, watch } from 'vue'
import { getTransaction, deleteTransaction } from '../../api/transaction'
import { listAccounts, accountDisplayName } from '../../api/account'
import { listCategories, buildCategoryLabelMap, buildCategoryIconMap } from '../../api/category'
import { listProjects } from '../../api/project'
import { listMerchants } from '../../api/merchant'
import { listTags } from '../../api/tag'
import { resolveIcon } from '../../utils/icons'
import { formatAmount } from '../../utils/format'

const props = defineProps({
  visible: { type: Boolean, default: false },
  id: { type: Number, default: null },
  ledgerId: { type: Number, default: null }
})
const emit = defineEmits(['update:visible', 'deleted'])

const tx = ref(null)
const accMap = ref({})
const catMap = ref({})
const catIconMap = ref({})
const projMap = ref({})
const merchMap = ref({})
const tagMap = ref({})
const loading = ref(false)
const deleting = ref(false)

// 弹窗打开且有目标 id 时拉取详情。
watch(
  () => [props.visible, props.id],
  ([v, id]) => {
    if (v && id != null) load()
  },
  { immediate: true }
)

async function load() {
  loading.value = true
  tx.value = null
  try {
    const [t, accs, cats] = await Promise.all([
      getTransaction(props.id, props.ledgerId),
      listAccounts(props.ledgerId),
      listCategories(props.ledgerId)
    ])
    tx.value = t
    accMap.value = Object.fromEntries(accs.map((a) => [a.id, accountDisplayName(a)]))
    catMap.value = buildCategoryLabelMap(cats)
    catIconMap.value = buildCategoryIconMap(cats)
    try {
      if (t.projectId != null) {
        const ps = await listProjects(props.ledgerId)
        projMap.value = Object.fromEntries(ps.map((p) => [p.id, p.name]))
      }
      if (t.merchantId != null) {
        const ms = await listMerchants(props.ledgerId)
        merchMap.value = Object.fromEntries(ms.map((m) => [m.id, m.name]))
      }
      if (Array.isArray(t.tagIds) && t.tagIds.length) {
        const ts = await listTags(props.ledgerId)
        tagMap.value = Object.fromEntries(ts.map((tg) => [tg.id, tg.name]))
      }
    } catch (e) {
      /* 附属信息缺失不阻断详情展示 */
    }
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

const isTransfer = computed(() => tx.value?.type === 'transfer')
const typeLabel = computed(() =>
  tx.value?.type === 'income' ? '收入' : tx.value?.type === 'transfer' ? '转账' : '支出'
)
const amountText = computed(() => {
  if (!tx.value) return '0.00'
  const s = tx.value.type === 'income' ? '+' : tx.value.type === 'expense' ? '-' : ''
  return `${s}${formatAmount(tx.value.amount)}`
})
const heroClass = computed(() => tx.value?.type || 'expense')
const categoryName = computed(() => (tx.value ? catMap.value[tx.value.categoryId] || '未分类' : ''))
const categoryIcon = computed(() =>
  tx.value ? resolveIcon(catIconMap.value[tx.value.categoryId], categoryName.value, tx.value.type) : 'receipt'
)
const accountName = computed(() => (tx.value ? accMap.value[tx.value.accountId] || '—' : ''))
const sourceName = computed(() => (tx.value ? accMap.value[tx.value.sourceAccountId] || '—' : ''))
const destName = computed(() => (tx.value ? accMap.value[tx.value.destinationAccountId] || '—' : ''))
const projectName = computed(() =>
  tx.value && tx.value.projectId != null ? projMap.value[tx.value.projectId] || '' : ''
)
const merchantName = computed(() =>
  tx.value && tx.value.merchantId != null ? merchMap.value[tx.value.merchantId] || '' : ''
)
const tagNames = computed(() => {
  if (!tx.value || !Array.isArray(tx.value.tagIds)) return []
  return tx.value.tagIds.map((id) => tagMap.value[id]).filter(Boolean)
})
const timeText = computed(() => {
  const s = String(tx.value?.occurredAt || '')
  if (!s) return ''
  const date = s.slice(0, 10).replace(/-/g, '/')
  const time = s.slice(11, 16)
  return time ? `${date} ${time}` : date
})

function close() {
  emit('update:visible', false)
}
function goEdit() {
  const t = tx.value
  if (!t) return
  const suffix = props.ledgerId ? `&ledgerId=${props.ledgerId}` : ''
  close()
  uni.navigateTo({ url: `/pages/record/record?id=${t.id}${suffix}` })
}
function confirmDelete() {
  if (deleting.value || !tx.value) return
  uni.showModal({
    title: '删除记录',
    content: '删除后会同步回滚账户余额，确定删除？',
    confirmText: '删除',
    confirmColor: '#e5484d',
    success: async (r) => {
      if (!r.confirm) return
      deleting.value = true
      try {
        await deleteTransaction(props.id, props.ledgerId)
        uni.showToast({ title: '已删除', icon: 'success' })
        emit('deleted', props.id)
        close()
      } catch (e) {
        uni.showToast({ title: e.message || '删除失败', icon: 'none' })
      } finally {
        deleting.value = false
      }
    }
  })
}
</script>

<template>
  <view v-if="visible" class="td-mask" @click="close">
    <view class="td-sheet" @click.stop>
      <!-- 头部 -->
      <view class="td-hero" :class="heroClass">
        <view class="td-nav">
          <text class="td-back" @click="close">‹</text>
          <text class="td-title">账单详情</text>
          <text class="td-space"></text>
        </view>
        <view class="td-herobody">
          <text class="td-type">{{ typeLabel }}</text>
          <text class="td-amt">{{ amountText }}</text>
        </view>
      </view>

      <!-- 信息卡 -->
      <view class="td-card">
        <template v-if="isTransfer">
          <view class="td-row"><text class="k">转出账户</text><text class="v">{{ sourceName }}</text></view>
          <view class="td-row"><text class="k">转入账户</text><text class="v">{{ destName }}</text></view>
        </template>
        <template v-else>
          <view class="td-row">
            <text class="k">分类</text>
            <view class="v cat"><AppIcon :name="categoryIcon" :size="34" /><text>{{ categoryName }}</text></view>
          </view>
          <view class="td-row"><text class="k">账户</text><text class="v">{{ accountName }}</text></view>
        </template>
        <view class="td-row"><text class="k">时间</text><text class="v">{{ timeText }}</text></view>
        <view v-if="tx && tx.note" class="td-row"><text class="k">备注</text><text class="v">{{ tx.note }}</text></view>
        <view v-if="projectName" class="td-row"><text class="k">项目</text><text class="v">{{ projectName }}</text></view>
        <view v-if="merchantName" class="td-row"><text class="k">商家</text><text class="v">{{ merchantName }}</text></view>
        <view v-if="tagNames.length" class="td-row">
          <text class="k">标签</text>
          <view class="v tags"><text v-for="(tn, i) in tagNames" :key="i" class="tag">{{ tn }}</text></view>
        </view>
      </view>

      <!-- 操作栏 -->
      <view class="td-actions">
        <view class="td-btn" @click="goEdit"><text>修改</text></view>
        <view class="td-sep"></view>
        <view class="td-btn del" :class="{ busy: deleting }" @click="confirmDelete"><text>删除</text></view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.td-mask {
  position: fixed; inset: 0; z-index: 600;
  background: rgba(15, 23, 42, 0.42);
  display: flex; align-items: flex-end;
}
.td-sheet {
  width: 100%;
  background: #eef0f2;
  border-radius: 28rpx 28rpx 0 0;
  overflow: hidden;
  padding-bottom: env(safe-area-inset-bottom);
  animation: td-up 0.22s ease;
}
@keyframes td-up {
  from { transform: translateY(100%); }
  to { transform: translateY(0); }
}
/* 头部 */
.td-hero { color: #fff; padding-bottom: 40rpx; }
.td-hero.expense, .td-hero.income { background: linear-gradient(150deg, #2f855a, #276749 72%); }
.td-hero.transfer { background: linear-gradient(150deg, #3b4a63, #2b3647 72%); }
.td-nav {
  display: flex; align-items: center; justify-content: space-between;
  padding: 20rpx 24rpx 4rpx;
}
.td-back { font-size: 48rpx; line-height: 1; width: 60rpx; }
.td-title { font-size: 32rpx; font-weight: 700; }
.td-space { width: 60rpx; }
.td-herobody { padding: 14rpx 40rpx 0; display: flex; flex-direction: column; gap: 10rpx; }
.td-type { font-size: 24rpx; opacity: 0.85; }
.td-amt { font-size: 66rpx; font-weight: 800; letter-spacing: -0.02em; }
/* 信息卡 */
.td-card {
  margin: -24rpx 24rpx 0;
  background: #fff; border-radius: 20rpx;
  padding: 8rpx 28rpx;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.06);
}
.td-row {
  display: flex; align-items: center; justify-content: space-between;
  gap: 24rpx; padding: 30rpx 0; border-bottom: 1rpx solid #f1f3f5;
}
.td-row:last-child { border-bottom: none; }
.k { font-size: 28rpx; color: #8a94a6; flex: 0 0 auto; }
.v { font-size: 30rpx; color: #16181c; font-weight: 600; text-align: right; flex: 1; min-width: 0; }
.v.cat { display: flex; align-items: center; justify-content: flex-end; gap: 12rpx; }
.v.tags { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 10rpx; }
.tag {
  font-size: 22rpx; color: #12a150; background: #e8f6ee;
  border-radius: 8rpx; padding: 4rpx 14rpx; font-weight: 600;
}
/* 操作栏 */
.td-actions {
  margin-top: 40rpx;
  display: flex; align-items: center;
  background: #fff; border-top: 1rpx solid #eef0f2;
}
.td-btn {
  flex: 1; display: flex; align-items: center; justify-content: center;
  padding: 30rpx 0; font-size: 30rpx; color: #2b2f36; font-weight: 700;
}
.td-btn.del { color: #e5484d; }
.td-btn.busy { opacity: 0.5; }
.td-sep { width: 1rpx; height: 44rpx; background: #eceef1; }
</style>
