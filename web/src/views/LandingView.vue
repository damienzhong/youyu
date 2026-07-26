<script setup lang="ts">
/**
 * 落地页（公开）：面向未登录访客的产品介绍与转化入口。
 *
 * 路由 /welcome（公开）。未登录访问受保护路由时由守卫重定向至此；
 * 已登录访问则被守卫送回 App 首页。CTA 指向注册/登录。
 */
import { RouterLink } from 'vue-router'
</script>

<template>
  <div class="landing">
    <!-- 导航 -->
    <header class="nav">
      <div class="wrap nav-inner">
        <div class="logo"><span class="mark">¥</span>有余</div>
        <nav class="nav-links">
          <a href="#pillars">为什么选有余</a>
          <a href="#features">功能特色</a>
          <a href="#start">如何开始</a>
        </nav>
        <div class="nav-actions">
          <RouterLink class="btn btn-ghost" to="/login">登录</RouterLink>
          <RouterLink class="btn btn-primary" to="/register">免费注册</RouterLink>
        </div>
      </div>
    </header>

    <!-- Hero -->
    <div class="hero">
      <div class="wrap hero-inner">
        <div class="hero-copy">
          <span class="eyebrow"><span class="dot"></span>无广告 · 永久免费 · 数据可导出</span>
          <h1 class="headline">干净记账，<br /><span class="hl">日子有余</span>。</h1>
          <p class="sub">没有广告，没有付费墙。三秒记好一笔，手机电脑一套账，数据随时带走——一个真正干净的记账本。</p>
          <div class="cta-row">
            <RouterLink class="btn btn-primary btn-lg" to="/register">免费开始记账</RouterLink>
            <RouterLink class="btn btn-ghost btn-lg" to="/login">已有账号，登录 →</RouterLink>
          </div>
          <p class="trust"><b>记好每一笔，日子有余</b> · 基础功能永久免费</p>
        </div>
        <div class="device-wrap">
          <div class="device"><div class="screen">
            <div class="d-hero">
              <div class="dlabel">本月结余</div>
              <div class="dbal num-font">¥12,326.70</div>
              <div class="drow">
                <span>收入<b class="num-font">¥12,500</b></span>
                <span>支出<b class="num-font">¥173.30</b></span>
                <span>净资产<b class="num-font">¥13,126</b></span>
              </div>
            </div>
            <div class="d-card">
              <div class="d-item"><div class="d-ico exp-bg">🍜</div><div class="d-t">餐饮 · 聚餐<small>工资卡 · 19:00</small></div><div class="d-amt exp">-128.50</div></div>
              <div class="d-item"><div class="d-ico exp-bg">🛵</div><div class="d-t">餐饮 · 外卖<small>支付宝 · 12:30</small></div><div class="d-amt exp">-38.80</div></div>
              <div class="d-item"><div class="d-ico blue-bg">🚇</div><div class="d-t">交通<small>支付宝 · 08:10</small></div><div class="d-amt exp">-6.00</div></div>
              <div class="d-item"><div class="d-ico inc-bg">💰</div><div class="d-t">工资<small>工资卡 · 09:00</small></div><div class="d-amt inc">+12,500</div></div>
              <div class="d-fab">＋</div>
            </div>
          </div></div>
        </div>
      </div>
    </div>

    <!-- 三大卖点 -->
    <section id="pillars" class="wrap">
      <div class="section-head">
        <h2 class="section-title">干净，是我们的底线</h2>
        <p class="section-desc">别的记账软件塞满广告和付费墙，我们把它们全部拿掉。</p>
      </div>
      <div class="pillars">
        <div class="pillar">
          <div class="pico"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="9"/><path d="M5 5l14 14"/></svg></div>
          <h3>零广告</h3>
          <p>启动页、记账页、报表页，全程没有横幅、插屏、开屏和信息流广告。安安静静，只做记账。</p>
        </div>
        <div class="pillar">
          <div class="pico"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 12v9H4v-9"/><rect x="2" y="7" width="20" height="5" rx="1"/><path d="M12 22V7"/><path d="M12 7H7.5a2.5 2.5 0 0 1 0-5C11 2 12 7 12 7z"/><path d="M12 7h4.5a2.5 2.5 0 0 0 0-5C13 2 12 7 12 7z"/></svg></div>
          <h3>基础功能永久免费</h3>
          <p>记账、看数据、导出数据，永久免费。不要求付费、订阅或解锁，也没有记账条数上限。</p>
        </div>
        <div class="pillar">
          <div class="pico"><svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg></div>
          <h3>数据永远属于你</h3>
          <p>随时把全部账目一键导出为 CSV / JSON，带得走、留得住，数据始终掌握在你自己手里。</p>
        </div>
      </div>
    </section>

    <!-- 功能特色 -->
    <section id="features" class="feat">
      <div class="wrap">
        <div class="section-head">
          <h2 class="section-title">好用，藏在每个细节里</h2>
          <p class="section-desc">为"每天都要记"而设计——快、顺、清楚。</p>
        </div>
        <div class="feat-grid">
          <div class="feat-card"><div class="num">01</div><h3>三秒记一笔</h3><p>打开就能记，最多三次点击完成。默认账户与时间自动预填，数字键盘首屏直达。</p></div>
          <div class="feat-card"><div class="num">02</div><h3>手机电脑一套账</h3><p>响应式 + PWA，手机加到主屏像原生 App，电脑打开网页同一份账，随时无缝切换。</p></div>
          <div class="feat-card"><div class="num">03</div><h3>报表看得明白</h3><p>本月收支结余一眼看清，分类占比环形图、月度趋势柱状图，钱花在哪心里有数。</p></div>
        </div>
      </div>
    </section>

    <!-- 三步开始 -->
    <section id="start" class="wrap">
      <div class="section-head">
        <h2 class="section-title">三步，开始你的第一笔</h2>
        <p class="section-desc">一分钟拥有一个干净的记账本。</p>
      </div>
      <div class="steps">
        <div class="step"><div class="idx">1</div><div><h3>注册账号</h3><p>用账号和密码注册，无需手机号、无需实名。</p></div></div>
        <div class="step"><div class="idx">2</div><div><h3>建账户与分类</h3><p>添加现金、银行卡等账户，设置你习惯的收支分类。</p></div></div>
        <div class="step"><div class="idx">3</div><div><h3>记好每一笔</h3><p>三秒记一笔，随时看报表、导数据，日子越记越有余。</p></div></div>
      </div>
    </section>

    <!-- 底部 CTA -->
    <div class="wrap">
      <div class="cta-band">
        <h2>今天，就从记好第一笔开始</h2>
        <p>免费注册，一分钟拥有一个干净的记账本。</p>
        <RouterLink class="btn btn-lg" to="/register">免费开始记账</RouterLink>
      </div>
    </div>

    <!-- 页脚 -->
    <footer class="foot">
      <div class="wrap">
        <div class="foot-top">
          <div class="foot-brand">
            <div class="logo"><span class="mark small">¥</span>有余</div>
            <p>记好每一笔，日子有余。一个干净、免费、数据自持的个人记账本。</p>
          </div>
          <div class="foot-col"><h4>产品</h4><a href="#features">功能特色</a><a href="#start">如何开始</a></div>
          <div class="foot-col"><h4>账户</h4><RouterLink to="/login">登录</RouterLink><RouterLink to="/register">免费注册</RouterLink></div>
          <div class="foot-col"><h4>法律</h4><RouterLink to="/legal/agreement">用户协议</RouterLink><RouterLink to="/legal/privacy">隐私政策</RouterLink></div>
        </div>
        <div class="foot-bottom">
          <span>© 2026 有余记账</span>
          <span>记好每一笔，日子有余</span>
        </div>
      </div>
    </footer>
  </div>
