#!/usr/bin/env node
/**
 * vue-check-ts7 — type-check Vue SFCs + .ts sources with TypeScript 7 only.
 *
 *   parse   : TS 7 native compiler (tsgo) via typescript/unstable/sync
 *   codegen : @vue/language-core (Volar) driven through a TS 7-backed `ts` shim
 *   check   : TS 7 native compiler, generated files injected via typescript/unstable/fs
 *   report  : diagnostics mapped back to .vue source through Volar's mappings,
 *             filtered by Volar's own `shouldReportDiagnostics` verification flag
 */
const core = require('@vue/language-core');
const { SourceMap } = require('@volar/source-map');
const parser = require('./ts7parser.cjs');
const { makeShim } = require('./shim.cjs');
const nodeFs = require('node:fs');
const nodePath = require('node:path');

function listVue(dir, out = []) {
  for (const e of nodeFs.readdirSync(dir, { withFileTypes: true })) {
    const p = nodePath.join(dir, e.name);
    if (e.isDirectory()) listVue(p, out);
    else if (e.name.endsWith('.vue')) out.push(p);
  }
  return out;
}
function lineCol(text, offset) {
  let line = 1, last = 0;
  for (let i = 0; i < offset && i < text.length; i++) if (text[i] === '\n') { line++; last = i + 1; }
  return { line, col: offset - last + 1 };
}

async function run() {
  await parser.init();
  const base = await makeShim();
  const shim = new Proxy(base, {
    get(t, p) {
      if (typeof p !== 'string' || p in t) return t[p];
      for (const s of ['Node', 'Declaration', 'Kind', 'Token']) if ((p + s) in t) return t[p + s];
      return function () { throw new Error(`ts.${p} unavailable in TS7`); };
    },
    has: (t, p) => p in t,
  });

  const cwd = process.cwd();
  const vueOptions = core.getDefaultCompilerOptions();
  const plugin = core.createVueLanguagePlugin(shim, { target: 99 }, vueOptions, id => id);

  const overlay = {};
  const meta = new Map();
  for (const file of listVue(nodePath.join(cwd, 'src'))) {
    const text = nodeFs.readFileSync(file, 'utf8');
    const snapshot = { getText: (s, e) => text.slice(s, e), getLength: () => text.length, getChangeRange: () => undefined };
    const vc = plugin.createVirtualCode(file, 'vue', snapshot);
    const svc = plugin.typescript.getServiceScript(vc);
    if (!svc) continue;
    const genPath = file + '.ts';
    overlay[genPath] = svc.code.snapshot.getText(0, svc.code.snapshot.getLength());
    meta.set(nodePath.resolve(genPath).replace(/\\/g, '/'), {
      vueFile: file, sourceText: text, map: new SourceMap(svc.code.mappings),
    });
  }

  const { API } = await import('typescript/unstable/sync');
  const norm = p => nodePath.resolve(p).replace(/\\/g, '/');
  const store = new Map(Object.entries(overlay).map(([k, v]) => [norm(k), v]));
  const dirs = new Map();
  for (const f of store.keys()) {
    const d = norm(nodePath.dirname(f));
    if (!dirs.has(d)) dirs.set(d, new Set());
    dirs.get(d).add(nodePath.basename(f));
  }
  const fs = {
    readFile: f => store.has(norm(f)) ? store.get(norm(f)) : undefined,
    fileExists: f => store.has(norm(f)) ? true : undefined,
    directoryExists: () => undefined,
    getAccessibleEntries: d => {
      const extra = dirs.get(norm(d));
      if (!extra) return undefined;
      let files = [], directories = [];
      try { for (const e of nodeFs.readdirSync(norm(d), { withFileTypes: true })) { if (e.isDirectory()) directories.push(e.name); else files.push(e.name); } } catch {}
      return { files: [...new Set([...files, ...extra])], directories };
    },
    realpath: () => undefined,
  };

  const api = new API({ cwd, fs });
  const snap = api.updateSnapshot({ openProjects: [nodePath.join(cwd, 'tsconfig.json')] });
  const proj = snap.getProjects()[0];
  const raw = [...proj.program.getSyntacticDiagnostics(), ...proj.program.getSemanticDiagnostics()];

  const out = [];
  let dropped = 0;
  for (const d of raw) {
    const f = d.fileName ? norm(d.fileName) : undefined;
    const m = f && meta.get(f);
    if (!m) { out.push({ file: d.fileName, pos: d.pos, code: d.code, text: d.text, srcText: null }); continue; }
    // Map generated span -> .vue source span, honouring Volar's verification flag.
    let mapped;
    for (const [s, e] of m.map.toSourceRange(d.pos, d.end, false,
      data => core.shouldReportDiagnostics(data, undefined, String(d.code)))) { mapped = [s, e]; break; }
    if (!mapped) { dropped++; continue; }
    out.push({ file: m.vueFile, pos: mapped[0], code: d.code, text: d.text, srcText: m.sourceText });
  }
  api.close();

  for (const d of out) {
    const text = d.srcText ?? nodeFs.readFileSync(d.file, 'utf8');
    const { line, col } = lineCol(text, d.pos);
    console.log(`${nodePath.relative(cwd, d.file)}(${line},${col}): error TS${d.code}: ${d.text}`);
  }
  console.log(`\n${out.length} error(s). (${dropped} generated-only diagnostic(s) filtered out by Volar verification mapping)`);
  return out.length ? 1 : 0;
}
run().then(c => process.exit(c)).catch(e => { console.error('vue-check-ts7 failed:', e.stack); process.exit(2); });
