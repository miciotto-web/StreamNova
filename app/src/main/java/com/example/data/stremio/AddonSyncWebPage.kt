package com.example.data.stremio

/**
 * Pagina HTML servita da [AddonSyncServer] su `GET /`.
 *
 * Dark theme mobile-first: campo per l'URL del manifest, pulsante di
 * installazione e lista degli addon attivi con pulsante "Rimuovi".
 * Tutte le azioni usano le API REST del micro-server (`/api/addons`).
 */
internal val ADDON_SYNC_PAGE: String = """
<!DOCTYPE html>
<html lang="it">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>StreamNova · Addon</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background: #0f172a; color: #e2e8f0;
    font-family: -apple-system, "Segoe UI", Roboto, sans-serif;
    padding: 24px 16px; min-height: 100vh;
  }
  main { max-width: 560px; margin: 0 auto; }
  h1 { color: #06b6d4; font-size: 24px; }
  .sub { color: #94a3b8; font-size: 13px; margin: 6px 0 20px; }
  .card {
    background: #1e293b; border: 1px solid #334155;
    border-radius: 14px; padding: 16px; margin-bottom: 16px;
  }
  h2 { font-size: 15px; margin-bottom: 12px; color: #e2e8f0; }
  label { display: block; font-size: 13px; color: #94a3b8; margin-bottom: 6px; }
  input {
    width: 100%; padding: 12px; border-radius: 10px;
    border: 1px solid #334155; background: #0f172a; color: #e2e8f0;
    font-size: 15px; margin-bottom: 12px;
  }
  input:focus { outline: 2px solid #06b6d4; border-color: transparent; }
  button {
    width: 100%; padding: 12px; border: none; border-radius: 10px;
    background: #06b6d4; color: #0f172a; font-size: 15px;
    font-weight: 700; cursor: pointer;
  }
  button:active { opacity: .8; }
  .status { font-size: 13px; margin-top: 10px; min-height: 18px; }
  .status.ok { color: #4ade80; }
  .status.ko { color: #f87171; }
  ul { list-style: none; }
  .row {
    display: flex; align-items: center; gap: 12px;
    padding: 10px 0; border-bottom: 1px solid #334155;
  }
  .row:last-child { border-bottom: none; }
  .info { flex: 1; min-width: 0; }
  .info strong { display: block; font-size: 14px; }
  .info span {
    display: block; font-size: 12px; color: #94a3b8;
    overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  }
  .badge {
    font-size: 11px; padding: 2px 8px; border-radius: 999px;
    background: rgba(74, 222, 128, .15); color: #4ade80;
  }
  .badge.off { background: rgba(148, 163, 184, .15); color: #94a3b8; }
  button.danger {
    width: auto; padding: 8px 14px; font-size: 13px;
    background: rgba(248, 113, 113, .15); color: #f87171;
  }
  .empty { color: #94a3b8; font-size: 13px; padding: 8px 0; }
</style>
</head>
<body>
<main>
  <h1>StreamNova</h1>
  <p class="sub">Configurazione addon da remoto</p>

  <section class="card">
    <label for="url">URL manifest Stremio</label>
    <input id="url" type="url" placeholder="https://torrentio.strem.fun/manifest.json"
           autocomplete="off" autocapitalize="off" spellcheck="false">
    <button id="install" onclick="installAddon()">Installa Addon</button>
    <p id="status" class="status"></p>
  </section>

  <section class="card">
    <h2>Addon attivi</h2>
    <ul id="list"><li class="empty">Caricamento…</li></ul>
  </section>
</main>

<script>
  var listEl = document.getElementById('list');
  var statusEl = document.getElementById('status');
  var urlEl = document.getElementById('url');

  function setStatus(msg, ok) {
    statusEl.textContent = msg;
    statusEl.className = 'status ' + (ok ? 'ok' : 'ko');
  }

  function esc(value) {
    return String(value).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function loadAddons() {
    fetch('/api/addons')
      .then(function (r) { return r.json(); })
      .then(function (addons) {
        if (!addons || !addons.length) {
          listEl.innerHTML = '<li class="empty">Nessun addon installato</li>';
          return;
        }
        listEl.innerHTML = addons.map(function (a) {
          var badge = a.enabled
            ? '<span class="badge">Attivo</span>'
            : '<span class="badge off">Disattivo</span>';
          return '<li class="row">' +
            '<div class="info"><strong>' + esc(a.name) + '</strong>' +
            '<span>' + esc(a.url) + '</span></div>' + badge +
            '<button class="danger" data-id="' + esc(a.id) + '">Rimuovi</button>' +
            '</li>';
        }).join('');
      })
      .catch(function () {
        listEl.innerHTML = '<li class="empty">Errore di caricamento</li>';
      });
  }

  function installAddon() {
    var url = urlEl.value.trim();
    if (!url) { setStatus('Incolla un URL manifest valido', false); return; }
    setStatus('Installazione in corso…', true);
    fetch('/api/addons', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ url: url })
    })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data.success) {
          setStatus('Addon installato: ' + (data.name || ''), true);
          urlEl.value = '';
          loadAddons();
        } else {
          setStatus(data.error || 'Installazione fallita', false);
        }
      })
      .catch(function () { setStatus('Errore di rete', false); });
  }

  function removeAddon(id) {
    fetch('/api/addons/' + encodeURIComponent(id), { method: 'DELETE' })
      .then(function (r) { return r.json(); })
      .then(function (data) {
        if (data.success) setStatus('Addon rimosso', true);
        else setStatus(data.error || 'Rimozione fallita', false);
        loadAddons();
      })
      .catch(function () { setStatus('Errore di rete', false); });
  }

  listEl.addEventListener('click', function (e) {
    var btn = e.target.closest('button[data-id]');
    if (btn) removeAddon(btn.getAttribute('data-id'));
  });

  loadAddons();
</script>
</body>
</html>
"""