</template>

<style scoped>
.landing {
  --green: #16a34a;
  --green-dark: #12833c;
  --ink: #0f172a;
  --muted: #5b6675;
  --line: #e7ebef;
  --soft: #f4f8f5;
  --radius: 18px;
  color: var(--ink);
  background: #fff;
  line-height: 1.6;
  min-height: 100vh;
}
.landing a { text-decoration: none; color: inherit; }
.wrap { max-width: 1140px; margin: 0 auto; padding: 0 22px; }
.num-font { font-variant-numeric: tabular-nums; }

.nav { position: sticky; top: 0; z-index: 20; background: rgba(255, 255, 255, 0.86); backdrop-filter: blur(10px); border-bottom: 1px solid var(--line); }
.nav-inner { display: flex; align-items: center; justify-content: space-between; height: 64px; }
.logo { display: flex; align-items: center; gap: 10px; font-weight: 800; font-size: 19px; }
.logo .mark { width: 34px; height: 34px; border-radius: 10px; background: linear-gradient(135deg, #22c55e, #0f7a3a); color: #fff; display: flex; align-items: center; justify-content: center; font-size: 19px; font-weight: 800; }
.logo .mark.small { width: 30px; height: 30px; font-size: 16px; border-radius: 9px; }
.nav-links { display: none; gap: 28px; }
.nav-links a { color: var(--muted); font-size: 15px; font-weight: 500; }
.nav-links a:hover { color: var(--ink); }
.nav-actions { display: flex; align-items: center; gap: 10px; }

.btn { display: inline-flex; align-items: center; justify-content: center; height: 42px; padding: 0 20px; border-radius: 11px; font-weight: 700; font-size: 15px; border: none; cursor: pointer; transition: filter 0.15s, transform 0.08s, background 0.15s; white-space: nowrap; }
.btn-primary { background: linear-gradient(135deg, #1eb257, #128a3f); color: #fff; box-shadow: 0 8px 20px rgba(22, 163, 74, 0.28); }
.btn-primary:hover { filter: brightness(1.05); }
.btn-primary:active { transform: scale(0.98); }
.btn-ghost { background: transparent; color: var(--ink); }
.btn-ghost:hover { background: var(--soft); }
.btn-lg { height: 54px; padding: 0 30px; font-size: 16px; border-radius: 13px; }

.hero { position: relative; overflow: hidden; background: radial-gradient(120% 130% at 88% -20%, #e6faee 0%, #ffffff 52%); }
.hero-inner { display: grid; grid-template-columns: 1fr; gap: 40px; padding: 52px 0 44px; align-items: center; }
.eyebrow { display: inline-flex; align-items: center; gap: 7px; font-size: 13px; font-weight: 600; color: var(--green-dark); background: #eafaf0; border: 1px solid #cfeeda; padding: 6px 13px; border-radius: 999px; margin-bottom: 20px; }
.eyebrow .dot { width: 7px; height: 7px; border-radius: 50%; background: var(--green); }
.headline { font-size: clamp(32px, 7.5vw, 52px); line-height: 1.14; font-weight: 850; letter-spacing: -0.02em; }
.headline .hl { color: var(--green); }
.sub { margin-top: 18px; font-size: clamp(15px, 2.4vw, 18px); color: var(--muted); max-width: 31em; }
.cta-row { display: flex; flex-wrap: wrap; gap: 12px; margin-top: 30px; }
.trust { margin-top: 18px; font-size: 13px; color: #93a0ac; display: flex; align-items: center; gap: 8px; }
.trust b { color: var(--green-dark); font-weight: 700; }

.device-wrap { display: flex; justify-content: center; }
.device { width: 288px; height: 588px; border-radius: 40px; background: #0f172a; padding: 11px; box-shadow: 0 34px 80px rgba(15, 23, 42, 0.3); }
.screen { width: 100%; height: 100%; border-radius: 31px; overflow: hidden; background: #f4f8f5; display: flex; flex-direction: column; }
.d-hero { background: linear-gradient(150deg, #35d07f, #16a34a 55%, #0b6b34); color: #fff; padding: 28px 20px 22px; }
.d-hero .dlabel { font-size: 12px; opacity: 0.9; }
.d-hero .dbal { font-size: 32px; font-weight: 800; margin-top: 4px; letter-spacing: -0.02em; }
.d-hero .drow { display: flex; gap: 16px; margin-top: 16px; font-size: 11px; }
.d-hero .drow b { display: block; font-size: 14px; margin-top: 2px; }
.d-card { margin: -16px 13px 0; background: #fff; border-radius: 16px; box-shadow: 0 6px 18px rgba(15, 23, 42, 0.08); padding: 13px; flex: 1; }
.d-item { display: flex; align-items: center; gap: 10px; padding: 10px 4px; border-bottom: 1px solid #f0f2f4; }
.d-item:last-child { border-bottom: none; }
.d-ico { width: 32px; height: 32px; border-radius: 9px; display: flex; align-items: center; justify-content: center; font-size: 16px; flex: 0 0 auto; }
.d-ico.exp-bg { background: #fef2f2; } .d-ico.blue-bg { background: #eff6ff; } .d-ico.inc-bg { background: #ecfdf5; }
.d-t { flex: 1; font-size: 12px; font-weight: 600; }
.d-t small { display: block; font-weight: 400; color: #9aa4b0; font-size: 10px; }
.d-amt { font-size: 13px; font-weight: 700; font-variant-numeric: tabular-nums; }
.d-amt.exp { color: #dc2626; } .d-amt.inc { color: #16a34a; }
.d-fab { margin: 12px auto 16px; width: 48px; height: 48px; border-radius: 50%; background: #16a34a; color: #fff; display: flex; align-items: center; justify-content: center; font-size: 27px; box-shadow: 0 8px 18px rgba(22, 163, 74, 0.4); }

section { padding: 60px 0; }
.section-head { text-align: center; max-width: 36em; margin: 0 auto 40px; }
.section-title { font-size: clamp(24px, 4vw, 34px); font-weight: 800; letter-spacing: -0.01em; }
.section-desc { color: var(--muted); margin-top: 12px; font-size: clamp(15px, 2vw, 17px); }

.pillars { display: grid; grid-template-columns: 1fr; gap: 16px; }
.pillar { background: var(--soft); border: 1px solid var(--line); border-radius: var(--radius); padding: 28px 24px; transition: transform 0.15s, box-shadow 0.15s; }
.pillar:hover { transform: translateY(-3px); box-shadow: 0 14px 30px rgba(15, 23, 42, 0.07); }
.pillar .pico { width: 48px; height: 48px; border-radius: 14px; background: #fff; border: 1px solid var(--line); display: flex; align-items: center; justify-content: center; color: var(--green); margin-bottom: 16px; }
.pillar h3 { font-size: 19px; }
.pillar p { color: var(--muted); font-size: 14.5px; margin-top: 9px; }

.feat { background: var(--soft); }
.feat-grid { display: grid; grid-template-columns: 1fr; gap: 16px; }
.feat-card { background: #fff; border: 1px solid var(--line); border-radius: var(--radius); padding: 26px; }
.feat-card .num { font-size: 13px; font-weight: 800; color: var(--green); }
.feat-card h3 { margin-top: 6px; font-size: 19px; }
.feat-card p { margin-top: 9px; color: var(--muted); font-size: 14.5px; }

.steps { display: grid; grid-template-columns: 1fr; gap: 18px; }
.step { display: flex; gap: 16px; align-items: flex-start; }
.step .idx { flex: 0 0 auto; width: 40px; height: 40px; border-radius: 12px; background: #ecfdf3; color: var(--green-dark); font-weight: 800; display: flex; align-items: center; justify-content: center; }
.step h3 { font-size: 17px; }
.step p { color: var(--muted); font-size: 14.5px; margin-top: 4px; }

.cta-band { background: linear-gradient(150deg, #16a34a, #0b6b34); color: #fff; border-radius: 26px; padding: 52px 26px; text-align: center; margin: 0 0 60px; position: relative; overflow: hidden; }
.cta-band::after { content: ''; position: absolute; width: 260px; height: 260px; border-radius: 50%; background: rgba(255, 255, 255, 0.08); top: -120px; right: -60px; }
.cta-band h2 { font-size: clamp(23px, 4vw, 32px); font-weight: 800; position: relative; }
.cta-band p { margin-top: 12px; color: rgba(255, 255, 255, 0.9); position: relative; }
.cta-band .btn { margin-top: 24px; background: #fff; color: var(--green-dark); box-shadow: 0 10px 24px rgba(0, 0, 0, 0.16); position: relative; }

.foot { border-top: 1px solid var(--line); padding: 40px 0 44px; color: #93a0ac; font-size: 14px; }
.foot-top { display: grid; grid-template-columns: 1fr; gap: 26px; margin-bottom: 28px; }
.foot-brand .logo { margin-bottom: 12px; }
.foot-brand p { max-width: 24em; font-size: 14px; }
.foot-col h4 { color: var(--ink); font-size: 14px; margin-bottom: 12px; }
.foot-col a { display: block; padding: 5px 0; }
.foot-col a:hover { color: var(--green-dark); }
.foot-bottom { border-top: 1px solid var(--line); padding-top: 20px; display: flex; flex-wrap: wrap; gap: 10px; justify-content: space-between; }

@media (min-width: 768px) {
  .nav-links { display: flex; }
  .pillars { grid-template-columns: repeat(3, 1fr); }
  .feat-grid { grid-template-columns: repeat(3, 1fr); }
  .steps { grid-template-columns: repeat(3, 1fr); }
  .foot-top { grid-template-columns: 2fr 1fr 1fr 1fr; }
}
@media (min-width: 900px) {
  .hero-inner { grid-template-columns: 1.05fr 0.95fr; gap: 48px; padding: 88px 0 72px; }
  .device { width: 310px; height: 632px; }
}
</style>
