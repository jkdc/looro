/* Palique admin panel — vanilla JS over the /v1/admin API. No build step. */
'use strict';

const $ = selector => document.querySelector(selector);
const NAV = [
  { id: 'overview', label: 'Resumen', icon: 'ti-chart-bar' },
  { id: 'growth', label: 'Crecimiento', icon: 'ti-trending-up' },
  { id: 'voice', label: 'Voz', icon: 'ti-microphone' },
  { id: 'economy', label: 'Economía', icon: 'ti-coins' },
  { id: 'usage', label: 'Uso', icon: 'ti-activity' },
  { id: 'browse', label: 'Tablas', icon: 'ti-table' },
  { id: 'policy', label: 'Política bienvenida', icon: 'ti-settings' },
  { id: 'level-tests', label: 'Generación de exámenes', icon: 'ti-book' },
  { id: 'exam-results', label: 'Resultados de exámenes', icon: 'ti-clipboard-check' },
  { id: 'plans', label: 'Planes', icon: 'ti-crown' },
  { id: 'campaigns', label: 'Campañas', icon: 'ti-gift' },
  { id: 'audit', label: 'Auditoría', icon: 'ti-shield-check' }
];
const PALETTE = ['#206bc4', '#2fb344', '#f76707', '#a55aad', '#d63939', '#429ade'];
const CHART_TYPE = { orders: 'bar', revenue_minor: 'bar', minutes_granted_ms: 'bar', minutes_consumed_ms: 'bar' };
const charts = [];
let tables = [];
const api = async (path, options = {}) => {
  const response = await fetch(path, { headers: { 'content-type': 'application/json' }, credentials: 'same-origin', ...options });
  if (response.status === 401) { location.href = '/login.html'; throw new Error('unauthenticated'); }
  const body = await response.json().catch(() => ({}));
  if (!response.ok) throw Object.assign(new Error(body.error?.code ?? 'request_failed'), { code: body.error?.code, status: response.status });
  return body;
};
const number = value => new Intl.NumberFormat('es').format(Number(value));
const msHuman = value => {
  const minutes = Math.round(Number(value) / 60000);
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  if (hours < 72) return `${hours} h ${minutes % 60} min`;
  return `${Math.round(hours / 24)} días`;
};
const usdNano = value => `$${(Number(value) / 1e9).toLocaleString('es', { maximumFractionDigits: 2 })}`;
const usdMinor = value => `$${(Number(value) / 100).toLocaleString('es', { minimumFractionDigits: 2 })}`;
const escapeHtml = value => String(value).replace(/[&<>"']/g, character =>
  ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character]);
const dateCell = cell => {
  const raw = cell.getValue();
  if (!raw) return '<span class="text-secondary">—</span>';
  return `<span title="${escapeHtml(raw)}">${new Date(raw).toISOString().slice(0, 16).replace('T', ' ')}</span>`;
};
const moneyCell = cell => cell.getValue() === null ? '<span class="text-secondary">—</span>' : usdMinor(cell.getValue());
const nanoCell = cell => cell.getValue() === null ? '<span class="text-secondary">—</span>' : usdNano(cell.getValue());
const durationCell = cell => cell.getValue() === null ? '<span class="text-secondary">—</span>' : msHuman(cell.getValue());
const BADGE_COLORS = {
  active: 'success', closing: 'warning', closed: 'secondary', creating: 'info', incomplete: 'danger',
  prepared: 'info', applied: 'success', test: 'warning', live: 'success', pending: 'info', settled: 'success',
  open: 'info', granted: 'success', skipped: 'secondary', account_deleted: 'secondary', operator_close: 'danger',
  user_requested: 'secondary', cancelled_before_provider: 'secondary', minutes: 'azure', ai_value: 'purple',
  stripe: 'indigo', play: 'green', welcome: 'teal', gift: 'orange', purchase: 'azure', reserve: 'secondary',
  settle: 'secondary', release: 'secondary', forfeit: 'danger', transfer: 'secondary'
};
const badgeCell = cell => {
  const raw = cell.getValue();
  if (raw === null || raw === undefined || raw === '') return '<span class="text-secondary">—</span>';
  const tone = BADGE_COLORS[String(raw)] ?? 'secondary';
  return `<span class="badge badge-${tone}">${escapeHtml(String(raw))}</span>`;
};
const BADGED_COLUMNS = new Set(['state', 'kind', 'close_reason', 'environment', 'entitlement_kind', 'purpose',
  'provider', 'reason', 'funding_mode', 'outcome']);
const uuidCell = cell => {
  const raw = cell.getValue();
  if (!raw) return '<span class="text-secondary">—</span>';
  const text = String(raw);
  return `<span class="cell-mono" title="${escapeHtml(text)}">${escapeHtml(text.slice(0, 8))}…</span>`;
};
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
$('#detail-close').addEventListener('click', () => closeModal('#detail-modal'));
$('#detail-dismiss').addEventListener('click', () => closeModal('#detail-modal'));
const ask = (title, target) => new Promise(resolve => {
  $('#reason-title').textContent = title;
  $('#reason-input').value = target;
  $('#reason-text').value = '';
  openModal('#reason-modal');
  $('#reason-ok').onclick = () => { closeModal('#reason-modal'); resolve({ reason: $('#reason-text').value }); };
  $('#reason-cancel').onclick = () => { closeModal('#reason-modal'); resolve({ reason: '' }); };
});

/* ---- dashboards ---- */
const KPI_ICONS = {
  'Cuentas': ['ti-users', 'bg-blue-lt text-blue'], 'Invitados': ['ti-user-question', 'bg-purple-lt text-purple'],
  'Sesiones app vivas': ['ti-plug-connected', 'bg-azure-lt text-azure'], 'Activos 30 días': ['ti-activity', 'bg-green-lt text-green'],
  'Minutos en wallets': ['ti-clock-hour-4', 'bg-orange-lt text-orange'], 'Minutos reservados': ['ti-clock-pause', 'bg-yellow-lt text-yellow'],
  'Órdenes / volumen': ['ti-shopping-cart', 'bg-indigo-lt text-indigo'], 'Coste helpers 30d': ['ti-lifebuoy', 'bg-pink-lt text-pink'],
  'Waitlist': ['ti-mail', 'bg-teal-lt text-teal'], 'Sesiones voz activas': ['ti-phone-outgoing', 'bg-red-lt text-red'],
  'Acciones admin': ['ti-shield-check', 'bg-lime-lt text-lime'], 'Informes IA 30d': ['ti-message-report', 'bg-cyan-lt text-cyan'],
  'Suscripciones activas': ['ti-crown', 'bg-yellow-lt text-yellow'], 'En prueba': ['ti-flask', 'bg-indigo-lt text-indigo'],
  'DAU (hoy)': ['ti-user-check', 'bg-green-lt text-green'], 'WAU': ['ti-users-group', 'bg-blue-lt text-blue'],
  'MAU': ['ti-users-plus', 'bg-azure-lt text-azure'], 'Eventos 7 días': ['ti-chart-dots', 'bg-lime-lt text-lime']
};
const kpi = (label, value) => {
  const [icon, tone] = KPI_ICONS[label] ?? ['ti-dots', 'bg-secondary-lt text-secondary'];
  return `<div class="col-sm-6 col-lg-3"><div class="card card-sm"><div class="card-body">
    <div class="row align-items-center g-0">
      <div class="col-auto"><span class="kpi-icon ${tone}"><i class="ti ${icon}"></i></span></div>
      <div class="col ps-3"><div class="subheader text-secondary">${label}</div>
      <div class="h2 mb-0 mt-1">${value}</div></div>
    </div></div></div></div>`;
};
const chartCard = (title, type) => `<div class="col-lg-6"><div class="card chart-card"><div class="card-body">
  <h3 class="card-title d-flex align-items-center gap-2"><i class="ti ${type === 'bar' ? 'ti-chart-column' : 'ti-chart-line'} text-secondary"></i>${title}</h3>
  <div class="chart-box"><canvas></canvas></div></div></div></div>`;

