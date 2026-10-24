/* Palique admin panel — vanilla JS over the /v1/admin API. No build step. */
'use strict';

const $ = selector => document.querySelector(selector);
const NAV = [
  { id: 'overview', label: 'Resumen', icon: 'ti-chart-bar', group: 'Operación' },
  { id: 'growth', label: 'Crecimiento', icon: 'ti-trending-up', group: 'Operación' },
  { id: 'voice', label: 'Voz', icon: 'ti-microphone', group: 'Operación' },
  { id: 'economy', label: 'Economía', icon: 'ti-coins', group: 'Operación' },
  { id: 'usage', label: 'Uso', icon: 'ti-activity', group: 'Operación' },
  { id: 'exam-weeks', label: 'Generar y revisar', icon: 'ti-book', group: 'Exámenes' },
  { id: 'exam-results', label: 'Resultados', icon: 'ti-clipboard-check', group: 'Exámenes' },
  { id: 'ia-config', label: 'Configuración de IA', icon: 'ti-cpu', group: 'Exámenes' },
  { id: 'plans', label: 'Planes', icon: 'ti-crown', group: 'Comercial' },
  { id: 'campaigns', label: 'Campañas', icon: 'ti-gift', group: 'Comercial' },
  { id: 'browse', label: 'Tablas', icon: 'ti-table', group: 'Sistema' },
  { id: 'policy', label: 'Política bienvenida', icon: 'ti-settings', group: 'Sistema' },
  { id: 'audit', label: 'Auditoría', icon: 'ti-shield-check', group: 'Sistema' }
];
const PALETTE = ['#206bc4', '#2fb344', '#f76707', '#a55aad', '#d63939', '#429ade'];
const CHART_TYPE = { orders: 'bar', revenue_minor: 'bar', minutes_granted_ms: 'bar', minutes_consumed_ms: 'bar' };
const charts = [];
let tables = [];
const api = async (path, options = {}) => {
  let response;
  try { response = await fetch(path, { headers: { 'content-type': 'application/json' }, credentials: 'same-origin', ...options }); }
  catch (error) { if (error.name !== 'AbortError') setApiStatus(false); throw error; }
  setApiStatus(true);
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
  settle: 'secondary', release: 'secondary', forfeit: 'danger', transfer: 'secondary',
  // Exam weeks and generation jobs: green means usable, red means the operator
  // has to look at it, yellow means waiting or unknown.
  draft: 'warning', approved: 'success', rejected: 'danger', retired: 'secondary',
  running: 'info', finished: 'success', failed: 'danger', unknown: 'warning'
};
const badgeCell = cell => {
  const raw = cell.getValue();
  if (raw === null || raw === undefined || raw === '') return '<span class="text-secondary">—</span>';
  const tone = BADGE_COLORS[String(raw)] ?? 'secondary';
  return `<span class="badge bg-${tone}-lt">${escapeHtml(String(raw))}</span>`;
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
  element.setAttribute('role', kind === 'danger' ? 'alert' : 'status');
  element.setAttribute('aria-live', kind === 'danger' ? 'assertive' : 'polite');
  element.style.zIndex = '9999'; element.textContent = message;
  document.body.append(element);
  setTimeout(() => element.remove(), 4_000);
};
const setApiStatus = connected => {
  const badge = $('#api-status');
  if (!badge) return;
  badge.className = `badge ${connected ? 'bg-success-lt' : 'bg-danger-lt'}`;
  badge.textContent = connected ? 'API conectada' : 'API sin conexión';
  badge.setAttribute('role', 'status');
  badge.setAttribute('aria-live', 'polite');
};
const validateFields = fields => {
  let firstInvalid = null;
  for (const { input, label, requiredText = false, requiredValue = false, validate } of fields) {
    const control = typeof input === 'string' ? $(input) : input;
    if (!control) continue;
    const key = control.id || `field-${control.dataset.tier ?? 'form'}-${control.dataset.field ?? 'input'}`;
    const errorId = `${key}-error`;
    const helper = document.getElementById(errorId);
    let message = '';
    if (!control.checkValidity()) {
      const bounds = control.validity.rangeOverflow ? `El máximo es ${control.max}.` :
        control.validity.rangeUnderflow ? `El mínimo es ${control.min}.` : 'Introduce un valor válido.';
      message = `${label}: ${bounds}`;
    } else if (requiredValue && !String(control.value).trim()) message = `${label}: este campo es obligatorio.`;
    else if (requiredText && control.value.trim().length < 3) message = `${label}: escribe al menos 3 caracteres.`;
    else if (validate && !validate(control.value)) message = `${label}: el valor no es válido.`;
    if (message) {
      control.setAttribute('aria-invalid', 'true');
      control.setAttribute('aria-describedby', [control.getAttribute('aria-describedby'), errorId].filter(Boolean).join(' '));
      const error = helper ?? document.createElement('div');
      error.id = errorId; error.className = 'invalid-feedback d-block';
      error.setAttribute('role', 'alert'); error.textContent = message;
      if (!helper) control.insertAdjacentElement('afterend', error);
      firstInvalid ??= control;
    } else {
      control.removeAttribute('aria-invalid');
      if (helper) helper.remove();
      const describedBy = (control.getAttribute('aria-describedby') ?? '').split(/\s+/).filter(id => id && id !== errorId);
      if (describedBy.length) control.setAttribute('aria-describedby', describedBy.join(' '));
      else control.removeAttribute('aria-describedby');
    }
  }
  if (firstInvalid) { firstInvalid.focus(); return false; }
  return true;
};
const refreshAfterMutation = async (container, render, confirmation, retryId) => {
  try { await render(container); toast(confirmation); }
  catch (error) {
    const renderFailure = failure => {
      container.innerHTML = `<div class="alert alert-warning mt-3" role="alert">
        <strong>${escapeHtml(confirmation)}</strong><p class="mb-2">La lectura no se pudo actualizar; los datos mostrados aún no están confirmados como recientes.</p>
        <span class="small">${escapeHtml(failure.code ?? failure.message ?? 'read_failed')}</span>
        <div><button class="btn btn-sm btn-outline-primary mt-2" id="${retryId}">Reintentar lectura</button></div></div>`;
      $(`#${retryId}`).onclick = async () => {
        container.setAttribute('aria-busy', 'true');
        try { await render(container); }
        catch (nextFailure) { renderFailure(nextFailure); }
        finally { container.setAttribute('aria-busy', 'false'); }
      };
    };
    renderFailure(error);
  }
};
const originLabel = value => value === 'user_operator' ? 'Operador' : value === 'automatic_worker' ? 'Automático' : '—';
let activeModalId = null;
let modalInvoker = null;
let cancelPendingReason = null;
const openModal = id => {
  const element = $(id);
  modalInvoker = document.activeElement instanceof HTMLElement ? document.activeElement : null;
  activeModalId = id;
  element.classList.add('show'); element.style.display = 'block';
  document.querySelector('.page').inert = true;
  document.body.classList.add('modal-open');
  const initialFocus = id === '#reason-modal' ? $('#reason-text') : $('#detail-close');
  initialFocus?.focus();
};
const closeModal = id => {
  const element = $(id);
  if (activeModalId !== id) return;
  element.classList.remove('show'); element.style.display = '';
  document.body.classList.remove('modal-open');
  document.querySelector('.page').inert = false;
  activeModalId = null;
  const invoker = modalInvoker;
  modalInvoker = null;
  if (invoker?.isConnected) invoker.focus();
};
document.addEventListener('keydown', event => {
  if (!activeModalId) return;
  const dialog = $(activeModalId);
  if (event.key === 'Escape') {
    event.preventDefault();
    if (activeModalId === '#reason-modal') cancelPendingReason?.();
    else closeModal(activeModalId);
    return;
  }
  if (event.key !== 'Tab') return;
  const focusable = [...dialog.querySelectorAll('a[href], button:not([disabled]), input:not([disabled]):not([tabindex="-1"]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])')]
    .filter(element => element.getClientRects().length > 0);
  if (!focusable.length) { event.preventDefault(); dialog.focus(); return; }
  const first = focusable[0], last = focusable[focusable.length - 1];
  if (event.shiftKey && (document.activeElement === first || !dialog.contains(document.activeElement))) {
    event.preventDefault(); last.focus();
  } else if (!event.shiftKey && (document.activeElement === last || !dialog.contains(document.activeElement))) {
    event.preventDefault(); first.focus();
  }
});
const detail = (title, body) => {
  $('#detail-title').textContent = title;
  $('#detail-body').textContent = typeof body === 'string' ? body : JSON.stringify(body, null, 2);
  openModal('#detail-modal');
};
/** Structured detail (escaped by the caller) for payloads a <pre> cannot explain. */
const detailHtml = (title, html) => {
  $('#detail-title').textContent = title;
  $('#detail-body').innerHTML = html;
  openModal('#detail-modal');
};
$('#detail-close').addEventListener('click', () => closeModal('#detail-modal'));
$('#detail-dismiss').addEventListener('click', () => closeModal('#detail-modal'));
const ask = (title, target) => new Promise(resolve => {
  $('#reason-title').textContent = title;
  $('#reason-input').value = target;
  const text = $('#reason-text'), ok = $('#reason-ok'), hint = $('#reason-hint');
  // Confirmar stays disabled until the reason is real: an empty reason used to
  // abandon the request silently, with only a toast that vanished.
  const validate = () => {
    const value = text.value.trim();
    const valid = value.length >= 3;
    ok.disabled = !valid;
    hint.textContent = valid ? `${value.length} caracteres · se guarda en la auditoría`
      : `Escribe el motivo (mínimo 3 caracteres)${value.length ? ` · faltan ${3 - value.length}` : ''}. Se guarda en la auditoría.`;
    hint.classList.toggle('text-danger', !valid);
    return valid;
  };
  text.value = '';
  text.oninput = validate;
  validate();
  cancelPendingReason = () => {
    cancelPendingReason = null;
    closeModal('#reason-modal');
    resolve({ reason: '' });
  };
  openModal('#reason-modal');
  ok.onclick = () => {
    if (!validate()) { text.focus(); return; }
    cancelPendingReason = null;
    closeModal('#reason-modal');
    resolve({ reason: text.value.trim() });
  };
  $('#reason-cancel').onclick = () => cancelPendingReason?.();
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
const chartCard = (title, type, serie) => {
  const points = Array.isArray(serie?.points) ? serie.points : [];
  const rows = points.map(point => `<tr><th scope="row">${escapeHtml(point.day)}</th><td>${number(point.value)}</td></tr>`).join('');
  return `<div class="col-lg-6"><div class="card chart-card"><div class="card-body">
    <h3 class="card-title d-flex align-items-center gap-2"><i class="ti ${type === 'bar' ? 'ti-chart-column' : 'ti-chart-line'} text-secondary" aria-hidden="true"></i>${escapeHtml(title)}</h3>
    <div class="chart-box"><canvas role="img" aria-label="Gráfico: ${escapeHtml(title)}"></canvas></div>
    <details class="chart-data"><summary>Ver datos en tabla</summary><div class="table-responsive"><table class="table table-sm">
      <caption class="visually-hidden">Datos de ${escapeHtml(title)}</caption><thead><tr><th scope="col">Fecha</th><th scope="col">Valor</th></tr></thead>
      <tbody>${rows || '<tr><td colspan="2">Sin datos disponibles.</td></tr>'}</tbody></table></div></details>
  </div></div></div>`;
};

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

async function renderDashboard(setName, container, context) {
  const [snapshot, series] = await Promise.all([
    setName === 'overview' ? api('/v1/admin/metrics', { signal: context.signal }) : Promise.resolve(null),
    api(`/v1/admin/metrics/timeseries?set=${setName}&days=30`, { signal: context.signal })
  ]);
  if (!context.isCurrent()) return;
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
  container.innerHTML = head + `<div class="row row-cards g-3">${series.series.map(serie => chartCard(serie.label, CHART_TYPE[serie.name] ?? 'line', serie)).join('')}</div>`;
  container.querySelectorAll('.chart-box canvas').forEach((canvas, index) => mountChart(canvas, series.series[index]));
  // Charts are created while the grid is still settling; re-measure once it has laid out.
  const frame = requestAnimationFrame(() => { if (context.isCurrent()) for (const chart of charts) chart.resize(); });
  context.register(() => cancelAnimationFrame(frame));
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
  if (spec.type === 'boolean') return cell => cell.getValue()
    ? '<span><i class="ti ti-circle-check text-success" aria-hidden="true"></i><span class="ms-1">Sí</span></span>'
    : '<span><i class="ti ti-circle-minus text-secondary" aria-hidden="true"></i><span class="ms-1">No</span></span>';
  if (spec.type === 'uuid') return uuidCell;
  if (/_minor$/.test(name)) return moneyCell;
  if (/_nano$/.test(name)) return nanoCell;
  if (/_ms$/.test(name)) return durationCell;
  if (BADGED_COLUMNS.has(name)) return badgeCell;
  return undefined;
}
async function renderBrowse(container, fixedTable, context) {
  const catalog = await api('/v1/admin/browse', { signal: context.signal });
  if (!context.isCurrent()) return;
  const tableNames = Object.keys(catalog.tables);
  const current = fixedTable ?? localStorage.getItem('browse.table') ?? tableNames[0];
  container.innerHTML = `
    ${fixedTable ? '' : `<div class="mb-3 d-flex align-items-center gap-3">
      <select id="browse-table" class="form-select w-auto" data-icon="table" aria-label="Tabla de datos"></select>
      <span class="text-secondary" id="browse-desc"></span>
    </div>`}
    <div class="card table-card">
      <div class="card-header py-2 d-flex align-items-center justify-content-between">
        <span class="text-secondary small"><i class="ti ti-database me-1"></i>${escapeHtml(fixedTable ?? current)}</span>
        <span class="text-secondary small" id="browse-total"></span>
      </div>
      <div class="card-body"><div id="browse-load-error" aria-live="polite"></div><div id="browse-grid"></div></div>
      <div class="card-footer py-2 pager-bar">
        <div class="btn-group">
          <button class="btn btn-sm" id="page-prev" aria-label="Página anterior"><i class="ti ti-chevron-left" aria-hidden="true"></i></button>
          <span class="btn btn-sm disabled" id="page-info"></span>
          <button class="btn btn-sm" id="page-next" aria-label="Página siguiente"><i class="ti ti-chevron-right" aria-hidden="true"></i></button>
        </div>
        <select class="form-select form-select-sm w-auto" id="page-size" aria-label="Filas por página">
          ${[10, 25, 50, 100].map(size => `<option${size === 25 ? ' selected' : ''}>${size}</option>`).join('')}
        </select>
      </div>
    </div>`;
  if (!fixedTable) {
    $('#browse-table').innerHTML = tableNames.map(name => `<option value="${name}"${name === current ? ' selected' : ''}>${name}</option>`).join('');
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
      const orderable = schema.orderable.includes(field);
      const base = { title: field.replaceAll('_', ' '), field,
        headerSort: orderable,
        sorter: orderable ? (spec.type === 'text' ? 'string' : 'number') : false,
        formatter: columnFormatter(field, spec),
        width: spec.type === 'timestamptz' ? 140 : spec.type === 'uuid' ? 110 : undefined };
      if (spec.operators.length) Object.assign(base, {
        headerFilter: (cell, onRendered, success, cancel, params) => {
          const wrapper = document.createElement('div');
          wrapper.className = 'd-flex gap-1';
          const operator = document.createElement('select');
          operator.className = 'form-select form-select-sm browse-filter-operator';
          operator.setAttribute('aria-label', `Operador ${field}`);
          operator.innerHTML = params.operators.map(value => `<option value="${value}">${value}</option>`).join('');
          const value = document.createElement('input');
          value.className = 'form-control form-control-sm browse-filter-value';
          value.setAttribute('aria-label', `Filtro ${field}`);
          value.placeholder = spec.type === 'timestamptz' ? 'AAAA-MM-DD' : 'Filtrar';
          const update = () => success({ operator: operator.value, value: value.value });
          operator.addEventListener('change', update);
          value.addEventListener('input', update);
          wrapper.append(operator, value);
          return wrapper;
        },
        headerFilterFunc: () => true,
        headerFilterParams: { operators: spec.operators }
      });
      return base;
    });
    if (name === 'accounts') columns.push({
      title: 'Acciones', field: '_actions', headerSort: false,
      formatter: cell => {
        const row = cell.getRow().getData(), label = row.email ?? row.id;
        return `<div class="row-actions"><button type="button" class="btn btn-sm btn-outline-primary" data-account-detail aria-label="Ver cuenta de ${escapeHtml(label)}">Detalle</button>
          <button type="button" class="btn btn-sm btn-outline-secondary" data-account-results aria-label="Ver exámenes de ${escapeHtml(label)}">Exámenes</button></div>`;
      },
      cellClick: async (event, cell) => {
        const button = event.target.closest('button');
        if (!button) return;
        const row = cell.getRow().getData();
        if (button.hasAttribute('data-account-results')) { location.hash = `#exam-results?account=${row.id}`; return; }
        if (button.getAttribute('aria-busy') === 'true') return;
        button.setAttribute('aria-busy', 'true');
        try {
          const report = await api(`/v1/admin/accounts/${row.id}`, { signal: context.signal });
          if (context.isCurrent()) detail(`Cuenta ${row.email ?? row.id}`, report);
        } catch (error) { if (context.isCurrent() && error.name !== 'AbortError') toast(`Error: ${error.code}`, 'danger'); }
        finally { button.removeAttribute('aria-busy'); }
      }
    });
    if (table) { table.destroy(); tables = tables.filter(item => item !== table); }
    table = new Tabulator('#browse-grid', {
      columns, layout: 'fitDataFill', sortMode: 'remote', placeholder: `<div class="empty p-3"><div class="empty-icon"><i class="ti ti-database-off"></i></div>
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
  let loadSequence = 0;
  let loadController = null;
  const load = async () => {
    const sequence = ++loadSequence;
    loadController?.abort();
    loadController = new AbortController();
    const controller = loadController;
    context.register(() => controller.abort());
    const name = fixedTable ?? $('#browse-table').value;
    const query = new URLSearchParams({ limit: String(state.size), offset: String((state.page - 1) * state.size),
      sort: state.sort, dir: state.dir });
    for (const entry of state.filters) {
      const spec = catalog.tables[name].columns[entry.field];
      const filter = entry.value ?? {};
      const operator = filter.operator;
      if (!spec.operators.includes(operator)) continue;
      query.set(`q_${entry.field}`, `${operator}:${String(filter.value ?? '')}`);
    }
    $('#browse-grid').setAttribute('aria-busy', 'true');
    $('#browse-load-error').textContent = 'Cargando resultados…';
    try {
      const body = await api(`/v1/admin/browse/${name}?${query}`, { signal: controller.signal });
      if (!context.isCurrent() || sequence !== loadSequence) return;
      table.setData(body.rows);
      const maxPage = Math.max(1, Math.ceil(body.total / state.size));
      state.page = Math.min(state.page, maxPage);
      $('#browse-total').textContent = `${number(body.total)} filas`;
      $('#page-info').textContent = `${state.page} / ${maxPage}`;
      $('#page-prev').disabled = state.page <= 1;
      $('#page-next').disabled = state.page >= maxPage;
      $('#browse-load-error').textContent = '';
    } catch (error) {
      if (context.isCurrent() && sequence === loadSequence && error.message !== 'unauthenticated' && error.name !== 'AbortError') {
        $('#browse-load-error').innerHTML = `<div class="alert alert-danger mb-2" role="alert">
          No se pudieron actualizar los resultados; las filas visibles pueden estar desactualizadas.
          <span class="small">${escapeHtml(error.code ?? error.message)}</span>
          <button class="btn btn-sm btn-outline-primary ms-2" id="browse-retry">Reintentar</button></div>`;
        $('#browse-retry').onclick = () => { void load(); };
      }
    } finally { if (context.isCurrent() && sequence === loadSequence) $('#browse-grid').setAttribute('aria-busy', 'false'); }
  };
  $('#page-prev').onclick = () => { if (state.page > 1) { state.page--; void load(); } };
  $('#page-next').onclick = () => { state.page++; void load(); };
  $('#page-size').onchange = event => { state.size = Number(event.target.value); state.page = 1; void load(); };
  if (!fixedTable) $('#browse-table').onchange = mountTable;
  await mountTable();
}

/* ---- level-tests view: generation watch state (module scope survives re-renders) ---- */
const GENERATION_WATCH_STORAGE_KEY = 'palique.admin.generation-watch.v1';
const GENERATION_REGISTRATION_TIMEOUT_MS = 120_000;
const RUN_CORRELATION_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
function restoreGenerationWatch() {
  try {
    const saved = JSON.parse(sessionStorage.getItem(GENERATION_WATCH_STORAGE_KEY) || 'null');
    if (!saved || !Array.isArray(saved.runs) || !Number.isSafeInteger(saved.weekId)) return null;
    const runs = saved.runs.filter(run => run && RUN_CORRELATION_ID.test(run.runCorrelationId) &&
      typeof run.languageID === 'string' && typeof run.level === 'string' && Number.isSafeInteger(run.weekId) &&
      Number.isFinite(run.requestedAt));
    if (!runs.length) return null;
    return { runs, results: Array.isArray(saved.results) ? saved.results.filter(result => typeof result === 'string') : [], weekId: saved.weekId };
  } catch { return null; }
}
let generationWatch = restoreGenerationWatch(); // exact request identities survive a page reload in this tab
let lastJobsSnapshot = [];
let lastWatchStatus = '';
let lastWatchTone = '';
let levelTestsRender = null;     // { container, view } — refreshed when a watched run ends
const TERMINAL_JOB_STATES = new Set(['finished', 'failed', 'unknown']);

const JOB_ERROR_HELP = [
  [/judge_failed_or_incomplete/, 'El juez LLM no devolvió un veredicto válido para esa sección. Suele repetirse con el mismo modelo: cambia de modelo o revisa la cuota del proveedor.'],
  [/only \d+ passages|minimum|fewer valid/, 'El proveedor devolvió menos contenido válido del exigido (pasajes o ítems). La respuesta llegó incompleta o no pasó el validador.'],
  [/fetch|network|timeout|ETIMEDOUT/i, 'La llamada al proveedor falló por red o agotó el tiempo de espera.'],
  [/llm_outcome_unknown/, 'No se pudo confirmar si el proveedor completó la petición (corte o timeout). Por seguridad no se reintenta solo: vuelve a lanzarlo con «Regenerar».'],
  [/interrupted_generation/, 'El proceso se interrumpió mientras generaba (reinicio o despliegue del contenedor).'],
  [/reconciliation_required/, 'La semana quedó en estado desconocido y hay que reconciliarla antes de reintentar.'],
  [/attempt_limit_reached/, 'Se agotaron los reintentos automáticos para esta semana y nivel.'],
  [/finished_without_week/, 'La marca dice terminado pero no hay examen guardado: vuelve a generarlo.'],
  [/no llm configuration/, 'No hay configuración de proveedor guardada. Revísala en Configuración de IA.'],
  [/no exam model/, 'No hay plantilla de examen para ese nivel.'],
];
const jobErrorHelp = (job) => {
  const haystack = [job.errorCode, ...(Array.isArray(job.errors) ? job.errors : [])].filter(Boolean).join(' ');
  return JOB_ERROR_HELP.find(([pattern]) => pattern.test(haystack))?.[1]
    ?? 'El pipeline de generación falló sin un motivo más específico. Revisa la configuración del proveedor y vuelve a lanzar la generación.';
};
/** Explains a red generation row: coarse code, real reasons, timestamps and
    what to do next. Content is escaped; nothing from the provider is trusted. */
const showJobError = job => {
  const errors = Array.isArray(job.errors) ? job.errors : [];
  const rows = [
    ['Examen', `${job.languageID} ${job.level} · semana ${job.weekId}`],
    ['Intentos', String(job.attempts ?? 0)],
    ['Inicio', examStamp(job.startedAt)],
    ['Fin', examStamp(job.finishedAt)],
    ['Actualizado', examStamp(job.updatedAt)],
  ];
  detailHtml(`Error de generación · ${job.languageID} ${job.level}`,
    '<div class="d-flex flex-wrap align-items-center gap-2 mb-3">' +
    badgeCell({ getValue: () => job.status }) +
    '<span class="badge badge-outline">' + escapeHtml(job.errorCode ?? 'error') + '</span></div>' +
    '<p class="mb-3">' + escapeHtml(jobErrorHelp(job)) + '</p>' +
    '<table class="table table-sm mb-3"><tbody>' + rows.map(([label, value]) =>
      '<tr><th class="text-secondary fw-normal">' + escapeHtml(label) + '</th><td>' + escapeHtml(value) + '</td></tr>').join('') +
    '</tbody></table>' +
    '<div class="subheader text-secondary mb-1">Detalle del último intento</div>' +
    (errors.length
      ? '<ul class="mb-0">' + errors.map(error => '<li><code>' + escapeHtml(error) + '</code></li>').join('') + '</ul>'
      : '<div class="text-secondary small">Este intento no dejó detalle por sección.</div>') +
    '<div class="alert alert-info mt-3 mb-0 small">Si se repite, revisa la clave, la cuota y el modelo en Configuración de IA y vuelve a lanzar desde Generar y revisar.</div>');
};

function renderJobs(jobs = lastJobsSnapshot) {
  const monitor = $('#jobs-monitor'); if (!monitor) return;
  monitor.innerHTML = jobs.length ? `<div class="table-responsive"><table class="table table-sm table-vcenter"><thead>
      <tr><th>Idioma</th><th>Nivel</th><th>Semana</th><th>Estado</th><th>Intentos</th><th>Error</th><th>Actualizado</th></tr></thead><tbody>${jobs.map((job, index) =>
      '<tr><td>' + escapeHtml(job.languageID) + '</td><td>' + escapeHtml(job.level) + '</td><td>' + escapeHtml(job.weekId) +
      '</td><td>' + badgeCell({ getValue: () => job.status }) + '</td><td>' + escapeHtml(job.attempts) + '</td><td>' +
      (job.errorCode
        ? '<button type="button" class="btn btn-link btn-sm p-0 align-baseline text-danger" data-job-error="' + index +
          '" title="Ver detalle del error">' + escapeHtml(job.errorCode) + '</button>'
        : '<span class="text-secondary">—</span>') +
      '</td><td>' + escapeHtml(examStamp(job.updatedAt)) + '</td></tr>').join('')}
      </tbody></table></div>` : '<div class="text-secondary small">Sin jobs registrados.</div>';
  // Re-attached on every repaint: the monitor re-renders while a run is watched.
  for (const button of monitor.querySelectorAll('[data-job-error]')) {
    button.onclick = () => showJobError(jobs[Number(button.dataset.jobError)]);
  }
}

// One fixed cadence for the whole watch: fast enough to feel live, slow enough
// to stay far from the panel's 600/hour admission limit during a long run.
const GENERATION_POLL_MS = 5_000;

function persistGenerationWatch() {
  try {
    if (generationWatch?.runs.length) sessionStorage.setItem(GENERATION_WATCH_STORAGE_KEY,
      JSON.stringify({ runs: generationWatch.runs, results: generationWatch.results, weekId: generationWatch.weekId }));
    else sessionStorage.removeItem(GENERATION_WATCH_STORAGE_KEY);
  } catch { /* exact run polling remains available during this page lifetime */ }
}

// One timer and one in-flight poll for the whole watch, owned here instead of
// on the watch object: a timer left over from a finished run must never clear
// the timer of the next one (that leaked a second 5 s loop per completion).
let generationTimer = null;
let generationTicking = false;
let levelTestsRenderSequence = 0;

function cancelGenerationTimer() {
  if (generationTimer) { clearTimeout(generationTimer); generationTimer = null; }
}

function scheduleGenerationWatch() {
  if (!generationWatch || generationTimer) return;
  const owner = levelTestsRender?.context;
  if (!owner?.isCurrent()) return;
  const timer = setTimeout(() => {
    generationTimer = null;
    void watchGenerationTick();
  }, GENERATION_POLL_MS);
  generationTimer = timer;
  owner.register(() => {
    clearTimeout(timer);
    if (generationTimer === timer) generationTimer = null;
  });
}

async function watchGenerationTick() {
  // Overlapping ticks would each arm a timer and multiply the cadence.
  if (generationTicking) return;
  const watch = generationWatch;
  if (!watch) return;
  const owner = levelTestsRender?.context;
  if (!owner?.isCurrent()) { cancelGenerationTimer(); return; }
  // The monitor is the only place progress is visible. Leaving the view parks
  // the watch: no timer and no requests. renderExamWeeksView re-arms it when
  // the operator comes back, instead of a ghost timer polling in the dark.
  if (!$('#jobs-monitor')) return;
  generationTicking = true;
  try {
    const runIds = watch.runs.map(run => run.runCorrelationId);
    const status = await api('/v1/admin/level-tests/status?runIds=' + encodeURIComponent(runIds.join(',')),
      { signal: owner.signal });
    if (generationWatch !== watch) return;
    if (!owner.isCurrent()) return;
    const jobs = Array.isArray(status.jobs) ? status.jobs : [];
    const trackedRuns = Array.isArray(status.trackedRuns) ? status.trackedRuns : [];
    const byCorrelation = new Map(trackedRuns.map(entry => [entry.correlationId, entry]));
    const trackedJobs = trackedRuns.filter(entry => entry.registration === 'registered').map(entry => entry.run);
    lastJobsSnapshot = [...new Map([...jobs, ...trackedJobs].map(job => [job.runId ?? `${job.languageID}:${job.level}:${job.weekId}:${job.attempts}`, job])).values()];
    renderJobs(lastJobsSnapshot);
    const pending = [], running = [], done = [];
    for (const run of watch.runs) {
      const entry = byCorrelation.get(run.runCorrelationId);
      if (entry?.registration === 'registered' && TERMINAL_JOB_STATES.has(entry.run.status)) {
        const job = entry.run;
        const key = `${run.languageID}:${run.level}`;
        watch.results.push(`${key} → ${job.status} (#${job.attemptOrdinal})${job.errorCode ? ` (${job.errorCode})` : ''}`);
        done.push(run.runCorrelationId);
        if (owner.isCurrent()) toast(`${key}: generación ${job.status === 'finished' ? 'completada' : job.status}${job.errorCode ? ` (${job.errorCode})` : ''}`,
          job.status === 'finished' ? 'success' : 'danger');
      } else if (entry?.registration === 'registered') {
        running.push(`${run.languageID}:${run.level}`);
      } else {
        pending.push(Date.now() - run.requestedAt >= GENERATION_REGISTRATION_TIMEOUT_MS
          ? `${run.languageID}:${run.level} (sin registro; estado desconocido)`
          : `${run.languageID}:${run.level}`);
      }
    }
    if (done.length) watch.runs = watch.runs.filter(run => !done.includes(run.runCorrelationId));
    persistGenerationWatch();
    lastWatchStatus = [
      running.length ? `En marcha: ${running.join(', ')}` : '',
      pending.length ? `Pendiente: ${pending.join(', ')}` : '',
      watch.results.join(' · '),
    ].filter(Boolean).join(' — ') || 'Esperando al runner de generación…';
    const line = $('#launch-status');
    const paint = tone => {
      lastWatchTone = tone;
      if (!line) return;
      line.textContent = lastWatchStatus;
      line.className = `small mt-1 fw-bold ${tone}`;
    };
    if (!watch.runs.length) {
      generationWatch = null;
      cancelGenerationTimer();
      persistGenerationWatch();
      lastWatchStatus = `Completado: ${watch.results.join(' · ')}`;
      paint(watch.results.every(result => result.includes('→ finished')) ? 'text-success' : 'text-danger');
      // The runner is done: refresh the weeks table so the new exams show up
      // ready for review / approve without a manual reload. Never repaint if
      // the operator moved to another view (they share the same container).
      if (levelTestsRender?.context.isCurrent() && $('#exam-weeks-card'))
        void renderExamWeeksView(levelTestsRender.container, levelTestsRender.view, levelTestsRender.context);
      return;
    }
    paint('text-primary');
  } catch { /* polling is best effort; retry on the next tick */ }
  finally { generationTicking = false; }
  if (owner.isCurrent()) scheduleGenerationWatch();
}

function startGenerationWatch(runs, weekId) {
  const validRuns = runs.filter(run => run && RUN_CORRELATION_ID.test(run.runCorrelationId));
  if (!validRuns.length) return;
  if (generationWatch && generationWatch.weekId === weekId) {
    // Same week: merge keys and keep the pending tick, so a re-render never
    // leaves two timers polling the same status endpoint.
    const merged = new Map(generationWatch.runs.map(run => [run.runCorrelationId, run]));
    for (const run of validRuns) merged.set(run.runCorrelationId, run);
    generationWatch = { ...generationWatch, runs: [...merged.values()] };
  } else {
    cancelGenerationTimer();
    generationWatch = { runs: validRuns, results: [], weekId };
  }
  persistGenerationWatch();
  lastWatchStatus = ''; lastWatchTone = '';
  scheduleGenerationWatch();
}

const LANGUAGE_CODES = ['en', 'es', 'fr', 'de', 'it', 'pt', 'nb', 'zh'];
const CEFR_LEVELS = ['A1', 'A2', 'B1', 'B2', 'C1'];

const MODERATION_COPY = {
  approve: { title: 'Aprobar borrador', done: 'Borrador aprobado' },
  reject: { title: 'Rechazar borrador', done: 'Borrador rechazado' },
  retire: { title: 'Retirar examen', done: 'Examen retirado: deja de servirse a los alumnos' },
};

/** Shared moderation step: the listing and the review page approve, reject and
    retire through the same reason dialog and endpoints. Returns false when
    cancelled. */
async function moderateWeek(week, action, trigger) {
  const copy = MODERATION_COPY[action];
  const decision = await ask(copy.title, `${week.languageID} ${week.level} · semana ${week.weekId}`);
  if (!decision.reason || decision.reason.trim().length < 3) return false;
  if (trigger) trigger.disabled = true;
  await api(`/v1/admin/level-tests/${encodeURIComponent(week.id)}/${action}`,
    { method: 'POST', body: JSON.stringify({ reason: decision.reason }) });
  toast(copy.done);
  return true;
}

/* ---- provider configuration (rare, sensitive: its own view) ---- */
async function renderAiConfig(container) {
  const [config, drafts] = await Promise.all([
    api('/v1/admin/llm-config'), api('/v1/admin/level-tests/weeks?status=draft&limit=1')
  ]);
  const pending = Number(drafts.total ?? 0);
  const providers = config.providers.map(item =>
    '<option value="' + escapeHtml(item.provider) + '"' + (item.provider === config.provider ? ' selected' : '') + '>' + escapeHtml(item.provider) + '</option>').join('');
  const keyPlaceholder = config.keyHint ? 'Guardada: ' + escapeHtml(config.keyHint) + ' · vacío conserva la clave' : 'Sin clave persistida';
  container.innerHTML = `<div class="row row-cards">
    <div class="col-lg-8"><div class="card" id="ia-config-card"><div class="card-header"><h3 class="card-title">Proveedor de generación</h3></div><div class="card-body">
      <div class="alert alert-info">Los valores proceden del servidor. La prueba usa solo la configuración guardada y no genera exámenes.</div>
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
    <div class="col-lg-4"><div class="card"><div class="card-header"><h3 class="card-title">Dónde se genera</h3></div><div class="card-body">
      <p class="text-secondary">Esta pantalla solo guarda credenciales, modelo y modos. Lanzar, seguir y aprobar exámenes vive en <a href="#exam-weeks">Generar y revisar</a>; los resultados de alumnos, en <a href="#exam-results">Resultados</a>.</p>
      <div class="d-flex justify-content-between"><span>Exámenes por aprobar</span><strong>${pending}</strong></div>
      <div class="text-secondary small mt-2">Cambiar el modelo afecta a la próxima generación, nunca a los exámenes ya creados.</div>
    </div></div></div>
  </div>`;
  const catalogFor = name => config.providers.find(item => item.provider === name);
  const modelOptions = (models, selected) => {
    const list = models.includes(selected) || !selected ? models : [...models, selected];
    return list.map(model => `<option value="${escapeHtml(model)}"${model === selected ? ' selected' : ''}>${escapeHtml(model)}</option>`).join('');
  };
  const renderProviderForm = (selectedModel, resetBaseUrl = false) => {
    const entry = catalogFor($('#lt-provider').value);
    if (resetBaseUrl) $('#lt-base-url').value = entry?.baseUrl ?? '';
    // Auto-filled but editable: operators may point a provider at a proxy or
    // a plan-specific gateway without editing presets in code.
    $('#lt-model-wrap').innerHTML = entry?.models?.length
      ? `<select class="form-select" id="lt-model">${modelOptions(entry.models, selectedModel)}</select>`
      : `<input class="form-control" id="lt-model" maxlength="200" value="${escapeHtml(selectedModel)}">`;
    $('#lt-model')?.addEventListener('input', () => refreshDirty());
    $('#lt-model')?.addEventListener('change', () => refreshDirty());
  };
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
      note.innerHTML = `<a href="#exam-weeks" class="text-warning">Hay cambios sin guardar · ${pending} exámenes por aprobar</a>`;
    } else { note.textContent = 'La prueba solo utiliza la configuración guardada y no genera exámenes.'; }
  };
  renderProviderForm(config.model);
  $('#lt-provider').addEventListener('change', () => {
    const entry = catalogFor($('#lt-provider').value);
    const current = $('#lt-model').value;
    const next = entry?.models?.includes(current) ? current : entry?.models?.[0] ?? '';
    renderProviderForm(next, true);
    refreshDirty();
  });
  fields.forEach(field => { const input = $('#lt-' + field); input.addEventListener('input', refreshDirty); input.addEventListener('change', refreshDirty); });
  $('#lt-save').onclick = async () => {
    const value = formValue(), payload = { provider: value.provider, model: value.model, baseUrl: value.baseUrl,
      publishMode: value.publishMode, verifyMode: value.verifyMode, variants: value.variants };
    if (value.apiKey) payload.apiKey = value.apiKey;
    const button = $('#lt-save'); button.disabled = true;
    try { await api('/v1/admin/llm-config', { method: 'PUT', body: JSON.stringify(payload) });
      toast('Configuración guardada'); await renderAiConfig(container); }
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
  refreshDirty();
}

