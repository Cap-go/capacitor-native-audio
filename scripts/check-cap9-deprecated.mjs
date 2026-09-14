#!/usr/bin/env node
/**
 * Capacitor 9 deprecated native API guard.
 *
 * Fails when plugin native sources still use APIs removed in Capacitor 9.
 * Does not flag Cordova SwiftPM product dependencies (still required on Cap 8).
 *
 * Usage:
 *   node scripts/check-cap9-deprecated.mjs
 *   node scripts/check-cap9-deprecated.mjs --dir path
 */

import path from "node:path";
import {
  DEFAULT_SKIP_DIRS,
  exists,
  loadCapacitorPluginContext,
  parseArgs,
  readText,
  walkFiles,
} from "./capacitor-plugin-check-utils.mjs";

const SKIP_DIRS = new Set([...DEFAULT_SKIP_DIRS, "example-app"]);

/** @type {{ id: string, pattern: RegExp, exts: string[], ignoreLine?: RegExp }[]} */
const RULES = [
  {
    id: "hasOption",
    pattern: /\bhasOption\s*\(/,
    exts: [".java", ".kt", ".swift"],
  },
  {
    id: "getConfigValue",
    pattern: /\bgetConfigValue\s*\(/,
    exts: [".java", ".kt", ".swift"],
  },
  {
    id: "@NativePlugin",
    pattern: /@NativePlugin\b/,
    exts: [".java", ".kt"],
  },
  {
    id: "saveCall",
    pattern: /\bsaveCall\s*\(/,
    exts: [".java", ".kt", ".swift"],
  },
  {
    id: "getSavedCall",
    pattern: /\bgetSavedCall\s*\(/,
    exts: [".java", ".kt", ".swift"],
  },
  {
    id: "freeSavedCall",
    pattern: /\bfreeSavedCall\s*\(/,
    exts: [".java", ".kt", ".swift"],
  },
  {
    id: "releaseCall",
    pattern: /\breleaseCall\s*\(/,
    exts: [".java", ".kt", ".swift"],
  },
  {
    id: "pluginRequestPermission",
    pattern: /\bpluginRequestPermissions?\s*\(/,
    exts: [".java", ".kt"],
  },
  {
    id: "pluginRequestAllPermissions",
    pattern: /\bpluginRequestAllPermissions\s*\(/,
    exts: [".java", ".kt"],
  },
  {
    id: "hasDefinedPermissions",
    pattern: /\bhasDefinedPermissions\s*\(/,
    exts: [".java", ".kt"],
  },
  {
    id: "CAPBridge",
    pattern: /\bCAPBridge\./,
    exts: [".swift"],
    ignoreLine: /CAPBridgedPlugin/,
  },
  {
    id: "CAPNotifications",
    pattern: /\bCAPNotifications\b/,
    exts: [".swift"],
  },
];

const CORDova_SPM_LINE =
  /\.product\s*\(\s*name\s*:\s*"Cordova"\s*,\s*package\s*:\s*"capacitor-swift-pm"\s*\)/;

/** @param {string} pluginDir @param {object} cap @returns {string[]} */
function collectScanRoots(pluginDir, cap) {
  const roots = [];
  if (cap.android) {
    const androidRoot =
      typeof cap.android === "object" && typeof cap.android.src === "string"
        ? path.resolve(pluginDir, cap.android.src)
        : path.join(pluginDir, "android");
    if (exists(androidRoot)) roots.push(androidRoot);
  }
  if (cap.ios) {
    const iosRoot =
      typeof cap.ios === "object" && typeof cap.ios.src === "string"
        ? path.resolve(pluginDir, cap.ios.src)
        : path.join(pluginDir, "ios");
    if (exists(iosRoot)) roots.push(iosRoot);
  }
  const packageSwift = path.join(pluginDir, "Package.swift");
  if (exists(packageSwift)) roots.push(packageSwift);
  return roots;
}

/** @param {string} line @param {string} ext @returns {string} */
function stripCommentsAndLiterals(line, ext) {
  let out = line;
  if (ext === ".java" || ext === ".kt" || ext === ".swift") {
    const slash = out.indexOf("//");
    if (slash >= 0) out = out.slice(0, slash);
  }
  out = out.replace(/"(?:\\.|[^"\\])*"/g, '""');
  out = out.replace(/'(?:\\.|[^'\\])*'/g, "''");
  return out;
}

/** @param {string} line @returns {boolean} */
function isDeprecatedApiDeclaration(line) {
  return (
    /^\s*(?:@\w+\s*)*(?:public|private|protected|internal|open|static|final|\s)*\b(?:void|func)\s+\w+\s*\(/.test(
      line,
    ) && !/\.\w+\s*\(/.test(line)
  );
}

/** @param {string} filePath @param {{ exts: string[], pattern: RegExp, ignoreLine?: RegExp }} rule @returns {{ line: number, text: string }[]} */
function scanFile(filePath, rule) {
  const ext = path.extname(filePath);
  if (!rule.exts.includes(ext)) return [];

  const txt = readText(filePath);
  const lines = txt.split(/\r?\n/);
  const hits = [];
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (filePath.endsWith("Package.swift") && CORDova_SPM_LINE.test(line)) {
      continue;
    }
    if (rule.ignoreLine?.test(line)) continue;
    const scanLine = stripCommentsAndLiterals(line, ext);
    if (isDeprecatedApiDeclaration(scanLine)) continue;
    if (rule.pattern.test(scanLine)) {
      hits.push({ line: i + 1, text: line.trim() });
    }
  }
  return hits;
}

let pluginDir;
try {
  pluginDir = parseArgs(process.argv).dir;
} catch (e) {
  console.error(e instanceof Error ? e.message : e);
  process.exit(2);
}
let cap;
try {
  ({ cap } = loadCapacitorPluginContext(pluginDir, "cap9-deprecated"));
} catch (e) {
  console.error(e instanceof Error ? e.message : e);
  process.exit(2);
}
if (!cap.android && !cap.ios) {
  process.exit(0);
}

const scanRoots = collectScanRoots(pluginDir, cap);
const allExts = [...new Set(RULES.flatMap((r) => r.exts))];
const files = [];
for (const root of scanRoots) {
  if (root.endsWith("Package.swift")) {
    files.push(root);
    continue;
  }
  files.push(...walkFiles(root, allExts, SKIP_DIRS));
}

const violations = [];
for (const file of files) {
  for (const rule of RULES) {
    const hits = scanFile(file, rule);
    for (const hit of hits) {
      violations.push({
        rule: rule.id,
        file: path.relative(pluginDir, file),
        line: hit.line,
        text: hit.text,
      });
    }
  }
}

if (violations.length) {
  const relDir = path.relative(process.cwd(), pluginDir) || ".";
  console.error(`[cap9-deprecated] FAIL in ${relDir}`);
  for (const v of violations) {
    console.error(`- ${v.rule}: ${v.file}:${v.line}: ${v.text}`);
  }
  process.exit(1);
}

process.exit(0);
