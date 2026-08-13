<script setup>
import { computed, ref } from 'vue'
import { onShow } from '@dcloudio/uni-app'
import {
  fetchReminders,
  createReminder,
  updateReminder,
  deleteReminder
} from '../../api/reminder'
import {
  FREQUENCY_OPTIONS,
  frequencyLabel,
  validateReminderForm,
  normalizeQuota,
  REMINDER_TIMEOUT_MS,
  REMINDER_MAX
} from '../../utils/reminder'
import {
  fetchBudgetReminderStatus,
  updateBudgetReminderPreference
} from '../../api/budgetReminder'
import { WX_REMINDER_TEMPLATE_ID, WX_BUDGET_REMINDER_TEMPLATE_ID } from '../../utils/config'
import { requestSubscribe } from '../../utils/subscribe'
import { useAuthStore } from '../../stores/auth'

/**
 * 记账提醒设置页（需求 10.1~10.11）。
 *
 * 与账本无关：api/reminder.js 全部 noLedger:true，页面不读写任何账本/金额/邮箱/邀请码，
 * 也不在任何位置展示这些内容（需求 10.11）。时间取值后端已是 HH:mm（Asia/Shanghai 口径），
 * 直接展示，不做本地时区换算。
 *
 * 请求范式照抄连续记账页 / 成长页：seq 请求序号 + withTimeout 客户端 3000ms 超时守卫，
 * 底层请求仍会跑完，靠序号忽略迟到结果；任一请求出错或超时只切失败态 + 重试入口，
 * 自动重试 0 次（需求 10.9）。
 *
 * 未登录（需求 10.10）：不发任何请求、不展示任何提醒项，只展示登录入口。
 */

const auth = useAuthStore()

// 未登录分支：一条请求都不发（需求 10.10），与数据态互斥。
const guest = ref(false)

// 列表请求状态（需求 10.2）：loading 只展示占位、不渲染任何提醒项。
const listState = ref('loading') // loading | ready | error
const reminders = ref([])
const quota = ref(0)
let listSeq = 0
let listInFlight = false

// 授权上报进行中：防止连点重复上报。
const granting = ref(false)

// —— 预算提醒（独立于记账提醒，需求 10.1~10.9）——
// 状态与记账提醒并列、互不影响：独立的开关、额度、授权入口与请求序号。
const budgetState = ref('loading') // loading | ready | error
const budgetEnabled = ref(true)
const budgetQuota = ref(0)
const budgetGranting = ref(false)
let budgetSeq = 0
let budgetInFlight = false

// —— 新增/编辑表单弹层 ——
const sheetVisible = ref(false)
const editingId = ref(null) // null 表示新增
const form = ref({ frequency: '', remindTime: '', enabled: true })
const formError = ref('') // 就地校验提示（需求 10.4）
const submitting = ref(false)

/** 客户端单请求超时（需求 10.9）：底层请求仍会跑完，靠序号守卫忽略其迟到结果。 */
function withTimeout(promise, ms) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject({ code: 'TIMEOUT', message: '请求超时' }), ms)
    promise.then(
      (v) => {
        clearTimeout(timer)
        resolve(v)
      },
      (e) => {
        clearTimeout(timer)
        reject(e)
      }
    )
  })
}

/** 统一失败提示：只 toast，不自动重试（需求 10.9）。 */
function toastError(e) {
  const msg = (e && e.message) || '操作失败，请稍后重试'
  uni.showToast({ title: msg, icon: 'none' })
}

/**
 * 拉取提醒列表 + 剩余订阅次数（需求 10.1、10.2）。
 * 出错只切 error 态、展示重试入口，绝不自动重发（需求 10.9）。
 */
async function loadList() {
  if (listInFlight) return
  const s = ++listSeq
  listInFlight = true
  listState.value = 'loading'
  try {
    const res = await withTimeout(fetchReminders(), REMINDER_TIMEOUT_MS)
    if (s !== listSeq) return
    reminders.value = Array.isArray(res?.reminders)
      ? res.reminders.filter((it) => it && it.reminderId != null)
      : []
    quota.value = normalizeQuota(res?.remainingQuota)
    listState.value = 'ready'
  } catch (e) {
    if (s !== listSeq) return
    listState.value = 'error'
  } finally {
    if (s === listSeq) listInFlight = false
  }
}

