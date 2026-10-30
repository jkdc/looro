import { Pool, type PoolClient } from 'pg';
import { log } from './log.js';

export type Database = Pool;
export function connectDatabase(url: string): Database {
  const pool = new Pool({ connectionString: url, max: 10, connectionTimeoutMillis: 5_000,
    statement_timeout: 10_000, application_name: 'app-billing' });
  // pg evicts broken idle clients; handle its error event so the next query can
  // reconnect instead of crashing the process. Never log client/error objects.
  pool.on('error', () => log.error('database_idle_client_error'));
  return pool;
}
export async function transaction<T>(db: Database, fn: (sql: PoolClient) => Promise<T>): Promise<T> {
  const sql = await db.connect();
  try {
    await sql.query('BEGIN');
    const result = await fn(sql);
    await sql.query('COMMIT');
    return result;
  } catch (error) {
    await sql.query('ROLLBACK');
    throw error;
  } finally { sql.release(); }
}
