import {
  providers
} from "unifont";
import { FontaceFontFileReader } from "../infra/fontace-font-file-reader.js";
import { LocalFontProvider } from "./local.js";
function adobe(config) {
  const provider = providers.adobe(config);
  let initializedProvider;
  return {
    name: provider._name,
    config,
    async init(context) {
      initializedProvider = await provider(context);
    },
    async resolveFont({ familyName, ...rest }) {
      return await initializedProvider?.resolveFont(familyName, rest);
    },
    async listFonts() {
      return await initializedProvider?.listFonts?.();
    }
  };
}
function bunny() {
  const provider = providers.bunny();
  let initializedProvider;
  return {
    name: provider._name,
    async init(context) {
      initializedProvider = await provider(context);
    },
    async resolveFont({ familyName, ...rest }) {
      return await initializedProvider?.resolveFont(familyName, rest);
    },
    async listFonts() {
      return await initializedProvider?.listFonts?.();
    }
  };
}
function fontshare() {
  const provider = providers.fontshare();
  let initializedProvider;
  return {
    name: provider._name,
    async init(context) {
      initializedProvider = await provider(context);
    },
    async resolveFont({ familyName, ...rest }) {
      return await initializedProvider?.resolveFont(familyName, rest);
    },
    async listFonts() {
      return await initializedProvider?.listFonts?.();
    }
  };
}
function fontsource() {
  const provider = providers.fontsource();
  let initializedProvider;
  return {
    name: provider._name,
    async init(context) {
      initializedProvider = await provider(context);
    },
    async resolveFont({ familyName, ...rest }) {
      return await initializedProvider?.resolveFont(familyName, rest);
    },
    async listFonts() {
      return await initializedProvider?.listFonts?.();
    }
  };
}
function google() {
  const provider = providers.google();
  let initializedProvider;
  return {
    name: provider._name,
    async init(context) {
      initializedProvider = await provider(context);
    },
    async resolveFont({ familyName, ...rest }) {
      return await initializedProvider?.resolveFont(familyName, rest);
    },
    async listFonts() {
      return await initializedProvider?.listFonts?.();
    }
  };
}
function googleicons() {
  const provider = providers.googleicons();
  let initializedProvider;
  return {
    name: provider._name,
    async init(context) {
      initializedProvider = await provider(context);
    },
    async resolveFont({ familyName, ...rest }) {
      return await initializedProvider?.resolveFont(familyName, rest);
    },
    async listFonts() {
      return await initializedProvider?.listFonts?.();
    }
  };
}
function local() {
  return new LocalFontProvider({
    fontFileReader: new FontaceFontFileReader()
  });
}
const fontProviders = {
  adobe,
  bunny,
  fontshare,
  fontsource,
  google,
  googleicons,
  local
};
export {
  fontProviders
};