/**
 * 拉取预算提醒状态（需求 10.2）：返回前 loading 占位、返回后以真实取值渲染开关与剩余次数。
 * 出错只切 error 态、展示重试入口，绝不自动重发（需求 10.7）。独立于记账提醒列表请求。
 */
async function loadBudgetStatus() {
  if (budgetInFlight) return
  const s = ++budgetSeq
  budgetInFlight = true
  budgetState.value = 'loading'
  try {
    const res = await withTimeout(fetchBudgetReminderStatus(), REMINDER_TIMEOUT_MS)
    if (s !== budgetSeq) return
    budgetEnabled.value = res?.enabled !== false
    budgetQuota.value = normalizeQuota(res?.remainingQuota)
    budgetState.value = 'ready'
  } catch (e) {
    if (s !== budgetSeq) return
    budgetState.value = 'error'
  } finally {
    if (s === budgetSeq) budgetInFlight = false
  }
}

onShow(() => {
  if (!auth.isLoggedIn) {
    // 未登录：不发任何请求，展示登录入口（需求 10.10）。
    guest.value = true
    return
  }
  guest.value = false
  loadList()
  loadBudgetStatus()
})

function retryList() {
  if (listInFlight) return
  loadList()
}

function retryBudget() {
  if (budgetInFlight) return
  loadBudgetStatus()
}

/** 切换预算提醒开关（需求 10.3）：调更新偏好接口，成功后就地更新开关。 */
async function toggleBudgetEnabled(e) {
  const next = e && e.detail ? !!e.detail.value : !budgetEnabled.value
  const prev = budgetEnabled.value
  try {
    const res = await withTimeout(
      updateBudgetReminderPreference(next),
      REMINDER_TIMEOUT_MS
    )
    budgetEnabled.value = res?.enabled != null ? res.enabled : next
    if (res?.remainingQuota != null) budgetQuota.value = normalizeQuota(res.remainingQuota)
  } catch (err) {
    budgetEnabled.value = prev // 失败回滚开关视图，不进错误态（需求 10.7）
    toastError(err)
  }
}

/**
 * 请求预算提醒订阅授权（需求 10.4~10.6）：wx.requestSubscribeMessage 请求预算提醒模板，
 * 用户点「允许」才上报；拒绝 / 失败不上报、提示未授权 + 再次授权入口、页面不进错误态。
 */
async function requestBudgetGrant() {
  if (budgetGranting.value) return
  if (typeof wx === 'undefined' || typeof wx.requestSubscribeMessage !== 'function') {
    uni.showToast({ title: '请在微信小程序内开启提醒', icon: 'none' })
    return
  }
  if (!WX_BUDGET_REMINDER_TEMPLATE_ID) {
    uni.showToast({ title: '预算提醒模板未配置', icon: 'none' })
    return
  }
  budgetGranting.value = true
  try {
    // 复用统一编排：只请求预算提醒模板，保留预算提醒区块语义（需求 4.2）。
    const { accepted } = await requestSubscribe([WX_BUDGET_REMINDER_TEMPLATE_ID])
    if (accepted.includes(WX_BUDGET_REMINDER_TEMPLATE_ID)) {
      await loadBudgetStatus() // 上报后从服务端刷新最新剩余次数
      uni.showToast({ title: '已开启预算提醒', icon: 'success' })
    } else {
      uni.showToast({ title: '未授权，暂时无法收到预算提醒', icon: 'none' })
    }
  } finally {
    budgetGranting.value = false
  }
}

function goLogin() {
  uni.navigateTo({ url: '/pages/login/login' })
}

// —— 新增/编辑 ——

function openAdd() {
  if (reminders.value.length >= REMINDER_MAX) {
    uni.showToast({ title: `最多只能设置 ${REMINDER_MAX} 条提醒`, icon: 'none' })
    return
  }
  editingId.value = null
  form.value = { frequency: '', remindTime: '', enabled: true }
  formError.value = ''
  sheetVisible.value = true
}

