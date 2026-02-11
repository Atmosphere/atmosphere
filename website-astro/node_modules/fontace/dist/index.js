import { create } from "fontkitten";

//#region src/index.ts
/** Get CSS weight for a font. */
function getWeight(font) {
	if (font.variationAxes.wght) return `${font.variationAxes.wght.min} ${font.variationAxes.wght.max}`;
	return `${font["OS/2"]?.usWeightClass || (font["OS/2"]?.fsSelection?.["bold"] ? 700 : 400)}`;
}
/** Get CSS style for a font. */
function getStyle(font) {
	return font["OS/2"]?.fsSelection?.italic || font.italicAngle !== 0 ? "italic" : "normal";
}
/**
* Infer font-face properties from a buffer containing font file data.
* @param fontBuffer Buffer containing font file data.
* @example
* import { fontace } from 'fontace';
* import fs from 'node:fs';
*
* const fontBuffer = fs.readFileSync('./Inter.ttf');
* const fontMetaData = fontace(fontBuffer);
* // { family: "Inter", style: "normal", weight: "400", unicodeRange: "U+0, U+20-7E...
*/
function fontace(fontBuffer) {
	const font = create(fontBuffer);
	if (font.isCollection) throw new Error(`${font.type} files are not supported.`);
	return {
		...getUnicodeRange(font),
		family: font.familyName,
		style: getStyle(font),
		weight: getWeight(font),
		format: {
			TTF: "truetype",
			WOFF: "woff",
			WOFF2: "woff2"
		}[font.type],
		isVariable: Object.keys(font.variationAxes).length > 0
	};
}
/**
* Convert an array of unicode code points to a CSS unicode-range string.
* @param font A font object.
* @returns A CSS unicode-range string, e.g. `"U+20-22, U+4E-50"`.
*/
function getUnicodeRange({ characterSet }) {
	if (!characterSet || characterSet.length === 0) {
		/** The default value of `unicodeRange` is U+0-10FFFF, which represents all Unicode characters. */
		const defaultRange = "U+0-10FFFF";
		return {
			unicodeRange: defaultRange,
			unicodeRangeArray: [defaultRange]
		};
	}
	characterSet.sort((a, b) => a - b);
	const ranges = [];
	let start = characterSet[0];
	let end = start;
	for (let i = 1; i < characterSet.length; i++) if (characterSet[i] === end + 1) end = characterSet[i];
	else {
		ranges.push(formatRange(start, end));
		start = characterSet[i];
		end = start;
	}
	ranges.push(formatRange(start, end));
	return {
		unicodeRange: ranges.join(", "),
		unicodeRangeArray: ranges
	};
}
/**
* Format a range of unicode code points as a CSS unicode-range string.
* @param start The start of the range, e.g. `32`.
* @param end The end of the range, e.g. `34`.
* @returns A CSS unicode-range string, e.g. `"U+20-22"`.
*/
function formatRange(start, end) {
	return start === end ? `U+${start.toString(16).toUpperCase()}` : `U+${start.toString(16).toUpperCase()}-${end.toString(16).toUpperCase()}`;
}

//#endregion
export { fontace };