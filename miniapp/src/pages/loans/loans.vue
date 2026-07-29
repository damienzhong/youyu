<script setup>
import { ref, computed } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  listLoans,
  createLoan,
  updateLoan,
  settleLoan,
  deleteLoan,
  LOAN_DIRECTIONS
} from '../../api/loan'
import { formatAmount } from '../../utils/format'

const borrowOutstanding = ref('0.00')
const lendOutstanding = ref('0.00')
const loans = ref([])
const loading = ref(false)

// 过滤：进行中 / 已结清 / 全部
const filter = ref('active')
const FILTERS = [
  { key: 'active', label: '进行中' },
  { key: 'settled', label: '已结清' },
  { key: 'all', label: '全部' }
]
const shown = computed(() => {
  if (filter.value === 'active') return loans.value.filter((l) => !l.settled)
  if (filter.value === 'settled') return loans.value.filter((l) => l.settled)
  return loans.value
})

async function load() {
  loading.value = true
  try {
    const r = await listLoans()
    borrowOutstanding.value = r.borrowOutstanding
    lendOutstanding.value = r.lendOutstanding
    loans.value = r.loans || []
  } catch (e) {
    if (e && e.code !== 'HTTP_401') uni.showToast({ title: e.message || '加载失败', icon: 'none' })
  } finally {
    loading.value = false
  }
}
onShow(load)

function dateOf(iso) {
  return iso ? iso.slice(0, 10) : ''
}

// ---------- 表单 ----------
const showForm = ref(false)
const submitting = ref(false)
const form = ref({ id: null, direction: 'BORROW', counterparty: '', amount: '', note: '', settled: false })
const isEditing = computed(() => form.value.id !== null)

function openCreate() {
  form.value = { id: null, direction: 'BORROW', counterparty: '', amount: '', note: '', settled: false }
  showForm.value = true
}
function openEdit(l) {
  form.value = {
    id: l.id, direction: l.direction, counterparty: l.counterparty,
    amount: String(l.amount), note: l.note || '', settled: l.settled
  }
  showForm.value = true
}

async function submit() {
  const counterparty = form.value.counterparty.trim()
  if (!counterparty) {
    uni.showToast({ title: '请输入对方名称', icon: 'none' })
    return
  }
  if (!form.value.amount || Number(form.value.amount) <= 0) {
    uni.showToast({ title: '请输入正确金额', icon: 'none' })
    return
  }
  submitting.value = true
  try {
    const payload = {
      direction: form.value.direction,
      counterparty,
      amount: form.value.amount,
      note: form.value.note.trim() || undefined
    }
    if (isEditing.value) await updateLoan(form.value.id, payload)
    else await createLoan(payload)
    showForm.value = false
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '保存失败', icon: 'none' })
  } finally {
    submitting.value = false
  }
}

async function toggleSettle(l) {
  try {
    await settleLoan(l.id, !l.settled)
    await load()
  } catch (e) {
    uni.showToast({ title: e.message || '操作失败', icon: 'none' })
  }
}

