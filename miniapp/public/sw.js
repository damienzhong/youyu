/*
 * 有余记账应用（uni-app H5）的 Service Worker —— 让 /app/ 可作为 PWA 安装并离线打开。
 *
 * 【作用域】本文件部署在 /app/sw.js，因此作用域天然限定为 /app/，不会碰根路径的营销落地站。
 * 这一点是硬约束：历史版本曾用 vite-plugin-pwa 在作用域 "/" 注册 SW，缓存整套旧应用并把导航
 * 回退到旧 index.html，结果拦截了同源新增的 /app/ 路径（善后脚本见 web/public/sw.js）。
 * 不要把本文件挪到站点根目录，也不要在注册时放宽 scope。
 *
 * 【缓存策略】刻意保守，宁可少缓存也不要出现「打开是旧版」或白屏：
 *   - 导航请求（HTML）：network-first，联网时永远拿最新，断网才回退缓存的 app shell。
 *   - /app/assets/**（Vite 带内容指纹）：cache-first，指纹变了自然 cache miss 走网络。
 *   - /app/static/**、/app/icons/**：stale-while-revalidate，文件名不带指纹，故后台静默更新。
 *   - /api/**：完全不拦截，直接落网络。账本/余额是强一致数据，缓存会造成脏读。
 *   - 非 GET、跨源请求：一律不拦截。
 *
 * 【逃生阀】页面可 postMessage({ type: 'YOUYU_SW_UNREGISTER' }) 让本 SW 清缓存并自注销。
 */

// v2：修「发版后客户端仍加载旧版」。根因是 nginx 的 `location = /app/index.html` 精确匹配
// 按请求 URI 判定，而客户端请求的是目录形式 `/app/`，命中的是前缀块 `location /app/`，
// 于是 HTML 没有任何 Cache-Control，被 WebView / 浏览器按 Last-Modified 启发式缓存住，
// 旧 HTML 又引用旧的带指纹 chunk。升版本号同时会让 activate 清掉 v1 里缓存的旧 shell。
const VERSION = 'youyu-app-v2';
const SHELL_CACHE = `${VERSION}-shell`;
const RUNTIME_CACHE = `${VERSION}-runtime`;

// 本 SW 拥有的缓存名前缀。activate 清理时只删这个前缀下的旧版本，
// 绝不 caches.keys() 全删——同源的落地站可能有自己的缓存，不该被牵连。
const CACHE_PREFIX = 'youyu-app-';

const BASE = '/app/';
const SHELL_URL = BASE; // SPA 入口，等价于 /app/index.html

// 安装期预缓存的最小集合：入口文档 + manifest + 图标。
// 不预缓存 assets/：文件名带构建指纹，此处写死会在发版后立刻失效。
const PRECACHE_URLS = [
  SHELL_URL,
  `${BASE}manifest.json`,
  `${BASE}icons/icon-192.png`,
  `${BASE}icons/icon-512.png`
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    (async () => {
      const cache = await caches.open(SHELL_CACHE);
      // 逐个 add：任一资源 404 不应让整个 install 失败（addAll 是全有或全无）。
      await Promise.all(
        PRECACHE_URLS.map((url) =>
          cache.add(new Request(url, { cache: 'reload' })).catch(() => {})
        )
      );
      await self.skipWaiting();
    })()
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const keys = await caches.keys();
      const stale = keys.filter(
        (k) => k.startsWith(CACHE_PREFIX) && k !== SHELL_CACHE && k !== RUNTIME_CACHE
      );
      await Promise.all(stale.map((k) => caches.delete(k)));
      await self.clients.claim();

      // 从旧版本升级上来（清掉了旧版缓存）时，让已打开的页面重新导航一次：
      // 此刻页面还是旧 SW 交付的旧 HTML，不重新导航的话用户得手动杀进程重开才能看到新版。
      // 只在升级时做，首次安装（stale 为空）不打扰；而 activate 仅在 sw.js 内容变化时触发，
      // 前端日常发版（只有带指纹的 assets 变化）不会走到这里，故不会反复刷新用户页面。
      if (stale.length > 0) {
        const windows = await self.clients.matchAll({ type: 'window' });
        windows.forEach((client) => client.navigate(client.url));
      }
    })()
  );
});

self.addEventListener('message', (event) => {
  if (!event.data || event.data.type !== 'YOUYU_SW_UNREGISTER') return;
  event.waitUntil(
    (async () => {
      const keys = await caches.keys();
      await Promise.all(keys.filter((k) => k.startsWith(CACHE_PREFIX)).map((k) => caches.delete(k)));
      await self.registration.unregister();
      const clients = await self.clients.matchAll({ type: 'window' });
      clients.forEach((client) => client.navigate(client.url));
    })()
  );
});

