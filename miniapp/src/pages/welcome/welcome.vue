<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import { useAuthStore } from '../../stores/auth'
import { useThemeStore } from '../../stores/theme'
import { STORAGE_KEYS } from '../../utils/config'

/**
 * 首次进入欢迎页（pages[0]，冷启动入口）。极简风：单色线性图标 + 大留白。
 * 作用：品牌第一印象 + 协议同意（合规）。必须在任何登录（含小程序静默登录）之前完成同意，
 * 因此欢迎页作为入口先行拦截；已同意过则直接转发到登录页，由登录页处理静默登录与路由。
 * 注意：不做竞品「开通读取支付账单」那种授权——我们不自动读取第三方支付账单（账单靠手动导入）。
 */
const auth = useAuthStore()
const themeStore = useThemeStore()
const statusBarHeight = (uni.getSystemInfoSync().statusBarHeight || 0) + 'px'

const ready = ref(false)
const agreed = ref(false)
const wxLoading = ref(false)

const FEATS = [
  { icon: 'list', title: '极速记账', desc: '3 秒一笔，计算器键盘 + 猜你要记' },
  { icon: 'members', title: '多账本 · 协作', desc: '家庭 / 项目分账，成员一起记' },
  { icon: 'chart', title: '报表 · 预算 · 成长', desc: '看清钱去哪，记账还能攒成就' }
]

onLoad(() => {
  if (uni.getStorageSync(STORAGE_KEYS.welcomed)) {
    uni.reLaunch({ url: '/pages/login/login' })
    return
  }
  ready.value = true
})

function markWelcomed() {
  uni.setStorageSync(STORAGE_KEYS.welcomed, '1')
}
function openLegal(type) {
  uni.navigateTo({ url: `/pages/legal/legal?type=${type}` })
}
function needAgree() {
  uni.showToast({ title: '请先阅读并同意协议', icon: 'none' })
}
async function onWxLogin() {
  if (!agreed.value) return needAgree()
  if (wxLoading.value) return
  wxLoading.value = true
  markWelcomed()
  try {
    await auth.loginWithWeixin()
    uni.reLaunch({ url: '/pages/login/login' })
  } catch (e) {
    uni.reLaunch({ url: '/pages/login/login' })
  } finally {
    wxLoading.value = false
  }
}
function onMoreLogin() {
  if (!agreed.value) return needAgree()
  markWelcomed()
  uni.reLaunch({ url: '/pages/login/login' })
}
</script>

<template>
  <view v-if="ready" class="page" :style="themeStore.current.vars">
    <view class="welcome" :style="{ paddingTop: `calc(${statusBarHeight} + 96rpx)` }">
      <!-- 品牌（极简） -->
      <view class="brand">
        <view class="logo"><AppIcon name="yuan" :size="52" color="#ffffff" /></view>
        <text class="app">有余</text>
        <text class="slogan">记好每一笔，日子更有余</text>
      </view>

      <!-- 卖点：单色线性图标，无彩色底 -->
      <view class="feats">
        <view v-for="f in FEATS" :key="f.icon" class="feat">
          <view class="fi"><AppIcon :name="f.icon" :size="46" color="#3a3f45" /></view>
          <view class="fmain">
            <text class="ft">{{ f.title }}</text>
            <text class="fs">{{ f.desc }}</text>
          </view>
        </view>
      </view>

      <!-- 协议 + 登录 -->
      <view class="foot">
        <view class="agree">
          <view class="box" :class="{ on: agreed }" @click="agreed = !agreed"></view>
          <text class="agree-txt">
            已阅读并同意
            <text class="link" @click="openLegal('user')">《用户协议》</text>
            和
            <text class="link" @click="openLegal('privacy')">《隐私政策》</text>
          </text>
        </view>
        <view class="cta" :class="{ disabled: !agreed, busy: wxLoading }" @click="onWxLogin">微信一键登录</view>
        <text class="alt" @click="onMoreLogin">其他方式登录</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; background: #fff; }
.welcome { min-height: 100vh; display: flex; flex-direction: column; padding: 0 56rpx 56rpx; box-sizing: border-box; }

/* 品牌 */
.brand { display: flex; flex-direction: column; align-items: center; }
.logo {
  width: 116rpx; height: 116rpx; border-radius: 32rpx;
  background: var(--c-brand, #12a150);
  display: flex; align-items: center; justify-content: center;
}
.app { font-size: 46rpx; font-weight: 800; color: #16181c; margin-top: 28rpx; letter-spacing: 4rpx; }
.slogan { font-size: 26rpx; color: #9aa2ad; margin-top: 12rpx; }

/* 卖点 */
.feats { margin-top: 96rpx; display: flex; flex-direction: column; gap: 44rpx; }
.feat { display: flex; align-items: center; gap: 28rpx; }
.fi { width: 52rpx; height: 52rpx; flex: 0 0 auto; display: flex; align-items: center; justify-content: center; }
.fmain { flex: 1; display: flex; flex-direction: column; gap: 6rpx; }
.ft { font-size: 31rpx; font-weight: 700; color: #16181c; }
.fs { font-size: 24rpx; color: #9aa2ad; }

/* 协议 + 登录 */
.foot { margin-top: auto; padding-top: 48rpx; }
.agree { display: flex; align-items: flex-start; justify-content: center; gap: 12rpx; margin-bottom: 28rpx; }
.box {
  width: 32rpx; height: 32rpx; border-radius: 50%; border: 2rpx solid #cfd5db;
  flex: 0 0 auto; margin-top: 2rpx; position: relative; box-sizing: border-box;
}
.box.on { background: var(--c-brand, #12a150); border-color: var(--c-brand, #12a150); }
.box.on::after {
  content: '✓'; color: #fff; font-size: 20rpx;
  position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
}
.agree-txt { font-size: 24rpx; color: #9aa2ad; line-height: 1.5; }
.link { color: var(--c-brand-ink, #0e8a44); }
.cta {
  height: 96rpx; border-radius: 24rpx;
  background: var(--c-brand, #12a150); color: #fff; font-size: 32rpx; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
}
.cta.disabled { background: #eceef1; color: #b6bcc4; }
.cta.busy { opacity: 0.7; }
.alt { display: block; text-align: center; font-size: 26rpx; color: #9aa2ad; margin-top: 28rpx; }
</style>
