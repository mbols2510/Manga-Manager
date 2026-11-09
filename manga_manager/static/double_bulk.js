(() => {
  const $ = selector => document.querySelector(selector);
  const panel = $('#process-panel');
  let token = null, busy = false, checking = false, lastState = '';
  async function api(path, body) {
    const options = body === undefined ? {} : {
      method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
    };
    const response = await fetch(window.DC_API + path, options);
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || response.statusText);
    return data;
  }
  function bytes(n) {
    const units = ['B', 'KB', 'MB', 'GB', 'TB']; let i = 0;
    while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
    return `${n.toFixed(1)} ${units[i]}`;
  }
  function close() { if (panel.open) panel.close(); }
  $('#process-close').onclick = close;
  panel.addEventListener('click', e => { if (e.target === panel) close(); });
  async function preview() {
    token = null;
    $('#process-apply').disabled = true;
    $('#process-refresh').disabled = true;
    $('#process-summary').textContent = 'Checking all manga in the current scan…';
    $('#process-preview').replaceChildren();
    try {
      const plan = await api('/bulk-preview', {});
      // Closing the dialog while a preview loads must never apply anything.
      if (!panel.open) return;
      token = plan.token;
      const retention = plan.trashRetentionDays > 0
        ? `Trash is deleted automatically after ${plan.trashRetentionDays} days.`
        : 'Automatic trash deletion is off. You can restore files from Trash.';
      $('#process-summary').textContent = `${plan.seriesCount} manga · ${plan.chapterCount} chapters · ${plan.fileCount} files (${bytes(plan.bytes)}) to Trash. ${retention} ${plan.skipped.length} chapters skipped. Nothing has been moved yet.`;
      const groups = new Map();
      for (const chapter of plan.chapters) {
        let details = groups.get(chapter.seriesId);
        if (!details) {
          details = document.createElement('details'); details.className = 'dc-process-series';
          const summary = document.createElement('summary'); summary.textContent = chapter.seriesName;
          details.appendChild(summary); groups.set(chapter.seriesId, details);
          $('#process-preview').appendChild(details);
        }
        const block = document.createElement('div'); block.className = 'dc-process-chapter';
        const title = document.createElement('strong'); title.textContent = chapter.chapterLabel;
        block.appendChild(title);
        const keep = document.createElement('p'); keep.className = 'dc-process-keep';
        keep.textContent = `Keep: ${chapter.keep.filePath} (${bytes(chapter.keep.bytes)}) · ${chapter.reason}`;
        block.appendChild(keep);
        for (const file of chapter.trash) {
          const line = document.createElement('p'); line.className = 'dc-process-trash';
          line.textContent = `Trash: ${file.filePath} (${bytes(file.bytes)})`; block.appendChild(line);
        }
        details.appendChild(block);
      }
      for (const skip of plan.skipped) {
        const line = document.createElement('p'); line.className = 'dc-note';
        line.textContent = `Skipped: ${skip.seriesName} · chapter ${skip.chapterId}: ${skip.reason}`;
        $('#process-preview').appendChild(line);
      }
      $('#process-apply').disabled = !plan.chapterCount;
      $('#process-apply').textContent = `Apply choices for ${plan.chapterCount} chapters`;
    } catch (e) { $('#process-summary').textContent = e.message; }
    finally { $('#process-refresh').disabled = false; }
  }
  $('#process-all').onclick = () => { panel.showModal(); preview(); };
  $('#process-refresh').onclick = preview;
  $('#process-apply').onclick = async () => {
    if (!token) return;
    $('#process-apply').disabled = true;
    $('#process-refresh').disabled = true;
    $('#process-summary').textContent = 'Starting the reviewed plan…';
    try {
      await api('/bulk-apply', {token});
      token = null; busy = true; $('#process-all').disabled = true; close();
      await refresh();
    } catch (e) {
      token = null;
      $('#process-summary').textContent = `${e.message} Refresh the preview before trying again.`;
    } finally { $('#process-refresh').disabled = false; }
  };
  async function refresh() {
    if (checking) return;
    checking = true;
    try {
      const [state, scan] = await Promise.all([api('/bulk-status'), api('/status')]);
      const wasBusy = busy; busy = state.running;
      $('#process-all').disabled = busy || scan.scanning || !scan.seriesWithDuplicates;
      $('#scan-now').disabled = busy || scan.scanning;
      if (state.total) {
        const box = $('#process-status'); box.hidden = false;
        const next = JSON.stringify(state);
        if (next !== lastState) {
          box.textContent = `${state.running ? (state.phase || 'Processing') : 'Finished'}: ${state.done}/${state.total} chapters · ${state.moved} files moved to Trash · ${state.failed} failed.`
            + [...state.errors, ...state.warnings].map(error => `\n${error}`).join('');
          lastState = next;
        }
      }
      if (wasBusy && !busy) window.dispatchEvent(new Event('dc-bulk-complete'));
    } catch (e) {
      if (busy) {
        const box = $('#process-status'); box.hidden = false;
        box.textContent = 'Connection lost while checking progress. Processing may still be running; progress will refresh when connected.';
        lastState = '';
      }
    } finally { checking = false; }
  }
  refresh(); setInterval(refresh, 2000);
})();
