/* Palique admin panel — vanilla JS over the /v1/admin API. No build step. */
'use strict';

const $ = selector => document.querySelector(selector);
const NAV = [
  { id: 'overview', label: 'Resumen' },
  { id: 'growth', label: 'Crecimiento' },
  { id: 'voice', label: 'Voz' },
  { id: 'economy', label: 'Economía' },
  { id: 'browse', label: 'Tablas' },
  { id: 'policy', label: 'Política bienvenida' },
  { id: 'campaigns', label: 'Campañas' },
  { id: 'audit', label: 'Auditoría' }
];
const charts = [];
const api = async (path, options = {}) => {
  const response = await fetch(path, { headers: { 'content-type': 'application/json' }, credentials: 'same-origin', ...options });
  if (response.status === 401) { location.href = '/login.html'; throw new Error('unauthenticated'); }
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw Object.assign(new Error(body.error?.code ?? 'request_failed'), { code: body.error?.code, status: response.status });
  return body;
};
const number = value => new Intl.NumberFormat('es').format(Number(value));
const msHuman = value => {
  const total = Math.round(Number(value) / 60000);
  return `${Math.floor(total / 60)} h ${total % 60} min`;
};
const usdNano = value => `$${(Number(value) / 1e9).toFixed(2)}`;
const usdMinor = value => `$${(Number(value) / 100).toFixed(2)}`;
const dateCell = cell => { const raw = cell.getValue(); return raw ? new Date(raw).toISOString().slice(0, 16).replace('T', ' ') : '—'; };
const toast = (message, kind = 'success') => {
  const element = document.createElement('div');
  element.className = `alert alert-${kind} position-fixed top-1 end-1 m-3 shadow`;
  element.style.zIndex = '9999'; element.textContent = message;
  document.body.append(element);
  setTimeout(() => element.remove(), 4_000);
};
const openModal = id => { const element = $(id); element.classList.add('show'); element.style.display = 'block';
  document.body.classList.add('modal-open'); };
const closeModal = id => { const element = $(id); element.classList.remove('show'); element.style.display = '';
  document.body.classList.remove('modal-open'); };
const detail = (title, body) => {
  $('#detail-title').textContent = title;
  $('#detail-body').textContent = typeof body === 'string' ? body : JSON.stringify(body, null, 2);
  openModal('#detail-modal');
};
$('#detail-modal .btn-close').addEventListener('click', () => closeModal('#detail-modal'));
$('#detail-modal .modal-footer .btn').addEventListener('click', () => closeModal('#detail-modal'));
const ask = (title, placeholder, defaultValue = '') => new Promise(resolve => {
  $('#reason-title').textContent = title;
  $('#reason-input').value = defaultValue;
  $('#reason-text').value = '';
  openModal('#reason-modal');
  $('#reason-ok').onclick = () => { closeModal('#reason-modal'); resolve({ target: $('#reason-input').value, reason: $('#reason-text').value }); };
  $('#reason-cancel').onclick = () => { closeModal('#reason-modal'); resolve({ target: $('#reason-input').value, reason: '' }); };
});

/* ---- dashboards ---- */
const kpi = (label, value) => `<div class="col-sm-6 col-lg-3"><div class="card card-sm"><div class="card-body">
  <div class="subheader">${label}</div><div class="h1 mb-0">${value}</div></div></div></div>`;
const chartCard = (title, tall = false) => `<div class="col-lg-6"><div class="card"><div class="card-body">
  <h3 class="card-title">${title}</h3><div style="height:${tall ? 260 : 200}px"><canvas></canvas></div></div></div></div>`;

