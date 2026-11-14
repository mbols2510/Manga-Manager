(() => {
  const $ = selector => document.querySelector(selector);
  const panel = $('#process-panel');
  let token = null, controls = [], busy = false, generation = 0, plan = null;
  const esc = value => { const node = document.createElement('span'); node.textContent = value ?? ''; return node.innerHTML.replace(/"/g, '&quot;').replace(/'/g, '&#39;'); };
  async function api(path, body) {
    const response = await fetch(window.DC_API + path, {
      method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(body)
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data.error || response.statusText);
    return data;
  }
  function bytes(n) {
    const units = ['B', 'KB', 'MB', 'GB', 'TB']; let i = 0;
    while (n >= 1024 && i < units.length - 1) { n /= 1024; i++; }
    return `${n.toFixed(1)} ${units[i]}`;
  }
  function relative(iso) {
    if (!iso) return 'date unknown';
    const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86400000);
    return days < 1 ? 'today' : `${days}d ago`;
  }
  function close() { generation++; token = null; if (panel.open) panel.close(); }
  $('#process-close').onclick = close;
  panel.addEventListener('cancel', close);
  panel.addEventListener('click', e => { if (e.target === panel) close(); });
  function summary() {
    const size = controls.reduce((sum, c) => sum + c.files.filter(f => f.id !== c.selected).reduce((n, f) => n + f.bytes, 0), 0);
    const retention = plan.trashRetentionDays > 0
      ? `Trash is kept for ${plan.trashRetentionDays} days.` : 'Automatic trash deletion is off.';
    $('#process-summary').textContent = plan.chapterCount
      ? `${plan.seriesCount} manga · ${plan.chapterCount} chapters · ${plan.fileCount} files (${bytes(size)}) to Trash. ${retention} Nothing has been moved yet.`
      : 'No chapters can be processed. Review the folder settings and the chapters needing attention below.';
  }
  function chapterCards(chapter) {
    const files = [chapter.keep, ...chapter.trash]
      .sort((a, b) => String(b.modifiedAt || '').localeCompare(String(a.modifiedAt || '')))
      .sort((a, b) => Number(b.isOfficial) - Number(a.isOfficial));
    const block = document.createElement('section'); block.className = 'dc-chapter';
    block.innerHTML = `<div class="dc-chead"><span class="dc-ctitle">${esc(chapter.chapterLabel)}</span><span class="pill">${files.length} files</span></div><div class="dc-files" role="radiogroup" aria-label="${esc(chapter.chapterLabel)}: file to keep"></div>`;
    const cards = [];
    const control = {chapter, files, selected: chapter.keep.id, set(id) {
      this.selected = id;
      cards.forEach(card => card.setAttribute('aria-checked', String(Number(card.dataset.id) === id)));
    }};
    for (const file of files) {
      const path = file.filePath.replace(/\\/g, '/'), name = path.split('/').pop(), directory = path.slice(0, -name.length);
      const badges = [file.isOfficial && '<span class="dc-chip official">Official</span>', file.isNewest && '<span class="dc-chip newest">Newest</span>', file.isLargest && '<span class="dc-chip largest">Largest</span>'].filter(Boolean).join('');
      const card = document.createElement('div'); card.className = 'dc-file';
      card.setAttribute('role', 'radio'); card.tabIndex = 0; card.dataset.id = file.id;
      card.innerHTML = `<span class="dc-radio"></span><div><div class="dc-fname">${esc(name)}</div><div class="dc-fdir" title="${esc(file.filePath)}">${esc(directory)}</div><div class="dc-meta"><span>${relative(file.modifiedAt)}${file.modifiedAt ? ' · ' + esc(new Date(file.modifiedAt).toLocaleString()) : ''}</span><span>${bytes(file.bytes)}</span><span>${file.pages ?? '?'} pg</span></div><div class="dc-chips">${badges}</div></div>`;
      card.onclick = () => { control.set(file.id); summary(); };
      card.onkeydown = e => {
        if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); card.click(); }
        if (['ArrowDown', 'ArrowRight', 'ArrowUp', 'ArrowLeft'].includes(e.key)) {
          e.preventDefault();
          const step = ['ArrowDown', 'ArrowRight'].includes(e.key) ? 1 : -1;
          const next = cards[(cards.indexOf(card) + step + cards.length) % cards.length]; next.focus(); next.click();
        }
      };
      cards.push(card); block.querySelector('.dc-files').appendChild(card);
    }
    control.set(control.selected); controls.push(control);
    return block;
  }
  async function preview() {
    const current = ++generation;
    token = null; controls = []; plan = null;
    $('#process-apply').disabled = true; $('#process-refresh').disabled = true;
    $('#process-summary').textContent = 'Checking all manga in the current scan…';
    $('#process-preview').replaceChildren(); $('#process-strategies').hidden = true;
    try {
      const data = await api('/bulk-preview', {});
      if (current !== generation || !panel.open) return;
      plan = data; token = plan.token;
      const groups = new Map();
      for (const chapter of plan.chapters) {
        let details = groups.get(chapter.seriesId);
        if (!details) {
          details = document.createElement('details'); details.className = 'dc-process-series'; details.open = groups.size === 0;
          const heading = document.createElement('summary'); heading.textContent = chapter.seriesName;
          details.appendChild(heading); groups.set(chapter.seriesId, details); $('#process-preview').appendChild(details);
        }
        details.appendChild(chapterCards(chapter));
      }
      if (plan.skipped.length) {
        const details = document.createElement('details'); details.className = 'dc-process-skipped'; details.open = !plan.chapterCount;
        const heading = document.createElement('summary'); heading.textContent = `${plan.skipped.length} chapters need attention`;
        details.appendChild(heading);
        const reasons = new Map();
        plan.skipped.forEach(skip => reasons.set(skip.reason, (reasons.get(skip.reason) || 0) + 1));
        reasons.forEach((count, reason) => {
          const line = document.createElement('p'); line.textContent = `${count} chapters: ${reason}`; details.appendChild(line);
        });
        if ([...reasons.keys()].some(reason => reason.includes('outside'))) {
          const note = document.createElement('p');
          note.textContent = `Check Settings → Double chapters. Manga folder in this container: ${plan.mangaRoot}. Kavita folder: ${plan.kavitaRoot}. Use the container mount, normally /manga, rather than the host folder.`;
          details.appendChild(note);
        }
        const list = document.createElement('ul');
        plan.skipped.forEach(skip => { const item = document.createElement('li'); item.textContent = `${skip.seriesName} · chapter ${skip.chapterId}`; list.appendChild(item); });
        const nested = document.createElement('details');
        const label = document.createElement('summary'); label.textContent = 'Show affected chapters'; nested.append(label, list); details.appendChild(nested);
        $('#process-preview').appendChild(details);
      }
      $('#process-strategies').hidden = !plan.chapterCount;
      $('#process-select-count').textContent = `Select for all ${plan.chapterCount}:`;
      summary();
      $('#process-apply').disabled = !plan.chapterCount;
      $('#process-apply').textContent = `Apply to all ${plan.chapterCount} chapters`;
    } catch (e) { if (current === generation) $('#process-summary').textContent = e.message; }
    finally { if (current === generation) $('#process-refresh').disabled = false; }
  }
  $('#process-strategies').querySelectorAll('[data-strategy]').forEach(button => button.onclick = () => {
    controls.forEach(control => {
      const key = button.dataset.strategy;
      const file = key === 'largest' ? control.files.find(f => f.isLargest)
        : key === 'newest' ? control.files.find(f => f.isNewest)
        : control.files.find(f => f.isOfficial) || control.files.find(f => f.isNewest);
      control.set((file || control.files[0]).id);
    });
    summary();
  });
  $('#process-all').onclick = () => { panel.showModal(); preview(); };
  $('#process-refresh').onclick = preview;
  $('#process-apply').onclick = async () => {
    if (!token) return;
    const trashed = controls.reduce((list, c) => list.concat(c.files.filter(f => f.id !== c.selected)), []);
    const days = plan.trashRetentionDays > 0 ? `They are deleted for good after ${plan.trashRetentionDays} days.` : 'They stay in the Trash until you empty it.';
    if (!confirm(`Move ${trashed.length} files (${bytes(trashed.reduce((n, f) => n + f.bytes, 0))}) from ${plan.chapterCount} chapters to the Trash? ${days}`)) return;
    $('#process-apply').disabled = true; $('#process-refresh').disabled = true;
    $('#process-summary').textContent = 'Starting the reviewed choices…';
    const selections = controls.map(c => ({seriesId: c.chapter.seriesId, chapterId: c.chapter.chapterId, keepFileId: c.selected}));
    try {
      const state = await api('/bulk-apply', {token, selections});
      busy = true; $('#process-all').disabled = true; close();
      renderStatus({...window.DC_STATUS, bulk: state});
      window.dispatchEvent(new Event('dc-request-status'));
    } catch (e) {
      token = null; $('#process-summary').textContent = `${e.message} Refresh the preview before trying again.`;
    } finally { $('#process-refresh').disabled = false; }
  };
  function renderStatus(scan) {
    if (!scan?.bulk) return;
    const state = scan.bulk, wasBusy = busy; busy = state.running;
    $('#process-all').disabled = busy || scan.scanning || !scan.seriesWithDuplicates;
    $('#scan-now').disabled = busy || scan.scanning;
    if (state.total) {
      const box = $('#process-status'); box.hidden = false;
      const text = `${state.running ? (state.phase || 'Processing') : 'Finished'}: ${state.done}/${state.total} chapters · ${state.moved} files moved to Trash · ${state.failed} failed.`
        + [...state.errors, ...state.warnings].map(error => `\n${error}`).join('');
      if (box.textContent !== text) box.textContent = text;
    }
    if (wasBusy && !busy) window.dispatchEvent(new Event('dc-bulk-complete'));
  }
  window.addEventListener('dc-status', event => renderStatus(event.detail));
  renderStatus(window.DC_STATUS);
})();
