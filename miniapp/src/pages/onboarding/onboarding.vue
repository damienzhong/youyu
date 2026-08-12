<script setup>
import { ref, computed } from 'vue'
import { useLedgerStore } from '../../stores/ledger'
import { createLedger } from '../../api/ledger'
import { createAccount } from '../../api/account'
import { seedDefaultCategories } from '../../api/category'

const ONBOARDED_KEY = 'youyu_onboarded'
const ledgerStore = useLedgerStore()

// 自定义导航页：顶部让出状态栏 + 胶囊高度，避免「跳过」被微信胶囊遮挡。
const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'

const step = ref(1)
const busy = ref(false)

// 第 1 步：场景（隐式决定账本）
const SCENARIOS = [
  { key: 'personal', emoji: '🙋', title: '个人记账', desc: '只有自己 · 用默认账本' },
  { key: 'family', emoji: '🏠', title: '家庭 / 情侣协作', desc: '可邀请他人共同记账' },
  { key: 'travel', emoji: '✈️', title: '旅行 / AA', desc: '和朋友一起，按人分摊' }
]
const scenario = ref('personal')

// 第 2 步：常用账户（多选）
const WALLETS = [
  { key: 'CASH', name: '现金', emoji: '💵', tint: '#e6f6ec' },
  { key: 'ALIPAY', name: '支付宝', emoji: '🅰️', tint: '#e8f0fe' },
  { key: 'WECHAT', name: '微信', emoji: '💬', tint: '#e6f9ee' },
  { key: 'BANK_CARD', name: '储蓄卡', emoji: '🏦', tint: '#eef4ff' },
  { key: 'CREDIT_CARD', name: '信用卡', emoji: '💳', tint: '#fdf3e2' }
]
const picked = ref({ CASH: true, ALIPAY: true })
function toggle(k) {
  picked.value[k] = !picked.value[k]
}
const pickedList = computed(() => WALLETS.filter((w) => picked.value[w.key]))

const scenarioTitle = computed(() => SCENARIOS.find((s) => s.key === scenario.value)?.title || '')
const accountSummary = computed(() => pickedList.value.map((w) => w.name).join(' · ') || '（未选）')

function next() {
  step.value = 2
}

// 第 2 步 → 供给：建账本(协作) / 定位默认账本 → 补默认分类 → 批量建账户
async function provision() {
  if (busy.value) return
  busy.value = true
  try {
    if (scenario.value === 'personal') {
      await ledgerStore.load()
      const def = ledgerStore.ledgers.find((l) => l.isDefault) || ledgerStore.ledgers[0]
      if (def) ledgerStore.setCurrent(def.id)
    } else {
      const name = scenario.value === 'family' ? '家庭账本' : '旅行账本'
      const l = await createLedger(name, 'COLLABORATIVE')
      await ledgerStore.load()
      ledgerStore.setCurrent(l.id)
    }
    // 默认分类（个人默认账本原为空；协作账本已自带，幂等无副作用）
    await seedDefaultCategories()
    // 批量创建选中的账户（落到当前账本作用域）
    for (const w of pickedList.value) {
      await createAccount({ name: w.name, type: w.key, initialBalance: '0' })
    }
    uni.setStorageSync(ONBOARDED_KEY, 1)
    step.value = 3
  } catch (e) {
    uni.showToast({ title: e.message || '初始化失败，请重试', icon: 'none' })
  } finally {
    busy.value = false
  }
}

function finish() {
  uni.setStorageSync(ONBOARDED_KEY, 1)
  uni.reLaunch({ url: '/pages/index/index' })
}
function skip() {
  uni.setStorageSync(ONBOARDED_KEY, 1)
  uni.reLaunch({ url: '/pages/index/index' })
}
</script>

