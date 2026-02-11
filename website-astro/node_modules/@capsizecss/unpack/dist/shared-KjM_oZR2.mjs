import { create } from "fontkitten";

//#region src/weightings.ts
var weightings_default = {
	"latin": {
		"0": .0053,
		"1": .0023,
		"2": .0026,
		"3": .001,
		"4": 8e-4,
		"5": .0015,
		"6": 7e-4,
		"7": 5e-4,
		"8": 7e-4,
		"9": 6e-4,
		",": .0083,
		" ": .154,
		"t": .0672,
		"h": .0351,
		"e": .0922,
		"o": .0571,
		"f": .017,
		"P": .0023,
		"p": .0163,
		"l": .0304,
		"'": .0014,
		"s": .0469,
		"R": .0015,
		"u": .0207,
		"b": .0114,
		"i": .0588,
		"c": .0232,
		"C": .0031,
		"n": .0578,
		"a": .0668,
		"d": .0298,
		"y": .0123,
		"w": .011,
		"B": .002,
		"r": .0526,
		"z": .0011,
		"G": .0011,
		"j": 9e-4,
		"T": .0041,
		".": .0079,
		"L": .0012,
		"k": .0046,
		"m": .0181,
		"]": 7e-4,
		"J": 9e-4,
		"F": .0015,
		"v": .0076,
		"g": .0155,
		"A": .004,
		"N": .0014,
		"-": .0018,
		"H": .0013,
		"D": .0013,
		"M": .0025,
		"I": .0022,
		"E": .0011,
		"\"": .0012,
		"S": .0041,
		"(": .001,
		")": .001,
		"x": .0025,
		"W": .0012,
		"Q": 1e-4,
		"Y": 3e-4,
		"q": 8e-4,
		"V": 5e-4,
		"á": 1e-4,
		"K": 7e-4,
		"U": .0016,
		"=": 7e-4,
		"[": .0021,
		"O": 9e-4,
		"é": 1e-4,
		"$": 2e-4,
		":": 8e-4,
		"|": .0038,
		"/": 1e-4,
		"%": 1e-4,
		"Z": 2e-4,
		";": 1e-4,
		"X": 1e-4
	},
	"thai": {
		"ส": .0258,
		"ว": .0372,
		"น": .0711,
		"บ": .0258,
		"จ": .0169,
		"า": .1024,
		"ก": .0552,
		"เ": .0419,
		"ร": .0873,
		"ม": .0416,
		"ค": .0214,
		"ำ": .0097,
		"ข": .0127,
		"อ": .0459,
		"ป": .0204,
		"ด": .0271,
		"ใ": .0109,
		"ภ": .0046,
		"ท": .0311,
		"พ": .0175,
		"ฤ": 9e-4,
		"ษ": .0042,
		"ศ": .0063,
		"ะ": .0255,
		"ช": .0158,
		"แ": .0158,
		"ล": .0339,
		"ง": .0433,
		"ย": .0345,
		"ห": .0197,
		"ฝ": 6e-4,
		"ต": .0239,
		"โ": .0077,
		"ญ": .0039,
		"ณ": .0071,
		"ผ": .0077,
		"ไ": .0111,
		"ฯ": 7e-4,
		"ฟ": .0044,
		"ธ": .0068,
		"ถ": .0061,
		"ฐ": .0033,
		"ซ": .0046,
		"ฉ": .0023,
		"ฑ": 4e-4,
		"ฆ": 2e-4,
		"ฬ": 3e-4,
		"ฏ": 2e-4,
		"ฎ": 3e-4,
		"ฒ": .0012,
		"ๆ": 3e-4,
		"ฮ": 4e-4,
		"๒": 1e-4,
		"๕": 1e-4
	}
};

//#endregion
//#region src/shared.ts
const supportedSubsets = Object.keys(weightings_default);
const weightingForCharacter = (character, subset) => {
	if (!Object.keys(weightings_default[subset]).includes(character)) throw new Error(`No weighting specified for character: “${character}”`);
	return weightings_default[subset][character];
};
const avgWidthForSubset = (font, subset) => {
	const sampleString = Object.keys(weightings_default[subset]).join("");
	const weightedWidth = font.glyphsForString(sampleString).reduce((sum, glyph, index) => {
		const character = sampleString.charAt(index);
		let charWidth = font["OS/2"].xAvgCharWidth;
		try {
			charWidth = glyph.advanceWidth;
		} catch (e) {
			console.warn(`Couldn’t read 'advanceWidth' for character “${character === " " ? "<space>" : character}” from “${font.familyName}”. Falling back to “xAvgCharWidth”.`);
		}
		if (glyph.isMark) return sum;
		return sum + charWidth * weightingForCharacter(character, subset);
	}, 0);
	return Math.round(weightedWidth);
};
const unpackMetricsFromFont = (font) => {
	const { capHeight, ascent, descent, lineGap, unitsPerEm, familyName, fullName, postscriptName, xHeight } = font;
	const subsets = supportedSubsets.reduce((acc, subset) => ({
		...acc,
		[subset]: { xWidthAvg: avgWidthForSubset(font, subset) }
	}), {});
	return {
		familyName,
		fullName,
		postscriptName,
		capHeight,
		ascent,
		descent,
		lineGap,
		unitsPerEm,
		xHeight,
		xWidthAvg: subsets.latin.xWidthAvg,
		subsets
	};
};
function handleCollectionErrors(font, { postscriptName, apiName, apiParamName }) {
	if (postscriptName && font === null) throw new Error([
		`The provided \`postscriptName\` of “${postscriptName}” cannot be found in the provided font collection.\n`,
		"Run the same command without specifying a `postscriptName` in the options to see the available names in the collection.",
		"For example:",
		"------------------------------------------",
		`const metrics = await ${apiName}('<${apiParamName}>');`,
		"------------------------------------------\n",
		""
	].join("\n"));
	if (font !== null && font.isCollection) {
		const availableNames = font.fonts.map((f) => f.postscriptName);
		throw new Error([
			"Metrics cannot be unpacked from a font collection.\n",
			"Provide either a single font or specify a `postscriptName` to extract from the collection via the options.",
			"For example:",
			"------------------------------------------",
			`const metrics = await ${apiName}('<${apiParamName}>', {`,
			`  postscriptName: '${availableNames[0]}'`,
			"});",
			"------------------------------------------\n",
			"Available `postscriptNames` in this font collection are:",
			...availableNames.map((fontName) => `  - ${fontName}`),
			""
		].join("\n"));
	}
}
const _fromBuffer = async (buffer, apiName, apiParamName, options) => {
	const { postscriptName } = options || {};
	const fontkitFont = create(buffer instanceof Buffer ? buffer : Buffer.from(buffer), postscriptName);
	handleCollectionErrors(fontkitFont, {
		postscriptName,
		apiName,
		apiParamName
	});
	return unpackMetricsFromFont(fontkitFont);
};
const fromBuffer = async (buffer, options) => {
	return _fromBuffer(buffer, "fromBuffer", "buffer", options);
};
const fromBlob = async (blob, options) => {
	const arrayBuffer = await blob.arrayBuffer();
	return _fromBuffer(new Uint8Array(arrayBuffer), "fromBlob", "blob", options);
};
const fromUrl = async (url, options) => {
	const arrayBuffer = await (await fetch(url)).arrayBuffer();
	return _fromBuffer(new Uint8Array(arrayBuffer), "fromUrl", "url", options);
};

//#endregion
export { supportedSubsets as a, fromUrl as i, fromBlob as n, fromBuffer as r, _fromBuffer as t };