<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  budgetOverview,
  setTotalBudget,
  setCategoryBudget,
  deleteCategoryBudget,
  copyPreviousBudget
} from '../../api/budget'
import { listCategories, flattenCategories } from '../../api/category'
import { formatAmount, currentMonth, monthLabel } from '../../utils/format'
import { shiftMonth } from '../../api/report'

const month = ref(currentMonth())
const ov = ref(null)
const loading = ref(false)
const expenseCats = ref([])

// 弹层：'total' 设总预算 | 'category' 设分类预算 | null
const sheet = ref(null)
const amountInput = ref('')
const catIndex = ref(0)
const editingCatId = ref(null)

function statusColor(status) {
  if (status === 'OVER') return '#f0553d'
  if (status === 'WARN') return '#f4a72b'
  return '#12a150'
}

// 总预算状态：≥100% 超支 / ≥80% 预警 / 其余正常，驱动状态徽标与进度条颜色
const totalStatus = computed(() => {
  const p = Number(ov.value?.usedPercent) || 0
  return p >= 100 ? 'OVER' : p >= 80 ? 'WARN' : 'OK'
})
const totalStatusLabel = computed(() => ({ OVER: '超支', WARN: '预警', OK: '正常' }[totalStatus.value]))

async function load() {
  loading.value = true
  try {
    const [o, cats] = await Promise.all([budgetOverview(month.value), listCategories()])
    ov.value = o
    expenseCats.value = flattenCategories(cats.expense)
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}

onShow(load)

function prevMonth() {
  month.value = shiftMonth(month.value, -1)
  load()
}
function nextMonth() {
  month.value = shiftMonth(month.value, 1)
  load()
}

// 已设分类预算的 id 集合，用于「添加分类预算」时过滤
const budgetedCatIds = computed(() => new Set((ov.value?.categories || []).map((c) => c.categoryId)))
const addableCats = computed(() => expenseCats.value.filter((c) => !budgetedCatIds.value.has(c.id)))

function openTotal() {
  amountInput.value = ov.value?.totalBudget != null ? String(ov.value.totalBudget) : ''
  sheet.value = 'total'
}
function openAddCategory() {
  if (!addableCats.value.length) {
    uni.showToast({ title: '没有可添加的分类', icon: 'none' })
    return
  }
  editingCatId.value = null
  catIndex.value = 0
  amountInput.value = ''
  sheet.value = 'category'
}
function openEditCategory(item) {
  editingCatId.value = item.categoryId
  amountInput.value = String(item.budget)
  sheet.value = 'categoryEdit'
}
function onCatChange(e) {
  catIndex.value = Number(e.detail.value)
}

async function submitSheet() {
  const amt = amountInput.value.trim()
  if (!amt || Number(amt) <= 0) {
    uni.showToast({ title: '请输入正确金额', icon: 'none' })
    return
  }
  try {
    if (sheet.value === 'total') {
      ov.value = await setTotalBudget(month.value, amt)
    } else if (sheet.value === 'category') {
      const cat = addableCats.value[catIndex.value]
      ov.value = await setCategoryBudget(month.value, cat.id, amt)
    } else if (sheet.value === 'categoryEdit') {
      ov.value = await setCategoryBudget(month.value, editingCatId.value, amt)
    }
    sheet.value = null
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  }
}

function removeCategory(item) {
  uni.showModal({
    title: '删除分类预算',
    content: `确定删除「${item.name}」的预算？`,
    success: async (r) => {
      if (!r.confirm) return
      try {
        ov.value = await deleteCategoryBudget(month.value, item.categoryId)
        await load()
      } catch (e) {
        uni.showToast({ title: e.message || '删除失败', icon: 'none' })
      }
    }
  })
}

async function copyPrev() {
  try {
    ov.value = await copyPreviousBudget(month.value)
    await load()
    uni.showToast({ title: '已沿用上月', icon: 'success' })
  } catch (e) {
    uni.showToast({ title: e.message || '操作失败', icon: 'none' })
  }
}

function pct(v) {
  return Math.min(Number(v) || 0, 100)
}
</script>

<template>
  <view class="page">
    <view class="month-bar">
      <view class="nav" @click="prevMonth"><text>‹</text></view>
      <text class="month">{{ monthLabel(month) }}</text>
      <view class="nav" @click="nextMonth"><text>›</text></view>
    </view>

    <!-- 总预算卡（中性卡 + 语义色进度条） -->
    <view v-if="ov && ov.hasBudget" class="total-card" @click="openTotal">
      <view class="tc-top">
        <text class="tc-label">本月剩余可用</text>
        <text class="chip" :class="totalStatus.toLowerCase()">{{ totalStatusLabel }} {{ ov.usedPercent }}%</text>
      </view>
      <text class="tc-remain" :class="{ neg: Number(ov.remaining) < 0 }">¥{{ formatAmount(ov.remaining) }}</text>
      <view class="barbg">
        <view class="bar" :style="{ width: pct(ov.usedPercent) + '%', background: statusColor(totalStatus) }"></view>
      </view>
      <view class="tc-foot">
        <text>已用 ¥{{ formatAmount(ov.spent) }} / {{ formatAmount(ov.totalBudget) }}</text>
        <text class="tc-edit">编辑 ›</text>
      </view>
    </view>

    <!-- 空状态：图标 + 说明 + 双行动，撑满一屏 -->
    <view v-else-if="ov" class="empty-budget">
      <view class="eb-icon">🎯</view>
      <text class="eb-title">还没有设置本月预算</text>
      <text class="eb-sub">设定每月预算，随时知道还能花多少，花超了也能第一时间发现。</text>
      <view class="eb-actions">
        <view class="btn primary" @click="openTotal">设置总预算</view>
        <view class="btn ghost" @click="copyPrev">沿用上月</view>
      </view>
    </view>

    <!-- 预算健康 -->
    <view v-if="ov && ov.hasBudget && ov.health" class="health">
      <view class="h-item">
        <text class="h-k">剩余天数</text>
        <text class="h-v">{{ ov.health.daysLeft }} 天</text>
      </view>
      <view class="h-item mid">
        <text class="h-k">日均可用</text>
        <text class="h-v">¥{{ formatAmount(ov.health.dailyAvailable) }}</text>
      </view>
      <view class="h-item">
        <text class="h-k">预计月底</text>
        <text class="h-v" :class="{ neg: ov.health.projectedOver }">
          ¥{{ formatAmount(ov.health.projectedBalance) }}
        </text>
      </view>
    </view>

    <!-- 分类预算：仅在已有总预算或已有分类预算时出现，避免空上加空 -->
    <block v-if="ov && (ov.hasBudget || ov.categories.length)">
      <view class="section-head">
        <text class="sh-title">分类预算</text>
        <text class="sh-add" @click="openAddCategory">＋ 添加</text>
      </view>

      <view v-if="!ov.categories.length" class="cat-empty">
        <text class="ce-text">还没有分类预算</text>
        <text class="ce-hint">为具体分类单独设限，控得更细</text>
      </view>

      <view class="cat-list" v-else>
        <view
          v-for="c in ov.categories"
          :key="c.categoryId"
          class="cat"
          @click="openEditCategory(c)"
          @longpress="removeCategory(c)"
        >
          <view class="cat-head">
            <text class="cat-name">{{ c.name }}</text>
            <text class="cat-amt" :style="{ color: statusColor(c.status) }">
              ¥{{ formatAmount(c.spent) }} / {{ formatAmount(c.budget) }}
            </text>
          </view>
          <view class="barbg">
            <view class="bar" :style="{ width: pct(c.usedPercent) + '%', background: statusColor(c.status) }"></view>
          </view>
          <view class="cat-foot">
            <text>{{ c.txCount }} 笔</text>
            <text :style="{ color: statusColor(c.status) }">{{ c.usedPercent }}%</text>
          </view>
        </view>
      </view>

      <text v-if="ov.categories.length" class="hint">点击编辑 · 长按删除</text>
    </block>

    <!-- 弹层 -->
    <view v-if="sheet" class="mask" @click="sheet = null">
      <view class="sheet" @click.stop>
        <text class="sheet-title">
          {{ sheet === 'total' ? '设置月度总预算' : sheet === 'category' ? '添加分类预算' : '编辑分类预算' }}
        </text>
        <picker
          v-if="sheet === 'category'"
          class="field picker"
          :range="addableCats"
          range-key="label"
          :value="catIndex"
          @change="onCatChange"
        >
          <text>分类：{{ addableCats[catIndex]?.label || '-' }}</text>
        </picker>
        <input v-model="amountInput" class="field" type="digit" placeholder="预算金额" />
        <view class="btn primary submit" @click="submitSheet">保存</view>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* 设计 token（对齐 design/youyu-ux-redesign.html）：中性底 + 品牌绿只做强调 */
.page {
  min-height: 100vh;
  padding: 24rpx 28rpx;
  background: #eef0f2;
  box-sizing: border-box;
}

/* 月份切换 */
.month-bar {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 28rpx;
  margin: 8rpx 0 24rpx;
}
.nav {
  width: 56rpx;
  height: 56rpx;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 4rpx 14rpx rgba(20, 24, 28, 0.06);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34rpx;
  color: #5b6470;
}
.nav:active {
  background: #f6f7f9;
}
.month {
  font-size: 32rpx;
  font-weight: 800;
  color: #16181c;
  min-width: 220rpx;
  text-align: center;
}

/* 通用卡片阴影/圆角 */
.total-card,
.health,
.cat-list,
.cat-empty {
  background: #fff;
  border-radius: 32rpx;
  box-shadow: 0 6rpx 22rpx rgba(20, 24, 28, 0.06);
}

/* 通用进度条 */
.barbg {
  height: 18rpx;
  background: #f6f7f9;
  border-radius: 999rpx;
  overflow: hidden;
}
.bar {
  height: 100%;
  border-radius: 999rpx;
  background: #12a150;
  transition: width 0.3s ease;
}

/* 状态徽标 */
.chip {
  font-size: 22rpx;
  font-weight: 700;
  padding: 6rpx 16rpx;
  border-radius: 999rpx;
}
.chip.ok {
  background: #e6f6ec;
  color: #0e8a44;
}
.chip.warn {
  background: #fdf3e2;
  color: #b9761a;
}
.chip.over {
  background: #fdece8;
  color: #f0553d;
}

/* 总预算卡（中性） */
.total-card {
  padding: 32rpx;
  margin-bottom: 24rpx;
}
.tc-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.tc-label {
  font-size: 24rpx;
  color: #9aa2ad;
}
.tc-remain {
  display: block;
  margin: 10rpx 0 24rpx;
  font-size: 62rpx;
  font-weight: 800;
  color: #16181c;
  font-variant-numeric: tabular-nums;
}
.tc-remain.neg {
  color: #f0553d;
}
.tc-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: #9aa2ad;
}
.tc-edit {
  color: #0e8a44;
  font-weight: 600;
}