/* ---- generation: launch, monitor, listing and moderation (daily surface) ---- */
async function renderExamWeeksView(container, view = {}, context = activeViewContext) {
  // ISO week id mirroring the server's weekIndex(): year * 100 + ISO week.
  const now = new Date();
  const monday = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const dayNum = monday.getUTCDay() || 7;
  monday.setUTCDate(monday.getUTCDate() + 4 - dayNum);
  const yearStart = new Date(Date.UTC(monday.getUTCFullYear(), 0, 1));
  const currentWeekId = monday.getUTCFullYear() * 100 + Math.ceil(((monday.getTime() - yearStart.getTime()) / 86_400_000 + 1) / 7);
  const renderState = { container, view, context, sequence: ++levelTestsRenderSequence };
  levelTestsRender = renderState;
  const filters = view.filters ?? {};
  const params = new URLSearchParams({ limit: '50', offset: String(view.offset ?? 0) });
  for (const [key, value] of Object.entries(filters)) if (value) params.set(key, String(value));
  const [status, weeks] = await Promise.all([
    api('/v1/admin/level-tests/status', { signal: context.signal }),
    api('/v1/admin/level-tests/weeks?' + params, { signal: context.signal })
  ]);
  if (!context.isCurrent() || levelTestsRender !== renderState) return;
  // Paint the freshest jobs immediately; the watcher keeps them current while
  // a generation launched from this session is still in flight.
  lastJobsSnapshot = Array.isArray(status.jobs) ? status.jobs : [];
  // Also pick up runs started elsewhere (scheduler, CLI, another operator) so a
  // reload never leaves the operator blind about an in-flight generation.
  const runningJobs = status.jobs.filter(job => job.status === 'running' && job.weekId === currentWeekId && job.runId);
  if (runningJobs.length) startGenerationWatch(runningJobs.map(job => ({ runCorrelationId: job.runId,
    languageID: job.languageID, level: job.level, weekId: job.weekId, requestedAt: Date.now() })), currentWeekId);
  const job = status.latestJob;
  const jobHtml = job
    ? '<hr><strong>Último job:</strong> ' + badgeCell({ getValue: () => job.status }) + ' · ' +
      escapeHtml(job.languageID) + ' ' + escapeHtml(job.level) + ' · semana ' + escapeHtml(job.weekId) +
      '<div class="text-secondary small">Actualizado: ' + escapeHtml(job.updatedAt || '—') +
      ' · intentos: ' + escapeHtml(job.attempts) + ' · error: ' +
      (job.errorCode
        ? '<button type="button" class="btn btn-link btn-sm p-0 align-baseline text-danger" id="latest-job-error">' +
          escapeHtml(job.errorCode) + '</button>'
        : '—') + '</div>'
    : '<hr><div class="text-secondary">Todavía no hay jobs registrados.</div>';
  const rowCells = item => '<tr><td>' + escapeHtml(item.weekId) + '</td><td>' + escapeHtml(item.languageID) +
    '</td><td>' + escapeHtml(item.level) + '</td><td>' + badgeCell({ getValue: () => item.status }) +
    '</td><td>' + escapeHtml(item.variantCount) + '</td><td>' + escapeHtml(item.model || '—') + '</td><td>' +
    escapeHtml(originLabel(item.origin)) + '</td><td>' +
    escapeHtml((item.createdAt || '—').slice(0, 16).replace('T', ' ')) + '</td><td>' +
    escapeHtml((item.approvedAt || '—').slice(0, 16).replace('T', ' ')) + '</td><td class="text-end text-nowrap">' +
    '<a class="btn btn-sm btn-outline-secondary me-1" href="#exam/' + escapeHtml(item.id) + '">Revisar</a>' +
    (item.status === 'draft' ?
      '<button class="btn btn-sm btn-success me-1" data-week-approve="' + escapeHtml(item.id) + '">Aprobar</button>' +
      '<button class="btn btn-sm btn-outline-danger" data-week-reject="' + escapeHtml(item.id) + '">Rechazar</button>' : '') +
    (item.status === 'approved' ?
      '<button class="btn btn-sm btn-outline-danger" data-week-retire="' + escapeHtml(item.id) + '">Retirar</button>' : '') +
    (item.weekId === currentWeekId && ['draft', 'rejected', 'retired'].includes(item.status) ?
      '<button class="btn btn-sm btn-outline-warning ms-1" data-week-regenerate="' + escapeHtml(item.languageID) +
      ':' + escapeHtml(item.level) + '">Regenerar</button>' : '') +
    '</td></tr>';
  const rows = weeks.items.map(rowCells).join('') ||
    '<tr><td colspan="10" class="text-secondary">No hay exámenes para este filtro.</td></tr>';
  const STATES = ['draft', 'approved', 'rejected', 'retired'];
  const option = (value, label, current) =>
    '<option value="' + escapeHtml(value) + '"' + (value === current ? ' selected' : '') + '>' + escapeHtml(label) + '</option>';
  const filterBar = `<div class="row g-2 mb-2">
    <div class="col-auto"><select class="form-select form-select-sm" id="wf-status" aria-label="Filtrar por estado">${option('', 'Estado: todos', filters.status)}${STATES.map(state => option(state, state, filters.status)).join('')}</select></div>
    <div class="col-auto"><select class="form-select form-select-sm" id="wf-language" aria-label="Filtrar por idioma">${option('', 'Idioma: todos', filters.language)}${LANGUAGE_CODES.map(lang => option(lang, lang, filters.language)).join('')}</select></div>
    <div class="col-auto"><select class="form-select form-select-sm" id="wf-level" aria-label="Filtrar por nivel">${option('', 'Nivel: todos', filters.level)}${CEFR_LEVELS.map(level => option(level, level, filters.level)).join('')}</select></div>
    <div class="col-auto"><input class="form-control form-control-sm" id="wf-week-from" aria-label="Semana desde" placeholder="Semana ≥" value="${escapeHtml(filters.week_from ?? '')}"></div>
    <div class="col-auto"><input class="form-control form-control-sm" id="wf-week-to" aria-label="Semana hasta" placeholder="Semana ≤" value="${escapeHtml(filters.week_to ?? '')}"></div>
    <div class="col-auto"><input class="form-control form-control-sm" id="wf-created-from" aria-label="Fecha de creación desde" placeholder="Creado ≥ AAAA-MM-DD" value="${escapeHtml(filters.created_from ?? '')}"></div>
    <div class="col-auto"><input class="form-control form-control-sm" id="wf-created-to" aria-label="Fecha de creación hasta" placeholder="Creado ≤ AAAA-MM-DD" value="${escapeHtml(filters.created_to ?? '')}"></div>
    <div class="col-auto"><button class="btn btn-sm btn-primary" id="wf-apply">Filtrar</button></div>
  </div>`;
  container.innerHTML = `<div class="row row-cards">
    <div class="col-12"><div class="card"><div class="card-header"><h3 class="card-title">Lanzar generación</h3>
      <div class="card-actions text-secondary small">Configuración del proveedor en <a href="#ia-config">Configuración de IA</a></div></div><div class="card-body">
      <div class="row g-3 align-items-end">
        <div class="col-auto"><label class="form-label" for="gen-language">Idioma</label>
          <select class="form-select" id="gen-language">${LANGUAGE_CODES.map(lang => `<option>${lang}</option>`).join('')}</select></div>
        <div class="col-auto"><label class="form-label" for="gen-levels">Niveles</label>
          <select class="form-select" id="gen-levels"><option value="all">Todos los niveles</option>${CEFR_LEVELS.map(level => `<option>${level}</option>`).join('')}</select></div>
        <div class="col-auto"><button class="btn btn-primary" id="gen-launch">Lanzar generación</button></div>
        <div class="col-auto"><span class="text-secondary small" id="scheduler-note">Scheduler: ${status.schedule.enabled ? 'configurado' : 'no configurado'} · activo en este proceso: ${status.scheduler.activeInProcess ? 'sí' : 'no'}</span></div>
      </div>
      <div class="text-secondary small mt-1">Genera la semana actual donde aún no haya examen: los aprobados y las semanas ya existentes se omiten. Para rehacer un borrador o una semana rechazada usa «Regenerar» en la tabla.</div>
      <div class="small mt-1 fw-bold ${lastWatchTone}" id="launch-status">${escapeHtml(lastWatchStatus)}</div>
      ${jobHtml}
      <div id="jobs-monitor" class="mt-2"></div>
    </div></div></div>
    <div class="col-12"><div class="card" id="exam-weeks-card"><div class="card-header"><h3 class="card-title">Exámenes generados</h3></div><div class="card-body">${filterBar}
    <div class="table-responsive"><table class="table table-vcenter card-table"><thead><tr><th>Semana</th><th>Idioma</th><th>Nivel</th><th>Estado</th><th>Variantes</th><th>Modelo</th><th>Origen</th><th>Creado</th><th>Aprobado</th><th></th></tr></thead><tbody>${rows}</tbody></table></div></div><div class="card-footer d-flex justify-content-between align-items-center">
      <button class="btn btn-sm btn-outline-secondary" id="weeks-prev" ${weeks.offset === 0 ? 'disabled' : ''}>Anterior</button>
      <span class="text-secondary small">Exámenes ${weeks.total ? weeks.offset + 1 : 0}–${weeks.offset + weeks.items.length} de ${weeks.total}</span>
      <button class="btn btn-sm btn-outline-secondary" id="weeks-next" ${weeks.items.length < weeks.limit ? 'disabled' : ''}>Siguiente</button>
    </div></div></div></div>`;
  $('#wf-apply').onclick = () => {
    const value = name => $('#wf-' + name)?.value.trim() ?? '';
    void renderExamWeeksView(container, {
      filters: { status: value('status'), language: value('language'), level: value('level'),
        week_from: value('week-from'), week_to: value('week-to'),
        created_from: value('created-from'), created_to: value('created-to') }, offset: 0 }, context);
  };
  $('#weeks-prev').onclick = () => { if (weeks.offset > 0) void renderExamWeeksView(container, { filters, offset: Math.max(0, weeks.offset - weeks.limit) }, context); };
  $('#weeks-next').onclick = () => { if (weeks.items.length === weeks.limit) void renderExamWeeksView(container, { filters, offset: weeks.offset + weeks.limit }, context); };
  for (const action of ['approve', 'reject', 'retire']) container.querySelectorAll('[data-week-' + action + ']').forEach(button => button.onclick = async () => {
    const row = weeks.items.find(item => item.id === button.dataset['week' + action[0].toUpperCase() + action.slice(1)]);
    if (!row) return;
    try { if (await moderateWeek(row, action, button)) { if (context.isCurrent()) await renderExamWeeksView(container, view, context); } else button.disabled = false; }
    catch (error) { if (context.isCurrent()) { toast('Error: ' + (error.code || error.message), 'danger'); button.disabled = false; } }
  });
  // Repaint the watch state on re-render and keep ticking while mounted.
  renderJobs();
  if ($('#latest-job-error')) context.listen($('#latest-job-error'), 'click', () => showJobError(job));
  scheduleGenerationWatch();
  $('#gen-launch').onclick = async () => {
    const languageID = $('#gen-language').value, levels = $('#gen-levels').value;
    const target = `${languageID} · ${levels === 'all' ? 'todos los niveles' : levels}`;
    const decision = await ask('Generar exámenes', target);
    if (!decision.reason || decision.reason.trim().length < 3) return toast('Motivo obligatorio', 'warning');
    try {
      const outcome = await api('/v1/admin/level-tests/generate', { method: 'POST',
        body: JSON.stringify({ languageID, levels, reason: decision.reason }) });
      const launched = outcome.keys.filter(key => key.status === 'started');
      const approved = outcome.keys.filter(key => key.status === 'skipped_approved').length;
      const existing = outcome.keys.filter(key => key.status === 'skipped_existing').length;
      if (context.isCurrent()) toast(`Lanzados ${launched.length} examen(es)` +
        (approved ? ` · ${approved} aprobados omitidos` : '') +
        (existing ? ` · ${existing} ya existentes (usa Regenerar)` : ''),
        launched.length ? 'success' : 'warning');
      startGenerationWatch(launched.map(key => ({ runCorrelationId: key.runCorrelationId, languageID: key.languageID,
        level: key.level, weekId: currentWeekId, requestedAt: Date.now() })), currentWeekId);
      if (context.isCurrent()) void watchGenerationTick();
    } catch (error) { if (context.isCurrent()) toast('Error: ' + (error.code || error.message), 'danger'); }
  };
  container.querySelectorAll('[data-week-regenerate]').forEach(button => button.onclick = async () => {
    const [languageID, level] = button.dataset.weekRegenerate.split(':');
    const decision = await ask('Regenerar examen', languageID + ':' + level);
    if (!decision.reason || decision.reason.trim().length < 3) return toast('Motivo obligatorio', 'warning');
    button.disabled = true;
    try { const outcome = await api('/v1/admin/level-tests/regenerate', { method: 'POST',
        body: JSON.stringify({ languageID, level, reason: decision.reason }) });
      if (context.isCurrent()) toast('Regeneración iniciada');
      startGenerationWatch([{ runCorrelationId: outcome.runCorrelationId, languageID, level, weekId: currentWeekId, requestedAt: Date.now() }], currentWeekId);
      if (context.isCurrent()) void watchGenerationTick();
    } catch (error) { if (context.isCurrent()) { toast('Error: ' + (error.code || error.message), 'danger'); button.disabled = false; } }
  });
}


