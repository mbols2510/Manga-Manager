(() => {
  const API = window.DC_API;
  const $ = (sel) => document.querySelector(sel);
  const esc = (s) => { const d = document.createElement('div'); d.textContent = s ?? ''; return d.innerHTML; };

  const fmtBytes = (n) => {
    if (!n && n !== 0) return '';
    const u = ['B', 'KB', 'MB', 'GB']; let i = 0;
    while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
    return `${n.toFixed(1)} ${u[i]}`;
  };
  const fmtTime = (iso) => iso ? new Date(iso).toLocaleString() : 'never';
  const fmtRel = (iso) => {
    if (!iso) return '';
    const mins = Math.round((Date.now() - new Date(iso).getTime()) / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return `${mins}m ago`;
    const h = Math.round(mins / 60); if (h < 24) return `${h}h ago`;
    const d = Math.round(h / 24); if (d < 30) return `${d}d ago`;
    const m = Math.round(d / 30); if (m < 12) return `${m}mo ago`;
    return `${Math.round(m / 12)}y ago`;
  };
  const chapterLabel = (c) => c.title || `Chapter ${c.range || c.number || '?'}`;
  const volumeLabel = (c) => (c.isLooseVolume || !c.volumeName) ? null : `Volume ${c.volumeName}`;

  let toastTimer;
  function toast(msg) {
    const t = $('#toast'); t.textContent = msg; t.classList.remove('hidden');
    clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.add('hidden'), 3200);
  }

  async function api(path, opts) {
    const res = await fetch(API + path, opts);
    if (!res.ok) {
      let msg = res.statusText;
      try { msg = (await res.json()).error || msg; } catch (e) {}
      throw new Error(msg);
    }
    return res.status === 204 ? null : res.json();
  }
  const post = (path, body) => api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body ?? {}) });

  // ------------------------------------------------------------------ status
  let wasScanning = false;
  async function refreshStatus() {
    const note = $('#status');
    try {
      const s = await api('/status');
      const parts = [s.scanning
        ? (s.scanTotal ? `Scanning… ${s.scanDone}/${s.scanTotal}` : 'Scanning…')
        : `Last scan ${s.lastScan ? fmtRel(s.lastScan) : 'never'}`];
      parts.push(`every ${s.scanIntervalMinutes} min`);
      if (s.lastError) parts.push(`⚠ ${s.lastError}`);
      if (!s.kavitaConfigured) parts.push('⚠ Kavita is not configured (Settings)');
      note.textContent = parts.join(' · ');
      $('#trash-retention-note').textContent = s.trashRetentionDays > 0
        ? `Trashed files are deleted automatically after ${s.trashRetentionDays} day${s.trashRetentionDays === 1 ? '' : 's'}.`
        : 'Automatic deletion is off: files stay in the trash until you purge them.';
      $('#digest-schedule').textContent = !s.digestEnabled ? 'The weekly digest is turned off.'
        : !s.ntfyConfigured ? 'The weekly digest needs notifications to be set up first (Settings → Notifications).'
        : `Sent every ${s.digestWeekday} at ${String(s.digestHour).padStart(2, '0')}:00${s.lastDigestWeek ? ` · last sent in ${s.lastDigestWeek}` : ''}.`;
      if (wasScanning && !s.scanning) refreshSeries();
      wasScanning = s.scanning;
    } catch (e) { note.textContent = 'Could not reach the backend'; }
  }

  // ------------------------------------------------------------------ series grid
  async function refreshSeries() {
    const list = await api('/series');
    const grid = $('#series-grid');
    grid.innerHTML = '';
    $('#empty-state').classList.toggle('hidden', list.length > 0);
    $('#dash-count').textContent = list.length ? `(${list.length})` : '';
    for (const s of list) {
      const card = document.createElement('button');
      card.type = 'button'; card.className = 'dc-card';
      card.innerHTML = `<div class="dc-cover"><img loading="lazy" alt="" src="${API}/cover/${s.id}"><span class="dc-count">${s.chapterCount} chapter${s.chapterCount === 1 ? '' : 's'}</span></div><span class="dc-name">${esc(s.name)}</span>`;
      card.onclick = () => openSeries(s.id, s.name);
      grid.appendChild(card);
    }
  }

  // ------------------------------------------------------------------ series panel
  const panel = $('#series-panel');
  let closeHook = null;

  function closePanel() {
    if (closeHook) { const h = closeHook; closeHook = null; h(); }   // tell Kavita about what was remediated
    if (panel.open) panel.close();
    $('#series-foot').classList.add('hidden');
  }
  $('#series-close').onclick = closePanel;
  panel.addEventListener('cancel', (e) => { e.preventDefault(); closePanel(); });
  panel.addEventListener('click', (e) => { if (e.target === panel) closePanel(); });

  async function openSeries(id, name) {
    let detail;
    try { detail = await api(`/series/${id}`); } catch (e) { toast(e.message); refreshSeries(); return; }
    $('#series-title').textContent = detail.name || name;
    const body = $('#series-body'); body.innerHTML = '';
    const chapters = [...detail.chapters].sort((a, b) => (a.sortOrder ?? 0) - (b.sortOrder ?? 0));

    const bulk = document.createElement('div');
    bulk.className = 'dc-bulk';
    bulk.innerHTML = `<span>Select for all ${chapters.length}:</span>
      <button class="btn sm" type="button" data-s="official">Official, else newest</button>
      <button class="btn sm" type="button" data-s="newest">Newest</button>
      <button class="btn sm" type="button" data-s="largest">Largest</button>`;
    body.appendChild(bulk);

    const foot = $('#series-foot'), applyBtn = $('#bulk-apply'), progress = $('#bulk-progress');
    applyBtn.textContent = `Apply to all ${chapters.length} chapters`;
    applyBtn.disabled = false; progress.classList.add('hidden');
    foot.classList.remove('hidden');

    let pendingScan = false, remediated = [];
    const scanIfPending = async () => {
      if (!pendingScan) return;
      pendingScan = false;
      const payload = remediated; remediated = [];
      try { await post(`/series/${id}/kavita-scan`, { libraryId: detail.libraryId ?? null, chapters: payload }); }
      catch (e) { console.warn('Kavita rescan failed', e); }
    };
    closeHook = scanIfPending;

    const ctrls = [];
    for (const chapter of chapters) {
      const block = document.createElement('div');
      block.className = 'dc-chapter';
      const vol = volumeLabel(chapter);
      block.innerHTML = `<div class="dc-chead"><div><span class="dc-ctitle">${esc(chapterLabel(chapter))}</span>${vol ? `<span class="dc-csub">${esc(vol)}</span>` : ''}</div><span class="pill">${chapter.files.length} files</span></div><div class="dc-files" role="radiogroup"></div>`;
      const list = block.querySelector('.dc-files');
      let selected = (chapter.files.find(f => f.isOfficial) || chapter.files.find(f => f.isNewest) || chapter.files[0])?.id;
      const cards = [];
      const paint = () => cards.forEach(c => c.setAttribute('aria-checked', String(c.dataset.id == selected)));
      for (const f of chapter.files) {
        const name = f.filePath.split('/').pop(), dir = f.filePath.slice(0, f.filePath.length - name.length);
        const chips = [f.isOfficial && '<span class="dc-chip official">Official</span>', f.isNewest && '<span class="dc-chip newest">Newest</span>', f.isLargest && '<span class="dc-chip largest">Largest</span>'].filter(Boolean).join('');
        const c = document.createElement('div');
        c.className = 'dc-file'; c.setAttribute('role', 'radio'); c.tabIndex = 0; c.dataset.id = f.id;
        c.innerHTML = `<span class="dc-radio"></span><div><div class="dc-fname">${esc(name)}</div><div class="dc-fdir" title="${esc(f.filePath)}">${esc(dir)}</div><div class="dc-meta"><span>${f.modifiedAt ? `${fmtRel(f.modifiedAt)} · ${fmtTime(f.modifiedAt)}` : 'date unknown'}</span><span>${fmtBytes(f.bytes)}</span><span>${f.pages} pg</span></div>${chips ? `<div class="dc-chips">${chips}</div>` : ''}</div>`;
        const pick = () => { selected = f.id; paint(); };
        c.onclick = pick;
        c.onkeydown = (e) => { if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); pick(); } };
        cards.push(c); list.appendChild(c);
      }
      paint();

      const remediate = async () => {
        await post(`/series/${id}/chapters/${chapter.id}/remediate`, { keepFileId: parseInt(selected, 10) });
        block.remove();
        pendingScan = true;
        const kept = chapter.files.find(f => f.id === selected);
        remediated.push({ chapterId: chapter.id, expectedPages: kept ? kept.pages : null });
      };
      const btn = document.createElement('button');
      btn.className = 'btn sm'; btn.type = 'button'; btn.textContent = 'Keep selected, trash the rest';
      btn.onclick = async () => {
        btn.disabled = true; btn.textContent = 'Working…';
        try {
          await remediate();
          refreshSeries(); refreshTrash();
          // One Kavita rescan once every chapter shown has been handled, not one per click.
          if (!body.querySelector('.dc-chapter')) { await scanIfPending(); closePanel(); }
        } catch (e) { toast('Failed: ' + e.message); btn.disabled = false; btn.textContent = 'Keep selected, trash the rest'; }
      };
      block.appendChild(btn);
      body.appendChild(block);
      ctrls.push({ block, remediate, set(strategy) {
        const t = strategy === 'largest' ? chapter.files.find(f => f.isLargest)
          : strategy === 'newest' ? chapter.files.find(f => f.isNewest)
          : chapter.files.find(f => f.isOfficial) || chapter.files.find(f => f.isNewest);
        if (t) { selected = t.id; paint(); }
      } });
    }
    bulk.querySelectorAll('[data-s]').forEach(b => b.onclick = () => ctrls.forEach(c => c.set(b.dataset.s)));

    applyBtn.onclick = async () => {
      if (!confirm(`Keep your current selection and move the other file(s) to the trash for all ${ctrls.length} chapters?`)) return;
      applyBtn.disabled = true; progress.classList.remove('hidden');
      let done = 0, failed = 0;
      for (const c of [...ctrls]) {
        if (!document.body.contains(c.block)) continue;
        progress.textContent = `Working on ${done + 1} of ${ctrls.length}…`;
        try { await c.remediate(); } catch (e) { failed++; }
        done++;
      }
      refreshSeries(); refreshTrash();
      if (done - failed > 0) {
        progress.textContent = 'Asking Kavita to rescan…';
        await scanIfPending();
        progress.textContent = failed ? `${done - failed} done, ${failed} failed. Kavita rescan requested.` : `Done: ${done} chapters. Kavita rescan requested.`;
      } else progress.textContent = `${failed} failed, nothing to rescan.`;
      if (!body.querySelector('.dc-chapter')) setTimeout(closePanel, 900); else applyBtn.disabled = false;
    };

    if (!panel.open) panel.showModal();
  }

  // ------------------------------------------------------------------ trash
  let trashList = [];
  async function refreshTrash() { trashList = await api('/trash'); renderTrash(); }

  function renderTrash() {
    const sel = $('#trash-series-filter');
    const names = [...new Set(trashList.map(t => t.seriesName).filter(Boolean))].sort((a, b) => a.localeCompare(b));
    const prev = sel.value;
    sel.innerHTML = '<option value="">All series</option>' + names.map(n => `<option value="${esc(n)}">${esc(n)}</option>`).join('');
    sel.value = names.includes(prev) ? prev : '';
    const rows = sel.value ? trashList.filter(t => t.seriesName === sel.value) : trashList;
    const purge = $('#purge-all');
    purge.textContent = sel.value ? `Empty trash for “${sel.value}”` : 'Empty trash permanently';
    purge.disabled = rows.length === 0;
    $('#trash-count').textContent = trashList.length ? `(${trashList.length})` : '';
    const tbody = $('#trash-body'); tbody.innerHTML = '';
    const empty = $('#trash-empty');
    empty.classList.toggle('hidden', rows.length > 0);
    empty.querySelector('p').textContent = trashList.length ? 'No trashed files match this filter.' : 'Trash is empty.';
    for (const t of rows) {
      const tr = document.createElement('tr');
      const expires = t.expiresAt ? `in ${fmtRel(t.expiresAt).replace(' ago', '')}`.replace('in just now', 'very soon') : 'never';
      tr.innerHTML = `<td>${esc(t.seriesName || '')}</td><td class="path">${esc(t.trashPath)}</td><td>${fmtBytes(t.size)}</td><td>${fmtTime(t.deletedAt)}</td><td>${expires}</td><td><button class="btn sm restore" type="button">Restore</button> <button class="btn sm purge" type="button">Delete forever</button></td>`;
      tr.querySelector('.restore').onclick = async () => {
        try { await post(`/trash/${t.id}/restore`); refreshTrash(); refreshSeries(); } catch (e) { toast('Restore failed: ' + e.message); }
      };
      tr.querySelector('.purge').onclick = async () => {
        if (!confirm('Permanently delete this file? This cannot be undone.')) return;
        await post(`/trash/${t.id}/purge`); refreshTrash();
      };
      tbody.appendChild(tr);
    }
  }
  $('#trash-series-filter').onchange = renderTrash;
  $('#purge-all').onclick = async () => {
    const f = $('#trash-series-filter').value;
    if (f) {
      const ids = trashList.filter(t => t.seriesName === f).map(t => t.id);
      if (!ids.length || !confirm(`Permanently delete ${ids.length} trashed file(s) for “${f}”? This cannot be undone.`)) return;
      for (const id of ids) await post(`/trash/${id}/purge`);
    } else {
      if (!confirm('Permanently delete ALL trashed files? This cannot be undone.')) return;
      await post('/trash/purge-all');
    }
    refreshTrash();
  };

  // ------------------------------------------------------------------ digest
  $('#digest-preview').onclick = async () => {
    try {
      const d = await api('/digest-preview');
      $('#digest-title').textContent = d.title;
      $('#digest-body').textContent = d.message;
      const acts = $('#digest-actions'); acts.innerHTML = '';
      d.actions.forEach(a => { const s = document.createElement('span'); s.textContent = a.label; acts.appendChild(s); });
      $('#digest-box').classList.remove('hidden');
    } catch (e) { toast(e.message); }
  };
  $('#digest-send').onclick = async () => {
    const msg = $('#digest-msg'); msg.textContent = 'Sending…';
    try { await post('/notify-test'); msg.textContent = 'Sent. Check your phone.'; }
    catch (e) { msg.textContent = 'Failed: ' + e.message; }
  };

  // ------------------------------------------------------------------ tabs & scan
  document.querySelectorAll('.dc-tabs [data-tab]').forEach(b => b.onclick = () => {
    document.querySelectorAll('.dc-tabs [data-tab]').forEach(x => x.setAttribute('aria-pressed', String(x === b)));
    for (const name of ['dashboard', 'trash', 'digest']) $('#view-' + name).hidden = name !== b.dataset.tab;
    if (b.dataset.tab === 'trash') refreshTrash();
  });
  $('#scan-now').onclick = async () => {
    try {
      const r = await post('/scan');
      toast(r.started ? 'Scan started' : 'A scan is already running');
      setTimeout(() => { refreshStatus(); refreshSeries(); }, 1500);
    } catch (e) { toast(e.message); }
  };

  const tick = () => Promise.all([refreshStatus(), refreshSeries(), refreshTrash()]).catch(() => {});
  tick();
  window.addEventListener('dc-bulk-complete', tick);
  setInterval(tick, 30000);
  setInterval(refreshStatus, 4000);
})();
