<script setup>
/**
 * 解锁弹层：播报本次待播报列表的第 1 项（需求 7.4、7.5、7.7、7.8、7.13、9.13）。
 *
 * 沿用既有 Sheet 组件（InputSheet / AccountTypeSheet）的同一套模式：
 * `v-model:visible` + 遮罩点击关闭 + 内容 `@click.stop` + `z-index: 600`
 * （高于底部 TabBar 的 500，否则弹层底部的两个入口会被导航栏压住）。
 *
 * 本组件只负责展示与手势，**不发任何请求、不做任何播报编排**：
 * Toast 队列、游标推进与页面跳转都由宿主页按 utils/achievement.js 的纯函数决策后执行，
 * 组件只向外抛四个事件（`update:visible` / `enter` / `share` / `save`）。
 *
 * `share` 与 `save` 成对存在：需求 8.1 后半句要求弹层为**正在播报的那一枚**成就同时提供
 * 「分享给好友」与「保存成就卡片到相册」，且不提供其它成就的这两个操作——本组件一次只持有
 * 一枚 `achievement`，这条约束因此是结构性的。分享入口用 `open-type="share"` 的 button
 * （小程序里只有它能唤起转发面板，普通 view 拿不到转发能力，写法与成就页 `.acts` 一致），
 * 并带上 `data-code` 供宿主页的 `onShareAppMessage` 从 `res.target.dataset` 认出目标；
 * 保存入口只抛 `save` 事件，绘制与写相册归宿主页（canvas 节点在页面上，不在组件里）。
 *
 * 两条刻意的取舍：
 *
 * 1. **不自动关闭**（需求 7.4）。保持展示直到用户点关闭、点遮罩或进入成就页。
 *    自动消失的弹层会让用户来不及点分享——弹层里的分享是需求 8.1 给「刚解锁的这一枚」
 *    准备的唯一入口，几秒后自己消失就等于这个入口时常不可用。
 *
 * 2. **动画只用 `transform: translateY() scale()` 与 `opacity`**（需求 7.7）。
 *    小程序里只有这两类属性走合成线程，用 `height` / `top` / `margin` 做动画会触发
 *    逐帧重排，在中低端机上明显掉帧。入场时长 900ms 取自 600–1500ms 闭区间的中间位置；
 *    收起复用 utils/achievement.js 的 MODAL_EXIT_MS(300ms) 上界（需求 7.8、7.16）。
 *
 * 颜色只用既有品牌绿 `#12a150` 与浅绿底 `#e7f7ee`，图标复用既有 AppIcon，
 * 不新增第二套颜色体系（需求 9.13）。
 */
import { ref, computed, watch } from 'vue'
import { MODAL_EXIT_MS, unlockedDateLabel } from '../../utils/achievement'

/**
 * 入场动画时长：需求 7.7 要求落在 [600, 1500] 毫秒闭区间内。
 * 只此一处使用，故不进 utils/achievement.js（那里只放被多方复用或需属性测试锁住的常量）。
 */
const MODAL_ENTER_MS = 900

const props = defineProps({
  visible: { type: Boolean, default: false },
  // 待播报的那一枚成就（PendingAchievementItem：code / name / description / category /
  // unlockedAt / eventId）。字段缺失时各处降级为空文案，绝不让弹层报错或白屏。
  achievement: { type: Object, default: null }
})
const emit = defineEmits(['update:visible', 'enter', 'share', 'save'])

// 收起中：此期间仍然渲染，好让收起动画跑完再从 DOM 摘掉（需求 7.8 的「结束动画并关闭」）。
const leaving = ref(false)
let leaveTimer = null

// 再次打开时清掉上一次未跑完的收起过程，避免弹层带着 leaving 类出场（动画倒放）。
watch(
  () => props.visible,
  (v) => {
    if (v) {
      if (leaveTimer) {
        clearTimeout(leaveTimer)
        leaveTimer = null
      }
      leaving.value = false
    }
  }
)

const name = computed(() => String(props.achievement?.name || '新成就'))
const description = computed(() => String(props.achievement?.description || ''))
// 成就编码只作 data-code 传给转发回调，不作文本渲染（需求 9.6）。
const code = computed(() => String(props.achievement?.code || ''))
// 精确到自然日的解锁日期（需求 7.4）；空值 / 畸形取值得到 '' 并整行不渲染。
const dateLabel = computed(() => unlockedDateLabel(props.achievement))

// 入场 / 收起共用一个 duration 绑定，使时长只有 JS 常量一个来源（样式里不再写死毫秒数）。
const animStyle = computed(() => ({
  animationDuration: (leaving.value ? MODAL_EXIT_MS : MODAL_ENTER_MS) + 'ms'
}))

/**
 * 收起：先跑收起动画，MODAL_EXIT_MS(300ms) 后再通知宿主页把 visible 置 false，
 * 并在同一时刻抛出 done（宿主页据此推进 Toast 队列或跳转成就页）。
 */
function leave(done) {
  if (leaving.value) return
  leaving.value = true
  leaveTimer = setTimeout(() => {
    leaveTimer = null
    leaving.value = false
    emit('update:visible', false)
    if (done) done()
  }, MODAL_EXIT_MS)
}

/** 关闭操作与遮罩点击（需求 7.8）。 */
function close() {
  leave(null)
}

/** 进入成就页（需求 7.16）：300ms 内收起并交由宿主页 navigateTo + 推进游标。 */
function enterPage() {
  leave(() => emit('enter'))
}

