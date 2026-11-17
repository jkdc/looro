import { test } from 'node:test';
import assert from 'node:assert/strict';
import { migrationsOnBootEnabled } from '../src/migrate.js';

// Pure env-gate for single-container deployments (e.g. Coolify git app,
// docs/deployment-coolify.md): the API process applies schema migrations
// at startup only when explicitly enabled. Must stay opt-in so the compose
// topologies with a dedicated migrate job keep their current behavior.
test('migrations on boot are disabled by default', () => {
  assert.equal(migrationsOnBootEnabled({}), false);
  assert.equal(migrationsOnBootEnabled({ RUN_MIGRATIONS_ON_BOOT: 'false' }), false);
  assert.equal(migrationsOnBootEnabled({ RUN_MIGRATIONS_ON_BOOT: '1' }), false);
});

test('migrations on boot require the exact true flag', () => {
  assert.equal(migrationsOnBootEnabled({ RUN_MIGRATIONS_ON_BOOT: 'true' }), true);
});