async function renderDashboard(setName, container) {
  const [snapshot, series] = await Promise.all([
    setName === 'overview' ? api('/v1/admin/metrics') : Promise.resolve(null),
    api(`/v1/admin/metrics/timeseries?set=${setName}&days=30`)
  ]);
  let head = '';
  if (setName === 'overview' && snapshot) {
    const revenue = snapshot.orders.reduce((total, row) => total + row.totalMinor, 0);
    const orders = snapshot.orders.reduce((total, row) => total + row.count, 0);
    head = `<div class="row mb-3">
      ${kpi('Cuentas', number(snapshot.accounts.total))}
      ${kpi('Invitados', number(snapshot.accounts.guests))}
      ${kpi('Sesiones app vivas', number(snapshot.liveAppSessions))}
      ${kpi('Activos 30 días', number(snapshot.activeAccounts30d))}
      ${kpi('Minutos en wallets', msHuman(snapshot.minuteWallets.balanceMs))}
      ${kpi('Minutos reservados', msHuman(snapshot.minuteWallets.reservedMs))}
      ${kpi('Órdenes / volumen', `${number(orders)} · ${usdMinor(revenue)}`)}
      ${kpi('Coste helpers 30d', usdNano(snapshot.helperRequests.cost30dNano))}
      ${kpi('Waitlist', number(snapshot.waitlist))}
      ${kpi('Sesiones voz activas', number(snapshot.hostedSessions['active'] ?? 0))}
      ${kpi('Acciones admin', number(snapshot.adminActions))}
      ${kpi('Informes IA 30d', number(snapshot.aiReports.last30d))}
    </div>`;
  }
  container.innerHTML = head + `<div class="row row-cards">${series.series.map(serie => chartCard(serie.label)).join('')}</div>`;
  series.series.forEach(serie => {
    const canvas = [...container.querySelectorAll('canvas')][series.series.indexOf(serie)];
    charts.push(new Chart(canvas, {
      type: 'line',
      data: { labels: serie.points.map(point => point.day.slice(5)),
        datasets: [{ label: serie.label, data: serie.points.map(point => point.value), tension: .3, fill: false, pointRadius: 2, borderWidth: 2 }] },
      options: { maintainAspectRatio: false, plugins: { legend: { display: false } },
        scales: { y: { beginAtZero: true } } }
    }));
  });
}

