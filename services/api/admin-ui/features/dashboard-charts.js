export function createDashboardCharts({ Chart, palette, chartTypes, escapeHtml, number }) {
  const charts = [];

  const card = (title, type, serie) => {
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

  const mount = (canvas, serie) => {
    const color = palette[charts.length % palette.length];
    const context = canvas.getContext('2d');
    const gradient = context.createLinearGradient(0, 0, 0, 220);
    gradient.addColorStop(0, color + '33');
    gradient.addColorStop(1, color + '05');
    const type = chartTypes[serie.name] ?? 'line';
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
          y: { beginAtZero: true, grid: { color: 'rgba(160,170,180,.15)' }, ticks: { font: { size: 10 }, maxTicksLimit: 6 } } }
      }
    }));
  };

  return {
    card,
    mount,
    resize: () => { for (const chart of charts) chart.resize(); },
    destroy: () => { for (const chart of charts) chart.destroy(); charts.length = 0; }
  };
}