function mountChart(canvas, serie) {
  const color = PALETTE[charts.length % PALETTE.length];
  const context = canvas.getContext('2d');
  const gradient = context.createLinearGradient(0, 0, 0, 220);
  gradient.addColorStop(0, color + '33'); gradient.addColorStop(1, color + '05');
  const type = CHART_TYPE[serie.name] ?? 'line';
  charts.push(new Chart(canvas, {
    type,
    data: { labels: serie.points.map(point => point.day.slice(5)),
      datasets: [{ label: serie.label, data: serie.points.map(point => point.value), tension: .35,
        fill: type === 'line', backgroundColor: type === 'bar' ? color + 'cc' : gradient,
        borderColor: color, borderWidth: 2, pointRadius: 0, pointHoverRadius: 4,
        borderRadius: type === 'bar' ? 3 : undefined, maxBarThickness: 28 }] },
    options: { maintainAspectRatio: false, interaction: { mode: 'index', intersect: false },
      plugins: { legend: { display: false },
        tooltip: { backgroundColor: '#1d273b', padding: 10, displayColors: false } },
      scales: { x: { grid: { display: false }, ticks: { font: { size: 10 }, maxRotation: 0, autoSkip: true, maxTicksLimit: 10 } },
        y: { beginAtZero: true, grid: { color: 'rgba(160,170,180,.15)' }, ticks: { font: { size: 10 }, maxTicksLimit: 6 } } } }
  }));
}

async function renderDashboard(setName, container) {
  const [snapshot, series] = await Promise.all([
    setName === 'overview' ? api('/v1/admin/metrics') : Promise.resolve(null),
    api(`/v1/admin/metrics/timeseries?set=${setName}&days=30`)
  ]);
  let head = '';
  if (setName === 'overview' && snapshot) {
    const revenue = snapshot.orders.reduce((total, row) => total + row.totalMinor, 0);
    const orders = snapshot.orders.reduce((total, row) => total + row.count, 0);
    head = `<div class="row mb-3 g-3">
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
      ${kpi('Suscripciones activas', number(snapshot.subscriptions.active + snapshot.subscriptions.trialing))}
      ${kpi('En prueba', number(snapshot.subscriptions.trialing))}
      ${kpi('Acciones admin', number(snapshot.adminActions))}
      ${kpi('Informes IA 30d', number(snapshot.aiReports.last30d))}
    </div>`;
  }
  container.innerHTML = head + `<div class="row row-cards g-3">${series.series.map(serie => chartCard(serie.label, CHART_TYPE[serie.name] ?? 'line')).join('')}</div>`;
  container.querySelectorAll('.chart-box canvas').forEach((canvas, index) => mountChart(canvas, series.series[index]));
  // Charts are created while the grid is still settling; re-measure once it has laid out.
  requestAnimationFrame(() => { for (const chart of charts) chart.resize(); });
}

