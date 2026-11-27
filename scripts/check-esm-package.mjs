#!/usr/bin/env node
/**
 * ESM package gate: packs this package, installs the tarball in a clean temp
 * directory and checks it the way a consumer without a bundler would.
 *
 *   node scripts/check-esm-package.mjs          native/Capacitor plugin mode
 *   node scripts/check-esm-package.mjs --dom    web-component mode (real happy-dom globals)
 *
 * Fails when:
 *   - `import('<pkg>')` (and every exports subpath) does not load, or writes anything to stderr
 *     (native mode: bare Node, no shim; --dom mode: @happy-dom/global-registrator registered)
 *   - an entry is `.js` without `"type": "module"` (imports also run with --no-experimental-detect-module)
 *   - `require('<pkg>')` is not refused (ERR_PACKAGE_PATH_NOT_EXPORTED / ERR_REQUIRE_ESM)
 *   - an export name (or a whole subpath) present in the last published npm version is missing
 * Run after `build`. Needs npm and registry access (installs @capacitor/core, the previous
 * release and happy-dom into the temp dir only).
 */
import { spawnSync } from 'node:child_process';
import { mkdtempSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const dom = process.argv.includes('--dom');
const root = process.cwd();
const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
const HAPPY_DOM = '@happy-dom/global-registrator@^20';
const PREV = 'esm-gate-previous';
const failures = [];
const CJS_INTEROP = new Set(['default', 'module.exports']);

function run(cmd, args, cwd, { allowFail = false } = {}) {
  const r = spawnSync(cmd, args, { cwd, encoding: 'utf8', env: { ...process.env, NODE_OPTIONS: '' } });
  if (!allowFail && r.status !== 0) {
    throw new Error(`${cmd} ${args.join(' ')} failed (${r.status}):\n${r.stdout}\n${r.stderr}`);
  }
  return r;
}

const subpaths = (() => {
  const ex = pkg.exports;
  if (!ex || typeof ex === 'string') return ['.'];
  return Object.keys(ex).filter((k) => k !== './package.json' && !k.includes('*'));
})();
const spec = (name, sub) => (sub === '.' ? name : `${name}/${sub.slice(2)}`);

const work = mkdtempSync(join(tmpdir(), 'esm-gate-'));
try {
  console.log(`check-esm-package: ${pkg.name}@${pkg.version} (${dom ? 'happy-dom' : 'bare node'}), node ${process.version}`);
  run('npm', ['pack', '--silent', '--pack-destination', work], root);
  const tgz = readdirSync(work).find((f) => f.endsWith('.tgz'));
  writeFileSync(join(work, 'package.json'), JSON.stringify({ name: 'esm-gate', private: true }) + '\n');

  const deps = [join(work, tgz)];
  // All peers, optional ones too (e.g. firebase): the old CJS build required them eagerly,
  // so the export diff only works when both builds can load as a web consumer app would.
  for (const [name, range] of Object.entries(pkg.peerDependencies ?? {})) deps.push(`${name}@${range}`);
  if (dom) deps.push(HAPPY_DOM);
  // Only a confirmed E404 means "never published"; any other registry error fails the gate.
  const published = run('npm', ['view', pkg.name, 'version'], work, { allowFail: true });
  if (published.status !== 0 && !/\bE404\b/.test(published.stderr)) {
    throw new Error(`npm view ${pkg.name} failed, cannot diff exports:\n${published.stderr.trim()}`);
  }
  const prevVersion = published.status === 0 ? published.stdout.trim() : null;
  if (prevVersion) deps.push(`${PREV}@npm:${pkg.name}@${prevVersion}`);
  run('npm', ['install', '--no-audit', '--no-fund', '--legacy-peer-deps', '--loglevel=error', ...deps], work);

  // Never rely on Node's module-syntax detection: ESM must be declared ("type": "module" or .mjs).
  const preload = [];
  if (process.allowedNodeEnvironmentFlags.has('--no-experimental-detect-module')) {
    preload.push('--no-experimental-detect-module');
  }
  for (const sub of subpaths) {
    const target = sub === '.' && !pkg.exports ? pkg.main : pkg.exports?.[sub]?.import ?? pkg.exports?.[sub];
    if (typeof target === 'string' && !target.endsWith('.mjs') && pkg.type !== 'module') {
      failures.push(`'${sub}' -> ${target} is .js but package.json has no "type": "module"`);
    }
  }
  if (dom) {
    writeFileSync(
      join(work, 'register-dom.mjs'),
      "import { GlobalRegistrator } from '@happy-dom/global-registrator';\nGlobalRegistrator.register();\n",
    );
    preload.push('--import', './register-dom.mjs');
  }

  const importKeys = (name, sub, label) => {
    const s = spec(name, sub);
    const code = `import(${JSON.stringify(s)}).then((m) => console.log(JSON.stringify(Object.keys(m).sort())))`;
    const r = run(process.execPath, [...preload, '--input-type=module', '-e', code], work, { allowFail: true });
    if (r.status !== 0 || r.stderr.trim()) {
      failures.push(`${label} import('${s}') ${r.status !== 0 ? 'failed' : 'wrote to stderr'}:\n${r.stderr.trim()}`);
      return null;
    }
    const keys = JSON.parse(r.stdout.trim().split('\n').pop());
    console.log(`  ${label} import('${s}') OK: ${keys.join(', ')}`);
    return keys;
  };

  const current = {};
  for (const sub of subpaths) current[sub] = importKeys(pkg.name, sub, 'this build');

  for (const sub of subpaths) {
    const s = spec(pkg.name, sub);
    const r = run(process.execPath, ['-e', `require(${JSON.stringify(s)})`], work, { allowFail: true });
    const code = (r.stderr.match(/ERR_[A-Z_]+/) ?? [])[0];
    if (r.status === 0) failures.push(`require('${s}') was NOT refused`);
    else if (!['ERR_PACKAGE_PATH_NOT_EXPORTED', 'ERR_REQUIRE_ESM'].includes(code)) {
      failures.push(`require('${s}') failed with an unexpected error (${code ?? 'no code'}):\n${r.stderr.trim()}`);
    } else console.log(`  require('${s}') refused: ${code}`);
  }

  if (!prevVersion) {
    console.log('  previous release: not on npm (E404), export diff skipped');
  } else {
    const prevPkg = JSON.parse(readFileSync(join(work, 'node_modules', PREV, 'package.json'), 'utf8'));
    const prevSubs =
      prevPkg.exports && typeof prevPkg.exports === 'object'
        ? Object.keys(prevPkg.exports).filter((k) => k !== './package.json' && !k.includes('*'))
        : ['.'];
    for (const sub of prevSubs) {
      if (!subpaths.includes(sub)) {
        failures.push(`subpath '${sub}' exported by ${prevVersion} is missing`);
        continue;
      }
      const before = importKeys(PREV, sub, `npm ${prevVersion}`);
      if (!before || !current[sub]) continue;
      // 'default' and 'module.exports' are CommonJS interop keys Node adds for the old CJS build.
      const dropped = before.filter((k) => !CJS_INTEROP.has(k) && !current[sub].includes(k));
      if (dropped.length) failures.push(`'${sub}' dropped exports vs ${prevVersion}: ${dropped.join(', ')}`);
      else console.log(`  exports of '${sub}' vs npm ${prevVersion}: nothing dropped`);
    }
  }
} finally {
  rmSync(work, { recursive: true, force: true });
}

if (failures.length) {
  console.error(`check-esm-package: FAIL\n- ${failures.join('\n- ')}`);
  process.exit(1);
}
console.log('check-esm-package: OK');