function confirmDelete() {
  const id = form.value.id
  uni.showModal({
    title: '删除记录',
    content: '确定删除这条借贷记录？',
    confirmColor: '#e5484d',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await deleteLoan(id)
        showForm.value = false
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
    <!-- 汇总 -->
    <view class="summary">
      <view class="s-tile borrow">
        <text class="s-k">借入 / 待还</text>
        <text class="s-v">¥{{ formatAmount(borrowOutstanding) }}</text>
      </view>
      <view class="s-tile lend">
        <text class="s-k">借出 / 待收</text>
        <text class="s-v">¥{{ formatAmount(lendOutstanding) }}</text>
      </view>
    </view>

    <!-- 过滤 -->
    <view class="filters">
      <text
        v-for="f in FILTERS"
        :key="f.key"
        class="f"
        :class="{ on: filter === f.key }"
        @click="filter = f.key"
      >{{ f.label }}</text>
    </view>

    <view v-if="!shown.length && !loading" class="empty">
      {{ filter === 'settled' ? '暂无已结清记录' : '暂无借贷记录，点右下角添加' }}
    </view>

    <view class="list" v-if="shown.length">
      <view v-for="l in shown" :key="l.id" class="item" :class="{ settled: l.settled }" @click="openEdit(l)">
        <text class="badge" :class="l.direction === 'BORROW' ? 'b' : 'l'">
          {{ l.direction === 'BORROW' ? '借入' : '借出' }}
        </text>
        <view class="i-main">
          <text class="i-name">{{ l.counterparty }}</text>
          <text class="i-sub">{{ dateOf(l.occurredAt) }}{{ l.note ? ' · ' + l.note : '' }}</text>
        </view>
        <view class="i-right">
          <text class="i-amt" :class="l.direction === 'BORROW' ? 'exp' : 'inc'">¥{{ formatAmount(l.amount) }}</text>
          <text class="i-act" @click.stop="toggleSettle(l)">{{ l.settled ? '恢复' : '结清' }}</text>
        </view>
      </view>
    </view>

    <text v-if="shown.length" class="hint">点击编辑 · 右侧可标记结清</text>
    <view class="fab" @click="openCreate">＋</view>

    <!-- 表单 -->
    <view v-if="showForm" class="mask" @click="showForm = false">
      <view class="sheet" @click.stop>
        <view class="form-head">
          <text class="fh-cancel" @click="showForm = false">取消</text>
          <text class="fh-title">{{ isEditing ? '编辑借贷' : '新增借贷' }}</text>
          <text class="fh-save" @click="submit">保存</text>
        </view>
        <view class="seg">
          <text
            v-for="d in LOAN_DIRECTIONS"
            :key="d.value"
            class="seg-i"
            :class="{ on: form.direction === d.value }"
            @click="form.direction = d.value"
          >{{ d.label }}</text>
        </view>
        <view class="form-body">
          <view class="frow">
            <text class="fk">对方</text>
            <input v-model="form.counterparty" class="finput" placeholder="姓名 / 备注" maxlength="50" />
          </view>
          <view class="frow">
            <text class="fk">金额</text>
            <input v-model="form.amount" class="finput" type="digit" placeholder="0.00" />
          </view>
          <view class="frow">
            <text class="fk">备注</text>
            <input v-model="form.note" class="finput" placeholder="可选" maxlength="200" />
          </view>
        </view>
        <button v-if="isEditing" class="del" @click="confirmDelete">删除记录</button>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  padding: 24rpx;
  background: #eef0f2;
}
.summary {
  display: flex;
  gap: 20rpx;
  margin-bottom: 20rpx;
}
.s-tile {
  flex: 1;
  border-radius: 22rpx;
  padding: 28rpx;
  color: #fff;
  box-shadow: 0 12rpx 30rpx rgba(20, 24, 28, 0.12);
}
.s-tile.borrow {
  background: linear-gradient(150deg, #f0806a, #e5563d);
}
.s-tile.lend {
  background: linear-gradient(150deg, #24bd6a, #0f8a45);
}
.s-k {
  font-size: 24rpx;
  opacity: 0.92;
}
.s-v {
  display: block;
  font-size: 44rpx;
  font-weight: 800;
  margin-top: 8rpx;
}
.filters {
  display: flex;
  gap: 16rpx;
  margin-bottom: 16rpx;
}
.f {
  font-size: 26rpx;
  color: #5b6470;
  padding: 10rpx 24rpx;
  background: #fff;
  border-radius: 999rpx;
}
.f.on {
  background: #16181c;
  color: #fff;
  font-weight: 700;
}
.empty {
  margin-top: 120rpx;
  text-align: center;
  color: #9aa2ad;
  font-size: 28rpx;
}
.list {
  background: #fff;
  border-radius: 22rpx;
  overflow: hidden;
  box-shadow: 0 8rpx 24rpx rgba(20, 24, 28, 0.05);
}
.item {
  display: flex;
  align-items: center;
  gap: 18rpx;
  padding: 26rpx 28rpx;
  border-top: 1rpx solid #f1f3f5;
}
.list .item:first-child {
  border-top: none;
}
.item.settled {
  opacity: 0.5;
}
.badge {
  font-size: 22rpx;
  font-weight: 700;
  padding: 6rpx 14rpx;
  border-radius: 999rpx;
  flex: 0 0 auto;
}
.badge.b {
  background: #fdece8;
  color: #e5563d;
}
.badge.l {
  background: #e6f6ec;
  color: #0f8a45;
}
.i-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.i-name {
  font-size: 30rpx;
  font-weight: 600;
  color: #16181c;
}
.i-sub {
  font-size: 22rpx;
  color: #9aa2ad;
}
.i-right {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 8rpx;
}
.i-amt {
  font-size: 30rpx;
  font-weight: 800;
}
.i-amt.exp {
  color: #e5563d;
}
.i-amt.inc {
  color: #0f8a45;
}
.i-act {
  font-size: 22rpx;
  color: #576b95;
  background: #f4f6f8;
  border-radius: 999rpx;
  padding: 4rpx 16rpx;
}
.hint {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: #bbb;
  margin: 20rpx 0;
}
.fab {
  position: fixed;
  right: 44rpx;
  bottom: 68rpx;
  width: 100rpx;
  height: 100rpx;
  border-radius: 50%;
  background: linear-gradient(135deg, #18b85a, #0e8a44);
  color: #fff;
  font-size: 58rpx;
  line-height: 100rpx;
  text-align: center;
  box-shadow: 0 14rpx 34rpx rgba(18, 161, 80, 0.4);
}
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.42);
  display: flex;
  align-items: flex-end;
  z-index: 50;
}
.sheet {
  width: 100%;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 32rpx 32rpx calc(32rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.form-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.fh-cancel {
  font-size: 28rpx;
  color: #9aa2ad;
}
.fh-title {
  font-size: 30rpx;
  font-weight: 800;
}
.fh-save {
  font-size: 28rpx;
  color: #12a150;
  font-weight: 700;
}
.seg {
  display: flex;
  background: #f4f6f8;
  border-radius: 14rpx;
  padding: 6rpx;
  margin: 20rpx 0;
}
.seg-i {
  flex: 1;
  text-align: center;
  padding: 16rpx 0;
  font-size: 28rpx;
  font-weight: 700;
  color: #5b6470;
  border-radius: 10rpx;
}
.seg-i.on {
  background: #fff;
  color: #16181c;
  box-shadow: 0 2rpx 8rpx rgba(0, 0, 0, 0.06);
}
.form-body {
  background: #f6f7f9;
  border-radius: 18rpx;
  padding: 0 24rpx;
}
.frow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 28rpx 0;
  border-top: 1rpx solid #eceef1;
}
.frow:first-child {
  border-top: none;
}
.fk {
  font-size: 30rpx;
  color: #5b6470;
}
.finput {
  flex: 1;
  text-align: right;
  font-size: 30rpx;
  color: #16181c;
}
.del {
  margin-top: 24rpx;
  background: #fff;
  color: #e5484d;
  border-radius: 44rpx;
  font-size: 30rpx;
  border: 1rpx solid #f1d4d4;
}
</style>