/* 空状态：撑满一屏，图标 + 说明 + 双行动 */
.empty-budget {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  padding: 96rpx 48rpx 40rpx;
}
.eb-icon {
  width: 140rpx;
  height: 140rpx;
  border-radius: 50%;
  background: #e6f6ec;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 68rpx;
  margin-bottom: 32rpx;
}
.eb-title {
  font-size: 34rpx;
  font-weight: 800;
  color: #16181c;
}
.eb-sub {
  margin-top: 14rpx;
  font-size: 26rpx;
  line-height: 1.7;
  color: #9aa2ad;
  max-width: 460rpx;
}
.eb-actions {
  margin-top: 48rpx;
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 20rpx;
}

/* 按钮：用 view 规避原生 button 的默认边框/尺寸问题 */
.btn {
  height: 92rpx;
  border-radius: 999rpx;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 30rpx;
  font-weight: 700;
}
.btn.primary {
  background: #12a150;
  color: #fff;
  box-shadow: 0 14rpx 30rpx rgba(18, 161, 80, 0.24);
}
.btn.primary:active {
  background: #0e8a44;
}
.btn.ghost {
  background: #f6f7f9;
  color: #16181c;
}
.btn.ghost:active {
  background: #eceef1;
}

/* 预算健康 */
.health {
  display: flex;
  padding: 28rpx 0;
  margin-bottom: 24rpx;
}
.h-item {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8rpx;
}
.h-item.mid {
  border-left: 1rpx solid #eceef1;
  border-right: 1rpx solid #eceef1;
}
.h-k {
  font-size: 22rpx;
  color: #9aa2ad;
}
.h-v {
  font-size: 30rpx;
  font-weight: 800;
  color: #16181c;
  font-variant-numeric: tabular-nums;
}
.h-v.neg {
  color: #f0553d;
}