/* ---- browse ---- */
async function renderBrowse(container, fixedTable) {
  const catalog = await api('/v1/admin/browse');
  const tables = Object.keys(catalog.tables);
  const current = fixedTable ?? localStorage.getItem('browse.table') ?? tables[0];
  container.innerHTML = `${fixedTable ? '' : `<div class="mb-3"><select id="browse-table" class="form-select w-auto">
      ${tables.map(name => `<option value="${name}"${name === current ? ' selected' : ''}>${name}</option>`).join('')}
    </select> <span class="text-secondary ms-2" id="browse-desc"></span></div>`}
    <div id="browse-grid"></div>
    <div class="d-flex align-items-center justify-content-between mt-2">
      <span class="text-secondary" id="browse-total"></span>
      <div class="btn-group" id="browse-pager">
        <button class="btn btn-sm" id="page-prev">‹ Anterior</button>
        <span class="btn btn-sm disabled" id="page-info"></span>
        <button class="btn btn-sm" id="page-next">Siguiente ›</button>
      </div>
    </div>`;
  const state = { page: 1, size: 25, sort: null, dir: null, filters: [] };
  let table;
  const mountTable = async () => {
    const name = fixedTable ?? $('#browse-table').value;
    if (!fixedTable) { localStorage.setItem('browse.table', name); $('#browse-desc').textContent = catalog.tables[name].description; }
    const schema = catalog.tables[name];
    state.page = 1; state.sort = schema.defaultOrder.column; state.dir = schema.defaultOrder.direction; state.filters = [];
    const columns = Object.entries(schema.columns).map(([field, spec]) => {
      const base = { title: field.replaceAll('_', ' '), field,
        sorter: spec.type === 'jsonb' ? undefined : 'local',
        formatter: spec.type === 'timestamptz' ? dateCell : undefined,
        width: spec.type === 'timestamptz' ? 150 : undefined };
      if (spec.type === 'boolean') Object.assign(base, { headerFilter: 'select',
        headerFilterParams: { values: { '': 'Todos', true: 'Sí', false: 'No' } }, headerFilterFunc: '=' });
      else if (spec.type === 'text') Object.assign(base, { headerFilter: 'input', headerFilterFunc: 'like' });
      else if (spec.type === 'timestamptz') Object.assign(base, { headerFilter: 'input', headerFilterPlaceholder: '≥ AAAA-MM-DD', headerFilterFunc: '>=' });
      else if (spec.type !== 'jsonb') Object.assign(base, { headerFilter: 'input', headerFilterFunc: '=' });
      return base;
    });
    table?.destroy();
    table = new Tabulator('#browse-grid', {
      columns, layout: 'fitDataFill', placeholder: 'Sin filas', rowContextMenu: contextMenu(name)
    });
    table.on('dataSorting', sorters => {
      if (!sorters.length) return;
      state.sort = sorters[0].field; state.dir = sorters[0].dir; state.page = 1; void load();
    });
    // dataFiltered fires when a header filter is applied; the key guard prevents the
    // reload loop that setData would otherwise cause by re-applying local filters.
    let lastFilterKey = '';
    table.on('dataFiltered', () => {
      const filters = table.getFilters(true).filter(entry => entry.value !== '' && entry.value !== undefined);
      const key = JSON.stringify(filters);
      if (key === lastFilterKey) return;
      lastFilterKey = key;
      state.filters = filters; state.page = 1; void load();
    });
    await load();
  };
  const load = async () => {
    const name = fixedTable ?? $('#browse-table').value;
    const query = new URLSearchParams({ limit: String(state.size), offset: String((state.page - 1) * state.size),
      sort: state.sort, dir: state.dir });
    for (const entry of state.filters) {
      const spec = catalog.tables[name].columns[entry.field];
      const operator = spec.type === 'text' ? 'contains' : spec.type === 'timestamptz' ? 'gte' : 'eq';
      query.set(`q_${entry.field}`, `${operator}:${String(entry.value)}`);
    }
    try {
      const body = await api(`/v1/admin/browse/${name}?${query}`);
      table.setData(body.rows);
      const maxPage = Math.max(1, Math.ceil(body.total / state.size));
      state.page = Math.min(state.page, maxPage);
      $('#browse-total').textContent = `${number(body.total)} filas`;
      $('#page-info').textContent = `${state.page} / ${maxPage}`;
      $('#page-prev').disabled = state.page <= 1;
      $('#page-next').disabled = state.page >= maxPage;
    } catch (error) {
      if (error.message !== 'unauthenticated') toast(`Error: ${error.code ?? error.message}`, 'danger');
    }
  };
  $('#page-prev').onclick = () => { if (state.page > 1) { state.page--; void load(); } };
  $('#page-next').onclick = () => { state.page++; void load(); };
  if (!fixedTable) $('#browse-table').onchange = mountTable;
  await mountTable();
}

/* ---- forms ---- */
async function renderPolicy(container) {
  const policy = await api('/v1/admin/policy/welcome');
  container.innerHTML = `<div class="row"><div class="col-lg-6"><div class="card"><div class="card-body">
    <h3 class="card-title">Minutos de bienvenida</h3>
    <div class="mb-3"><label class="form-check form-switch">
      <input class="form-check-input" type="checkbox" id="pol-enabled" ${policy.welcomeEnabled ? 'checked' : ''}>
      <span class="form-check-label">Conceder minutos de bienvenida</span></label></div>
    <div class="mb-3"><label class="form-label">Minutos</label>
      <input type="number" min="0" max="1440" class="form-control" id="pol-minutes" value="${policy.welcomeMinutes}"></div>
    <div class="mb-3"><label class="form-label">Motivo (obligatorio, queda auditado)</label>
      <textarea class="form-control" id="pol-reason" rows="2" maxlength="500"></textarea></div>
    <button class="btn btn-primary" id="pol-save">Guardar</button>
  </div></div></div></div>`;
  $('#pol-save').onclick = async () => {
    try {
      await api('/v1/admin/policy/welcome', { method: 'PUT', body: JSON.stringify({
        version: policy.version, welcomeEnabled: $('#pol-enabled').checked,
        welcomeMinutes: Number($('#pol-minutes').value), reason: $('#pol-reason').value }) });
      toast('Política actualizada'); renderPolicy(container);
    } catch (error) { toast(`Error: ${error.code}`, 'danger'); }
  };
}