/** 分享（需求 7.13、8.1）：弹层保持继续展示，只把事件抛给宿主页。 */
function share() {
  emit('share', props.achievement || null)
}

/**
 * 保存成就卡片到相册（需求 8.1 后半句）：同样保持弹层继续展示——
 * 绘制、授权与写相册的三条分支都在宿主页（canvas 节点在页面上），
 * 保存过程中弹层若自己消失，用户就看不到「已保存到相册 / 需要相册权限」的结果了。
 */
function save() {
  emit('save', props.achievement || null)
}
</script>

<template>
  <view v-if="visible" class="au-mask" :class="{ leaving }" :style="animStyle" @click="close">
    <view class="au-card" :class="{ leaving }" :style="animStyle" @click.stop>
      <view class="au-ic"><AppIcon name="badge" :size="72" color="#12a150" /></view>
      <text class="au-eyebrow">成就解锁</text>
      <text class="au-name">{{ name }}</text>
      <text v-if="description" class="au-desc">{{ description }}</text>
      <text v-if="dateLabel" class="au-date">{{ dateLabel }} 解锁</text>

      <view class="au-actions">
        <view class="au-btn au-btn-primary" @click="enterPage">进入成就页</view>
      </view>
      <!--
        正在播报的这一枚成就的分享与保存（需求 8.1 后半句）。
        分享必须是 open-type="share" 的 button：普通 view 拿不到转发能力；
        data-code 供宿主页 onShareAppMessage 从 res.target.dataset 认出是哪一枚。
      -->
      <view class="au-actions au-actions-sub">
        <button class="au-btn au-btn-ghost" open-type="share" :data-code="code" @click="share">
          分享给好友
        </button>
        <view class="au-btn au-btn-ghost" @click="save">保存卡片</view>
      </view>
      <text class="au-close" @click="close">关闭</text>
    </view>
  </view>
</template>

<style scoped>
.au-mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.42);
  display: flex;
  align-items: center;
  justify-content: center;
  /* 高于底部 TabBar（z-index:500），避免弹层底部的两个入口被导航栏遮挡 */
  z-index: 600;
  /* 只做不透明度过渡：遮罩不参与位移与缩放，省一层合成开销 */
  animation-name: au-fade-in;
  animation-timing-function: ease-out;
  animation-fill-mode: both;
}
.au-mask.leaving {
  animation-name: au-fade-out;
}
.au-card {
  width: 560rpx;
  background: #fff;
  border-radius: 32rpx;
  padding: 48rpx 40rpx 32rpx;
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: center;
  /* 入场 / 收起一律只动 transform 与 opacity（需求 7.7），不动 height / top / margin */
  animation-name: au-pop-in;
  animation-timing-function: cubic-bezier(0.22, 0.61, 0.36, 1);
  animation-fill-mode: both;
}
.au-card.leaving {
  animation-name: au-pop-out;
  animation-timing-function: ease-in;
}
@keyframes au-fade-in {
  from { opacity: 0; }
  to { opacity: 1; }
}
@keyframes au-fade-out {
  from { opacity: 1; }
  to { opacity: 0; }
}
@keyframes au-pop-in {
  from { transform: translateY(80rpx) scale(0.86); opacity: 0; }
  60% { transform: translateY(0) scale(1.04); opacity: 1; }
  to { transform: translateY(0) scale(1); opacity: 1; }
}
@keyframes au-pop-out {
  from { transform: translateY(0) scale(1); opacity: 1; }
  to { transform: translateY(40rpx) scale(0.92); opacity: 0; }
}
.au-ic {
  width: 132rpx;
  height: 132rpx;
  border-radius: 50%;
  background: #e7f7ee;
  display: flex;
  align-items: center;
  justify-content: center;
}
.au-eyebrow {
  margin-top: 24rpx;
  font-size: 22rpx;
  font-weight: 700;
  color: #12a150;
  letter-spacing: 4rpx;
}
.au-name {
  margin-top: 12rpx;
  font-size: 38rpx;
  font-weight: 800;
  color: #16181c;
  text-align: center;
}
.au-desc {
  margin-top: 14rpx;
  font-size: 26rpx;
  color: #5b6470;
  line-height: 1.6;
  text-align: center;
}
.au-date {
  margin-top: 14rpx;
  font-size: 22rpx;
  color: #9aa2ad;
}
.au-actions {
  margin-top: 36rpx;
  width: 100%;
  display: flex;
  align-items: center;
  gap: 20rpx;
}
/* 分享 / 保存这一行贴着主按钮，间距比主按钮与文案之间小一档 */
.au-actions-sub {
  margin-top: 16rpx;
}
.au-btn {
  flex: 1;
  height: 84rpx;
  border-radius: 44rpx;
  font-size: 28rpx;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  /* button 与 view 共用这一套胶囊样式，故把 button 的默认样式抹平（写法同成就页 .act） */
  margin: 0;
  padding: 0;
  line-height: 1;
  border: none;
}
.au-btn::after {
  border: none;
}
.au-btn-primary {
  background: #12a150;
  color: #fff;
}
.au-btn-ghost {
  background: #e7f7ee;
  color: #12a150;
}
.au-close {
  margin-top: 20rpx;
  padding: 12rpx 40rpx;
  font-size: 26rpx;
  color: #9aa2ad;
}
</style>