function openEdit(item) {
  editingId.value = item.reminderId
  form.value = {
    frequency: item.frequency,
    remindTime: item.remindTime,
    enabled: item.enabled !== false
  }
  formError.value = ''
  sheetVisible.value = true
}

function closeSheet() {
  if (submitting.value) return
  sheetVisible.value = false
}

function pickFrequency(value) {
  form.value.frequency = value
  formError.value = ''
}

function onTimeChange(e) {
  form.value.remindTime = e.detail.value
  formError.value = ''
}

/**
 * 提交新增/编辑（需求 10.3、10.4）：
 * 先本地校验频率已选、小时 0–23、分钟 0–59；不合法则就地提示、不发请求、保留已填内容。
 */
async function submitForm() {
  if (submitting.value) return
  const check = validateReminderForm(form.value)
  if (!check.ok) {
    formError.value = check.field === 'frequency' ? '请选择提醒频率' : '请选择合法的提醒时间'
    return
  }
  submitting.value = true
  const body = {
    frequency: form.value.frequency,
    remindTime: form.value.remindTime,
    enabled: form.value.enabled
  }
  try {
    if (editingId.value == null) {
      await withTimeout(createReminder(body), REMINDER_TIMEOUT_MS)
    } else {
      await withTimeout(updateReminder(editingId.value, body), REMINDER_TIMEOUT_MS)
    }
    sheetVisible.value = false
    uni.showToast({ title: '已保存', icon: 'success' })
    await loadList() // 成功后就地刷新列表（需求 10.8）
  } catch (e) {
    toastError(e)
  } finally {
    submitting.value = false
  }
}

/** 切换启用开关（需求 10.8）：调更新接口，成功后就地更新该项。 */
async function toggleEnabled(item) {
  const next = !(item.enabled !== false)
  try {
    const res = await withTimeout(
      updateReminder(item.reminderId, { enabled: next }),
      REMINDER_TIMEOUT_MS
    )
    const idx = reminders.value.findIndex((r) => r.reminderId === item.reminderId)
    if (idx >= 0) {
      reminders.value[idx] = {
        ...reminders.value[idx],
        enabled: res?.enabled != null ? res.enabled : next
      }
    }
  } catch (e) {
    toastError(e)
  }
}

/** 删除提醒（需求 10.8）：确认后调删除接口，成功后就地移除。 */
function removeReminder(item) {
  uni.showModal({
    title: '删除提醒',
    content: '确定删除这条提醒？',
    success: async (r) => {
      if (!r.confirm) return
      try {
        await withTimeout(deleteReminder(item.reminderId), REMINDER_TIMEOUT_MS)
        reminders.value = reminders.value.filter((x) => x.reminderId !== item.reminderId)
        uni.showToast({ title: '已删除', icon: 'success' })
      } catch (e) {
        toastError(e)
      }
    }
  })
}

// —— 订阅授权（需求 10.5、10.6、10.7）——

/**
 * 请求一次性订阅授权：wx.requestSubscribeMessage → 用户点「允许」才上报（需求 10.5）。
 * 拒绝 / 调用失败 → 不上报、提示未授权 + 再次授权入口、页面不进错误态（需求 10.6）。
 */
async function requestGrant() {
  if (granting.value) return
  // H5 / 非微信环境或未配置模板 id：无法发起订阅授权，给出提示，页面不进错误态。
  if (typeof wx === 'undefined' || typeof wx.requestSubscribeMessage !== 'function') {
    uni.showToast({ title: '请在微信小程序内开启提醒', icon: 'none' })
    return
  }
  if (!WX_REMINDER_TEMPLATE_ID) {
    uni.showToast({ title: '提醒模板未配置', icon: 'none' })
    return
  }
  granting.value = true
  try {
    // 复用统一编排（retention-nudges）：批量请求 + 按模板上报 accept；此处只请求记账提醒模板。
    const { accepted } = await requestSubscribe([WX_REMINDER_TEMPLATE_ID])
    if (accepted.includes(WX_REMINDER_TEMPLATE_ID)) {
      await loadList() // 上报后从服务端刷新最新剩余次数（需求 10.5）
      uni.showToast({ title: '已开启提醒', icon: 'success' })
    } else {
      uni.showToast({ title: '未授权，暂时无法收到提醒', icon: 'none' })
    }
  } finally {
    granting.value = false
  }
}

