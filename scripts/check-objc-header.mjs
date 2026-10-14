/**
 * THE HEADER KOTLIN BINDS TO MUST BE THE FACADE SWIFT BUILDS.
 *
 * inapp-kmp's iOS side calls the iOS SDK's Objective-C facade through a header
 * kept by hand (inapp-kmp/src/nativeInterop/cinterop/GraineObjC.h). Nothing
 * at build time compares it with the Swift that implements it
 * (sdk/ios/Sources/GraineInApp/ObjC): a selector the header names and Swift
 * does not answer is a crash in an app, not a build error. This reads both
 * and refuses any class or selector in the header that the facade does not
 * declare, in that class, with @objc(…) (or as an @objc property).
 *
 *   node scripts/check-objc-header.mjs
 *
 * The iOS SDK is the folder `ios` beside this one (as for scripts/ios-test-library.sh:
 * a checkout of https://github.com/GraineAI/inapp-ios of the same version, cloned
 * beside a checkout of this repository as `ios`); GRAINE_IOS_SDK_DIR names another place.
 */
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const header = join(here, "../inapp-kmp/src/nativeInterop/cinterop/GraineObjC.h");
const swiftDir = join(resolve(process.env.GRAINE_IOS_SDK_DIR || join(here, "../../ios")), "Sources/GraineInApp/ObjC");
if (!existsSync(swiftDir)) {
  console.error(`No iOS SDK facade at ${swiftDir}.\nClone https://github.com/GraineAI/inapp-ios (the same version) beside this folder as 'ios', or set GRAINE_IOS_SDK_DIR.`);
  process.exit(1);
}

/** class → selectors, from the header. */
function headerSelectors(text) {
  const out = new Map();
  let current = null;
  for (const raw of text.split("\n")) {
    const line = raw.trim();
    const iface = line.match(/^@interface\s+(\w+)\s*:/);
    if (iface) { current = iface[1]; out.set(current, new Set()); continue; }
    if (line.startsWith("@end")) { current = null; continue; }
    if (!current) continue;
    const prop = line.match(/^@property\s*\([^)]*\)\s*[\w\s*<>_]*?\b(\w+)\s*;$/);
    if (prop) { out.get(current).add(prop[1]); continue; }
    if (/^[-+]\s*\(/.test(line)) {
      // Drop the return type, then every parameter's type and name: what is left is the selector.
      const body = line.replace(/^[-+]\s*\([^)]*(\([^)]*\)[^)]*)*\)\s*/, "").replace(/;$/, "");
      const parts = [...body.matchAll(/(\w+):/g)].map((m) => m[1] + ":");
      const name = parts.length ? parts.join("") : body.trim();
      out.get(current).add((line.startsWith("+") ? "+" : "") + name);
    }
  }
  return out;
}

/** class → selectors, from the Swift facade. */
function swiftSelectors(files) {
  const out = new Map();
  const add = (cls, sel) => { if (!out.has(cls)) out.set(cls, new Set()); out.get(cls).add(sel); };
  for (const file of files) {
    const lines = readFileSync(file, "utf8").split("\n");
    let current = null;
    let pending = null;
    for (const raw of lines) {
      const line = raw.trim();
      const cls = line.match(/^public\s+(final\s+)?class\s+(\w+)/);
      if (cls) { current = cls[2]; if (!out.has(current)) out.set(current, new Set()); pending = null; continue; }
      const ext = line.match(/^extension\s+(\w+)/);
      if (ext) { current = ext[1]; pending = null; continue; }
      if (/^(public\s+)?protocol\s/.test(line) || /^(final\s+)?class\s/.test(line) || /^enum\s/.test(line)) { current = null; pending = null; continue; }
      const objc = line.match(/^@objc\(([^)]+)\)\s*(.*)$/);
      if (objc) {
        const rest = objc[2];
        const isStatic = /\bstatic\b/.test(rest);
        if (/\bclass\b|\bprotocol\b/.test(rest)) continue;
        if (rest) { if (current) add(current, (isStatic ? "+" : "") + objc[1]); }
        else pending = objc[1];
        continue;
      }
      if (pending && current) {
        add(current, (/\bstatic\b/.test(line) ? "+" : "") + pending);
        pending = null;
        continue;
      }
      const prop = line.match(/^@objc\s+public\s+(?:private\(set\)\s+)?(?:lazy\s+)?(?:var|let)\s+(\w+)/);
      if (prop && current) add(current, prop[1]);
    }
  }
  return out;
}

const files = readdirSync(swiftDir).filter((f) => f.endsWith(".swift")).map((f) => join(swiftDir, f));
const wanted = headerSelectors(readFileSync(header, "utf8"));
const have = swiftSelectors(files);
const problems = [];
for (const [cls, selectors] of wanted) {
  const declared = have.get(cls);
  if (!declared) { problems.push(`${cls}: no @objc(${cls}) class in the facade`); continue; }
  for (const sel of selectors) if (!declared.has(sel)) problems.push(`${cls} ${sel}: the header names it, the facade does not declare it`);
}
if (problems.length) {
  console.error("GraineObjC.h and the Swift facade disagree:\n  " + problems.join("\n  "));
  process.exit(1);
}
const count = [...wanted.values()].reduce((n, s) => n + s.size, 0);
console.log(`GraineObjC.h: ${wanted.size} classes, ${count} selectors — every one declared by the Swift facade.`);
