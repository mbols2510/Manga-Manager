// Only public shell assets are stored. Pages, covers, APIs and writes always use the network.
const CACHE_PREFIX = 'manga-manager-shell-';
const CACHE = CACHE_PREFIX + {{ version|tojson }} + '-1';
const OFFLINE = new URL({{ url_for('offline')|tojson }}, self.location.origin).href;
const ASSETS = {{ assets|tojson }}.map(path => new URL(path, self.location.origin).href);
const PUBLIC = new Set([OFFLINE, ...ASSETS]);
self.addEventListener('install', event => {
  event.waitUntil(caches.open(CACHE).then(cache => cache.addAll([...PUBLIC])).then(() => self.skipWaiting()));
});
self.addEventListener('activate', event => {
  event.waitUntil(caches.keys().then(keys => Promise.all(
    keys.filter(key => key.startsWith(CACHE_PREFIX) && key !== CACHE).map(key => caches.delete(key))
  )).then(() => self.clients.claim()));
});
self.addEventListener('fetch', event => {
  if (event.request.method !== 'GET') return;
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) return;
  if (event.request.mode === 'navigate') {
    event.respondWith(fetch(event.request).catch(() => caches.open(CACHE).then(cache => cache.match(OFFLINE))));
  } else if (PUBLIC.has(url.href)) {
    event.respondWith(caches.open(CACHE).then(async cache => (await cache.match(url.href)) || fetch(event.request)));
  }
});