/* ---- browse ---- */
function columnFormatter(name, spec) {
  if (spec.type === 'timestamptz') return dateCell;
  if (spec.type === 'jsonb') return cell => {
    const raw = cell.getValue();
    if (raw === null || raw === undefined) return '<span class="text-secondary">—</span>';
    const text = JSON.stringify(raw);
    return `<span class="cell-mono" title="${escapeHtml(text)}">${escapeHtml(text.slice(0, 24))}${text.length > 24 ? '…' : ''}</span>`;
  };
  if (spec.type === 'boolean') return cell => cell.getValue() ? '<i class="ti ti-circle-check text-success"></i>' :
    '<i class="ti ti-circle-minus text-secondary"></i>';
  if (spec.type === 'uuid') return uuidCell;
  if (/_minor$/.test(name)) return moneyCell;
  if (/_nano$/.test(name)) return nanoCell;
  if (/_ms$/.test(name)) return durationCell;
  if (BADGED_COLUMNS.has(name)) return badgeCell;
  return undefined;
}
async function renderBrowse(container, fixedTable) {
  const catalog = await api('/v1/admin/browse');
  const tables = Object.keys(catalog.tables);
  const current = fixedTable ?? localStorage.getItem('browse.table') ?? tables[0];
  container.innerHTML = `
    ${fixedTable ? '' : `<div class="mb-3 d-flex align-items-center gap-3">
      <select id="browse-table" class="form-select w-auto" data-icon="table"></select>
      <span class="text-secondary" id="browse-desc"></span>
    </div>`}
    <div class="card table-card">
      <div class="card-header py-2 d-flex align-items-center justify-content-between">
        <span class="text-secondary small"><i class="ti ti-database me-1"></i>${escapeHtml(fixedTable ?? current)}</span>
        <span class="text-secondary small" id="browse-total"></span>
      </div>
      <div class="card-body"><div id="browse-grid"></div></div>
      <div class="card-footer py-2 pager-bar">
        <div class="btn-group">
          <button class="btn btn-sm" id="page-prev"><i class="ti ti-chevron-left"></i></button>
          <span class="btn btn-sm disabled" id="page-info"></span>
          <button class="btn btn-sm" id="page-next"><i class="ti ti-chevron-right"></i></button>
        </div>
        <select class="form-select form-select-sm w-auto" id="page-size">
          ${[10, 25, 50, 100].map(size => `<option${size === 25 ? ' selected' : ''}>${size}</option>`).join('')}
        </select>
      </div>
    </div>`;
  if (!fixedTable) {
    $('#browse-table').innerHTML = tables.map(name => `<option value="${name}"${name === current ? ' selected' : ''}>${name}</option>`).join('');
    $('#browse-desc').textContent = catalog.tables[current].description;
  }
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
        formatter: columnFormatter(field, spec),
        width: spec.type === 'timestamptz' ? 140 : spec.type === 'uuid' ? 110 : undefined };
      if (spec.type === 'boolean') Object.assign(base, { headerFilter: 'select', headerFilterFunc: '=',
        headerFilterParams: { values: { '': 'todos', true: 'sí', false: 'no' } } });
      else if (spec.type === 'text') Object.assign(base, { headerFilter: 'input', headerFilterFunc: 'like' });
      else if (spec.type === 'timestamptz') Object.assign(base, { headerFilter: 'input', headerFilterFunc: '>=',
        headerFilterPlaceholder: '≥ AAAA-MM-DD' });
      else if (spec.type !== 'jsonb') Object.assign(base, { headerFilter: 'input', headerFilterFunc: '=' });
      return base;
    });
    table?.destroy();
    table = new Tabulator('#browse-grid', {
      columns, layout: 'fitDataFill', placeholder: `<div class="empty p-3"><div class="empty-icon"><i class="ti ti-database-off"></i></div>
        <p class="empty-title">Sin filas</p><p class="empty-subtitle text-secondary">Ajusta los filtros o cambia de tabla.</p></div>`,
      rowContextMenu: contextMenu(name)
    });
    tables.push(table);
    let lastFilterKey = '';
    table.on('dataFiltered', () => {
      const filters = table.getFilters(true).filter(entry => entry.value !== '' && entry.value !== undefined);
      const key = JSON.stringify(filters);
      if (key === lastFilterKey) return;
      lastFilterKey = key;
      state.filters = filters; state.page = 1; void load();
    });
    table.on('dataSorting', sorters => {
      if (!sorters.length) return;
      state.sort = sorters[0].field; state.dir = sorters[0].dir; state.page = 1; void load();
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
  $('#page-size').onchange = event => { state.size = Number(event.target.value); state.page = 1; void load(); };
  if (!fixedTable) $('#browse-table').onchange = mountTable;
  await mountTable();
}

async function renderLevelTestOperations(container, view = {}) {
  // ISO week id mirroring the server's weekIndex(): year * 100 + ISO week.
  const now = new Date();
  const monday = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const dayNum = monday.getUTCDay() || 7;
  monday.setUTCDate(monday.getUTCDate() + 4 - dayNum);
  const yearStart = new Date(Date.UTC(monday.getUTCFullYear(), 0, 1));
  const currentWeekId = monday.getUTCFullYear() * 100 + Math.ceil(((monday.getTime() - yearStart.getTime()) / 86_400_000 + 1) / 7);
  const filters = view.filters ?? {};
  const params = new URLSearchParams({ limit: '50', offset: String(view.offset ?? 0) });
  for (const [key, value] of Object.entries(filters)) if (value) params.set(key, String(value));
  const [config, status, weeks] = await Promise.all([
    api('/v1/admin/llm-config'), api('/v1/admin/level-tests/status'),
    api('/v1/admin/level-tests/weeks?' + params)
  ]);
  const providers = config.providers.map(item =>
    '<option value="' + escapeHtml(item.provider) + '"' + (item.provider === config.provider ? ' selected' : '') + '>' + escapeHtml(item.provider) + '</option>').join('');
  const keyPlaceholder = config.keyHint ? 'Guardada: ' + escapeHtml(config.keyHint) + ' · vacío conserva la clave' : 'Sin clave persistida';
  const job = status.latestJob;
  const jobHtml = job
    ? '<hr><strong>Último job:</strong> ' + escapeHtml(job.status) + ' · ' + escapeHtml(job.languageID) + ' ' + escapeHtml(job.level) +
      ' · semana ' + escapeHtml(job.weekId) + '<div class="text-secondary small">Actualizado: ' + escapeHtml(job.updatedAt || '—') +
      ' · intentos: ' + escapeHtml(job.attempts) + ' · error: ' + escapeHtml(job.errorCode || '—') + '</div>'
    : '<hr><div class="text-secondary">Todavía no hay jobs registrados.</div>';
  const rowCells = item => '<tr><td>' + escapeHtml(item.weekId) + '</td><td>' + escapeHtml(item.languageID) +
    '</td><td>' + escapeHtml(item.level) + '</td><td>' + badgeCell({ getValue: () => item.status }) +
    '</td><td>' + escapeHtml(item.variantCount) + '</td><td>' + escapeHtml(item.model || '—') + '</td><td>' +
    escapeHtml((item.createdAt || '—').slice(0, 16).replace('T', ' ')) + '</td><td>' +
    escapeHtml((item.approvedAt || '—').slice(0, 16).replace('T', ' ')) + '</td><td class="text-end">' +
    '<button class="btn btn-sm btn-outline-secondary me-1" data-week-detail="' + escapeHtml(item.id) + '">Ver</button>' +
    (item.status === 'draft' ?
      '<button class="btn btn-sm btn-success me-1" data-week-approve="' + escapeHtml(item.id) + '">Aprobar</button>' +
      '<button class="btn btn-sm btn-outline-danger" data-week-reject="' + escapeHtml(item.id) + '">Rechazar</button>' : '') +
    (item.weekId === currentWeekId && ['draft', 'rejected'].includes(item.status) ?
      '<button class="btn btn-sm btn-outline-warning ms-1" data-week-regenerate="' + escapeHtml(item.languageID) +
      ':' + escapeHtml(item.level) + '">Regenerar</button>' : '') +
    '</td></tr>';
  const rows = weeks.items.map(rowCells).join('') ||
    '<tr><td colspan="9" class="text-secondary">No hay exámenes para este filtro.</td></tr>';
  const LANGS = ['en', 'es', 'fr', 'de', 'it', 'pt', 'nb', 'zh'];
  const LEVELS = ['A1', 'A2', 'B1', 'B2', 'C1'];
  const STATES = ['draft', 'approved', 'rejected', 'retired'];
  const option = (value, label, current) =>
    '<option value="' + escapeHtml(value) + '"' + (value === current ? ' selected' : '') + '>' + escapeHtml(label) + '</option>';
  const filterBar = `<div class="row g-2 mb-2">
    <div class="col-auto"><select class="form-select form-select-sm" id="wf-status">${option('', 'Estado: todos', filters.status)}${STATES.map(state => option(state, state, filters.status)).join('')}</select></div>
    <div class="col-auto"><select class="form-select form-select-sm" id="wf-language">${option('', 'Idioma: todos', filters.language)}${LANGS.map(lang => option(lang, lang, filters.language)).join('')}</select></div>
    <div class="col-auto"><select class="form-select form-select-sm" id="wf-level">${option('', 'Nivel: todos', filters.level)}${LEVELS.map(level => option(level, level, filters.level)).join('')}</select></div>
    <div class="col-auto"><input class="form-control form-control-sm" id="wf-week-from" placeholder="Semana ≥" value="${escapeHtml(filters.week_from ?? '')}"></div>
    <div class="col-auto"><input class="form-control form-control-sm" id="wf-week-to" placeholder="Semana ≤" value="${escapeHtml(filters.week_to ?? '')}"></div>
    <div class="col-auto"><input class="form-control form-control-sm" id="wf-created-from" placeholder="Creado ≥ AAAA-MM-DD" value="${escapeHtml(filters.created_from ?? '')}"></div>
    <div class="col-auto"><input class="form-control form-control-sm" id="wf-created-to" placeholder="Creado ≤ AAAA-MM-DD" value="${escapeHtml(filters.created_to ?? '')}"></div>
    <div class="col-auto"><button class="btn btn-sm btn-primary" id="wf-apply">Filtrar</button></div>
  </div>`;
  container.innerHTML = `<div class="row row-cards">
    <div class="col-lg-7"><div class="card"><div class="card-header"><h3 class="card-title">Configuración efectiva</h3></div><div class="card-body">
      <div class="alert alert-info">Los valores proceden del servidor. La prueba usa solo la configuración guardada.</div>
      <div class="row g-3">
        <div class="col-md-6"><label class="form-label" for="lt-provider">Proveedor</label><select class="form-select" id="lt-provider">${providers}</select></div>
        <div class="col-md-6"><label class="form-label" for="lt-model">Modelo</label><span id="lt-model-wrap"></span></div>
        <div class="col-12"><label class="form-label" for="lt-base-url">URL base</label><input class="form-control" id="lt-base-url" maxlength="500" value="${escapeHtml(config.baseUrl)}"></div>
        <div class="col-12"><label class="form-label" for="lt-api-key">Clave API</label><input class="form-control" id="lt-api-key" type="password" autocomplete="new-password" placeholder="${keyPlaceholder}"></div>
        <div class="col-md-4"><label class="form-label" for="lt-publish">Publicación</label><select class="form-select" id="lt-publish"><option value="review" ${config.publishMode === 'review' ? 'selected' : ''}>Revisión</option><option value="auto" ${config.publishMode === 'auto' ? 'selected' : ''}>Automática</option></select></div>
        <div class="col-md-4"><label class="form-label" for="lt-verify">Verificación</label><select class="form-select" id="lt-verify"><option value="strict" ${config.verifyMode === 'strict' ? 'selected' : ''}>Estricta</option><option value="flag" ${config.verifyMode === 'flag' ? 'selected' : ''}>Marcar</option><option value="off" ${config.verifyMode === 'off' ? 'selected' : ''}>Desactivada</option></select></div>
        <div class="col-md-4"><label class="form-label" for="lt-variants">Variantes</label><input class="form-control" id="lt-variants" type="number" min="1" max="5" value="${escapeHtml(config.variants)}"></div>
      </div>
      <div class="mt-3 d-flex flex-wrap gap-2"><button class="btn btn-primary" id="lt-save">Guardar y recargar</button><button class="btn btn-outline-primary" id="lt-test" ${config.keyHint ? '' : 'disabled'}>Probar conexión guardada</button><span class="align-self-center fw-bold" id="lt-test-result"></span><span class="text-secondary align-self-center" id="lt-dirty">La prueba solo utiliza la configuración guardada.</span></div>
    </div></div></div>
    <div class="col-lg-5"><div class="card"><div class="card-header"><h3 class="card-title">Scheduler y última generación</h3></div><div class="card-body">
      <div class="d-flex justify-content-between"><span>Configurado</span><strong>${status.schedule.enabled ? 'Sí' : 'No'}</strong></div>
      <div class="d-flex justify-content-between"><span>Scheduler activo en este proceso</span><strong>${status.scheduler.activeInProcess ? 'Sí' : 'No'}</strong></div>
      <div class="text-secondary small mt-2">La actividad local no indica el estado de otras réplicas.</div>${jobHtml}
      <div id="jobs-monitor" class="mt-2"></div>
    </div></div></div>
    <div class="col-12"><div class="card" id="exam-weeks-card"><div class="card-header"><h3 class="card-title">Exámenes generados</h3></div><div class="card-body">${filterBar}
    <div class="table-responsive"><table class="table table-vcenter card-table"><thead><tr><th>Semana</th><th>Idioma</th><th>Nivel</th><th>Estado</th><th>Variantes</th><th>Modelo</th><th>Creado</th><th>Aprobado</th><th></th></tr></thead><tbody>${rows}</tbody></table></div></div><div class="card-footer d-flex justify-content-between align-items-center">
      <button class="btn btn-sm btn-outline-secondary" id="weeks-prev" ${weeks.offset === 0 ? 'disabled' : ''}>Anterior</button>
      <span class="text-secondary small">Exámenes ${weeks.total ? weeks.offset + 1 : 0}–${weeks.offset + weeks.items.length} de ${weeks.total}</span>
      <button class="btn btn-sm btn-outline-secondary" id="weeks-next" ${weeks.items.length < weeks.limit ? 'disabled' : ''}>Siguiente</button>
    </div></div></div></div>`;
  const catalogFor = name => config.providers.find(item => item.provider === name);
  const modelOptions = (models, selected) => {
    const list = models.includes(selected) || !selected ? models : [...models, selected];
    return list.map(model => `<option value="${escapeHtml(model)}"${model === selected ? ' selected' : ''}>${escapeHtml(model)}</option>`).join('');
  };
  const renderProviderForm = (selectedModel, resetBaseUrl = false) => {
    const entry = catalogFor($('#lt-provider').value);
    if (resetBaseUrl) $('#lt-base-url').value = entry?.baseUrl ?? '';
    $('#lt-base-url').readOnly = Boolean(entry?.baseUrl ?? '');
    $('#lt-model-wrap').innerHTML = entry?.models?.length
      ? `<select class="form-select" id="lt-model">${modelOptions(entry.models, selectedModel)}</select>`
      : `<input class="form-control" id="lt-model" maxlength="200" value="${escapeHtml(selectedModel)}">`;
    $('#lt-model')?.addEventListener('input', () => refreshDirty());
    $('#lt-model')?.addEventListener('change', () => refreshDirty());
  };
  renderProviderForm(config.model);
  $('#lt-provider').addEventListener('change', () => {
    const entry = catalogFor($('#lt-provider').value);
    const current = $('#lt-model').value;
    const next = entry?.models?.includes(current) ? current : entry?.models?.[0] ?? '';
    renderProviderForm(next, true);
    refreshDirty();
  });
  const fields = ['provider', 'base-url', 'api-key', 'publish', 'verify', 'variants'];
  const formValue = () => ({ provider: $('#lt-provider').value, model: $('#lt-model').value, baseUrl: $('#lt-base-url').value,
    apiKey: $('#lt-api-key').value, publishMode: $('#lt-publish').value, verifyMode: $('#lt-verify').value, variants: Number($('#lt-variants').value) });
  const initial = { provider: config.provider, model: config.model, baseUrl: config.baseUrl,
    publishMode: config.publishMode, verifyMode: config.verifyMode, variants: config.variants };
  const isDirty = () => { const value = formValue(); return Boolean(value.apiKey) || Object.keys(initial).some(key => value[key] !== initial[key]); };
  const refreshDirty = () => {
    const dirty = isDirty();
    $('#lt-test').disabled = !config.keyHint;
    const note = $('#lt-dirty');
    if (dirty) {
      const pending = weeks.items.filter(item => item.status === 'draft').length;
      note.innerHTML = `<a href="#" id="lt-dirty-link" class="text-warning">Hay cambios sin guardar · ${pending} exámenes por aprobar</a>`;
      $('#lt-dirty-link').addEventListener('click', event => {
        event.preventDefault();
        $('#exam-weeks-card').scrollIntoView({ behavior: 'smooth' });
      });
    } else { note.textContent = 'La prueba solo utiliza la configuración guardada y no genera exámenes.'; }
  };
  fields.forEach(field => { const input = $('#lt-' + field); input.addEventListener('input', refreshDirty); input.addEventListener('change', refreshDirty); });
  $('#lt-save').onclick = async () => {
    const value = formValue(), payload = { provider: value.provider, model: value.model, baseUrl: value.baseUrl,
      publishMode: value.publishMode, verifyMode: value.verifyMode, variants: value.variants };
    if (value.apiKey) payload.apiKey = value.apiKey;
    const button = $('#lt-save'); button.disabled = true;
    try { await api('/v1/admin/llm-config', { method: 'PUT', body: JSON.stringify(payload) });
      toast('Configuración guardada'); await renderLevelTestOperations(container); }
    catch (error) { toast('Error: ' + (error.code || error.message), 'danger'); button.disabled = false; }
  };
  const showTestResult = (message, ok) => {
    const result = $('#lt-test-result');
    result.className = `align-self-center fw-bold ${ok ? 'text-success' : 'text-danger'}`;
    result.textContent = message;
    toast(message, ok ? 'success' : 'danger');
  };
  $('#lt-test').onclick = async () => {
    if (!config.keyHint) return refreshDirty();
    const button = $('#lt-test'); button.disabled = true; button.textContent = 'Probando…';
    $('#lt-test-result').textContent = '';
    try { const result = await api('/v1/admin/llm-config/test', { method: 'POST', body: '{}' });
      showTestResult(result.ok ? 'Conexión correcta (HTTP ' + result.status + ')' :
        'El proveedor respondió HTTP ' + result.status + (result.detail ? ': ' + result.detail.slice(0, 300) : ''), Boolean(result.ok));
    } catch (error) { showTestResult('Prueba no disponible: ' + (error.code || error.message), false); }
    finally { button.textContent = 'Probar conexión guardada'; button.disabled = !config.keyHint; }
  };
  $('#wf-apply').onclick = () => {
    const value = name => $('#wf-' + name)?.value.trim() ?? '';
    renderLevelTestOperations(container, {
      filters: { status: value('status'), language: value('language'), level: value('level'),
        week_from: value('week-from'), week_to: value('week-to'),
        created_from: value('created-from'), created_to: value('created-to') }, offset: 0 });
  };
  $('#weeks-prev').onclick = () => { if (weeks.offset > 0) void renderLevelTestOperations(container, { filters, offset: Math.max(0, weeks.offset - weeks.limit) }); };
  $('#weeks-next').onclick = () => { if (weeks.items.length === weeks.limit) void renderLevelTestOperations(container, { filters, offset: weeks.offset + weeks.limit }); };
  container.querySelectorAll('[data-week-detail]').forEach(button => button.onclick = async () => {
    try { const week = await api('/v1/admin/level-tests/weeks/' + button.dataset.weekDetail);
      detail('Examen ' + week.languageID + ' ' + week.level + ' · semana ' + week.weekId,
        { status: week.status, pool: week.pool, variants: week.variants, judgeMode: week.judgeMode,
          review: week.review, model: week.model, generator: week.generator });
    } catch (error) { toast('Error: ' + (error.code || error.message), 'danger'); }
  });
  for (const action of ['approve', 'reject']) container.querySelectorAll('[data-week-' + action + ']').forEach(button => button.onclick = async () => {
    const row = weeks.items.find(item => item.id === button.dataset['week' + action[0].toUpperCase() + action.slice(1)]);
    if (!row) return;
    const decision = await ask(action === 'approve' ? 'Aprobar borrador' : 'Rechazar borrador', row.id);
    if (!decision.reason || decision.reason.trim().length < 3) return toast('Motivo obligatorio', 'warning');
    button.disabled = true;
    try { await api('/v1/admin/level-tests/' + encodeURIComponent(row.id) + '/' + action,
      { method: 'POST', body: JSON.stringify({ reason: decision.reason }) });
      toast(action === 'approve' ? 'Borrador aprobado' : 'Borrador rechazado'); await renderLevelTestOperations(container, view); }
    catch (error) { toast('Error: ' + (error.code || error.message), 'danger'); button.disabled = false; }
  });
  const renderJobs = jobs => { const monitor = $('#jobs-monitor'); if (!monitor) return;
    monitor.innerHTML = jobs.length ? `<div class="table-responsive"><table class="table table-sm table-vcenter"><thead>
      <tr><th>Idioma</th><th>Nivel</th><th>Semana</th><th>Estado</th><th>Intentos</th><th>Error</th><th>Actualizado</th></tr></thead><tbody>${jobs.map(job =>
      '<tr><td>' + escapeHtml(job.languageID) + '</td><td>' + escapeHtml(job.level) + '</td><td>' + escapeHtml(job.weekId) +
      '</td><td>' + badgeCell({ getValue: () => job.status }) + '</td><td>' + escapeHtml(job.attempts) + '</td><td>' +
      escapeHtml(job.errorCode || '—') + '</td><td>' + escapeHtml((job.updatedAt || '—').slice(0, 16).replace('T', ' ')) + '</td></tr>').join('')}
      </tbody></table></div>` : '<div class="text-secondary small">Sin jobs registrados.</div>';
  };
  const pollJobs = async () => {
    if (!$('#jobs-monitor')) return;
    try { const fresh = await api('/v1/admin/level-tests/status');
      if (!$('#jobs-monitor')) return;
      renderJobs(fresh.jobs);
      if (fresh.jobs.some(job => job.status === 'running')) setTimeout(pollJobs, 5000);
    } catch { /* polling is best effort */ }
  };
  void pollJobs();
  container.querySelectorAll('[data-week-regenerate]').forEach(button => button.onclick = async () => {
    const [languageID, level] = button.dataset.weekRegenerate.split(':');
    const decision = await ask('Regenerar examen', languageID + ':' + level);
    if (!decision.reason || decision.reason.trim().length < 3) return toast('Motivo obligatorio', 'warning');
    button.disabled = true;
    try { await api('/v1/admin/level-tests/regenerate', { method: 'POST',
        body: JSON.stringify({ languageID, level, reason: decision.reason }) });
      toast('Regeneración iniciada'); void pollJobs();
    } catch (error) { toast('Error: ' + (error.code || error.message), 'danger'); button.disabled = false; }
  });
}

/* ---- exam attempts (per-student results) ---- */
async function renderExamAttempts(container, routeParams = {}, filters = {}) {
  // Hash params (#exam-results?account=…) seed the filters on first entry;
  // later re-renders pass pagination in the same slot without an account.
  const merged = { ...filters };
  if (routeParams.account && !merged.account) merged.account = routeParams.account;
  if (routeParams.email && !merged.email) merged.email = routeParams.email;
  const offset = routeParams.offset ?? 0;
  const query = new URLSearchParams({ limit: '50', offset: String(offset) });
  for (const [key, value] of Object.entries(merged)) if (value) query.set(key, String(value));
  const [attempts, summary] = await Promise.all([
    api('/v1/admin/level-test-attempts?' + query), api('/v1/admin/level-test-attempts/summary')
  ]);
  const studentEmail = merged.studentEmail || (merged.account ? attempts.items[0]?.email : null);
  const rows = attempts.items.map(item =>
    '<tr><td class="cell-mono" title="' + escapeHtml(item.attemptId) + '">' + escapeHtml(item.attemptId.slice(0, 8)) + '…</td>' +
    '<td>' + escapeHtml(item.email || '—') + '</td><td>' + escapeHtml(item.languageID) + ' ' + escapeHtml(item.level) +
    '</td><td>' + escapeHtml(item.weekId) + '</td><td>' + escapeHtml(item.source) + '</td><td>' +
    (item.state === 'completed' ? badgeCell({ getValue: () => 'settled' }) + ' ' : '<span class="badge badge-info">in_progress</span> ') +
    (item.passed === null ? '—' : item.passed ? '<span class="badge badge-success">passed</span>' : '<span class="badge badge-danger">failed</span>') +
    '</td><td>' + (item.overall === null ? '—' : item.overall + ' %') + '</td><td>' +
    escapeHtml((item.startedAt || '—').slice(0, 16).replace('T', ' ')) + '</td>' +
    '<td class="text-end"><button class="btn btn-sm btn-outline-secondary" data-attempt-detail="' +
    escapeHtml(item.accountId) + ':' + escapeHtml(item.attemptId) + '">Ver</button></td></tr>').join('') ||
    '<tr><td colspan="9" class="text-secondary">Sin resultados para este filtro.</td></tr>';
  const filterBar = `<div class="row g-2 mb-2 align-items-end">
    <div class="col"><input class="form-control form-control-sm" id="wa-email" placeholder="Email contiene" value="${escapeHtml(merged.email ?? '')}"></div>
    <div class="col-auto"><select class="form-select form-select-sm" id="wa-language"><option value="">Idioma: todos</option>${['en','es','fr','de','it','pt','nb','zh'].map(lang => `<option${lang === merged.language ? ' selected' : ''}>${lang}</option>`).join('')}</select></div>
    <div class="col-auto"><select class="form-select form-select-sm" id="wa-state"><option value="">Estado: todos</option><option${merged.state === 'completed' ? ' selected' : ''}>completed</option><option${merged.state === 'in_progress' ? ' selected' : ''}>in_progress</option></select></div>
    <div class="col-auto"><select class="form-select form-select-sm" id="wa-passed"><option value="">Resultado: todos</option><option value="true"${merged.passed === 'true' ? ' selected' : ''}>aprobados</option><option value="false"${merged.passed === 'false' ? ' selected' : ''}>suspendidos</option></select></div>
    <div class="col-auto"><input class="form-control form-control-sm" id="wa-week-from" placeholder="Semana ≥" value="${escapeHtml(merged.week_from ?? '')}"></div>
    <div class="col-auto"><input class="form-control form-control-sm" id="wa-week-to" placeholder="Semana ≤" value="${escapeHtml(merged.week_to ?? '')}"></div>
    <div class="col-auto"><button class="btn btn-sm btn-primary" id="wa-apply">Filtrar</button><button class="btn btn-sm btn-outline-primary" id="wa-export">Exportar CSV</button></div>
  </div>`;
  container.innerHTML = `<div class="row row-cards">
    ${merged.account ? `<div class="col-12"><div class="card card-sm"><div class="card-body"><strong>Alumno:</strong> ${escapeHtml(studentEmail || merged.account)}</div></div></div>` : ''}
    <div class="col-12"><div class="card"><div class="card-header"><h3 class="card-title">Resumen</h3></div><div class="card-body d-flex gap-4">
      <div><div class="subheader text-secondary">Intentos</div><strong>${summary.total}</strong></div>
      <div><div class="subheader text-secondary">Completados</div><strong>${summary.completed}</strong></div>
      <div><div class="subheader text-secondary">Aprobados</div><strong>${summary.passed}</strong></div>
      <div><div class="subheader text-secondary">Nota media</div><strong>${summary.avgOverall === null ? '—' : summary.avgOverall + ' %'}</strong></div>
    </div></div></div>
    <div class="col-12"><div class="card"><div class="card-header"><h3 class="card-title">Resultados de exámenes</h3></div><div class="card-body">${filterBar}
    <div class="table-responsive"><table class="table table-vcenter card-table"><thead><tr><th>Intento</th><th>Alumno</th><th>Examen</th><th>Semana</th><th>Origen</th><th>Estado</th><th>Nota</th><th>Inicio</th><th></th></tr></thead><tbody>${rows}</tbody></table></div></div><div class="card-footer d-flex justify-content-between align-items-center">
      <button class="btn btn-sm btn-outline-secondary" id="attempts-prev" ${attempts.offset === 0 ? 'disabled' : ''}>Anterior</button>
      <span class="text-secondary small">Resultados ${attempts.total ? attempts.offset + 1 : 0}–${attempts.offset + attempts.items.length} de ${attempts.total}</span>
      <button class="btn btn-sm btn-outline-secondary" id="attempts-next" ${attempts.items.length < attempts.limit ? 'disabled' : ''}>Siguiente</button>
    </div></div></div></div>`;
  $('#wa-apply').onclick = () => {
    const value = id => $('#' + id)?.value.trim() ?? '';
    renderExamAttempts(container, { offset: 0 }, { email: value('wa-email'), language: value('wa-language'),
      state: value('wa-state'), passed: value('wa-passed'), week_from: value('wa-week-from'), week_to: value('wa-week-to'),
      account: merged.account, studentEmail: studentEmail });
  };
  $('#attempts-prev').onclick = () => { if (attempts.offset > 0) void renderExamAttempts(container, { offset: attempts.offset - attempts.limit }, merged); };
  $('#attempts-next').onclick = () => { if (attempts.items.length === attempts.limit) void renderExamAttempts(container, { offset: attempts.offset + attempts.limit }, merged); };
  $('#wa-export').onclick = async () => {
    try { const response = await fetch('/v1/admin/level-test-attempts/export?' + query, { credentials: 'same-origin' });
      if (!response.ok) return toast('Error: ' + (await response.json().catch(() => ({}))).error?.code ?? 'export_failed', 'danger');
      const url = URL.createObjectURL(await response.blob());
      const link = document.createElement('a');
      link.href = url; link.download = 'level-test-attempts.csv'; link.click();
      URL.revokeObjectURL(url);
    } catch (error) { toast('Error: ' + error.message, 'danger'); }
  };
  container.querySelectorAll('[data-attempt-detail]').forEach(button => button.onclick = async () => {
    const [accountId, attemptId] = button.dataset.attemptDetail.split(':');
    try { const attempt = await api(`/v1/admin/level-test-attempts/${accountId}/${attemptId}`);
      detail('Intento de ' + (attempt.email || accountId) + ' · ' + attempt.languageID + ' ' + attempt.level + ' · semana ' + attempt.weekId,
        { result: attempt.result, answers: attempt.answers });
    } catch (error) { toast('Error: ' + (error.code || error.message), 'danger'); }
  });
}

/* ---- forms ---- */
async function renderPolicy(container) {
  const policy = await api('/v1/admin/policy/welcome');
  container.innerHTML = `<div class="row"><div class="col-lg-6"><div class="card"><div class="card-body">
    <h3 class="card-title d-flex align-items-center gap-2"><i class="ti ti-settings text-secondary"></i>Minutos de bienvenida</h3>
    <div class="mb-3"><label class="form-check form-switch">
      <input class="form-check-input" type="checkbox" id="pol-enabled" ${policy.welcomeEnabled ? 'checked' : ''}>
      <span class="form-check-label">Conceder minutos de bienvenida al registrarse</span></label></div>
    <div class="mb-3"><label class="form-label">Minutos</label>
      <input type="number" min="0" max="1440" class="form-control" id="pol-minutes" value="${policy.welcomeMinutes}"></div>
    <div class="mb-3"><label class="form-label">Motivo (obligatorio, queda auditado)</label>
      <textarea class="form-control" id="pol-reason" rows="2" maxlength="500"></textarea></div>
    <button class="btn btn-primary" id="pol-save"><i class="ti ti-device-floppy me-1"></i>Guardar</button>
    <span class="text-secondary ms-2">versión actual ${policy.version}</span>
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

async function renderPlans(container) {
  const { config, tiers } = await api('/v1/admin/plans');
  const tierRows = tiers.map((tier, index) => `<div class="card-body border-top py-3">
    <h4 class="text-uppercase small text-secondary mb-2">Plan ${tier.tier}</h4>
    <div class="row">
      <div class="col"><label class="form-label">Minutos/mes</label>
        <input type="number" min="15" max="100000" class="form-control" data-tier="${index}" data-field="monthlyMinutes" value="${tier.monthlyMinutes}"></div>
      <div class="col"><label class="form-label">Tope de rollover (min)</label>
        <input type="number" min="15" max="144000" class="form-control" data-tier="${index}" data-field="rolloverCapMinutes" value="${tier.rolloverCapMinutes}"></div>
      <div class="col"><label class="form-label">Precio Stripe (price ID)</label>
        <input class="form-control font-monospace" data-tier="${index}" data-field="stripePriceID" value="${tier.stripePriceID ?? ''}" placeholder="price_..."></div>
      <div class="col"><label class="form-label">Producto Play</label>
        <input class="form-control font-monospace" data-tier="${index}" data-field="playProductID" value="${tier.playProductID ?? ''}" placeholder="subscripción.producto"></div>
    </div></div>`).join('');
  container.innerHTML = `<div class="row"><div class="col-lg-8"><div class="card"><div class="card-body">
    <h3 class="card-title d-flex align-items-center gap-2"><i class="ti ti-crown text-secondary"></i>Suscripciones y planes</h3>
    <div class="row">
      <div class="col"><label class="form-check form-switch mt-2">
        <input class="form-check-input" type="checkbox" id="plans-drip" ${config.freeDripEnabled ? 'checked' : ''}>
        <span class="form-check-label">Goteo gratuito mensual</span></label></div>
      <div class="col"><label class="form-label">Minutos/mes del goteo</label>
        <input type="number" min="0" max="60" class="form-control" id="plans-drip-minutes" value="${config.freeDripMinutes}"></div>
      <div class="col"><label class="form-label">Tope de goteo (min)</label>
        <input type="number" min="0" max="1440" class="form-control" id="plans-drip-cap" value="${config.freeDripRolloverCapMinutes}"></div>
      <div class="col"><label class="form-label">Días de prueba</label>
        <input type="number" min="0" max="30" class="form-control" id="plans-trial" value="${config.trialDays}"></div>
      <div class="col"><label class="form-label">Presupuesto vitalicio del goteo (0=sin tope)</label>
        <input type="number" min="0" class="form-control" id="plans-budget" value="${config.lifetimeDripBudgetMinutes}"></div>
    </div></div>${tierRows}
    <div class="card-body border-top">
      <div class="mb-3"><label class="form-label">Motivo (obligatorio, queda auditado)</label>
        <textarea class="form-control" id="plans-reason" rows="2" maxlength="500"></textarea></div>
      <button class="btn btn-primary" id="plans-save"><i class="ti ti-device-floppy me-1"></i>Guardar planes</button>
      <span class="text-secondary ms-2">versión actual ${config.version}</span>
      <div id="plans-error" class="text-danger mt-2"></div>
    </div></div></div></div>`;
  $('#plans-save').onclick = async () => {
    const tierInputs = [...container.querySelectorAll('[data-tier]')];
    const payload = { version: config.version, reason: $('#plans-reason').value,
      freeDripEnabled: $('#plans-drip').checked, freeDripMinutes: Number($('#plans-drip-minutes').value),
      freeDripRolloverCapMinutes: Number($('#plans-drip-cap').value), trialDays: Number($('#plans-trial').value),
      lifetimeDripBudgetMinutes: Number($('#plans-budget').value),
      tiers: tiers.map((tier, index) => ({ tier: tier.tier,
        monthlyMinutes: Number(tierInputs.find(i => +i.dataset.tier === index && i.dataset.field === 'monthlyMinutes').value),
        rolloverCapMinutes: Number(tierInputs.find(i => +i.dataset.tier === index && i.dataset.field === 'rolloverCapMinutes').value),
        features: tier.features,
        stripePriceID: tierInputs.find(i => +i.dataset.tier === index && i.dataset.field === 'stripePriceID').value || null,
        playProductID: tierInputs.find(i => +i.dataset.tier === index && i.dataset.field === 'playProductID').value || null })) };
    try {
      await api('/v1/admin/plans', { method: 'PUT', body: JSON.stringify(payload) });
      toast('Planes actualizados'); renderPlans(container);
    } catch (error) { $('#plans-error').textContent = error.code === 'subscription_version_conflict' ?
      'Conflicto de versión: recarga y reintenta.' : `Error: ${error.code}`; }
  };
}

async function renderCampaigns(container) {
  container.innerHTML = `<div class="row"><div class="col-lg-8"><div class="card"><div class="card-body">
    <h3 class="card-title d-flex align-items-center gap-2"><i class="ti ti-gift text-secondary"></i>Campaña de minutos</h3>
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
    <button class="btn btn-primary" id="camp-prepare"><i class="ti ti-checklist me-1"></i>Preparar</button>
    <div id="camp-result" class="mt-3"></div>
  </div></div></div></div>`;
  for (const radio of ['#aud-one', '#aud-all']) $(radio).onchange = () => { $('#aud-account-wrap').style.display = $('#aud-one').checked ? '' : 'none'; };
  $('#camp-prepare').onclick = async () => {
    try {
      const summary = await api('/v1/admin/campaigns', { method: 'POST', body: JSON.stringify({
        audience: $('#aud-one').checked ? [$('#camp-account').value.trim()] : 'all-current-users',
        minutesPerUser: Number($('#camp-minutes').value), maxTotalMinutes: Number($('#camp-cap').value),
        reason: $('#camp-reason').value }) });
      $('#camp-result').innerHTML = `<div class="alert alert-info d-flex align-items-center gap-2">
        <i class="ti ti-info-circle"></i><span>Preparada: <strong>${summary.recipients}</strong> destinatarios,
        ${summary.minutesPerUser} min/usuario. Confirmación <code class="small">${summary.confirmation}</code></span></div>
        <button class="btn btn-warning" id="camp-apply"><i class="ti ti-alert-triangle me-1"></i>Aplicar (irreversible)</button>`;
      $('#camp-apply').onclick = async () => {
        try {
          const outcome = await api(`/v1/admin/campaigns/${summary.campaignID}/apply`, { method: 'POST',
            body: JSON.stringify({ confirmation: summary.confirmation, reason: $('#camp-reason').value }) });
          $('#camp-result').innerHTML += `<div class="alert alert-success d-flex align-items-center gap-2">
            <i class="ti ti-circle-check"></i><span>Aplicada: concedidos <strong>${outcome.granted}</strong>,
            omitidos ${outcome.skipped}.</span></div>`;
        } catch (error) { toast(`Error: ${error.code}`, 'danger'); }
      };
    } catch (error) { toast(`Error: ${error.code}`, 'danger'); }
  };
}

/* ---- usage (in-app feature metrics) ---- */
async function renderUsage(container) {
  const [overview, series] = await Promise.all([
    api('/v1/admin/metrics/usage?days=30'),
    api('/v1/admin/metrics/timeseries?set=usage&days=30')
  ]);
  const percent = (value, size) => size > 0 ? `${Math.round(value * 100 / size)} %` : '—';
  const featureLabel = { app_open: 'Apertura de app', session_started: 'Sesión iniciada', session_ended: 'Sesión terminada',
    session_resumed: 'Sesión reanudada', theme_selected: 'Tema elegido', lesson_started: 'Lección iniciada',
    lesson_completed: 'Lección completada', assessment_completed: 'Evaluación final', practice_completed: 'Ronda de repaso',
    word_lookup: 'Búsqueda de palabra', replay_used: 'Repetición en voz alta', tts_speed_changed: 'Velocidad TTS cambiada',
    help_requested: 'Más fácil, por favor', report_requested: 'Informe de conversación', paywall_viewed: 'Pantalla de minutos' };
  const retentionRows = overview.retention.slice(-14).reverse().map(row => `<tr>
    <td>${escapeHtml(row.day)}</td><td>${number(row.size)}</td>
    <td>${percent(row.d1, row.size)}</td><td>${percent(row.d7, row.size)}</td><td>${percent(row.d30, row.size)}</td></tr>`).join('');
  container.innerHTML = `<div class="row mb-3 g-3">
      ${kpi('DAU (hoy)', number(overview.dau))}
      ${kpi('WAU', number(overview.wau))}
      ${kpi('MAU', number(overview.mau))}
      ${kpi('Eventos 7 días', number(overview.events7d))}
    </div>
    <div class="row row-cards g-3">${series.series.map(serie => chartCard(serie.label)).join('')}</div>
    <div class="row row-cards g-3 mt-1">
      <div class="col-lg-5"><div class="card"><div class="card-body">
        <h3 class="card-title"><i class="ti ti-flame text-secondary"></i>Funciones más usadas (7 días)</h3>
        ${overview.topFeatures.length ? overview.topFeatures.map(feature => `<div class="d-flex align-items-center justify-content-between border-bottom py-2">
          <span>${escapeHtml(featureLabel[feature.name] ?? feature.name)}</span>
          <strong>${number(feature.count)}</strong></div>`).join('')
          : '<div class="text-secondary py-2">Sin eventos todavía.</div>'}
      </div></div></div>
      <div class="col-lg-7"><div class="card"><div class="card-body">
        <h3 class="card-title"><i class="ti ti-chart-arcs text-secondary"></i>Retención por cohorte de registro (últimas 2 semanas)</h3>
        <div class="table-responsive"><table class="table table-sm table-vcenter card-table">
          <thead><tr><th>Día de registro</th><th>Cohorte</th><th>D1</th><th>D7</th><th>D30</th></tr></thead>
          <tbody>${retentionRows || '<tr><td colspan="5" class="text-secondary">Sin cohortes todavía.</td></tr>'}</tbody>
        </table></div>
      </div></div></div>
    </div>`;
  container.querySelectorAll('.chart-box canvas').forEach((canvas, index) => mountChart(canvas, series.series[index]));
  requestAnimationFrame(() => { for (const chart of charts) chart.resize(); });
}

/* ---- row actions ---- */
function contextMenu(tableName) {
  if (tableName === 'accounts') return [
    { label: '<i class="ti ti-id-badge me-2"></i>Detalle de cuenta', action: (event, row) => api(`/v1/admin/accounts/${row.getData().id}`)
        .then(report => detail(`Cuenta ${row.getData().email ?? row.getData().id}`, report)).catch(error => toast(`Error: ${error.code}`, 'danger')) },
    { label: '<i class="ti ti-clipboard-check me-2"></i>Ver exámenes', action: (_event, row) => {
        location.hash = `#exam-results?account=${row.getData().id}`; } },
    { separator: true },
    { label: '<i class="ti ti-gift me-2"></i>Regalar minutos…', action: async (event, row) => {
        const asked = await ask('Campaña de minutos', row.getData().email ?? row.getData().id);
        if (!asked.reason || asked.reason.trim().length < 3) return toast('Motivo obligatorio', 'warning');
        try { const summary = await api('/v1/admin/campaigns', { method: 'POST', body: JSON.stringify({
            audience: [row.getData().id], minutesPerUser: 5, maxTotalMinutes: 60, reason: asked.reason }) });
          await api(`/v1/admin/campaigns/${summary.campaignID}/apply`, { method: 'POST',
            body: JSON.stringify({ confirmation: summary.confirmation, reason: asked.reason }) });
          toast(`Regalados 5 minutos a ${row.getData().email ?? row.getData().id}`);
        } catch (error) { toast(`Error: ${error.code}`, 'danger'); } } }
  ];
  if (tableName === 'hosted_sessions') return [
    { label: '<i class="ti ti-phone-off me-2"></i>Forzar cierre…', action: async (event, row) => {
        const data = row.getData();
        if (data.state === 'closed') return toast('Ya está cerrada', 'warning');
        const asked = await ask('Forzar cierre de sesión', data.id);
        if (!asked.reason || asked.reason.trim().length < 3) return toast('Motivo obligatorio', 'warning');
        try { await api(`/v1/admin/hosted-sessions/${data.id}/close`, { method: 'POST',
          body: JSON.stringify({ reason: asked.reason }) }); toast('Cierre solicitado'); row.update({ state: 'closing' }); }
        catch (error) { toast(`Error: ${error.code}`, 'danger'); } } }
  ];
  if (tableName === 'access_requests') return [
    { label: '<i class="ti ti-trash me-2"></i>Eliminar de la waitlist…', action: async (event, row) => {
        const asked = await ask('Eliminar de la waitlist', row.getData().email);
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
  usage: { title: 'Uso de la app', render: renderUsage },
  browse: { title: 'Explorador de tablas', render: container => renderBrowse(container, null) },
  policy: { title: 'Política de bienvenida', render: renderPolicy },
  'level-tests': { title: 'Generación de exámenes', render: renderLevelTestOperations },
  'exam-results': { title: 'Resultados de exámenes', render: renderExamAttempts },
  plans: { title: 'Planes y suscripciones', render: renderPlans },
  campaigns: { title: 'Campañas', render: renderCampaigns },
  audit: { title: 'Auditoría de acciones', render: container => renderBrowse(container, 'admin_actions') }
};
async function route() {
  const [routePath, routeQuery] = (location.hash.slice(1) || 'overview').split('?');
  const view = VIEWS[routePath] ?? VIEWS.overview;
  const params = Object.fromEntries(new URLSearchParams(routeQuery ?? ''));
  $('#page-title').textContent = view.title;
  document.title = `${view.title} · Palique Admin`;
  for (const link of document.querySelectorAll('#nav .nav-link')) link.classList.toggle('active', link.hash === `#${routePath}`);
  for (const chart of charts) chart.destroy();
  charts.length = 0;
  for (const table of tables) table.destroy();
  tables = [];
  const container = $('#view');
  container.innerHTML = '<div class="mt-3 progress progress-sm"><div class="progress-bar progress-bar-indeterminate"></div></div>';
  try { await view.render(container, params); }
  catch (error) { if (error.message !== 'unauthenticated') container.innerHTML = `<div class="alert alert-danger mt-3"><i class="ti ti-alert-triangle me-2"></i>${error.code ?? error.message}</div>`; }
}
(function boot() {
  $('#nav').innerHTML = NAV.map(item => `<li class="nav-item"><a class="nav-link" href="#${item.id}">
    <span class="nav-link-icon"><i class="ti ${item.icon}"></i></span><span class="nav-link-title">${item.label}</span></a></li>`).join('');
  api('/v1/admin/me').then(me => {
    const name = me.email ?? me.adminID;
    $('#whoami').textContent = name;
    $('#avatar').textContent = (me.email ?? 'A')[0].toUpperCase();
  }).catch(() => {});
  $('#signout').onclick = async () => { try { await api('/v1/admin/auth/sign-out', { method: 'POST', body: '{}' }); } catch {} location.href = '/login.html'; };
  window.addEventListener('hashchange', route);
  route();
})();
