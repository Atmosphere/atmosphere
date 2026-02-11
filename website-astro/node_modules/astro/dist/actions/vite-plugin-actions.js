import { addRollupInput } from "../core/build/add-rollup-input.js";
import { shouldAppendForwardSlash } from "../core/build/util.js";
import { getServerOutputDirectory } from "../prerender/utils.js";
import {
  ENTRYPOINT_VIRTUAL_MODULE_ID,
  OPTIONS_VIRTUAL_MODULE_ID,
  RESOLVED_ENTRYPOINT_VIRTUAL_MODULE_ID,
  RESOLVED_NOOP_ENTRYPOINT_VIRTUAL_MODULE_ID,
  RESOLVED_OPTIONS_VIRTUAL_MODULE_ID,
  RESOLVED_RUNTIME_VIRTUAL_MODULE_ID,
  RESOLVED_VIRTUAL_MODULE_ID,
  RUNTIME_VIRTUAL_MODULE_ID,
  VIRTUAL_MODULE_ID
} from "./consts.js";
import { isActionsFilePresent } from "./utils.js";
function vitePluginActionsBuild(opts, internals) {
  return {
    name: "@astro/plugin-actions-build",
    options(options) {
      return addRollupInput(options, [ENTRYPOINT_VIRTUAL_MODULE_ID]);
    },
    writeBundle(_, bundle) {
      for (const [chunkName, chunk] of Object.entries(bundle)) {
        if (chunk.type !== "asset" && chunk.facadeModuleId === RESOLVED_ENTRYPOINT_VIRTUAL_MODULE_ID) {
          const outputDirectory = getServerOutputDirectory(opts.settings);
          internals.astroActionsEntryPoint = new URL(chunkName, outputDirectory);
        }
      }
    }
  };
}
function vitePluginActions({
  fs,
  settings
}) {
  let resolvedActionsId;
  return {
    name: VIRTUAL_MODULE_ID,
    enforce: "pre",
    async resolveId(id) {
      if (id === VIRTUAL_MODULE_ID) {
        return RESOLVED_VIRTUAL_MODULE_ID;
      }
      if (id === RUNTIME_VIRTUAL_MODULE_ID) {
        return RESOLVED_RUNTIME_VIRTUAL_MODULE_ID;
      }
      if (id === OPTIONS_VIRTUAL_MODULE_ID) {
        return RESOLVED_OPTIONS_VIRTUAL_MODULE_ID;
      }
      if (id === ENTRYPOINT_VIRTUAL_MODULE_ID) {
        const resolvedModule = await this.resolve(
          `${decodeURI(new URL("actions", settings.config.srcDir).pathname)}`
        );
        if (!resolvedModule) {
          return RESOLVED_NOOP_ENTRYPOINT_VIRTUAL_MODULE_ID;
        }
        resolvedActionsId = resolvedModule.id;
        return RESOLVED_ENTRYPOINT_VIRTUAL_MODULE_ID;
      }
    },
    async configureServer(server) {
      const filePresentOnStartup = await isActionsFilePresent(fs, settings.config.srcDir);
      async function watcherCallback() {
        const filePresent = await isActionsFilePresent(fs, settings.config.srcDir);
        if (filePresentOnStartup !== filePresent) {
          server.restart();
        }
      }
      server.watcher.on("add", watcherCallback);
      server.watcher.on("change", watcherCallback);
    },
    async load(id, opts) {
      if (id === RESOLVED_VIRTUAL_MODULE_ID) {
        return { code: `export * from 'astro/actions/runtime/virtual.js';` };
      }
      if (id === RESOLVED_NOOP_ENTRYPOINT_VIRTUAL_MODULE_ID) {
        return { code: "export const server = {}" };
      }
      if (id === RESOLVED_ENTRYPOINT_VIRTUAL_MODULE_ID) {
        return { code: `export { server } from ${JSON.stringify(resolvedActionsId)};` };
      }
      if (id === RESOLVED_RUNTIME_VIRTUAL_MODULE_ID) {
        return {
          code: `export * from 'astro/actions/runtime/${opts?.ssr ? "server" : "client"}.js';`
        };
      }
      if (id === RESOLVED_OPTIONS_VIRTUAL_MODULE_ID) {
        return {
          code: `
						export const shouldAppendTrailingSlash = ${JSON.stringify(
            shouldAppendForwardSlash(settings.config.trailingSlash, settings.config.build.format)
          )};
					`
        };
      }
    }
  };
}
export {
  vitePluginActions,
  vitePluginActionsBuild
};
