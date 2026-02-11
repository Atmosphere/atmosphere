import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { readFile } from "node:fs/promises";
import { isAbsolute } from "node:path";
import colors from "piccolore";
import { getAlgorithm, shouldTrackCspHashes } from "../../core/csp/common.js";
import { generateCspDigest } from "../../core/encryption.js";
import { collectErrorMetadata } from "../../core/errors/dev/utils.js";
import { AstroError, AstroErrorData, isAstroError } from "../../core/errors/index.js";
import { formatErrorMessage } from "../../core/messages.js";
import { appendForwardSlash, joinPaths, prependForwardSlash } from "../../core/path.js";
import { getClientOutputDirectory } from "../../prerender/utils.js";
import {
  ASSETS_DIR,
  CACHE_DIR,
  DEFAULTS,
  RESOLVED_RUNTIME_VIRTUAL_MODULE_ID,
  RESOLVED_VIRTUAL_MODULE_ID,
  RUNTIME_VIRTUAL_MODULE_ID,
  VIRTUAL_MODULE_ID
} from "./constants.js";
import { collectComponentData } from "./core/collect-component-data.js";
import { collectFontAssetsFromFaces } from "./core/collect-font-assets-from-faces.js";
import { collectFontData } from "./core/collect-font-data.js";
import { computeFontFamiliesAssets } from "./core/compute-font-families-assets.js";
import { filterAndTransformFontFaces } from "./core/filter-and-transform-font-faces.js";
import { getOrCreateFontFamilyAssets } from "./core/get-or-create-font-family-assets.js";
import { optimizeFallbacks } from "./core/optimize-fallbacks.js";
import { resolveFamily } from "./core/resolve-family.js";
import { BuildFontFileIdGenerator } from "./infra/build-font-file-id-generator.js";
import { BuildUrlResolver } from "./infra/build-url-resolver.js";
import { CachedFontFetcher } from "./infra/cached-font-fetcher.js";
import { CapsizeFontMetricsResolver } from "./infra/capsize-font-metrics-resolver.js";
import { DevFontFileIdGenerator } from "./infra/dev-font-file-id-generator.js";
import { DevUrlResolver } from "./infra/dev-url-resolver.js";
import { FsFontFileContentResolver } from "./infra/fs-font-file-content-resolver.js";
import { LevenshteinStringMatcher } from "./infra/levenshtein-string-matcher.js";
import { MinifiableCssRenderer } from "./infra/minifiable-css-renderer.js";
import { NodeFontTypeExtractor } from "./infra/node-font-type-extractor.js";
import { RealSystemFallbacksProvider } from "./infra/system-fallbacks-provider.js";
import { UnifontFontResolver } from "./infra/unifont-font-resolver.js";
import { UnstorageFsStorage } from "./infra/unstorage-fs-storage.js";
import { XxhashHasher } from "./infra/xxhash-hasher.js";
function fontsPlugin({ settings, sync, logger }) {
  if (!settings.config.experimental.fonts) {
    return {
      name: "astro:fonts:fallback",
      resolveId(id) {
        if (id === VIRTUAL_MODULE_ID) {
          return RESOLVED_VIRTUAL_MODULE_ID;
        }
        if (id === RUNTIME_VIRTUAL_MODULE_ID) {
          return RESOLVED_RUNTIME_VIRTUAL_MODULE_ID;
        }
      },
      load(id) {
        if (id === RESOLVED_VIRTUAL_MODULE_ID || id === RESOLVED_RUNTIME_VIRTUAL_MODULE_ID) {
          return {
            code: ""
          };
        }
      }
    };
  }
  const assetsDir = prependForwardSlash(
    appendForwardSlash(joinPaths(settings.config.build.assets, ASSETS_DIR))
  );
  const baseUrl = joinPaths(settings.config.base, assetsDir);
  let fontFileById = null;
  let componentDataByCssVariable = null;
  let fontDataByCssVariable = null;
  let isBuild;
  let fontFetcher = null;
  let fontTypeExtractor = null;
  const cleanup = () => {
    componentDataByCssVariable = null;
    fontDataByCssVariable = null;
    fontFileById = null;
    fontFetcher = null;
  };
  return {
    name: "astro:fonts",
    config(_, { command }) {
      isBuild = command === "build";
    },
    async buildStart() {
      const { root } = settings.config;
      const hasher = await XxhashHasher.create();
      const storage = new UnstorageFsStorage({
        // In dev, we cache fonts data in .astro so it can be easily inspected and cleared
        base: new URL(CACHE_DIR, isBuild ? settings.config.cacheDir : settings.dotAstroDir)
      });
      const systemFallbacksProvider = new RealSystemFallbacksProvider();
      fontFetcher = new CachedFontFetcher({ storage, fetch, readFile });
      const cssRenderer = new MinifiableCssRenderer({ minify: isBuild });
      const fontMetricsResolver = new CapsizeFontMetricsResolver({ fontFetcher, cssRenderer });
      fontTypeExtractor = new NodeFontTypeExtractor();
      const stringMatcher = new LevenshteinStringMatcher();
      const urlResolver = isBuild ? new BuildUrlResolver({
        base: baseUrl,
        assetsPrefix: settings.config.build.assetsPrefix,
        searchParams: settings.adapter?.client?.assetQueryParams ?? new URLSearchParams()
      }) : new DevUrlResolver({
        base: baseUrl,
        searchParams: settings.adapter?.client?.assetQueryParams ?? new URLSearchParams()
      });
      const contentResolver = new FsFontFileContentResolver({
        readFileSync: (path) => readFileSync(path, "utf-8")
      });
      const fontFileIdGenerator = isBuild ? new BuildFontFileIdGenerator({
        hasher,
        contentResolver
      }) : new DevFontFileIdGenerator({
        hasher,
        contentResolver
      });
      const { bold } = colors;
      const defaults = DEFAULTS;
      const resolvedFamilies = settings.config.experimental.fonts.map(
        (family) => resolveFamily({ family, hasher })
      );
      const { fontFamilyAssets, fontFileById: _fontFileById } = await computeFontFamiliesAssets({
        resolvedFamilies,
        defaults,
        bold,
        logger,
        stringMatcher,
        fontResolver: await UnifontFontResolver.create({
          families: resolvedFamilies,
          hasher,
          storage,
          root
        }),
        getOrCreateFontFamilyAssets: ({ family, fontFamilyAssetsByUniqueKey }) => getOrCreateFontFamilyAssets({
          family,
          fontFamilyAssetsByUniqueKey,
          bold,
          logger
        }),
        filterAndTransformFontFaces: ({ family, fonts }) => filterAndTransformFontFaces({
          family,
          fonts,
          fontFileIdGenerator,
          fontTypeExtractor,
          urlResolver
        }),
        collectFontAssetsFromFaces: ({ collectedFontsIds, family, fontFilesIds, fonts }) => collectFontAssetsFromFaces({
          collectedFontsIds,
          family,
          fontFilesIds,
          fonts,
          fontFileIdGenerator,
          hasher,
          defaults
        })
      });
      fontDataByCssVariable = collectFontData(fontFamilyAssets);
      componentDataByCssVariable = await collectComponentData({
        cssRenderer,
        defaults,
        fontFamilyAssets,
        optimizeFallbacks: ({ collectedFonts, fallbacks, family }) => optimizeFallbacks({
          collectedFonts,
          fallbacks,
          family,
          fontMetricsResolver,
          systemFallbacksProvider
        })
      });
      fontFileById = _fontFileById;
      if (shouldTrackCspHashes(settings.config.experimental.csp)) {
        const algorithm = getAlgorithm(settings.config.experimental.csp);
        for (const { css } of componentDataByCssVariable.values()) {
          settings.injectedCsp.styleHashes.push(await generateCspDigest(css, algorithm));
        }
        for (const resource of urlResolver.cspResources) {
          settings.injectedCsp.fontResources.add(resource);
        }
      }
    },
    async configureServer(server) {
      server.watcher.on("change", (path) => {
        if (!fontFileById) {
          return;
        }
        const localPaths = [...fontFileById.values()].filter(({ url }) => isAbsolute(url)).map((v) => v.url);
        if (localPaths.includes(path)) {
          logger.info("assets", "Font file updated");
          server.restart();
        }
      });
      server.watcher.on("unlink", (path) => {
        if (!fontFileById) {
          return;
        }
        const localPaths = [...fontFileById.values()].filter(({ url }) => isAbsolute(url)).map((v) => v.url);
        if (localPaths.includes(path)) {
          logger.warn(
            "assets",
            `The font file ${JSON.stringify(path)} referenced in your config has been deleted. Restore the file or remove this font from your configuration if it is no longer needed.`
          );
        }
      });
      server.middlewares.use(assetsDir, async (req, res, next) => {
        if (!fontFetcher || !fontTypeExtractor) {
          logger.debug(
            "assets",
            "Fonts dependencies should be initialized by now, skipping dev middleware."
          );
          return next();
        }
        if (!req.url) {
          return next();
        }
        const fontId = req.url.slice(1);
        const fontData = fontFileById?.get(fontId);
        if (!fontData) {
          return next();
        }
        res.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        res.setHeader("Pragma", "no-cache");
        res.setHeader("Expires", 0);
        try {
          const buffer = await fontFetcher.fetch({ id: fontId, ...fontData });
          res.setHeader("Content-Length", buffer.length);
          res.setHeader("Content-Type", `font/${fontTypeExtractor.extract(fontId)}`);
          res.end(buffer);
        } catch (err) {
          logger.error("assets", "Cannot download font file");
          if (isAstroError(err)) {
            logger.error(
              "SKIP_FORMAT",
              formatErrorMessage(collectErrorMetadata(err), logger.level() === "debug")
            );
          }
          res.statusCode = 500;
          res.end();
        }
      });
    },
    resolveId(id) {
      if (id === VIRTUAL_MODULE_ID) {
        return RESOLVED_VIRTUAL_MODULE_ID;
      }
      if (id === RUNTIME_VIRTUAL_MODULE_ID) {
        return RESOLVED_RUNTIME_VIRTUAL_MODULE_ID;
      }
    },
    async load(id) {
      if (id === RESOLVED_VIRTUAL_MODULE_ID) {
        return {
          code: `
						export const componentDataByCssVariable = new Map(${JSON.stringify(Array.from(componentDataByCssVariable?.entries() ?? []))});
						export const fontDataByCssVariable = ${JSON.stringify(fontDataByCssVariable ?? {})}
					`
        };
      }
      if (id === RESOLVED_RUNTIME_VIRTUAL_MODULE_ID) {
        return {
          code: `export * from 'astro/assets/fonts/runtime.js';`
        };
      }
    },
    async buildEnd() {
      if (sync || settings.config.experimental.fonts.length === 0 || !isBuild) {
        cleanup();
        return;
      }
      try {
        const dir = getClientOutputDirectory(settings);
        const fontsDir = new URL(`.${assetsDir}`, dir);
        try {
          mkdirSync(fontsDir, { recursive: true });
        } catch (cause) {
          throw new AstroError(AstroErrorData.UnknownFilesystemError, { cause });
        }
        if (fontFileById) {
          logger.info(
            "assets",
            `Copying fonts (${fontFileById.size} file${fontFileById.size === 1 ? "" : "s"})...`
          );
          await Promise.all(
            Array.from(fontFileById.entries()).map(async ([id, associatedData]) => {
              const data = await fontFetcher.fetch({ id, ...associatedData });
              try {
                writeFileSync(new URL(id, fontsDir), data);
              } catch (cause) {
                throw new AstroError(AstroErrorData.UnknownFilesystemError, { cause });
              }
            })
          );
        }
      } finally {
        cleanup();
      }
    }
  };
}
export {
  fontsPlugin
};