/* ---- exam review page: the exam as the student takes it, never raw JSON ---- */
const EXAM_SECTION_LABELS = { recognition: 'Reconocimiento', writing: 'Escritura', reading: 'Lectura', listening: 'Escucha' };
const examSectionLabel = section => EXAM_SECTION_LABELS[section] ?? section;
const examStamp = value => value ? String(value).slice(0, 16).replace('T', ' ') : '—';
const examAccepted = item => new Set((Array.isArray(item.answers) ? item.answers : [])
  .map(answer => String(answer).trim().toLowerCase()));

const examOptionList = item => {
  const options = Array.isArray(item.options) ? item.options : [];
  if (!options.length) return '';
  const accepted = examAccepted(item);
  return '<ul class="exam-options">' + options.map(option => {
    const correct = accepted.has(String(option).trim().toLowerCase());
    return '<li class="exam-option' + (correct ? ' exam-option-ok' : '') + '">' +
      '<span class="exam-mark">' + (correct ? '✓' : '') + '</span><span>' + escapeHtml(option) + '</span></li>';
  }).join('') + '</ul>';
};

const examAnswerChips = item => {
  const answers = Array.isArray(item.answers) ? item.answers : [];
  if (!answers.length) return '<div class="text-secondary small">Sin respuestas aceptadas.</div>';
  return '<div class="exam-answers"><span class="text-secondary small me-1">Aceptadas:</span>' +
    answers.map(answer => '<span class="badge bg-success-lt">' + escapeHtml(answer) + '</span>').join(' ') + '</div>';
};