<template>
  <view class="ob" :style="{ paddingTop: `calc(${statusBarHeight} + 88rpx)` }">
    <!-- 进度 -->
    <view class="top">
      <text class="step-k">第 {{ step }} / 3 步 · {{ step === 1 ? '选择记账场景' : step === 2 ? '选常用账户' : '完成' }}</text>
      <text v-if="step < 3" class="skip" @click="skip">跳过</text>
    </view>
    <view class="pbar"><view class="pfill" :style="{ width: step * 33.34 + '%' }"></view></view>

    <!-- 第 1 步 场景 -->
    <block v-if="step === 1">
      <view class="hd">
        <text class="hd-emoji">🧭</text>
        <text class="hd-title">你想怎么记？</text>
        <text class="hd-sub">随时可以再改，也能建更多账本</text>
      </view>
      <view class="card list">
        <view
          v-for="(s, i) in SCENARIOS"
          :key="s.key"
          class="opt"
          :class="{ first: i === 0, on: scenario === s.key }"
          @click="scenario = s.key"
        >
          <text class="opt-ic">{{ s.emoji }}</text>
          <view class="opt-main">
            <text class="opt-title">{{ s.title }}</text>
            <text class="opt-desc">{{ s.desc }}</text>
          </view>
          <text class="radio" :class="{ on: scenario === s.key }"></text>
        </view>
      </view>
      <view class="btn" @click="next">下一步 · 选常用账户</view>
    </block>

    <!-- 第 2 步 账户 -->
    <block v-else-if="step === 2">
      <view class="hd">
        <text class="hd-emoji">👛</text>
        <text class="hd-title">你常用哪些钱包？</text>
        <text class="hd-sub">选中的会自动建成账户，稍后可填余额</text>
      </view>
      <view class="wallets">
        <view
          v-for="w in WALLETS"
          :key="w.key"
          class="wallet"
          :class="{ on: picked[w.key] }"
          @click="toggle(w.key)"
        >
          <text class="w-ic" :style="{ background: w.tint }">{{ w.emoji }}</text>
          <text class="w-name">{{ w.name }}</text>
          <text class="w-mark">{{ picked[w.key] ? '✓' : '＋' }}</text>
        </view>
      </view>
      <view class="btn" :class="{ disabled: !pickedList.length }" @click="provision">
        {{ busy ? '正在准备…' : '下一步 · 记第一笔' }}
      </view>
      <text class="tiny-hint">至少选一个账户；也可以先跳过稍后再建</text>
    </block>

    <!-- 第 3 步 完成 -->
    <block v-else>
      <view class="hd">
        <text class="hd-emoji">🎉</text>
        <text class="hd-title">一切就绪，开始记账吧</text>
        <text class="hd-sub">已为你准备好账本与常用账户</text>
      </view>
      <view class="card list">
        <view class="opt first"><text class="opt-ic">🙋</text><view class="opt-main"><text class="opt-title">记账场景</text></view><text class="done">{{ scenarioTitle }} ✓</text></view>
        <view class="opt"><text class="opt-ic">👛</text><view class="opt-main"><text class="opt-title">账户</text></view><text class="done">{{ accountSummary }} ✓</text></view>
        <view class="opt"><text class="opt-ic">🏷️</text><view class="opt-main"><text class="opt-title">分类</text></view><text class="done">已预置 ✓</text></view>
      </view>
      <view class="btn" @click="finish">开始记账</view>
    </block>
  </view>
</template>

<style scoped>
.ob {
  min-height: 100vh;
  background: #eef0f2;
  padding: 28rpx 32rpx calc(40rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.top {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.step-k {
  font-size: 24rpx;
  color: #9aa2ad;
}
.skip {
  font-size: 24rpx;
  color: #9aa2ad;
}
.pbar {
  height: 10rpx;
  background: #e3e6ea;
  border-radius: 6rpx;
  overflow: hidden;
  margin-top: 14rpx;
}
.pfill {
  height: 100%;
  background: #12a150;
  border-radius: 6rpx;
  transition: width 0.25s;
}
.hd {
  text-align: center;
  margin: 56rpx 0 32rpx;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
}
.hd-emoji {
  font-size: 96rpx;
}
.hd-title {
  font-size: 44rpx;
  font-weight: 800;
  color: #16181c;
}
.hd-sub {
  font-size: 26rpx;
  color: #6b7280;
}
.card.list {
  background: #fff;
  border-radius: 24rpx;
  padding: 0 28rpx;
  box-shadow: 0 8rpx 26rpx rgba(20, 24, 28, 0.06);
}
.opt {
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 30rpx 0;
  border-top: 1rpx solid #eceef1;
}
.opt.first {
  border-top: none;
}
.opt-ic {
  width: 76rpx;
  height: 76rpx;
  border-radius: 22rpx;
  background: #f6f7f9;
  text-align: center;
  line-height: 76rpx;
  font-size: 38rpx;
  flex: 0 0 auto;
}
.opt.on .opt-ic {
  background: #e6f6ec;
}
.opt-main {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.opt-title {
  font-size: 32rpx;
  font-weight: 700;
  color: #16181c;
}
.opt-desc {
  font-size: 24rpx;
  color: #9aa2ad;
}
.done {
  font-size: 24rpx;
  color: #12a150;
  font-weight: 600;
}
.radio {
  width: 36rpx;
  height: 36rpx;
  border-radius: 50%;
  border: 3rpx solid #d1d5db;
  box-sizing: border-box;
  flex: 0 0 auto;
}
.radio.on {
  border-color: #12a150;
  background: radial-gradient(circle at center, #12a150 0, #12a150 9rpx, #fff 10rpx, #fff 100%);
}
.wallets {
  display: flex;
  flex-wrap: wrap;
  gap: 20rpx;
}
.wallet {
  width: calc(50% - 10rpx);
  box-sizing: border-box;
  background: #fff;
  border-radius: 20rpx;
  padding: 26rpx;
  display: flex;
  align-items: center;
  gap: 16rpx;
  box-shadow: 0 8rpx 26rpx rgba(20, 24, 28, 0.05);
}
.wallet.on {
  box-shadow: 0 0 0 3rpx #12a150;
}
.w-ic {
  width: 64rpx;
  height: 64rpx;
  border-radius: 18rpx;
  text-align: center;
  line-height: 64rpx;
  font-size: 32rpx;
}
.w-name {
  flex: 1;
  font-size: 30rpx;
  font-weight: 700;
  color: #16181c;
}
.w-mark {
  font-size: 30rpx;
  color: #12a150;
  font-weight: 800;
}
.wallet:not(.on) .w-mark {
  color: #c0c4cc;
}
.btn {
  margin-top: 40rpx;
  height: 96rpx;
  border-radius: 999rpx;
  background: #12a150;
  color: #fff;
  font-size: 32rpx;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 16rpx 34rpx rgba(18, 161, 80, 0.24);
}
.btn.disabled {
  opacity: 0.5;
}
.tiny-hint {
  display: block;
  text-align: center;
  font-size: 22rpx;
  color: #9aa2ad;
  margin-top: 18rpx;
}
</style>
