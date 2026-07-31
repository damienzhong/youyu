/*
 * 自注销 Service Worker。
 *
 * 历史版本的 web 曾用 vite-plugin-pwa 在作用域 "/" 注册 SW，缓存整套旧记账应用，
 * 并把导航回退到旧的 index.html——这会拦截同源新增的 /app/（uni-app H5）路径。
 * 现在落地站不再使用 PWA，这个脚本用于让老客户端自愈：
 *   - 清空所有缓存
 *   - 注销自身
 *   - 刷新所有受控页面，回到无 SW 的普通站点
 * 老浏览器会周期性拉取 /sw.js 并做字节比对，取到本脚本后即完成自愈。
 */
self.addEventListener('install', () => self.skipWaiting());

self.addEventListener('activate', (event) => {
  event.waitUntil(
    (async () => {
      const keys = await caches.keys();
      await Promise.all(keys.map((k) => caches.delete(k)));
      await self.registration.unregister();
      const clients = await self.clients.matchAll({ type: 'window' });
      clients.forEach((client) => client.navigate(client.url));
    })()
  );
});