const examItemCard = (item, verdict, index) => {
  const flagged = verdict?.verdict === 'flagged';
  const judge = verdict
    ? '<span class="badge bg-' + (flagged ? 'warning' : 'success') + '-lt">Juez: ' +
      (flagged ? 'marcado' : 'ok') + '</span>'
    : '<span class="badge bg-secondary-lt">Juez: sin revisar</span>';
  const reason = verdict?.reason
    ? '<div class="exam-verdict-reason"><i class="ti ti-info-circle me-1"></i>' + escapeHtml(verdict.reason) + '</div>' : '';
  return '<article class="exam-item' + (flagged ? ' exam-item-flagged' : '') + '">' +
    '<div class="exam-item-head"><span class="exam-index">' + index + '</span>' +
    '<span class="badge badge-outline">' + escapeHtml(item.kind ?? 'item') + '</span>' +
    '<span class="cell-mono text-secondary">' + escapeHtml(item.id ?? '') + '</span>' +
    '<span class="ms-auto">' + judge + '</span></div>' +
    '<div class="exam-prompt">' + escapeHtml(item.prompt ?? '(sin enunciado)') + '</div>' +
    examOptionList(item) + examAnswerChips(item) +
    (item.explain ? '<div class="exam-explain"><i class="ti ti-bulb me-1"></i>' + escapeHtml(item.explain) + '</div>' : '') +
    reason + '</article>';
};

