(() => {
  const script = document.currentScript;
  if (!window.isSecureContext || !('serviceWorker' in navigator)) return;
  window.addEventListener('load', () => {
    navigator.serviceWorker.register(script.dataset.worker, {scope: script.dataset.scope, updateViaCache: 'none'})
      .catch(error => console.warn('App offline support unavailable', error));
  });
  let prompt;
  const button = document.createElement('button');
  button.type = 'button'; button.className = 'btn pwa-install'; button.textContent = 'Install app';
  button.hidden = true;
  button.style.cssText = 'position:fixed;right:20px;bottom:88px;z-index:40;background:#e4002b;color:#fff;border:0;border-radius:12px;padding:12px 18px;cursor:pointer;font:inherit';
  document.body.appendChild(button);
  window.addEventListener('beforeinstallprompt', event => {
    event.preventDefault(); prompt = event;
    button.hidden = window.matchMedia('(display-mode: standalone)').matches;
  });
  button.addEventListener('click', async () => {
    if (!prompt) return;
    button.hidden = true;
    const current = prompt; prompt = null;
    try { await current.prompt(); await current.userChoice; }
    catch (error) { console.warn('App installation unavailable', error); }
  });
  window.addEventListener('appinstalled', () => { prompt = null; button.hidden = true; });
})();
