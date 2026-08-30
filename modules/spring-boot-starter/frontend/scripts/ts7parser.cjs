// A `ts.createSourceFile` shim backed ENTIRELY by TypeScript 7's native compiler.
// Each parse uses a throwaway API instance because tsgo caches file content
// per-session and offers no invalidation channel for VFS-backed files.
const path = require('node:path');
const ROOT = path.join(__dirname, '.parsehost');
const CFG = path.join(ROOT, 'tsconfig.json');
const SLOTS = { '.ts': 'p0.ts', '.tsx': 'p0.tsx', '.js': 'p0.js', '.jsx': 'p0.jsx' };

let API;
async function init() {
  ({ API } = await import('typescript/unstable/sync'));
}

let parseCount = 0;
let parseMs = 0;

function createSourceFile(fileName, text, _target, _setParents, _scriptKind) {
  const ext = fileName.endsWith('.tsx') ? '.tsx'
    : fileName.endsWith('.jsx') ? '.jsx'
    : fileName.endsWith('.js') ? '.js' : '.ts';
  const target = path.join(ROOT, SLOTS[ext]);
  const store = {};
  for (const s of Object.values(SLOTS)) store[path.join(ROOT, s)] = '';
  store[target] = text;
  const fs = {
    readFile: f => { const r = path.resolve(f); return Object.prototype.hasOwnProperty.call(store, r) ? store[r] : undefined; },
    fileExists: f => Object.prototype.hasOwnProperty.call(store, path.resolve(f)) ? true : undefined,
    directoryExists: () => undefined, getAccessibleEntries: () => undefined, realpath: () => undefined,
  };
  const t0 = performance.now();
  const api = new API({ cwd: ROOT, fs });
  try {
    const snap = api.updateSnapshot({ openProjects: [CFG] });
    const proj = snap.getProjects()[0];
    const sf = proj && proj.program.getSourceFile(target);
    parseCount++; parseMs += performance.now() - t0;
    return sf;
  } finally {
    api.close();
  }
}

module.exports = { init, createSourceFile, stats: () => ({ parseCount, parseMs }) };