/* 分类预算 */
.section-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: 20rpx 8rpx 16rpx;
}
.sh-title {
  font-size: 30rpx;
  font-weight: 800;
  color: #16181c;
}
.sh-add {
  font-size: 26rpx;
  color: #0e8a44;
  font-weight: 700;
}
.cat-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10rpx;
  padding: 56rpx 0;
}
.ce-text {
  font-size: 28rpx;
  color: #5b6470;
  font-weight: 600;
}
.ce-hint {
  font-size: 24rpx;
  color: #9aa2ad;
}

.cat-list {
  padding: 8rpx 28rpx;
}
.cat {
  padding: 26rpx 0;
  border-top: 1rpx solid #eceef1;
}
.cat-list .cat:first-child {
  border-top: none;
}
.cat-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16rpx;
}
.cat-name {
  font-size: 28rpx;
  font-weight: 700;
  color: #16181c;
}
.cat-amt {
  font-size: 26rpx;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}
.cat-foot {
  display: flex;
  justify-content: space-between;
  margin-top: 12rpx;
  font-size: 22rpx;
  color: #9aa2ad;
}
.hint {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: #9aa2ad;
  margin: 24rpx 0;
}

/* 底部弹层 */
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.4);
  display: flex;
  align-items: flex-end;
  z-index: 100;
}
.sheet {
  width: 100%;
  background: #fff;
  border-radius: 32rpx 32rpx 0 0;
  padding: 40rpx;
  padding-bottom: calc(40rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  gap: 24rpx;
}
.sheet-title {
  font-size: 32rpx;
  font-weight: 800;
  color: #16181c;
}
.field {
  background: #f6f7f9;
  border-radius: 16rpx;
  padding: 26rpx;
  font-size: 30rpx;
  color: #16181c;
}
.submit {
  margin-top: 8rpx;
}
</style>
