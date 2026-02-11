import { t as __exportAll } from "./chunk-15K8U1wQ.mjs";
import { hash } from "ohash";
import { findAll, generate, parse } from "css-tree";
import { ofetch } from "ofetch";

//#region src/css/parse.ts
const extractableKeyMap = {
	"src": "src",
	"font-display": "display",
	"font-weight": "weight",
	"font-style": "style",
	"font-feature-settings": "featureSettings",
	"font-variation-settings": "variationSettings",
	"unicode-range": "unicodeRange"
};
const formatPriorityList = Object.values({
	woff2: "woff2",
	woff: "woff",
	otf: "opentype",
	ttf: "truetype",
	eot: "embedded-opentype",
	svg: "svg"
});
function extractFontFaceData(css, family) {
	const fontFaces = [];
	for (const node of findAll(parse(css), (node$1) => node$1.type === "Atrule" && node$1.name === "font-face")) {
		/* v8 ignore next 3 */
		if (node.type !== "Atrule" || node.name !== "font-face") continue;
		if (family) {
			if (!node.block?.children.some((child) => {
				if (child.type !== "Declaration" || child.property !== "font-family") return false;
				const value = extractCSSValue(child);
				const slug = family.toLowerCase();
				if (typeof value === "string" && value.toLowerCase() === slug) return true;
				if (Array.isArray(value) && value.length > 0 && value.some((v) => v.toLowerCase() === slug)) return true;
				return false;
			})) continue;
		}
		const data = {};
		for (const child of node.block?.children || []) if (child.type === "Declaration" && child.property in extractableKeyMap) {
			const value = extractCSSValue(child);
			data[extractableKeyMap[child.property]] = ["src", "unicode-range"].includes(child.property) && !Array.isArray(value) ? [value] : value;
		}
		if (!data.src) continue;
		fontFaces.push(data);
	}
	return mergeFontSources(fontFaces);
}
function processRawValue(value) {
	return value.split(",").map((v) => v.trim().replace(/^(?<quote>['"])(.*)\k<quote>$/, "$2"));
}
function extractCSSValue(node) {
	if (node.value.type === "Raw") return processRawValue(node.value.value);
	const values = [];
	let buffer = "";
	for (const child of node.value.children) {
		if (child.type === "Function") {
			if (child.name === "local" && child.children.first?.type === "String") values.push({ name: child.children.first.value });
			if (child.name === "format") {
				if (child.children.first?.type === "String") values.at(-1).format = child.children.first.value;
				else if (child.children.first?.type === "Identifier") values.at(-1).format = child.children.first.name;
			}
			if (child.name === "tech") {
				if (child.children.first?.type === "String") values.at(-1).tech = child.children.first.value;
				else if (child.children.first?.type === "Identifier") values.at(-1).tech = child.children.first.name;
			}
		}
		if (child.type === "Url") values.push({ url: child.value });
		if (child.type === "Identifier") buffer = buffer ? `${buffer} ${child.name}` : child.name;
		if (child.type === "String") values.push(child.value);
		if (child.type === "Dimension") {
			const dimensionValue = child.value + child.unit;
			buffer = buffer ? `${buffer} ${dimensionValue}` : dimensionValue;
		}
		if (child.type === "Operator" && child.value === "," && buffer) {
			values.push(buffer);
			buffer = "";
		}
		if (child.type === "UnicodeRange") values.push(child.value);
		if (child.type === "Number") values.push(Number(child.value));
	}
	if (buffer) values.push(buffer);
	if (values.length === 1) return values[0];
	return values;
}
function mergeFontSources(data) {
	const mergedData = [];
	for (const face of data) {
		const keys = Object.keys(face).filter((k) => k !== "src");
		const existing = mergedData.find((f) => Object.keys(f).length === keys.length + 1 && keys.every((key) => f[key]?.toString() === face[key]?.toString()));
		if (existing) {
			for (const s of face.src) if (existing.src.every((src) => "url" in src ? !("url" in s) || s.url !== src.url : !("name" in s) || s.name !== src.name)) existing.src.push(s);
		} else mergedData.push(face);
	}
	for (const face of mergedData) face.src.sort((a, b) => {
		return ("format" in a ? formatPriorityList.indexOf(a.format || "woff2") : -2) - ("format" in b ? formatPriorityList.indexOf(b.format || "woff2") : -2);
	});
	return mergedData;
}

//#endregion
//#region src/fetch.ts
function mini$fetch(url, options) {
	const retries = options?.retries ?? 3;
	const retryDelay = options?.retryDelay ?? 1e3;
	return ofetch(url, {
		baseURL: options?.baseURL,
		query: options?.query,
		responseType: options?.responseType ?? "text",
		headers: options?.headers,
		retry: false
	}).catch((err) => {
		if (retries <= 0) throw err;
		console.warn(`Could not fetch from \`${(options?.baseURL ?? "") + url}\`. Will retry in \`${retryDelay}ms\`. \`${retries}\` retries left.`);
		return new Promise((resolve) => setTimeout(resolve, retryDelay)).then(() => mini$fetch(url, {
			...options,
			retries: retries - 1
		}));
	});
}
const $fetch = Object.assign(mini$fetch, { create: (defaults) => (url, options) => mini$fetch(url, {
	...defaults,
	...options
}) });

//#endregion
//#region src/utils.ts
function defineFontProvider(name, provider) {
	return ((options) => Object.assign(provider.bind(null, options || {}), {
		_name: name,
		_options: options
	}));
}
function prepareWeights({ inputWeights, weights, hasVariableWeights }) {
	const collectedWeights = [];
	for (const weight of inputWeights) {
		if (weight.includes(" ")) {
			if (hasVariableWeights) {
				collectedWeights.push(weight);
				continue;
			}
			const [min, max] = weight.split(" ");
			collectedWeights.push(...weights.filter((_w) => {
				const w = Number(_w);
				return w >= Number(min) && w <= Number(max);
			}).map((w) => String(w)));
			continue;
		}
		if (weights.includes(weight)) collectedWeights.push(weight);
	}
	return [...new Set(collectedWeights)].map((weight) => ({
		weight,
		variable: weight.includes(" ")
	}));
}
function splitCssIntoSubsets(input) {
	const data = [];
	const comments = [];
	const nodes = findAll(parse(input, {
		positions: true,
		onComment(value, loc) {
			comments.push({
				value: value.trim(),
				endLine: loc.end.line
			});
		}
	}), (node) => node.type === "Atrule" && node.name === "font-face");
	if (comments.length === 0) return [{
		subset: null,
		css: input
	}];
	for (const node of nodes) {
		const comment = comments.filter((comment$1) => comment$1.endLine < node.loc.start.line).at(-1);
		data.push({
			subset: comment?.value ?? null,
			css: generate(node)
		});
	}
	return data;
}
const formatMap = {
	woff2: "woff2",
	woff: "woff",
	otf: "opentype",
	ttf: "truetype",
	eot: "embedded-opentype"
};
function computeIdFromSource(source) {
	return "name" in source ? source.name : source.url;
}
function cleanFontFaces(fonts, _formats) {
	const formats = _formats.map((format) => formatMap[format]);
	const result = [];
	const hashToIndex = /* @__PURE__ */ new Map();
	for (const { src: _src, meta, ...font } of fonts) {
		const key = hash(font);
		const index = hashToIndex.get(key);
		const src = _src.map((source) => "name" in source ? source : {
			...source,
			...source.format ? { format: formatMap[source.format] ?? source.format } : {}
		}).filter((source) => "name" in source || !source.format || formats.includes(source.format));
		if (src.length === 0) continue;
		if (index === void 0) {
			hashToIndex.set(key, result.push({
				...font,
				...meta ? { meta } : {},
				src
			}) - 1);
			continue;
		}
		const existing = result[index];
		const ids = new Set(existing.src.map((source) => computeIdFromSource(source)));
		existing.src.push(...src.filter((source) => {
			const id = computeIdFromSource(source);
			return !ids.has(id) && ids.add(id);
		}));
	}
	return result;
}

//#endregion
//#region src/providers/adobe.ts
const fontCSSAPI = $fetch.create({ baseURL: "https://use.typekit.net" });
async function getAdobeFontMeta(id) {
	const { kit } = await $fetch(`https://typekit.com/api/v1/json/kits/${id}/published`, { responseType: "json" });
	return kit;
}
const KIT_REFRESH_TIMEOUT = 300 * 1e3;
var adobe_default = defineFontProvider("adobe", async (options, ctx) => {
	if (!options.id) return;
	const familyMap = /* @__PURE__ */ new Map();
	const notFoundFamilies = /* @__PURE__ */ new Set();
	const fonts = { kits: [] };
	let lastRefreshKitTime;
	const kits = typeof options.id === "string" ? [options.id] : options.id;
	await fetchKits();
	async function fetchKits(bypassCache = false) {
		familyMap.clear();
		notFoundFamilies.clear();
		fonts.kits = [];
		await Promise.all(kits.map(async (id) => {
			let meta;
			const key = `adobe:meta-${id}.json`;
			if (bypassCache) {
				meta = await getAdobeFontMeta(id);
				await ctx.storage.setItem(key, meta);
			} else meta = await ctx.storage.getItem(key, () => getAdobeFontMeta(id));
			if (!meta) throw new TypeError("No font metadata found in adobe response.");
			fonts.kits.push(meta);
			for (const family of meta.families) familyMap.set(family.name, family.id);
		}));
	}
	async function getFontDetails(family, options$1) {
		options$1.weights = options$1.weights.map(String);
		for (const kit of fonts.kits) {
			const font = kit.families.find((f) => f.name === family);
			if (!font) continue;
			const weights = prepareWeights({
				inputWeights: options$1.weights,
				hasVariableWeights: false,
				weights: font.variations.map((v) => `${v.slice(-1)}00`)
			}).map((w) => w.weight);
			const styles = [];
			for (const style of font.variations) {
				if (style.includes("i") && !options$1.styles.includes("italic")) continue;
				if (!weights.includes(String(`${style.slice(-1)}00`))) continue;
				styles.push(style);
			}
			if (styles.length === 0) continue;
			return extractFontFaceData(await fontCSSAPI(`/${kit.id}.css`), font.css_names[0] ?? family.toLowerCase().split(" ").join("-")).filter((font$1) => {
				const [lowerWeight, upperWeight] = Array.isArray(font$1.weight) ? font$1.weight : [0, 0];
				return (!options$1.styles || !font$1.style || options$1.styles.includes(font$1.style)) && (!weights || !font$1.weight || Array.isArray(font$1.weight) ? weights.some((weight) => Number(weight) <= upperWeight || Number(weight) >= lowerWeight) : weights.includes(String(font$1.weight)));
			});
		}
		return [];
	}
	return {
		listFonts() {
			return [...familyMap.keys()];
		},
		async resolveFont(family, options$1) {
			if (notFoundFamilies.has(family)) return;
			if (!familyMap.has(family)) {
				const lastRefetch = lastRefreshKitTime || 0;
				if (Date.now() - lastRefetch > KIT_REFRESH_TIMEOUT) {
					lastRefreshKitTime = Date.now();
					await fetchKits(true);
				}
			}
			if (!familyMap.has(family)) {
				notFoundFamilies.add(family);
				return;
			}
			return { fonts: await ctx.storage.getItem(`adobe:${family}-${hash(options$1)}-data.json`, () => getFontDetails(family, options$1)) };
		}
	};
});

//#endregion
//#region src/providers/bunny.ts
const fontAPI$2 = $fetch.create({ baseURL: "https://fonts.bunny.net" });
var bunny_default = defineFontProvider("bunny", async (_options, ctx) => {
	const familyMap = /* @__PURE__ */ new Map();
	const fonts = await ctx.storage.getItem("bunny:meta.json", () => fontAPI$2("/list", { responseType: "json" }));
	for (const [id, family] of Object.entries(fonts)) familyMap.set(family.familyName, id);
	async function getFontDetails(family, options) {
		const id = familyMap.get(family);
		const font = fonts[id];
		const weights = prepareWeights({
			inputWeights: options.weights,
			hasVariableWeights: false,
			weights: font.weights.map(String)
		});
		const styleMap = {
			italic: "i",
			oblique: "i",
			normal: ""
		};
		const styles = new Set(options.styles.map((i) => styleMap[i]));
		if (weights.length === 0 || styles.size === 0) return [];
		const css = await fontAPI$2("/css", { query: { family: `${id}:${weights.flatMap((w) => [...styles].map((s) => `${w.weight}${s}`)).join(",")}` } });
		const resolvedFontFaceData = [];
		const groups = splitCssIntoSubsets(css).filter((group) => group.subset ? options.subsets.includes(group.subset) : true);
		for (const group of groups) {
			const data = extractFontFaceData(group.css);
			data.map((f) => {
				f.meta ??= {};
				if (group.subset) f.meta.subset = group.subset;
				return f;
			});
			resolvedFontFaceData.push(...data);
		}
		return cleanFontFaces(resolvedFontFaceData, options.formats);
	}
	return {
		listFonts() {
			return [...familyMap.keys()];
		},
		async resolveFont(fontFamily, defaults) {
			if (!familyMap.has(fontFamily)) return;
			return { fonts: await ctx.storage.getItem(`bunny:${fontFamily}-${hash(defaults)}-data.json`, () => getFontDetails(fontFamily, defaults)) };
		}
	};
});

//#endregion
//#region src/providers/fontshare.ts
const fontAPI$1 = $fetch.create({ baseURL: "https://api.fontshare.com/v2" });
var fontshare_default = defineFontProvider("fontshare", async (_options, ctx) => {
	const fontshareFamilies = /* @__PURE__ */ new Set();
	const fonts = await ctx.storage.getItem("fontshare:meta.json", async () => {
		const fonts$1 = [];
		let offset = 0;
		let chunk;
		do {
			chunk = await fontAPI$1("/fonts", {
				responseType: "json",
				query: {
					offset,
					limit: 100
				}
			});
			fonts$1.push(...chunk.fonts);
			offset++;
		} while (chunk.has_more);
		return fonts$1;
	});
	for (const font of fonts) fontshareFamilies.add(font.name);
	async function getFontDetails(family, options) {
		const font = fonts.find((f) => f.name === family);
		const numbers = [];
		const weights = prepareWeights({
			inputWeights: options.weights,
			hasVariableWeights: false,
			weights: font.styles.map((s) => String(s.weight.weight))
		}).map((w) => w.weight);
		for (const style of font.styles) {
			if (style.is_italic && !options.styles.includes("italic")) continue;
			if (!style.is_italic && !options.styles.includes("normal")) continue;
			if (!weights.includes(String(style.weight.weight))) continue;
			numbers.push(style.weight.number);
		}
		if (numbers.length === 0) return [];
		return cleanFontFaces(extractFontFaceData(await fontAPI$1(`/css?f[]=${`${font.slug}@${numbers.join(",")}`}`)), options.formats);
	}
	return {
		listFonts() {
			return [...fontshareFamilies];
		},
		async resolveFont(fontFamily, defaults) {
			if (!fontshareFamilies.has(fontFamily)) return;
			return { fonts: await ctx.storage.getItem(`fontshare:${fontFamily}-${hash(defaults)}-data.json`, () => getFontDetails(fontFamily, defaults)) };
		}
	};
});

//#endregion
//#region src/providers/fontsource.ts
const fontAPI = $fetch.create({ baseURL: "https://api.fontsource.org/v1" });
var fontsource_default = defineFontProvider("fontsource", async (_options, ctx) => {
	const fonts = await ctx.storage.getItem("fontsource:meta.json", () => fontAPI("/fonts", { responseType: "json" }));
	const familyMap = /* @__PURE__ */ new Map();
	for (const meta of fonts) familyMap.set(meta.family, meta);
	async function getFontDetails(family, options) {
		const font = familyMap.get(family);
		const weights = prepareWeights({
			inputWeights: options.weights,
			hasVariableWeights: font.variable,
			weights: font.weights.map(String)
		});
		const styles = options.styles.filter((style) => font.styles.includes(style));
		const subsets = options.subsets ? options.subsets.filter((subset) => font.subsets.includes(subset)) : [font.defSubset];
		if (weights.length === 0 || styles.length === 0) return [];
		const fontDetail = await fontAPI(`/fonts/${font.id}`, { responseType: "json" });
		const fontFaceData = [];
		for (const subset of subsets) for (const style of styles) for (const { weight, variable } of weights) {
			if (variable) {
				try {
					const variableAxes = await ctx.storage.getItem(`fontsource:${font.family}-axes.json`, () => fontAPI(`/variable/${font.id}`, { responseType: "json" }));
					if (variableAxes && variableAxes.axes.wght) fontFaceData.push({
						style,
						weight: [Number(variableAxes.axes.wght.min), Number(variableAxes.axes.wght.max)],
						src: [{
							url: `https://cdn.jsdelivr.net/fontsource/fonts/${font.id}:vf@latest/${subset}-wght-${style}.woff2`,
							format: "woff2"
						}],
						unicodeRange: fontDetail.unicodeRange[subset]?.split(","),
						meta: { subset }
					});
				} catch {
					console.error(`Could not download variable axes metadata for \`${font.family}\` from \`fontsource\`. \`unifont\` will not be able to inject variable axes for ${font.family}.`);
				}
				continue;
			}
			const variantUrl = fontDetail.variants[weight][style][subset].url;
			fontFaceData.push({
				style,
				weight,
				src: Object.entries(variantUrl).map(([format, url]) => ({
					url,
					format
				})),
				unicodeRange: fontDetail.unicodeRange[subset]?.split(","),
				meta: { subset }
			});
		}
		return cleanFontFaces(fontFaceData, options.formats);
	}
	return {
		listFonts() {
			return [...familyMap.keys()];
		},
		async resolveFont(fontFamily, options) {
			if (!familyMap.has(fontFamily)) return;
			return { fonts: await ctx.storage.getItem(`fontsource:${fontFamily}-${hash(options)}-data.json`, () => getFontDetails(fontFamily, options)) };
		}
	};
});

//#endregion
//#region src/providers/google.ts
const userAgents = {
	eot: "Mozilla/4.0 (compatible; MSIE 8.0; Windows NT 6.1; Trident/4.0)",
	ttf: "Mozilla/5.0 (Macintosh; U; Intel Mac OS X 10_6_8; de-at) AppleWebKit/533.21.1 (KHTML, like Gecko) Version/5.0.5 Safari/533.21.1",
	woff: "Mozilla/5.0 (Windows NT 6.1; WOW64; rv:27.0) Gecko/20100101 Firefox/27.0",
	woff2: "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
};
var google_default = defineFontProvider("google", async (providerOptions, ctx) => {
	const googleFonts = await ctx.storage.getItem("google:meta.json", () => $fetch("https://fonts.google.com/metadata/fonts", { responseType: "json" }).then((r) => r.familyMetadataList));
	const styleMap = {
		italic: "1",
		oblique: "1",
		normal: "0"
	};
	async function getFontDetails(family, options) {
		const font = googleFonts.find((font$1) => font$1.family === family);
		const styles = [...new Set(options.styles.map((i) => styleMap[i]))].sort();
		const glyphs = (options.options?.experimental?.glyphs ?? providerOptions.experimental?.glyphs?.[family])?.join("");
		const weights = prepareWeights({
			inputWeights: options.weights,
			hasVariableWeights: font.axes.some((a) => a.tag === "wght"),
			weights: Object.keys(font.fonts)
		}).map((v) => v.variable ? {
			weight: v.weight.replace(" ", ".."),
			variable: v.variable
		} : v);
		if (weights.length === 0 || styles.length === 0) return [];
		const resolvedAxes = [];
		let resolvedVariants = [];
		const variableAxis = options.options?.experimental?.variableAxis ?? providerOptions.experimental?.variableAxis?.[family];
		const candidateAxes = [
			"wght",
			"ital",
			...Object.keys(variableAxis ?? {})
		].sort(googleFlavoredSorting);
		for (const axis of candidateAxes) {
			const axisValue = {
				wght: weights.map((v) => v.weight),
				ital: styles
			}[axis] ?? variableAxis[axis].map((v) => Array.isArray(v) ? `${v[0]}..${v[1]}` : v);
			if (resolvedVariants.length === 0) resolvedVariants = axisValue;
			else resolvedVariants = resolvedVariants.flatMap((v) => [...axisValue].map((o) => [v, o].join(","))).sort();
			resolvedAxes.push(axis);
		}
		let priority = 0;
		const resolvedFontFaceData = [];
		for (const format of options.formats) {
			const userAgent = userAgents[format];
			if (!userAgent) continue;
			const groups = splitCssIntoSubsets(await $fetch("/css2", {
				baseURL: "https://fonts.googleapis.com",
				headers: { "user-agent": userAgent },
				query: {
					family: `${family}:${resolvedAxes.join(",")}@${resolvedVariants.join(";")}`,
					...glyphs && { text: glyphs }
				}
			})).filter((group) => group.subset ? options.subsets.includes(group.subset) : true);
			for (const group of groups) {
				const data = extractFontFaceData(group.css);
				data.map((f) => {
					f.meta ??= {};
					f.meta.priority = priority;
					if (group.subset) f.meta.subset = group.subset;
					return f;
				});
				resolvedFontFaceData.push(...data);
			}
			priority++;
		}
		return cleanFontFaces(resolvedFontFaceData, options.formats);
	}
	return {
		listFonts() {
			return googleFonts.map((font) => font.family);
		},
		async resolveFont(fontFamily, options) {
			if (!googleFonts.some((font) => font.family === fontFamily)) return;
			return { fonts: await ctx.storage.getItem(`google:${fontFamily}-${hash(options)}-data.json`, () => getFontDetails(fontFamily, options)) };
		}
	};
});
function googleFlavoredSorting(a, b) {
	const isALowercase = a.charAt(0) === a.charAt(0).toLowerCase();
	const isBLowercase = b.charAt(0) === b.charAt(0).toLowerCase();
	if (isALowercase !== isBLowercase) return Number(isBLowercase) - Number(isALowercase);
	else return a.localeCompare(b);
}

//#endregion
//#region src/providers/googleicons.ts
var googleicons_default = defineFontProvider("googleicons", async (providerOptions, ctx) => {
	const googleIcons = await ctx.storage.getItem("googleicons:meta.json", async () => {
		const data = await $fetch("https://fonts.google.com/metadata/icons?key=material_symbols&incomplete=true");
		return JSON.parse(data.substring(data.indexOf("\n") + 1)).families;
	});
	async function getFontDetails(family, options) {
		const iconNames = (options.options?.experimental?.glyphs ?? providerOptions.experimental?.glyphs?.[family])?.join("");
		let css = "";
		for (const format of options.formats) {
			const userAgent = userAgents[format];
			if (!userAgent) continue;
			if (family.includes("Icons")) css += await $fetch("/icon", {
				baseURL: "https://fonts.googleapis.com",
				headers: { "user-agent": userAgent },
				query: { family }
			});
			else css += await $fetch("/css2", {
				baseURL: "https://fonts.googleapis.com",
				headers: { "user-agent": userAgent },
				query: {
					family: `${family}:opsz,wght,FILL,GRAD@20..48,100..700,0..1,-50..200`,
					...iconNames && { icon_names: iconNames }
				}
			});
		}
		return cleanFontFaces(extractFontFaceData(css), options.formats);
	}
	return {
		listFonts() {
			return googleIcons;
		},
		async resolveFont(fontFamily, options) {
			if (!googleIcons.includes(fontFamily)) return;
			return { fonts: await ctx.storage.getItem(`googleicons:${fontFamily}-${hash(options)}-data.json`, () => getFontDetails(fontFamily, options)) };
		}
	};
});

//#endregion
//#region src/providers.ts
var providers_exports = /* @__PURE__ */ __exportAll({
	adobe: () => adobe_default,
	bunny: () => bunny_default,
	fontshare: () => fontshare_default,
	fontsource: () => fontsource_default,
	google: () => google_default,
	googleicons: () => googleicons_default
});

//#endregion
//#region package.json
var version = "0.7.3";

//#endregion
//#region src/cache.ts
function memoryStorage() {
	const cache = /* @__PURE__ */ new Map();
	return {
		getItem(key) {
			return cache.get(key);
		},
		setItem(key, value) {
			cache.set(key, value);
		}
	};
}
const ONE_WEEK = 1e3 * 60 * 60 * 24 * 7;
function createAsyncStorage(storage, options = {}) {
	const prefix = options?.cachedBy?.length ? `${createCacheKey(...options.cachedBy)}:` : "";
	const resolveKey = (key) => `${prefix}${key}`;
	return {
		async getItem(key, init) {
			const resolvedKey = resolveKey(key);
			const now = Date.now();
			const res = await storage.getItem(resolvedKey);
			if (res && res.expires > now && res.version === version) return res.data;
			if (!init) return null;
			const data = await init();
			await storage.setItem(resolvedKey, {
				expires: now + ONE_WEEK,
				version,
				data
			});
			return data;
		},
		async setItem(key, data) {
			await storage.setItem(resolveKey(key), {
				expires: Date.now() + ONE_WEEK,
				version,
				data
			});
		}
	};
}
function createCacheKey(...fragments) {
	return fragments.map((f) => {
		return sanitize(typeof f === "string" ? f : hash(f));
	}).join(":");
}
function sanitize(input) {
	if (!input) return "";
	return input.replace(/[^\w.-]/g, "_");
}

//#endregion
//#region src/unifont.ts
const defaultResolveOptions = {
	weights: ["400"],
	styles: ["normal", "italic"],
	subsets: [
		"cyrillic-ext",
		"cyrillic",
		"greek-ext",
		"greek",
		"vietnamese",
		"latin-ext",
		"latin"
	],
	formats: ["woff2"]
};
async function createUnifont(providers, unifontOptions) {
	const stack = {};
	const storage = unifontOptions?.storage ?? memoryStorage();
	for (const provider of providers) stack[provider._name] = void 0;
	await Promise.all(providers.map(async (provider) => {
		const context = { storage: createAsyncStorage(storage, { cachedBy: [provider._name, provider._options] }) };
		try {
			const initializedProvider = await provider(context);
			if (initializedProvider) stack[provider._name] = initializedProvider;
		} catch (cause) {
			const message = `Could not initialize provider \`${provider._name}\`. \`unifont\` will not be able to process fonts provided by this provider.`;
			if (unifontOptions?.throwOnError) throw new Error(message, { cause });
			console.error(message, cause);
		}
		if (!stack[provider._name]?.resolveFont) delete stack[provider._name];
	}));
	const allProviders = Object.keys(stack);
	async function resolveFont(fontFamily, options = {}, providers$1 = allProviders) {
		const mergedOptions = {
			...defaultResolveOptions,
			...options
		};
		for (const id of providers$1) {
			const provider = stack[id];
			try {
				const result = await provider?.resolveFont(fontFamily, {
					...mergedOptions,
					options: mergedOptions.options?.[id]
				});
				if (result) return {
					provider: id,
					...result
				};
			} catch (cause) {
				const message = `Could not resolve font face for \`${fontFamily}\` from \`${id}\` provider.`;
				if (unifontOptions?.throwOnError) throw new Error(message, { cause });
				console.error(message, cause);
			}
		}
		return { fonts: [] };
	}
	async function listFonts(providers$1 = allProviders) {
		let names;
		for (const id of providers$1) {
			const provider = stack[id];
			try {
				const result = await provider?.listFonts?.();
				if (result) {
					names ??= [];
					names.push(...result);
				}
			} catch (cause) {
				const message = `Could not list names from \`${id}\` provider.`;
				if (unifontOptions?.throwOnError) throw new Error(message, { cause });
				console.error(message, cause);
			}
		}
		return names;
	}
	return {
		resolveFont,
		listFonts
	};
}

//#endregion
export { createUnifont, defaultResolveOptions, defineFontProvider, providers_exports as providers };