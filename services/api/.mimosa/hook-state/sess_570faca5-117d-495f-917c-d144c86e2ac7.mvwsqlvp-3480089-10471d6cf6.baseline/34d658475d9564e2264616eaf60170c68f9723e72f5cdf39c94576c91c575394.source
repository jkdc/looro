/* Owns asynchronous reads and browser resources for one rendered view. */
'use strict';

class AdminViewContext {
  constructor(isActive = () => true) {
    this._isActive = isActive;
    this._disposed = false;
    this._cleanups = new Set();
    this._controller = new AbortController();
  }

  get signal() { return this._controller.signal; }
  get disposed() { return this._disposed; }
  isCurrent() { return !this._disposed && this._isActive(); }

  register(resource) {
    if (typeof resource === 'function') {
      if (this._disposed) resource();
      else this._cleanups.add(resource);
      return resource;
    }
    if (resource && typeof resource.dispose === 'function') return this.register(() => resource.dispose());
    if (resource && typeof resource.destroy === 'function') return this.register(() => resource.destroy());
    throw new TypeError('View resources must be cleanup functions or disposable objects');
  }

  listen(target, type, listener, options) {
    target.addEventListener(type, listener, options);
    this.register(() => target.removeEventListener(type, listener, options));
    return listener;
  }

  timeout(callback, delay) {
    const id = setTimeout(() => { this._cleanups.delete(cleanup); if (this.isCurrent()) callback(); }, delay);
    const cleanup = () => clearTimeout(id);
    this.register(cleanup);
    return id;
  }

  dispose() {
    if (this._disposed) return;
    this._disposed = true;
    this._controller.abort();
    for (const cleanup of this._cleanups) {
      try { cleanup(); } catch { /* One bad resource must not leak the rest. */ }
    }
    this._cleanups.clear();
  }
}

globalThis.AdminViewContext = AdminViewContext;
