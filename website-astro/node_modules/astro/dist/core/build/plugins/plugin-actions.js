import { vitePluginActionsBuild } from "../../../actions/vite-plugin-actions.js";
function pluginActions(opts, internals) {
  return {
    targets: ["server"],
    hooks: {
      "build:before": () => {
        return {
          vitePlugin: vitePluginActionsBuild(opts, internals)
        };
      }
    }
  };
}
export {
  pluginActions
};
