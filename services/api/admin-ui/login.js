'use strict';
const $ = selector => document.querySelector(selector);
(async function boot() {
  try {
    const me = await fetch('/v1/admin/me', { credentials: 'same-origin' });
    if (me.ok) { location.href = '/'; return; }
  } catch { /* not signed in */ }
  let challenge;
  try {
    challenge = await (await fetch('/v1/admin/auth/challenge', { method: 'POST', credentials: 'same-origin',
      headers: { 'content-type': 'application/json' }, body: '{}' })).json();
  } catch { $('#error').textContent = 'No se pudo iniciar el reto de autenticación.'; return; }
  $('#login-form').onsubmit = async event => {
    event.preventDefault();
    $('#error').textContent = '';
    const username = $('#username').value.trim(), password = $('#password').value;
    if (!/^[a-z0-9._-]{3,64}$/.test(username) || !password) { $('#error').textContent = 'Usuario o contraseña no válidos.'; return; }
    let response;
    try {
      response = await fetch('/v1/admin/auth/login', { method: 'POST', credentials: 'same-origin',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ username, password, challengeID: challenge.challengeID }) });
    } catch { $('#error').textContent = 'No se pudo contactar con el servidor.'; return; }
    if (response.ok) location.href = '/';
    else $('#error').textContent = response.status === 403 ? 'Cuenta deshabilitada.' : 'Credenciales no válidas.';
  };
  $('#bearer-btn').onclick = async () => {
    const bearer = $('#bearer').value.trim();
    if (!/^[A-Za-z0-9_-]{43}$/.test(bearer)) { $('#error').textContent = 'El token debe tener 43 caracteres [A-Za-z0-9_-].'; return; }
    const response = await fetch('/v1/admin/auth/session', { method: 'POST', credentials: 'same-origin',
      headers: { 'content-type': 'application/json' }, body: JSON.stringify({ bearer }) });
    if (response.ok) location.href = '/';
    else $('#error').textContent = 'Token no válido o sesión expirada.';
  };
})();