// 频率标签在模板里用 frequencyLabel 直接映射。
const list = computed(() => reminders.value)
const quotaZero = computed(() => quota.value <= 0)
const budgetQuotaZero = computed(() => budgetQuota.value <= 0)
</script>

<template>
  <view class="page">
    <!-- 未登录：只展示登录入口，不展示任何提醒项（需求 10.10） -->
    <view v-if="guest" class="fail-card">
      <AppIcon name="badge" :size="52" color="#12a150" />
      <text class="f-t">登录后设置记账提醒</text>
      <text class="f-d">到点提醒你记账，别再忘记啦</text>
      <text class="retry" @click="goLogin">去登录</text>
    </view>

    <template v-else>
      <!-- 剩余订阅次数 + 授权入口（需求 10.1、10.7） -->
      <view class="quota-card" :class="{ warn: quotaZero }">
        <view class="q-main">
          <text class="q-k">剩余可提醒次数</text>
          <text class="q-v">{{ quota }}</text>
        </view>
        <text v-if="quotaZero" class="q-tip">授权后才能继续收到提醒</text>
        <text v-else class="q-tip">每收到一次提醒消耗一次授权</text>
        <text class="q-btn" @click="requestGrant">{{ quotaZero ? '去授权' : '再次授权' }}</text>
      </view>

      <!-- 列表加载中：只展示占位，不渲染任何提醒项（需求 10.2） -->
      <view v-if="listState === 'loading'" class="fail-card slim">
        <text class="f-d">正在加载提醒…</text>
      </view>

      <!-- 列表出错：失败提示 + 重试入口，不展示提醒项（需求 10.9） -->
      <view v-else-if="listState === 'error'" class="fail-card slim">
        <AppIcon name="warning" :size="52" color="#c7ccd2" />
        <text class="f-t">提醒没能加载出来</text>
        <text class="f-d">网络不太顺畅，稍后再试一次</text>
        <text class="retry" @click="retryList">重试</text>
      </view>

      <!-- 列表就绪 -->
      <template v-else>
        <view class="sect">我的提醒</view>

        <!-- 空列表（需求 10.2） -->
        <view v-if="list.length === 0" class="fail-card slim">
          <text class="f-t">还没有提醒</text>
          <text class="f-d">添加一条，到点提醒你记账</text>
        </view>

        <view v-else class="card">
          <view v-for="it in list" :key="it.reminderId" class="row">
            <view class="r-main" @click="openEdit(it)">
              <text class="r-time">{{ it.remindTime }}</text>
              <text class="r-freq">{{ frequencyLabel(it.frequency) }}</text>
            </view>
            <switch
              class="r-switch"
              color="#12a150"
              :checked="it.enabled !== false"
              @change="toggleEnabled(it)"
            />
            <text class="r-del" @click="removeReminder(it)">删除</text>
          </view>
        </view>

        <view class="add-btn" @click="openAdd">＋ 添加提醒</view>
      </template>

      <!-- 预算提醒区块（独立于记账提醒，需求 10.1~10.9） -->
      <view class="sect">预算提醒</view>

      <!-- 加载中：占位（需求 10.2） -->
      <view v-if="budgetState === 'loading'" class="fail-card slim">
        <text class="f-d">正在加载预算提醒…</text>
      </view>

      <!-- 出错：失败提示 + 重试入口（需求 10.7） -->
      <view v-else-if="budgetState === 'error'" class="fail-card slim">
        <AppIcon name="warning" :size="52" color="#c7ccd2" />
        <text class="f-t">预算提醒没能加载出来</text>
        <text class="f-d">网络不太顺畅，稍后再试一次</text>
        <text class="retry" @click="retryBudget">重试</text>
      </view>

      <!-- 就绪：开关 + 剩余次数 + 授权入口（需求 10.1、10.3、10.4、10.6） -->
      <template v-else>
        <view class="card">
          <view class="row">
            <view class="r-main col">
              <text class="r-t2">预算超支提醒</text>
              <text class="r-d2">预算超支或接近上限时提醒你</text>
            </view>
            <switch
              class="r-switch"
              color="#12a150"
              :checked="budgetEnabled"
              @change="toggleBudgetEnabled"
            />
          </view>
        </view>

        <view class="quota-card" :class="{ warn: budgetQuotaZero }">
          <view class="q-main">
            <text class="q-k">剩余可提醒次数</text>
            <text class="q-v">{{ budgetQuota }}</text>
          </view>
          <text v-if="budgetQuotaZero" class="q-tip">授权后才能继续收到预算提醒</text>
          <text v-else class="q-tip">每收到一次预算提醒消耗一次授权</text>
          <text class="q-btn" @click="requestBudgetGrant">
            {{ budgetQuotaZero ? '去授权' : '再次授权' }}
          </text>
        </view>
      </template>
    </template>

    <!-- 新增/编辑弹层（需求 10.3、10.4） -->
    <view v-if="sheetVisible" class="mask" @click="closeSheet">
      <view class="sheet" @click.stop>
        <text class="sheet-title">{{ editingId == null ? '新增提醒' : '编辑提醒' }}</text>

        <text class="field-label">提醒频率</text>
        <view class="freq-row">
          <view
            v-for="opt in FREQUENCY_OPTIONS"
            :key="opt.value"
            class="freq-chip"
            :class="{ on: form.frequency === opt.value }"
            @click="pickFrequency(opt.value)"
          >
            {{ opt.label }}
          </view>
        </view>

        <text class="field-label">提醒时间</text>
        <picker mode="time" :value="form.remindTime" @change="onTimeChange">
          <view class="time-picker">
            <text :class="{ placeholder: !form.remindTime }">
              {{ form.remindTime || '选择时:分' }}
            </text>
          </view>
        </picker>

        <view class="switch-row">
          <text class="field-label inline">启用</text>
          <switch color="#12a150" :checked="form.enabled" @change="form.enabled = $event.detail.value" />
        </view>

        <text v-if="formError" class="form-error">{{ formError }}</text>

        <view class="sheet-actions">
          <view class="btn ghost" @click="closeSheet">取消</view>
          <view class="btn primary" :class="{ disabled: submitting }" @click="submitForm">
            {{ submitting ? '保存中…' : '保存' }}
          </view>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page {
  min-height: 100vh;
  background: #f2f4f6;
  padding: 24rpx 24rpx 0;
}
/* 失败 / 加载 / 登录引导卡（复用连续记账页观感） */
.fail-card {
  background: #fff;
  border-radius: 24rpx;
  padding: 52rpx 30rpx;
  text-align: center;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
}
.fail-card.slim {
  padding: 40rpx 30rpx;
  margin-top: 24rpx;
}
.fail-card .f-t {
  font-size: 30rpx;
  font-weight: 700;
  color: #25292e;
}
.fail-card .f-d {
  font-size: 24rpx;
  color: #9aa2ad;
  line-height: 1.6;
}
.retry {
  margin-top: 10rpx;
  font-size: 26rpx;
  color: #12a150;
  font-weight: 600;
  border: 1rpx solid #12a150;
  border-radius: 999rpx;
  padding: 8rpx 32rpx;
}
/* 剩余订阅次数卡 */
.quota-card {
  background: linear-gradient(135deg, #22c55e, #0f8a45 72%);
  border-radius: 24rpx;
  padding: 34rpx 30rpx;
  color: #fff;
  box-shadow: 0 16rpx 34rpx rgba(18, 161, 80, 0.28);
  display: flex;
  flex-direction: column;
  gap: 10rpx;
}
.quota-card.warn {
  background: linear-gradient(135deg, #f0913a, #d9701c 72%);
  box-shadow: 0 16rpx 34rpx rgba(217, 112, 28, 0.28);
}
.q-main {
  display: flex;
  align-items: baseline;
  gap: 16rpx;
}
.q-k {
  font-size: 26rpx;
  opacity: 0.92;
}
.q-v {
  font-size: 48rpx;
  font-weight: 800;
  line-height: 1;
}
.q-tip {
  font-size: 24rpx;
  opacity: 0.92;
}
.q-btn {
  margin-top: 8rpx;
  align-self: flex-start;
  font-size: 26rpx;
  font-weight: 600;
  color: #12a150;
  background: #fff;
  border-radius: 999rpx;
  padding: 12rpx 40rpx;
}
.quota-card.warn .q-btn {
  color: #d9701c;
}
/* 区块标题与列表卡 */
.sect {
  font-size: 24rpx;
  color: #9aa2ad;
  padding: 30rpx 8rpx 12rpx;
}
.card {
  background: #fff;
  border-radius: 20rpx;
  overflow: hidden;
  box-shadow: 0 8rpx 22rpx rgba(20, 24, 28, 0.05);
}
.row {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 26rpx 28rpx;
  border-top: 1rpx solid #eef0f2;
}
.card .row:first-child {
  border-top: none;
}
.r-main {
  flex: 1;
  display: flex;
  align-items: baseline;
  gap: 18rpx;
  min-width: 0;
}
.r-time {
  font-size: 40rpx;
  font-weight: 800;
  color: #25292e;
}
.r-freq {
  font-size: 26rpx;
  color: #12a150;
  font-weight: 600;
}
/* 预算提醒行：标题 + 说明（列布局，与记账提醒行的时间+频率并列观感一致） */
.r-main.col {
  flex-direction: column;
  align-items: flex-start;
  gap: 6rpx;
}
.r-t2 {
  font-size: 30rpx;
  font-weight: 700;
  color: #25292e;
}
.r-d2 {
  font-size: 24rpx;
  color: #9aa2ad;
}
.r-switch {
  transform: scale(0.85);
  flex: 0 0 auto;
}
.r-del {
  font-size: 26rpx;
  color: #e5484d;
  flex: 0 0 auto;
}
.add-btn {
  margin-top: 24rpx;
  background: #12a150;
  color: #fff;
  border-radius: 18rpx;
  text-align: center;
  padding: 28rpx;
  font-size: 30rpx;
  font-weight: 700;
  box-shadow: 0 8rpx 22rpx rgba(18, 161, 80, 0.22);
}
/* 新增/编辑弹层 */
.mask {
  position: fixed;
  left: 0;
  right: 0;
  top: 0;
  bottom: 0;
  background: rgba(20, 24, 28, 0.45);
  display: flex;
  align-items: flex-end;
  z-index: 50;
}
.sheet {
  width: 100%;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 34rpx 30rpx calc(34rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.sheet-title {
  font-size: 32rpx;
  font-weight: 800;
  color: #25292e;
  display: block;
  text-align: center;
  margin-bottom: 24rpx;
}
.field-label {
  font-size: 24rpx;
  color: #9aa2ad;
  display: block;
  padding: 18rpx 4rpx 12rpx;
}
.field-label.inline {
  padding: 0;
}
.freq-row {
  display: flex;
  gap: 16rpx;
}
.freq-chip {
  flex: 1;
  text-align: center;
  padding: 20rpx 0;
  border-radius: 16rpx;
  background: #f4f5f7;
  color: #4b5563;
  font-size: 28rpx;
}
.freq-chip.on {
  background: #e7f7ee;
  color: #12a150;
  font-weight: 700;
  border: 1rpx solid #12a150;
}
.time-picker {
  background: #f4f5f7;
  border-radius: 16rpx;
  padding: 24rpx 28rpx;
  font-size: 32rpx;
  color: #25292e;
}
.time-picker .placeholder {
  color: #9aa2ad;
  font-size: 28rpx;
}
.switch-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 24rpx 4rpx 4rpx;
}
.form-error {
  display: block;
  color: #e5484d;
  font-size: 24rpx;
  padding: 16rpx 4rpx 0;
}
.sheet-actions {
  display: flex;
  gap: 20rpx;
  margin-top: 30rpx;
}
.btn {
  flex: 1;
  text-align: center;
  padding: 26rpx;
  border-radius: 16rpx;
  font-size: 30rpx;
  font-weight: 700;
}
.btn.ghost {
  background: #f4f5f7;
  color: #4b5563;
}
.btn.primary {
  background: #12a150;
  color: #fff;
}
.btn.primary.disabled {
  opacity: 0.6;
}
</style>
