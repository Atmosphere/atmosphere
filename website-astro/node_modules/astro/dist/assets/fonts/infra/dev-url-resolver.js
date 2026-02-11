import { joinPaths, prependForwardSlash } from "../../../core/path.js";
import { createPlaceholderURL, stringifyPlaceholderURL } from "../../utils/url.js";
class DevUrlResolver {
  #resolved = false;
  #base;
  #searchParams;
  constructor({
    base,
    searchParams
  }) {
    this.#base = base;
    this.#searchParams = searchParams;
  }
  resolve(id) {
    this.#resolved ||= true;
    const urlPath = prependForwardSlash(joinPaths(this.#base, id));
    const url = createPlaceholderURL(urlPath);
    this.#searchParams.forEach((value, key) => {
      url.searchParams.set(key, value);
    });
    return stringifyPlaceholderURL(url);
  }
  get cspResources() {
    return this.#resolved ? ["'self'"] : [];
  }
}
export {
  DevUrlResolver
};