const examPassageCard = (passage, items, verdicts, start) =>
  '<div class="exam-passage"><div class="exam-passage-head"><i class="ti ti-file-text me-1"></i>' +
  escapeHtml(passage.title ?? passage.id ?? 'Texto') + '</div>' +
  '<div class="exam-passage-text">' + escapeHtml(passage.text ?? '') + '</div></div>' +
  items.map((item, offset) => examItemCard(item, verdicts.get(item.id), start + offset)).join('');

const examItemCount = groups => groups.reduce((total, group) => total + group.items.length, 0);

const examSectionsHtml = (sections, verdicts) => sections.map(({ section, groups }) => {
  let counter = 0;
  const body = groups.map(group => {
    if (group.passage) {
      const html = examPassageCard(group.passage, group.items, verdicts, counter + 1);
      counter += group.items.length;
      return html;
    }
    // An item whose passage is missing renders standalone instead of vanishing.
    return group.items.map(item => examItemCard(item, verdicts.get(item.id), ++counter)).join('');
  }).join('');
  return '<section class="exam-section"><h3 class="exam-section-title">' + escapeHtml(examSectionLabel(section)) +
    '<span class="text-secondary small ms-2">' + examItemCount(groups) + ' ítems</span></h3>' + body + '</section>';
}).join('');