async function renderCampaigns(container) {
  container.innerHTML = `<div class="row"><div class="col-lg-8"><div class="card"><div class="card-body">
    <h3 class="card-title">Campaña de minutos</h3>
    <div class="mb-3"><label class="form-check">
      <input class="form-check-input" type="radio" name="aud" id="aud-one" checked> <span class="form-check-label">Un único usuario (UUID)</span></label>
      <label class="form-check"><input class="form-check-input" type="radio" name="aud" id="aud-all"> <span class="form-check-label">Todos los usuarios actuales</span></label></div>
    <div class="mb-3" id="aud-account-wrap"><label class="form-label">Cuenta</label>
      <input class="form-control font-monospace" id="camp-account" placeholder="uuid"></div>
    <div class="row"><div class="col"><label class="form-label">Minutos por usuario</label>
      <input type="number" min="1" class="form-control" id="camp-minutes" value="5"></div>
      <div class="col"><label class="form-label">Tope total (minutos)</label>
      <input type="number" min="1" class="form-control" id="camp-cap" value="5000"></div></div>
    <div class="mb-3 mt-3"><label class="form-label">Motivo (obligatorio)</label>
      <textarea class="form-control" id="camp-reason" rows="2" maxlength="500"></textarea></div>
    <button class="btn btn-primary" id="camp-prepare">Preparar</button>
    <div id="camp-result" class="mt-3"></div>
  </div></div></div></div>`;
  for (const radio of ['#aud-one', '#aud-all']) $(radio).onchange = () => { $('#aud-account-wrap').style.display = $('#aud-one').checked ? '' : 'none'; };
  $('#camp-prepare').onclick = async () => {
    try {
      const summary = await api('/v1/admin/campaigns', { method: 'POST', body: JSON.stringify({
        audience: $('#aud-one').checked ? [$('#camp-account').value.trim()] : 'all-current-users',
        minutesPerUser: Number($('#camp-minutes').value), maxTotalMinutes: Number($('#camp-cap').value),
        reason: $('#camp-reason').value }) });
      $('#camp-result').innerHTML = `<div class="alert alert-info">Preparada: ${summary.recipients} destinatarios,
        ${summary.minutesPerUser} min/usuario. Confirmación <code class="small">${summary.confirmation}</code></div>
        <button class="btn btn-warning" id="camp-apply">Aplicar (irreversible)</button>`;
      $('#camp-apply').onclick = async () => {
        try {
          const outcome = await api(`/v1/admin/campaigns/${summary.campaignID}/apply`, { method: 'POST',
            body: JSON.stringify({ confirmation: summary.confirmation, reason: $('#camp-reason').value }) });
          $('#camp-result').innerHTML += `<div class="alert alert-success mt-2">Aplicada: concedidos ${outcome.granted},
            omitidos ${outcome.skipped}.</div>`;
        } catch (error) { toast(`Error: ${error.code}`, 'danger'); }
      };
    } catch (error) { toast(`Error: ${error.code}`, 'danger'); }
  };
}

