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
  if (challenge.googleClientID) {
    const redirectUri = `${location.origin}/oauth2/callback.html`;
    $('#google-btn').href = 'https://accounts.google.com/o/oauth2/v2/auth?' + new URLSearchParams({
      client_id: challenge.googleClientID, redirect_uri: redirectUri, response_type: 'code',
      scope: 'openid email', state: challenge.challengeID, nonce: challenge.nonce });
  } else {
    $('#google-flow').remove();
  }
  $('#bearer-btn').onclick = async () => {
    const bearer = $('#bearer').value.trim();
    if (!/^[A-Za-z0-9_-]{43}$/.test(bearer)) { $('#error').textContent = 'El token debe tener 43 caracteres [A-Za-z0-9_-].'; return; }
    const response = await fetch('/v1/admin/auth/session', { method: 'POST', credentials: 'same-origin',
      headers: { 'content-type': 'application/json' }, body: JSON.stringify({ bearer }) });
    if (response.ok) location.href = '/';
    else $('#error').textContent = 'Token no válido o sesión expirada.';
  };
})();
