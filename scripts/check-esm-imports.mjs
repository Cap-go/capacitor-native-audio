#!/usr/bin/env node
/**
 * Fails when a relative import/export in src/ has no explicit `.js` extension.
 * ESM-only packages must load in plain Node (no bundler), which needs full
 * relative specifiers in the emitted JS (`./definitions.js`, `./web.js`).
 *
 *   node scripts/check-esm-imports.mjs          check, exit 1 on findings
 *   node scripts/check-esm-imports.mjs --fix    rewrite specifiers in place
 */
import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';

const root = process.cwd();
const srcDir = join(root, 'src');
const fix = process.argv.includes('--fix');
const SOURCE = /\.(ts|tsx|mts)$/;
const OK_EXT = /\.(js|mjs|json|css)$/;
// static `from '...'`, side-effect `import '...'`, dynamic `import('...')`
const PATTERN = /(\bfrom\s*|\bimport\s*\(\s*|\bimport\s+)(['"])(\.{1,2}\/[^'"\n]*)\2/g;

function walk(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return walk(p);
    return SOURCE.test(name) && !name.endsWith('.d.ts') ? [p] : [];
  });
}

function blankComments(code) {
  // Replace comment bodies with spaces (keeps offsets) so examples in comments are ignored.
  return code.replace(/\/\*[\s\S]*?\*\/|(^|[^:'"`\\])\/\/[^\n]*/g, (m) => m.replace(/[^\n]/g, ' '));
}

function resolveSpecifier(file, spec) {
  const base = resolve(dirname(file), spec);
  for (const ext of ['.ts', '.tsx', '.mts']) {
    if (existsSync(base + ext)) return spec + (ext === '.mts' ? '.mjs' : '.js');
  }
  if (spec.endsWith('.ts') && existsSync(base)) return spec.slice(0, -3) + '.js';
  for (const ext of ['.ts', '.tsx']) {
    if (existsSync(join(base, 'index' + ext))) return spec.replace(/\/?$/, '/index.js');
  }
  return null;
}

const findings = [];
for (const file of walk(srcDir)) {
  const code = readFileSync(file, 'utf8');
  const scan = blankComments(code);
  const edits = [];
  for (const m of scan.matchAll(PATTERN)) {
    const spec = m[3];
    if (OK_EXT.test(spec)) continue;
    const start = m.index + m[1].length + 1;
    const line = code.slice(0, start).split('\n').length;
    const suggestion = resolveSpecifier(file, spec);
    findings.push({ file: relative(root, file), line, spec, suggestion });
    if (suggestion) edits.push({ start, end: start + spec.length, suggestion });
  }
  if (fix && edits.length) {
    let out = code;
    for (const e of edits.reverse()) out = out.slice(0, e.start) + e.suggestion + out.slice(e.end);
    writeFileSync(file, out);
  }
}

if (fix) {
  const unresolved = findings.filter((f) => !f.suggestion);
  console.log(`check-esm-imports: fixed ${findings.length - unresolved.length} specifier(s)`);
  for (const f of unresolved) console.error(`  cannot resolve ${f.file}:${f.line} '${f.spec}'`);
  process.exit(unresolved.length ? 1 : 0);
}
if (findings.length) {
  console.error('check-esm-imports: relative imports must end in .js (ESM-only package, plain Node import):');
  for (const f of findings) {
    console.error(`  ${f.file}:${f.line} '${f.spec}'${f.suggestion ? ` -> '${f.suggestion}'` : ''}`);
  }
  console.error('Run `node scripts/check-esm-imports.mjs --fix` to rewrite them.');
  process.exit(1);
}
console.log('check-esm-imports: OK');
