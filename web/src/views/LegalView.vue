<script setup lang="ts">
/**
 * 协议文档页：承载《用户协议》与《隐私政策》。
 *
 * 公开路由 /legal/:doc（doc = agreement | privacy），登录/注册前均可查看。
 * 正文为 MVP 阶段示例条款，正式上线前应由法务审核后替换。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const doc = computed(() => (route.params.doc === 'privacy' ? 'privacy' : 'agreement'))
const updatedAt = '2026-07-26'

function goBack() {
  if (window.history.length > 1) router.back()
  else router.replace('/')
}
</script>

<template>
  <div class="legal">
    <header class="bar">
      <button class="back" type="button" aria-label="返回" @click="goBack">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M15 18l-6-6 6-6"/></svg>
      </button>
      <h1>{{ doc === 'privacy' ? '隐私政策' : '用户协议' }}</h1>
      <span class="spacer" aria-hidden="true"></span>
    </header>

    <article class="doc container">
      <p class="updated">最近更新：{{ updatedAt }}</p>

      <!-- 用户协议 -->
      <template v-if="doc === 'agreement'">
        <p>欢迎使用「有余」记账（以下简称"本应用"）。在使用前，请你仔细阅读本《用户协议》。当你登录或使用本应用，即表示你已阅读并同意本协议的全部内容。</p>
        <h2>一、服务内容</h2>
        <p>本应用为你提供个人记账服务，包括账户管理、收支与转账记录、分类管理、报表统计与数据导出等功能。记账、查看数据与导出数据为基础功能，永久免费，且不含任何第三方广告。</p>
        <h2>二、账号与安全</h2>
        <p>本应用通过邮箱验证码或微信授权登录，不设置登录密码。请妥善保管你的邮箱与微信账号，你应对在你账号下发生的所有操作负责。若发现账号被未经授权使用，请及时通过更换绑定邮箱或解绑微信等方式处理。</p>
        <h2>三、你的数据</h2>
        <p>你在本应用中录入的账目数据归你所有。你可随时通过导出功能获取自己的完整数据（CSV / JSON）。我们不会将你的账目数据用于与提供本服务无关的目的。</p>
        <h2>四、使用规范</h2>
        <p>你承诺不利用本应用从事违反法律法规或损害他人权益的活动，不对本应用进行反向工程、恶意攻击或干扰其正常运行。</p>
        <h2>五、免责声明</h2>
        <p>本应用按"现状"提供，用于个人记账参考，不构成任何投资、税务或财务建议。在法律允许的范围内，我们不对因使用或无法使用本服务造成的损失承担责任。</p>
        <h2>六、协议变更</h2>
        <p>我们可能适时更新本协议，更新后将在本页公示。若你在协议更新后继续使用本应用，即视为接受更新后的协议。</p>
      </template>

      <!-- 隐私政策 -->
      <template v-else>
        <p>「有余」记账（以下简称"本应用"）尊重并保护你的隐私。本《隐私政策》说明我们如何收集、使用与保护你的信息。</p>
        <h2>一、我们收集的信息</h2>
        <p>为提供记账服务，我们收集：你登录所用的邮箱地址，或微信授权获得的用户标识；你主动录入的账户、交易与分类等账目数据。我们不设置登录密码，也不收集与记账无关的个人敏感信息。</p>
        <h2>二、信息的使用</h2>
        <p>我们仅将上述信息用于向你提供记账、统计与导出等本应用功能，以及通过邮箱验证码保障登录安全。我们不会向第三方出售你的个人信息。</p>
        <h2>三、数据存储与安全</h2>
        <p>登录验证码仅在有效期内临时保存并在使用后失效，我们不存储你的登录密码。你的账目数据按用户严格隔离，其他用户无法访问。我们采取合理的技术措施保护数据安全。</p>
        <h2>四、数据可携带与删除</h2>
        <p>你可随时导出自己的全部数据。你也有权要求删除你的账号及相关数据。</p>
        <h2>五、无广告与无第三方追踪</h2>
        <p>本应用不含第三方广告 SDK，不进行用于广告目的的第三方追踪。</p>
        <h2>六、政策变更</h2>
        <p>我们可能适时更新本政策，更新后将在本页公示。</p>
      </template>

      <p class="disclaimer">说明：以上为产品早期阶段的示例条款，最终版本以正式发布时经法律审核后的文本为准。</p>
    </article>
  </div>
</template>

<style scoped>
.legal {
  min-height: 100vh;
  background: var(--color-bg);
}
.bar {
  position: sticky;
  top: 0;
  z-index: 10;
  display: flex;
  align-items: center;
  gap: 8px;
  height: 52px;
  padding: calc(env(safe-area-inset-top)) 12px 0;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
}
.bar h1 { flex: 1; text-align: center; margin: 0; font-size: 17px; font-weight: 700; }
.back, .spacer { width: 40px; height: 40px; }
.back {
  display: flex; align-items: center; justify-content: center;
  border: none; background: none; color: var(--color-text); border-radius: 10px;
}
.back svg { width: 22px; height: 22px; }
.back:active { background: var(--color-bg); }

.doc { padding-block: 20px 40px; max-width: 720px; }
.doc .updated { color: var(--color-muted); font-size: 13px; margin: 0 0 16px; }
.doc h2 { font-size: 16px; margin: 22px 0 8px; }
.doc p { margin: 0 0 12px; font-size: 15px; line-height: 1.75; color: #374151; }
.doc .disclaimer {
  margin-top: 28px; padding: 12px 14px; border-radius: var(--radius);
  background: #f1f5f9; color: var(--color-muted); font-size: 13px; line-height: 1.7;
}
</style>
