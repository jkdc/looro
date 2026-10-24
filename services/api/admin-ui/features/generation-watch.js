/* Pure copy for the generation watcher's loss-of-confirmation state. */
'use strict';

/** Explains that a failed poll kept the last known monitor state, so the
    operator never reads a stale "En marcha" as confirmed. */
export function watchFailureStatus(lastConfirmedAt, pollMs, now = Date.now()) {
  const seconds = Math.max(1, Math.round(Number(pollMs) / 1000));
  const suffix = `Reintentando cada ${seconds} s; el monitor puede estar desactualizado.`;
  const confirmed = Number.isFinite(lastConfirmedAt) && lastConfirmedAt > 0
    ? new Date(lastConfirmedAt) : null;
  if (!confirmed || confirmed.getTime() > now) return `Sin confirmación del estado de generación. ${suffix}`;
  const stamp = confirmed.toLocaleTimeString('es-ES', { hour12: false });
  return `Sin confirmación del estado de generación desde las ${stamp}. ${suffix}`;
}