/** Variant items arrive flat, with `section` and an optional `passageId`: group
    them in first-appearance order and render each passage once, before its questions. */
const examVariantSections = variant => {
  const items = Array.isArray(variant?.items) ? variant.items : [];
  const passages = new Map((Array.isArray(variant?.passages) ? variant.passages : [])
    .filter(passage => passage && passage.id).map(passage => [passage.id, passage]));
  const order = [];
  const bySection = new Map();
  for (const item of items) {
    const section = item.section ?? 'recognition';
    if (!bySection.has(section)) { bySection.set(section, []); order.push(section); }
    const bucket = bySection.get(section);
    const passage = item.passageId ? passages.get(item.passageId) : undefined;
    if (!passage) { bucket.push({ items: [item] }); continue; }
    const group = bucket.find(entry => entry.passage?.id === passage.id);
    if (group) group.items.push(item); else bucket.push({ passage, items: [item] });
  }
  return order.map(section => ({ section, groups: bySection.get(section) }));
};

/** The pool keeps passages nested; normalise it to the same shape as a variant. */
const examPoolSections = pool => [['recognition', 'recognition'], ['writing', 'writing'],
  ['reading', 'readingPassages'], ['listening', 'listeningPassages']].flatMap(([section, key]) => {
  const entries = Array.isArray(pool?.[key]) ? pool[key] : [];
  if (!entries.length) return [];
  return [{ section, groups: entries.map(entry => entry && Array.isArray(entry.items)
    ? { passage: entry, items: entry.items }
    : { items: entry ? [entry] : [] }) }];
});

const examVariantMeta = variant => {
  const thresholds = variant?.thresholds && typeof variant.thresholds === 'object'
    ? Object.entries(variant.thresholds) : [];
  return '<div class="exam-variant-meta">' +
    '<span>' + (Array.isArray(variant?.items) ? variant.items.length : 0) + ' ítems</span>' +
    (variant?.contentHash ? '<span class="cell-mono">#' + escapeHtml(String(variant.contentHash).slice(0, 12)) + '</span>' : '') +
    thresholds.map(([key, value]) => '<span>' + escapeHtml(key) + ': ' + escapeHtml(value) + '%</span>').join('') +
    '</div>';
};

async function renderExamReview(container, params = {}, rawId) {
  const weekId = String(rawId ?? '').trim();
  const backLink = '<a class="btn btn-sm btn-outline-secondary" href="#exam-weeks">Volver a Generar y revisar</a>';
  if (!weekId) {
    container.innerHTML = '<div class="alert alert-warning mt-3"><h3 class="mb-2">Falta el identificador</h3>' + backLink + '</div>';
    return;
  }
  let week;
  try { week = await api('/v1/admin/level-tests/weeks/' + encodeURIComponent(weekId)); }
  catch (error) {
    if (error.message === 'unauthenticated') throw error;
    // A deleted or mistyped id is a state, not a stack trace.
    container.innerHTML = '<div class="alert alert-warning mt-3"><h3 class="mb-2">Examen no encontrado</h3>' +
      '<p class="text-secondary">No existe un examen con el identificador <span class="cell-mono">' +
      escapeHtml(weekId) + '</span>.</p>' + backLink + '</div>';
    return;
  }
  const variants = Array.isArray(week.variants) ? week.variants : [];
  const verdicts = new Map((Array.isArray(week.review) ? week.review : [])
    .filter(entry => entry && entry.id).map(entry => [entry.id, entry]));
  const flagged = [...verdicts.values()].filter(entry => entry.verdict === 'flagged');
  const status = String(week.status ?? '');
  const tone = BADGE_COLORS[status] ?? 'secondary';
  const tabs = variants.map((_, index) => ({ id: 'variant-' + index, label: 'Variante ' + (index + 1) }))
    .concat([{ id: 'pool', label: 'Pool completo' }]);

  const banner = flagged.length
    ? '<div class="col-12"><div class="alert alert-warning"><div class="d-flex"><i class="ti ti-alert-triangle me-2"></i>' +
      '<div><strong>' + flagged.length + ' ítem' + (flagged.length === 1 ? '' : 's') + ' marcado' + (flagged.length === 1 ? '' : 's') +
      ' por el juez</strong><div class="small">Revisa el motivo en cada tarjeta antes de aprobar.</div></div></div></div></div>'
    : '';
  // A retired week explains itself: a manual retirement carries the operator
  // motive, while a superseded row only carries the regeneration that replaced it.
  const retirement = status === 'retired'
    ? '<div class="col-12"><div class="alert alert-warning"><strong>Semana retirada.</strong> ' +
      (week.retiredReason
        ? escapeHtml(week.retiredReason) + ' <span class="text-secondary">(' + escapeHtml(examStamp(week.retiredAt)) + ')</span>'
        : 'Reemplazada por una regeneración.') +
      '</div></div>'
    : '';

  container.innerHTML = '<div class="row row-cards">' +
    '<div class="col-12"><div class="card"><div class="card-body">' +
    '<div class="d-flex flex-wrap justify-content-between align-items-start gap-3">' +
    '<div><div class="text-secondary small">Idioma ' + escapeHtml(week.languageID) + ' · nivel ' + escapeHtml(week.level) +
    ' · semana ' + escapeHtml(week.weekId) + '</div>' +
    '<h2 class="mb-2">' + escapeHtml(week.languageID) + ' ' + escapeHtml(week.level) + ' · semana ' + escapeHtml(week.weekId) + '</h2>' +
    '<div class="d-flex align-items-center gap-2"><span class="badge bg-' + tone + '-lt">' + escapeHtml(status) + '</span>' +
    '<span class="text-secondary small">' + variants.length + ' variante' + (variants.length === 1 ? '' : 's') + '</span>' +
    '<span class="text-secondary small">' + escapeHtml(week.generator || '—') + '</span></div></div>' +
    '<div class="text-end text-secondary small">' +
    '<div>Modelo: <strong>' + escapeHtml(week.model || '—') + '</strong></div>' +
    '<div>Origen: ' + escapeHtml(originLabel(week.origin)) + '</div>' +
    '<div>Juez: ' + escapeHtml(week.judgeMode || '—') + '</div>' +
    '<div>Tokens: ' + number(week.tokensIn ?? 0) + ' entrada · ' + number(week.tokensOut ?? 0) + ' salida</div>' +
    '<div>Creado: ' + escapeHtml(examStamp(week.createdAt)) + '</div>' +
    (week.approvedAt ? '<div>Aprobado: ' + escapeHtml(examStamp(week.approvedAt)) + '</div>' : '') +
    '<div>Prompt v' + escapeHtml(week.promptVersion ?? '—') + ' · juez v' + escapeHtml(week.judgeVersion ?? '—') + '</div>' +
    '<div class="mt-2">' + backLink + '</div></div></div></div></div></div>' + banner + retirement +
    '<div class="col-12"><div class="card"><div class="card-header">' +
    '<ul class="nav nav-tabs card-header-tabs">' + tabs.map(tab =>
      '<li class="nav-item"><button class="nav-link" type="button" data-exam-tab="' + tab.id + '">' +
      escapeHtml(tab.label) + '</button></li>').join('') + '</ul></div>' +
    '<div class="card-body" id="exam-body"></div></div></div>' +
    (status === 'draft'
      ? '<div class="col-12"><div class="card exam-actions"><div class="card-body d-flex flex-wrap justify-content-between align-items-center gap-2">' +
        '<span class="text-secondary small">Aprobar publica esta semana y la sirve a los alumnos. Cada acción queda auditada con su motivo.</span>' +
        '<span class="d-flex gap-2"><button class="btn btn-outline-danger" id="exam-reject">Rechazar</button>' +
        '<button class="btn btn-success" id="exam-approve">Aprobar</button></span></div></div></div>' : '') +
    (status === 'approved'
      ? '<div class="col-12"><div class="card exam-actions"><div class="card-body d-flex flex-wrap justify-content-between align-items-center gap-2">' +
        '<span class="text-secondary small">Retirar deja de servir esta semana a los alumnos y conserva el contenido y la evaluación. Para rehacerla, usa «Regenerar».</span>' +
        '<button class="btn btn-outline-danger" id="exam-retire">Retirar</button></div></div></div>' : '') +
    '</div>';

  const paint = tab => {
    const body = $('#exam-body');
    if (!body) return;
    if (tab === 'pool') {
      body.innerHTML = examSectionsHtml(examPoolSections(week.pool), verdicts) ||
        '<div class="text-secondary">El pool está vacío.</div>';
    } else {
      const variant = variants[Number(String(tab).replace('variant-', ''))] ?? {};
      body.innerHTML = examVariantMeta(variant) +
        (examSectionsHtml(examVariantSections(variant), verdicts) || '<div class="text-secondary">La variante no tiene ítems.</div>');
    }
    for (const button of container.querySelectorAll('[data-exam-tab]')) {
      button.classList.toggle('active', button.dataset.examTab === tab);
    }
  };
  container.querySelectorAll('[data-exam-tab]').forEach(button => {
    button.onclick = () => paint(button.dataset.examTab);
  });
  paint(tabs[0].id);

  for (const action of ['approve', 'reject', 'retire']) {
    const button = $('#exam-' + action);
    if (!button) continue;
    button.onclick = async () => {
      button.disabled = true;
      try {
        if (await moderateWeek(week, action)) await renderExamReview(container, params, weekId);
        else button.disabled = false;
      } catch (error) { toast('Error: ' + (error.code || error.message), 'danger'); button.disabled = false; }
    };
  }
}


