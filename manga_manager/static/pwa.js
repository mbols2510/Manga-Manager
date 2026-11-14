(() => {
  const script = document.currentScript;
  if (!window.isSecureContext || !('serviceWorker' in navigator)) return;
  window.addEventListener('load', () => {
    navigator.serviceWorker.register(script.dataset.worker, {scope: script.dataset.scope, updateViaCache: 'none'})
      .catch(error => console.warn('App offline support unavailable', error));
  });
})();
