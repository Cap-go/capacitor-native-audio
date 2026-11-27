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

import ts from 'typescript';

const root = process.cwd();
const srcDir = join(root, 'src');
const fix = process.argv.includes('--fix');
const SOURCE = /\.(ts|tsx|mts)$/;
const OK_EXT = /\.(js|mjs|json|css)$/;

function walk(dir) {
  if (!existsSync(dir)) return [];
  return readdirSync(dir).flatMap((name) => {
    const p = join(dir, name);
    if (statSync(p).isDirectory()) return walk(p);
    return SOURCE.test(name) && !name.endsWith('.d.ts') ? [p] : [];
  });
}

// Walks the TypeScript AST so every module specifier is seen (import/export declarations,
// `export * as ns from`, `import x = require()`, `import()` calls and `import('...')` types)
// while comments and ordinary string literals are ignored.
function relativeSpecifiers(code, file) {
  const kind = file.endsWith('.tsx') ? ts.ScriptKind.TSX : ts.ScriptKind.TS;
  const sf = ts.createSourceFile(file, code, ts.ScriptTarget.Latest, true, kind);
  const found = [];
  const add = (lit) => {
    if (lit && ts.isStringLiteralLike(lit) && /^\.{1,2}\//.test(lit.text)) {
      found.push({ fileName: lit.text, pos: lit.getStart(sf) + 1 });
    }
  };
  const visit = (node) => {
    if (ts.isImportDeclaration(node) || ts.isExportDeclaration(node)) add(node.moduleSpecifier);
    else if (ts.isImportEqualsDeclaration(node) && ts.isExternalModuleReference(node.moduleReference)) {
      add(node.moduleReference.expression);
    } else if (ts.isCallExpression(node) && node.expression.kind === ts.SyntaxKind.ImportKeyword) add(node.arguments[0]);
    else if (ts.isImportTypeNode(node) && ts.isLiteralTypeNode(node.argument)) add(node.argument.literal);
    ts.forEachChild(node, visit);
  };
  visit(sf);
  return found;
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
  const edits = [];
  for (const ref of relativeSpecifiers(code, file)) {
    const spec = ref.fileName;
    if (OK_EXT.test(spec)) continue;
    const start = ref.pos;
    if (code.slice(start, start + spec.length) !== spec) throw new Error(`offset mismatch in ${file} for '${spec}'`);
    const line = code.slice(0, start).split('\n').length;
    const suggestion = resolveSpecifier(file, spec);
    findings.push({ file: relative(root, file), line, spec, suggestion });
    if (suggestion) edits.push({ start, end: start + spec.length, suggestion });
  }
  if (fix && edits.length) {
    let out = code;
    for (const e of edits.sort((a, b) => b.start - a.start)) out = out.slice(0, e.start) + e.suggestion + out.slice(e.end);
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
