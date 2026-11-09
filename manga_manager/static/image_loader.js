// Keep cover downloads near the viewport and leave server workers available for controls.
(() => {
  const queue = [], records = new WeakMap();
  let active = 0;
  function pump() {
    if (document.hidden) return;
    while (active < 2 && queue.length) {
      const record = queue.shift(), {img} = record;
      if (!img.isConnected || !record.visible || record.state !== 'queued') {
        if (record.state === 'queued') record.state = 'idle';
        continue;
      }
      record.state = 'loading'; active++;
      const finish = success => {
        img.removeEventListener('load', loaded); img.removeEventListener('error', failed);
        active--;
        if (success) {
          record.state = 'done'; observer.unobserve(img);
        } else if (record.tries++ < 2 && img.isConnected) {
          record.state = 'waiting';
          setTimeout(() => { record.state = 'idle'; enqueue(record); }, record.tries * 1200);
        } else { record.state = 'done'; img.hidden = true; observer.unobserve(img); }
        pump();
      };
      const loaded = () => finish(true), failed = () => finish(false);
      img.addEventListener('load', loaded); img.addEventListener('error', failed);
      img.decoding = 'async'; img.src = img.dataset.coverSrc;
    }
  }
  function enqueue(record) {
    if (record.state === 'idle' && record.visible && record.img.isConnected) {
      record.state = 'queued'; queue.push(record); pump();
    }
  }
  const observer = new IntersectionObserver(entries => {
    for (const entry of entries) {
      const record = records.get(entry.target);
      record.visible = entry.isIntersecting;
      enqueue(record);
    }
  }, {rootMargin: '300px'});
  function scan(root) {
    const images = root.matches?.('img[data-cover-src]') ? [root] : root.querySelectorAll?.('img[data-cover-src]') || [];
    for (const img of images) if (!records.has(img)) {
      records.set(img, {img, visible: false, state: 'idle', tries: 0}); observer.observe(img);
    }
  }
  new MutationObserver(changes => changes.forEach(change => change.addedNodes.forEach(scan)))
    .observe(document.body, {childList: true, subtree: true});
  scan(document);
  document.addEventListener('visibilitychange', pump);
})();
