(() => {
  const API = window.CBZ_API;
  const $ = (s) => document.querySelector(s);
  const LONG = new Set(["Summary", "Notes", "Review"]);
  let FIELDS = [], files = [], selected = new Set(), editing = null;

  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));

  function toast(msg, ms = 3500) {
    const t = $("#toast");
    t.textContent = msg; t.hidden = false;
    clearTimeout(toast.h);
    toast.h = setTimeout(() => (t.hidden = true), ms);
  }
  async function api(path, opts) {
    const r = await fetch(API + path, opts);
    if (!r.ok) {
      let m = r.statusText;
      try { m = (await r.json()).error || m; } catch { /* not JSON */ }
      throw new Error(m);
    }
    return r;
  }
  const json = (method, body) => ({ method, headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) });

  async function load() {
    files = await (await api("/files")).json();
    selected = new Set([...selected].filter((id) => files.some((f) => f.id === id)));
    render();
  }

  function render() {
    $("#rows").innerHTML = files.map((f) => `
      <tr data-id="${f.id}"${editing === f.id ? ' class="is-open"' : ""}>
        <td class="cbz-check"><input type="checkbox" class="sel" aria-label="Select ${esc(f.name)}"${selected.has(f.id) ? " checked" : ""}></td>
        <td title="${esc(f.name)}"><button type="button" class="cbz-open"${f.error ? " disabled" : ""}>${esc(f.name)}</button></td>
        <td>${esc(f.fields.Series)}</td><td>${esc(f.fields.Number)}</td><td>${esc(f.fields.Volume)}</td><td title="${esc(f.fields.Title)}">${esc(f.fields.Title)}</td>
        <td>${f.error ? `<span class="cbz-tag err" title="${esc(f.error)}">error</span>` : f.has_info ? "" : '<span class="cbz-tag">no ComicInfo</span>'}</td>
      </tr>`).join("");
    $("#empty").hidden = files.length > 0;
    $("#count").textContent = files.length ? `${files.length} file${files.length === 1 ? "" : "s"}` : "";
    $("#selcount").textContent = selected.size ? `${selected.size} selected` : "";
    $("#all").checked = files.length > 0 && selected.size === files.length;
    $("#dl").disabled = $("#rm").disabled = selected.size === 0;
    updateBulkButtons();
  }

  $("#rows").addEventListener("click", (e) => {
    const tr = e.target.closest("tr");
    if (!tr) return;
    const id = tr.dataset.id;
    if (e.target.classList.contains("sel")) {
      e.target.checked ? selected.add(id) : selected.delete(id);
      render();
      return;
    }
    if (e.target.closest(".cbz-open")) openEditor(id);
  });
  $("#all").onchange = (e) => { selected = e.target.checked ? new Set(files.map((f) => f.id)) : new Set(); render(); };

  // ---------- upload ----------
  function upload(list) {
    list = [...list].filter((f) => /\.(cbz|zip)$/i.test(f.name));
    if (!list.length) return toast("Only .cbz files are supported");
    const fd = new FormData();
    list.forEach((f) => fd.append("files", f));
    const xhr = new XMLHttpRequest(), bar = $("#progress");
    bar.hidden = false;
    xhr.upload.onprogress = (e) => { if (e.lengthComputable) bar.firstChild.style.width = (e.loaded / e.total * 100) + "%"; };
    xhr.onload = async () => {
      bar.hidden = true; bar.firstChild.style.width = "0";
      let res;
      try { res = JSON.parse(xhr.responseText); } catch { res = null; }
      if (xhr.status !== 200 || !Array.isArray(res)) return toast("Upload failed: " + ((res && res.error) || xhr.statusText));
      res.filter((r) => !r.id).forEach((r) => toast(`${r.name}: ${r.error}`, 6000));
      res.filter((r) => r.id).forEach((r) => selected.add(r.id));
      await load();
    };
    xhr.onerror = () => { bar.hidden = true; toast("Upload failed"); };
    xhr.open("POST", API + "/upload");
    xhr.send(fd);
  }
  const drop = $("#drop");
  drop.addEventListener("keydown", (e) => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); $("#picker").click(); } });
  $("#picker").onchange = (e) => { upload(e.target.files); e.target.value = ""; };
  drop.ondragover = (e) => { e.preventDefault(); drop.classList.add("over"); };
  drop.ondragleave = () => drop.classList.remove("over");
  drop.ondrop = (e) => { e.preventDefault(); drop.classList.remove("over"); upload(e.dataTransfer.files); };

  // ---------- download / remove ----------
  $("#dl").onclick = async () => {
    const ids = [...selected];
    if (ids.length === 1) { location.href = `${API}/files/${ids[0]}/download`; return; }
    try {
      const blob = await (await api("/download", json("POST", { ids }))).blob();
      const a = document.createElement("a");
      a.href = URL.createObjectURL(blob); a.download = "edited-cbz.zip"; a.click();
      setTimeout(() => URL.revokeObjectURL(a.href), 10000);
    } catch (e) { toast(e.message); }
  };
  $("#rm").onclick = async () => {
    if (!confirm(`Remove ${selected.size} file(s) from the editor? Your originals are not touched.`)) return;
    for (const id of selected) {
      try { await api(`/files/${id}`, { method: "DELETE" }); } catch { /* already gone */ }
      if (editing === id) closeEditor();
    }
    selected.clear();
    await load();
  };

  // ---------- single editor ----------
  const dlg = $("#editor");
  function openEditor(id) {
    const f = files.find((x) => x.id === id);
    if (!f) return;
    if (f.error) return toast(f.error);
    editing = id;
    $("#edname").textContent = f.name;
    $("#edform").innerHTML = FIELDS.map((k) => {
      const v = esc(f.fields[k] || "");
      return LONG.has(k)
        ? `<label class="full">${k}<textarea name="${k}" rows="3">${v}</textarea></label>`
        : `<label>${k}<input type="text" name="${k}" value="${v}"></label>`;
    }).join("");
    if (!dlg.open) dlg.showModal();
    render();
  }
  function closeEditor() { editing = null; if (dlg.open) dlg.close(); render(); }
  $("#edclose").onclick = closeEditor;
  dlg.addEventListener("close", () => { editing = null; render(); });
  dlg.addEventListener("click", (e) => { if (e.target === dlg) closeEditor(); });
  $("#edsave").onclick = async () => {
    const f = files.find((x) => x.id === editing);
    if (!f) return;
    const changes = {};
    $("#edform").querySelectorAll("[name]").forEach((el) => {
      if (el.value !== (f.fields[el.name] || "")) changes[el.name] = el.value;
    });
    if (!Object.keys(changes).length) return toast("No changes");
    try {
      await api(`/files/${editing}`, json("PUT", { fields: changes }));
      toast("Saved");
      await load();
      openEditor(editing);
    } catch (e) { toast(e.message); }
  };

  // ---------- bulk ----------
  const fieldSelect = (def) => `<select class="f" aria-label="Field">${FIELDS.map((k) => `<option${k === def ? " selected" : ""}>${k}</option>`).join("")}</select>`;
  const OPS = {
    set: () => `<strong>Set field</strong>${fieldSelect("Series")}<span class="cbz-muted">to</span><input type="text" class="value" placeholder="Value (empty clears the field)" size="28" aria-label="Value">`,
    number: () => `<strong>Auto-number</strong>${fieldSelect("Number")}
      <label>Start <input type="text" class="start" value="1" size="4"></label>
      <label>Step <input type="text" class="step" value="1" size="3"></label>
      <label>Zero-pad <input type="number" class="pad" min="0" max="12" value="0" style="width:64px"></label>`,
    regex: () => `<strong>Regex replace</strong>${fieldSelect("Title")}
      <label>Find <input type="text" class="find" placeholder="^Vol\\.\\s*" size="20"></label>
      <label>Replace <input type="text" class="replace" placeholder="\\1" size="14"></label>
      <label><input type="checkbox" class="ic"> Ignore case</label>`,
  };
  $("#addop").onclick = () => {
    const type = $("#newop").value, div = document.createElement("div");
    div.className = "cbz-op"; div.dataset.type = type;
    div.innerHTML = OPS[type]() + '<button type="button" class="btn sm rmop" aria-label="Remove this operation">Remove</button>';
    $("#ops").append(div);
    updateBulkButtons();
  };
  $("#ops").addEventListener("click", (e) => {
    if (e.target.classList.contains("rmop")) { e.target.closest(".cbz-op").remove(); updateBulkButtons(); hidePreview(); }
  });
  $("#ops").addEventListener("input", hidePreview);
  function updateBulkButtons() {
    $("#preview").disabled = $("#apply").disabled = !(selected.size > 0 && $("#ops").children.length > 0);
  }
  function hidePreview() { $("#previewbox").hidden = true; }
  function collectOps() {
    return [...$("#ops").children].map((d) => {
      const q = (s) => d.querySelector(s), type = d.dataset.type, o = { type, field: q(".f").value };
      if (type === "set") o.value = q(".value").value;
      if (type === "number") { o.start = q(".start").value; o.step = q(".step").value; o.pad = +q(".pad").value || 0; }
      if (type === "regex") { o.find = q(".find").value; o.replace = q(".replace").value; o.ignore_case = q(".ic").checked; }
      return o;
    });
  }
  $("#preview").onclick = async () => {
    try {
      const res = await (await api("/bulk", json("POST", { ids: [...selected], ops: collectOps(), preview: true }))).json();
      const rows = res.flatMap((r) => r.changes.map((c) =>
        `<tr><td>${esc(r.name)}</td><td>${esc(c.field)}</td><td class="old">${esc(c.old) || "<em>(empty)</em>"}</td><td class="new">${esc(c.new) || "<em>(cleared)</em>"}</td></tr>`));
      $("#prevrows").innerHTML = rows.join("") || '<tr><td colspan="4" class="cbz-muted">No changes.</td></tr>';
      $("#previewbox").hidden = false;
    } catch (e) { toast(e.message); }
  };
  $("#apply").onclick = async () => {
    if (!confirm(`Apply to ${selected.size} file(s)?`)) return;
    try {
      await api("/bulk", json("POST", { ids: [...selected], ops: collectOps() }));
      toast("Applied");
      hidePreview();
      await load();
    } catch (e) { toast(e.message); }
  };

  (async () => {
    try {
      FIELDS = (await (await api("/fields")).json()).fields;
      await load();
    } catch (e) { toast(e.message); }
  })();
})();
