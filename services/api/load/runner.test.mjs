import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const here = fileURLToPath(new URL('.', import.meta.url));
const repoRoot = fileURLToPath(new URL('../../../', import.meta.url));

test('smoke and baseline use the isolated tooling Compose service', () => {
  const makefile = readFileSync(`${repoRoot}/Makefile`, 'utf8');
  const smoke = makefile.match(/^load-api-smoke:[\s\S]*?(?=^\S|$(?![\s\S]))/m)?.[0] ?? '';
  const baseline = makefile.match(/^load-api-baseline:[\s\S]*?(?=^\S|$(?![\s\S]))/m)?.[0] ?? '';

  assert.ok(/^LOAD_RUNNER_COMPOSE\s*:=.*compose\.runner\.yaml$/m.test(makefile),
    'Makefile must define the dedicated tooling Compose file');
  assert.ok(/\$\(LOAD_RUNNER_COMPOSE\)\s+run\s+--build\s+--rm\s+load-runner\s+smoke/.test(smoke),
    'load-api-smoke must run smoke in the tooling container');
  assert.ok(/\$\(LOAD_RUNNER_COMPOSE\)\s+run\s+--build\s+--rm\s+load-runner\s+baseline/.test(baseline),
    'load-api-baseline must run baseline in the tooling container');
  assert.doesNotMatch(`${smoke}\n${baseline}`, /\$\(COMPOSE\).*exec api/);
});

test('tooling container exposes only a read-only repository and the authorized Docker socket', () => {
  const compose = readFileSync(`${here}/compose.runner.yaml`, 'utf8');

  assert.match(compose, /network_mode:\s*none/);
  assert.match(compose, /source:\s*\.\.\/\.\.\/\.\.\s*\n\s*target:\s*\/workspace\s*\n\s*read_only:\s*true/);
  assert.match(compose, /source:\s*\/var\/run\/docker\.sock\s*\n\s*target:\s*\/var\/run\/docker\.sock/);
  assert.equal((compose.match(/^\s+source:/gm) ?? []).length, 2);
});

test('Docker Compose resolves exactly the authorized runner mounts', () => {
  const config = JSON.parse(execFileSync('docker', [
    'compose', '--project-directory', here, '-f', `${here}/compose.runner.yaml`, 'config', '--format', 'json',
  ], { encoding: 'utf8' }));
  const service = config.services['load-runner'];
  const mounts = service.volumes.map(({ type, source, target, read_only }) => ({
    type, source, target, read_only: read_only === true,
  }));

  assert.equal(service.network_mode, 'none');
  assert.deepEqual(mounts, [
    { type: 'bind', source: '/workspace', target: '/workspace', read_only: true },
    { type: 'bind', source: '/var/run/docker.sock', target: '/var/run/docker.sock', read_only: false },
  ]);
});

test('tool container provides Node, Git and Docker Compose', () => {
  assert.match(execFileSync('node', ['--version'], { encoding: 'utf8' }), /^v\d+\./);
  assert.match(execFileSync('git', ['--version'], { encoding: 'utf8' }), /^git version \d/);
  assert.match(execFileSync('docker', ['compose', 'version'], { encoding: 'utf8' }), /Docker Compose version/);
});

test('tool image installs orchestration tools without modifying the production image', () => {
  const dockerfile = readFileSync(`${here}/Dockerfile.runner`, 'utf8');
  const makefile = readFileSync(`${repoRoot}/Makefile`, 'utf8');

  assert.match(dockerfile, /apk add --no-cache[^\n]*git[^\n]*docker-cli[^\n]*docker-cli-compose/);
  assert.match(makefile, /LOAD_RUNNER_COMPOSE.*compose\.runner\.yaml/);
});
