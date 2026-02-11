import { ActionsCantBeLoaded } from "../core/errors/errors-data.js";
import { AstroError } from "../core/errors/index.js";
import { ENTRYPOINT_VIRTUAL_MODULE_ID } from "./consts.js";
async function loadActions(moduleLoader) {
  try {
    return await moduleLoader.import(ENTRYPOINT_VIRTUAL_MODULE_ID);
  } catch (error) {
    throw new AstroError(ActionsCantBeLoaded, { cause: error });
  }
}
export {
  loadActions
};