/* ---- row actions ---- */
function contextMenu(tableName) {
  if (tableName === 'accounts') return [
    { label: 'Detalle de cuenta', action: (event, row) => api(`/v1/admin/accounts/${row.getData().id}`)
        .then(report => detail(`Cuenta ${row.getData().id}`, report)).catch(error => toast(`Error: ${error.code}`, 'danger')) },
    { label: 'Regalar minutos…', action: async (event, row) => {
        const asked = await ask('Campaña de minutos', 'Motivo');
        if (!asked.reason || asked.reason.trim().length < 3) return toast('Motivo obligatorio', 'warning');
        try { const summary = await api('/v1/admin/campaigns', { method: 'POST', body: JSON.stringify({
            audience: [row.getData().id], minutesPerUser: 5, maxTotalMinutes: 60, reason: asked.reason }) });
          await api(`/v1/admin/campaigns/${summary.campaignID}/apply`, { method: 'POST',
            body: JSON.stringify({ confirmation: summary.confirmation, reason: asked.reason }) });
          toast(`Regalados 5 minutos a ${row.getData().email ?? row.getData().id}`);
        } catch (error) { toast(`Error: ${error.code}`, 'danger'); } } }
  ];
  if (tableName === 'hosted_sessions') return [
    { label: 'Forzar cierre…', action: async (event, row) => {
        const data = row.getData();
        if (data.state === 'closed') return toast('Ya está cerrada', 'warning');
        const asked = await ask('Forzar cierre de sesión', 'Motivo');
        if (!asked.reason || asked.reason.trim().length < 3) return toast('Motivo obligatorio', 'warning');
        try { await api(`/v1/admin/hosted-sessions/${data.id}/close`, { method: 'POST',
          body: JSON.stringify({ reason: asked.reason }) }); toast('Cierre solicitado'); row.update({ state: 'closing' }); }
        catch (error) { toast(`Error: ${error.code}`, 'danger'); } } }
  ];
  if (tableName === 'access_requests') return [
    { label: 'Eliminar de la waitlist…', action: async (event, row) => {
        const asked = await ask('Eliminar de la waitlist', 'Motivo');
        if (!asked.reason || asked.reason.trim().length < 3) return toast('Motivo obligatorio', 'warning');
        try { await api('/v1/admin/access-requests', { method: 'DELETE',
          body: JSON.stringify({ email: row.getData().email, reason: asked.reason }) });
          toast('Eliminada'); row.delete(); }
        catch (error) { toast(`Error: ${error.code}`, 'danger'); } } }
  ];
  return undefined;
}

/* ---- router ---- */
const VIEWS = {
  overview: { title: 'Resumen', render: container => renderDashboard('overview', container) },
  growth: { title: 'Crecimiento', render: container => renderDashboard('growth', container) },
  voice: { title: 'Voz', render: container => renderDashboard('voice', container) },
  economy: { title: 'Economía', render: container => renderDashboard('economy', container) },
  browse: { title: 'Tablas', render: container => renderBrowse(container, null) },
  policy: { title: 'Política de bienvenida', render: renderPolicy },
  campaigns: { title: 'Campañas', render: renderCampaigns },
  audit: { title: 'Auditoría de acciones', render: container => renderBrowse(container, 'admin_actions') }
};
async function route() {
  const viewName = (location.hash.slice(1) || 'overview').split('?')[0];
  const view = VIEWS[viewName] ?? VIEWS.overview;
  $('#page-title').textContent = view.title;
  for (const chart of charts) { if (chart instanceof Chart) chart.destroy(); else chart.destroy(); }
  charts.length = 0;
  const container = $('#view');
  container.innerHTML = '<div class="progress progress-sm"><div class="progress-bar progress-bar-indeterminate"></div></div>';
  try { await view.render(container); }
  catch (error) { if (error.message !== 'unauthenticated') container.innerHTML = `<div class="alert alert-danger">${error.code ?? error.message}</div>`; }
}
(function boot() {
  $('#nav').innerHTML = NAV.map(item => `<li class="nav-item"><a class="nav-link" href="#${item.id}">${item.label}</a></li>`).join('');
  api('/v1/admin/me').then(me => { $('#whoami').textContent = me.email ?? me.adminID; })
    .catch(() => {});
  $('#signout').onclick = async () => { try { await api('/v1/admin/auth/sign-out', { method: 'POST', body: '{}' }); } catch {} location.href = '/login.html'; };
  window.addEventListener('hashchange', route);
  route();
})();
