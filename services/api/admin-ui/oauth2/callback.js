'use strict';
(async function complete() {
  const params = new URLSearchParams(location.search);
  const code = params.get('code'), state = params.get('state');
  if (!code || !state) {
    document.getElementById('status').textContent = 'Faltan parámetros de retorno.';
    document.getElementById('error').textContent = 'Vuelve a la pantalla de acceso e inténtalo de nuevo.';
    return;
  }
  const response = await fetch('/v1/admin/auth/exchange', { method: 'POST', credentials: 'same-origin',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ provider: 'google', code, redirectUri: `${location.origin}/oauth2/callback.html`, challengeID: state }) });
  if (response.ok) { location.href = '/'; return; }
  const body = await response.json().catch(() => ({}));
  document.getElementById('status').textContent = 'No se pudo iniciar sesión.';
  document.getElementById('error').textContent = body.error?.code ?? 'error desconocido';
})();
