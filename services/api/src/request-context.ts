import { AsyncLocalStorage } from 'node:async_hooks';
import { ROOT_CONTEXT, type Context, type ContextManagementToken, type ContextManager } from '@opentelemetry/api';

/** Minimal Node context manager for the API's request-scoped spans. */
export class AsyncLocalStorageContextManager implements ContextManager {
  private readonly storage = new AsyncLocalStorage<Context>();
  active(): Context { return this.storage.getStore() ?? ROOT_CONTEXT; }
  with<A extends unknown[], F extends (...args: A) => ReturnType<F>>(
    context: Context, fn: F, thisArg?: ThisParameterType<F>, ...args: A
  ): ReturnType<F> {
    return this.storage.run(context, () => fn.apply(thisArg, args));
  }
  bind<T>(context: Context, target: T): T {
    if (typeof target !== 'function') return target;
    const manager = this;
    return function (this: unknown, ...args: unknown[]) {
      return manager.with(context, target as (...values: unknown[]) => unknown, this, ...args);
    } as T;
  }
  attach(context: Context): ContextManagementToken {
    const previous = this.active();
    this.storage.enterWith(context);
    let disposed = false;
    return { dispose: () => {
      if (!disposed) { disposed = true; this.storage.enterWith(previous); }
    } };
  }
  enable(): this { return this; }
  disable(): this { this.storage.disable(); return this; }
}
