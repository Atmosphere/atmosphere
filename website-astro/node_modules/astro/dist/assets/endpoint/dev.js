import { safeModulePaths, viteFSConfig } from "astro:assets";
import { readFile } from "node:fs/promises";
import os from "node:os";
import picomatch from "picomatch";
import { isFileLoadingAllowed } from "vite";
import { handleImageRequest, loadRemoteImage } from "./shared.js";
function replaceFileSystemReferences(src) {
  return os.platform().includes("win32") ? src.replace(/^\/@fs\//, "") : src.replace(/^\/@fs/, "");
}
async function loadLocalImage(src, url) {
  let returnValue;
  let fsPath;
  if (src.startsWith("/@fs/")) {
    fsPath = replaceFileSystemReferences(src);
  }
  if (fsPath && isFileLoadingAllowed(
    {
      fsDenyGlob: picomatch(
        // matchBase: true does not work as it's documented
        // https://github.com/micromatch/picomatch/issues/89
        // convert patterns without `/` on our side for now
        viteFSConfig.deny.map(
          (pattern) => pattern.includes("/") ? pattern : `**/${pattern}`
        ),
        {
          matchBase: false,
          nocase: true,
          dot: true
        }
      ),
      server: { fs: viteFSConfig },
      safeModulePaths
    },
    fsPath
  )) {
    try {
      returnValue = await readFile(fsPath);
    } catch {
      returnValue = void 0;
    }
    if (!returnValue) {
      try {
        const res = await fetch(new URL(src, url));
        if (res.ok) {
          returnValue = Buffer.from(await res.arrayBuffer());
        }
      } catch {
        returnValue = void 0;
      }
    }
  } else {
    const sourceUrl = new URL(src, url.origin);
    if (sourceUrl.origin !== url.origin) {
      returnValue = void 0;
    }
    return loadRemoteImage(sourceUrl);
  }
  return returnValue;
}
const GET = async ({ request }) => {
  if (!import.meta.env.DEV) {
    console.error("The dev image endpoint can only be used in dev mode.");
    return new Response("Invalid endpoint", { status: 500 });
  }
  try {
    return await handleImageRequest({ request, loadLocalImage });
  } catch (err) {
    console.error("Could not process image request:", err);
    return new Response(`Could not process image request: ${err}`, {
      status: 500
    });
  }
};
export {
  GET
};
