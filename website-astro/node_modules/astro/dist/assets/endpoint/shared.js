import { imageConfig } from "astro:assets";
import { isRemotePath, removeQueryString } from "@astrojs/internal-helpers/path";
import { isRemoteAllowed } from "@astrojs/internal-helpers/remote";
import * as mime from "mrmime";
import { getConfiguredImageService } from "../internal.js";
import { etag } from "../utils/etag.js";
async function loadRemoteImage(src) {
  try {
    const res = await fetch(src);
    if (!res.ok) {
      return void 0;
    }
    return Buffer.from(await res.arrayBuffer());
  } catch {
    return void 0;
  }
}
const handleImageRequest = async ({
  request,
  loadLocalImage
}) => {
  const imageService = await getConfiguredImageService();
  if (!("transform" in imageService)) {
    throw new Error("Configured image service is not a local service");
  }
  const url = new URL(request.url);
  const transform = await imageService.parseURL(url, imageConfig);
  if (!transform?.src) {
    return new Response("Invalid request", { status: 400 });
  }
  let inputBuffer = void 0;
  if (isRemotePath(transform.src)) {
    if (!isRemoteAllowed(transform.src, imageConfig)) {
      return new Response("Forbidden", { status: 403 });
    }
    inputBuffer = await loadRemoteImage(new URL(transform.src));
  } else {
    inputBuffer = await loadLocalImage(removeQueryString(transform.src), url);
  }
  if (!inputBuffer) {
    return new Response("Internal Server Error", { status: 500 });
  }
  const { data, format } = await imageService.transform(inputBuffer, transform, imageConfig);
  return new Response(data, {
    status: 200,
    headers: {
      "Content-Type": mime.lookup(format) ?? `image/${format}`,
      "Cache-Control": "public, max-age=31536000",
      ETag: etag(data.toString()),
      Date: (/* @__PURE__ */ new Date()).toUTCString()
    }
  });
};
export {
  handleImageRequest,
  loadRemoteImage
};
