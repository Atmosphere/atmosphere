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

// Enable package exports (subpath like "atmosphere.js/react-native")
config.resolver.unstable_enablePackageExports = true;

module.exports = config;
