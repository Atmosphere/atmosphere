// Fails when an `expo export --source-maps` output bundles more than one copy of
// react or react-native: two Reacts in one bundle break every hook at runtime
// ("Invalid hook call"), which no unit test or type check notices.
//
// Usage: node scripts/check-single-react.mjs <export output dir>
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

const outDir = process.argv[2];
if (!outDir) {
  console.error('usage: node scripts/check-single-react.mjs <export output dir>');
  process.exit(2);
}

const maps = [];
const walk = (dir) => {
  for (const entry of readdirSync(dir)) {
    const file = join(dir, entry);
    if (statSync(file).isDirectory()) walk(file);
    else if (file.endsWith('.map')) maps.push(file);
  }
};
walk(outDir);
if (maps.length === 0) {
  console.error(`no source map under ${outDir}: export with --source-maps`);
  process.exit(2);
}

let failed = false;
for (const map of maps) {
  const { sources } = JSON.parse(readFileSync(map, 'utf8'));
  for (const pkg of ['react', 'react-native']) {
    // The trailing slash keeps react-native-markdown-display and the like out.
    const marker = `node_modules/${pkg}/`;
    const roots = new Set();
    for (const source of sources) {
      const at = source.lastIndexOf(marker);
      if (at >= 0) roots.add(source.slice(0, at + marker.length));
    }
    console.log(`${map}: ${pkg} copies: ${roots.size} ${[...roots].join(' ')}`);
    if (roots.size !== 1) failed = true;
  }
}
if (failed) {
  console.error('expected exactly one copy of react and of react-native in the bundle');
  process.exit(1);
}
