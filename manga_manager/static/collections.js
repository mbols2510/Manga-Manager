(() => {
  const $ = (s) => document.querySelector(s);
  const API = "/api/collections";
  let collections = [], rules = [], editing = null, wasRunning = false;

  async function api(path, opts = {}) {
    const r = await fetch(API + path, { headers: { "Content-Type": "application/json" }, ...opts });
    const data = (r.headers.get("content-type") || "").includes("json") ? await r.json() : null;
    if (!r.ok) throw new Error((data && data.error) || r.statusText);
    return data;
  }
  const esc = (s) => String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
  const fmt = (iso) => (iso ? new Date(iso).toLocaleString() : "—");
  const colName = (id) => (collections.find((c) => c.id == id) || {}).title || `Collection #${id}`;
  function toast(msg) {
    const t = $("#toast");
    t.textContent = msg; t.hidden = false;
    clearTimeout(t._h); t._h = setTimeout(() => (t.hidden = true), 4000);
  }

  document.querySelectorAll("[data-tab]").forEach((b) => (b.onclick = () => {
    document.querySelectorAll("[data-tab]").forEach((x) => x.setAttribute("aria-pressed", x === b));
    document.querySelectorAll("[data-panel]").forEach((p) => (p.hidden = p.dataset.panel !== b.dataset.tab));
    if (b.dataset.tab === "history") loadHistory();
  }));

  async function loadCollections() {
    try { collections = await api("/kavita/collections"); } catch { collections = []; }
  }

  async function loadRules() {
    rules = await api("/rules");
    const el = $("#rules");
    if (!rules.length) {
      el.innerHTML = `<li class="empty">No rules yet. Add one to start filing series into collections.</li>`;
      return;
    }
    el.innerHTML = rules.map((r) => `
      <li class="rule ${r.enabled ? "" : "off"}">
        <div>
          <h3>${esc(r.name)}</h3>
          <div class="meta">Into <b>${esc(colName(r.collection_id))}</b> when ${esc(r.fields.join(" or "))} ${r.match === "exact" ? "equal" : "contain"}${r.enabled ? "" : " · disabled"}</div>
          <div class="kws">${r.keywords.map((k) => `<span class="kw">${esc(k)}</span>`).join("")}</div>
        </div>
        <div class="ops">
          <button class="btn sm" type="button" data-preview="${r.id}">Preview</button>
          <button class="btn sm" type="button" data-edit="${r.id}">Edit</button>
        </div>
      </li>`).join("");
    el.querySelectorAll("[data-edit]").forEach((b) => (b.onclick = () => openRule(rules.find((r) => r.id === b.dataset.edit))));
    el.querySelectorAll("[data-preview]").forEach((b) => (b.onclick = () => startRun(true, b.dataset.preview)));
  }

  function fillCollectionSelect(selected) {
    const opts = collections.map((c) => `<option value="${c.id}">${esc(c.title)} (#${c.id}, ${c.itemCount ?? 0} items)</option>`);
    if (selected != null && !collections.some((c) => c.id == selected)) opts.unshift(`<option value="${selected}">Collection #${selected}</option>`);
    if (!opts.length) opts.push(`<option value="">Couldn't load collections — check Kavita settings</option>`);
    $("#r-col").innerHTML = opts.join("");
    if (selected != null) $("#r-col").value = selected;
  }

  function openRule(r) {
    editing = r || null;
    $("#dlg-title").textContent = r ? "Edit rule" : "Add rule";
    $("#r-name").value = r ? r.name : "";
    $("#r-kw").value = r ? r.keywords.join(", ") : "";
    const fields = r ? r.fields : ["genres", "tags"];
    document.querySelectorAll("input[name=f]").forEach((c) => (c.checked = fields.includes(c.value)));
    $("#r-match").value = r ? r.match : "contains";
    $("#r-on").checked = r ? r.enabled : true;
    $("#r-del").hidden = !r;
    fillCollectionSelect(r ? r.collection_id : null);
    $("#dlg").showModal();
  }
  $("#add-rule").onclick = () => openRule(null);
  $("#r-cancel").onclick = () => $("#dlg").close();
  $("#r-del").onclick = async () => {
    if (!editing || !confirm(`Delete rule "${editing.name}"?`)) return;
    await api(`/rules/${editing.id}`, { method: "DELETE" });
    $("#dlg").close(); loadRules();
  };
  $("#rule-form").onsubmit = async (e) => {
    e.preventDefault();
    const body = {
      name: $("#r-name").value.trim(),
      keywords: $("#r-kw").value.split(",").map((s) => s.trim()).filter(Boolean),
      fields: [...document.querySelectorAll("input[name=f]:checked")].map((c) => c.value),
      match: $("#r-match").value,
      collection_id: Number($("#r-col").value),
      enabled: $("#r-on").checked,
    };
    try {
      await api(editing ? `/rules/${editing.id}` : "/rules", { method: editing ? "PUT" : "POST", body: JSON.stringify(body) });
      $("#dlg").close(); loadRules(); toast("Rule saved.");
    } catch (err) { toast(err.message); }
  };

  async function loadSuggestions() {
    try {
      const m = await api("/kavita/metadata");
      $("#kw-list").innerHTML = [...new Set([...m.genres, ...m.tags])].map((t) => `<option value="${esc(t)}">`).join("");
    } catch { /* suggestions are optional */ }
  }

  $("#test").onclick = async () => {
    try {
      const r = await api("/kavita/test");
      toast(`Connected: ${r.libraries} libraries, ${r.collections} collections.`);
    } catch (err) { toast("Connection failed: " + err.message); }
  };

  async function startRun(dry, ruleId) {
    try {
      await api("/run", { method: "POST", body: JSON.stringify({ dry_run: dry, rule_id: ruleId || null }) });
      toast(dry ? "Preview started…" : "Run started…");
      pollStatus();
    } catch (err) { toast(err.message); }
  }
  $("#go").onclick = () => startRun(false);
  $("#dry").onclick = () => startRun(true);

  function runHtml(h) {
    const added = h.results.reduce((n, r) => n + r.added.length, 0);
    const rows = h.results.map((r) => `<tr><td>${esc(r.rule)}</td><td>${esc(r.collection_title || colName(r.collection_id))}</td><td>${r.matched}</td>
      <td>${r.error ? `<span class="err">${esc(r.error)}</span>` : esc(r.added.map((a) => a.name).join(", ") || "—")}</td></tr>`).join("");
    return `<details class="run ${esc(h.status)}"><summary><b>${fmt(h.started)}</b>
      <span>${h.dry_run ? "Preview" : "Run"} via ${esc(h.trigger)}</span>
      <span>${h.series_scanned} scanned, ${h.dry_run ? "would add" : "added"} ${added}</span>
      <small>${h.duration_s ?? "?"}s</small></summary>
      ${h.error ? `<p class="err">${esc(h.error)}</p>` : `<table class="t"><tr><th>Rule</th><th>Collection</th><th>Matched</th><th>${h.dry_run ? "Would add" : "Added"}</th></tr>${rows}</table>`}
    </details>`;
  }
  async function loadHistory() {
    const hist = await api("/history");
    $("#history").innerHTML = hist.length ? hist.map(runHtml).join("") : `<p class="empty">No runs yet.</p>`;
  }
  $("#refresh-hist").onclick = loadHistory;

  async function pollStatus() {
    const s = await api("/status");
    $("#st-state").innerHTML = s.running ? `<span class="pulse"></span>${esc(s.progress || "Running")}` : "Idle";
    $("#st-next").textContent = s.scheduled && s.next_run ? fmt(s.next_run) : "Not scheduled";
    $("#st-libs").textContent = s.library_ids.length ? s.library_ids.map((i) => "#" + i).join(", ") : "All";
    const l = s.last_run;
    $("#st-last").textContent = l ? `${fmt(l.started)} (${l.status}${l.dry_run ? ", preview" : ""})` : "Never";
    $("#go").disabled = $("#dry").disabled = s.running;
    if (wasRunning && !s.running) {
      toast(l && l.error ? "Run failed: " + l.error : "Run finished.");
      loadHistory();
      document.querySelector("[data-tab=history]").click();
    }
    wasRunning = s.running;
    if (s.running) setTimeout(pollStatus, 1500);
  }

  (async () => {
    await loadCollections();
    loadRules(); pollStatus(); loadSuggestions();
  })();
})();
