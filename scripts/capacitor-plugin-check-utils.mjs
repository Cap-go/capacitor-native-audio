import { accessSync, constants, readFileSync, readdirSync } from "node:fs";
import path from "node:path";

export const DEFAULT_SKIP_DIRS = new Set([
  "node_modules",
  "dist",
  "build",
  ".build",
  ".gradle",
  "Pods",
  "DerivedData",
  ".swiftpm",
  ".git",
]);

/** @type {string | null} */
let pluginRoot = null;

export function setPluginRoot(dir) {
  pluginRoot = path.resolve(dir);
}

function isUnderPluginRoot(targetPath) {
  if (!pluginRoot) return false;
  const resolved = path.resolve(targetPath);
  const rel = path.relative(pluginRoot, resolved);
  return rel === "" || (!rel.startsWith("..") && !path.isAbsolute(rel));
}

export function readText(p) {
  if (typeof p !== "string" || p.includes("\0") || !isUnderPluginRoot(p)) {
    return "";
  }
  try {
    return readFileSync(path.resolve(p), "utf8");
  } catch {
    // Unreadable paths are treated as empty (check scripts skip missing files).
    return "";
  }
}

export function exists(p) {
  if (typeof p !== "string" || p.includes("\0") || !isUnderPluginRoot(p)) {
    return false;
  }
  try {
    accessSync(path.resolve(p), constants.F_OK);
    return true;
  } catch {
    // Missing paths are treated as absent.
    return false;
  }
}

export function loadCapacitorPluginContext(pluginDir, logPrefix) {
  setPluginRoot(pluginDir);
  const pkgPath = path.join(pluginDir, "package.json");
  if (!exists(pkgPath)) {
    throw new Error(`[${logPrefix}] ERROR: missing package.json in ${pluginDir}`);
  }
  let pkg;
  try {
    pkg = JSON.parse(readText(pkgPath));
  } catch (e) {
    const message = e instanceof Error ? e.message : String(e);
    throw new Error(`[${logPrefix}] ERROR: invalid package.json (${pkgPath}): ${message}`);
  }
  if (pkg === null || typeof pkg !== "object") {
    throw new Error(`[${logPrefix}] ERROR: package.json must contain an object (${pkgPath})`);
  }
  const cap =
    typeof pkg.capacitor === "object" && pkg.capacitor !== null ? pkg.capacitor : {};
  return { pkg, cap };
}

export function parseArgs(argv) {
  const out = { dir: process.cwd() };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === "--dir" || a === "--pluginDir") {
      const raw = argv[++i] || ".";
      if (typeof raw !== "string" || raw.includes("\0")) {
        throw new Error("[plugin-check] ERROR: invalid --dir path");
      }
      out.dir = path.resolve(raw);
      continue;
    }
  }
  setPluginRoot(out.dir);
  return out;
}

export function listRootFiles(pluginDir, suffix) {
  if (typeof suffix !== "string" || !/^\.[a-z0-9]+$/i.test(suffix)) {
    return [];
  }
  if (typeof pluginDir !== "string" || pluginDir.includes("\0") || !isUnderPluginRoot(pluginDir)) {
    return [];
  }
  const resolved = path.resolve(pluginDir);
  try {
    return readdirSync(resolved, { withFileTypes: true })
      .filter((entry) => entry.isFile() && entry.name.endsWith(suffix))
      .map((entry) => path.join(resolved, entry.name))
      .filter((p) => isUnderPluginRoot(p))
      .sort();
  } catch {
    // Unreadable plugin roots are treated as having no matching files.
    return [];
  }
}

export function walkFiles(rootDir, exts, skipDirs = DEFAULT_SKIP_DIRS) {
  if (typeof rootDir !== "string" || rootDir.includes("\0") || !isUnderPluginRoot(rootDir)) {
    return [];
  }
  const resolvedRoot = path.resolve(rootDir);
  const out = new Set();
  const stack = [resolvedRoot];
  try {
    while (stack.length) {
      const dir = stack.pop();
      if (typeof dir !== "string" || dir.includes("\0") || !isUnderPluginRoot(dir)) {
        continue;
      }
      const entries = readdirSync(path.resolve(dir), { withFileTypes: true });
      for (const entry of entries) {
        const fullPath = path.join(dir, entry.name);
        if (!isUnderPluginRoot(fullPath)) continue;
        if (entry.isDirectory()) {
          if (skipDirs.has(entry.name)) continue;
          stack.push(fullPath);
          continue;
        }
        if (!entry.isFile()) continue;
        for (const ext of exts) {
          if (!/^\.[a-z0-9]+$/i.test(ext)) continue;
          if (entry.name.endsWith(ext)) {
            out.add(fullPath);
            break;
          }
        }
      }
    }
  } catch {
    // Unwalkable roots are skipped.
    return [];
  }
  return [...out].sort();
}
