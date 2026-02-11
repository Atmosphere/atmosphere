const VIRTUAL_CLIENT_ID = "virtual:astro:adapter-config/client";
const RESOLVED_VIRTUAL_CLIENT_ID = "\0" + VIRTUAL_CLIENT_ID;
function vitePluginAdapterConfig(settings) {
  return {
    name: "astro:adapter-config",
    resolveId(id) {
      if (id === VIRTUAL_CLIENT_ID) {
        return RESOLVED_VIRTUAL_CLIENT_ID;
      }
    },
    load(id, options) {
      if (id === RESOLVED_VIRTUAL_CLIENT_ID) {
        if (options?.ssr) {
          return {
            code: `export const internalFetchHeaders = {};`
          };
        }
        const adapter = settings.adapter;
        const clientConfig = adapter?.client || {};
        let internalFetchHeaders = {};
        if (clientConfig.internalFetchHeaders) {
          internalFetchHeaders = typeof clientConfig.internalFetchHeaders === "function" ? clientConfig.internalFetchHeaders() : clientConfig.internalFetchHeaders;
        }
        return {
          code: `export const internalFetchHeaders = ${JSON.stringify(internalFetchHeaders)};`
        };
      }
    }
  };
}
export {
  vitePluginAdapterConfig
};