/* ---- exam attempts (per-student results) ---- */
// Renders may resolve out of order (fast filter after slow one): a stale
// response must never overwrite the view painted by a newer one.
let attemptsRenderEpoch = 0;
async function renderExamAttempts(container, routeParams = {}, filters = {}) {
  const epoch = ++attemptsRenderEpoch;
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
  if (epoch !== attemptsRenderEpoch) return;
  const studentEmail = merged.studentEmail || (merged.account ? attempts.items[0]?.email : null);
  // A re-render landing while the operator types must not wipe the draft input.
  const draftEmail = $('#wa-email')?.value ?? merged.email ?? '';
  const rows = attempts.items.map(item =>
    '<tr><td class="cell-mono" title="' + escapeHtml(item.attemptId) + '">' + escapeHtml(item.attemptId.slice(0, 8)) + '…</td>' +
    '<td>' + escapeHtml(item.email || '—') + '</td><td>' + escapeHtml(item.languageID) + ' ' + escapeHtml(item.level) +
    '</td><td>' + escapeHtml(item.weekId) + '</td><td>' + escapeHtml(item.source) + '</td><td>' +
    (item.state === 'completed' ? badgeCell({ getValue: () => 'settled' }) + ' ' : '<span class="badge bg-info-lt">in_progress</span> ') +
    (item.passed === null ? '—' : item.passed ? '<span class="badge bg-success-lt">passed</span>' : '<span class="badge bg-danger-lt">failed</span>') +
    '</td><td>' + (item.overall === null ? '—' : item.overall + ' %') + '</td><td>' +
    escapeHtml((item.startedAt || '—').slice(0, 16).replace('T', ' ')) + '</td>' +
    '<td class="text-end"><button class="btn btn-sm btn-outline-secondary" data-attempt-detail="' +
    escapeHtml(item.accountId) + ':' + escapeHtml(item.attemptId) + '">Ver</button></td></tr>').join('') ||
    '<tr><td colspan="9" class="text-secondary">Sin resultados para este filtro.</td></tr>';
  const filterBar = `<div class="row g-2 mb-2 align-items-end">
    <div class="col"><input class="form-control form-control-sm" id="wa-email" placeholder="Email contiene" value="${escapeHtml(draftEmail)}"></div>
    <div class="col-auto"><select class="form-select form-select-sm" id="wa-language"><option value="">Idioma: todos</option>${['en','es','fr','de','it','pt','nb','zh'].map(lang => `<option${lang === merged.language ? ' selected' : ''}>${lang}</option>`).join('')}</select></div>
    <div class="col-auto"><select class="form-select form-select-sm" id="wa-state"><option value="">Estado: todos</option><option${merged.state === 'completed' ? ' selected' : ''}>completed</option><option${merged.state === 'in_progress' ? ' selected' : ''}>in_progress</option></select></div>
    <div class="col-auto"><select class="form-select form-select-sm" id="wa-passed"><option value="">Resultado: todos</option><option value="true"${merged.passed === 'true' ? ' selected' : ''}>aprobados</option><option value="false"${merged.passed === 'false' ? ' selected' : ''}>suspendidos</option></select></div>
    <div class="col-auto"><input class="form-control form-control-sm" id="wa-week-from" placeholder="Semana ≥" value="${escapeHtml(merged.week_from ?? '')}"></div>
    <div class="col-auto"><input class="form-control form-control-sm" id="wa-week-to" placeholder="Semana ≤" value="${escapeHtml(merged.week_to ?? '')}"></div>
    <div class="col-auto"><button class="btn btn-sm btn-primary" id="wa-apply">Filtrar</button><button class="btn btn-sm btn-outline-primary" id="wa-export">Exportar CSV</button></div>
  </div>`;
  container.innerHTML = `<div class="row row-cards">
    ${merged.account ? `<div class="col-12"><div class="card card-sm"><div class="card-body"><strong>Alumno:</strong> ${escapeHtml(studentEmail || merged.account)}</div></div></div>` : ''}
    <div class="col-12"><div class="card"><div class="card-header"><h3 class="card-title">Resumen global</h3></div><div class="card-body d-flex flex-column gap-2">
      <p class="text-secondary mb-0" role="note">El resumen es global y no cambia con los filtros del listado.</p><div class="d-flex gap-4">
      <div><div class="subheader text-secondary">Intentos</div><strong>${summary.total}</strong></div>
      <div><div class="subheader text-secondary">Completados</div><strong>${summary.completed}</strong></div>
      <div><div class="subheader text-secondary">Aprobados</div><strong>${summary.passed}</strong></div>
      <div><div class="subheader text-secondary">Nota media</div><strong>${summary.avgOverall === null ? '—' : summary.avgOverall + ' %'}</strong></div>
    </div></div></div></div>
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
    <div class="mb-3"><label class="form-label" for="pol-minutes">Minutos</label>
      <input type="number" min="0" max="1440" class="form-control" id="pol-minutes" value="${policy.welcomeMinutes}"></div>
    <div class="mb-3"><label class="form-label" for="pol-reason">Motivo (obligatorio, queda auditado)</label>
      <textarea class="form-control" id="pol-reason" rows="2" maxlength="500"></textarea></div>
    <button class="btn btn-primary" id="pol-save"><i class="ti ti-device-floppy me-1"></i>Guardar</button>
    <span class="text-secondary ms-2">versión actual ${policy.version}</span>
  </div></div></div></div>`;
  $('#pol-save').onclick = async () => {
    if (!validateFields([
      { input: '#pol-minutes', label: 'Minutos', requiredValue: true },
      { input: '#pol-reason', label: 'Motivo', requiredText: true }
    ])) return;
    try {
      await api('/v1/admin/policy/welcome', { method: 'PUT', body: JSON.stringify({
        version: policy.version, welcomeEnabled: $('#pol-enabled').checked,
        welcomeMinutes: Number($('#pol-minutes').value), reason: $('#pol-reason').value }) });
      await refreshAfterMutation(container, renderPolicy, 'Política actualizada correctamente', 'policy-refresh');
    } catch (error) { toast(`Error: ${error.code}`, 'danger'); }
  };
}

