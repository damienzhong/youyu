<script setup>
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'

/**
 * 协议文档页：用 ?type=user|privacy 区分《用户协议》/《隐私政策》。
 * 文案为占位框架，正式上线前需由业务/法务补齐并复核。
 */
const type = ref('user')
const title = ref('用户协议')

onLoad((q) => {
  type.value = q && q.type === 'privacy' ? 'privacy' : 'user'
  title.value = type.value === 'privacy' ? '隐私政策' : '用户协议'
  uni.setNavigationBarTitle({ title: title.value })
})
</script>

<template>
  <view class="page">
    <text class="h">{{ title }}</text>
    <text class="upd">更新日期：2026-08-09 · 生效日期：2026-08-09</text>

    <template v-if="type === 'user'">
      <text class="p">欢迎使用「有余」记账（以下简称“本服务”）。在使用本服务前，请你仔细阅读并充分理解本协议的全部条款。当你勾选同意或开始使用本服务，即视为你已接受本协议。</text>
      <text class="s">一、服务内容</text>
      <text class="p">本服务为你提供记账、账本管理、账单导入、报表统计、预算与记账激励等功能。本服务不自动读取你的第三方支付账单，账单数据由你自行手动导入。</text>
      <text class="s">二、账号与登录</text>
      <text class="p">你可通过微信授权或邮箱验证码登录。你应妥善保管账号，因你自身原因导致的账号或数据泄露由你自行承担相应责任。</text>
      <text class="s">三、用户行为规范</text>
      <text class="p">你承诺不利用本服务从事违法违规活动，不上传违法或侵犯他人权益的内容。</text>
      <text class="s">四、数据与免责</text>
      <text class="p">我们会尽力保障服务稳定与数据安全，但因不可抗力、网络故障等非我方原因造成的数据丢失或服务中断，我们不承担责任。建议你定期通过导出功能备份数据。</text>
      <text class="s">五、协议变更</text>
      <text class="p">我们可能适时更新本协议，更新后将在应用内提示。若你继续使用即视为接受更新后的协议。</text>
    </template>

    <template v-else>
      <text class="p">「有余」记账（以下简称“我们”）非常重视你的个人信息与隐私保护。本政策说明我们如何收集、使用与保护你的信息。</text>
      <text class="s">一、我们收集的信息</text>
      <text class="p">1. 账号信息：微信登录时的用户标识、或你填写的邮箱，用于创建与识别账号。</text>
      <text class="p">2. 记账数据：你主动录入或导入的收支、账户、分类、备注等信息，用于向你提供记账与统计功能。</text>
      <text class="p">3. 设备与日志：为保障服务与排障，可能记录必要的设备与运行日志。</text>
      <text class="s">二、信息的使用</text>
      <text class="p">你的记账数据仅用于向你本人（及你主动邀请的协作成员）提供功能展示与统计，我们不会将其用于与服务无关的用途。</text>
      <text class="s">三、信息的存储与保护</text>
      <text class="p">我们采取合理的技术与管理措施保护你的信息安全。数据存储于服务提供方的服务器，保存期限为提供服务所必需的期间。</text>
      <text class="s">四、第三方与共享</text>
      <text class="p">除法律法规要求或经你明确同意外，我们不会向第三方共享你的个人信息。</text>
      <text class="s">五、你的权利</text>
      <text class="p">你可随时查看、导出或删除你的记账数据，也可注销账号。注销后我们将按法律要求删除或匿名化你的个人信息。</text>
      <text class="s">六、联系我们</text>
      <text class="p">如对本政策有任何疑问，可通过应用内「我的-关于」提供的方式与我们联系。</text>
    </template>

    <text class="foot">以上为占位框架条款，正式条款以最终发布版本为准。</text>
    <view style="height: 40rpx;"></view>
  </view>
</template>

<style scoped>
.page { min-height: 100vh; background: #fff; padding: 32rpx 32rpx 0; }
.h { display: block; font-size: 40rpx; font-weight: 800; color: #16181c; }
.upd { display: block; font-size: 22rpx; color: #9aa2ad; margin: 12rpx 0 24rpx; }
.s { display: block; font-size: 30rpx; font-weight: 700; color: #16181c; margin: 26rpx 0 10rpx; }
.p { display: block; font-size: 27rpx; color: #5b6470; line-height: 1.75; margin-bottom: 10rpx; }
.foot { display: block; font-size: 22rpx; color: #9aa2ad; margin-top: 30rpx; line-height: 1.6; }
</style>
