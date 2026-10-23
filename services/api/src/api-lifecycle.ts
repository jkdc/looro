export interface ApiLifecycleOptions {
  listen: () => Promise<void>;
  startWorkers: () => void;
  shutdownSteps: readonly (() => void | Promise<void>)[];
}

/** Starts workers only after HTTP is listening and closes resources once in declared order. */
export function createApiLifecycle({ listen, startWorkers, shutdownSteps }: ApiLifecycleOptions) {
  let shutdownPromise: Promise<void> | undefined;

  const shutdown = (): Promise<void> => {
    if (!shutdownPromise) {
      shutdownPromise = (async () => {
        const failures: unknown[] = [];
        for (const step of shutdownSteps) {
          try { await step(); } catch (error) { failures.push(error); }
        }
        if (failures.length) throw new AggregateError(failures, 'API shutdown encountered resource cleanup failures.');
      })();
    }
    return shutdownPromise;
  };

  const start = async (): Promise<void> => {
    try {
      await listen();
      startWorkers();
    } catch (error) {
      try { await shutdown(); } catch { /* Preserve the startup failure as the primary error. */ }
      throw error;
    }
  };

  return { start, shutdown };
}
