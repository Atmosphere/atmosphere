const { getDefaultConfig } = require('expo/metro-config');
const path = require('path');

const config = getDefaultConfig(__dirname);

const atmosphereRoot = path.resolve(__dirname, '../../../atmosphere.js');
const projectNodeModules = path.resolve(__dirname, 'node_modules');

// Watch the atmosphere.js source directory (linked via file:)
config.watchFolders = [atmosphereRoot];

// When resolving modules from atmosphere.js, look in our node_modules first
config.resolver.nodeModulesPaths = [projectNodeModules];

// Force all shared deps to resolve from expo-classroom's node_modules
config.resolver.extraNodeModules = new Proxy(
  {},
  {
    get: (_target, name) => {
      return path.join(projectNodeModules, String(name));
    },
  },
);

// Block Metro from resolving react from atmosphere.js's own node_modules
// This forces the extraNodeModules fallback to our project's single React copy
const escapeRegExp = (s) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
config.resolver.blockList = [
  new RegExp(escapeRegExp(path.resolve(atmosphereRoot, 'node_modules/react/')) + '.*'),
  new RegExp(escapeRegExp(path.resolve(atmosphereRoot, 'node_modules/react-dom/')) + '.*'),
  new RegExp(escapeRegExp(path.resolve(atmosphereRoot, 'node_modules/react-markdown/')) + '.*'),
];

// React and React Native must be a single copy in the bundle, or hooks fail with
// "Invalid hook call". bun installs a file: dependency as a copy carrying its own
// devDependencies, so node_modules/atmosphere.js/node_modules/react exists (19.3.x
// against the app's pinned 19.2.3) and the blockList above, which names the repo
// path, does not cover it. Resolve both packages, and their subpaths, from the
// app root wherever the importer sits, so no nested copy can be picked.
const singletons = new Set(['react', 'react-native']);
const appOrigin = path.join(__dirname, 'package.json');
config.resolver.resolveRequest = (context, moduleName, platform) => {
  const packageName = moduleName.split('/')[0];
  if (singletons.has(packageName)) {
    return context.resolveRequest({ ...context, originModulePath: appOrigin }, moduleName, platform);
  }
  return context.resolveRequest(context, moduleName, platform);
};

// Enable package exports (subpath like "atmosphere.js/react-native")
config.resolver.unstable_enablePackageExports = true;

module.exports = config;