async function renderPlans(container) {
  const { config, tiers } = await api('/v1/admin/plans');
  const tierRows = tiers.map((tier, index) => `<div class="card-body border-top py-3">
    <h4 class="text-uppercase small text-secondary mb-2">Plan ${tier.tier}</h4>
    <div class="row">
      <div class="col"><label class="form-label" for="plans-tier-${index}-minutes">Minutos/mes</label>
        <input id="plans-tier-${index}-minutes" type="number" min="15" max="100000" class="form-control" data-tier="${index}" data-field="monthlyMinutes" value="${tier.monthlyMinutes}"></div>
      <div class="col"><label class="form-label" for="plans-tier-${index}-rollover">Tope de rollover (min)</label>
        <input id="plans-tier-${index}-rollover" type="number" min="15" max="144000" class="form-control" data-tier="${index}" data-field="rolloverCapMinutes" value="${tier.rolloverCapMinutes}"></div>
      <div class="col"><label class="form-label" for="plans-tier-${index}-stripe">Precio Stripe (price ID)</label>
        <input id="plans-tier-${index}-stripe" class="form-control font-monospace" data-tier="${index}" data-field="stripePriceID" value="${tier.stripePriceID ?? ''}" placeholder="price_..."></div>
      <div class="col"><label class="form-label" for="plans-tier-${index}-play">Producto Play</label>
        <input id="plans-tier-${index}-play" class="form-control font-monospace" data-tier="${index}" data-field="playProductID" value="${tier.playProductID ?? ''}" placeholder="subscripción.producto"></div>
    </div></div>`).join('');
  container.innerHTML = `<div class="row"><div class="col-lg-8"><div class="card"><div class="card-body">
    <h3 class="card-title d-flex align-items-center gap-2"><i class="ti ti-crown text-secondary"></i>Suscripciones y planes</h3>
    <div class="row">
      <div class="col"><label class="form-check form-switch mt-2">
        <input class="form-check-input" type="checkbox" id="plans-drip" ${config.freeDripEnabled ? 'checked' : ''}>
        <span class="form-check-label">Goteo gratuito mensual</span></label></div>
      <div class="col"><label class="form-label" for="plans-drip-minutes">Minutos/mes del goteo</label>
        <input type="number" min="0" max="60" class="form-control" id="plans-drip-minutes" value="${config.freeDripMinutes}"></div>
      <div class="col"><label class="form-label" for="plans-drip-cap">Tope de goteo (min)</label>
        <input type="number" min="0" max="1440" class="form-control" id="plans-drip-cap" value="${config.freeDripRolloverCapMinutes}"></div>
      <div class="col"><label class="form-label" for="plans-trial">Días de prueba</label>
        <input type="number" min="0" max="30" class="form-control" id="plans-trial" value="${config.trialDays}"></div>
      <div class="col"><label class="form-label" for="plans-budget">Presupuesto vitalicio del goteo (0=sin tope)</label>
        <input type="number" min="0" class="form-control" id="plans-budget" value="${config.lifetimeDripBudgetMinutes}"></div>
    </div></div>${tierRows}
    <div class="card-body border-top">
      <div class="mb-3"><label class="form-label" for="plans-reason">Motivo (obligatorio, queda auditado)</label>
        <textarea class="form-control" id="plans-reason" rows="2" maxlength="500"></textarea></div>
      <button class="btn btn-primary" id="plans-save"><i class="ti ti-device-floppy me-1"></i>Guardar planes</button>
      <span class="text-secondary ms-2">versión actual ${config.version}</span>
      <div id="plans-error" class="text-danger mt-2"></div>
    </div></div></div></div>`;
  $('#plans-save').onclick = async () => {
    const tierInputs = [...container.querySelectorAll('[data-tier]')];
    const fields = [
      { input: '#plans-drip-minutes', label: 'Minutos/mes del goteo', requiredValue: true },
      { input: '#plans-drip-cap', label: 'Tope de goteo', requiredValue: true },
      { input: '#plans-trial', label: 'Días de prueba', requiredValue: true },
      { input: '#plans-budget', label: 'Presupuesto vitalicio', requiredValue: true },
      ...tierInputs.filter(input => input.type === 'number').map(input => ({ input,
        label: input.dataset.field === 'monthlyMinutes' ? 'Minutos/mes del plan' : 'Tope de rollover', requiredValue: true })),
      { input: '#plans-reason', label: 'Motivo', requiredText: true }
    ];
    if (!validateFields(fields)) return;
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
      await refreshAfterMutation(container, renderPlans, 'Planes actualizados correctamente', 'plans-refresh');
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
    <div class="mb-3" id="aud-account-wrap"><label class="form-label" for="camp-account">Cuenta</label>
      <input class="form-control font-monospace" id="camp-account" placeholder="uuid"></div>
    <div class="row"><div class="col"><label class="form-label" for="camp-minutes">Minutos por usuario</label>
      <input type="number" min="1" class="form-control" id="camp-minutes" value="5"></div>
      <div class="col"><label class="form-label" for="camp-cap">Tope total (minutos)</label>
      <input type="number" min="1" class="form-control" id="camp-cap" value="5000"></div></div>
    <div class="mb-3 mt-3"><label class="form-label" for="camp-reason">Motivo (obligatorio)</label>
      <textarea class="form-control" id="camp-reason" rows="2" maxlength="500"></textarea></div>
    <button class="btn btn-primary" id="camp-prepare"><i class="ti ti-checklist me-1"></i>Preparar</button>
    <div id="camp-result" class="mt-3"></div>
  </div></div></div></div>`;
  let preparedCampaign = null;
  const intentControls = ['#aud-one', '#aud-all', '#camp-account', '#camp-minutes', '#camp-cap', '#camp-reason'];
  const lockIntent = locked => {
    for (const selector of intentControls) $(selector).disabled = locked;
    $('#camp-prepare').disabled = locked;
  };
  const invalidatePrepared = () => {
    if (!preparedCampaign) return;
    preparedCampaign = null;
    const apply = $('#camp-apply');
    if (apply) apply.disabled = true;
    $('#camp-result').insertAdjacentHTML('beforeend', '<div class="alert alert-warning mt-2" role="status">La campaña preparada ya no coincide con los datos actuales. Vuelve a prepararla.</div>');
  };
  for (const selector of ['#aud-one', '#aud-all']) $(selector).onchange = () => {
    $('#aud-account-wrap').style.display = $('#aud-one').checked ? '' : 'none';
    invalidatePrepared();
  };
  for (const selector of ['#camp-account', '#camp-minutes', '#camp-cap', '#camp-reason']) {
    $(selector).addEventListener('input', invalidatePrepared);
    $(selector).addEventListener('change', invalidatePrepared);
  }
  $('#camp-prepare').onclick = async () => {
    const fields = [
      ...($('#aud-one').checked ? [{ input: '#camp-account', label: 'Cuenta',
        validate: value => /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value.trim()) }] : []),
      { input: '#camp-minutes', label: 'Minutos por usuario', requiredValue: true },
      { input: '#camp-cap', label: 'Tope total', requiredValue: true },
      { input: '#camp-reason', label: 'Motivo', requiredText: true }
    ];
    if (!validateFields(fields)) return;
    try {
      const intent = { audience: $('#aud-one').checked ? [$('#camp-account').value.trim()] : 'all-current-users',
        minutesPerUser: Number($('#camp-minutes').value), maxTotalMinutes: Number($('#camp-cap').value),
        reason: $('#camp-reason').value };
      const summary = await api('/v1/admin/campaigns', { method: 'POST', body: JSON.stringify({
        ...intent }) });
      preparedCampaign = { ...intent, campaignID: summary.campaignID, confirmation: summary.confirmation };
      $('#camp-result').innerHTML = `<div class="alert alert-info d-flex align-items-center gap-2">
        <i class="ti ti-info-circle"></i><span>Preparada: <strong>${summary.recipients}</strong> destinatarios,
        ${summary.minutesPerUser} min/usuario (${number(summary.recipients * summary.minutesPerUser)} min totales).
        Campaña <code>${summary.campaignID}</code> · confirmación <code class="small">${summary.confirmation}</code></span></div>
        <button class="btn btn-warning" id="camp-apply"><i class="ti ti-alert-triangle me-1"></i>Aplicar (irreversible)</button>`;
      $('#camp-apply').onclick = async () => {
        if (!preparedCampaign || preparedCampaign.campaignID !== summary.campaignID) return;
        const apply = $('#camp-apply');
        apply.disabled = true;
        lockIntent(true);
        try {
          const outcome = await api(`/v1/admin/campaigns/${summary.campaignID}/apply`, { method: 'POST',
            body: JSON.stringify({ confirmation: summary.confirmation, reason: preparedCampaign.reason }) });
          preparedCampaign = null;
          lockIntent(false);
          $('#camp-result').innerHTML += `<div class="alert alert-success d-flex align-items-center gap-2">
            <i class="ti ti-circle-check"></i><span>Aplicada: concedidos <strong>${outcome.granted}</strong>,
            omitidos ${outcome.skipped}.</span></div>`;
        } catch (error) {
          apply.disabled = false;
          toast(`No se pudo confirmar el resultado de la campaña ${summary.campaignID}. Puedes reintentar la misma campaña; no prepares otra.`, 'danger');
        }
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
    <div class="row row-cards g-3">${series.series.map(serie => chartCard(serie.label, 'line', serie)).join('')}</div>
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
  overview: { title: 'Resumen', render: (container, _params, _id, context) => renderDashboard('overview', container, context) },
  growth: { title: 'Crecimiento', render: (container, _params, _id, context) => renderDashboard('growth', container, context) },
  voice: { title: 'Voz', render: (container, _params, _id, context) => renderDashboard('voice', container, context) },
  economy: { title: 'Economía', render: (container, _params, _id, context) => renderDashboard('economy', container, context) },
  usage: { title: 'Uso de la app', render: renderUsage },
  browse: { title: 'Explorador de tablas', render: (container, _params, _id, context) => renderBrowse(container, null, context) },
  policy: { title: 'Política de bienvenida', render: renderPolicy },
  'exam-weeks': { title: 'Generar y revisar exámenes', render: (container, params, _id, context) => renderExamWeeksView(container, params, context) },
  'exam-results': { title: 'Resultados de exámenes', render: renderExamAttempts },
  'ia-config': { title: 'Configuración de IA', render: renderAiConfig },
  exam: { title: 'Revisión de examen', render: renderExamReview },
  plans: { title: 'Planes y suscripciones', render: renderPlans },
  campaigns: { title: 'Campañas', render: renderCampaigns },
  audit: { title: 'Auditoría de acciones', render: (container, _params, _id, context) => renderBrowse(container, 'admin_actions', context) }
};
// Routes that moved when configuration and generation were split apart.
const LEGACY_ROUTES = { 'level-tests': 'exam-weeks' };
// Hash changes can arrive while the previous view is still fetching (e.g. a
// link to a view immediately followed by another). Rendering both interleaves
// their writes into #view — the slower one wipes the newer view and arms the
// same watcher twice — so renders are serialized and coalesced: at most one in
// flight, and the latest hash always wins.
let routing = false;
let reroute = false;
let activeViewContext = null;
function setMobileMenu(open, restoreFocus = false) {
  const sidebar = document.querySelector('.navbar-vertical');
  const button = $('#mobile-menu-toggle');
  if (!sidebar || !button) return;
  sidebar.classList.toggle('mobile-open', open);
  $('#sidebar-menu').classList.toggle('show', open);
  button.setAttribute('aria-expanded', String(open));
  button.setAttribute('aria-label', open ? 'Cerrar menú' : 'Abrir menú');
  if (!open && restoreFocus && button.getClientRects().length) button.focus();
}
async function route() {
  setMobileMenu(false, Boolean(document.querySelector('.navbar-vertical.mobile-open')));
  if (routing) { activeViewContext?.dispose(); reroute = true; return; }
  routing = true;
  try {
    do {
      reroute = false;
      await renderRoute();
    } while (reroute);
  } finally { routing = false; }
}
async function renderRoute() {
  activeViewContext?.dispose();
  const context = new AdminViewContext(() => activeViewContext === context);
  activeViewContext = context;
  const [rawPath, routeQuery] = (location.hash.slice(1) || 'overview').split('?');
  const [routePath, detailId] = rawPath.split('/');
  if (LEGACY_ROUTES[routePath]) { location.replace(`#${LEGACY_ROUTES[routePath]}`); return; }
  const view = VIEWS[routePath] ?? VIEWS.overview;
  const params = Object.fromEntries(new URLSearchParams(routeQuery ?? ''));
  $('#page-title').textContent = view.title;
  $('#mobile-page-title').textContent = view.title;
  document.title = `${view.title} · Palique Admin`;
  // The review page belongs to the generation surface: keep it highlighted.
  const navPath = routePath === 'exam' ? 'exam-weeks' : routePath;
  for (const link of document.querySelectorAll('#nav a.nav-link')) {
    const active = link.hash === `#${navPath}`;
    link.classList.toggle('active', active);
    if (active) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  }
  for (const chart of charts) chart.destroy();
  charts.length = 0;
  for (const table of tables) table.destroy();
  tables = [];
  const container = $('#view');
  container.setAttribute('aria-busy', 'true');
  container.innerHTML = '<div class="mt-3 progress progress-sm" role="status" aria-live="polite" aria-label="Cargando vista"><div class="progress-bar progress-bar-indeterminate"></div></div>';
  try { await view.render(container, params, detailId, context); }
  catch (error) {
    if (context.isCurrent() && error.message !== 'unauthenticated' && error.name !== 'AbortError') {
      container.innerHTML = `<div class="alert alert-danger mt-3" role="alert"><i class="ti ti-alert-triangle me-2"></i>
        <strong>No se pudo cargar esta vista.</strong> ${escapeHtml(error.code ?? error.message ?? 'request_failed')}
        <div><button class="btn btn-sm btn-outline-primary mt-2" id="view-retry">Reintentar carga</button></div></div>`;
      $('#view-retry').onclick = () => route();
    }
  } finally { if (context.isCurrent()) container.setAttribute('aria-busy', 'false'); }
}
(function boot() {
  // Grouping is data: a heading appears whenever an entry opens a new group, so
  // every entry belongs to a visible section and no heading can be mistaken for
  // the first item of the next one.
  $('#nav').innerHTML = NAV.map((item, index) => {
    const heading = NAV[index - 1]?.group !== item.group
      ? `<li class="nav-item nav-group"><span class="nav-link">${escapeHtml(item.group ?? '')}</span></li>`
      : '';
    return heading + `<li class="nav-item"><a class="nav-link" href="#${item.id}">
    <span class="nav-link-icon"><i class="ti ${item.icon}"></i></span><span class="nav-link-title">${item.label}</span></a></li>`;
  }).join('');
  $('#mobile-menu-toggle').onclick = () => setMobileMenu($('#mobile-menu-toggle').getAttribute('aria-expanded') !== 'true');
  $('#nav').addEventListener('click', event => { if (event.target.closest('a.nav-link')) setMobileMenu(false, true); });
  window.addEventListener('keydown', event => {
    if (event.key === 'Escape' && document.querySelector('.navbar-vertical.mobile-open')) {
      event.preventDefault(); setMobileMenu(false, true);
    }
  });
  api('/v1/admin/me').then(me => {
    const name = me.email ?? me.adminID;
    $('#whoami').textContent = name;
    $('#avatar').textContent = (me.email ?? 'A')[0].toUpperCase();
  }).catch(() => {});
  const signOut = async () => { try { await api('/v1/admin/auth/sign-out', { method: 'POST', body: '{}' }); } catch {};
    try { sessionStorage.removeItem(GENERATION_WATCH_STORAGE_KEY); } catch {} location.href = '/login.html'; };
  $('#signout').onclick = signOut;
  $('#mobile-signout').onclick = signOut;
  window.addEventListener('hashchange', route);
  route();
})();