/**
 * 联网优先，成功则顺手更新缓存；失败（离线）回退缓存。用于 HTML 导航。
 *
 * @param bypassHttpCache 是否绕过 HTTP 缓存层。HTML 必须绕过：服务端没给 `/app/` 声明
 *        no-cache，普通 fetch 会命中 WebView / 浏览器的 HTTP 缓存直接返回旧 HTML，
 *        "联网优先" 就名存实亡了。
 *        刻意用 `fetch(request.url, ...)` 而非 `fetch(request, { cache })`：导航请求的
 *        mode 为 'navigate'，经 Request 构造器会被改写成 'same-origin'，行为不可靠。
 *        导航必为 GET，用 url 重新发起是等价的。
 */
async function networkFirst(request, cacheName, fallbackUrl, bypassHttpCache) {
  const cache = await caches.open(cacheName);
  try {
    const fresh = bypassHttpCache
      ? await fetch(request.url, { cache: 'no-store', credentials: 'same-origin' })
      : await fetch(request);
    // 只缓存成功的基本响应；opaque/错误响应缓存了会污染离线回退。
    if (fresh && fresh.ok && fresh.type === 'basic') {
      cache.put(fallbackUrl || request, fresh.clone()).catch(() => {});
    }
    return fresh;
  } catch (e) {
    const cached = await cache.match(fallbackUrl || request);
    if (cached) return cached;
    throw e;
  }
}

/** 缓存优先，未命中才走网络。用于带内容指纹、内容不会变的资源。 */
async function cacheFirst(request, cacheName) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request);
  if (cached) return cached;
  const fresh = await fetch(request);
  if (fresh && fresh.ok && fresh.type === 'basic') {
    cache.put(request, fresh.clone()).catch(() => {});
  }
  return fresh;
}

/** 先给缓存、后台静默更新。用于文件名不带指纹但可以容忍一版延迟的资源。 */
async function staleWhileRevalidate(request, cacheName) {
  const cache = await caches.open(cacheName);
  const cached = await cache.match(request);
  const networkPromise = fetch(request)
    .then((fresh) => {
      if (fresh && fresh.ok && fresh.type === 'basic') {
        cache.put(request, fresh.clone()).catch(() => {});
      }
      return fresh;
    })
    .catch(() => undefined);
  if (cached) return cached;
  const fresh = await networkPromise;
  if (fresh) return fresh;
  throw new Error(`离线且无缓存: ${request.url}`);
}

self.addEventListener('fetch', (event) => {
  const { request } = event;

  // 非 GET（登录、记账等写操作）一律直连，不做任何离线排队。
  if (request.method !== 'GET') return;

  let url;
  try {
    url = new URL(request.url);
  } catch (e) {
    return;
  }

  // 跨源资源不拦截。
  if (url.origin !== self.location.origin) return;

  // 后端接口不拦截：金额/账本数据必须强一致。
  if (url.pathname.startsWith('/api/')) return;

  // 只管自己作用域内的东西，落地站（根路径）不碰。
  if (!url.pathname.startsWith(BASE)) return;

  // SPA 导航：network-first 且绕过 HTTP 缓存，离线回退到 app shell，
  // 保证 hash 路由的任意深链都能开、且联网时拿到的一定是最新 HTML。
  if (request.mode === 'navigate') {
    event.respondWith(networkFirst(request, SHELL_CACHE, SHELL_URL, true));
    return;
  }

  // Vite 产物带内容指纹，可放心长期缓存。
  if (url.pathname.startsWith(`${BASE}assets/`)) {
    event.respondWith(cacheFirst(request, RUNTIME_CACHE));
    return;
  }

  // 图标与 static 资源：文件名稳定，用 SWR 避免改图后一直拿旧的。
  if (url.pathname.startsWith(`${BASE}static/`) || url.pathname.startsWith(`${BASE}icons/`)) {
    event.respondWith(staleWhileRevalidate(request, RUNTIME_CACHE));
    return;
  }

  // /app/ 下的其余 GET（manifest 等）：联网优先、离线兜底。
  // 同样绕过 HTTP 缓存——这些文件名不带指纹，靠 HTTP 缓存会拿到旧版。
  event.respondWith(networkFirst(request, RUNTIME_CACHE, null, true));
});
