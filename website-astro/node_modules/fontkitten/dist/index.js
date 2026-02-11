import inflate from "tiny-inflate";

//#region ../@fontkitten/restructure/dist/index.js
const Latin1Decoder = new TextDecoder("latin1");
var DecodeStream = class DecodeStream$1 {
	#view;
	pos;
	length;
	constructor(buffer) {
		this.buffer = buffer;
		this.#view = new DataView(buffer.buffer, buffer.byteOffset, buffer.byteLength);
		this.pos = 0;
		this.length = buffer.length;
	}
	readString(length, encoding = "ascii") {
		const buf = this.readBuffer(length);
		try {
			return new TextDecoder(encoding).decode(buf);
		} catch {
			return Latin1Decoder.decode(buf);
		}
	}
	readBuffer(length) {
		return this.buffer.slice(this.pos, this.pos += length);
	}
	readUInt8() {
		const ret = this.#view.getUint8(this.pos);
		this.pos += DecodeStream$1.TYPES.UInt8;
		return ret;
	}
	readUInt16BE() {
		const ret = this.#view.getUint16(this.pos);
		this.pos += DecodeStream$1.TYPES.UInt16;
		return ret;
	}
	readUInt32BE() {
		const ret = this.#view.getUint32(this.pos);
		this.pos += DecodeStream$1.TYPES.UInt32;
		return ret;
	}
	readInt8() {
		const ret = this.#view.getInt8(this.pos);
		this.pos += DecodeStream$1.TYPES.Int8;
		return ret;
	}
	readInt16BE() {
		const ret = this.#view.getInt16(this.pos);
		this.pos += DecodeStream$1.TYPES.Int16;
		return ret;
	}
	readUInt24BE() {
		return (this.readUInt16BE() << 8) + this.readUInt8();
	}
	readInt32BE() {
		const ret = this.#view.getInt32(this.pos);
		this.pos += DecodeStream$1.TYPES.Int32;
		return ret;
	}
	static TYPES = {
		UInt8: 1,
		UInt16: 2,
		UInt24: 3,
		UInt32: 4,
		Int8: 1,
		Int16: 2,
		Int32: 4
	};
};
var NumberT = class {
	#size;
	#readFnName;
	constructor(type) {
		this.#readFnName = type === "Int8" || type === "UInt8" ? `read${type}` : `read${type}BE`;
		this.#size = DecodeStream.TYPES[type];
	}
	size() {
		return this.#size;
	}
	decode(stream) {
		return stream[this.#readFnName]();
	}
};
const uint8 = new NumberT("UInt8");
const uint16 = new NumberT("UInt16");
const uint24 = new NumberT("UInt24");
const uint32 = new NumberT("UInt32");
const int8 = new NumberT("Int8");
const int16 = new NumberT("Int16");
const int32 = new NumberT("Int32");
var Fixed = class extends NumberT {
	#point;
	constructor(size, fracBits = size >> 1) {
		super(`Int${size}`);
		this.#point = 1 << fracBits;
	}
	decode(stream) {
		return super.decode(stream) / this.#point;
	}
};
const fixed16 = new Fixed(16);
const fixed32 = new Fixed(32);
function resolveLength(length, stream, parent) {
	let res;
	if (typeof length === "number") res = length;
	else if (typeof length === "function") res = length.call(parent, parent);
	else if (parent && typeof length === "string") res = parent[length];
	else if (stream && length instanceof NumberT) res = length.decode(stream);
	if (isNaN(res)) throw new Error("Not a fixed size");
	return res;
}
var PropertyDescriptor = class {
	enumerable = true;
	configurable = true;
	constructor(opts = {}) {
		for (const key in opts) this[key] = opts[key];
	}
};
var ArrayT = class {
	#lengthType;
	constructor(type, length, lengthType = "count") {
		this.type = type;
		this.length = length;
		this.#lengthType = lengthType;
	}
	decode(stream, parent) {
		let length;
		const { pos } = stream;
		const res = [];
		let ctx = parent;
		if (this.length != null) length = resolveLength(this.length, stream, parent);
		if (this.length instanceof NumberT) {
			Object.defineProperties(res, {
				parent: { value: parent },
				_startOffset: { value: pos },
				_currentOffset: {
					value: 0,
					writable: true
				},
				_length: { value: length }
			});
			ctx = res;
		}
		if (length == null || this.#lengthType === "bytes") {
			const target = length != null ? stream.pos + length : (parent != null ? parent._length : void 0) ? parent._startOffset + parent._length : stream.length;
			while (stream.pos < target) res.push(this.type.decode(stream, ctx));
		} else for (let i = 0, end = length; i < end; i++) res.push(this.type.decode(stream, ctx));
		return res;
	}
};
var LazyArray = class extends ArrayT {
	decode(stream, parent) {
		const { pos } = stream;
		const length = resolveLength(this.length, stream, parent);
		if (this.length instanceof NumberT) parent = {
			parent,
			_startOffset: pos,
			_currentOffset: 0,
			_length: length
		};
		const res = new LazyArrayValue(this.type, length, stream, parent);
		stream.pos += length * this.type.size(null, parent);
		return res;
	}
};
var LazyArrayValue = class {
	#type;
	#stream;
	#ctx;
	#base;
	#items;
	length;
	constructor(type, length, stream, ctx) {
		this.#type = type;
		this.length = length;
		this.#stream = stream;
		this.#ctx = ctx;
		this.#base = this.#stream.pos;
		this.#items = [];
	}
	get(index) {
		if (index < 0 || index >= this.length) return;
		if (this.#items[index] == null) {
			const { pos } = this.#stream;
			this.#stream.pos = this.#base + this.#type.size(null, this.#ctx) * index;
			this.#items[index] = this.#type.decode(this.#stream, this.#ctx);
			this.#stream.pos = pos;
		}
		return this.#items[index];
	}
	toArray() {
		const result = [];
		for (let i = 0, end = this.length; i < end; i++) result.push(this.get(i));
		return result;
	}
};
var Bitfield = class {
	#type;
	#flags;
	constructor(type, flags = []) {
		this.#type = type;
		this.#flags = flags;
	}
	decode(stream) {
		const val = this.#type.decode(stream);
		const res = {};
		for (let i = 0; i < this.#flags.length; i++) {
			const flag = this.#flags[i];
			if (flag != null) res[flag] = !!(val & 1 << i);
		}
		return res;
	}
};
var BufferT = class {
	#length;
	constructor(length) {
		this.#length = length;
	}
	decode(stream, parent) {
		const length = resolveLength(this.#length, stream, parent);
		return stream.readBuffer(length);
	}
};
var Optional = class {
	#type;
	#condition;
	constructor(type, condition = true) {
		this.#type = type;
		this.#condition = condition;
	}
	decode(stream, parent) {
		if (typeof this.#condition === "function" ? this.#condition.call(parent, parent) : this.#condition) return this.#type.decode(stream, parent);
	}
};
var Reserved = class {
	#type;
	#count;
	constructor(type, count = 1) {
		this.#type = type;
		this.#count = count;
	}
	decode(stream, parent) {
		stream.pos += this.size(null, parent);
	}
	size(data$1, parent) {
		const count = resolveLength(this.#count, null, parent);
		return this.#type.size() * count;
	}
};
var StringT = class {
	#length;
	#encoding;
	constructor(length, encoding = "ascii") {
		this.#length = length;
		this.#encoding = encoding;
	}
	decode(stream, parent) {
		const encoding = typeof this.#encoding === "function" ? this.#encoding.call(parent, parent) || "ascii" : this.#encoding;
		const width = encodingWidth(encoding);
		const length = resolveLength(this.#length, stream, parent);
		const string = stream.readString(length, encoding);
		if (this.#length == null && stream.pos < stream.length) stream.pos += width;
		return string;
	}
};
function encodingWidth(encoding) {
	switch (encoding) {
		case "ascii":
		case "utf8": return 1;
		case "utf-16be":
		case "utf-16le":
		case "utf16be":
		case "utf16-be":
		case "ucs2": return 2;
		default: return 1;
	}
}
var Struct = class {
	#fields;
	process;
	constructor(fields = {}) {
		this.#fields = fields;
	}
	decode(stream, parent, length = 0) {
		const res = this._setup(stream, parent, length);
		this._parseFields(stream, res, this.#fields);
		if (this.process != null) this.process.call(res, stream);
		return res;
	}
	_setup(stream, parent, length) {
		const res = {};
		Object.defineProperties(res, {
			parent: { value: parent },
			_startOffset: { value: stream.pos },
			_currentOffset: {
				value: 0,
				writable: true
			},
			_length: { value: length }
		});
		return res;
	}
	_parseFields(stream, res, fields) {
		for (const key in fields) {
			let val;
			const type = fields[key];
			if (typeof type === "function") val = type.call(res, res);
			else val = type.decode(stream, res);
			if (val !== void 0) if (val instanceof PropertyDescriptor) Object.defineProperty(res, key, val);
			else res[key] = val;
			res._currentOffset = stream.pos - res._startOffset;
		}
	}
	size(val, parent, includePointers = true) {
		if (val == null) val = {};
		const ctx = {
			parent,
			val,
			pointerSize: 0
		};
		let size = 0;
		for (let key in this.#fields) {
			const type = this.#fields[key];
			if (typeof type !== "function" && "size" in type && type.size != null) size += type.size(val[key], ctx);
		}
		if (includePointers) size += ctx.pointerSize;
		return size;
	}
};
const getPath = (object, pathArray) => {
	return pathArray.reduce((prevObj, key) => prevObj && prevObj[key], object);
};
var VersionedStruct = class VersionedStruct$1 extends Struct {
	#type;
	#versionPath;
	constructor(type, versions) {
		super();
		this.versions = versions;
		this.#type = type;
		if (typeof type === "string") this.#versionPath = type.split(".");
	}
	decode(stream, parent, length = 0) {
		const res = this._setup(stream, parent, length);
		if (typeof this.#type === "string") res.version = getPath(parent, this.#versionPath);
		else res.version = this.#type.decode(stream);
		if (this.versions.header) this._parseFields(stream, res, this.versions.header);
		const fields = this.versions[res.version];
		if (fields == null) throw new Error(`Unknown version ${res.version}`);
		if (fields instanceof VersionedStruct$1) return fields.decode(stream, parent);
		this._parseFields(stream, res, fields);
		if (this.process != null) this.process.call(res, stream);
		return res;
	}
};
var Pointer = class {
	#type;
	#options;
	constructor(offsetType, type, options = {}) {
		this.offsetType = offsetType;
		this.#type = type === "void" ? null : type;
		this.#options = {
			type: "local",
			allowNull: true,
			nullValue: 0,
			lazy: false,
			...options
		};
	}
	decode(stream, ctx) {
		const offset = this.offsetType.decode(stream, ctx);
		if (offset === this.#options.nullValue && this.#options.allowNull) return null;
		let relative;
		switch (this.#options.type) {
			case "local":
				relative = ctx._startOffset;
				break;
			case "parent":
				relative = ctx.parent._startOffset;
				break;
			default:
				var c = ctx;
				while (c.parent) c = c.parent;
				relative = c._startOffset || 0;
		}
		if (this.#options.relativeTo) relative += this.#options.relativeTo(ctx);
		const ptr$1 = offset + relative;
		if (this.#type != null) {
			let val = null;
			const decodeValue = () => {
				if (val != null) return val;
				const { pos } = stream;
				stream.pos = ptr$1;
				val = this.#type.decode(stream, ctx);
				stream.pos = pos;
				return val;
			};
			if (this.#options.lazy) return new PropertyDescriptor({ get: decodeValue });
			return decodeValue();
		} else return ptr$1;
	}
	size() {
		return this.offsetType.size();
	}
};

//#endregion
//#region src/decorators.ts
/**
* This decorator caches the results of a getter or method such that
* the results are lazily computed once, and then cached.
*/
function cache(_target, key, descriptor) {
	if (descriptor.get) {
		const get = descriptor.get;
		descriptor.get = function() {
			const value = get.call(this);
			Object.defineProperty(this, key, { value });
			return value;
		};
	} else if (typeof descriptor.value === "function") {
		const fn = descriptor.value;
		return { get() {
			const cache$1 = /* @__PURE__ */ new Map();
			const memoized = ((...args) => {
				const key$1 = args.length > 0 ? args[0] : "value";
				if (cache$1.has(key$1)) return cache$1.get(key$1);
				const result = fn.apply(this, args);
				cache$1.set(key$1, result);
				return result;
			});
			Object.defineProperty(this, key, { value: memoized });
			return memoized;
		} };
	}
	return descriptor;
}

//#endregion
//#region src/tables/directory.ts
const TableEntry = new Struct({
	tag: new StringT(4),
	checkSum: uint32,
	offset: new Pointer(uint32, "void", { type: "global" }),
	length: uint32
});
const Directory = new Struct({
	tag: new StringT(4),
	numTables: uint16,
	searchRange: uint16,
	entrySelector: uint16,
	rangeShift: uint16,
	tables: new ArrayT(TableEntry, "numTables")
});
Directory.process = function() {
	this.tables = Object.fromEntries(this.tables.map((table) => [table.tag, table]));
};
var directory_default = Directory;

//#endregion
//#region src/tables/cmap.ts
const SubHeader = new Struct({
	firstCode: uint16,
	entryCount: uint16,
	idDelta: int16,
	idRangeOffset: uint16
});
const CmapGroup = new Struct({
	startCharCode: uint32,
	endCharCode: uint32,
	glyphID: uint32
});
const UnicodeValueRange = new Struct({
	startUnicodeValue: uint24,
	additionalCount: uint8
});
const UVSMapping = new Struct({
	unicodeValue: uint24,
	glyphID: uint16
});
const DefaultUVS = new ArrayT(UnicodeValueRange, uint32);
const NonDefaultUVS = new ArrayT(UVSMapping, uint32);
const VarSelectorRecord = new Struct({
	varSelector: uint24,
	defaultUVS: new Pointer(uint32, DefaultUVS, { type: "parent" }),
	nonDefaultUVS: new Pointer(uint32, NonDefaultUVS, { type: "parent" })
});
const CmapSubtable = new VersionedStruct(uint16, {
	0: {
		length: uint16,
		language: uint16,
		codeMap: new LazyArray(uint8, 256)
	},
	2: {
		length: uint16,
		language: uint16,
		subHeaderKeys: new ArrayT(uint16, 256),
		subHeaderCount: (t) => Math.max.apply(Math, t.subHeaderKeys),
		subHeaders: new LazyArray(SubHeader, "subHeaderCount"),
		glyphIndexArray: new LazyArray(uint16, "subHeaderCount")
	},
	4: {
		length: uint16,
		language: uint16,
		segCountX2: uint16,
		segCount: (t) => t.segCountX2 >> 1,
		searchRange: uint16,
		entrySelector: uint16,
		rangeShift: uint16,
		endCode: new LazyArray(uint16, "segCount"),
		reservedPad: new Reserved(uint16),
		startCode: new LazyArray(uint16, "segCount"),
		idDelta: new LazyArray(int16, "segCount"),
		idRangeOffset: new LazyArray(uint16, "segCount"),
		glyphIndexArray: new LazyArray(uint16, (t) => (t.length - t._currentOffset) / 2)
	},
	6: {
		length: uint16,
		language: uint16,
		firstCode: uint16,
		entryCount: uint16,
		glyphIndices: new LazyArray(uint16, "entryCount")
	},
	8: {
		reserved: new Reserved(uint16),
		length: uint32,
		language: uint16,
		is32: new LazyArray(uint8, 8192),
		nGroups: uint32,
		groups: new LazyArray(CmapGroup, "nGroups")
	},
	10: {
		reserved: new Reserved(uint16),
		length: uint32,
		language: uint32,
		firstCode: uint32,
		entryCount: uint32,
		glyphIndices: new LazyArray(uint16, "numChars")
	},
	12: {
		reserved: new Reserved(uint16),
		length: uint32,
		language: uint32,
		nGroups: uint32,
		groups: new LazyArray(CmapGroup, "nGroups")
	},
	13: {
		reserved: new Reserved(uint16),
		length: uint32,
		language: uint32,
		nGroups: uint32,
		groups: new LazyArray(CmapGroup, "nGroups")
	},
	14: {
		length: uint32,
		numRecords: uint32,
		varSelectors: new LazyArray(VarSelectorRecord, "numRecords")
	}
});
const CmapEntry = new Struct({
	platformID: uint16,
	encodingID: uint16,
	table: new Pointer(uint32, CmapSubtable, {
		type: "parent",
		lazy: true
	})
});
var cmap_default = new Struct({
	version: uint16,
	numSubtables: uint16,
	tables: new ArrayT(CmapEntry, "numSubtables")
});

//#endregion
//#region src/tables/head.ts
var head_default = new Struct({
	version: int32,
	revision: int32,
	checkSumAdjustment: uint32,
	magicNumber: uint32,
	flags: uint16,
	unitsPerEm: uint16,
	created: new ArrayT(int32, 2),
	modified: new ArrayT(int32, 2),
	xMin: int16,
	yMin: int16,
	xMax: int16,
	yMax: int16,
	macStyle: new Bitfield(uint16, [
		"bold",
		"italic",
		"underline",
		"outline",
		"shadow",
		"condensed",
		"extended"
	]),
	lowestRecPPEM: uint16,
	fontDirectionHint: int16,
	indexToLocFormat: int16,
	glyphDataFormat: int16
});

//#endregion
//#region src/tables/hhea.ts
var hhea_default = new Struct({
	version: int32,
	ascent: int16,
	descent: int16,
	lineGap: int16,
	advanceWidthMax: uint16,
	minLeftSideBearing: int16,
	minRightSideBearing: int16,
	xMaxExtent: int16,
	caretSlopeRise: int16,
	caretSlopeRun: int16,
	caretOffset: int16,
	reserved: new Reserved(int16, 4),
	metricDataFormat: int16,
	numberOfMetrics: uint16
});

//#endregion
//#region src/tables/hmtx.ts
const HmtxEntry = new Struct({
	advance: uint16,
	bearing: int16
});
var hmtx_default = new Struct({
	metrics: new LazyArray(HmtxEntry, (t) => t.parent.hhea.numberOfMetrics),
	bearings: new LazyArray(int16, (t) => t.parent.maxp.numGlyphs - t.parent.hhea.numberOfMetrics)
});

//#endregion
//#region src/tables/maxp.ts
var maxp_default = new Struct({
	version: int32,
	numGlyphs: uint16,
	maxPoints: uint16,
	maxContours: uint16,
	maxComponentPoints: uint16,
	maxComponentContours: uint16,
	maxZones: uint16,
	maxTwilightPoints: uint16,
	maxStorage: uint16,
	maxFunctionDefs: uint16,
	maxInstructionDefs: uint16,
	maxStackElements: uint16,
	maxSizeOfInstructions: uint16,
	maxComponentElements: uint16,
	maxComponentDepth: uint16
});

//#endregion
//#region src/encodings.ts
/**
* Gets an encoding name from platform, encoding, and language ids.
* Returned encoding names can be used in iconv-lite to decode text.
*/
function getEncoding(platformID, encodingID, languageID = 0) {
	return platformID === 1 && MAC_LANGUAGE_ENCODINGS[languageID] ? MAC_LANGUAGE_ENCODINGS[languageID] : ENCODINGS[platformID][encodingID];
}
const SINGLE_BYTE_ENCODINGS = new Set([
	"x-mac-roman",
	"x-mac-cyrillic",
	"iso-8859-6",
	"iso-8859-8"
]);
const MAC_ENCODINGS = {
	"x-mac-croatian": "ÄÅÇÉÑÖÜáàâäãåçéèêëíìîïñóòôöõúùûü†°¢£§•¶ß®Š™´¨≠ŽØ∞±≤≥∆µ∂∑∏š∫ªºΩžø¿¡¬√ƒ≈Ć«Č… ÀÃÕŒœĐ—“”‘’÷◊©⁄€‹›Æ»–·‚„‰ÂćÁčÈÍÎÏÌÓÔđÒÚÛÙıˆ˜¯πË˚¸Êæˇ",
	"x-mac-gaelic": "ÄÅÇÉÑÖÜáàâäãåçéèêëíìîïñóòôöõúùûü†°¢£§•¶ß®©™´¨≠ÆØḂ±≤≥ḃĊċḊḋḞḟĠġṀæøṁṖṗɼƒſṠ«»… ÀÃÕŒœ–—“”‘’ṡẛÿŸṪ€‹›Ŷŷṫ·Ỳỳ⁊ÂÊÁËÈÍÎÏÌÓÔ♣ÒÚÛÙıÝýŴŵẄẅẀẁẂẃ",
	"x-mac-greek": "Ä¹²É³ÖÜ΅àâä΄¨çéèêë£™îï•½‰ôö¦€ùûü†ΓΔΘΛΞΠß®©ΣΪ§≠°·Α±≤≥¥ΒΕΖΗΙΚΜΦΫΨΩάΝ¬ΟΡ≈Τ«»… ΥΧΆΈœ–―“”‘’÷ΉΊΌΎέήίόΏύαβψδεφγηιξκλμνοπώρστθωςχυζϊϋΐΰ­",
	"x-mac-icelandic": "ÄÅÇÉÑÖÜáàâäãåçéèêëíìîïñóòôöõúùûüÝ°¢£§•¶ß®©™´¨≠ÆØ∞±≤≥¥µ∂∑∏π∫ªºΩæø¿¡¬√ƒ≈∆«»… ÀÃÕŒœ–—“”‘’÷◊ÿŸ⁄€ÐðÞþý·‚„‰ÂÊÁËÈÍÎÏÌÓÔÒÚÛÙıˆ˜¯˘˙˚¸˝˛ˇ",
	"x-mac-inuit": "ᐃᐄᐅᐆᐊᐋᐱᐲᐳᐴᐸᐹᑉᑎᑏᑐᑑᑕᑖᑦᑭᑮᑯᑰᑲᑳᒃᒋᒌᒍᒎᒐᒑ°ᒡᒥᒦ•¶ᒧ®©™ᒨᒪᒫᒻᓂᓃᓄᓅᓇᓈᓐᓯᓰᓱᓲᓴᓵᔅᓕᓖᓗᓘᓚᓛᓪᔨᔩᔪᔫᔭ… ᔮᔾᕕᕖᕗ–—“”‘’ᕘᕙᕚᕝᕆᕇᕈᕉᕋᕌᕐᕿᖀᖁᖂᖃᖄᖅᖏᖐᖑᖒᖓᖔᖕᙱᙲᙳᙴᙵᙶᖖᖠᖡᖢᖣᖤᖥᖦᕼŁł",
	"x-mac-ce": "ÄĀāÉĄÖÜáąČäčĆćéŹźĎíďĒēĖóėôöõúĚěü†°Ę£§•¶ß®©™ę¨≠ģĮįĪ≤≥īĶ∂∑łĻļĽľĹĺŅņŃ¬√ńŇ∆«»… ňŐÕőŌ–—“”‘’÷◊ōŔŕŘ‹›řŖŗŠ‚„šŚśÁŤťÍŽžŪÓÔūŮÚůŰűŲųÝýķŻŁżĢˇ",
	"x-mac-romanian": "ÄÅÇÉÑÖÜáàâäãåçéèêëíìîïñóòôöõúùûü†°¢£§•¶ß®©™´¨≠ĂȘ∞±≤≥¥µ∂∑∏π∫ªºΩăș¿¡¬√ƒ≈∆«»… ÀÃÕŒœ–—“”‘’÷◊ÿŸ⁄€‹›Țț‡·‚„‰ÂÊÁËÈÍÎÏÌÓÔÒÚÛÙıˆ˜¯˘˙˚¸˝˛ˇ",
	"x-mac-turkish": "ÄÅÇÉÑÖÜáàâäãåçéèêëíìîïñóòôöõúùûü†°¢£§•¶ß®©™´¨≠ÆØ∞±≤≥¥µ∂∑∏π∫ªºΩæø¿¡¬√ƒ≈∆«»… ÀÃÕŒœ–—“”‘’÷◊ÿŸĞğİıŞş‡·‚„‰ÂÊÁËÈÍÎÏÌÓÔÒÚÛÙˆ˜¯˘˙˚¸˝˛ˇ"
};
const encodingCache = /* @__PURE__ */ new Map();
function getEncodingMapping(encoding) {
	const cached = encodingCache.get(encoding);
	if (cached) return cached;
	const mapping = MAC_ENCODINGS[encoding];
	if (mapping) {
		const res = /* @__PURE__ */ new Map();
		for (let i = 0; i < mapping.length; i++) res.set(mapping.charCodeAt(i), 128 + i);
		encodingCache.set(encoding, res);
		return res;
	}
	if (SINGLE_BYTE_ENCODINGS.has(encoding)) {
		const decoder = new TextDecoder(encoding);
		const mapping$1 = new Uint8Array(128);
		for (let i = 0; i < 128; i++) mapping$1[i] = 128 + i;
		const res = /* @__PURE__ */ new Map();
		const s = decoder.decode(mapping$1);
		for (let i = 0; i < 128; i++) res.set(s.charCodeAt(i), 128 + i);
		encodingCache.set(encoding, res);
		return res;
	}
}
const ENCODINGS = [
	[
		"utf-16be",
		"utf-16be",
		"utf-16be",
		"utf-16be",
		"utf-16be",
		"utf-16be",
		"utf-16be"
	],
	[
		"x-mac-roman",
		"shift-jis",
		"big5",
		"euc-kr",
		"iso-8859-6",
		"iso-8859-8",
		"x-mac-greek",
		"x-mac-cyrillic",
		"x-mac-symbol",
		"x-mac-devanagari",
		"x-mac-gurmukhi",
		"x-mac-gujarati",
		"Oriya",
		"Bengali",
		"Tamil",
		"Telugu",
		"Kannada",
		"Malayalam",
		"Sinhalese",
		"Burmese",
		"Khmer",
		"iso-8859-11",
		"Laotian",
		"Georgian",
		"Armenian",
		"gbk",
		"Tibetan",
		"Mongolian",
		"Geez",
		"x-mac-ce",
		"Vietnamese",
		"Sindhi"
	],
	[
		"ascii",
		null,
		"iso-8859-1"
	],
	[
		"symbol",
		"utf-16be",
		"shift-jis",
		"gb18030",
		"big5",
		"euc-kr",
		"johab",
		null,
		null,
		null,
		"utf-16be"
	]
];
const MAC_LANGUAGE_ENCODINGS = {
	15: "x-mac-icelandic",
	17: "x-mac-turkish",
	18: "x-mac-croatian",
	24: "x-mac-ce",
	25: "x-mac-ce",
	26: "x-mac-ce",
	27: "x-mac-ce",
	28: "x-mac-ce",
	30: "x-mac-icelandic",
	37: "x-mac-romanian",
	38: "x-mac-ce",
	39: "x-mac-ce",
	40: "x-mac-ce",
	143: "x-mac-inuit",
	146: "x-mac-gaelic"
};
const LANGUAGES = [
	[],
	{
		0: "en",
		30: "fo",
		60: "ks",
		90: "rw",
		1: "fr",
		31: "fa",
		61: "ku",
		91: "rn",
		2: "de",
		32: "ru",
		62: "sd",
		92: "ny",
		3: "it",
		33: "zh",
		63: "bo",
		93: "mg",
		4: "nl",
		34: "nl-BE",
		64: "ne",
		94: "eo",
		5: "sv",
		35: "ga",
		65: "sa",
		128: "cy",
		6: "es",
		36: "sq",
		66: "mr",
		129: "eu",
		7: "da",
		37: "ro",
		67: "bn",
		130: "ca",
		8: "pt",
		38: "cz",
		68: "as",
		131: "la",
		9: "no",
		39: "sk",
		69: "gu",
		132: "qu",
		10: "he",
		40: "si",
		70: "pa",
		133: "gn",
		11: "ja",
		41: "yi",
		71: "or",
		134: "ay",
		12: "ar",
		42: "sr",
		72: "ml",
		135: "tt",
		13: "fi",
		43: "mk",
		73: "kn",
		136: "ug",
		14: "el",
		44: "bg",
		74: "ta",
		137: "dz",
		15: "is",
		45: "uk",
		75: "te",
		138: "jv",
		16: "mt",
		46: "be",
		76: "si",
		139: "su",
		17: "tr",
		47: "uz",
		77: "my",
		140: "gl",
		18: "hr",
		48: "kk",
		78: "km",
		141: "af",
		19: "zh-Hant",
		49: "az-Cyrl",
		79: "lo",
		142: "br",
		20: "ur",
		50: "az-Arab",
		80: "vi",
		143: "iu",
		21: "hi",
		51: "hy",
		81: "id",
		144: "gd",
		22: "th",
		52: "ka",
		82: "tl",
		145: "gv",
		23: "ko",
		53: "mo",
		83: "ms",
		146: "ga",
		24: "lt",
		54: "ky",
		84: "ms-Arab",
		147: "to",
		25: "pl",
		55: "tg",
		85: "am",
		148: "el-polyton",
		26: "hu",
		56: "tk",
		86: "ti",
		149: "kl",
		27: "es",
		57: "mn-CN",
		87: "om",
		150: "az",
		28: "lv",
		58: "mn",
		88: "so",
		151: "nn",
		29: "se",
		59: "ps",
		89: "sw"
	},
	[],
	{
		1078: "af",
		16393: "en-IN",
		1159: "rw",
		1074: "tn",
		1052: "sq",
		6153: "en-IE",
		1089: "sw",
		1115: "si",
		1156: "gsw",
		8201: "en-JM",
		1111: "kok",
		1051: "sk",
		1118: "am",
		17417: "en-MY",
		1042: "ko",
		1060: "sl",
		5121: "ar-DZ",
		5129: "en-NZ",
		1088: "ky",
		11274: "es-AR",
		15361: "ar-BH",
		13321: "en-PH",
		1108: "lo",
		16394: "es-BO",
		3073: "ar",
		18441: "en-SG",
		1062: "lv",
		13322: "es-CL",
		2049: "ar-IQ",
		7177: "en-ZA",
		1063: "lt",
		9226: "es-CO",
		11265: "ar-JO",
		11273: "en-TT",
		2094: "dsb",
		5130: "es-CR",
		13313: "ar-KW",
		2057: "en-GB",
		1134: "lb",
		7178: "es-DO",
		12289: "ar-LB",
		1033: "en",
		1071: "mk",
		12298: "es-EC",
		4097: "ar-LY",
		12297: "en-ZW",
		2110: "ms-BN",
		17418: "es-SV",
		6145: "ary",
		1061: "et",
		1086: "ms",
		4106: "es-GT",
		8193: "ar-OM",
		1080: "fo",
		1100: "ml",
		18442: "es-HN",
		16385: "ar-QA",
		1124: "fil",
		1082: "mt",
		2058: "es-MX",
		1025: "ar-SA",
		1035: "fi",
		1153: "mi",
		19466: "es-NI",
		10241: "ar-SY",
		2060: "fr-BE",
		1146: "arn",
		6154: "es-PA",
		7169: "aeb",
		3084: "fr-CA",
		1102: "mr",
		15370: "es-PY",
		14337: "ar-AE",
		1036: "fr",
		1148: "moh",
		10250: "es-PE",
		9217: "ar-YE",
		5132: "fr-LU",
		1104: "mn",
		20490: "es-PR",
		1067: "hy",
		6156: "fr-MC",
		2128: "mn-CN",
		3082: "es",
		1101: "as",
		4108: "fr-CH",
		1121: "ne",
		1034: "es",
		2092: "az-Cyrl",
		1122: "fy",
		1044: "nb",
		21514: "es-US",
		1068: "az",
		1110: "gl",
		2068: "nn",
		14346: "es-UY",
		1133: "ba",
		1079: "ka",
		1154: "oc",
		8202: "es-VE",
		1069: "eu",
		3079: "de-AT",
		1096: "or",
		2077: "sv-FI",
		1059: "be",
		1031: "de",
		1123: "ps",
		1053: "sv",
		2117: "bn",
		5127: "de-LI",
		1045: "pl",
		1114: "syr",
		1093: "bn-IN",
		4103: "de-LU",
		1046: "pt",
		1064: "tg",
		8218: "bs-Cyrl",
		2055: "de-CH",
		2070: "pt-PT",
		2143: "tzm",
		5146: "bs",
		1032: "el",
		1094: "pa",
		1097: "ta",
		1150: "br",
		1135: "kl",
		1131: "qu-BO",
		1092: "tt",
		1026: "bg",
		1095: "gu",
		2155: "qu-EC",
		1098: "te",
		1027: "ca",
		1128: "ha",
		3179: "qu",
		1054: "th",
		3076: "zh-HK",
		1037: "he",
		1048: "ro",
		1105: "bo",
		5124: "zh-MO",
		1081: "hi",
		1047: "rm",
		1055: "tr",
		2052: "zh",
		1038: "hu",
		1049: "ru",
		1090: "tk",
		4100: "zh-SG",
		1039: "is",
		9275: "smn",
		1152: "ug",
		1028: "zh-TW",
		1136: "ig",
		4155: "smj-NO",
		1058: "uk",
		1155: "co",
		1057: "id",
		5179: "smj",
		1070: "hsb",
		1050: "hr",
		1117: "iu",
		3131: "se-FI",
		1056: "ur",
		4122: "hr-BA",
		2141: "iu-Latn",
		1083: "se",
		2115: "uz-Cyrl",
		1029: "cs",
		2108: "ga",
		2107: "se-SE",
		1091: "uz",
		1030: "da",
		1076: "xh",
		8251: "sms",
		1066: "vi",
		1164: "prs",
		1077: "zu",
		6203: "sma-NO",
		1106: "cy",
		1125: "dv",
		1040: "it",
		7227: "sms",
		1160: "wo",
		2067: "nl-BE",
		2064: "it-CH",
		1103: "sa",
		1157: "sah",
		1043: "nl",
		1041: "ja",
		7194: "sr-Cyrl-BA",
		1144: "ii",
		3081: "en-AU",
		1099: "kn",
		3098: "sr",
		1130: "yo",
		10249: "en-BZ",
		1087: "kk",
		6170: "sr-Latn-BA",
		4105: "en-CA",
		1107: "km",
		2074: "sr-Latn",
		9225: "en-029",
		1158: "quc",
		1132: "nso"
	}
];

//#endregion
//#region src/tables/name.ts
const NameRecord = new Struct({
	platformID: uint16,
	encodingID: uint16,
	languageID: uint16,
	nameID: uint16,
	length: uint16,
	string: new Pointer(uint16, new StringT("length", (t) => getEncoding(t.platformID, t.encodingID, t.languageID)), {
		type: "parent",
		relativeTo: (ctx) => ctx.parent.stringOffset,
		allowNull: false
	})
});
const LangTagRecord = new Struct({
	length: uint16,
	tag: new Pointer(uint16, new StringT("length", "utf16-be"), {
		type: "parent",
		relativeTo: (ctx) => ctx.stringOffset
	})
});
const NameTable = new VersionedStruct(uint16, {
	0: {
		count: uint16,
		stringOffset: uint16,
		records: new ArrayT(NameRecord, "count")
	},
	1: {
		count: uint16,
		stringOffset: uint16,
		records: new ArrayT(NameRecord, "count"),
		langTagCount: uint16,
		langTags: new ArrayT(LangTagRecord, "langTagCount")
	}
});
var name_default = NameTable;
const NAMES = [
	"copyright",
	"fontFamily",
	"fontSubfamily",
	"uniqueSubfamily",
	"fullName",
	"version",
	"postscriptName",
	"trademark",
	"manufacturer",
	"designer",
	"description",
	"vendorURL",
	"designerURL",
	"license",
	"licenseURL",
	null,
	"preferredFamily",
	"preferredSubfamily",
	"compatibleFull",
	"sampleText",
	"postscriptCIDFontName",
	"wwsFamilyName",
	"wwsSubfamilyName"
];
NameTable.process = function(stream) {
	const records = {};
	for (const record of this.records) {
		let language = LANGUAGES[record.platformID][record.languageID];
		if (language == null && this.langTags != null && record.languageID >= 32768) language = this.langTags[record.languageID - 32768].tag;
		if (language == null) language = record.platformID + "-" + record.languageID;
		const key = record.nameID >= 256 ? "fontFeatures" : NAMES[record.nameID] || record.nameID;
		if (records[key] == null) records[key] = {};
		let obj = records[key];
		if (record.nameID >= 256) obj = obj[record.nameID] || (obj[record.nameID] = {});
		if (typeof record.string === "string" || typeof obj[language] !== "string") obj[language] = record.string;
	}
	this.records = records;
};

//#endregion
//#region src/tables/OS2.ts
const version1 = {
	typoAscender: int16,
	typoDescender: int16,
	typoLineGap: int16,
	winAscent: uint16,
	winDescent: uint16,
	codePageRange: new ArrayT(uint32, 2)
};
const version2 = {
	...version1,
	xHeight: int16,
	capHeight: int16,
	defaultChar: uint16,
	breakChar: uint16,
	maxContent: uint16
};
const OS2 = new VersionedStruct(uint16, {
	header: {
		xAvgCharWidth: int16,
		usWeightClass: uint16,
		usWidthClass: uint16,
		fsType: new Bitfield(uint16, [
			null,
			"noEmbedding",
			"viewOnly",
			"editable",
			null,
			null,
			null,
			null,
			"noSubsetting",
			"bitmapOnly"
		]),
		ySubscriptXSize: int16,
		ySubscriptYSize: int16,
		ySubscriptXOffset: int16,
		ySubscriptYOffset: int16,
		ySuperscriptXSize: int16,
		ySuperscriptYSize: int16,
		ySuperscriptXOffset: int16,
		ySuperscriptYOffset: int16,
		yStrikeoutSize: int16,
		yStrikeoutPosition: int16,
		sFamilyClass: int16,
		panose: new ArrayT(uint8, 10),
		ulCharRange: new ArrayT(uint32, 4),
		vendorID: new StringT(4),
		fsSelection: new Bitfield(uint16, [
			"italic",
			"underscore",
			"negative",
			"outlined",
			"strikeout",
			"bold",
			"regular",
			"useTypoMetrics",
			"wws",
			"oblique"
		]),
		usFirstCharIndex: uint16,
		usLastCharIndex: uint16
	},
	0: {},
	1: version1,
	2: version2,
	3: version2,
	4: version2,
	5: {
		...version2,
		usLowerOpticalPointSize: uint16,
		usUpperOpticalPointSize: uint16
	}
});
var OS2_default = OS2;

//#endregion
//#region src/tables/post.ts
var post_default = new VersionedStruct(fixed32, {
	header: {
		italicAngle: fixed32,
		underlinePosition: int16,
		underlineThickness: int16,
		isFixedPitch: uint32,
		minMemType42: uint32,
		maxMemType42: uint32,
		minMemType1: uint32,
		maxMemType1: uint32
	},
	1: {},
	2: {
		numberOfGlyphs: uint16,
		glyphNameIndex: new ArrayT(uint16, "numberOfGlyphs"),
		names: new ArrayT(new StringT(uint8))
	},
	2.5: {
		numberOfGlyphs: uint16,
		offsets: new ArrayT(uint8, "numberOfGlyphs")
	},
	3: {},
	4: { map: new ArrayT(uint32, (t) => t.parent.maxp.numGlyphs) }
});

//#endregion
//#region src/tables/loca.ts
const loca = new VersionedStruct("head.indexToLocFormat", {
	0: { offsets: new ArrayT(uint16) },
	1: { offsets: new ArrayT(uint32) }
});
loca.process = function() {
	if (this.version === 0 && !this._processed) {
		for (let i = 0; i < this.offsets.length; i++) this.offsets[i] <<= 1;
		this._processed = true;
	}
};
var loca_default = loca;

//#endregion
//#region src/tables/glyf.ts
var glyf_default = new ArrayT(new BufferT());

//#endregion
//#region src/cff/CFFOperand.ts
const FLOAT_EOF = 15;
const FLOAT_LOOKUP = [
	"0",
	"1",
	"2",
	"3",
	"4",
	"5",
	"6",
	"7",
	"8",
	"9",
	".",
	"E",
	"E-",
	null,
	"-"
];
var CFFOperand = class {
	static decode(stream, value) {
		if (32 <= value && value <= 246) return value - 139;
		if (247 <= value && value <= 250) return (value - 247) * 256 + stream.readUInt8() + 108;
		if (251 <= value && value <= 254) return -(value - 251) * 256 - stream.readUInt8() - 108;
		if (value === 28) return stream.readInt16BE();
		if (value === 29) return stream.readInt32BE();
		if (value === 30) {
			let str = "";
			while (true) {
				const b = stream.readUInt8();
				const n1 = b >> 4;
				if (n1 === FLOAT_EOF) break;
				str += FLOAT_LOOKUP[n1];
				const n2 = b & 15;
				if (n2 === FLOAT_EOF) break;
				str += FLOAT_LOOKUP[n2];
			}
			return parseFloat(str);
		}
		return null;
	}
};

//#endregion
//#region src/cff/CFFDict.ts
var CFFDict = class {
	constructor(ops = []) {
		this.ops = ops;
		this.fields = Object.fromEntries(ops.map((field) => {
			return [Array.isArray(field[0]) ? field[0][0] << 8 | field[0][1] : field[0], field];
		}));
	}
	decodeOperands(type, stream, ret, operands) {
		if (Array.isArray(type)) return operands.map((op, i) => this.decodeOperands(type[i], stream, ret, [op]));
		else if (type.decode != null) return type.decode(stream, ret, operands);
		else switch (type) {
			case "number":
			case "offset":
			case "sid": return operands[0];
			case "boolean": return !!operands[0];
			default: return operands;
		}
	}
	decode(stream, parent) {
		const end = stream.pos + parent.length;
		const ret = {};
		let operands = [];
		Object.defineProperties(ret, {
			parent: { value: parent },
			_startOffset: { value: stream.pos }
		});
		for (const key in this.fields) {
			const field = this.fields[key];
			ret[field[1]] = field[3];
		}
		while (stream.pos < end) {
			let b = stream.readUInt8();
			if (b < 28) {
				if (b === 12) b = b << 8 | stream.readUInt8();
				const field = this.fields[b];
				if (!field) throw new Error(`Unknown operator ${b}`);
				const val = this.decodeOperands(field[2], stream, ret, operands);
				if (val != null) if (val instanceof PropertyDescriptor) Object.defineProperty(ret, field[1], val);
				else ret[field[1]] = val;
				operands = [];
			} else operands.push(CFFOperand.decode(stream, b));
		}
		return ret;
	}
};

//#endregion
//#region src/cff/CFFIndex.ts
var CFFIndex = class {
	constructor(type) {
		this.type = type;
	}
	getCFFVersion(ctx) {
		while (ctx && !ctx.hdrSize) ctx = ctx.parent;
		return ctx?.version ?? -1;
	}
	decode(stream, parent) {
		const count = this.getCFFVersion(parent) >= 2 ? stream.readUInt32BE() : stream.readUInt16BE();
		if (count === 0) return [];
		const offSize = stream.readUInt8();
		let offsetType;
		if (offSize === 1) offsetType = uint8;
		else if (offSize === 2) offsetType = uint16;
		else if (offSize === 3) offsetType = uint24;
		else if (offSize === 4) offsetType = uint32;
		else throw new Error(`Bad offset size in CFFIndex: ${offSize} ${stream.pos}`);
		const ret = [];
		const startPos = stream.pos + (count + 1) * offSize - 1;
		let start = offsetType.decode(stream);
		for (let i = 0; i < count; i++) {
			const end = offsetType.decode(stream);
			if (this.type != null) {
				const { pos } = stream;
				stream.pos = startPos + start;
				parent.length = end - start;
				ret.push(this.type.decode(stream, parent));
				stream.pos = pos;
			} else ret.push({
				offset: startPos + start,
				length: end - start
			});
			start = end;
		}
		stream.pos = startPos + start;
		return ret;
	}
};

//#endregion
//#region src/cff/CFFPointer.ts
var CFFPointer = class extends Pointer {
	constructor(type, options = {}) {
		if (options.type == null) options.type = "global";
		super(null, type, options);
	}
	decode(stream, parent, operands) {
		this.offsetType = { decode: () => operands[0] };
		return super.decode(stream, parent);
	}
};

//#endregion
//#region src/cff/CFFPrivateDict.ts
var CFFBlendOp = class {
	static decode(_stream, _parent, operands) {
		let numBlends = operands.pop();
		while (operands.length > numBlends) operands.pop();
	}
};
var CFFPrivateDict_default = new CFFDict([
	[
		6,
		"BlueValues",
		"delta",
		null
	],
	[
		7,
		"OtherBlues",
		"delta",
		null
	],
	[
		8,
		"FamilyBlues",
		"delta",
		null
	],
	[
		9,
		"FamilyOtherBlues",
		"delta",
		null
	],
	[
		[12, 9],
		"BlueScale",
		"number",
		.039625
	],
	[
		[12, 10],
		"BlueShift",
		"number",
		7
	],
	[
		[12, 11],
		"BlueFuzz",
		"number",
		1
	],
	[
		10,
		"StdHW",
		"number",
		null
	],
	[
		11,
		"StdVW",
		"number",
		null
	],
	[
		[12, 12],
		"StemSnapH",
		"delta",
		null
	],
	[
		[12, 13],
		"StemSnapV",
		"delta",
		null
	],
	[
		[12, 14],
		"ForceBold",
		"boolean",
		false
	],
	[
		[12, 17],
		"LanguageGroup",
		"number",
		0
	],
	[
		[12, 18],
		"ExpansionFactor",
		"number",
		.06
	],
	[
		[12, 19],
		"initialRandomSeed",
		"number",
		0
	],
	[
		20,
		"defaultWidthX",
		"number",
		0
	],
	[
		21,
		"nominalWidthX",
		"number",
		0
	],
	[
		22,
		"vsindex",
		"number",
		0
	],
	[
		23,
		"blend",
		CFFBlendOp,
		null
	],
	[
		19,
		"Subrs",
		new CFFPointer(new CFFIndex(), { type: "local" }),
		null
	]
]);

//#endregion
//#region src/cff/CFFEncodings.ts
const StandardEncoding = [
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"space",
	"exclam",
	"quotedbl",
	"numbersign",
	"dollar",
	"percent",
	"ampersand",
	"quoteright",
	"parenleft",
	"parenright",
	"asterisk",
	"plus",
	"comma",
	"hyphen",
	"period",
	"slash",
	"zero",
	"one",
	"two",
	"three",
	"four",
	"five",
	"six",
	"seven",
	"eight",
	"nine",
	"colon",
	"semicolon",
	"less",
	"equal",
	"greater",
	"question",
	"at",
	"A",
	"B",
	"C",
	"D",
	"E",
	"F",
	"G",
	"H",
	"I",
	"J",
	"K",
	"L",
	"M",
	"N",
	"O",
	"P",
	"Q",
	"R",
	"S",
	"T",
	"U",
	"V",
	"W",
	"X",
	"Y",
	"Z",
	"bracketleft",
	"backslash",
	"bracketright",
	"asciicircum",
	"underscore",
	"quoteleft",
	"a",
	"b",
	"c",
	"d",
	"e",
	"f",
	"g",
	"h",
	"i",
	"j",
	"k",
	"l",
	"m",
	"n",
	"o",
	"p",
	"q",
	"r",
	"s",
	"t",
	"u",
	"v",
	"w",
	"x",
	"y",
	"z",
	"braceleft",
	"bar",
	"braceright",
	"asciitilde",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"exclamdown",
	"cent",
	"sterling",
	"fraction",
	"yen",
	"florin",
	"section",
	"currency",
	"quotesingle",
	"quotedblleft",
	"guillemotleft",
	"guilsinglleft",
	"guilsinglright",
	"fi",
	"fl",
	"",
	"endash",
	"dagger",
	"daggerdbl",
	"periodcentered",
	"",
	"paragraph",
	"bullet",
	"quotesinglbase",
	"quotedblbase",
	"quotedblright",
	"guillemotright",
	"ellipsis",
	"perthousand",
	"",
	"questiondown",
	"",
	"grave",
	"acute",
	"circumflex",
	"tilde",
	"macron",
	"breve",
	"dotaccent",
	"dieresis",
	"",
	"ring",
	"cedilla",
	"",
	"hungarumlaut",
	"ogonek",
	"caron",
	"emdash",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"AE",
	"",
	"ordfeminine",
	"",
	"",
	"",
	"",
	"Lslash",
	"Oslash",
	"OE",
	"ordmasculine",
	"",
	"",
	"",
	"",
	"",
	"ae",
	"",
	"",
	"",
	"dotlessi",
	"",
	"",
	"lslash",
	"oslash",
	"oe",
	"germandbls"
];
const ExpertEncoding = [
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"space",
	"exclamsmall",
	"Hungarumlautsmall",
	"",
	"dollaroldstyle",
	"dollarsuperior",
	"ampersandsmall",
	"Acutesmall",
	"parenleftsuperior",
	"parenrightsuperior",
	"twodotenleader",
	"onedotenleader",
	"comma",
	"hyphen",
	"period",
	"fraction",
	"zerooldstyle",
	"oneoldstyle",
	"twooldstyle",
	"threeoldstyle",
	"fouroldstyle",
	"fiveoldstyle",
	"sixoldstyle",
	"sevenoldstyle",
	"eightoldstyle",
	"nineoldstyle",
	"colon",
	"semicolon",
	"commasuperior",
	"threequartersemdash",
	"periodsuperior",
	"questionsmall",
	"",
	"asuperior",
	"bsuperior",
	"centsuperior",
	"dsuperior",
	"esuperior",
	"",
	"",
	"isuperior",
	"",
	"",
	"lsuperior",
	"msuperior",
	"nsuperior",
	"osuperior",
	"",
	"",
	"rsuperior",
	"ssuperior",
	"tsuperior",
	"",
	"ff",
	"fi",
	"fl",
	"ffi",
	"ffl",
	"parenleftinferior",
	"",
	"parenrightinferior",
	"Circumflexsmall",
	"hyphensuperior",
	"Gravesmall",
	"Asmall",
	"Bsmall",
	"Csmall",
	"Dsmall",
	"Esmall",
	"Fsmall",
	"Gsmall",
	"Hsmall",
	"Ismall",
	"Jsmall",
	"Ksmall",
	"Lsmall",
	"Msmall",
	"Nsmall",
	"Osmall",
	"Psmall",
	"Qsmall",
	"Rsmall",
	"Ssmall",
	"Tsmall",
	"Usmall",
	"Vsmall",
	"Wsmall",
	"Xsmall",
	"Ysmall",
	"Zsmall",
	"colonmonetary",
	"onefitted",
	"rupiah",
	"Tildesmall",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"",
	"exclamdownsmall",
	"centoldstyle",
	"Lslashsmall",
	"",
	"",
	"Scaronsmall",
	"Zcaronsmall",
	"Dieresissmall",
	"Brevesmall",
	"Caronsmall",
	"",
	"Dotaccentsmall",
	"",
	"",
	"Macronsmall",
	"",
	"",
	"figuredash",
	"hypheninferior",
	"",
	"",
	"Ogoneksmall",
	"Ringsmall",
	"Cedillasmall",
	"",
	"",
	"",
	"onequarter",
	"onehalf",
	"threequarters",
	"questiondownsmall",
	"oneeighth",
	"threeeighths",
	"fiveeighths",
	"seveneighths",
	"onethird",
	"twothirds",
	"",
	"",
	"zerosuperior",
	"onesuperior",
	"twosuperior",
	"threesuperior",
	"foursuperior",
	"fivesuperior",
	"sixsuperior",
	"sevensuperior",
	"eightsuperior",
	"ninesuperior",
	"zeroinferior",
	"oneinferior",
	"twoinferior",
	"threeinferior",
	"fourinferior",
	"fiveinferior",
	"sixinferior",
	"seveninferior",
	"eightinferior",
	"nineinferior",
	"centinferior",
	"dollarinferior",
	"periodinferior",
	"commainferior",
	"Agravesmall",
	"Aacutesmall",
	"Acircumflexsmall",
	"Atildesmall",
	"Adieresissmall",
	"Aringsmall",
	"AEsmall",
	"Ccedillasmall",
	"Egravesmall",
	"Eacutesmall",
	"Ecircumflexsmall",
	"Edieresissmall",
	"Igravesmall",
	"Iacutesmall",
	"Icircumflexsmall",
	"Idieresissmall",
	"Ethsmall",
	"Ntildesmall",
	"Ogravesmall",
	"Oacutesmall",
	"Ocircumflexsmall",
	"Otildesmall",
	"Odieresissmall",
	"OEsmall",
	"Oslashsmall",
	"Ugravesmall",
	"Uacutesmall",
	"Ucircumflexsmall",
	"Udieresissmall",
	"Yacutesmall",
	"Thornsmall",
	"Ydieresissmall"
];

//#endregion
//#region src/cff/CFFCharsets.ts
const ISOAdobeCharset = [
	".notdef",
	"space",
	"exclam",
	"quotedbl",
	"numbersign",
	"dollar",
	"percent",
	"ampersand",
	"quoteright",
	"parenleft",
	"parenright",
	"asterisk",
	"plus",
	"comma",
	"hyphen",
	"period",
	"slash",
	"zero",
	"one",
	"two",
	"three",
	"four",
	"five",
	"six",
	"seven",
	"eight",
	"nine",
	"colon",
	"semicolon",
	"less",
	"equal",
	"greater",
	"question",
	"at",
	"A",
	"B",
	"C",
	"D",
	"E",
	"F",
	"G",
	"H",
	"I",
	"J",
	"K",
	"L",
	"M",
	"N",
	"O",
	"P",
	"Q",
	"R",
	"S",
	"T",
	"U",
	"V",
	"W",
	"X",
	"Y",
	"Z",
	"bracketleft",
	"backslash",
	"bracketright",
	"asciicircum",
	"underscore",
	"quoteleft",
	"a",
	"b",
	"c",
	"d",
	"e",
	"f",
	"g",
	"h",
	"i",
	"j",
	"k",
	"l",
	"m",
	"n",
	"o",
	"p",
	"q",
	"r",
	"s",
	"t",
	"u",
	"v",
	"w",
	"x",
	"y",
	"z",
	"braceleft",
	"bar",
	"braceright",
	"asciitilde",
	"exclamdown",
	"cent",
	"sterling",
	"fraction",
	"yen",
	"florin",
	"section",
	"currency",
	"quotesingle",
	"quotedblleft",
	"guillemotleft",
	"guilsinglleft",
	"guilsinglright",
	"fi",
	"fl",
	"endash",
	"dagger",
	"daggerdbl",
	"periodcentered",
	"paragraph",
	"bullet",
	"quotesinglbase",
	"quotedblbase",
	"quotedblright",
	"guillemotright",
	"ellipsis",
	"perthousand",
	"questiondown",
	"grave",
	"acute",
	"circumflex",
	"tilde",
	"macron",
	"breve",
	"dotaccent",
	"dieresis",
	"ring",
	"cedilla",
	"hungarumlaut",
	"ogonek",
	"caron",
	"emdash",
	"AE",
	"ordfeminine",
	"Lslash",
	"Oslash",
	"OE",
	"ordmasculine",
	"ae",
	"dotlessi",
	"lslash",
	"oslash",
	"oe",
	"germandbls",
	"onesuperior",
	"logicalnot",
	"mu",
	"trademark",
	"Eth",
	"onehalf",
	"plusminus",
	"Thorn",
	"onequarter",
	"divide",
	"brokenbar",
	"degree",
	"thorn",
	"threequarters",
	"twosuperior",
	"registered",
	"minus",
	"eth",
	"multiply",
	"threesuperior",
	"copyright",
	"Aacute",
	"Acircumflex",
	"Adieresis",
	"Agrave",
	"Aring",
	"Atilde",
	"Ccedilla",
	"Eacute",
	"Ecircumflex",
	"Edieresis",
	"Egrave",
	"Iacute",
	"Icircumflex",
	"Idieresis",
	"Igrave",
	"Ntilde",
	"Oacute",
	"Ocircumflex",
	"Odieresis",
	"Ograve",
	"Otilde",
	"Scaron",
	"Uacute",
	"Ucircumflex",
	"Udieresis",
	"Ugrave",
	"Yacute",
	"Ydieresis",
	"Zcaron",
	"aacute",
	"acircumflex",
	"adieresis",
	"agrave",
	"aring",
	"atilde",
	"ccedilla",
	"eacute",
	"ecircumflex",
	"edieresis",
	"egrave",
	"iacute",
	"icircumflex",
	"idieresis",
	"igrave",
	"ntilde",
	"oacute",
	"ocircumflex",
	"odieresis",
	"ograve",
	"otilde",
	"scaron",
	"uacute",
	"ucircumflex",
	"udieresis",
	"ugrave",
	"yacute",
	"ydieresis",
	"zcaron"
];
const ExpertCharset = [
	".notdef",
	"space",
	"exclamsmall",
	"Hungarumlautsmall",
	"dollaroldstyle",
	"dollarsuperior",
	"ampersandsmall",
	"Acutesmall",
	"parenleftsuperior",
	"parenrightsuperior",
	"twodotenleader",
	"onedotenleader",
	"comma",
	"hyphen",
	"period",
	"fraction",
	"zerooldstyle",
	"oneoldstyle",
	"twooldstyle",
	"threeoldstyle",
	"fouroldstyle",
	"fiveoldstyle",
	"sixoldstyle",
	"sevenoldstyle",
	"eightoldstyle",
	"nineoldstyle",
	"colon",
	"semicolon",
	"commasuperior",
	"threequartersemdash",
	"periodsuperior",
	"questionsmall",
	"asuperior",
	"bsuperior",
	"centsuperior",
	"dsuperior",
	"esuperior",
	"isuperior",
	"lsuperior",
	"msuperior",
	"nsuperior",
	"osuperior",
	"rsuperior",
	"ssuperior",
	"tsuperior",
	"ff",
	"fi",
	"fl",
	"ffi",
	"ffl",
	"parenleftinferior",
	"parenrightinferior",
	"Circumflexsmall",
	"hyphensuperior",
	"Gravesmall",
	"Asmall",
	"Bsmall",
	"Csmall",
	"Dsmall",
	"Esmall",
	"Fsmall",
	"Gsmall",
	"Hsmall",
	"Ismall",
	"Jsmall",
	"Ksmall",
	"Lsmall",
	"Msmall",
	"Nsmall",
	"Osmall",
	"Psmall",
	"Qsmall",
	"Rsmall",
	"Ssmall",
	"Tsmall",
	"Usmall",
	"Vsmall",
	"Wsmall",
	"Xsmall",
	"Ysmall",
	"Zsmall",
	"colonmonetary",
	"onefitted",
	"rupiah",
	"Tildesmall",
	"exclamdownsmall",
	"centoldstyle",
	"Lslashsmall",
	"Scaronsmall",
	"Zcaronsmall",
	"Dieresissmall",
	"Brevesmall",
	"Caronsmall",
	"Dotaccentsmall",
	"Macronsmall",
	"figuredash",
	"hypheninferior",
	"Ogoneksmall",
	"Ringsmall",
	"Cedillasmall",
	"onequarter",
	"onehalf",
	"threequarters",
	"questiondownsmall",
	"oneeighth",
	"threeeighths",
	"fiveeighths",
	"seveneighths",
	"onethird",
	"twothirds",
	"zerosuperior",
	"onesuperior",
	"twosuperior",
	"threesuperior",
	"foursuperior",
	"fivesuperior",
	"sixsuperior",
	"sevensuperior",
	"eightsuperior",
	"ninesuperior",
	"zeroinferior",
	"oneinferior",
	"twoinferior",
	"threeinferior",
	"fourinferior",
	"fiveinferior",
	"sixinferior",
	"seveninferior",
	"eightinferior",
	"nineinferior",
	"centinferior",
	"dollarinferior",
	"periodinferior",
	"commainferior",
	"Agravesmall",
	"Aacutesmall",
	"Acircumflexsmall",
	"Atildesmall",
	"Adieresissmall",
	"Aringsmall",
	"AEsmall",
	"Ccedillasmall",
	"Egravesmall",
	"Eacutesmall",
	"Ecircumflexsmall",
	"Edieresissmall",
	"Igravesmall",
	"Iacutesmall",
	"Icircumflexsmall",
	"Idieresissmall",
	"Ethsmall",
	"Ntildesmall",
	"Ogravesmall",
	"Oacutesmall",
	"Ocircumflexsmall",
	"Otildesmall",
	"Odieresissmall",
	"OEsmall",
	"Oslashsmall",
	"Ugravesmall",
	"Uacutesmall",
	"Ucircumflexsmall",
	"Udieresissmall",
	"Yacutesmall",
	"Thornsmall",
	"Ydieresissmall"
];
const ExpertSubsetCharset = [
	".notdef",
	"space",
	"dollaroldstyle",
	"dollarsuperior",
	"parenleftsuperior",
	"parenrightsuperior",
	"twodotenleader",
	"onedotenleader",
	"comma",
	"hyphen",
	"period",
	"fraction",
	"zerooldstyle",
	"oneoldstyle",
	"twooldstyle",
	"threeoldstyle",
	"fouroldstyle",
	"fiveoldstyle",
	"sixoldstyle",
	"sevenoldstyle",
	"eightoldstyle",
	"nineoldstyle",
	"colon",
	"semicolon",
	"commasuperior",
	"threequartersemdash",
	"periodsuperior",
	"asuperior",
	"bsuperior",
	"centsuperior",
	"dsuperior",
	"esuperior",
	"isuperior",
	"lsuperior",
	"msuperior",
	"nsuperior",
	"osuperior",
	"rsuperior",
	"ssuperior",
	"tsuperior",
	"ff",
	"fi",
	"fl",
	"ffi",
	"ffl",
	"parenleftinferior",
	"parenrightinferior",
	"hyphensuperior",
	"colonmonetary",
	"onefitted",
	"rupiah",
	"centoldstyle",
	"figuredash",
	"hypheninferior",
	"onequarter",
	"onehalf",
	"threequarters",
	"oneeighth",
	"threeeighths",
	"fiveeighths",
	"seveneighths",
	"onethird",
	"twothirds",
	"zerosuperior",
	"onesuperior",
	"twosuperior",
	"threesuperior",
	"foursuperior",
	"fivesuperior",
	"sixsuperior",
	"sevensuperior",
	"eightsuperior",
	"ninesuperior",
	"zeroinferior",
	"oneinferior",
	"twoinferior",
	"threeinferior",
	"fourinferior",
	"fiveinferior",
	"sixinferior",
	"seveninferior",
	"eightinferior",
	"nineinferior",
	"centinferior",
	"dollarinferior",
	"periodinferior",
	"commainferior"
];

//#endregion
//#region src/tables/opentype.ts
const LangSysTable = new Struct({
	reserved: new Reserved(uint16),
	reqFeatureIndex: uint16,
	featureCount: uint16,
	featureIndexes: new ArrayT(uint16, "featureCount")
});
const LangSysRecord = new Struct({
	tag: new StringT(4),
	langSys: new Pointer(uint16, LangSysTable, { type: "parent" })
});
const Script = new Struct({
	defaultLangSys: new Pointer(uint16, LangSysTable),
	count: uint16,
	langSysRecords: new ArrayT(LangSysRecord, "count")
});
const ScriptRecord = new Struct({
	tag: new StringT(4),
	script: new Pointer(uint16, Script, { type: "parent" })
});
const ScriptList = new ArrayT(ScriptRecord, uint16);
const FeatureParams = new Struct({
	version: uint16,
	nameID: uint16
});
const Feature = new Struct({
	featureParams: new Pointer(uint16, FeatureParams),
	lookupCount: uint16,
	lookupListIndexes: new ArrayT(uint16, "lookupCount")
});
const FeatureRecord = new Struct({
	tag: new StringT(4),
	feature: new Pointer(uint16, Feature, { type: "parent" })
});
const FeatureList = new ArrayT(FeatureRecord, uint16);
const LookupFlags = new Struct({
	markAttachmentType: uint8,
	flags: new Bitfield(uint8, [
		"rightToLeft",
		"ignoreBaseGlyphs",
		"ignoreLigatures",
		"ignoreMarks",
		"useMarkFilteringSet"
	])
});
function LookupList(SubTable) {
	const Lookup = new Struct({
		lookupType: uint16,
		flags: LookupFlags,
		subTableCount: uint16,
		subTables: new ArrayT(new Pointer(uint16, SubTable), "subTableCount"),
		markFilteringSet: new Optional(uint16, (t) => t.flags.flags.useMarkFilteringSet)
	});
	return new LazyArray(new Pointer(uint16, Lookup), uint16);
}
const RangeRecord = new Struct({
	start: uint16,
	end: uint16,
	startCoverageIndex: uint16
});
const Coverage = new VersionedStruct(uint16, {
	1: {
		glyphCount: uint16,
		glyphs: new ArrayT(uint16, "glyphCount")
	},
	2: {
		rangeCount: uint16,
		rangeRecords: new ArrayT(RangeRecord, "rangeCount")
	}
});
const ClassRangeRecord = new Struct({
	start: uint16,
	end: uint16,
	class: uint16
});
const ClassDef = new VersionedStruct(uint16, {
	1: {
		startGlyph: uint16,
		glyphCount: uint16,
		classValueArray: new ArrayT(uint16, "glyphCount")
	},
	2: {
		classRangeCount: uint16,
		classRangeRecord: new ArrayT(ClassRangeRecord, "classRangeCount")
	}
});
const LookupRecord = new Struct({
	sequenceIndex: uint16,
	lookupListIndex: uint16
});
const Rule = new Struct({
	glyphCount: uint16,
	lookupCount: uint16,
	input: new ArrayT(uint16, (t) => t.glyphCount - 1),
	lookupRecords: new ArrayT(LookupRecord, "lookupCount")
});
const RuleSet = new ArrayT(new Pointer(uint16, Rule), uint16);
const ClassRule = new Struct({
	glyphCount: uint16,
	lookupCount: uint16,
	classes: new ArrayT(uint16, (t) => t.glyphCount - 1),
	lookupRecords: new ArrayT(LookupRecord, "lookupCount")
});
const ClassSet = new ArrayT(new Pointer(uint16, ClassRule), uint16);
const Context = new VersionedStruct(uint16, {
	1: {
		coverage: new Pointer(uint16, Coverage),
		ruleSetCount: uint16,
		ruleSets: new ArrayT(new Pointer(uint16, RuleSet), "ruleSetCount")
	},
	2: {
		coverage: new Pointer(uint16, Coverage),
		classDef: new Pointer(uint16, ClassDef),
		classSetCnt: uint16,
		classSet: new ArrayT(new Pointer(uint16, ClassSet), "classSetCnt")
	},
	3: {
		glyphCount: uint16,
		lookupCount: uint16,
		coverages: new ArrayT(new Pointer(uint16, Coverage), "glyphCount"),
		lookupRecords: new ArrayT(LookupRecord, "lookupCount")
	}
});
const ChainRule = new Struct({
	backtrackGlyphCount: uint16,
	backtrack: new ArrayT(uint16, "backtrackGlyphCount"),
	inputGlyphCount: uint16,
	input: new ArrayT(uint16, (t) => t.inputGlyphCount - 1),
	lookaheadGlyphCount: uint16,
	lookahead: new ArrayT(uint16, "lookaheadGlyphCount"),
	lookupCount: uint16,
	lookupRecords: new ArrayT(LookupRecord, "lookupCount")
});
const ChainRuleSet = new ArrayT(new Pointer(uint16, ChainRule), uint16);
const ChainingContext = new VersionedStruct(uint16, {
	1: {
		coverage: new Pointer(uint16, Coverage),
		chainCount: uint16,
		chainRuleSets: new ArrayT(new Pointer(uint16, ChainRuleSet), "chainCount")
	},
	2: {
		coverage: new Pointer(uint16, Coverage),
		backtrackClassDef: new Pointer(uint16, ClassDef),
		inputClassDef: new Pointer(uint16, ClassDef),
		lookaheadClassDef: new Pointer(uint16, ClassDef),
		chainCount: uint16,
		chainClassSet: new ArrayT(new Pointer(uint16, ChainRuleSet), "chainCount")
	},
	3: {
		backtrackGlyphCount: uint16,
		backtrackCoverage: new ArrayT(new Pointer(uint16, Coverage), "backtrackGlyphCount"),
		inputGlyphCount: uint16,
		inputCoverage: new ArrayT(new Pointer(uint16, Coverage), "inputGlyphCount"),
		lookaheadGlyphCount: uint16,
		lookaheadCoverage: new ArrayT(new Pointer(uint16, Coverage), "lookaheadGlyphCount"),
		lookupCount: uint16,
		lookupRecords: new ArrayT(LookupRecord, "lookupCount")
	}
});

//#endregion
//#region src/tables/variations.ts
/*******************
* Variation Store *
*******************/
const F2DOT14 = new Fixed(16, 14);
const RegionAxisCoordinates = new Struct({
	startCoord: F2DOT14,
	peakCoord: F2DOT14,
	endCoord: F2DOT14
});
const VariationRegionList = new Struct({
	axisCount: uint16,
	regionCount: uint16,
	variationRegions: new ArrayT(new ArrayT(RegionAxisCoordinates, "axisCount"), "regionCount")
});
const DeltaSet = new Struct({
	shortDeltas: new ArrayT(int16, (t) => t.parent.shortDeltaCount),
	regionDeltas: new ArrayT(int8, (t) => t.parent.regionIndexCount - t.parent.shortDeltaCount),
	deltas: (t) => t.shortDeltas.concat(t.regionDeltas)
});
const ItemVariationData = new Struct({
	itemCount: uint16,
	shortDeltaCount: uint16,
	regionIndexCount: uint16,
	regionIndexes: new ArrayT(uint16, "regionIndexCount"),
	deltaSets: new ArrayT(DeltaSet, "itemCount")
});
const ItemVariationStore = new Struct({
	format: uint16,
	variationRegionList: new Pointer(uint32, VariationRegionList),
	variationDataCount: uint16,
	itemVariationData: new ArrayT(new Pointer(uint32, ItemVariationData), "variationDataCount")
});
/**********************
* Feature Variations *
**********************/
const ConditionTable = new VersionedStruct(uint16, { 1: {
	axisIndex: uint16,
	axisIndex: uint16,
	filterRangeMinValue: F2DOT14,
	filterRangeMaxValue: F2DOT14
} });
const ConditionSet = new Struct({
	conditionCount: uint16,
	conditionTable: new ArrayT(new Pointer(uint32, ConditionTable), "conditionCount")
});
const FeatureTableSubstitutionRecord = new Struct({
	featureIndex: uint16,
	alternateFeatureTable: new Pointer(uint32, Feature, { type: "parent" })
});
const FeatureTableSubstitution = new Struct({
	version: fixed32,
	substitutionCount: uint16,
	substitutions: new ArrayT(FeatureTableSubstitutionRecord, "substitutionCount")
});
const FeatureVariationRecord = new Struct({
	conditionSet: new Pointer(uint32, ConditionSet, { type: "parent" }),
	featureTableSubstitution: new Pointer(uint32, FeatureTableSubstitution, { type: "parent" })
});
const FeatureVariations = new Struct({
	majorVersion: uint16,
	minorVersion: uint16,
	featureVariationRecordCount: uint32,
	featureVariationRecords: new ArrayT(FeatureVariationRecord, "featureVariationRecordCount")
});

//#endregion
//#region src/cff/CFFTop.ts
var PredefinedOp = class {
	constructor(predefinedOps, type) {
		this.predefinedOps = predefinedOps;
		this.type = type;
	}
	decode(stream, parent, operands) {
		if (this.predefinedOps[operands[0]]) return this.predefinedOps[operands[0]];
		return this.type.decode(stream, parent, operands);
	}
};
var CFFEncodingVersion = class extends NumberT {
	constructor() {
		super("UInt8");
	}
	decode(stream) {
		return uint8.decode(stream) & 127;
	}
};
const Range1 = new Struct({
	first: uint16,
	nLeft: uint8
});
const Range2 = new Struct({
	first: uint16,
	nLeft: uint16
});
const CFFCustomEncoding = new VersionedStruct(new CFFEncodingVersion(), {
	0: {
		nCodes: uint8,
		codes: new ArrayT(uint8, "nCodes")
	},
	1: {
		nRanges: uint8,
		ranges: new ArrayT(Range1, "nRanges")
	}
});
const CFFEncoding = new PredefinedOp([StandardEncoding, ExpertEncoding], new CFFPointer(CFFCustomEncoding, { lazy: true }));
var RangeArray = class extends ArrayT {
	decode(stream, parent) {
		const length = resolveLength(this.length, stream, parent);
		let count = 0;
		const res = [];
		while (count < length) {
			const range$1 = this.type.decode(stream, parent);
			range$1.offset = count;
			count += range$1.nLeft + 1;
			res.push(range$1);
		}
		return res;
	}
};
const getCharsetLength = (t) => t.parent.CharStrings.length - 1;
const CFFCustomCharset = new VersionedStruct(uint8, {
	0: { glyphs: new ArrayT(uint16, getCharsetLength) },
	1: { ranges: new RangeArray(Range1, getCharsetLength) },
	2: { ranges: new RangeArray(Range2, getCharsetLength) }
});
const CFFCharset = new PredefinedOp([
	ISOAdobeCharset,
	ExpertCharset,
	ExpertSubsetCharset
], new CFFPointer(CFFCustomCharset, { lazy: true }));
const FDRange3 = new Struct({
	first: uint16,
	fd: uint8
});
const FDRange4 = new Struct({
	first: uint32,
	fd: uint16
});
const FDSelect = new VersionedStruct(uint8, {
	0: { fds: new ArrayT(uint8, (t) => t.parent.CharStrings.length) },
	3: {
		nRanges: uint16,
		ranges: new ArrayT(FDRange3, "nRanges"),
		sentinel: uint16
	},
	4: {
		nRanges: uint32,
		ranges: new ArrayT(FDRange4, "nRanges"),
		sentinel: uint32
	}
});
const ptr = new CFFPointer(CFFPrivateDict_default);
var CFFPrivateOp = class {
	decode(stream, parent, operands) {
		parent.length = operands[0];
		return ptr.decode(stream, parent, [operands[1]]);
	}
};
const Private = [
	18,
	"Private",
	new CFFPrivateOp(),
	null
];
const FontName = [
	[12, 38],
	"FontName",
	"sid",
	null
];
const FontMatrix = [
	[12, 7],
	"FontMatrix",
	"array",
	[
		.001,
		0,
		0,
		.001,
		0,
		0
	]
];
const PaintType = [
	[12, 5],
	"PaintType",
	"number",
	0
];
const CharStrings = [
	17,
	"CharStrings",
	new CFFPointer(new CFFIndex()),
	null
];
const FDSelectEntry = [
	[12, 37],
	"FDSelect",
	new CFFPointer(FDSelect),
	null
];
const FDArray = [
	[12, 36],
	"FDArray",
	new CFFPointer(new CFFIndex(new CFFDict([
		Private,
		FontName,
		FontMatrix,
		PaintType
	]))),
	null
];
const CFFTopDict = new CFFDict([
	[
		[12, 30],
		"ROS",
		[
			"sid",
			"sid",
			"number"
		],
		null
	],
	[
		0,
		"version",
		"sid",
		null
	],
	[
		1,
		"Notice",
		"sid",
		null
	],
	[
		[12, 0],
		"Copyright",
		"sid",
		null
	],
	[
		2,
		"FullName",
		"sid",
		null
	],
	[
		3,
		"FamilyName",
		"sid",
		null
	],
	[
		4,
		"Weight",
		"sid",
		null
	],
	[
		[12, 1],
		"isFixedPitch",
		"boolean",
		false
	],
	[
		[12, 2],
		"ItalicAngle",
		"number",
		0
	],
	[
		[12, 3],
		"UnderlinePosition",
		"number",
		-100
	],
	[
		[12, 4],
		"UnderlineThickness",
		"number",
		50
	],
	PaintType,
	[
		[12, 6],
		"CharstringType",
		"number",
		2
	],
	FontMatrix,
	[
		13,
		"UniqueID",
		"number",
		null
	],
	[
		5,
		"FontBBox",
		"array",
		[
			0,
			0,
			0,
			0
		]
	],
	[
		[12, 8],
		"StrokeWidth",
		"number",
		0
	],
	[
		14,
		"XUID",
		"array",
		null
	],
	[
		15,
		"charset",
		CFFCharset,
		ISOAdobeCharset
	],
	[
		16,
		"Encoding",
		CFFEncoding,
		StandardEncoding
	],
	CharStrings,
	Private,
	[
		[12, 20],
		"SyntheticBase",
		"number",
		null
	],
	[
		[12, 21],
		"PostScript",
		"sid",
		null
	],
	[
		[12, 22],
		"BaseFontName",
		"sid",
		null
	],
	[
		[12, 23],
		"BaseFontBlend",
		"delta",
		null
	],
	[
		[12, 31],
		"CIDFontVersion",
		"number",
		0
	],
	[
		[12, 32],
		"CIDFontRevision",
		"number",
		0
	],
	[
		[12, 33],
		"CIDFontType",
		"number",
		0
	],
	[
		[12, 34],
		"CIDCount",
		"number",
		8720
	],
	[
		[12, 35],
		"UIDBase",
		"number",
		null
	],
	FDSelectEntry,
	FDArray,
	FontName
]);
const CFF2TopDict = new CFFDict([
	FontMatrix,
	CharStrings,
	FDSelectEntry,
	FDArray,
	[
		24,
		"vstore",
		new CFFPointer(new Struct({
			length: uint16,
			itemVariationStore: ItemVariationStore
		})),
		null
	],
	[
		25,
		"maxstack",
		"number",
		193
	]
]);
const CFFTop = new VersionedStruct(fixed16, {
	1: {
		hdrSize: uint8,
		offSize: uint8,
		nameIndex: new CFFIndex(new StringT("length")),
		topDictIndex: new CFFIndex(CFFTopDict),
		stringIndex: new CFFIndex(new StringT("length")),
		globalSubrIndex: new CFFIndex()
	},
	2: {
		hdrSize: uint8,
		length: uint16,
		topDict: CFF2TopDict,
		globalSubrIndex: new CFFIndex()
	}
});
var CFFTop_default = CFFTop;

//#endregion
//#region src/cff/CFFStandardStrings.ts
var CFFStandardStrings_default = [
	".notdef",
	"space",
	"exclam",
	"quotedbl",
	"numbersign",
	"dollar",
	"percent",
	"ampersand",
	"quoteright",
	"parenleft",
	"parenright",
	"asterisk",
	"plus",
	"comma",
	"hyphen",
	"period",
	"slash",
	"zero",
	"one",
	"two",
	"three",
	"four",
	"five",
	"six",
	"seven",
	"eight",
	"nine",
	"colon",
	"semicolon",
	"less",
	"equal",
	"greater",
	"question",
	"at",
	"A",
	"B",
	"C",
	"D",
	"E",
	"F",
	"G",
	"H",
	"I",
	"J",
	"K",
	"L",
	"M",
	"N",
	"O",
	"P",
	"Q",
	"R",
	"S",
	"T",
	"U",
	"V",
	"W",
	"X",
	"Y",
	"Z",
	"bracketleft",
	"backslash",
	"bracketright",
	"asciicircum",
	"underscore",
	"quoteleft",
	"a",
	"b",
	"c",
	"d",
	"e",
	"f",
	"g",
	"h",
	"i",
	"j",
	"k",
	"l",
	"m",
	"n",
	"o",
	"p",
	"q",
	"r",
	"s",
	"t",
	"u",
	"v",
	"w",
	"x",
	"y",
	"z",
	"braceleft",
	"bar",
	"braceright",
	"asciitilde",
	"exclamdown",
	"cent",
	"sterling",
	"fraction",
	"yen",
	"florin",
	"section",
	"currency",
	"quotesingle",
	"quotedblleft",
	"guillemotleft",
	"guilsinglleft",
	"guilsinglright",
	"fi",
	"fl",
	"endash",
	"dagger",
	"daggerdbl",
	"periodcentered",
	"paragraph",
	"bullet",
	"quotesinglbase",
	"quotedblbase",
	"quotedblright",
	"guillemotright",
	"ellipsis",
	"perthousand",
	"questiondown",
	"grave",
	"acute",
	"circumflex",
	"tilde",
	"macron",
	"breve",
	"dotaccent",
	"dieresis",
	"ring",
	"cedilla",
	"hungarumlaut",
	"ogonek",
	"caron",
	"emdash",
	"AE",
	"ordfeminine",
	"Lslash",
	"Oslash",
	"OE",
	"ordmasculine",
	"ae",
	"dotlessi",
	"lslash",
	"oslash",
	"oe",
	"germandbls",
	"onesuperior",
	"logicalnot",
	"mu",
	"trademark",
	"Eth",
	"onehalf",
	"plusminus",
	"Thorn",
	"onequarter",
	"divide",
	"brokenbar",
	"degree",
	"thorn",
	"threequarters",
	"twosuperior",
	"registered",
	"minus",
	"eth",
	"multiply",
	"threesuperior",
	"copyright",
	"Aacute",
	"Acircumflex",
	"Adieresis",
	"Agrave",
	"Aring",
	"Atilde",
	"Ccedilla",
	"Eacute",
	"Ecircumflex",
	"Edieresis",
	"Egrave",
	"Iacute",
	"Icircumflex",
	"Idieresis",
	"Igrave",
	"Ntilde",
	"Oacute",
	"Ocircumflex",
	"Odieresis",
	"Ograve",
	"Otilde",
	"Scaron",
	"Uacute",
	"Ucircumflex",
	"Udieresis",
	"Ugrave",
	"Yacute",
	"Ydieresis",
	"Zcaron",
	"aacute",
	"acircumflex",
	"adieresis",
	"agrave",
	"aring",
	"atilde",
	"ccedilla",
	"eacute",
	"ecircumflex",
	"edieresis",
	"egrave",
	"iacute",
	"icircumflex",
	"idieresis",
	"igrave",
	"ntilde",
	"oacute",
	"ocircumflex",
	"odieresis",
	"ograve",
	"otilde",
	"scaron",
	"uacute",
	"ucircumflex",
	"udieresis",
	"ugrave",
	"yacute",
	"ydieresis",
	"zcaron",
	"exclamsmall",
	"Hungarumlautsmall",
	"dollaroldstyle",
	"dollarsuperior",
	"ampersandsmall",
	"Acutesmall",
	"parenleftsuperior",
	"parenrightsuperior",
	"twodotenleader",
	"onedotenleader",
	"zerooldstyle",
	"oneoldstyle",
	"twooldstyle",
	"threeoldstyle",
	"fouroldstyle",
	"fiveoldstyle",
	"sixoldstyle",
	"sevenoldstyle",
	"eightoldstyle",
	"nineoldstyle",
	"commasuperior",
	"threequartersemdash",
	"periodsuperior",
	"questionsmall",
	"asuperior",
	"bsuperior",
	"centsuperior",
	"dsuperior",
	"esuperior",
	"isuperior",
	"lsuperior",
	"msuperior",
	"nsuperior",
	"osuperior",
	"rsuperior",
	"ssuperior",
	"tsuperior",
	"ff",
	"ffi",
	"ffl",
	"parenleftinferior",
	"parenrightinferior",
	"Circumflexsmall",
	"hyphensuperior",
	"Gravesmall",
	"Asmall",
	"Bsmall",
	"Csmall",
	"Dsmall",
	"Esmall",
	"Fsmall",
	"Gsmall",
	"Hsmall",
	"Ismall",
	"Jsmall",
	"Ksmall",
	"Lsmall",
	"Msmall",
	"Nsmall",
	"Osmall",
	"Psmall",
	"Qsmall",
	"Rsmall",
	"Ssmall",
	"Tsmall",
	"Usmall",
	"Vsmall",
	"Wsmall",
	"Xsmall",
	"Ysmall",
	"Zsmall",
	"colonmonetary",
	"onefitted",
	"rupiah",
	"Tildesmall",
	"exclamdownsmall",
	"centoldstyle",
	"Lslashsmall",
	"Scaronsmall",
	"Zcaronsmall",
	"Dieresissmall",
	"Brevesmall",
	"Caronsmall",
	"Dotaccentsmall",
	"Macronsmall",
	"figuredash",
	"hypheninferior",
	"Ogoneksmall",
	"Ringsmall",
	"Cedillasmall",
	"questiondownsmall",
	"oneeighth",
	"threeeighths",
	"fiveeighths",
	"seveneighths",
	"onethird",
	"twothirds",
	"zerosuperior",
	"foursuperior",
	"fivesuperior",
	"sixsuperior",
	"sevensuperior",
	"eightsuperior",
	"ninesuperior",
	"zeroinferior",
	"oneinferior",
	"twoinferior",
	"threeinferior",
	"fourinferior",
	"fiveinferior",
	"sixinferior",
	"seveninferior",
	"eightinferior",
	"nineinferior",
	"centinferior",
	"dollarinferior",
	"periodinferior",
	"commainferior",
	"Agravesmall",
	"Aacutesmall",
	"Acircumflexsmall",
	"Atildesmall",
	"Adieresissmall",
	"Aringsmall",
	"AEsmall",
	"Ccedillasmall",
	"Egravesmall",
	"Eacutesmall",
	"Ecircumflexsmall",
	"Edieresissmall",
	"Igravesmall",
	"Iacutesmall",
	"Icircumflexsmall",
	"Idieresissmall",
	"Ethsmall",
	"Ntildesmall",
	"Ogravesmall",
	"Oacutesmall",
	"Ocircumflexsmall",
	"Otildesmall",
	"Odieresissmall",
	"OEsmall",
	"Oslashsmall",
	"Ugravesmall",
	"Uacutesmall",
	"Ucircumflexsmall",
	"Udieresissmall",
	"Yacutesmall",
	"Thornsmall",
	"Ydieresissmall",
	"001.000",
	"001.001",
	"001.002",
	"001.003",
	"Black",
	"Bold",
	"Book",
	"Light",
	"Medium",
	"Regular",
	"Roman",
	"Semibold"
];

//#endregion
//#region src/cff/CFFFont.ts
var CFFFont = class CFFFont {
	constructor(stream) {
		this.stream = stream;
		this.decode();
	}
	static decode(stream) {
		return new CFFFont(stream);
	}
	decode() {
		const top = CFFTop_default.decode(this.stream);
		for (const key in top) this[key] = top[key];
		if (this.version < 2) {
			if (this.topDictIndex.length !== 1) throw new Error("Only a single font is allowed in CFF");
			this.topDict = this.topDictIndex[0];
		}
		this.isCIDFont = this.topDict.ROS != null;
		return this;
	}
	string(sid) {
		if (this.version >= 2) return null;
		if (sid < CFFStandardStrings_default.length) return CFFStandardStrings_default[sid];
		return this.stringIndex[sid - CFFStandardStrings_default.length];
	}
	get postscriptName() {
		return this.version < 2 ? this.nameIndex[0] : null;
	}
	get fullName() {
		return this.string(this.topDict.FullName);
	}
	get familyName() {
		return this.string(this.topDict.FamilyName);
	}
	getCharString(glyph) {
		this.stream.pos = this.topDict.CharStrings[glyph].offset;
		return this.stream.readBuffer(this.topDict.CharStrings[glyph].length);
	}
	getGlyphName(gid) {
		if (this.version >= 2 || this.isCIDFont) return null;
		const { charset } = this.topDict;
		if (Array.isArray(charset)) return charset[gid];
		if (gid === 0) return ".notdef";
		gid -= 1;
		switch (charset.version) {
			case 0: return this.string(charset.glyphs[gid]);
			case 1:
			case 2:
				for (let i = 0; i < charset.ranges.length; i++) {
					let range$1 = charset.ranges[i];
					if (range$1.offset <= gid && gid <= range$1.offset + range$1.nLeft) return this.string(range$1.first + (gid - range$1.offset));
				}
				break;
		}
		return null;
	}
	fdForGlyph(gid) {
		if (!this.topDict.FDSelect) return null;
		switch (this.topDict.FDSelect.version) {
			case 0: return this.topDict.FDSelect.fds[gid];
			case 3:
			case 4:
				const { ranges } = this.topDict.FDSelect;
				let low = 0;
				let high = ranges.length - 1;
				while (low <= high) {
					const mid = low + high >> 1;
					if (gid < ranges[mid].first) high = mid - 1;
					else if (mid < high && gid >= ranges[mid + 1].first) low = mid + 1;
					else return ranges[mid].fd;
				}
			default: throw new Error(`Unknown FDSelect version: ${this.topDict.FDSelect.version}`);
		}
	}
	privateDictForGlyph(gid) {
		if (this.topDict.FDSelect) {
			const fd = this.fdForGlyph(gid);
			if (this.topDict.FDArray[fd]) return this.topDict.FDArray[fd].Private;
			return null;
		}
		if (this.version < 2) return this.topDict.Private;
		return this.topDict.FDArray[0].Private;
	}
};
var CFFFont_default = CFFFont;

//#endregion
//#region src/tables/sbix.ts
const ImageTable = new Struct({
	ppem: uint16,
	resolution: uint16,
	imageOffsets: new ArrayT(new Pointer(uint32, "void"), (t) => t.parent.parent.maxp.numGlyphs + 1)
});
var sbix_default = new Struct({
	version: uint16,
	flags: new Bitfield(uint16, ["renderOutlines"]),
	numImgTables: uint32,
	imageTables: new ArrayT(new Pointer(uint32, ImageTable), "numImgTables")
});

//#endregion
//#region src/tables/COLR.ts
let LayerRecord = new Struct({
	gid: uint16,
	paletteIndex: uint16
});
let BaseGlyphRecord = new Struct({
	gid: uint16,
	firstLayerIndex: uint16,
	numLayers: uint16
});
var COLR_default = new Struct({
	version: uint16,
	numBaseGlyphRecords: uint16,
	baseGlyphRecord: new Pointer(uint32, new ArrayT(BaseGlyphRecord, "numBaseGlyphRecords")),
	layerRecords: new Pointer(uint32, new ArrayT(LayerRecord, "numLayerRecords"), { lazy: true }),
	numLayerRecords: uint16
});

//#endregion
//#region src/tables/CPAL.ts
const ColorRecord = new Struct({
	blue: uint8,
	green: uint8,
	red: uint8,
	alpha: uint8
});
var CPAL_default = new VersionedStruct(uint16, {
	header: {
		numPaletteEntries: uint16,
		numPalettes: uint16,
		numColorRecords: uint16,
		colorRecords: new Pointer(uint32, new ArrayT(ColorRecord, "numColorRecords")),
		colorRecordIndices: new ArrayT(uint16, "numPalettes")
	},
	0: {},
	1: {
		offsetPaletteTypeArray: new Pointer(uint32, new ArrayT(uint32, "numPalettes")),
		offsetPaletteLabelArray: new Pointer(uint32, new ArrayT(uint16, "numPalettes")),
		offsetPaletteEntryLabelArray: new Pointer(uint32, new ArrayT(uint16, "numPaletteEntries"))
	}
});

//#endregion
//#region src/tables/GSUB.ts
const Sequence = new ArrayT(uint16, uint16);
const AlternateSet = Sequence;
const Ligature = new Struct({
	glyph: uint16,
	compCount: uint16,
	components: new ArrayT(uint16, (t) => t.compCount - 1)
});
const LigatureSet = new ArrayT(new Pointer(uint16, Ligature), uint16);
const GSUBLookup = new VersionedStruct("lookupType", {
	1: new VersionedStruct(uint16, {
		1: {
			coverage: new Pointer(uint16, Coverage),
			deltaGlyphID: int16
		},
		2: {
			coverage: new Pointer(uint16, Coverage),
			glyphCount: uint16,
			substitute: new LazyArray(uint16, "glyphCount")
		}
	}),
	2: {
		substFormat: uint16,
		coverage: new Pointer(uint16, Coverage),
		count: uint16,
		sequences: new LazyArray(new Pointer(uint16, Sequence), "count")
	},
	3: {
		substFormat: uint16,
		coverage: new Pointer(uint16, Coverage),
		count: uint16,
		alternateSet: new LazyArray(new Pointer(uint16, AlternateSet), "count")
	},
	4: {
		substFormat: uint16,
		coverage: new Pointer(uint16, Coverage),
		count: uint16,
		ligatureSets: new LazyArray(new Pointer(uint16, LigatureSet), "count")
	},
	5: Context,
	6: ChainingContext,
	7: {
		substFormat: uint16,
		lookupType: uint16,
		extension: new Pointer(uint32, null)
	},
	8: {
		substFormat: uint16,
		coverage: new Pointer(uint16, Coverage),
		backtrackCoverage: new ArrayT(new Pointer(uint16, Coverage), "backtrackGlyphCount"),
		lookaheadGlyphCount: uint16,
		lookaheadCoverage: new ArrayT(new Pointer(uint16, Coverage), "lookaheadGlyphCount"),
		glyphCount: uint16,
		substitutes: new ArrayT(uint16, "glyphCount")
	}
});
GSUBLookup.versions[7].extension.type = GSUBLookup;
var GSUB_default = new VersionedStruct(uint32, {
	header: {
		scriptList: new Pointer(uint16, ScriptList),
		featureList: new Pointer(uint16, FeatureList),
		lookupList: new Pointer(uint16, new LookupList(GSUBLookup))
	},
	65536: {},
	65537: { featureVariations: new Pointer(uint32, FeatureVariations) }
});

//#endregion
//#region src/tables/HVAR.ts
var VariableSizeNumber = class {
	#size;
	constructor(size) {
		this.#size = size;
	}
	decode(stream, parent) {
		switch (this.size(0, parent)) {
			case 1: return stream.readUInt8();
			case 2: return stream.readUInt16BE();
			case 3: return stream.readUInt24BE();
			case 4: return stream.readUInt32BE();
		}
	}
	size(_val, parent) {
		return resolveLength(this.#size, null, parent);
	}
};
const MapDataEntry = new Struct({
	entry: new VariableSizeNumber((t) => ((t.parent.entryFormat & 48) >> 4) + 1),
	outerIndex: (t) => t.entry >> (t.parent.entryFormat & 15) + 1,
	innerIndex: (t) => t.entry & (1 << (t.parent.entryFormat & 15) + 1) - 1
});
const DeltaSetIndexMap = new Struct({
	entryFormat: uint16,
	mapCount: uint16,
	mapData: new ArrayT(MapDataEntry, "mapCount")
});
var HVAR_default = new Struct({
	majorVersion: uint16,
	minorVersion: uint16,
	itemVariationStore: new Pointer(uint32, ItemVariationStore),
	advanceWidthMapping: new Pointer(uint32, DeltaSetIndexMap),
	LSBMapping: new Pointer(uint32, DeltaSetIndexMap),
	RSBMapping: new Pointer(uint32, DeltaSetIndexMap)
});

//#endregion
//#region src/tables/vhea.ts
var vhea_default = new Struct({
	version: uint16,
	ascent: int16,
	descent: int16,
	lineGap: int16,
	advanceHeightMax: int16,
	minTopSideBearing: int16,
	minBottomSideBearing: int16,
	yMaxExtent: int16,
	caretSlopeRise: int16,
	caretSlopeRun: int16,
	caretOffset: int16,
	reserved: new Reserved(int16, 4),
	metricDataFormat: int16,
	numberOfMetrics: uint16
});

//#endregion
//#region src/tables/vmtx.ts
const VmtxEntry = new Struct({
	advance: uint16,
	bearing: int16
});
var vmtx_default = new Struct({
	metrics: new LazyArray(VmtxEntry, (t) => t.parent.vhea.numberOfMetrics),
	bearings: new LazyArray(int16, (t) => t.parent.maxp.numGlyphs - t.parent.vhea.numberOfMetrics)
});

//#endregion
//#region src/tables/avar.ts
const shortFrac$1 = new Fixed(16, 14);
const Correspondence = new Struct({
	fromCoord: shortFrac$1,
	toCoord: shortFrac$1
});
const Segment = new Struct({
	pairCount: uint16,
	correspondence: new ArrayT(Correspondence, "pairCount")
});
var avar_default = new Struct({
	version: fixed32,
	axisCount: uint32,
	segment: new ArrayT(Segment, "axisCount")
});

//#endregion
//#region src/tables/fvar.ts
const Axis = new Struct({
	axisTag: new StringT(4),
	minValue: fixed32,
	defaultValue: fixed32,
	maxValue: fixed32,
	flags: uint16,
	nameID: uint16,
	name: (t) => t.parent.parent.name.records.fontFeatures[t.nameID]
});
const Instance = new Struct({
	nameID: uint16,
	name: (t) => t.parent.parent.name.records.fontFeatures[t.nameID],
	flags: uint16,
	coord: new ArrayT(fixed32, (t) => t.parent.axisCount),
	postscriptNameID: new Optional(uint16, (t) => t.parent.instanceSize - t._currentOffset > 0)
});
var fvar_default = new Struct({
	version: fixed32,
	offsetToData: uint16,
	countSizePairs: uint16,
	axisCount: uint16,
	axisSize: uint16,
	instanceCount: uint16,
	instanceSize: uint16,
	axis: new ArrayT(Axis, "axisCount"),
	instance: new ArrayT(Instance, "instanceCount")
});

//#endregion
//#region src/tables/gvar.ts
const shortFrac = new Fixed(16, 14);
var Offset = class {
	static decode(stream, parent) {
		return parent.flags ? stream.readUInt32BE() : stream.readUInt16BE() * 2;
	}
};
const gvar = new Struct({
	version: uint16,
	reserved: new Reserved(uint16),
	axisCount: uint16,
	globalCoordCount: uint16,
	globalCoords: new Pointer(uint32, new ArrayT(new ArrayT(shortFrac, "axisCount"), "globalCoordCount")),
	glyphCount: uint16,
	flags: uint16,
	offsetToData: uint32,
	offsets: new ArrayT(new Pointer(Offset, "void", {
		relativeTo: (ctx) => ctx.offsetToData,
		allowNull: false
	}), (t) => t.glyphCount + 1)
});
var gvar_default = gvar;

//#endregion
//#region src/tables/index.ts
const tables = {
	cmap: cmap_default,
	head: head_default,
	hhea: hhea_default,
	hmtx: hmtx_default,
	maxp: maxp_default,
	name: name_default,
	"OS/2": OS2_default,
	post: post_default,
	loca: loca_default,
	glyf: glyf_default,
	"CFF ": CFFFont_default,
	CFF2: CFFFont_default,
	sbix: sbix_default,
	COLR: COLR_default,
	CPAL: CPAL_default,
	GSUB: GSUB_default,
	HVAR: HVAR_default,
	vhea: vhea_default,
	vmtx: vmtx_default,
	avar: avar_default,
	fvar: fvar_default,
	gvar: gvar_default
};
var tables_default = tables;

//#endregion
//#region src/utils.ts
function binarySearch(arr, cmp) {
	let min = 0;
	let max = arr.length - 1;
	while (min <= max) {
		const mid = min + max >> 1;
		const res = cmp(arr[mid]);
		if (res < 0) max = mid - 1;
		else if (res > 0) min = mid + 1;
		else return mid;
	}
	return -1;
}
function range(index, end) {
	const range$1 = [];
	while (index < end) range$1.push(index++);
	return range$1;
}
const asciiDecoder = new TextDecoder("ascii");

//#endregion
//#region \0@oxc-project+runtime@0.107.0/helpers/decorate.js
function __decorate(decorators, target, key, desc) {
	var c = arguments.length, r = c < 3 ? target : desc === null ? desc = Object.getOwnPropertyDescriptor(target, key) : desc, d;
	if (typeof Reflect === "object" && typeof Reflect.decorate === "function") r = Reflect.decorate(decorators, target, key, desc);
	else for (var i = decorators.length - 1; i >= 0; i--) if (d = decorators[i]) r = (c < 3 ? d(r) : c > 3 ? d(target, key, r) : d(target, key)) || r;
	return c > 3 && r && Object.defineProperty(target, key, r), r;
}

//#endregion
//#region src/CmapProcessor.ts
var CmapProcessor = class {
	#cmap;
	#uvs;
	#encoding;
	constructor(cmapTable) {
		this.#encoding = null;
		this.#cmap = this.#findSubtable(cmapTable, [
			[3, 10],
			[0, 6],
			[0, 4],
			[3, 1],
			[0, 3],
			[0, 2],
			[0, 1],
			[0, 0]
		]);
		if (!this.#cmap) for (let cmap of cmapTable.tables) {
			const mapping = getEncodingMapping(getEncoding(cmap.platformID, cmap.encodingID, cmap.table.language - 1));
			if (mapping) {
				this.#cmap = cmap.table;
				this.#encoding = mapping;
			}
		}
		if (!this.#cmap) throw new Error("Could not find a supported cmap table");
		this.#uvs = this.#findSubtable(cmapTable, [[0, 5]]);
		if (this.#uvs && this.#uvs.version !== 14) this.#uvs = null;
	}
	#findSubtable(cmapTable, pairs) {
		for (let [platformID, encodingID] of pairs) for (let cmap of cmapTable.tables) if (cmap.platformID === platformID && cmap.encodingID === encodingID) return cmap.table;
		return null;
	}
	lookup(codepoint, variationSelector) {
		if (this.#encoding) codepoint = this.#encoding.get(codepoint) || codepoint;
		else if (variationSelector) {
			let gid = this.#getVariationSelector(codepoint, variationSelector);
			if (gid) return gid;
		}
		let cmap = this.#cmap;
		switch (cmap.version) {
			case 0: return cmap.codeMap.get(codepoint) || 0;
			case 4: {
				let min = 0;
				let max = cmap.segCount - 1;
				while (min <= max) {
					let mid = min + max >> 1;
					if (codepoint < cmap.startCode.get(mid)) max = mid - 1;
					else if (codepoint > cmap.endCode.get(mid)) min = mid + 1;
					else {
						let rangeOffset = cmap.idRangeOffset.get(mid);
						let gid;
						if (rangeOffset === 0) gid = codepoint + cmap.idDelta.get(mid);
						else {
							let index = rangeOffset / 2 + (codepoint - cmap.startCode.get(mid)) - (cmap.segCount - mid);
							gid = cmap.glyphIndexArray.get(index) || 0;
							if (gid !== 0) gid += cmap.idDelta.get(mid);
						}
						return gid & 65535;
					}
				}
				return 0;
			}
			case 8: throw new Error("TODO: cmap format 8");
			case 6:
			case 10: return cmap.glyphIndices.get(codepoint - cmap.firstCode) || 0;
			case 12:
			case 13: {
				let min = 0;
				let max = cmap.nGroups - 1;
				while (min <= max) {
					let mid = min + max >> 1;
					let group = cmap.groups.get(mid);
					if (codepoint < group.startCharCode) max = mid - 1;
					else if (codepoint > group.endCharCode) min = mid + 1;
					else if (cmap.version === 12) return group.glyphID + (codepoint - group.startCharCode);
					else return group.glyphID;
				}
				return 0;
			}
			case 14: throw new Error("TODO: cmap format 14");
			default: throw new Error(`Unknown cmap format ${cmap.version}`);
		}
	}
	#getVariationSelector(codepoint, variationSelector) {
		if (!this.#uvs) return 0;
		const selectors = this.#uvs.varSelectors.toArray();
		let i = binarySearch(selectors, (x) => variationSelector - x.varSelector);
		const sel = selectors[i];
		if (i !== -1 && sel.defaultUVS) i = binarySearch(sel.defaultUVS, (x) => codepoint < x.startUnicodeValue ? -1 : codepoint > x.startUnicodeValue + x.additionalCount ? 1 : 0);
		if (i !== -1 && sel.nonDefaultUVS) {
			i = binarySearch(sel.nonDefaultUVS, (x) => codepoint - x.unicodeValue);
			if (i !== -1) return sel.nonDefaultUVS[i].glyphID;
		}
		return 0;
	}
	getCharacterSet() {
		const cmap = this.#cmap;
		switch (cmap.version) {
			case 0: return range(0, cmap.codeMap.length);
			case 4: return cmap.endCode.toArray().flatMap((endCode, i) => range(cmap.startCode.get(i), endCode + 1));
			case 8: throw new Error("TODO: cmap format 8");
			case 6:
			case 10: return range(cmap.firstCode, cmap.firstCode + cmap.glyphIndices.length);
			case 12:
			case 13: return cmap.groups.toArray().flatMap((group) => range(group.startCharCode, group.endCharCode + 1));
			case 14: throw new Error("TODO: cmap format 14");
			default: throw new Error(`Unknown cmap format ${cmap.version}`);
		}
	}
};
__decorate([cache], CmapProcessor.prototype, "getCharacterSet", null);

//#endregion
//#region src/glyph/BBox.ts
/**
* Represents a glyph bounding box
*/
var BBox = class {
	constructor(minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity) {
		this.minX = minX;
		this.minY = minY;
		this.maxX = maxX;
		this.maxY = maxY;
	}
	get width() {
		return this.maxX - this.minX;
	}
	get height() {
		return this.maxY - this.minY;
	}
	addPoint(x, y) {
		if (Math.abs(x) !== Infinity) {
			if (x < this.minX) this.minX = x;
			if (x > this.maxX) this.maxX = x;
		}
		if (Math.abs(y) !== Infinity) {
			if (y < this.minY) this.minY = y;
			if (y > this.maxY) this.maxY = y;
		}
	}
};

//#endregion
//#region src/glyph/Path.ts
const SVG_COMMANDS = {
	moveTo: "M",
	lineTo: "L",
	quadraticCurveTo: "Q",
	bezierCurveTo: "C",
	closePath: "Z"
};
/**
* Path objects are returned by glyphs and represent the actual
* vector outlines for each glyph in the font.
*/
var Path = class Path {
	commands = [];
	_bbox = null;
	_cbox = null;
	/**
	* Converts the path to an SVG path data string
	*/
	toSVG() {
		return this.commands.map((c) => {
			const args = c.args.map((arg) => Math.round(arg * 100) / 100);
			return `${SVG_COMMANDS[c.command]}${args.join(" ")}`;
		}).join("");
	}
	/**
	* Gets the "control box" of a path.
	* This is like the bounding box, but it includes all points including
	* control points of bezier segments and is much faster to compute than
	* the real bounding box.
	*/
	get cbox() {
		if (!this._cbox) {
			const cbox = new BBox();
			for (const command of this.commands) for (let i = 0; i < command.args.length; i += 2) cbox.addPoint(command.args[i], command.args[i + 1]);
			this._cbox = Object.freeze(cbox);
		}
		return this._cbox;
	}
	/**
	* Gets the exact bounding box of the path by evaluating curve segments.
	* Slower to compute than the control box, but more accurate.
	*/
	get bbox() {
		if (this._bbox) return this._bbox;
		const bbox = new BBox();
		let cx = 0, cy = 0;
		const f = (t) => Math.pow(1 - t, 3) * p0[i] + 3 * Math.pow(1 - t, 2) * t * p1[i] + 3 * (1 - t) * Math.pow(t, 2) * p2[i] + Math.pow(t, 3) * p3[i];
		for (const c of this.commands) switch (c.command) {
			case "moveTo":
			case "lineTo":
				const [x, y] = c.args;
				bbox.addPoint(x, y);
				cx = x;
				cy = y;
				break;
			case "quadraticCurveTo":
			case "bezierCurveTo":
				if (c.command === "quadraticCurveTo") {
					var [qp1x, qp1y, p3x, p3y] = c.args;
					var cp1x = cx + 2 / 3 * (qp1x - cx);
					var cp1y = cy + 2 / 3 * (qp1y - cy);
					var cp2x = p3x + 2 / 3 * (qp1x - p3x);
					var cp2y = p3y + 2 / 3 * (qp1y - p3y);
				} else var [cp1x, cp1y, cp2x, cp2y, p3x, p3y] = c.args;
				bbox.addPoint(p3x, p3y);
				var p0 = [cx, cy];
				var p1 = [cp1x, cp1y];
				var p2 = [cp2x, cp2y];
				var p3 = [p3x, p3y];
				for (var i = 0; i <= 1; i++) {
					const b = 6 * p0[i] - 12 * p1[i] + 6 * p2[i];
					const a = -3 * p0[i] + 9 * p1[i] - 9 * p2[i] + 3 * p3[i];
					const c$1 = 3 * p1[i] - 3 * p0[i];
					if (a === 0) {
						if (b === 0) continue;
						const t = -c$1 / b;
						if (0 < t && t < 1) {
							if (i === 0) bbox.addPoint(f(t), bbox.maxY);
							else if (i === 1) bbox.addPoint(bbox.maxX, f(t));
						}
						continue;
					}
					const b2ac = Math.pow(b, 2) - 4 * c$1 * a;
					if (b2ac < 0) continue;
					const t1 = (-b + Math.sqrt(b2ac)) / (2 * a);
					if (0 < t1 && t1 < 1) {
						if (i === 0) bbox.addPoint(f(t1), bbox.maxY);
						else if (i === 1) bbox.addPoint(bbox.maxX, f(t1));
					}
					const t2 = (-b - Math.sqrt(b2ac)) / (2 * a);
					if (0 < t2 && t2 < 1) {
						if (i === 0) bbox.addPoint(f(t2), bbox.maxY);
						else if (i === 1) bbox.addPoint(bbox.maxX, f(t2));
					}
				}
				cx = p3x;
				cy = p3y;
				break;
		}
		return this._bbox = Object.freeze(bbox);
	}
	/**
	* Applies a mapping function to each point in the path.
	*/
	mapPoints(fn) {
		const path = new Path();
		for (const c of this.commands) {
			const args = [];
			for (let i = 0; i < c.args.length; i += 2) {
				const [x, y] = fn(c.args[i], c.args[i + 1]);
				args.push(x, y);
			}
			path[c.command](...args);
		}
		return path;
	}
	/**
	* Transforms the path by the given matrix.
	*/
	transform(m0, m1, m2, m3, m4, m5) {
		return this.mapPoints((x, y) => {
			return [m0 * x + m2 * y + m4, m1 * x + m3 * y + m5];
		});
	}
	/**
	* Translates the path by the given offset.
	*/
	translate(x, y) {
		return this.transform(1, 0, 0, 1, x, y);
	}
	/**
	* Rotates the path by the given angle (in radians).
	*/
	rotate(angle) {
		const cos = Math.cos(angle);
		const sin = Math.sin(angle);
		return this.transform(cos, sin, -sin, cos, 0, 0);
	}
	/**
	* Scales the path.
	*/
	scale(scaleX, scaleY = scaleX) {
		return this.transform(scaleX, 0, 0, scaleY, 0, 0);
	}
};
for (let command of [
	"moveTo",
	"lineTo",
	"quadraticCurveTo",
	"bezierCurveTo",
	"closePath"
]) Path.prototype[command] = function(...args) {
	this._bbox = this._cbox = null;
	this.commands.push({
		command,
		args
	});
	return this;
};

//#endregion
//#region src/glyph/isMark.ts
const markRanges = JSON.parse("[[768,879],[1155,1161],[1425,1469],1471,[1473,1474],[1476,1477],1479,[1552,1562],[1611,1631],1648,[1750,1756],[1759,1764],[1767,1768],[1770,1773],1809,[1840,1866],[1958,1968],[2027,2035],2045,[2070,2073],[2075,2083],[2085,2087],[2089,2093],[2137,2139],[2259,2273],[2275,2307],[2362,2364],[2366,2383],[2385,2391],[2402,2403],[2433,2435],2492,[2494,2500],[2503,2504],[2507,2509],2519,[2530,2531],2558,[2561,2563],2620,[2622,2626],[2631,2632],[2635,2637],2641,[2672,2673],2677,[2689,2691],2748,[2750,2757],[2759,2761],[2763,2765],[2786,2787],[2810,2815],[2817,2819],2876,[2878,2884],[2887,2888],[2891,2893],[2902,2903],[2914,2915],2946,[3006,3010],[3014,3016],[3018,3021],3031,[3072,3076],[3134,3140],[3142,3144],[3146,3149],[3157,3158],[3170,3171],[3201,3203],3260,[3262,3268],[3270,3272],[3274,3277],[3285,3286],[3298,3299],[3328,3331],[3387,3388],[3390,3396],[3398,3400],[3402,3405],3415,[3426,3427],[3458,3459],3530,[3535,3540],3542,[3544,3551],[3570,3571],3633,[3636,3642],[3655,3662],3761,[3764,3772],[3784,3789],[3864,3865],3893,3895,3897,[3902,3903],[3953,3972],[3974,3975],[3981,3991],[3993,4028],4038,[4139,4158],[4182,4185],[4190,4192],[4194,4196],[4199,4205],[4209,4212],[4226,4237],4239,[4250,4253],[4957,4959],[5906,5908],[5938,5940],[5970,5971],[6002,6003],[6068,6099],6109,[6155,6157],[6277,6278],6313,[6432,6443],[6448,6459],[6679,6683],[6741,6750],[6752,6780],6783,[6832,6846],[6912,6916],[6964,6980],[7019,7027],[7040,7042],[7073,7085],[7142,7155],[7204,7223],[7376,7378],[7380,7400],7405,7412,[7415,7417],[7616,7673],[7675,7679],[8400,8432],[11503,11505],11647,[11744,11775],[12330,12335],[12441,12442],[42607,42610],[42612,42621],[42654,42655],[42736,42737],43010,43014,43019,[43043,43047],[43136,43137],[43188,43205],[43232,43249],43263,[43302,43309],[43335,43347],[43392,43395],[43443,43456],43493,[43561,43574],43587,[43596,43597],[43643,43645],43696,[43698,43700],[43703,43704],[43710,43711],43713,[43755,43759],[43765,43766],[44003,44010],[44012,44013],64286,[65024,65039],[65056,65071],66045,66272,[66422,66426],[68097,68099],[68101,68102],[68108,68111],[68152,68154],68159,[68325,68326],[68900,68903],[69446,69456],[69632,69634],[69688,69702],[69759,69762],[69808,69818],[69888,69890],[69927,69940],[69957,69958],70003,[70016,70018],[70067,70080],[70089,70092],[70188,70199],70206,[70367,70378],[70400,70403],[70459,70460],[70462,70468],[70471,70472],[70475,70477],70487,[70498,70499],[70502,70508],[70512,70516],[70709,70726],70750,[70832,70851],[71087,71093],[71096,71104],[71132,71133],[71216,71232],[71339,71351],[71453,71467],[71724,71738],[72145,72151],[72154,72160],72164,[72193,72202],[72243,72249],[72251,72254],72263,[72273,72283],[72330,72345],[72751,72758],[72760,72767],[72850,72871],[72873,72886],[73009,73014],73018,[73020,73021],[73023,73029],73031,[73098,73102],[73104,73105],[73107,73111],[73459,73462],[92912,92916],[92976,92982],94031,[94033,94087],[94095,94098],[113821,113822],[119141,119145],[119149,119154],[119163,119170],[119173,119179],[119210,119213],[119362,119364],[121344,121398],[121403,121452],121461,121476,[121499,121503],[121505,121519],[122880,122886],[122888,122904],[122907,122913],[122915,122916],[122918,122922],[123184,123190],[123628,123631],[125136,125142],[125252,125258],[917760,917999]]").map((item) => typeof item === "number" ? [item, item] : item);
function isMark(codePoint) {
	return binarySearch(markRanges, ([start, end]) => codePoint < start ? -1 : codePoint > end ? 1 : 0) > -1;
}

//#endregion
//#region src/glyph/StandardNames.ts
var StandardNames_default = [
	".notdef",
	".null",
	"nonmarkingreturn",
	"space",
	"exclam",
	"quotedbl",
	"numbersign",
	"dollar",
	"percent",
	"ampersand",
	"quotesingle",
	"parenleft",
	"parenright",
	"asterisk",
	"plus",
	"comma",
	"hyphen",
	"period",
	"slash",
	"zero",
	"one",
	"two",
	"three",
	"four",
	"five",
	"six",
	"seven",
	"eight",
	"nine",
	"colon",
	"semicolon",
	"less",
	"equal",
	"greater",
	"question",
	"at",
	"A",
	"B",
	"C",
	"D",
	"E",
	"F",
	"G",
	"H",
	"I",
	"J",
	"K",
	"L",
	"M",
	"N",
	"O",
	"P",
	"Q",
	"R",
	"S",
	"T",
	"U",
	"V",
	"W",
	"X",
	"Y",
	"Z",
	"bracketleft",
	"backslash",
	"bracketright",
	"asciicircum",
	"underscore",
	"grave",
	"a",
	"b",
	"c",
	"d",
	"e",
	"f",
	"g",
	"h",
	"i",
	"j",
	"k",
	"l",
	"m",
	"n",
	"o",
	"p",
	"q",
	"r",
	"s",
	"t",
	"u",
	"v",
	"w",
	"x",
	"y",
	"z",
	"braceleft",
	"bar",
	"braceright",
	"asciitilde",
	"Adieresis",
	"Aring",
	"Ccedilla",
	"Eacute",
	"Ntilde",
	"Odieresis",
	"Udieresis",
	"aacute",
	"agrave",
	"acircumflex",
	"adieresis",
	"atilde",
	"aring",
	"ccedilla",
	"eacute",
	"egrave",
	"ecircumflex",
	"edieresis",
	"iacute",
	"igrave",
	"icircumflex",
	"idieresis",
	"ntilde",
	"oacute",
	"ograve",
	"ocircumflex",
	"odieresis",
	"otilde",
	"uacute",
	"ugrave",
	"ucircumflex",
	"udieresis",
	"dagger",
	"degree",
	"cent",
	"sterling",
	"section",
	"bullet",
	"paragraph",
	"germandbls",
	"registered",
	"copyright",
	"trademark",
	"acute",
	"dieresis",
	"notequal",
	"AE",
	"Oslash",
	"infinity",
	"plusminus",
	"lessequal",
	"greaterequal",
	"yen",
	"mu",
	"partialdiff",
	"summation",
	"product",
	"pi",
	"integral",
	"ordfeminine",
	"ordmasculine",
	"Omega",
	"ae",
	"oslash",
	"questiondown",
	"exclamdown",
	"logicalnot",
	"radical",
	"florin",
	"approxequal",
	"Delta",
	"guillemotleft",
	"guillemotright",
	"ellipsis",
	"nonbreakingspace",
	"Agrave",
	"Atilde",
	"Otilde",
	"OE",
	"oe",
	"endash",
	"emdash",
	"quotedblleft",
	"quotedblright",
	"quoteleft",
	"quoteright",
	"divide",
	"lozenge",
	"ydieresis",
	"Ydieresis",
	"fraction",
	"currency",
	"guilsinglleft",
	"guilsinglright",
	"fi",
	"fl",
	"daggerdbl",
	"periodcentered",
	"quotesinglbase",
	"quotedblbase",
	"perthousand",
	"Acircumflex",
	"Ecircumflex",
	"Aacute",
	"Edieresis",
	"Egrave",
	"Iacute",
	"Icircumflex",
	"Idieresis",
	"Igrave",
	"Oacute",
	"Ocircumflex",
	"apple",
	"Ograve",
	"Uacute",
	"Ucircumflex",
	"Ugrave",
	"dotlessi",
	"circumflex",
	"tilde",
	"macron",
	"breve",
	"dotaccent",
	"ring",
	"cedilla",
	"hungarumlaut",
	"ogonek",
	"caron",
	"Lslash",
	"lslash",
	"Scaron",
	"scaron",
	"Zcaron",
	"zcaron",
	"brokenbar",
	"Eth",
	"eth",
	"Yacute",
	"yacute",
	"Thorn",
	"thorn",
	"minus",
	"multiply",
	"onesuperior",
	"twosuperior",
	"threesuperior",
	"onehalf",
	"onequarter",
	"threequarters",
	"franc",
	"Gbreve",
	"gbreve",
	"Idotaccent",
	"Scedilla",
	"scedilla",
	"Cacute",
	"cacute",
	"Ccaron",
	"ccaron",
	"dcroat"
];

//#endregion
//#region src/glyph/Glyph.ts
/**
* Glyph objects represent a glyph in the font. They have various properties for accessing metrics and
* the actual vector path the glyph represents.
*
* You do not create glyph objects directly. They are created by various methods on the font object.
* There are several subclasses of the base Glyph class internally that may be returned depending
* on the font format, but they all inherit from this class.
*/
var Glyph = class {
	isMark;
	isLigature;
	_metrics;
	constructor(id, codePoints, _font) {
		this.id = id;
		this.codePoints = codePoints;
		this._font = _font;
		this.isMark = this.codePoints.length > 0 && this.codePoints.every(isMark);
		this.isLigature = this.codePoints.length > 1;
	}
	_getPath() {
		return new Path();
	}
	_getCBox() {
		return this.path.cbox;
	}
	_getBBox() {
		return this.path.bbox;
	}
	#getTableMetrics(table) {
		if (this.id < table.metrics.length) return table.metrics.get(this.id);
		const metric = table.metrics.get(table.metrics.length - 1);
		return {
			advance: metric ? metric.advance : 0,
			bearing: table.bearings.get(this.id - table.metrics.length) || 0
		};
	}
	_getMetrics(cbox) {
		if (this._metrics) return this._metrics;
		let { advance: advanceWidth, bearing: leftBearing } = this.#getTableMetrics(this._font.hmtx);
		let advanceHeight, topBearing;
		if (this._font.vmtx) ({advance: advanceHeight, bearing: topBearing} = this.#getTableMetrics(this._font.vmtx));
		else {
			const os2 = this._font["OS/2"];
			if (!cbox) ({cbox} = this);
			if (os2.version) {
				advanceHeight = Math.abs(os2.typoAscender - os2.typoDescender);
				topBearing = os2.typoAscender - cbox.maxY;
			} else {
				const { hhea } = this._font;
				advanceHeight = Math.abs(hhea.ascent - hhea.descent);
				topBearing = hhea.ascent - cbox.maxY;
			}
		}
		if (this._font._variationProcessor && this._font.HVAR) advanceWidth += this._font._variationProcessor.getAdvanceAdjustment(this.id, this._font.HVAR);
		return this._metrics = {
			advanceWidth,
			advanceHeight,
			leftBearing,
			topBearing
		};
	}
	/**
	* The glyph’s control box.
	* This is often the same as the bounding box, but is faster to compute.
	* Because of the way bezier curves are defined, some of the control points
	* can be outside of the bounding box. Where `bbox` takes this into account,
	* `cbox` does not. Thus, cbox is less accurate, but faster to compute.
	* See [here](http://www.freetype.org/freetype2/docs/glyphs/glyphs-6.html#section-2)
	* for a more detailed description.
	*/
	get cbox() {
		return this._getCBox();
	}
	/**
	* The glyph’s bounding box, i.e. the rectangle that encloses the
	* glyph outline as tightly as possible.
	*/
	get bbox() {
		return this._getBBox();
	}
	/**
	* A vector Path object representing the glyph outline.
	*/
	get path() {
		return this._getPath();
	}
	/**
	* Returns a path scaled to the given font size.
	*/
	getScaledPath(size) {
		let scale = 1 / this._font.unitsPerEm * size;
		return this.path.scale(scale);
	}
	/**
	* The glyph's advance width.
	*/
	get advanceWidth() {
		return this._getMetrics().advanceWidth;
	}
	/**
	* The glyph's advance height.
	*/
	get advanceHeight() {
		return this._getMetrics().advanceHeight;
	}
	_getName() {
		const { post } = this._font;
		if (!post) return null;
		switch (post.version) {
			case 1: return StandardNames_default[this.id];
			case 2:
				const id = post.glyphNameIndex[this.id];
				return id < StandardNames_default.length ? StandardNames_default[id] : post.names[id - StandardNames_default.length];
			case 2.5: return StandardNames_default[this.id + post.offsets[this.id]];
			case 4: return String.fromCharCode(post.map[this.id]);
		}
	}
	/**
	* The glyph's name
	*/
	get name() {
		return this._getName();
	}
};
__decorate([cache], Glyph.prototype, "cbox", null);
__decorate([cache], Glyph.prototype, "bbox", null);
__decorate([cache], Glyph.prototype, "path", null);
__decorate([cache], Glyph.prototype, "advanceWidth", null);
__decorate([cache], Glyph.prototype, "advanceHeight", null);
__decorate([cache], Glyph.prototype, "name", null);

//#endregion
//#region src/glyph/TTFGlyph.ts
const GlyfHeader = new Struct({
	numberOfContours: int16,
	xMin: int16,
	yMin: int16,
	xMax: int16,
	yMax: int16
});
const ON_CURVE = 1;
const X_SHORT_VECTOR = 2;
const Y_SHORT_VECTOR = 4;
const REPEAT = 8;
const SAME_X = 16;
const SAME_Y = 32;
const ARG_1_AND_2_ARE_WORDS = 1;
const WE_HAVE_A_SCALE = 8;
const MORE_COMPONENTS = 32;
const WE_HAVE_AN_X_AND_Y_SCALE = 64;
const WE_HAVE_A_TWO_BY_TWO = 128;
const WE_HAVE_INSTRUCTIONS = 256;
var Point = class Point {
	constructor(onCurve, endContour, x = 0, y = 0) {
		this.onCurve = onCurve;
		this.endContour = endContour;
		this.x = x;
		this.y = y;
	}
	copy() {
		return new Point(this.onCurve, this.endContour, this.x, this.y);
	}
};
var Component = class {
	pos = 0;
	scaleX = 1;
	scaleY = 1;
	scale01 = 0;
	scale10 = 0;
	constructor(glyphID, dx, dy) {
		this.glyphID = glyphID;
		this.dx = dx;
		this.dy = dy;
	}
};
/**
* Represents a TrueType glyph.
*/
var TTFGlyph = class extends Glyph {
	type = "TTF";
	_getCBox(internal) {
		if (this._font._variationProcessor && !internal) return this.path.cbox;
		const stream = this._font._getTableStream("glyf");
		stream.pos += this._font.loca.offsets[this.id];
		const glyph = GlyfHeader.decode(stream);
		const cbox = new BBox(glyph.xMin, glyph.yMin, glyph.xMax, glyph.yMax);
		return Object.freeze(cbox);
	}
	_parseGlyphCoord(stream, prev, short, same) {
		let val;
		if (short) {
			val = stream.readUInt8();
			if (!same) val = -val;
			val += prev;
		} else val = same ? prev : prev + stream.readInt16BE();
		return val;
	}
	_decode() {
		let glyfPos = this._font.loca.offsets[this.id];
		if (glyfPos === this._font.loca.offsets[this.id + 1]) return null;
		const stream = this._font._getTableStream("glyf");
		stream.pos += glyfPos;
		const startPos = stream.pos;
		const glyph = GlyfHeader.decode(stream);
		if (glyph.numberOfContours > 0) this._decodeSimple(glyph, stream);
		else if (glyph.numberOfContours < 0) this._decodeComposite(glyph, stream, startPos);
		return glyph;
	}
	_decodeSimple(glyph, stream) {
		glyph.points = [];
		const endPtsOfContours = new ArrayT(uint16, glyph.numberOfContours).decode(stream);
		glyph.instructions = new ArrayT(uint8, uint16).decode(stream);
		const flags = [];
		const numCoords = endPtsOfContours[endPtsOfContours.length - 1] + 1;
		while (flags.length < numCoords) {
			const flag = stream.readUInt8();
			flags.push(flag);
			if (flag & REPEAT) {
				const count = stream.readUInt8();
				for (let j = 0; j < count; j++) flags.push(flag);
			}
		}
		for (let i = 0; i < flags.length; i++) {
			const flag = flags[i];
			const point = new Point(!!(flag & ON_CURVE), endPtsOfContours.indexOf(i) >= 0, 0, 0);
			glyph.points.push(point);
		}
		let px = 0;
		for (let i = 0; i < flags.length; i++) {
			const flag = flags[i];
			glyph.points[i].x = px = this._parseGlyphCoord(stream, px, flag & X_SHORT_VECTOR, flag & SAME_X);
		}
		let py = 0;
		for (let i = 0; i < flags.length; i++) {
			const flag = flags[i];
			glyph.points[i].y = py = this._parseGlyphCoord(stream, py, flag & Y_SHORT_VECTOR, flag & SAME_Y);
		}
		if (this._font._variationProcessor) {
			let points = glyph.points.slice();
			points.push(...this.#getPhantomPoints(glyph));
			this._font._variationProcessor.transformPoints(this.id, points);
			glyph.phantomPoints = points.slice(-4);
		}
	}
	_decodeComposite(glyph, stream, offset = 0) {
		glyph.components = [];
		let haveInstructions = false;
		let flags = MORE_COMPONENTS;
		while (flags & MORE_COMPONENTS) {
			flags = stream.readUInt16BE();
			const gPos = stream.pos - offset;
			const glyphID = stream.readUInt16BE();
			if (!haveInstructions) haveInstructions = (flags & WE_HAVE_INSTRUCTIONS) !== 0;
			let dx, dy;
			if (flags & ARG_1_AND_2_ARE_WORDS) {
				dx = stream.readInt16BE();
				dy = stream.readInt16BE();
			} else {
				dx = stream.readInt8();
				dy = stream.readInt8();
			}
			const component = new Component(glyphID, dx, dy);
			component.pos = gPos;
			const two_30 = 1 << 30;
			if (flags & WE_HAVE_A_SCALE) component.scaleX = component.scaleY = (stream.readUInt8() << 24 | stream.readUInt8() << 16) / two_30;
			else if (flags & WE_HAVE_AN_X_AND_Y_SCALE) {
				component.scaleX = (stream.readUInt8() << 24 | stream.readUInt8() << 16) / two_30;
				component.scaleY = (stream.readUInt8() << 24 | stream.readUInt8() << 16) / two_30;
			} else if (flags & WE_HAVE_A_TWO_BY_TWO) {
				component.scaleX = (stream.readUInt8() << 24 | stream.readUInt8() << 16) / two_30;
				component.scale01 = (stream.readUInt8() << 24 | stream.readUInt8() << 16) / two_30;
				component.scale10 = (stream.readUInt8() << 24 | stream.readUInt8() << 16) / two_30;
				component.scaleY = (stream.readUInt8() << 24 | stream.readUInt8() << 16) / two_30;
			}
			glyph.components.push(component);
		}
		if (this._font._variationProcessor) {
			const points = glyph.components.map((c) => new Point(true, true, c.dx, c.dy));
			points.push(...this.#getPhantomPoints(glyph));
			this._font._variationProcessor.transformPoints(this.id, points);
			glyph.phantomPoints = points.splice(-4, 4);
			for (let i = 0; i < points.length; i++) {
				const point = points[i];
				glyph.components[i].dx = point.x;
				glyph.components[i].dy = point.y;
			}
		}
		return haveInstructions;
	}
	#getPhantomPoints(glyph) {
		const cbox = this._getCBox(true);
		this._metrics ??= Glyph.prototype._getMetrics.call(this, cbox);
		const { advanceWidth, advanceHeight, leftBearing, topBearing } = this._metrics;
		return [
			new Point(false, true, glyph.xMin - leftBearing, 0),
			new Point(false, true, glyph.xMin - leftBearing + advanceWidth, 0),
			new Point(false, true, 0, glyph.yMax + topBearing),
			new Point(false, true, 0, glyph.yMax + topBearing + advanceHeight)
		];
	}
	_getContours() {
		const glyph = this._decode();
		if (!glyph) return [];
		let points = [];
		if (glyph.numberOfContours < 0) for (let component of glyph.components) {
			const contours$1 = this._font.getGlyph(component.glyphID)._getContours();
			for (let i = 0; i < contours$1.length; i++) {
				const contour = contours$1[i];
				for (let j = 0; j < contour.length; j++) {
					const point = contour[j];
					const x = point.x * component.scaleX + point.y * component.scale01 + component.dx;
					const y = point.y * component.scaleY + point.x * component.scale10 + component.dy;
					points.push(new Point(point.onCurve, point.endContour, x, y));
				}
			}
		}
		else points = glyph.points || [];
		if (glyph.phantomPoints && !this._font.directory.tables.HVAR) {
			this._metrics.advanceWidth = glyph.phantomPoints[1].x - glyph.phantomPoints[0].x;
			this._metrics.advanceHeight = glyph.phantomPoints[3].y - glyph.phantomPoints[2].y;
			this._metrics.leftBearing = glyph.xMin - glyph.phantomPoints[0].x;
			this._metrics.topBearing = glyph.phantomPoints[2].y - glyph.yMax;
		}
		const contours = [];
		let cur = [];
		for (let k = 0; k < points.length; k++) {
			const point = points[k];
			cur.push(point);
			if (point.endContour) {
				contours.push(cur);
				cur = [];
			}
		}
		return contours;
	}
	_getMetrics() {
		if (this._metrics) return this._metrics;
		const cbox = this._getCBox(true);
		super._getMetrics(cbox);
		if (this._font._variationProcessor && !this._font.HVAR) this.path;
		return this._metrics;
	}
	_getPath() {
		const contours = this._getContours();
		const path = new Path();
		let curvePt;
		for (const contour of contours) {
			let firstPt = contour[0];
			const lastPt = contour[contour.length - 1];
			let start = 0;
			if (firstPt.onCurve) {
				curvePt = null;
				start = 1;
			} else {
				if (lastPt.onCurve) firstPt = lastPt;
				else firstPt = new Point(false, false, (firstPt.x + lastPt.x) / 2, (firstPt.y + lastPt.y) / 2);
				curvePt = firstPt;
			}
			path.moveTo(firstPt.x, firstPt.y);
			for (let j = start; j < contour.length; j++) {
				const pt = contour[j];
				const prevPt = j === 0 ? firstPt : contour[j - 1];
				if (prevPt.onCurve && pt.onCurve) path.lineTo(pt.x, pt.y);
				else if (prevPt.onCurve && !pt.onCurve) curvePt = pt;
				else if (!prevPt.onCurve && !pt.onCurve) {
					const midX = (prevPt.x + pt.x) / 2;
					const midY = (prevPt.y + pt.y) / 2;
					path.quadraticCurveTo(prevPt.x, prevPt.y, midX, midY);
					curvePt = pt;
				} else if (!prevPt.onCurve && pt.onCurve) {
					path.quadraticCurveTo(curvePt.x, curvePt.y, pt.x, pt.y);
					curvePt = null;
				} else throw new Error("Unknown TTF path state");
			}
			if (curvePt) path.quadraticCurveTo(curvePt.x, curvePt.y, firstPt.x, firstPt.y);
			path.closePath();
		}
		return path;
	}
};

//#endregion
//#region src/glyph/CFFGlyph.ts
/**
* Represents an OpenType PostScript glyph, in the Compact Font Format.
*/
var CFFGlyph = class extends Glyph {
	type = "CFF";
	_getName() {
		if (this._font.CFF2) return super._getName();
		return this._font["CFF "].getGlyphName(this.id);
	}
	bias(s) {
		return s.length < 1240 ? 107 : s.length < 33900 ? 1131 : 32768;
	}
	_getPath() {
		const cff = this._font.CFF2 || this._font["CFF "];
		const { stream } = cff;
		const str = cff.topDict.CharStrings[this.id];
		let end = str.offset + str.length;
		stream.pos = str.offset;
		const path = new Path();
		const stack = [];
		const trans = [];
		let width = null;
		let nStems = 0;
		let x = 0, y = 0;
		let usedGsubrs;
		let usedSubrs;
		let open = false;
		this._usedGsubrs = usedGsubrs = {};
		this._usedSubrs = usedSubrs = {};
		const gsubrs = cff.globalSubrIndex || [];
		const gsubrsBias = this.bias(gsubrs);
		const privateDict = cff.privateDictForGlyph(this.id) || {};
		const subrs = privateDict.Subrs || [];
		const subrsBias = this.bias(subrs);
		const vstore = cff.topDict.vstore?.itemVariationStore;
		let { vsindex } = privateDict;
		const variationProcessor = this._font._variationProcessor;
		const checkWidth = () => {
			width ??= stack.shift() + privateDict.nominalWidthX;
		};
		const parseStems = () => {
			if (stack.length % 2 !== 0) checkWidth();
			nStems += stack.length >> 1;
			return stack.length = 0;
		};
		const moveTo = (x$1, y$1) => {
			if (open) path.closePath();
			path.moveTo(x$1, y$1);
			open = true;
		};
		const parse = () => {
			while (stream.pos < end) {
				let op = stream.readUInt8();
				if (op < 32) {
					let index, subr, phase;
					let c1x, c1y, c2x, c2y, c3x, c3y;
					let c4x, c4y, c5x, c5y, c6x, c6y;
					let pts;
					switch (op) {
						case 1:
						case 3:
						case 18:
						case 23:
							parseStems();
							break;
						case 4:
							if (stack.length > 1) checkWidth();
							y += stack.shift();
							moveTo(x, y);
							break;
						case 5:
							while (stack.length >= 2) {
								x += stack.shift();
								y += stack.shift();
								path.lineTo(x, y);
							}
							break;
						case 6:
						case 7:
							phase = op === 6;
							while (stack.length >= 1) {
								if (phase) x += stack.shift();
								else y += stack.shift();
								path.lineTo(x, y);
								phase = !phase;
							}
							break;
						case 8:
							while (stack.length > 0) {
								c1x = x + stack.shift();
								c1y = y + stack.shift();
								c2x = c1x + stack.shift();
								c2y = c1y + stack.shift();
								x = c2x + stack.shift();
								y = c2y + stack.shift();
								path.bezierCurveTo(c1x, c1y, c2x, c2y, x, y);
							}
							break;
						case 10:
							index = stack.pop() + subrsBias;
							subr = subrs[index];
							if (subr) {
								usedSubrs[index] = true;
								const p = stream.pos;
								const e = end;
								stream.pos = subr.offset;
								end = subr.offset + subr.length;
								parse();
								stream.pos = p;
								end = e;
							}
							break;
						case 11:
							if (cff.version >= 2) break;
							return;
						case 14:
							if (cff.version >= 2) break;
							if (stack.length > 0) checkWidth();
							if (open) {
								path.closePath();
								open = false;
							}
							break;
						case 15:
							if (cff.version < 2) throw new Error("vsindex operator not supported in CFF v1");
							vsindex = stack.pop();
							break;
						case 16: {
							if (cff.version < 2) throw new Error("blend operator not supported in CFF v1");
							if (!variationProcessor) throw new Error("blend operator in non-variation font");
							const blendVector = variationProcessor.getBlendVector(vstore, vsindex);
							const numBlends = stack.pop();
							let numOperands = numBlends * blendVector.length;
							let delta = stack.length - numOperands;
							const base = delta - numBlends;
							for (let i = 0; i < numBlends; i++) {
								let sum = stack[base + i];
								for (let j = 0; j < blendVector.length; j++) sum += blendVector[j] * stack[delta++];
								stack[base + i] = sum;
							}
							while (numOperands--) stack.pop();
							break;
						}
						case 19:
						case 20:
							parseStems();
							stream.pos += nStems + 7 >> 3;
							break;
						case 21:
							if (stack.length > 2) checkWidth();
							x += stack.shift();
							y += stack.shift();
							moveTo(x, y);
							break;
						case 22:
							if (stack.length > 1) checkWidth();
							x += stack.shift();
							moveTo(x, y);
							break;
						case 24:
							while (stack.length >= 8) {
								c1x = x + stack.shift();
								c1y = y + stack.shift();
								c2x = c1x + stack.shift();
								c2y = c1y + stack.shift();
								x = c2x + stack.shift();
								y = c2y + stack.shift();
								path.bezierCurveTo(c1x, c1y, c2x, c2y, x, y);
							}
							x += stack.shift();
							y += stack.shift();
							path.lineTo(x, y);
							break;
						case 25:
							while (stack.length >= 8) {
								x += stack.shift();
								y += stack.shift();
								path.lineTo(x, y);
							}
							c1x = x + stack.shift();
							c1y = y + stack.shift();
							c2x = c1x + stack.shift();
							c2y = c1y + stack.shift();
							x = c2x + stack.shift();
							y = c2y + stack.shift();
							path.bezierCurveTo(c1x, c1y, c2x, c2y, x, y);
							break;
						case 26:
							if (stack.length % 2) x += stack.shift();
							while (stack.length >= 4) {
								c1x = x;
								c1y = y + stack.shift();
								c2x = c1x + stack.shift();
								c2y = c1y + stack.shift();
								x = c2x;
								y = c2y + stack.shift();
								path.bezierCurveTo(c1x, c1y, c2x, c2y, x, y);
							}
							break;
						case 27:
							if (stack.length % 2) y += stack.shift();
							while (stack.length >= 4) {
								c1x = x + stack.shift();
								c1y = y;
								c2x = c1x + stack.shift();
								c2y = c1y + stack.shift();
								x = c2x + stack.shift();
								y = c2y;
								path.bezierCurveTo(c1x, c1y, c2x, c2y, x, y);
							}
							break;
						case 28:
							stack.push(stream.readInt16BE());
							break;
						case 29:
							index = stack.pop() + gsubrsBias;
							subr = gsubrs[index];
							if (subr) {
								usedGsubrs[index] = true;
								const p = stream.pos;
								const e = end;
								stream.pos = subr.offset;
								end = subr.offset + subr.length;
								parse();
								stream.pos = p;
								end = e;
							}
							break;
						case 30:
						case 31:
							phase = op === 31;
							while (stack.length >= 4) {
								if (phase) {
									c1x = x + stack.shift();
									c1y = y;
									c2x = c1x + stack.shift();
									c2y = c1y + stack.shift();
									y = c2y + stack.shift();
									x = c2x + (stack.length === 1 ? stack.shift() : 0);
								} else {
									c1x = x;
									c1y = y + stack.shift();
									c2x = c1x + stack.shift();
									c2y = c1y + stack.shift();
									x = c2x + stack.shift();
									y = c2y + (stack.length === 1 ? stack.shift() : 0);
								}
								path.bezierCurveTo(c1x, c1y, c2x, c2y, x, y);
								phase = !phase;
							}
							break;
						case 12:
							op = stream.readUInt8();
							switch (op) {
								case 3:
									let a = stack.pop();
									let b = stack.pop();
									stack.push(a && b ? 1 : 0);
									break;
								case 4:
									a = stack.pop();
									b = stack.pop();
									stack.push(a || b ? 1 : 0);
									break;
								case 5:
									a = stack.pop();
									stack.push(a ? 0 : 1);
									break;
								case 9:
									a = stack.pop();
									stack.push(Math.abs(a));
									break;
								case 10:
									a = stack.pop();
									b = stack.pop();
									stack.push(a + b);
									break;
								case 11:
									a = stack.pop();
									b = stack.pop();
									stack.push(a - b);
									break;
								case 12:
									a = stack.pop();
									b = stack.pop();
									stack.push(a / b);
									break;
								case 14:
									a = stack.pop();
									stack.push(-a);
									break;
								case 15:
									a = stack.pop();
									b = stack.pop();
									stack.push(a === b ? 1 : 0);
									break;
								case 18:
									stack.pop();
									break;
								case 20:
									let val = stack.pop();
									let idx = stack.pop();
									trans[idx] = val;
									break;
								case 21:
									idx = stack.pop();
									stack.push(trans[idx] || 0);
									break;
								case 22:
									const s1 = stack.pop();
									const s2 = stack.pop();
									const v1 = stack.pop();
									const v2 = stack.pop();
									stack.push(v1 <= v2 ? s1 : s2);
									break;
								case 23:
									stack.push(Math.random());
									break;
								case 24:
									a = stack.pop();
									b = stack.pop();
									stack.push(a * b);
									break;
								case 26:
									a = stack.pop();
									stack.push(Math.sqrt(a));
									break;
								case 27:
									a = stack.pop();
									stack.push(a, a);
									break;
								case 28:
									a = stack.pop();
									b = stack.pop();
									stack.push(b, a);
									break;
								case 29:
									idx = stack.pop();
									if (idx < 0) idx = 0;
									else if (idx > stack.length - 1) idx = stack.length - 1;
									stack.push(stack[idx]);
									break;
								case 30:
									const n = stack.pop();
									let j = stack.pop();
									if (j >= 0) while (j > 0) {
										var t = stack[n - 1];
										for (let i = n - 2; i >= 0; i--) stack[i + 1] = stack[i];
										stack[0] = t;
										j--;
									}
									else while (j < 0) {
										var t = stack[0];
										for (let i = 0; i <= n; i++) stack[i] = stack[i + 1];
										stack[n - 1] = t;
										j++;
									}
									break;
								case 34:
									c1x = x + stack.shift();
									c1y = y;
									c2x = c1x + stack.shift();
									c2y = c1y + stack.shift();
									c3x = c2x + stack.shift();
									c3y = c2y;
									c4x = c3x + stack.shift();
									c4y = c3y;
									c5x = c4x + stack.shift();
									c5y = c4y;
									c6x = c5x + stack.shift();
									c6y = c5y;
									x = c6x;
									y = c6y;
									path.bezierCurveTo(c1x, c1y, c2x, c2y, c3x, c3y);
									path.bezierCurveTo(c4x, c4y, c5x, c5y, c6x, c6y);
									break;
								case 35:
									pts = [];
									for (let i = 0; i <= 5; i++) {
										x += stack.shift();
										y += stack.shift();
										pts.push(x, y);
									}
									path.bezierCurveTo(...pts.slice(0, 6));
									path.bezierCurveTo(...pts.slice(6));
									stack.shift();
									break;
								case 36:
									c1x = x + stack.shift();
									c1y = y + stack.shift();
									c2x = c1x + stack.shift();
									c2y = c1y + stack.shift();
									c3x = c2x + stack.shift();
									c3y = c2y;
									c4x = c3x + stack.shift();
									c4y = c3y;
									c5x = c4x + stack.shift();
									c5y = c4y + stack.shift();
									c6x = c5x + stack.shift();
									c6y = c5y;
									x = c6x;
									y = c6y;
									path.bezierCurveTo(c1x, c1y, c2x, c2y, c3x, c3y);
									path.bezierCurveTo(c4x, c4y, c5x, c5y, c6x, c6y);
									break;
								case 37:
									const startx = x;
									const starty = y;
									pts = [];
									for (let i = 0; i <= 4; i++) {
										x += stack.shift();
										y += stack.shift();
										pts.push(x, y);
									}
									if (Math.abs(x - startx) > Math.abs(y - starty)) {
										x += stack.shift();
										y = starty;
									} else {
										x = startx;
										y += stack.shift();
									}
									pts.push(x, y);
									path.bezierCurveTo(...pts.slice(0, 6));
									path.bezierCurveTo(...pts.slice(6));
									break;
								default: throw new Error(`Unknown op: 12 ${op}`);
							}
							break;
						default: throw new Error(`Unknown op: ${op}`);
					}
				} else if (op < 247) stack.push(op - 139);
				else if (op < 251) {
					var b1 = stream.readUInt8();
					stack.push((op - 247) * 256 + b1 + 108);
				} else if (op < 255) {
					var b1 = stream.readUInt8();
					stack.push(-(op - 251) * 256 - b1 - 108);
				} else stack.push(stream.readInt32BE() / 65536);
			}
		};
		parse();
		if (open) path.closePath();
		return path;
	}
};

//#endregion
//#region src/glyph/SBIXGlyph.ts
const SBIXImage = new Struct({
	originX: uint16,
	originY: uint16,
	type: new StringT(4),
	data: new BufferT((t) => t.parent.buflen - t._currentOffset)
});
/**
* Represents a color (e.g. emoji) glyph in Apple's SBIX format.
*/
var SBIXGlyph = class extends TTFGlyph {
	type = "SBIX";
	/**
	* Returns an object representing a glyph image at the given point size.
	* The object has a data property with a Buffer containing the actual image data,
	* along with the image type, and origin.
	*/
	getImageForSize(size) {
		for (let i = 0; i < this._font.sbix.imageTables.length; i++) {
			var table = this._font.sbix.imageTables[i];
			if (table.ppem >= size) break;
		}
		const offsets$1 = table.imageOffsets;
		const start = offsets$1[this.id];
		const end = offsets$1[this.id + 1];
		if (start === end) return null;
		this._font.stream.pos = start;
		return SBIXImage.decode(this._font.stream, { buflen: end - start });
	}
};

//#endregion
//#region src/glyph/COLRGlyph.ts
const COLRLayer = (glyph, color) => ({
	glyph,
	color
});
const Black = {
	red: 0,
	green: 0,
	blue: 0,
	alpha: 255
};
/**
* Represents a color (e.g. emoji) glyph in Microsoft's COLR format.
* Each glyph in this format contain a list of colored layers, each
* of which  is another vector glyph.
*/
var COLRGlyph = class extends Glyph {
	type = "COLR";
	_getBBox() {
		const bbox = new BBox();
		for (const layer of this.layers) {
			const b = layer.glyph.bbox;
			bbox.addPoint(b.minX, b.minY);
			bbox.addPoint(b.maxX, b.maxY);
		}
		return bbox;
	}
	/**
	* Returns an array of objects containing the glyph and color for
	* each layer in the composite color glyph.
	* @type {object[]}
	*/
	get layers() {
		const cpal = this._font.CPAL;
		const colr = this._font.COLR;
		let low = 0;
		let high = colr.baseGlyphRecord.length - 1;
		while (low <= high) {
			const mid = low + high >> 1;
			const rec = colr.baseGlyphRecord[mid];
			if (this.id < rec.gid) high = mid - 1;
			else if (this.id > rec.gid) low = mid + 1;
			else {
				var baseLayer = rec;
				break;
			}
		}
		if (baseLayer == null) {
			const g = this._font._getBaseGlyph(this.id);
			return g === this ? [] : [COLRLayer(g, Black)];
		}
		const layers = [];
		for (let i = baseLayer.firstLayerIndex; i < baseLayer.firstLayerIndex + baseLayer.numLayers; i++) {
			const rec = colr.layerRecords[i];
			const color = cpal.colorRecords[rec.paletteIndex];
			const g = this._font._getBaseGlyph(rec.gid);
			layers.push(COLRLayer(g, color));
		}
		return layers;
	}
};

//#endregion
//#region src/glyph/GlyphVariationProcessor.ts
const TUPLES_SHARE_POINT_NUMBERS = 32768;
const TUPLE_COUNT_MASK = 4095;
const EMBEDDED_TUPLE_COORD = 32768;
const INTERMEDIATE_TUPLE = 16384;
const PRIVATE_POINT_NUMBERS = 8192;
const TUPLE_INDEX_MASK = 4095;
const POINTS_ARE_WORDS = 128;
const POINT_RUN_COUNT_MASK = 127;
const DELTAS_ARE_ZERO = 128;
const DELTAS_ARE_WORDS = 64;
const DELTA_RUN_COUNT_MASK = 63;
/**
* This class is transforms TrueType glyphs according to the data from
* the Apple Advanced Typography variation tables (fvar, gvar, and avar).
* These tables allow infinite adjustments to glyph weight, width, slant,
* and optical size without the designer needing to specify every exact style.
*
* Apple's documentation for these tables is not great, so thanks to the
* Freetype project for figuring much of this out.
*
* @private
*/
var GlyphVariationProcessor = class {
	#normalizedCoords;
	#blendVectors = /* @__PURE__ */ new Map();
	constructor(font, coords) {
		this.font = font;
		this.#normalizedCoords = this.normalizeCoords(coords);
	}
	normalizeCoords(coords) {
		const normalized = this.font.fvar.axis.map((axis, i) => (coords[i] - axis.defaultValue + Number.EPSILON) / ((coords[i] < axis.defaultValue ? axis.defaultValue - axis.minValue : axis.maxValue - axis.defaultValue) + Number.EPSILON));
		for (let i = 0; i < this.font.avar?.segment.length || 0; i++) {
			const segment = this.font.avar.segment[i];
			for (let j = 0; j < segment.correspondence.length; j++) {
				const pair = segment.correspondence[j];
				if (j >= 1 && normalized[i] < pair.fromCoord) {
					const prev = segment.correspondence[j - 1];
					normalized[i] = ((normalized[i] - prev.fromCoord) * (pair.toCoord - prev.toCoord) + Number.EPSILON) / (pair.fromCoord - prev.fromCoord + Number.EPSILON) + prev.toCoord;
					break;
				}
			}
		}
		return normalized;
	}
	transformPoints(gid, glyphPoints) {
		if (!this.font.fvar || !this.font.gvar) return;
		const { gvar: gvar$1 } = this.font;
		if (gid >= gvar$1.glyphCount) return;
		const offset = gvar$1.offsets[gid];
		if (offset === gvar$1.offsets[gid + 1]) return;
		const { stream } = this.font;
		stream.pos = offset;
		if (stream.pos >= stream.length) return;
		let tupleCount = stream.readUInt16BE();
		let offsetToData = offset + stream.readUInt16BE();
		let sharedPoints;
		if (tupleCount & TUPLES_SHARE_POINT_NUMBERS) {
			var here = stream.pos;
			stream.pos = offsetToData;
			sharedPoints = this.decodePoints();
			offsetToData = stream.pos;
			stream.pos = here;
		}
		const origPoints = glyphPoints.map((pt) => pt.copy());
		tupleCount &= TUPLE_COUNT_MASK;
		for (let i = 0; i < tupleCount; i++) {
			const tupleDataSize = stream.readUInt16BE();
			const tupleIndex = stream.readUInt16BE();
			let tupleCoords, startCoords, endCoords;
			if (tupleIndex & EMBEDDED_TUPLE_COORD) {
				tupleCoords = [];
				for (let a = 0; a < gvar$1.axisCount; a++) tupleCoords.push(stream.readInt16BE() / 16384);
			} else {
				if ((tupleIndex & TUPLE_INDEX_MASK) >= gvar$1.globalCoordCount) throw new Error("Invalid gvar table");
				tupleCoords = gvar$1.globalCoords[tupleIndex & TUPLE_INDEX_MASK];
			}
			if (tupleIndex & INTERMEDIATE_TUPLE) {
				startCoords = [];
				for (let a = 0; a < gvar$1.axisCount; a++) startCoords.push(stream.readInt16BE() / 16384);
				endCoords = [];
				for (let a = 0; a < gvar$1.axisCount; a++) endCoords.push(stream.readInt16BE() / 16384);
			}
			let factor = this.tupleFactor(tupleIndex, tupleCoords, startCoords, endCoords);
			if (factor === 0) {
				offsetToData += tupleDataSize;
				continue;
			}
			var here = stream.pos;
			stream.pos = offsetToData;
			let points;
			if (tupleIndex & PRIVATE_POINT_NUMBERS) points = this.decodePoints();
			else points = sharedPoints;
			const nPoints = points.length === 0 ? glyphPoints.length : points.length;
			const xDeltas = this.decodeDeltas(nPoints);
			const yDeltas = this.decodeDeltas(nPoints);
			if (points.length === 0) for (let i$1 = 0; i$1 < glyphPoints.length; i$1++) {
				const point = glyphPoints[i$1];
				point.x += Math.round(xDeltas[i$1] * factor);
				point.y += Math.round(yDeltas[i$1] * factor);
			}
			else {
				let outPoints = origPoints.map((pt) => pt.copy());
				let hasDelta = glyphPoints.map(() => false);
				for (let i$1 = 0; i$1 < points.length; i$1++) {
					const idx = points[i$1];
					if (idx < glyphPoints.length) {
						const point = outPoints[idx];
						hasDelta[idx] = true;
						point.x += xDeltas[i$1] * factor;
						point.y += yDeltas[i$1] * factor;
					}
				}
				this.interpolateMissingDeltas(outPoints, origPoints, hasDelta);
				for (let i$1 = 0; i$1 < glyphPoints.length; i$1++) {
					const deltaX = outPoints[i$1].x - origPoints[i$1].x;
					const deltaY = outPoints[i$1].y - origPoints[i$1].y;
					glyphPoints[i$1].x = Math.round(glyphPoints[i$1].x + deltaX);
					glyphPoints[i$1].y = Math.round(glyphPoints[i$1].y + deltaY);
				}
			}
			offsetToData += tupleDataSize;
			stream.pos = here;
		}
	}
	decodePoints() {
		const { stream } = this.font;
		let count = stream.readUInt8();
		if (count & POINTS_ARE_WORDS) count = (count & POINT_RUN_COUNT_MASK) << 8 | stream.readUInt8();
		const points = new Uint16Array(count);
		let i = 0;
		let point = 0;
		while (i < count) {
			const run = stream.readUInt8();
			const runCount = (run & POINT_RUN_COUNT_MASK) + 1;
			const fn = run & POINTS_ARE_WORDS ? stream.readUInt16BE : stream.readUInt8;
			for (let j = 0; j < runCount && i < count; j++) {
				point += fn.call(stream);
				points[i++] = point;
			}
		}
		return points;
	}
	decodeDeltas(count) {
		const { stream } = this.font;
		let i = 0;
		const deltas = new Int16Array(count);
		while (i < count) {
			const run = stream.readUInt8();
			const runCount = (run & DELTA_RUN_COUNT_MASK) + 1;
			if (run & DELTAS_ARE_ZERO) i += runCount;
			else {
				const fn = run & DELTAS_ARE_WORDS ? stream.readInt16BE : stream.readInt8;
				for (let j = 0; j < runCount && i < count; j++) deltas[i++] = fn.call(stream);
			}
		}
		return deltas;
	}
	tupleFactor(tupleIndex, tupleCoords, startCoords, endCoords) {
		const normalized = this.#normalizedCoords;
		const { gvar: gvar$1 } = this.font;
		let factor = 1;
		for (let i = 0; i < gvar$1.axisCount; i++) {
			if (tupleCoords[i] === 0) continue;
			if (normalized[i] === 0) return 0;
			if ((tupleIndex & INTERMEDIATE_TUPLE) === 0) {
				if (normalized[i] < Math.min(0, tupleCoords[i]) || normalized[i] > Math.max(0, tupleCoords[i])) return 0;
				factor = (factor * normalized[i] + Number.EPSILON) / (tupleCoords[i] + Number.EPSILON);
			} else if (normalized[i] < startCoords[i] || normalized[i] > endCoords[i]) return 0;
			else if (normalized[i] < tupleCoords[i]) factor = factor * (normalized[i] - startCoords[i] + Number.EPSILON) / (tupleCoords[i] - startCoords[i] + Number.EPSILON);
			else factor = factor * (endCoords[i] - normalized[i] + Number.EPSILON) / (endCoords[i] - tupleCoords[i] + Number.EPSILON);
		}
		return factor;
	}
	interpolateMissingDeltas(points, inPoints, hasDelta) {
		if (points.length === 0) return;
		let point = 0;
		while (point < points.length) {
			const firstPoint = point;
			let endPoint = point;
			let pt = points[endPoint];
			while (!pt.endContour) pt = points[++endPoint];
			while (point <= endPoint && !hasDelta[point]) point++;
			if (point > endPoint) continue;
			const firstDelta = point;
			let curDelta = point;
			point++;
			while (point <= endPoint) {
				if (hasDelta[point]) {
					this.deltaInterpolate(curDelta + 1, point - 1, curDelta, point, inPoints, points);
					curDelta = point;
				}
				point++;
			}
			if (curDelta === firstDelta) this.deltaShift(firstPoint, endPoint, curDelta, inPoints, points);
			else {
				this.deltaInterpolate(curDelta + 1, endPoint, curDelta, firstDelta, inPoints, points);
				if (firstDelta > 0) this.deltaInterpolate(firstPoint, firstDelta - 1, curDelta, firstDelta, inPoints, points);
			}
			point = endPoint + 1;
		}
	}
	deltaInterpolate(p1, p2, ref1, ref2, inPoints, outPoints) {
		if (p1 > p2) return;
		const iterable = ["x", "y"];
		for (let i = 0; i < iterable.length; i++) {
			const k = iterable[i];
			if (inPoints[ref1][k] > inPoints[ref2][k]) {
				const p = ref1;
				ref1 = ref2;
				ref2 = p;
			}
			const in1 = inPoints[ref1][k];
			const in2 = inPoints[ref2][k];
			const out1 = outPoints[ref1][k];
			const out2 = outPoints[ref2][k];
			if (in1 !== in2 || out1 === out2) {
				const scale = in1 === in2 ? 0 : (out2 - out1) / (in2 - in1);
				for (let p = p1; p <= p2; p++) {
					let out = inPoints[p][k];
					if (out <= in1) out += out1 - in1;
					else if (out >= in2) out += out2 - in2;
					else out = out1 + (out - in1) * scale;
					outPoints[p][k] = out;
				}
			}
		}
	}
	deltaShift(p1, p2, ref, inPoints, outPoints) {
		const deltaX = outPoints[ref].x - inPoints[ref].x;
		const deltaY = outPoints[ref].y - inPoints[ref].y;
		if (deltaX === 0 && deltaY === 0) return;
		for (let p = p1; p <= p2; p++) if (p !== ref) {
			outPoints[p].x += deltaX;
			outPoints[p].y += deltaY;
		}
	}
	getAdvanceAdjustment(gid, table) {
		let outerIndex, innerIndex;
		if (table.advanceWidthMapping) {
			const { mapCount, mapData } = table.advanceWidthMapping;
			const idx = gid >= mapCount ? mapCount - 1 : gid;
			({outerIndex, innerIndex} = mapData[idx]);
		} else {
			outerIndex = 0;
			innerIndex = gid;
		}
		return this.getDelta(table.itemVariationStore, outerIndex, innerIndex);
	}
	getDelta(itemStore, outerIndex, innerIndex) {
		const varData = itemStore.itemVariationData[outerIndex];
		if (outerIndex >= itemStore.itemVariationData.length || innerIndex >= varData.deltaSets.length) return 0;
		const deltaSet = varData.deltaSets[innerIndex];
		const blendVector = this.getBlendVector(itemStore, outerIndex);
		let netAdjustment = 0;
		for (let master = 0; master < varData.regionIndexCount; master++) netAdjustment += deltaSet.deltas[master] * blendVector[master];
		return netAdjustment;
	}
	getBlendVector(itemStore, outerIndex) {
		const varData = itemStore.itemVariationData[outerIndex];
		if (this.#blendVectors.has(varData)) return this.#blendVectors.get(varData);
		const normalizedCoords = this.#normalizedCoords;
		const blendVector = [];
		for (let master = 0; master < varData.regionIndexCount; master++) {
			let scalar = 1;
			const regionIndex = varData.regionIndexes[master];
			const axes = itemStore.variationRegionList.variationRegions[regionIndex];
			for (let j = 0; j < axes.length; j++) {
				const axis = axes[j];
				let axisScalar;
				if (axis.startCoord > axis.peakCoord || axis.peakCoord > axis.endCoord) axisScalar = 1;
				else if (axis.startCoord < 0 && axis.endCoord > 0 && axis.peakCoord !== 0) axisScalar = 1;
				else if (axis.peakCoord === 0) axisScalar = 1;
				else if (normalizedCoords[j] < axis.startCoord || normalizedCoords[j] > axis.endCoord) axisScalar = 0;
				else if (normalizedCoords[j] === axis.peakCoord) axisScalar = 1;
				else if (normalizedCoords[j] < axis.peakCoord) axisScalar = (normalizedCoords[j] - axis.startCoord + Number.EPSILON) / (axis.peakCoord - axis.startCoord + Number.EPSILON);
				else axisScalar = (axis.endCoord - normalizedCoords[j] + Number.EPSILON) / (axis.endCoord - axis.peakCoord + Number.EPSILON);
				scalar *= axisScalar;
			}
			blendVector[master] = scalar;
		}
		this.#blendVectors.set(varData, blendVector);
		return blendVector;
	}
};

//#endregion
//#region src/TTFFont.ts
/**
* This is the base class for all SFNT-based font formats in fontkitten.
* It supports TrueType, and PostScript glyphs, and several color glyph formats.
*/
var TTFFont = class TTFFont {
	type = "TTF";
	isCollection = false;
	stream;
	#variationCoords;
	#directoryPos;
	#tables = {};
	_glyphs = {};
	directory;
	"OS/2";
	"hhea";
	static probe(buffer) {
		const format = asciiDecoder.decode(buffer.slice(0, 4));
		return format === "true" || format === "OTTO" || format === String.fromCharCode(0, 1, 0, 0);
	}
	constructor(stream, variationCoords = null) {
		this.stream = stream;
		this.#variationCoords = variationCoords;
		this.#directoryPos = this.stream.pos;
		this.directory = this._decodeDirectory();
		for (const tag in this.directory.tables) {
			const table = this.directory.tables[tag];
			if (tables_default[tag] && table.length > 0) Object.defineProperty(this, tag, { get: this.#getTable.bind(this, table) });
		}
	}
	#getTable(table) {
		try {
			this.#tables[table.tag] ??= this._decodeTable(table);
		} catch {}
		return this.#tables[table.tag];
	}
	_getTableStream(tag) {
		const table = this.directory.tables[tag];
		if (table) {
			this.stream.pos = table.offset;
			return this.stream;
		}
		return null;
	}
	_decodeDirectory() {
		return directory_default.decode(this.stream, { _startOffset: 0 });
	}
	_decodeTable(table) {
		const pos = this.stream.pos;
		const stream = this._getTableStream(table.tag);
		const result = tables_default[table.tag].decode(stream, this, table.length);
		this.stream.pos = pos;
		return result;
	}
	/**
	* Gets a string from the font's `name` table
	*/
	getName(key) {
		const record = this.name?.records[key];
		return record?.["en"] || record?.[Object.keys(record)[0]] || null;
	}
	/**
	* The unique PostScript name for this font, e.g. "Helvetica-Bold"
	*/
	get postscriptName() {
		return this.getName("postscriptName");
	}
	/**
	* The font's full name, e.g. "Helvetica Bold"
	*/
	get fullName() {
		return this.getName("fullName");
	}
	/**
	* The font's family name, e.g. "Helvetica"
	*/
	get familyName() {
		return this.getName("fontFamily");
	}
	/**
	* The font's sub-family, e.g. "Bold".
	*/
	get subfamilyName() {
		return this.getName("fontSubfamily");
	}
	/**
	* The font's copyright information
	*/
	get copyright() {
		return this.getName("copyright");
	}
	/**
	* The font's version number
	*/
	get version() {
		return this.getName("version");
	}
	/**
	* The font’s [ascender](https://en.wikipedia.org/wiki/Ascender_(typography))
	*/
	get ascent() {
		return this.hhea.ascent;
	}
	/**
	* The font’s [descender](https://en.wikipedia.org/wiki/Descender)
	*/
	get descent() {
		return this.hhea.descent;
	}
	/**
	* The amount of space that should be included between lines
	*/
	get lineGap() {
		return this.hhea.lineGap;
	}
	/**
	* The offset from the normal underline position that should be used
	*/
	get underlinePosition() {
		return this.post.underlinePosition;
	}
	/**
	* The weight of the underline that should be used
	*/
	get underlineThickness() {
		return this.post.underlineThickness;
	}
	/**
	* If this is an italic font, the angle the cursor should be drawn at to match the font design
	*/
	get italicAngle() {
		return this.post.italicAngle;
	}
	/**
	* The height of capital letters above the baseline.
	* See [here](https://en.wikipedia.org/wiki/Cap_height) for more details.
	*/
	get capHeight() {
		let os2 = this["OS/2"];
		return os2 ? os2.capHeight : this.ascent;
	}
	/**
	* The height of lower case letters in the font.
	* See [here](https://en.wikipedia.org/wiki/X-height) for more details.
	*/
	get xHeight() {
		return this["OS/2"]?.xHeight ?? 0;
	}
	/**
	* The number of glyphs in the font.
	*/
	get numGlyphs() {
		return this.maxp.numGlyphs;
	}
	/**
	* The size of the font’s internal coordinate grid
	*/
	get unitsPerEm() {
		return this.head.unitsPerEm;
	}
	/**
	* The font’s bounding box, i.e. the box that encloses all glyphs in the font.
	*/
	get bbox() {
		return Object.freeze(new BBox(this.head.xMin, this.head.yMin, this.head.xMax, this.head.yMax));
	}
	get _cmapProcessor() {
		return new CmapProcessor(this.cmap);
	}
	/**
	* An array of all of the unicode code points supported by the font.
	*/
	get characterSet() {
		return this._cmapProcessor.getCharacterSet();
	}
	/**
	* Returns whether there is glyph in the font for the given unicode code point.
	*
	* @param {number} codePoint
	* @return {boolean}
	*/
	hasGlyphForCodePoint(codePoint) {
		return !!this._cmapProcessor.lookup(codePoint);
	}
	/**
	* Maps a single unicode code point to a Glyph object.
	* Does not perform any advanced substitutions (there is no context to do so).
	*/
	glyphForCodePoint(codePoint) {
		return this.getGlyph(this._cmapProcessor.lookup(codePoint), [codePoint]);
	}
	/**
	* Returns an array of Glyph objects for the given string.
	* This is only a one-to-one mapping from characters to glyphs.
	*/
	glyphsForString(string) {
		const glyphs = [];
		const len = string.length;
		let idx = 0;
		let last = -1;
		let state = -1;
		while (idx <= len) {
			let code = 0;
			let nextState = 0;
			if (idx < len) {
				code = string.charCodeAt(idx++);
				if (55296 <= code && code <= 56319 && idx < len) {
					const next = string.charCodeAt(idx);
					if (56320 <= next && next <= 57343) {
						idx++;
						code = ((code & 1023) << 10) + (next & 1023) + 65536;
					}
				}
				nextState = 65024 <= code && code <= 65039 || 917760 <= code && code <= 917999 ? 1 : 0;
			} else idx++;
			if (state === 0 && nextState === 1) glyphs.push(this.getGlyph(this._cmapProcessor.lookup(last, code), [last, code]));
			else if (state === 0 && nextState === 0) glyphs.push(this.glyphForCodePoint(last));
			last = code;
			state = nextState;
		}
		return glyphs;
	}
	_getBaseGlyph(glyph, characters = []) {
		if (!this._glyphs[glyph]) {
			if (this.directory.tables.glyf) this._glyphs[glyph] = new TTFGlyph(glyph, characters, this);
			else if (this.directory.tables["CFF "] || this.directory.tables.CFF2) this._glyphs[glyph] = new CFFGlyph(glyph, characters, this);
		}
		return this._glyphs[glyph] || null;
	}
	/**
	* Returns a glyph object for the given glyph id.
	* You can pass the array of code points this glyph represents for
	* your use later, and it will be stored in the glyph object.
	*/
	getGlyph(glyph, characters = []) {
		if (!this._glyphs[glyph]) if (this.directory.tables.sbix) this._glyphs[glyph] = new SBIXGlyph(glyph, characters, this);
		else if (this.directory.tables.COLR && this.directory.tables.CPAL) this._glyphs[glyph] = new COLRGlyph(glyph, characters, this);
		else this._getBaseGlyph(glyph, characters);
		return this._glyphs[glyph] || null;
	}
	/**
	* Returns an object describing the available variation axes
	* that this font supports. Keys are setting tags, and values
	* contain the axis name, range, and default value.
	*/
	get variationAxes() {
		return Object.fromEntries(this.fvar?.axis.map((axis) => [axis.axisTag.trim(), {
			name: axis.name.en,
			min: axis.minValue,
			default: axis.defaultValue,
			max: axis.maxValue
		}]) || []);
	}
	/**
	* Returns an object describing the named variation instances
	* that the font designer has specified. Keys are variation names
	* and values are the variation settings for this instance.
	*/
	get namedVariations() {
		return Object.fromEntries(this.fvar?.instance.map((instance) => {
			const settings = {};
			for (let i = 0; i < this.fvar.axis.length; i++) {
				const axis = this.fvar.axis[i];
				settings[axis.axisTag.trim()] = instance.coord[i];
			}
			return [instance.name.en, settings];
		}) || []);
	}
	/**
	* Returns a new font with the given variation settings applied.
	* Settings can either be an instance name, or an object containing
	* variation tags as specified by the `variationAxes` property.
	*
	* @param {object} settings
	* @return {TTFFont}
	*/
	getVariation(settings) {
		if (!(this.directory.tables.fvar && (this.directory.tables.gvar && this.directory.tables.glyf || this.directory.tables.CFF2))) throw new Error("Variations require a font with the fvar, gvar and glyf, or CFF2 tables.");
		if (typeof settings === "string") settings = this.namedVariations[settings];
		if (typeof settings !== "object") throw new Error("Variation settings must be either a variation name or settings object.");
		const coords = this.fvar.axis.map((axis) => {
			const axisTag = axis.axisTag.trim();
			if (axisTag in settings) return Math.max(axis.minValue, Math.min(axis.maxValue, settings[axisTag]));
			else return axis.defaultValue;
		});
		const stream = new DecodeStream(this.stream.buffer);
		stream.pos = this.#directoryPos;
		const font = new TTFFont(stream, coords);
		font.#tables = this.#tables;
		return font;
	}
	get _variationProcessor() {
		if (!this.fvar) return null;
		let variationCoords = this.#variationCoords;
		if (!variationCoords) {
			if (!this.CFF2) return null;
			variationCoords = this.fvar.axis.map((axis) => axis.defaultValue);
		}
		return new GlyphVariationProcessor(this, variationCoords);
	}
	getFont(name) {
		return this.getVariation(name);
	}
};
__decorate([cache], TTFFont.prototype, "bbox", null);
__decorate([cache], TTFFont.prototype, "_cmapProcessor", null);
__decorate([cache], TTFFont.prototype, "characterSet", null);
__decorate([cache], TTFFont.prototype, "variationAxes", null);
__decorate([cache], TTFFont.prototype, "namedVariations", null);
__decorate([cache], TTFFont.prototype, "_variationProcessor", null);

//#endregion
//#region src/tables/WOFFDirectory.ts
let WOFFDirectoryEntry = new Struct({
	tag: new StringT(4),
	offset: new Pointer(uint32, "void", { type: "global" }),
	compLength: uint32,
	length: uint32,
	origChecksum: uint32
});
let WOFFDirectory = new Struct({
	tag: new StringT(4),
	flavor: uint32,
	length: uint32,
	numTables: uint16,
	reserved: new Reserved(uint16),
	totalSfntSize: uint32,
	majorVersion: uint16,
	minorVersion: uint16,
	metaOffset: uint32,
	metaLength: uint32,
	metaOrigLength: uint32,
	privOffset: uint32,
	privLength: uint32,
	tables: new ArrayT(WOFFDirectoryEntry, "numTables")
});
WOFFDirectory.process = function() {
	this.tables = Object.fromEntries(this.tables.map((table) => [table.tag, table]));
};
var WOFFDirectory_default = WOFFDirectory;

//#endregion
//#region src/WOFFFont.ts
var WOFFFont = class extends TTFFont {
	type = "WOFF";
	static probe(buffer) {
		return asciiDecoder.decode(buffer.slice(0, 4)) === "wOFF";
	}
	_decodeDirectory() {
		return WOFFDirectory_default.decode(this.stream, { _startOffset: 0 });
	}
	_getTableStream(tag) {
		const table = this.directory.tables[tag];
		if (table) {
			this.stream.pos = table.offset;
			if (table.compLength < table.length) {
				this.stream.pos += 2;
				const outBuffer = new Uint8Array(table.length);
				const buf = inflate(this.stream.readBuffer(table.compLength - 2), outBuffer);
				return new DecodeStream(buf);
			} else return this.stream;
		}
		return null;
	}
};

//#endregion
//#region src/vendor/brotliDecode.ts
const MAX_HUFFMAN_TABLE_SIZE = Int32Array.from([
	256,
	402,
	436,
	468,
	500,
	534,
	566,
	598,
	630,
	662,
	694,
	726,
	758,
	790,
	822,
	854,
	886,
	920,
	952,
	984,
	1016,
	1048,
	1080
]);
const CODE_LENGTH_CODE_ORDER = Int32Array.from([
	1,
	2,
	3,
	4,
	0,
	5,
	17,
	6,
	16,
	7,
	8,
	9,
	10,
	11,
	12,
	13,
	14,
	15
]);
const DISTANCE_SHORT_CODE_INDEX_OFFSET = Int32Array.from([
	0,
	3,
	2,
	1,
	0,
	0,
	0,
	0,
	0,
	0,
	3,
	3,
	3,
	3,
	3,
	3
]);
const DISTANCE_SHORT_CODE_VALUE_OFFSET = Int32Array.from([
	0,
	0,
	0,
	0,
	-1,
	1,
	-2,
	2,
	-3,
	3,
	-1,
	1,
	-2,
	2,
	-3,
	3
]);
const FIXED_TABLE = Int32Array.from([
	131072,
	131076,
	131075,
	196610,
	131072,
	131076,
	131075,
	262145,
	131072,
	131076,
	131075,
	196610,
	131072,
	131076,
	131075,
	262149
]);
const BLOCK_LENGTH_OFFSET = Int32Array.from([
	1,
	5,
	9,
	13,
	17,
	25,
	33,
	41,
	49,
	65,
	81,
	97,
	113,
	145,
	177,
	209,
	241,
	305,
	369,
	497,
	753,
	1265,
	2289,
	4337,
	8433,
	16625
]);
const BLOCK_LENGTH_N_BITS = Int32Array.from([
	2,
	2,
	2,
	2,
	3,
	3,
	3,
	3,
	4,
	4,
	4,
	4,
	5,
	5,
	5,
	5,
	6,
	6,
	7,
	8,
	9,
	10,
	11,
	12,
	13,
	24
]);
const INSERT_LENGTH_N_BITS = Int16Array.from([
	0,
	0,
	0,
	0,
	0,
	0,
	1,
	1,
	2,
	2,
	3,
	3,
	4,
	4,
	5,
	5,
	6,
	7,
	8,
	9,
	10,
	12,
	14,
	24
]);
const COPY_LENGTH_N_BITS = Int16Array.from([
	0,
	0,
	0,
	0,
	0,
	0,
	0,
	0,
	1,
	1,
	2,
	2,
	3,
	3,
	4,
	4,
	5,
	5,
	6,
	7,
	8,
	9,
	10,
	24
]);
const CMD_LOOKUP = new Int16Array(2816);
unpackCommandLookupTable(CMD_LOOKUP);
function log2floor(i) {
	let result = -1;
	let step = 16;
	let v = i;
	while (step > 0) {
		let next = v >> step;
		if (next !== 0) {
			result += step;
			v = next;
		}
		step = step >> 1;
	}
	return result + v;
}
function calculateDistanceAlphabetSize(npostfix, ndirect, maxndistbits) {
	return 16 + ndirect + 2 * (maxndistbits << npostfix);
}
function calculateDistanceAlphabetLimit(s, maxDistance, npostfix, ndirect) {
	if (maxDistance < ndirect + (2 << npostfix)) return makeError(s, -23);
	const offset = (maxDistance - ndirect >> npostfix) + 4;
	const ndistbits = log2floor(offset) - 1;
	return ((ndistbits - 1 << 1 | offset >> ndistbits & 1) - 1 << npostfix) + (1 << npostfix) + ndirect + 16;
}
function unpackCommandLookupTable(cmdLookup) {
	const insertLengthOffsets = new Int32Array(24);
	const copyLengthOffsets = new Int32Array(24);
	copyLengthOffsets[0] = 2;
	for (let i = 0; i < 23; ++i) {
		insertLengthOffsets[i + 1] = insertLengthOffsets[i] + (1 << INSERT_LENGTH_N_BITS[i]);
		copyLengthOffsets[i + 1] = copyLengthOffsets[i] + (1 << COPY_LENGTH_N_BITS[i]);
	}
	for (let cmdCode = 0; cmdCode < 704; ++cmdCode) {
		let rangeIdx = cmdCode >> 6;
		let distanceContextOffset = -4;
		if (rangeIdx >= 2) {
			rangeIdx -= 2;
			distanceContextOffset = 0;
		}
		const insertCode = (170064 >> rangeIdx * 2 & 3) << 3 | cmdCode >> 3 & 7;
		const copyCode = (156228 >> rangeIdx * 2 & 3) << 3 | cmdCode & 7;
		const copyLengthOffset = copyLengthOffsets[copyCode];
		const distanceContext = distanceContextOffset + Math.min(copyLengthOffset, 5) - 2;
		const index = cmdCode * 4;
		cmdLookup[index] = INSERT_LENGTH_N_BITS[insertCode] | COPY_LENGTH_N_BITS[copyCode] << 8;
		cmdLookup[index + 1] = insertLengthOffsets[insertCode];
		cmdLookup[index + 2] = copyLengthOffsets[copyCode];
		cmdLookup[index + 3] = distanceContext;
	}
}
function decodeWindowBits(s) {
	const largeWindowEnabled = s.isLargeWindow;
	s.isLargeWindow = 0;
	if (s.bitOffset >= 16) {
		s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
		s.bitOffset -= 16;
	}
	if (readFewBits(s, 1) === 0) return 16;
	let n = readFewBits(s, 3);
	if (n !== 0) return 17 + n;
	n = readFewBits(s, 3);
	if (n !== 0) {
		if (n === 1) {
			if (largeWindowEnabled === 0) return -1;
			s.isLargeWindow = 1;
			if (readFewBits(s, 1) === 1) return -1;
			n = readFewBits(s, 6);
			if (n < 10 || n > 30) return -1;
			return n;
		}
		return 8 + n;
	}
	return 17;
}
function initState(s) {
	if (s.runningState !== 0) return makeError(s, -26);
	s.blockTrees = new Int32Array(3091);
	s.blockTrees[0] = 7;
	s.distRbIdx = 3;
	let result = calculateDistanceAlphabetLimit(s, 2147483644, 3, 120);
	if (result < 0) return result;
	const maxDistanceAlphabetLimit = result;
	s.distExtraBits = new Int8Array(maxDistanceAlphabetLimit);
	s.distOffset = new Int32Array(maxDistanceAlphabetLimit);
	result = initBitReader(s);
	if (result < 0) return result;
	s.runningState = 1;
	return 0;
}
function close(s) {
	if (s.runningState === 0) return makeError(s, -25);
	if (s.runningState > 0) s.runningState = 11;
	return 0;
}
function decodeVarLenUnsignedByte(s) {
	if (s.bitOffset >= 16) {
		s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
		s.bitOffset -= 16;
	}
	if (readFewBits(s, 1) !== 0) {
		const n = readFewBits(s, 3);
		if (n === 0) return 1;
		return readFewBits(s, n) + (1 << n);
	}
	return 0;
}
function decodeMetaBlockLength(s) {
	if (s.bitOffset >= 16) {
		s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
		s.bitOffset -= 16;
	}
	s.inputEnd = readFewBits(s, 1);
	s.metaBlockLength = 0;
	s.isUncompressed = 0;
	s.isMetadata = 0;
	if (s.inputEnd !== 0 && readFewBits(s, 1) !== 0) return 0;
	const sizeNibbles = readFewBits(s, 2) + 4;
	if (sizeNibbles === 7) {
		s.isMetadata = 1;
		if (readFewBits(s, 1) !== 0) return makeError(s, -6);
		const sizeBytes = readFewBits(s, 2);
		if (sizeBytes === 0) return 0;
		for (let i = 0; i < sizeBytes; ++i) {
			if (s.bitOffset >= 16) {
				s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
				s.bitOffset -= 16;
			}
			const bits = readFewBits(s, 8);
			if (bits === 0 && i + 1 === sizeBytes && sizeBytes > 1) return makeError(s, -8);
			s.metaBlockLength += bits << i * 8;
		}
	} else for (let i = 0; i < sizeNibbles; ++i) {
		if (s.bitOffset >= 16) {
			s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
			s.bitOffset -= 16;
		}
		const bits = readFewBits(s, 4);
		if (bits === 0 && i + 1 === sizeNibbles && sizeNibbles > 4) return makeError(s, -8);
		s.metaBlockLength += bits << i * 4;
	}
	s.metaBlockLength++;
	if (s.inputEnd === 0) s.isUncompressed = readFewBits(s, 1);
	return 0;
}
function readSymbol(tableGroup, tableIdx, s) {
	let offset = tableGroup[tableIdx];
	const v = s.accumulator32 >>> s.bitOffset;
	offset += v & 255;
	const bits = tableGroup[offset] >> 16;
	const sym = tableGroup[offset] & 65535;
	if (bits <= 8) {
		s.bitOffset += bits;
		return sym;
	}
	offset += sym;
	const mask = (1 << bits) - 1;
	offset += (v & mask) >>> 8;
	s.bitOffset += (tableGroup[offset] >> 16) + 8;
	return tableGroup[offset] & 65535;
}
function readBlockLength(tableGroup, tableIdx, s) {
	if (s.bitOffset >= 16) {
		s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
		s.bitOffset -= 16;
	}
	const code = readSymbol(tableGroup, tableIdx, s);
	const n = BLOCK_LENGTH_N_BITS[code];
	if (s.bitOffset >= 16) {
		s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
		s.bitOffset -= 16;
	}
	return BLOCK_LENGTH_OFFSET[code] + (n <= 16 ? readFewBits(s, n) : readManyBits(s, n));
}
function moveToFront(v, index) {
	let i = index;
	const value = v[i];
	while (i > 0) {
		v[i] = v[i - 1];
		i--;
	}
	v[0] = value;
}
function inverseMoveToFrontTransform(v, vLen) {
	const mtf = new Int32Array(256);
	for (let i = 0; i < 256; ++i) mtf[i] = i;
	for (let i = 0; i < vLen; ++i) {
		const index = v[i] & 255;
		v[i] = mtf[index];
		if (index !== 0) moveToFront(mtf, index);
	}
}
function readHuffmanCodeLengths(codeLengthCodeLengths, numSymbols, codeLengths, s) {
	let symbol = 0;
	let prevCodeLen = 8;
	let repeat = 0;
	let repeatCodeLen = 0;
	let space = 32768;
	const table = new Int32Array(33);
	buildHuffmanTable(table, table.length - 1, 5, codeLengthCodeLengths, 18);
	while (symbol < numSymbols && space > 0) {
		if (s.halfOffset > 2030) {
			const result = readMoreInput(s);
			if (result < 0) return result;
		}
		if (s.bitOffset >= 16) {
			s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
			s.bitOffset -= 16;
		}
		const p = s.accumulator32 >>> s.bitOffset & 31;
		s.bitOffset += table[p] >> 16;
		const codeLen = table[p] & 65535;
		if (codeLen < 16) {
			repeat = 0;
			codeLengths[symbol++] = codeLen;
			if (codeLen !== 0) {
				prevCodeLen = codeLen;
				space -= 32768 >> codeLen;
			}
		} else {
			const extraBits = codeLen - 14;
			let newLen = 0;
			if (codeLen === 16) newLen = prevCodeLen;
			if (repeatCodeLen !== newLen) {
				repeat = 0;
				repeatCodeLen = newLen;
			}
			const oldRepeat = repeat;
			if (repeat > 0) {
				repeat -= 2;
				repeat = repeat << extraBits;
			}
			if (s.bitOffset >= 16) {
				s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
				s.bitOffset -= 16;
			}
			repeat += readFewBits(s, extraBits) + 3;
			const repeatDelta = repeat - oldRepeat;
			if (symbol + repeatDelta > numSymbols) return makeError(s, -2);
			for (let i = 0; i < repeatDelta; ++i) codeLengths[symbol++] = repeatCodeLen;
			if (repeatCodeLen !== 0) space -= repeatDelta << 15 - repeatCodeLen;
		}
	}
	if (space !== 0) return makeError(s, -18);
	codeLengths.fill(0, symbol, numSymbols);
	return 0;
}
function checkDupes(s, symbols, length) {
	for (let i = 0; i < length - 1; ++i) for (let j = i + 1; j < length; ++j) if (symbols[i] === symbols[j]) return makeError(s, -7);
	return 0;
}
function readSimpleHuffmanCode(alphabetSizeMax, alphabetSizeLimit, tableGroup, tableIdx, s) {
	const codeLengths = new Int32Array(alphabetSizeLimit);
	const symbols = new Int32Array(4);
	const maxBits = 1 + log2floor(alphabetSizeMax - 1);
	const numSymbols = readFewBits(s, 2) + 1;
	for (let i = 0; i < numSymbols; ++i) {
		if (s.bitOffset >= 16) {
			s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
			s.bitOffset -= 16;
		}
		const symbol = readFewBits(s, maxBits);
		if (symbol >= alphabetSizeLimit) return makeError(s, -15);
		symbols[i] = symbol;
	}
	const result = checkDupes(s, symbols, numSymbols);
	if (result < 0) return result;
	let histogramId = numSymbols;
	if (numSymbols === 4) histogramId += readFewBits(s, 1);
	switch (histogramId) {
		case 1:
			codeLengths[symbols[0]] = 1;
			break;
		case 2:
			codeLengths[symbols[0]] = 1;
			codeLengths[symbols[1]] = 1;
			break;
		case 3:
			codeLengths[symbols[0]] = 1;
			codeLengths[symbols[1]] = 2;
			codeLengths[symbols[2]] = 2;
			break;
		case 4:
			codeLengths[symbols[0]] = 2;
			codeLengths[symbols[1]] = 2;
			codeLengths[symbols[2]] = 2;
			codeLengths[symbols[3]] = 2;
			break;
		case 5:
			codeLengths[symbols[0]] = 1;
			codeLengths[symbols[1]] = 2;
			codeLengths[symbols[2]] = 3;
			codeLengths[symbols[3]] = 3;
			break;
		default: break;
	}
	return buildHuffmanTable(tableGroup, tableIdx, 8, codeLengths, alphabetSizeLimit);
}
function readComplexHuffmanCode(alphabetSizeLimit, skip, tableGroup, tableIdx, s) {
	const codeLengths = new Int32Array(alphabetSizeLimit);
	const codeLengthCodeLengths = new Int32Array(18);
	let space = 32;
	let numCodes = 0;
	for (let i = skip; i < 18; ++i) {
		const codeLenIdx = CODE_LENGTH_CODE_ORDER[i];
		if (s.bitOffset >= 16) {
			s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
			s.bitOffset -= 16;
		}
		const p = s.accumulator32 >>> s.bitOffset & 15;
		s.bitOffset += FIXED_TABLE[p] >> 16;
		const v = FIXED_TABLE[p] & 65535;
		codeLengthCodeLengths[codeLenIdx] = v;
		if (v !== 0) {
			space -= 32 >> v;
			numCodes++;
			if (space <= 0) break;
		}
	}
	if (space !== 0 && numCodes !== 1) return makeError(s, -4);
	const result = readHuffmanCodeLengths(codeLengthCodeLengths, alphabetSizeLimit, codeLengths, s);
	if (result < 0) return result;
	return buildHuffmanTable(tableGroup, tableIdx, 8, codeLengths, alphabetSizeLimit);
}
function readHuffmanCode(alphabetSizeMax, alphabetSizeLimit, tableGroup, tableIdx, s) {
	if (s.halfOffset > 2030) {
		const result = readMoreInput(s);
		if (result < 0) return result;
	}
	if (s.bitOffset >= 16) {
		s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
		s.bitOffset -= 16;
	}
	const simpleCodeOrSkip = readFewBits(s, 2);
	if (simpleCodeOrSkip === 1) return readSimpleHuffmanCode(alphabetSizeMax, alphabetSizeLimit, tableGroup, tableIdx, s);
	return readComplexHuffmanCode(alphabetSizeLimit, simpleCodeOrSkip, tableGroup, tableIdx, s);
}
function decodeContextMap(contextMapSize, contextMap, s) {
	let result;
	if (s.halfOffset > 2030) {
		result = readMoreInput(s);
		if (result < 0) return result;
	}
	const numTrees = decodeVarLenUnsignedByte(s) + 1;
	if (numTrees === 1) {
		contextMap.fill(0, 0, contextMapSize);
		return numTrees;
	}
	if (s.bitOffset >= 16) {
		s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
		s.bitOffset -= 16;
	}
	const useRleForZeros = readFewBits(s, 1);
	let maxRunLengthPrefix = 0;
	if (useRleForZeros !== 0) maxRunLengthPrefix = readFewBits(s, 4) + 1;
	const alphabetSize = numTrees + maxRunLengthPrefix;
	const tableSize = MAX_HUFFMAN_TABLE_SIZE[alphabetSize + 31 >> 5];
	const table = new Int32Array(tableSize + 1);
	const tableIdx = table.length - 1;
	result = readHuffmanCode(alphabetSize, alphabetSize, table, tableIdx, s);
	if (result < 0) return result;
	let i = 0;
	while (i < contextMapSize) {
		if (s.halfOffset > 2030) {
			result = readMoreInput(s);
			if (result < 0) return result;
		}
		if (s.bitOffset >= 16) {
			s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
			s.bitOffset -= 16;
		}
		const code = readSymbol(table, tableIdx, s);
		if (code === 0) {
			contextMap[i] = 0;
			i++;
		} else if (code <= maxRunLengthPrefix) {
			if (s.bitOffset >= 16) {
				s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
				s.bitOffset -= 16;
			}
			let reps = (1 << code) + readFewBits(s, code);
			while (reps !== 0) {
				if (i >= contextMapSize) return makeError(s, -3);
				contextMap[i] = 0;
				i++;
				reps--;
			}
		} else {
			contextMap[i] = code - maxRunLengthPrefix;
			i++;
		}
	}
	if (s.bitOffset >= 16) {
		s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
		s.bitOffset -= 16;
	}
	if (readFewBits(s, 1) === 1) inverseMoveToFrontTransform(contextMap, contextMapSize);
	return numTrees;
}
function decodeBlockTypeAndLength(s, treeType, numBlockTypes) {
	const ringBuffers = s.rings;
	const offset = 4 + treeType * 2;
	if (s.bitOffset >= 16) {
		s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
		s.bitOffset -= 16;
	}
	let blockType = readSymbol(s.blockTrees, 2 * treeType, s);
	const result = readBlockLength(s.blockTrees, 2 * treeType + 1, s);
	if (blockType === 1) blockType = ringBuffers[offset + 1] + 1;
	else if (blockType === 0) blockType = ringBuffers[offset];
	else blockType -= 2;
	if (blockType >= numBlockTypes) blockType -= numBlockTypes;
	ringBuffers[offset] = ringBuffers[offset + 1];
	ringBuffers[offset + 1] = blockType;
	return result;
}
function decodeLiteralBlockSwitch(s) {
	s.literalBlockLength = decodeBlockTypeAndLength(s, 0, s.numLiteralBlockTypes);
	const literalBlockType = s.rings[5];
	s.contextMapSlice = literalBlockType << 6;
	s.literalTreeIdx = s.contextMap[s.contextMapSlice] & 255;
	s.contextLookupOffset1 = s.contextModes[literalBlockType] << 9;
	s.contextLookupOffset2 = s.contextLookupOffset1 + 256;
}
function decodeCommandBlockSwitch(s) {
	s.commandBlockLength = decodeBlockTypeAndLength(s, 1, s.numCommandBlockTypes);
	s.commandTreeIdx = s.rings[7];
}
function decodeDistanceBlockSwitch(s) {
	s.distanceBlockLength = decodeBlockTypeAndLength(s, 2, s.numDistanceBlockTypes);
	s.distContextMapSlice = s.rings[9] << 2;
}
function maybeReallocateRingBuffer(s) {
	let newSize = s.maxRingBufferSize;
	if (newSize > s.expectedTotalSize) {
		const minimalNewSize = s.expectedTotalSize;
		while (newSize >> 1 > minimalNewSize) newSize = newSize >> 1;
		if (s.inputEnd === 0 && newSize < 16384 && s.maxRingBufferSize >= 16384) newSize = 16384;
	}
	if (newSize <= s.ringBufferSize) return;
	const ringBufferSizeWithSlack = newSize + 37;
	const newBuffer = new Int8Array(ringBufferSizeWithSlack);
	const oldBuffer = s.ringBuffer;
	if (oldBuffer.length !== 0) newBuffer.set(oldBuffer.subarray(0, s.ringBufferSize), 0);
	s.ringBuffer = newBuffer;
	s.ringBufferSize = newSize;
}
function readNextMetablockHeader(s) {
	if (s.inputEnd !== 0) {
		s.nextRunningState = 10;
		s.runningState = 12;
		return 0;
	}
	s.literalTreeGroup = new Int32Array(0);
	s.commandTreeGroup = new Int32Array(0);
	s.distanceTreeGroup = new Int32Array(0);
	let result;
	if (s.halfOffset > 2030) {
		result = readMoreInput(s);
		if (result < 0) return result;
	}
	result = decodeMetaBlockLength(s);
	if (result < 0) return result;
	if (s.metaBlockLength === 0 && s.isMetadata === 0) return 0;
	if (s.isUncompressed !== 0 || s.isMetadata !== 0) {
		result = jumpToByteBoundary(s);
		if (result < 0) return result;
		if (s.isMetadata === 0) s.runningState = 6;
		else s.runningState = 5;
	} else s.runningState = 3;
	if (s.isMetadata !== 0) return 0;
	s.expectedTotalSize += s.metaBlockLength;
	if (s.expectedTotalSize > 1 << 30) s.expectedTotalSize = 1 << 30;
	if (s.ringBufferSize < s.maxRingBufferSize) maybeReallocateRingBuffer(s);
	return 0;
}
function readMetablockPartition(s, treeType, numBlockTypes) {
	let offset = s.blockTrees[2 * treeType];
	if (numBlockTypes <= 1) {
		s.blockTrees[2 * treeType + 1] = offset;
		s.blockTrees[2 * treeType + 2] = offset;
		return 1 << 28;
	}
	const blockTypeAlphabetSize = numBlockTypes + 2;
	let result = readHuffmanCode(blockTypeAlphabetSize, blockTypeAlphabetSize, s.blockTrees, 2 * treeType, s);
	if (result < 0) return result;
	offset += result;
	s.blockTrees[2 * treeType + 1] = offset;
	const blockLengthAlphabetSize = 26;
	result = readHuffmanCode(blockLengthAlphabetSize, blockLengthAlphabetSize, s.blockTrees, 2 * treeType + 1, s);
	if (result < 0) return result;
	offset += result;
	s.blockTrees[2 * treeType + 2] = offset;
	return readBlockLength(s.blockTrees, 2 * treeType + 1, s);
}
function calculateDistanceLut(s, alphabetSizeLimit) {
	const distExtraBits = s.distExtraBits;
	const distOffset = s.distOffset;
	const npostfix = s.distancePostfixBits;
	const ndirect = s.numDirectDistanceCodes;
	const postfix = 1 << npostfix;
	let bits = 1;
	let half = 0;
	let i = 16;
	for (let j = 0; j < ndirect; ++j) {
		distExtraBits[i] = 0;
		distOffset[i] = j + 1;
		++i;
	}
	while (i < alphabetSizeLimit) {
		const base = ndirect + ((2 + half << bits) - 4 << npostfix) + 1;
		for (let j = 0; j < postfix; ++j) {
			distExtraBits[i] = bits;
			distOffset[i] = base + j;
			++i;
		}
		bits = bits + half;
		half = half ^ 1;
	}
}
function readMetablockHuffmanCodesAndContextMaps(s) {
	s.numLiteralBlockTypes = decodeVarLenUnsignedByte(s) + 1;
	let result = readMetablockPartition(s, 0, s.numLiteralBlockTypes);
	if (result < 0) return result;
	s.literalBlockLength = result;
	s.numCommandBlockTypes = decodeVarLenUnsignedByte(s) + 1;
	result = readMetablockPartition(s, 1, s.numCommandBlockTypes);
	if (result < 0) return result;
	s.commandBlockLength = result;
	s.numDistanceBlockTypes = decodeVarLenUnsignedByte(s) + 1;
	result = readMetablockPartition(s, 2, s.numDistanceBlockTypes);
	if (result < 0) return result;
	s.distanceBlockLength = result;
	if (s.halfOffset > 2030) {
		result = readMoreInput(s);
		if (result < 0) return result;
	}
	if (s.bitOffset >= 16) {
		s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
		s.bitOffset -= 16;
	}
	s.distancePostfixBits = readFewBits(s, 2);
	s.numDirectDistanceCodes = readFewBits(s, 4) << s.distancePostfixBits;
	s.contextModes = new Int8Array(s.numLiteralBlockTypes);
	let i = 0;
	while (i < s.numLiteralBlockTypes) {
		const limit = Math.min(i + 96, s.numLiteralBlockTypes);
		while (i < limit) {
			if (s.bitOffset >= 16) {
				s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
				s.bitOffset -= 16;
			}
			s.contextModes[i] = readFewBits(s, 2);
			i++;
		}
		if (s.halfOffset > 2030) {
			result = readMoreInput(s);
			if (result < 0) return result;
		}
	}
	const contextMapLength = s.numLiteralBlockTypes << 6;
	s.contextMap = new Int8Array(contextMapLength);
	result = decodeContextMap(contextMapLength, s.contextMap, s);
	if (result < 0) return result;
	const numLiteralTrees = result;
	s.trivialLiteralContext = 1;
	for (let j = 0; j < contextMapLength; ++j) if (s.contextMap[j] !== j >> 6) {
		s.trivialLiteralContext = 0;
		break;
	}
	s.distContextMap = new Int8Array(s.numDistanceBlockTypes << 2);
	result = decodeContextMap(s.numDistanceBlockTypes << 2, s.distContextMap, s);
	if (result < 0) return result;
	const numDistTrees = result;
	s.literalTreeGroup = new Int32Array(huffmanTreeGroupAllocSize(256, numLiteralTrees));
	result = decodeHuffmanTreeGroup(256, 256, numLiteralTrees, s, s.literalTreeGroup);
	if (result < 0) return result;
	s.commandTreeGroup = new Int32Array(huffmanTreeGroupAllocSize(704, s.numCommandBlockTypes));
	result = decodeHuffmanTreeGroup(704, 704, s.numCommandBlockTypes, s, s.commandTreeGroup);
	if (result < 0) return result;
	let distanceAlphabetSizeMax = calculateDistanceAlphabetSize(s.distancePostfixBits, s.numDirectDistanceCodes, 24);
	let distanceAlphabetSizeLimit = distanceAlphabetSizeMax;
	if (s.isLargeWindow === 1) {
		distanceAlphabetSizeMax = calculateDistanceAlphabetSize(s.distancePostfixBits, s.numDirectDistanceCodes, 62);
		result = calculateDistanceAlphabetLimit(s, 2147483644, s.distancePostfixBits, s.numDirectDistanceCodes);
		if (result < 0) return result;
		distanceAlphabetSizeLimit = result;
	}
	s.distanceTreeGroup = new Int32Array(huffmanTreeGroupAllocSize(distanceAlphabetSizeLimit, numDistTrees));
	result = decodeHuffmanTreeGroup(distanceAlphabetSizeMax, distanceAlphabetSizeLimit, numDistTrees, s, s.distanceTreeGroup);
	if (result < 0) return result;
	calculateDistanceLut(s, distanceAlphabetSizeLimit);
	s.contextMapSlice = 0;
	s.distContextMapSlice = 0;
	s.contextLookupOffset1 = s.contextModes[0] * 512;
	s.contextLookupOffset2 = s.contextLookupOffset1 + 256;
	s.literalTreeIdx = 0;
	s.commandTreeIdx = 0;
	s.rings[4] = 1;
	s.rings[5] = 0;
	s.rings[6] = 1;
	s.rings[7] = 0;
	s.rings[8] = 1;
	s.rings[9] = 0;
	return 0;
}
function copyUncompressedData(s) {
	throw new Error("copyUncompressedData not implemented");
}
function writeRingBuffer(s) {
	const toWrite = Math.min(s.outputLength - s.outputUsed, s.ringBufferBytesReady - s.ringBufferBytesWritten);
	if (toWrite !== 0) {
		s.output.set(s.ringBuffer.subarray(s.ringBufferBytesWritten, s.ringBufferBytesWritten + toWrite), s.outputOffset + s.outputUsed);
		s.outputUsed += toWrite;
		s.ringBufferBytesWritten += toWrite;
	}
	if (s.outputUsed < s.outputLength) return 0;
	return 2;
}
function huffmanTreeGroupAllocSize(alphabetSizeLimit, n) {
	return n + n * MAX_HUFFMAN_TABLE_SIZE[alphabetSizeLimit + 31 >> 5];
}
function decodeHuffmanTreeGroup(alphabetSizeMax, alphabetSizeLimit, n, s, group) {
	let next = n;
	for (let i = 0; i < n; ++i) {
		group[i] = next;
		const result = readHuffmanCode(alphabetSizeMax, alphabetSizeLimit, group, i, s);
		if (result < 0) return result;
		next += result;
	}
	return 0;
}
function calculateFence(s) {
	let result = s.ringBufferSize;
	if (s.isEager !== 0) result = Math.min(result, s.ringBufferBytesWritten + s.outputLength - s.outputUsed);
	return result;
}
function doUseDictionary(s, fence) {
	if (s.distance > 2147483644) return makeError(s, -9);
	const address = s.distance - s.maxDistance - 1 - s.cdTotalSize;
	if (address < 0) {
		const result = initializeCompoundDictionaryCopy(s, -address - 1, s.copyLength);
		if (result < 0) return result;
		s.runningState = 14;
	} else {
		const dictionaryData = data;
		const wordLength = s.copyLength;
		if (wordLength > 31) return makeError(s, -9);
		const shift = sizeBits[wordLength];
		if (shift === 0) return makeError(s, -9);
		let offset = offsets[wordLength];
		const wordIdx = address & (1 << shift) - 1;
		const transformIdx = address >> shift;
		offset += wordIdx * wordLength;
		const transforms = RFC_TRANSFORMS;
		if (transformIdx >= transforms.numTransforms) return makeError(s, -9);
		const len = transformDictionaryWord(s.ringBuffer, s.pos, dictionaryData, offset, wordLength, transforms, transformIdx);
		s.pos += len;
		s.metaBlockLength -= len;
		if (s.pos >= fence) {
			s.nextRunningState = 4;
			s.runningState = 12;
			return 0;
		}
		s.runningState = 4;
	}
	return 0;
}
function initializeCompoundDictionaryCopy(s, address, length) {
	throw new Error("initializeCompoundDictionaryCopy not implemented");
}
function copyFromCompoundDictionary(s, fence) {
	throw new Error("copyFromCompoundDictionary not implemented");
}
function decompress(s) {
	let result;
	if (s.runningState === 0) return makeError(s, -25);
	if (s.runningState < 0) return makeError(s, -28);
	if (s.runningState === 11) return makeError(s, -22);
	if (s.runningState === 1) {
		const windowBits = decodeWindowBits(s);
		if (windowBits === -1) return makeError(s, -11);
		s.maxRingBufferSize = 1 << windowBits;
		s.maxBackwardDistance = s.maxRingBufferSize - 16;
		s.runningState = 2;
	}
	let fence = calculateFence(s);
	let ringBufferMask = s.ringBufferSize - 1;
	let ringBuffer = s.ringBuffer;
	while (s.runningState !== 10) switch (s.runningState) {
		case 2:
			if (s.metaBlockLength < 0) return makeError(s, -10);
			result = readNextMetablockHeader(s);
			if (result < 0) return result;
			fence = calculateFence(s);
			ringBufferMask = s.ringBufferSize - 1;
			ringBuffer = s.ringBuffer;
			continue;
		case 3:
			result = readMetablockHuffmanCodesAndContextMaps(s);
			if (result < 0) return result;
			s.runningState = 4;
			continue;
		case 4:
			if (s.metaBlockLength <= 0) {
				s.runningState = 2;
				continue;
			}
			if (s.halfOffset > 2030) {
				result = readMoreInput(s);
				if (result < 0) return result;
			}
			if (s.commandBlockLength === 0) decodeCommandBlockSwitch(s);
			s.commandBlockLength--;
			if (s.bitOffset >= 16) {
				s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
				s.bitOffset -= 16;
			}
			const cmdCode = readSymbol(s.commandTreeGroup, s.commandTreeIdx, s) << 2;
			const insertAndCopyExtraBits = CMD_LOOKUP[cmdCode];
			const insertLengthOffset = CMD_LOOKUP[cmdCode + 1];
			const copyLengthOffset = CMD_LOOKUP[cmdCode + 2];
			s.distanceCode = CMD_LOOKUP[cmdCode + 3];
			if (s.bitOffset >= 16) {
				s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
				s.bitOffset -= 16;
			}
			const insertLengthExtraBits = insertAndCopyExtraBits & 255;
			s.insertLength = insertLengthOffset + (insertLengthExtraBits <= 16 ? readFewBits(s, insertLengthExtraBits) : readManyBits(s, insertLengthExtraBits));
			if (s.bitOffset >= 16) {
				s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
				s.bitOffset -= 16;
			}
			const copyLengthExtraBits = insertAndCopyExtraBits >> 8;
			s.copyLength = copyLengthOffset + (copyLengthExtraBits <= 16 ? readFewBits(s, copyLengthExtraBits) : readManyBits(s, copyLengthExtraBits));
			s.j = 0;
			s.runningState = 7;
			continue;
		case 7:
			if (s.trivialLiteralContext !== 0) while (s.j < s.insertLength) {
				if (s.halfOffset > 2030) {
					result = readMoreInput(s);
					if (result < 0) return result;
				}
				if (s.literalBlockLength === 0) decodeLiteralBlockSwitch(s);
				s.literalBlockLength--;
				if (s.bitOffset >= 16) {
					s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
					s.bitOffset -= 16;
				}
				ringBuffer[s.pos] = readSymbol(s.literalTreeGroup, s.literalTreeIdx, s);
				s.pos++;
				s.j++;
				if (s.pos >= fence) {
					s.nextRunningState = 7;
					s.runningState = 12;
					break;
				}
			}
			else {
				let prevByte1 = ringBuffer[s.pos - 1 & ringBufferMask] & 255;
				let prevByte2 = ringBuffer[s.pos - 2 & ringBufferMask] & 255;
				while (s.j < s.insertLength) {
					if (s.halfOffset > 2030) {
						result = readMoreInput(s);
						if (result < 0) return result;
					}
					if (s.literalBlockLength === 0) decodeLiteralBlockSwitch(s);
					const literalContext = LOOKUP[s.contextLookupOffset1 + prevByte1] | LOOKUP[s.contextLookupOffset2 + prevByte2];
					const literalTreeIdx = s.contextMap[s.contextMapSlice + literalContext] & 255;
					s.literalBlockLength--;
					prevByte2 = prevByte1;
					if (s.bitOffset >= 16) {
						s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
						s.bitOffset -= 16;
					}
					prevByte1 = readSymbol(s.literalTreeGroup, literalTreeIdx, s);
					ringBuffer[s.pos] = prevByte1;
					s.pos++;
					s.j++;
					if (s.pos >= fence) {
						s.nextRunningState = 7;
						s.runningState = 12;
						break;
					}
				}
			}
			if (s.runningState !== 7) continue;
			s.metaBlockLength -= s.insertLength;
			if (s.metaBlockLength <= 0) {
				s.runningState = 4;
				continue;
			}
			let distanceCode = s.distanceCode;
			if (distanceCode < 0) s.distance = s.rings[s.distRbIdx];
			else {
				if (s.halfOffset > 2030) {
					result = readMoreInput(s);
					if (result < 0) return result;
				}
				if (s.distanceBlockLength === 0) decodeDistanceBlockSwitch(s);
				s.distanceBlockLength--;
				if (s.bitOffset >= 16) {
					s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
					s.bitOffset -= 16;
				}
				const distTreeIdx = s.distContextMap[s.distContextMapSlice + distanceCode] & 255;
				distanceCode = readSymbol(s.distanceTreeGroup, distTreeIdx, s);
				if (distanceCode < 16) {
					const index = s.distRbIdx + DISTANCE_SHORT_CODE_INDEX_OFFSET[distanceCode] & 3;
					s.distance = s.rings[index] + DISTANCE_SHORT_CODE_VALUE_OFFSET[distanceCode];
					if (s.distance < 0) return makeError(s, -12);
				} else {
					const extraBits = s.distExtraBits[distanceCode];
					let bits;
					if (s.bitOffset + extraBits <= 32) bits = readFewBits(s, extraBits);
					else {
						if (s.bitOffset >= 16) {
							s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
							s.bitOffset -= 16;
						}
						bits = extraBits <= 16 ? readFewBits(s, extraBits) : readManyBits(s, extraBits);
					}
					s.distance = s.distOffset[distanceCode] + (bits << s.distancePostfixBits);
				}
			}
			if (s.maxDistance !== s.maxBackwardDistance && s.pos < s.maxBackwardDistance) s.maxDistance = s.pos;
			else s.maxDistance = s.maxBackwardDistance;
			if (s.distance > s.maxDistance) {
				s.runningState = 9;
				continue;
			}
			if (distanceCode > 0) {
				s.distRbIdx = s.distRbIdx + 1 & 3;
				s.rings[s.distRbIdx] = s.distance;
			}
			if (s.copyLength > s.metaBlockLength) return makeError(s, -9);
			s.j = 0;
			s.runningState = 8;
			continue;
		case 8:
			let src = s.pos - s.distance & ringBufferMask;
			let dst = s.pos;
			const copyLength = s.copyLength - s.j;
			const srcEnd = src + copyLength;
			const dstEnd = dst + copyLength;
			if (srcEnd < ringBufferMask && dstEnd < ringBufferMask) {
				if (copyLength < 12 || srcEnd > dst && dstEnd > src) {
					const numQuads = copyLength + 3 >> 2;
					for (let k = 0; k < numQuads; ++k) {
						ringBuffer[dst++] = ringBuffer[src++];
						ringBuffer[dst++] = ringBuffer[src++];
						ringBuffer[dst++] = ringBuffer[src++];
						ringBuffer[dst++] = ringBuffer[src++];
					}
				} else ringBuffer.copyWithin(dst, src, srcEnd);
				s.j += copyLength;
				s.metaBlockLength -= copyLength;
				s.pos += copyLength;
			} else while (s.j < s.copyLength) {
				ringBuffer[s.pos] = ringBuffer[s.pos - s.distance & ringBufferMask];
				s.metaBlockLength--;
				s.pos++;
				s.j++;
				if (s.pos >= fence) {
					s.nextRunningState = 8;
					s.runningState = 12;
					break;
				}
			}
			if (s.runningState === 8) s.runningState = 4;
			continue;
		case 9:
			result = doUseDictionary(s, fence);
			if (result < 0) return result;
			continue;
		case 14:
			s.pos += copyFromCompoundDictionary(s, fence);
			if (s.pos >= fence) {
				s.nextRunningState = 14;
				s.runningState = 12;
				return 2;
			}
			s.runningState = 4;
			continue;
		case 5:
			while (s.metaBlockLength > 0) {
				if (s.halfOffset > 2030) {
					result = readMoreInput(s);
					if (result < 0) return result;
				}
				if (s.bitOffset >= 16) {
					s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
					s.bitOffset -= 16;
				}
				readFewBits(s, 8);
				s.metaBlockLength--;
			}
			s.runningState = 2;
			continue;
		case 6:
			result = copyUncompressedData(s);
			if (result < 0) return result;
			continue;
		case 12:
			s.ringBufferBytesReady = Math.min(s.pos, s.ringBufferSize);
			s.runningState = 13;
			continue;
		case 13:
			result = writeRingBuffer(s);
			if (result !== 0) return result;
			if (s.pos >= s.maxBackwardDistance) s.maxDistance = s.maxBackwardDistance;
			if (s.pos >= s.ringBufferSize) {
				if (s.pos > s.ringBufferSize) ringBuffer.copyWithin(0, s.ringBufferSize, s.pos);
				s.pos = s.pos & ringBufferMask;
				s.ringBufferBytesWritten = 0;
			}
			s.runningState = s.nextRunningState;
			continue;
		default: return makeError(s, -28);
	}
	if (s.runningState !== 10) return makeError(s, -29);
	if (s.metaBlockLength < 0) return makeError(s, -10);
	result = jumpToByteBoundary(s);
	if (result !== 0) return result;
	result = checkHealth(s, 1);
	if (result !== 0) return result;
	return 1;
}
var Transforms = class {
	numTransforms = 0;
	triplets = new Int32Array(0);
	prefixSuffixStorage = new Int8Array(0);
	prefixSuffixHeads = new Int32Array(0);
	params = new Int16Array(0);
	constructor(numTransforms, prefixSuffixLen, prefixSuffixCount) {
		this.numTransforms = numTransforms;
		this.triplets = new Int32Array(numTransforms * 3);
		this.params = new Int16Array(numTransforms);
		this.prefixSuffixStorage = new Int8Array(prefixSuffixLen);
		this.prefixSuffixHeads = new Int32Array(prefixSuffixCount + 1);
	}
};
const RFC_TRANSFORMS = new Transforms(121, 167, 50);
function unpackTransforms(prefixSuffix, prefixSuffixHeads, transforms, prefixSuffixSrc, transformsSrc) {
	const prefixSuffixBytes = toUtf8Runes(prefixSuffixSrc);
	const n = prefixSuffixBytes.length;
	let index = 1;
	let j = 0;
	for (let i = 0; i < n; ++i) {
		const c = prefixSuffixBytes[i];
		if (c === 35) prefixSuffixHeads[index++] = j;
		else prefixSuffix[j++] = c;
	}
	for (let i = 0; i < 363; ++i) transforms[i] = transformsSrc.charCodeAt(i) - 32;
}
unpackTransforms(RFC_TRANSFORMS.prefixSuffixStorage, RFC_TRANSFORMS.prefixSuffixHeads, RFC_TRANSFORMS.triplets, "# #s #, #e #.# the #.com/#Â\xA0# of # and # in # to #\"#\">#\n#]# for # a # that #. # with #'# from # by #. The # on # as # is #ing #\n	#:#ed #(# at #ly #=\"# of the #. This #,# not #er #al #='#ful #ive #less #est #ize #ous #", "     !! ! ,  *!  &!  \" !  ) *   * -  ! # !  #!*!  +  ,$ !  -  %  .  / #   0  1 .  \"   2  3!*   4%  ! # /   5  6  7  8 0  1 &   $   9 +   :  ;  < '  !=  >  ?! 4  @ 4  2  &   A *# (   B  C& ) %  ) !*# *-% A +! *.  D! %'  & E *6  F  G% ! *A *%  H! D  I!+!  J!+   K +- *4! A  L!*4  M  N +6  O!*% +.! K *G  P +%(  ! G *D +D  Q +# *K!*G!+D!+# +G +A +4!+% +K!+4!*D!+K!*K");
function transformDictionaryWord(dst, dstOffset, src, srcOffset, wordLen, transforms, transformIndex) {
	let offset = dstOffset;
	const triplets = transforms.triplets;
	const prefixSuffixStorage = transforms.prefixSuffixStorage;
	const prefixSuffixHeads = transforms.prefixSuffixHeads;
	const transformOffset = 3 * transformIndex;
	const prefixIdx = triplets[transformOffset];
	const transformType = triplets[transformOffset + 1];
	const suffixIdx = triplets[transformOffset + 2];
	let prefix = prefixSuffixHeads[prefixIdx];
	const prefixEnd = prefixSuffixHeads[prefixIdx + 1];
	let suffix = prefixSuffixHeads[suffixIdx];
	const suffixEnd = prefixSuffixHeads[suffixIdx + 1];
	let omitFirst = transformType - 11;
	let omitLast = transformType;
	if (omitFirst < 1 || omitFirst > 9) omitFirst = 0;
	if (omitLast < 1 || omitLast > 9) omitLast = 0;
	while (prefix !== prefixEnd) dst[offset++] = prefixSuffixStorage[prefix++];
	let len = wordLen;
	if (omitFirst > len) omitFirst = len;
	let dictOffset = srcOffset + omitFirst;
	len -= omitFirst;
	len -= omitLast;
	let i = len;
	while (i > 0) {
		dst[offset++] = src[dictOffset++];
		i--;
	}
	if (transformType === 10 || transformType === 11) {
		let uppercaseOffset = offset - len;
		if (transformType === 10) len = 1;
		while (len > 0) {
			const c0 = dst[uppercaseOffset] & 255;
			if (c0 < 192) {
				if (c0 >= 97 && c0 <= 122) dst[uppercaseOffset] = dst[uppercaseOffset] ^ 32;
				uppercaseOffset += 1;
				len -= 1;
			} else if (c0 < 224) {
				dst[uppercaseOffset + 1] = dst[uppercaseOffset + 1] ^ 32;
				uppercaseOffset += 2;
				len -= 2;
			} else {
				dst[uppercaseOffset + 2] = dst[uppercaseOffset + 2] ^ 5;
				uppercaseOffset += 3;
				len -= 3;
			}
		}
	} else if (transformType === 21 || transformType === 22) throw new Error("transformDictionaryWord: transformType 21 and 22 not implemented");
	while (suffix !== suffixEnd) dst[offset++] = prefixSuffixStorage[suffix++];
	return offset - dstOffset;
}
function getNextKey(key, len) {
	let step = 1 << len - 1;
	while ((key & step) !== 0) step = step >> 1;
	return (key & step - 1) + step;
}
function replicateValue(table, offset, step, end, item) {
	let pos = end;
	while (pos > 0) {
		pos -= step;
		table[offset + pos] = item;
	}
}
function nextTableBitSize(count, len, rootBits) {
	let bits = len;
	let left = 1 << bits - rootBits;
	while (bits < 15) {
		left -= count[bits];
		if (left <= 0) break;
		bits++;
		left = left << 1;
	}
	return bits - rootBits;
}
function buildHuffmanTable(tableGroup, tableIdx, rootBits, codeLengths, codeLengthsSize) {
	const tableOffset = tableGroup[tableIdx];
	const sorted = new Int32Array(codeLengthsSize);
	const count = new Int32Array(16);
	const offset = new Int32Array(16);
	for (let sym = 0; sym < codeLengthsSize; ++sym) count[codeLengths[sym]]++;
	offset[1] = 0;
	for (let len = 1; len < 15; ++len) offset[len + 1] = offset[len] + count[len];
	for (let sym = 0; sym < codeLengthsSize; ++sym) if (codeLengths[sym] !== 0) sorted[offset[codeLengths[sym]]++] = sym;
	let tableBits = rootBits;
	let tableSize = 1 << tableBits;
	let totalSize = tableSize;
	if (offset[15] === 1) {
		for (let k = 0; k < totalSize; ++k) tableGroup[tableOffset + k] = sorted[0];
		return totalSize;
	}
	let key = 0;
	let symbol = 0;
	let step = 1;
	for (let len = 1; len <= rootBits; ++len) {
		step = step << 1;
		while (count[len] > 0) {
			replicateValue(tableGroup, tableOffset + key, step, tableSize, len << 16 | sorted[symbol++]);
			key = getNextKey(key, len);
			count[len]--;
		}
	}
	const mask = totalSize - 1;
	let low = -1;
	let currentOffset = tableOffset;
	step = 1;
	for (let len = rootBits + 1; len <= 15; ++len) {
		step = step << 1;
		while (count[len] > 0) {
			if ((key & mask) !== low) {
				currentOffset += tableSize;
				tableBits = nextTableBitSize(count, len, rootBits);
				tableSize = 1 << tableBits;
				totalSize += tableSize;
				low = key & mask;
				tableGroup[tableOffset + low] = tableBits + rootBits << 16 | currentOffset - tableOffset - low;
			}
			replicateValue(tableGroup, currentOffset + (key >> rootBits), step, tableSize, len - rootBits << 16 | sorted[symbol++]);
			key = getNextKey(key, len);
			count[len]--;
		}
	}
	return totalSize;
}
function readMoreInput(s) {
	if (s.endOfStreamReached !== 0) {
		if (halfAvailable(s) >= -2) return 0;
		return makeError(s, -16);
	}
	const readOffset = s.halfOffset << 1;
	let bytesInBuffer = 4096 - readOffset;
	s.byteBuffer.copyWithin(0, readOffset, 4096);
	s.halfOffset = 0;
	while (bytesInBuffer < 4096) {
		const spaceLeft = 4096 - bytesInBuffer;
		const len = readInput(s, s.byteBuffer, bytesInBuffer, spaceLeft);
		if (len < -1) return len;
		if (len <= 0) {
			s.endOfStreamReached = 1;
			s.tailBytes = bytesInBuffer;
			bytesInBuffer += 1;
			break;
		}
		bytesInBuffer += len;
	}
	bytesToNibbles(s, bytesInBuffer);
	return 0;
}
function checkHealth(s, endOfStream) {
	if (s.endOfStreamReached === 0) return 0;
	const byteOffset = (s.halfOffset << 1) + (s.bitOffset + 7 >> 3) - 4;
	if (byteOffset > s.tailBytes) return makeError(s, -13);
	if (endOfStream !== 0 && byteOffset !== s.tailBytes) return makeError(s, -17);
	return 0;
}
function readFewBits(s, n) {
	const v = s.accumulator32 >>> s.bitOffset & (1 << n) - 1;
	s.bitOffset += n;
	return v;
}
function readManyBits(s, n) {
	const low = readFewBits(s, 16);
	s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
	s.bitOffset -= 16;
	return low | readFewBits(s, n - 16) << 16;
}
function initBitReader(s) {
	s.byteBuffer = new Int8Array(4160);
	s.accumulator32 = 0;
	s.shortBuffer = new Int16Array(2080);
	s.bitOffset = 32;
	s.halfOffset = 2048;
	s.endOfStreamReached = 0;
	return prepare(s);
}
function prepare(s) {
	if (s.halfOffset > 2030) {
		const result = readMoreInput(s);
		if (result !== 0) return result;
	}
	let health = checkHealth(s, 0);
	if (health !== 0) return health;
	s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
	s.bitOffset -= 16;
	s.accumulator32 = s.shortBuffer[s.halfOffset++] << 16 | s.accumulator32 >>> 16;
	s.bitOffset -= 16;
	return 0;
}
function jumpToByteBoundary(s) {
	const padding = 32 - s.bitOffset & 7;
	if (padding !== 0) {
		if (readFewBits(s, padding) !== 0) return makeError(s, -5);
	}
	return 0;
}
function halfAvailable(s) {
	let limit = 2048;
	if (s.endOfStreamReached !== 0) limit = s.tailBytes + 1 >> 1;
	return limit - s.halfOffset;
}
function bytesToNibbles(s, byteLen) {
	const byteBuffer = s.byteBuffer;
	const halfLen = byteLen >> 1;
	const shortBuffer = s.shortBuffer;
	for (let i = 0; i < halfLen; ++i) shortBuffer[i] = byteBuffer[i * 2] & 255 | (byteBuffer[i * 2 + 1] & 255) << 8;
}
const LOOKUP = new Int32Array(2048);
function unpackLookupTable(lookup, utfMap, utfRle) {
	for (let i = 0; i < 256; ++i) {
		lookup[i] = i & 63;
		lookup[512 + i] = i >> 2;
		lookup[1792 + i] = 2 + (i >> 6);
	}
	for (let i = 0; i < 128; ++i) lookup[1024 + i] = 4 * (utfMap.charCodeAt(i) - 32);
	for (let i = 0; i < 64; ++i) {
		lookup[1152 + i] = i & 1;
		lookup[1216 + i] = 2 + (i & 1);
	}
	let offset = 1280;
	for (let k = 0; k < 19; ++k) {
		const value = k & 3;
		const rep = utfRle.charCodeAt(k) - 32;
		for (let i = 0; i < rep; ++i) lookup[offset++] = value;
	}
	for (let i = 0; i < 16; ++i) {
		lookup[1792 + i] = 1;
		lookup[2032 + i] = 6;
	}
	lookup[1792] = 0;
	lookup[2047] = 7;
	for (let i = 0; i < 256; ++i) lookup[1536 + i] = lookup[1792 + i] << 3;
}
unpackLookupTable(LOOKUP, "         !!  !                  \"#$##%#$&'##(#)#++++++++++((&*'##,---,---,-----,-----,-----&#'###.///.///./////./////./////&#'# ", "A/*  ':  & : $   @");
var State = class {
	ringBuffer = new Int8Array(0);
	contextModes = new Int8Array(0);
	contextMap = new Int8Array(0);
	distContextMap = new Int8Array(0);
	distExtraBits = new Int8Array(0);
	output = new Int8Array(0);
	byteBuffer = new Int8Array(0);
	shortBuffer = new Int16Array(0);
	intBuffer = new Int32Array(0);
	rings = new Int32Array(0);
	blockTrees = new Int32Array(0);
	literalTreeGroup = new Int32Array(0);
	commandTreeGroup = new Int32Array(0);
	distanceTreeGroup = new Int32Array(0);
	distOffset = new Int32Array(0);
	accumulator64 = 0;
	runningState = 0;
	nextRunningState = 0;
	accumulator32 = 0;
	bitOffset = 0;
	halfOffset = 0;
	tailBytes = 0;
	endOfStreamReached = 0;
	metaBlockLength = 0;
	inputEnd = 0;
	isUncompressed = 0;
	isMetadata = 0;
	literalBlockLength = 0;
	numLiteralBlockTypes = 0;
	commandBlockLength = 0;
	numCommandBlockTypes = 0;
	distanceBlockLength = 0;
	numDistanceBlockTypes = 0;
	pos = 0;
	maxDistance = 0;
	distRbIdx = 0;
	trivialLiteralContext = 0;
	literalTreeIdx = 0;
	commandTreeIdx = 0;
	j = 0;
	insertLength = 0;
	contextMapSlice = 0;
	distContextMapSlice = 0;
	contextLookupOffset1 = 0;
	contextLookupOffset2 = 0;
	distanceCode = 0;
	numDirectDistanceCodes = 0;
	distancePostfixBits = 0;
	distance = 0;
	copyLength = 0;
	maxBackwardDistance = 0;
	maxRingBufferSize = 0;
	ringBufferSize = 0;
	expectedTotalSize = 0;
	outputOffset = 0;
	outputLength = 0;
	outputUsed = 0;
	ringBufferBytesWritten = 0;
	ringBufferBytesReady = 0;
	isEager = 0;
	isLargeWindow = 0;
	cdNumChunks = 0;
	cdTotalSize = 0;
	cdBrIndex = 0;
	cdBrOffset = 0;
	cdBrLength = 0;
	cdBrCopied = 0;
	cdChunks = new Array(0);
	cdChunkOffsets = new Int32Array(0);
	cdBlockBits = 0;
	cdBlockMap = new Int8Array(0);
	input = new InputStream(new Int8Array(0));
	constructor() {
		this.ringBuffer = new Int8Array(0);
		this.rings = new Int32Array(10);
		this.rings[0] = 16;
		this.rings[1] = 15;
		this.rings[2] = 11;
		this.rings[3] = 4;
	}
};
let data = new Int8Array(0);
const offsets = new Int32Array(32);
const sizeBits = new Int32Array(32);
function setData(newData, newSizeBits) {
	const dictionaryOffsets = offsets;
	const dictionarySizeBits = sizeBits;
	for (let i = 0; i < newSizeBits.length; ++i) dictionarySizeBits[i] = newSizeBits[i];
	let pos = 0;
	for (let i = 0; i < newSizeBits.length; ++i) {
		dictionaryOffsets[i] = pos;
		const bits = dictionarySizeBits[i];
		if (bits !== 0) pos += i << (bits & 31);
	}
	for (let i = newSizeBits.length; i < 32; ++i) dictionaryOffsets[i] = pos;
	data = newData;
}
function unpackDictionaryData(dictionary, data0, data1, skipFlip, sizeBits$1, sizeBitsData) {
	const dict = toUsAsciiBytes(data0 + data1);
	const skipFlipRunes = toUtf8Runes(skipFlip);
	let offset = 0;
	const n = skipFlipRunes.length >> 1;
	for (let i = 0; i < n; ++i) {
		const skip = skipFlipRunes[2 * i] - 36;
		const flip = skipFlipRunes[2 * i + 1] - 36;
		for (let j = 0; j < skip; ++j) {
			dict[offset] = dict[offset] ^ 3;
			offset++;
		}
		for (let j = 0; j < flip; ++j) {
			dict[offset] = dict[offset] ^ 236;
			offset++;
		}
	}
	for (let i = 0; i < sizeBitsData.length; ++i) sizeBits$1[i] = sizeBitsData.charCodeAt(i) - 65;
	dictionary.set(dict);
}
{
	const dictionaryData = new Int8Array(122784);
	const dictionarySizeBits = new Int32Array(25);
	unpackDictionaryData(dictionaryData, "wjnfgltmojefofewab`h`lgfgbwbpkltlmozpjwf`jwzlsfmivpwojhfeqfftlqhwf{wzfbqlufqalgzolufelqnallhsobzojufojmfkfosklnfpjgfnlqftlqgolmdwkfnujftejmgsbdfgbzpevookfbgwfqnfb`kbqfbeqlnwqvfnbqhbaofvslmkjdkgbwfobmgmftpfufmmf{w`bpfalwkslpwvpfgnbgfkbmgkfqftkbwmbnfOjmhaoldpjyfabpfkfognbhfnbjmvpfq$*#(klogfmgptjwkMftpqfbgtfqfpjdmwbhfkbufdbnfpffm`boosbwktfoosovpnfmvejonsbqwiljmwkjpojpwdllgmffgtbzptfpwilapnjmgboploldlqj`kvpfpobpwwfbnbqnzellghjmdtjoofbpwtbqgafpwejqfSbdfhmltbtbz-smdnlufwkbmolbgdjufpfoemlwfnv`keffgnbmzql`hj`lmlm`follhkjgfgjfgKlnfqvofklpwbib{jmel`ovaobtpofppkboeplnfpv`kylmf233&lmfp`bqfWjnfqb`faovfelvqtffheb`fklsfdbufkbqgolpwtkfmsbqhhfswsbpppkjsqllnKWNOsobmWzsfglmfpbufhffseobdojmhplogejufwllhqbwfwltmivnswkvpgbqh`bqgejofefbqpwbzhjoowkbweboobvwlfufq-`lnwbohpklsulwfgffsnlgfqfpwwvqmalqmabmgefooqlpfvqo+phjmqlof`lnfb`wpbdfpnffwdlog-isdjwfnubqzefowwkfmpfmggqlsUjft`lsz2-3!?,b=pwlsfopfojfpwlvqsb`h-djesbpw`pp<dqbznfbm%dw8qjgfpklwobwfpbjgqlbgubq#effoilkmqj`hslqwebpw$VB.gfbg?,a=sllqajoowzsfV-P-tllgnvpw1s{8JmelqbmhtjgftbmwtbooofbgX3^8sbvotbufpvqf'+$ tbjwnbppbqnpdlfpdbjmobmdsbjg\"..#ol`hvmjwqllwtbohejqntjef{no!plmdwfpw13s{hjmgqltpwlloelmwnbjopbefpwbqnbsp`lqfqbjmeoltabazpsbmpbzp7s{85s{8bqwpellwqfbotjhjkfbwpwfswqjslqd,obhftfbhwlogElqn`bpwebmpabmhufqzqvmpivozwbph2s{8dlbodqftpoltfgdfjg>!pfwp6s{8-ip<73s{je#+pllmpfbwmlmfwvafyfqlpfmwqffgeb`wjmwldjewkbqn2;s{`bnfkjooalogyllnuljgfbpzqjmdejoosfbhjmjw`lpw0s{8ib`hwbdpajwpqloofgjwhmftmfbq?\"..dqltIPLMgvwzMbnfpbofzlv#olwpsbjmibyy`logfzfpejpkttt-qjphwbapsqfu23s{qjpf16s{Aovfgjmd033/abooelqgfbqmtjogal{-ebjqob`hufqpsbjqivmfwf`kje+\"sj`hfujo'+! tbqnolqgglfpsvoo/333jgfbgqbtkvdfpslwevmgavqmkqfe`foohfzpwj`hklvqolppevfo21s{pvjwgfboQPP!bdfgdqfzDFW!fbpfbjnpdjqobjgp;s{8mbuzdqjgwjsp :::tbqpobgz`bqp*8#~sks<kfoowbootklnyk9	),	#233kboo-		B4s{8svpk`kbw3s{8`qft),?,kbpk46s{eobwqbqf#%%#wfoo`bnslmwlobjgnjppphjswfmwejmfnbofdfwpsolw733/		`lloeffw-sks?aq=fqj`nlpwdvjgafoogfp`kbjqnbwkbwln,jnd% ;1ov`h`fmw3338wjmzdlmfkwnopfoogqvdEQFFmlgfmj`h<jg>olpfmvooubpwtjmgQPP#tfbqqfozaffmpbnfgvhfmbpb`bsftjpkdvoeW109kjwppolwdbwfhj`haovqwkfz26s{$$*8*8!=npjftjmpajqgplqwafwbpffhW2;9lqgpwqffnboo53s{ebqnlupalzpX3^-$*8!SLPWafbqhjgp*8~~nbqzwfmg+VH*rvbgyk9\n.pjy....sqls$*8ojewW2:9uj`fbmgzgfaw=QPPsllomf`haoltW259gllqfuboW249ofwpebjolqbosloomlub`lopdfmf#lxplewqlnfwjooqlpp?k0=slvqebgfsjmh?wq=njmj*\"+njmfyk9abqpkfbq33*8njoh#..=jqlmeqfggjphtfmwpljosvwp,ip,klozW119JPAMW139bgbnpffp?k1=iplm$/#$`lmwW129#QPPollsbpjbnllm?,s=plvoOJMFelqw`bqwW279?k2=;3s{\"..?:s{8W379njhf975Ymj`fjm`kZlqhqj`fyk9\b$**8svqfnbdfsbqbwlmfalmg904Y\\le\\$^*8333/yk9\vwbmhzbqgaltoavpk965YIbub03s{	~	&@0&907YifeeF[SJ`bpkujpbdloepmltyk9rvfq-`pppj`hnfbwnjm-ajmggfookjqfsj`pqfmw905YKWWS.132elwltloeFMG#{al{967YALGZgj`h8	~	f{jw906Yubqpafbw$~*8gjfw:::8bmmf~~?,Xj^-Obmdhn.^tjqfwlzpbggppfbobof{8	\n~f`klmjmf-lqd336*wlmziftppbmgofdpqlle333*#133tjmfdfbqgldpallwdbqz`vwpwzofwfnswjlm-{no`l`hdbmd'+$-63s{Sk-Gnjp`bobmolbmgfphnjofqzbmvmj{gjp`*8~	gvpw`ojs*-		43s{.133GUGp4^=?wbsfgfnlj((*tbdffvqlskjolswpklofEBRpbpjm.15WobapsfwpVQO#avoh`llh8~	KFBGX3^*baaqivbm+2:;ofpkwtjm?,j=plmzdvzpev`hsjsf.	\"331*mgltX2^8X^8	Old#pbow	\n\nabmdwqjnabwk*x	33s{	~*8hl9\0effpbg=p9,,#X^8wloosovd+*x	x	#-ip$133sgvboalbw-ISD*8	~rvlw*8		$*8		~1327132613251324132;132:13131312131113101317131613151314131;131:130313021301130013071306130513041320132113221323133:133;133413351336133713301331133213332:::2::;2::42::52::62::72::02::12::22::32:;:2:;;2:;42:;52:;62:;72:;02:;12:;22:;32:4:2:4;2:442:452:462:472:402:412:422:432:5:2:5;2:542:552:562:572:502:512:522:532:6:2:6;2:642:652:662:672:602:612:622:632333231720:73333::::`lnln/Mpfpwffpwbsfqlwlglkb`f`bgbb/]lajfmg/Abbp/Aujgb`bpllwqlelqlplollwqb`vbogjilpjgldqbmwjslwfnbgfafbodlrv/Efpwlmbgbwqfpsl`l`bpbabilwlgbpjmlbdvbsvfpvmlpbmwfgj`fovjpfoobnbzlylmbbnlqsjpllaqb`oj`foolgjlpklqb`bpj<[<\\<Q<\\<R<P=l<\\=l=o=n<\\<Q<Y<S<R<R=n<T<[<Q<R<X<R=n<R<Z<Y<R<Q<T=i<q<\\<Y<Y<]=g<P=g<~=g=m<R<^=g<^<R<q<R<R<]<s<R<W<T<Q<T<L<H<q<Y<p=g=n=g<r<Q<T<P<X<\\<{<\\<x<\\<q=o<r<]=n<Y<t<[<Y<U<Q=o<P<P<N=g=o<Z5m5f4O5j5i4K5i4U5o5h4O5d4]4C5f4K5m5e5k5d5h5i5h5o4K5d5h5k4D4_4K5h4I5j5k5f4O5f5n4C5k5h4G5i4D5k5h5d5h5f4D5h4K5f4D5o4X5f4K5i4O5i5j4F4D5f5h5j4A4D5k5i5i4X5d4Xejqpwujgflojdkwtlqognfgjbtkjwf`olpfaob`hqjdkwpnbooallhpsob`fnvpj`ejfoglqgfqsljmwubovfofufowbaofalbqgklvpfdqlvstlqhpzfbqppwbwfwlgbztbwfqpwbqwpwzofgfbwksltfqsklmfmjdkwfqqlqjmsvwbalvwwfqnpwjwofwllopfufmwol`bowjnfpobqdftlqgpdbnfppklqwpsb`fel`vp`ofbqnlgfoaol`hdvjgfqbgjlpkbqftlnfmbdbjmnlmfzjnbdfmbnfpzlvmdojmfpobwfq`lolqdqffmeqlmw%bns8tbw`kelq`fsqj`fqvofpafdjmbewfqujpjwjppvfbqfbpafoltjmgf{wlwboklvqpobafosqjmwsqfppavjowojmhppsffgpwvgzwqbgfelvmgpfmpfvmgfqpkltmelqnpqbmdfbggfgpwjoonlufgwbhfmbalufeobpkej{fglewfmlwkfqujftp`kf`hofdboqjufqjwfnprvj`hpkbsfkvnbmf{jpwdljmdnlujfwkjqgabpj`sfb`fpwbdftjgwkoldjmjgfbptqlwfsbdfpvpfqpgqjufpwlqfaqfbhplvwkulj`fpjwfpnlmwktkfqfavjogtkj`kfbqwkelqvnwkqffpslqwsbqwz@oj`holtfqojufp`obppobzfqfmwqzpwlqzvpbdfplvmg`lvqwzlvq#ajqwkslsvswzsfpbssozJnbdfafjmdvssfqmlwfpfufqzpkltpnfbmpf{wqbnbw`kwqb`hhmltmfbqozafdbmpvsfqsbsfqmlqwkofbqmdjufmmbnfgfmgfgWfqnpsbqwpDqlvsaqbmgvpjmdtlnbmebopfqfbgzbvgjlwbhfptkjof-`ln,ojufg`bpfpgbjoz`kjogdqfbwivgdfwklpfvmjwpmfufqaqlbg`lbpw`lufqbssofejofp`z`ofp`fmfsobmp`oj`htqjwfrvffmsjf`ffnbjoeqbnflogfqsklwlojnjw`b`kf`jujop`boffmwfqwkfnfwkfqfwlv`kalvmgqlzbobphfgtklofpjm`fpwl`h#mbnfebjwkkfbqwfnswzleefqp`lsfltmfgnjdkwboavnwkjmhaollgbqqbznbilqwqvpw`bmlmvmjlm`lvmwubojgpwlmfPwzofOldjmkbsszl``vqofew9eqfpkrvjwfejonpdqbgfmffgpvqabmejdkwabpjpklufqbvwl8qlvwf-kwnonj{fgejmboZlvq#pojgfwlsj`aqltmbolmfgqbtmpsojwqfb`kQjdkwgbwfpnbq`krvlwfdllgpOjmhpglvawbpzm`wkvnaboolt`kjfezlvwkmlufo23s{8pfqufvmwjokbmgp@kf`hPsb`frvfqzibnfpfrvbowtj`f3/333Pwbqwsbmfoplmdpqlvmgfjdkwpkjewtlqwkslpwpofbgptffhpbuljgwkfpfnjofpsobmfpnbqwboskbsobmwnbqhpqbwfpsobzp`objnpbofpwf{wppwbqptqlmd?,k0=wkjmd-lqd,nvowjkfbqgSltfqpwbmgwlhfmplojg+wkjpaqjmdpkjsppwbeewqjfg`boopevoozeb`wpbdfmwWkjp#,,..=bgnjmfdzswFufmw26s{8Fnbjowqvf!`qlpppsfmwaoldpal{!=mlwfgofbuf`kjmbpjyfpdvfpw?,k7=qlalwkfbuzwqvf/pfufmdqbmg`qjnfpjdmpbtbqfgbm`fskbpf=?\"..fm\\VP% 0:8133s{\\mbnfobwjmfmilzbib{-bwjlmpnjwkV-P-#klogpsfwfqjmgjbmbu!=`kbjmp`lqf`lnfpgljmdsqjlqPkbqf2::3pqlnbmojpwpibsbmeboopwqjboltmfqbdqff?,k1=bavpfbofqwlsfqb!.,,T`bqgpkjoopwfbnpSklwlwqvwk`ofbm-sks<pbjmwnfwboolvjpnfbmwsqlleaqjfeqlt!=dfmqfwqv`hollhpUbovfEqbnf-mfw,..=	?wqz#x	ubq#nbhfp`lpwpsobjmbgvowrvfpwwqbjmobalqkfosp`bvpfnbdj`nlwlqwkfjq163s{ofbpwpwfsp@lvmw`lvogdobpppjgfpevmgpklwfobtbqgnlvwknlufpsbqjpdjufpgvw`kwf{bpeqvjwmvoo/X^8wls!=	?\"..SLPW!l`fbm?aq,=eollqpsfbhgfswk#pjyfabmhp`bw`k`kbqw13s{8bojdmgfboptlvog63s{8vqo>!sbqhpnlvpfNlpw#---?,bnlmdaqbjmalgz#mlmf8abpfg`bqqzgqbewqfefqsbdf\\klnf-nfwfqgfobzgqfbnsqlufiljmw?,wq=gqvdp?\"..#bsqjojgfboboofmf{b`welqwk`lgfpoldj`Ujft#pffnpaobmhslqwp#+133pbufg\\ojmhdlbopdqbmwdqffhklnfpqjmdpqbwfg03s{8tklpfsbqpf+*8!#Aol`hojmv{ilmfpsj{fo$*8!=*8je+.ofewgbujgklqpfEl`vpqbjpfal{fpWqb`hfnfmw?,fn=abq!=-pq`>wltfqbow>!`baofkfmqz17s{8pfwvsjwbozpkbqsnjmlqwbpwftbmwpwkjp-qfpfwtkffodjqop,`pp,233&8`ovappwveeajaofulwfp#2333hlqfb~*8	abmgprvfvf>#x~8;3s{8`hjmdx	\n\nbkfbg`ol`hjqjpkojhf#qbwjlpwbwpElqn!zbkll*X3^8Balvwejmgp?,k2=gfavdwbphpVQO#>`foop~*+*821s{8sqjnfwfoopwvqmp3{533-isd!psbjmafb`kwb{fpnj`qlbmdfo..=?,djewppwfuf.ojmhalgz-~*8	\nnlvmw#+2::EBR?,qldfqeqbmh@obpp1;s{8effgp?k2=?p`lwwwfpwp11s{8gqjmh*##oftjppkboo 30:8#elq#olufgtbpwf33s{8ib9npjnlm?elmwqfsoznffwpvmwfq`kfbswjdkwAqbmg*#\">#gqfpp`ojspqllnplmhfznlajonbjm-Mbnf#sobwfevmmzwqffp`ln,!2-isdtnlgfsbqbnPWBQWofew#jggfm/#132*8	~	elqn-ujqvp`kbjqwqbmptlqpwSbdfpjwjlmsbw`k?\"..	l.`b`ejqnpwlvqp/333#bpjbmj((*xbglaf$*X3^jg>23alwk8nfmv#-1-nj-smd!hfujm`lb`k@kjogaqv`f1-isdVQO*(-isdpvjwfpoj`fkbqqz213!#ptffwwq=	mbnf>gjfdlsbdf#ptjpp..=		 eee8!=Old-`ln!wqfbwpkffw*#%%#27s{8poffsmwfmwejofgib9ojg>!`Mbnf!tlqpfpklwp.al{.gfowb	%ow8afbqp97;Y?gbwb.qvqbo?,b=#psfmgabhfqpklsp>#!!8sks!=`wjlm20s{8aqjbmkfoolpjyf>l>&1E#iljmnbzaf?jnd#jnd!=/#eipjnd!#!*X3^NWlsAWzsf!mftozGbmph`yf`kwqbjohmltp?,k6=ebr!=yk.`m23*8	.2!*8wzsf>aovfpwqvozgbujp-ip$8=	?\"pwffo#zlv#k1=	elqn#ifpvp233&#nfmv-	\n	tbofpqjphpvnfmwggjmda.ojhwfb`kdje!#ufdbpgbmphffpwjpkrjspvlnjplaqfgfpgffmwqfwlglpsvfgfb/]lpfpw/Mwjfmfkbpwblwqlpsbqwfglmgfmvfulkb`fqelqnbnjpnlnfilqnvmglbrv/Ag/Abpp/_olbzvgbef`kbwlgbpwbmwlnfmlpgbwlplwqbppjwjlnv`klbklqbovdbqnbzlqfpwlpklqbpwfmfqbmwfpelwlpfpwbpsb/Apmvfubpbovgelqlpnfgjlrvjfmnfpfpslgfq`kjofpfq/Muf`fpgf`jqilp/Efpwbqufmwbdqvslkf`klfoolpwfmdlbnjdl`lpbpmjufodfmwfnjpnbbjqfpivojlwfnbpkb`jbebulqivmjlojaqfsvmwlavfmlbvwlqbaqjoavfmbwf{wlnbqylpbafqojpwbovfdl`/_nlfmfqlivfdlsfq/Vkbafqfpwlzmvm`bnvifqubolqevfqbojaqldvpwbjdvboulwlp`bplpdv/Absvfglplnlpbujplvpwfggfafmml`kfavp`bebowbfvqlppfqjfgj`kl`vqpl`obuf`bpbpof/_msobylobqdllaqbpujpwbbslzlivmwlwqbwbujpwl`qfbq`bnslkfnlp`jm`l`bqdlsjplplqgfmkb`fm/Mqfbgjp`lsfgql`fq`bsvfgbsbsfonfmlq/Vwjo`obqlilqdf`boofslmfqwbqgfmbgjfnbq`bpjdvffoobppjdol`l`kfnlwlpnbgqf`obpfqfpwlmj/]lrvfgbsbpbqabm`lkjilpujbifsbaol/Epwfujfmfqfjmlgfibqelmgl`bmbomlqwfofwqb`bvpbwlnbqnbmlpovmfpbvwlpujoobufmglsfpbqwjslpwfmdbnbq`loofubsbgqfvmjglubnlpylmbpbnalpabmgbnbqjbbavplnv`kbpvajqqjlibujujqdqbgl`kj`bboo/Ailufmgj`kbfpwbmwbofppbojqpvfolsfplpejmfpoobnbavp`l/Epwboofdbmfdqlsobybkvnlqsbdbqivmwbglaofjpobpalopbab/]lkbaobov`kb/mqfbgj`fmivdbqmlwbpuboofboo/M`bqdbglolqbabilfpw/Edvpwlnfmwfnbqjlejqnb`lpwlej`kbsobwbkldbqbqwfpofzfpbrvfonvpflabpfpsl`lpnjwbg`jfol`kj`lnjfgldbmbqpbmwlfwbsbgfafpsobzbqfgfppjfwf`lqwf`lqfbgvgbpgfpflujfilgfpfbbdvbp%rvlw8glnbjm`lnnlmpwbwvpfufmwpnbpwfqpzpwfnb`wjlmabmmfqqfnlufp`qloovsgbwfdolabonfgjvnejowfqmvnafq`kbmdfqfpvowsvaoj`p`qffm`kllpfmlqnbowqbufojppvfpplvq`fwbqdfwpsqjmdnlgvofnlajofptjw`ksklwlpalqgfqqfdjlmjwpfoepl`jbob`wjuf`lovnmqf`lqgelooltwjwof=fjwkfqofmdwkebnjozeqjfmgobzlvwbvwklq`qfbwfqfujftpvnnfqpfqufqsobzfgsobzfqf{sbmgsloj`zelqnbwglvaofsljmwppfqjfpsfqplmojujmdgfpjdmnlmwkpelq`fpvmjrvftfjdkwsflsoffmfqdzmbwvqfpfbq`kejdvqfkbujmd`vpwlnleepfwofwwfqtjmgltpvanjwqfmgfqdqlvspvsolbgkfbowknfwklgujgflpp`klloevwvqfpkbgltgfabwfubovfpLaif`wlwkfqpqjdkwpofbdvf`kqlnfpjnsofmlwj`fpkbqfgfmgjmdpfbplmqfslqwlmojmfprvbqfavwwlmjnbdfpfmbaofnlujmdobwfpwtjmwfqEqbm`fsfqjlgpwqlmdqfsfbwOlmglmgfwbjoelqnfggfnbmgpf`vqfsbppfgwlddofsob`fpgfuj`fpwbwj``jwjfppwqfbnzfooltbwwb`hpwqffweojdkwkjggfmjmel!=lsfmfgvpfevouboofz`bvpfpofbgfqpf`qfwpf`lmggbnbdfpslqwpf{`fswqbwjmdpjdmfgwkjmdpfeef`wejfogppwbwfpleej`fujpvbofgjwlqulovnfQfslqwnvpfvnnlujfpsbqfmwb``fppnlpwoznlwkfq!#jg>!nbqhfwdqlvmg`kbm`fpvqufzafelqfpznalonlnfmwpsff`knlwjlmjmpjgfnbwwfq@fmwfqlaif`wf{jpwpnjggofFvqlsfdqltwkofdb`znbmmfqfmlvdk`bqffqbmptfqlqjdjmslqwbo`ojfmwpfof`wqbmgln`olpfgwlsj`p`lnjmdebwkfqlswjlmpjnsozqbjpfgfp`bsf`klpfm`kvq`kgfejmfqfbplm`lqmfqlvwsvwnfnlqzjeqbnfsloj`fnlgfopMvnafqgvqjmdleefqppwzofphjoofgojpwfg`boofgpjoufqnbqdjmgfofwfafwwfqaqltpfojnjwpDolabopjmdoftjgdfw`fmwfqavgdfwmltqbs`qfgjw`objnpfmdjmfpbefwz`klj`fpsjqjw.pwzofpsqfbgnbhjmdmffgfgqvppjbsofbpff{wfmwP`qjswaqlhfmbooltp`kbqdfgjujgfeb`wlqnfnafq.abpfgwkflqz`lmejdbqlvmgtlqhfgkfosfg@kvq`kjnsb`wpklvogbotbzpoldl!#alwwlnojpw!=*xubq#sqfej{lqbmdfKfbgfq-svpk+`lvsofdbqgfmaqjgdfobvm`kQfujftwbhjmdujpjlmojwwofgbwjmdAvwwlmafbvwzwkfnfpelqdlwPfbq`kbm`klqbonlpwolbgfg@kbmdfqfwvqmpwqjmdqfolbgNlajofjm`lnfpvssozPlvq`flqgfqpujftfg%maps8`lvqpfBalvw#jpobmg?kwno#`llhjfmbnf>!bnbylmnlgfqmbguj`fjm?,b=9#Wkf#gjboldklvpfpAFDJM#Nf{j`lpwbqwp`fmwqfkfjdkwbggjmdJpobmgbppfwpFnsjqfP`kllofeelqwgjqf`wmfbqoznbmvboPfof`w-		Lmfiljmfgnfmv!=SkjojsbtbqgpkbmgofjnslqwLeej`fqfdbqgphjoopmbwjlmPslqwpgfdqfftffhoz#+f-d-afkjmggl`wlqolddfgvmjwfg?,a=?,afdjmpsobmwpbppjpwbqwjpwjppvfg033s{`bmbgbbdfm`zp`kfnfqfnbjmAqbyjopbnsofoldl!=afzlmg.p`bofb``fswpfqufgnbqjmfEllwfq`bnfqb?,k2=	\\elqn!ofbufppwqfpp!#,=	-dje!#lmolbgolbgfqL{elqgpjpwfqpvqujuojpwfmefnbofGfpjdmpjyf>!bssfbowf{w!=ofufopwkbmhpkjdkfqelq`fgbmjnbobmzlmfBeqj`bbdqffgqf`fmwSflsof?aq#,=tlmgfqsqj`fpwvqmfg#x~8nbjm!=jmojmfpvmgbztqbs!=ebjofg`fmpvpnjmvwfafb`lmrvlwfp263s{fpwbwfqfnlwffnbjo!ojmhfgqjdkw8pjdmboelqnbo2-kwnopjdmvssqjm`feolbw9-smd!#elqvn-B``fppsbsfqpplvmgpf{wfmgKfjdkwpojgfqVWE.;!%bns8#Afelqf-#TjwkpwvgjlltmfqpnbmbdfsqlejwiRvfqzbmmvbosbqbnpalvdkwebnlvpdlldofolmdfqj((*#xjpqbfopbzjmdgf`jgfklnf!=kfbgfqfmpvqfaqbm`ksjf`fpaol`h8pwbwfgwls!=?qb`jmdqfpjyf..%dw8sb`jwzpf{vboavqfbv-isd!#23/333lawbjmwjwofpbnlvmw/#Jm`-`lnfgznfmv!#ozqj`pwlgbz-jmgffg`lvmwz\\oldl-EbnjozollhfgNbqhfwopf#jeSobzfqwvqhfz*8ubq#elqfpwdjujmdfqqlqpGlnbjm~fopfxjmpfqwAold?,ellwfqoldjm-ebpwfqbdfmwp?algz#23s{#3sqbdnbeqjgbzivmjlqgloobqsob`fg`lufqpsovdjm6/333#sbdf!=alpwlm-wfpw+bubwbqwfpwfg\\`lvmwelqvnpp`kfnbjmgf{/ejoofgpkbqfpqfbgfqbofqw+bssfbqPvanjwojmf!=algz!=	)#WkfWklvdkpffjmdifqpfzMftp?,ufqjezf{sfqwjmivqztjgwk>@llhjfPWBQW#b`qlpp\\jnbdfwkqfbgmbwjufsl`hfwal{!=	Pzpwfn#Gbujg`bm`fqwbaofpsqlufgBsqjo#qfboozgqjufqjwfn!=nlqf!=albqgp`lolqp`bnsvpejqpw##X^8nfgjb-dvjwbqejmjpktjgwk9pkltfgLwkfq#-sks!#bppvnfobzfqptjoplmpwlqfpqfojfeptfgfm@vpwlnfbpjoz#zlvq#Pwqjmd		Tkjowbzolq`ofbq9qfplqweqfm`kwklvdk!*#(#!?algz=avzjmdaqbmgpNfnafqmbnf!=lssjmdpf`wlq6s{8!=upsb`fslpwfqnbilq#`leeffnbqwjmnbwvqfkbssfm?,mbu=hbmpbpojmh!=Jnbdfp>ebopftkjof#kpsb`f3%bns8#		Jm##sltfqSlophj.`lolqilqgbmAlwwlnPwbqw#.`lvmw1-kwnomftp!=32-isdLmojmf.qjdkwnjoofqpfmjlqJPAM#33/333#dvjgfpubovf*f`wjlmqfsbjq-{no!##qjdkwp-kwno.aol`hqfdF{s9klufqtjwkjmujqdjmsklmfp?,wq=vpjmd#	\nubq#=$*8	\n?,wg=	?,wq=	abkbpbaqbpjodbofdlnbdzbqslophjpqsphj4]4C5d\bTA\nzk\vBl\bQ\vUmGx\bSM\nmC\bTA	wQ\nd}\bW@\bTl\bTF	i@	cT\vBM\v|jBV	qw	cC\bWI\npa	fM\n{Z{X\bTF\bVV\bVK	mkF	[]\bPm\bTv\nsI\vpg	[I\bQpmx\v_W\n^M\npe\vQ}\vGu\nel\npeChBV\bTA	So\nzk\vGL\vxD\nd[JzMY\bQpli\nfl\npC{BNt\vwT	i_\bTgQQ\n|p\vXN\bQS\vxDQC\bWZ	pD\vVS\bTWNtYh\nzuKjN}	wr	Ha\n_D	j`\vQ}\vWp\nxZ{c	ji	BU\nbDa|	Tn	pV\nZd\nmC\vEV{X	c}	To\bWl\bUd	IQ	cg\vxs\nXW	wR\vek	c}	]y	Jn\nrp\neg\npV\nz\\{W\npl\nz\\\nzU	Pc	`{\bV@\nc|\bRw	i_\bVb\nwX	HvSu\bTF\v_W\vWs\vsIm\nTT\ndc	US	}f	iZ\bWz	c}MD	Be	iD\v@@\bTl\bPv	}tSwM`\vnU	kW\ved\nqo\vxY	A|\bTz\vy`BRBM	iaXU\nyun^	fL	iI\nXW	fD\bWz\bW@	yj	m	av	BN\vb\\	pD\bTf\nY[	Jn\bQy	[^\vWc\vyuDlCJ\vWj\vHR	`V\vuW	Qy\np@\vGuplJm\bW[\nLP\nxC\n`m	wQuiR\nbI	wQ	BZ	WVBR\npg	cgtiCW\n_y	Rg\bQa\vQB\vWc\nYble\ngESu\nL[	Q	ea	dj\v]W\nb~M`	wL\bTV\bVH\nt\npl	|bs_\bU|\bTaoQlvSkM`\bTv\vK}\nfl	cCoQBR	Hk	|d\bQp	HK	BZ\vHR\bPv\vLx\vEZ\bT\bTv	iDoDMU\vwBSuk`St\ntC	Pl	Kg\noi	jY\vxYh}\nzk\bWZ	m\ve`	TB	fE\nzk	`zYh\nV|	HK	AJ	AJ\bUL	p\\	ql\nYcKd\nfyYh	[I\vDgJm\n]n\nlb\bUd\n{Z	lu	fsoQ\bTWJm\vwB	eaYhBC	sb	Tn\nzU\n_y\vxY	Q]\ngwmt	O\\\ntb\bWW\bQy	mI	V[\ny\\\naB\vRb	wQ\n]QQJ\bWg\vWa\bQj\ntC\bVH\nYm\vxs\bVK\nel\bWI\vxYCq\ntR\vHV\bTl\bVw	ay\bQa\bVV	}t	dj\nr|	p\\	wR\n{i\nTT	[I	i[	AJ\vxs\v_W	d{\vQ}	cg	Tz	A|	Cj\vLmN}m\nbK	dZ	p\\	`V	sV\np@	iD	wQ\vQ}\bTfkaJm\v@@\bV`	zp\n@NSw	iI	cg\noiSu\bVwloCy	c}\vb\\	sUBA\bWI\bTf\nxS	Vp\nd|\bTV\vbC	NoJu\nTC	|`\n{Z	D]\bU|	c}lm\bTl	Bv	Pl	c}\bQp	m\nLk	kj\n@NSbKO	j_	p\\\nzU\bTl\bTg\bWI	cfXO\bWW\ndzli	BN\nd[\bWOMD\vKC	dj	I_\bVV\ny\\\vLmxl	xB	kV\vb\\\vJW\vVS	Vx\vxD	d{MD\bTa	|`\vPzR}\vWsBM\nsICN\bTaJm\npe	i_\npV\nrh	Rd	Hv\n~A\nxR\vWh\vWk\nxS\vAz\vwX\nbIoQ	fw\nqI\nV|\nunz\vpg	d\\\voA{D	i_xB\bT	`Vqr	TTg]CA\vuR	VJ	T`\npw\vRb	I_\nCxRo\vsICjKh	Bv	WVBBoD{D\nhcKm\v^R	QE\n{I\np@\nc|Gt	c}Dl\nzUqN	sVk}	Hh\v|j\nqou|	Q]\vekZM`St\npe	dj\bVG\veE	m\vWc|I\n[W	fL\bT	BZSu\vKaCqNtY[\nqI\bTv	fM	i@	}fB\\	Qy\vBl\bWgXDkc\vx[\bVV	Q]	a	Py\vxD\nfI	}foD	dj	SGls	~DCN\n{Z	\\v\n_D\nhc\vx_C[	AJ\nLM	VxCI	bj	c^	cF\ntCSx	wrXA\bU\\	|a\vK\\\bTV\bVj\nd|	fsCX\ntb\bRw	Vx	AE	A|\bTNt\vDg	Vc\bTld@\npo	M	cF\npe	iZ	Bo\bSq\nfHl`\bTx\bWf	HE\vF{	cO	fD\nlm\vfZ\nlm\veU	dGBH\bTV	SiMW\nwX\nz\\	\\cCX\nd}	l}\bQp\bTV	F~\bQ	`i\ng@nO\bUd\bTl\nL[	wQ	ji\ntC	|J\nLU\naB\vxYKj	AJuN	i[\npeSk\vDg\vx]\bVb\bVV\nea	kV\nqI\bTaSk\nAO	pD\ntb\nts\nyi\bVg	i_\v_W\nLkNt	yj	fMR	iI\bTl\vwX	sV\vMl\nyu	AJ\bVjKO	WV\vA}\vW\nrp	iD\v|olv\vsIBM	d~	CU\bVbeV\npC\vwT	j`	c}\vxs\vps\vvh	WV\vGg\vAe\vVK\v]W	rg\vWcF`	Br\vb\\	dZ\bQp\nqIkF\nLk\vAR\bWI\bTg	bs	dw\n{L\n_y	iZ\bTA	lg\bVV\bTl	dk\n`k	a{	i_{Awj	wN\v@@\bTe	i_\n_D	wL\nAH\viK\vek\n[]	p_	yj\bTv	US	[r\n{I\npsGt\vVK\nplS}\vWP	|dMD\vHV\bTR}M`\bTV\bVHlvCh\bW[Ke	R{\v^R	ab	BZ	VA	B`\nd|\nhsKe	BeOi	R{	d\\nB\bWZ	dZ	VJOs	muQ\vhZQ@QQ\nfI\bW[B\\li\nzU\nMdM`\nxS\bVV\n\\}\vxD	m\bTpIS\nc|	kVi~	V{\vhZ	|b\bWt\n@R\voA\vnU\bWI	ea	B`	iD	c}	TzBR\vQBNj	CP	[I\bTv	`WuN\vpg\vpg\vWc	iT	bs	wL	U_	c\\	|h\vKa	Nr	fL\nq|\nzu\nz\\	Nr\bUg	|bm`\bTv\nyd\nrp\bWf	UXBV\nzk\nd}	wQ	}fCe\ved\bTW\bSB\nxU	cn\bTb\ne	a\\	SG\bU|\npV\nN\\Kn\vnU	At	pD\v^R\vIrb[	R{	dE\vxD\vWK\vWA\bQL\bW@Su\bUd\nDM	PcCADloQ	Hswiub\na\bQpOb\nLP\bTlY[\vK}	AJ\bQn^\vsA\bSM\nqM\bWZ\n^W\vz{S|	fD\bVK\bTv\bPvBB	CPdF	id\vxsmx\vws	cC\ntC	ycM`\vW\nrh\bQp\vxD\\o\nsI_k\nzukF	fDXsXO	jp\bTvBS{B	Br\nzQ\nbI	c{BDBVnO\bTF	caJd	fL	PV	I_\nlK`o	wX\npa	gu\bP}{^\bWf\n{I	BN\npaKl\vpg	cn	fL\vvhCq\bTl\vnU\bSqCm	wR\bUJ\npe\nyd\nYgCy\vKW	fD\neaoQ	j_	BvnM\vID\bTa\nzApl\n]n\bTa	R{	fr\n_y\bUg{Xkk\vxD|Ixl\nfyCe\vwB\nLk\vd]\noi\n}h	Q]\npe\bVwHkOQ\nzk	AJ\npV\bPv\ny\\	A{Oi\bSBXA\veE	jp\nq}	iDqN\v^R	m	iZ	Br\bVg\noi\n\\X	U_\nc|\vHV\bTf	Tn\\N\\N\nuBlv\nyu	Td\bTf\bPL\v]W	dG\nA`\nw^\ngI\npe	dw\nz\\ia\bWZ	cFJm\n{Z\bWO_kDfRR	d\\\bVV\vxsBNtilm	Td	]y\vHV	So\v|jXX	A|\vZ^\vGu\bTWM`kF\vhZ\vVK	dG\vBl	ay\nxUqEnO\bVw\nqICX\ne	Pl\bWO\vLm	dLuHCm	dTfn\vwBka\vnU\n@M\nyT	Hv	\\}Kh	d~Yhk}\neR	d\\\bWI	|b	HK	iD\bTWMY\npl\bQ_	wr\vAx	HE\bTg\bSqvp\vb\\\bWO\nOl\nsI\nfy\vID	\\c\n{Z\n^~\npe\nAO	TT\vxvk_\bWO\v|j\vwB	Qy	i@	Pl	Ha	dZk}ra	UT\vJc\ved\np@	QN\nd|	kj	HkM`\noi	wr	d\\\nlq\no_\nlb\nL[	acBBBHCm\npl	IQ\bVK\vxs\n`e\viK\npaOi	US\bTp	fD\nPGkkXA\nz\\\neg\vWh	wRqN\nqS	cnlo\nxS\n^W	BU\nt	HE	p\\	fF	fw\bVV\bW@	ak\vVKls	VJ\bVV\veE\\o\nyX\nYmM`lL\nd|\nzk	A{sE	wQXT\nt	Pl	]y\vwT{pMD\vb\\	Q]Kj	Jn\nAH\vRb	BU	HK	\\c\nfIm\nqM\n@R	So\noiBT	Hv\n_yKh	BZ	]i\bUJ	V{Sr\nbI\vGg	a_\bTR\nfI\nfl	[K	IIS|\vuW	iI\bWI\nqI\v|jBV\bVg\bWZkF\vx]\bTA	ab	fr	i@	Jd	Jd\vps\nAO\bTaxu	iD\nzk	|d	|`\bW[	lP	dG\bVV\vw}\vqO	i[\bQ\bTz\vVF	wNts	dw\bTv\neS\ngi	NryS\npe\bVV\bSq\n`m	yj	BZ\vWX\bSB	c\\\nUR	[J	c_nM\bWQ\vAx\nMd	Brui\vxY\bSM\vWc\v|j\vxs	}Q	BO\bPL\bWW	fM\nAO	Pc\veUe^\bTg\nqI	ac\bPv	cFoQ	Q\vhZka\nz\\	iK	BU\n`k	CPS|M`\n{I	S{_O	BZZiSk	ps	p\\\nYu\n]s\nxC\bWt\nbD	kV\vGuyS\nqA	[r\neKM`	dZlL\bUg\bTl\nbD	US\vb\\	pV\nccS\\	ct	`z\bPL\vWs\nA`\neg\bSquECR\vDg	`W\vz{\vWcSkSk	bW\bUg	ea\nxZ	iI	UX	VJ\nqn	S{\vRb\bTQ\nplGt\vuWuj\npF\nqI	fL	[I	iaXO\nyu\vDg\ved	q{VG\bQka	Vj	kV	xB\nd|\np@	QN	Pc	ps]j	kV	oU\bTp\nzUnB\vB]	a{\bV@\n]nm`	cz	R{m`\bQa\vwT\bSMMYqN	dj~s\vQ}MY\vMB	Bv	wR\bRg\vQ}	ql\vKC\nrmxuCC\vwB\vvh	BqXq\npV	i_ObuE\nbd\nqo\v{i\nC~	BL\veEuH\bVjEyGz\vzR\v{i	cf\n{Z\n]nXA\vGu\vnU	hS\vGI\nCc	HE\bTA	HBBHCj\nCc\bTF	HE\nXI	A{\bQ	c\\\vmO\vWX\nfH\np@MY\bTF\nlK	Bt\nzU	TTKm\vwT\npV\ndt\vyI	Vx	Q	Rg	Td\nzU\bRS\nLM	wAnM	Tn\ndS	]g\nLc\vwB	}t	[I	CPkX\vFm\vhZm	i[\np@\vQ}\vW	|d\nMO\nMd	f_	fD	cJ	Hz\vRb	io	PyY[\nxU	ct\v@@	ww\bPvBMFF\ntbv|\vKm	Bq	BqKh`o\nZdXU	i]	|`	StB\\\bQ\v_W	TJ\nqI	|a	A{\vuPMD	Pl\nxR	fL\vws	c{	d\\\bV`\neg	HKkc\nd|\bVV\ny\\kc	i]\bVG	`V	ss	I_	AE	bs	du\nel	pD\vW\nqslv\bSMZi\vVKia\vQB	Q\n{Z\bPt\vKl\nlK\nhs\ndS\bVKmf\nd^	kV	cO\nc|\bVH	\\]\bTv\bSq	mI\vDg	VJ	cn\ny\\\bVg\bTv\nyX\bTF	]]\bTp\noi\nhs\veU\nBf	djMr\n|p	\\g	]r\bVb{D\nd[XN	fM	O\\s_	cf	iZXN\vWc	qv\n`m	U^oD\nd|\vGg	dE\vwflou}\nd|oQ	`iOi\vxD\ndZ\nCxYw\nzk\ntb\ngw	yj	B`\nyX\vps\ntC\vpP\vqw\bPu\bPX	Dm\npwNj	ss	aG\vxs\bPt\noLGz	Ok	i@	i]eC	IQ	ii	dj\v@J	|duh\bWZ\veU\vnU\bTa	cCg]\nzkYh\bVK\nLU\np@\ntb\ntR	Cj\vNP	i@\bP{\n\\}\n{c\nwX	fL\bVG	c{	|`	AJ	|C	fDln	|d	bs\nqI{B\vAx\np@\nzk\vRbOs\vWSe^\vD_	Bv\vWd\bVb\vxs\veE\bRw\n]n\n|p\vg|	fwkc\bTIka\n\\TSp	ju\vps\npeu|\vGr\bVe	CU]MXU\vxD\bTa	IQ\vWq	CU	am	dj\bSoSw\vnUCh	Q]s_\bPt	fS\bTa	\\}\n@OYc	UZ\bTx\npe\vnU\nzU	|}	iD\nz\\\bSM\vxDBR\nzQ	QN]MYh\nLP\vFm\vLXvc\vqlka	HK\bVb\ntC\nCy\bTv\nuVoQ	`z	[I	B`\vRb	yj	sb\vWs\bTl	kV\ved\nelL\vxN	m\nJn	jY\vxD\bVb\bSq\vyu	wL\vXL\bTA	pg	At	nDXX	wR\npl\nhwyS\nps	cO\bW[\v|jXN	sV	p\\	Be\nb~\nAJ\n]ek`qN	dw	WV	HE\vEVJz	id	B`	zhE]	fD\bTgqN\bTa	jaCv\bSM\nhc\bUet_	ieg]	wQ\nPn\bVB	jw\bVg\vbE	BZ\vRH\bP{	jp\n\\}	a_	cC	|a\vD]	BZ	i[	fD\vxW\no_	d\\\n_D\ntb	\\c	AJ\nlKoQlo\vLx\vM@\bWZKn\vpg\nTi\nIv\n|r\v@}JzLmWhk}ln\vxD\n]sgc\vps	Br\bTW\vBMtZ\nBYDW	jf\vSWC}\nqo	dE	mv	IQ\bPP\bUblvBC\nzQ	[I\vgl\nig\bUsBT\vbC\bSq	sU	iW\nJn	SY	HK	rg\npV\vID\v|jKO	`S	|a`vbmglfmujbqnbgqjgavp`bqjmj`jlwjfnslslqrvf`vfmwbfpwbglsvfgfmivfdlp`lmwqbfpw/Mmmlnaqfwjfmfmsfqejonbmfqbbnjdlp`jvgbg`fmwqlbvmrvfsvfgfpgfmwqlsqjnfqsqf`jlpfd/Vmavfmlpuloufqsvmwlppfnbmbkba/Abbdlpwlmvfulpvmjglp`bqolpfrvjslmj/]lpnv`klpbodvmb`lqqfljnbdfmsbqwjqbqqjabnbq/Abklnaqffnsoflufqgbg`bnajlnv`kbpevfqlmsbpbglo/Amfbsbqf`fmvfubp`vqplpfpwbabrvjfqlojaqlp`vbmwlb``fplnjdvfoubqjlp`vbwqlwjfmfpdqvslppfq/Mmfvqlsbnfgjlpeqfmwfb`fq`bgfn/Mplefqwb`l`kfpnlgfoljwbojbofwqbpbod/Vm`lnsqb`vbofpf{jpwf`vfqslpjfmglsqfmpboofdbqujbifpgjmfqlnvq`jbslgq/Msvfpwlgjbqjlsvfaolrvjfqfnbmvfosqlsjl`qjpjp`jfqwlpfdvqlnvfqwfevfmwf`fqqbqdqbmgffef`wlsbqwfpnfgjgbsqlsjbleqf`fwjfqqbf.nbjoubqjbpelqnbpevwvqllaifwlpfdvjqqjfpdlmlqnbpnjpnlp/Vmj`l`bnjmlpjwjlpqby/_mgfajglsqvfabwlofglwfm/Abifp/Vpfpsfql`l`jmblqjdfmwjfmgb`jfmwl`/Mgjykbaobqpfq/Abobwjmbevfqybfpwjoldvfqqbfmwqbq/E{jwlo/_sfybdfmgbu/Agflfujwbqsbdjmbnfwqlpibujfqsbgqfpe/M`jo`bafyb/Mqfbppbojgbfmu/Alibs/_mbavplpajfmfpwf{wlpoofubqsvfgbmevfqwf`ln/Vm`obpfpkvnbmlwfmjglajoablvmjgbgfpw/Mpfgjwbq`qfbgl<X<W=c=k=n<R<V<\\<V<T<W<T=a=n<R<^=m<Y<Y<_<R<S=l<T=n<\\<V<Y=e<Y=o<Z<Y<v<\\<V<]<Y<[<]=g<W<R<Q<T<~=m<Y<S<R<X<A=n<R=n<R<P=k<Y<P<Q<Y=n<W<Y=n=l<\\<[<R<Q<\\<_<X<Y<P<Q<Y<x<W=c<s=l<T<Q<\\=m<Q<T=i=n<Y<P<V=n<R<_<R<X<^<R=n=n<\\<P<M<D<|<P<\\=c<K=n<R<^<\\=m<^<\\<P<Y<P=o<N<\\<V<X<^<\\<Q<\\<P=a=n<T=a=n=o<~<\\<P=n<Y=i<S=l<R=n=o=n<Q<\\<X<X<Q=c<~<R=n=n=l<T<Q<Y<U<~<\\=m<Q<T<P=m<\\<P=n<R=n=l=o<]<r<Q<T<P<T=l<Q<Y<Y<r<r<r<W<T=j=a=n<\\<r<Q<\\<Q<Y<P<X<R<P<P<R<U<X<^<Y<R<Q<R=m=o<X\fHy\fIk\fHU\fId\fHy\fIl\fHT\fIk\fHy\fHR\fHy\fIg\fHx\fH\\\fHF\fH\\\fHD\fIk\fHc\fHy\fHy\fHS\fHA\fIl\fHk\fHT\fHy\fH\\\fHH\fIg\fHU\fIg\fHj\fHF\fHU\fIl\fHC\fHU\fHC\fHR\fHH\fHy\fHI\fHRibdqbm\fHj\fHp\fHp\fIg\fHi\fH@\fHJ\fIg\fH{\fHd\fHp\fHR\fH{\fHc\fHU\fHB\fHk\fHD\fHY\fHU\fHC\fIk\fHI\fIk\fHI\fIl\fHt\fH\\\fHp\fH@\fHJ\fIl\fHy\fHd\fHp\fIl\fHY\fIk\fHD\fHd\fHD\fHc\fHU\fH\\\fHe\fHT\fHB\fIk\fHy\fHB\fHY\fIg\fH^\fIk\fHT\fH@\fHB\fHd\fHJ\fIk\fH\fH\\\fHj\fHB\fH@\fHT\fHA\fH\\\fH@\fHD\fHv\fH^\fHB\fHD\fHj\fH{\fHT\fIl\fH^\fIl4U5h5e4I5h5e5k4\\4K4N4B4]4U4C4C4K5h5e5k4\\5k4Y5d4]4V5f4]5o4K5j5d5h4K4D5f5j4U4]4Z4\\5h5o5k5j4K5f5d5i5n4K5h4U5h5f4K5j4K5h5o5j4A4F5e5n4D5h5d4A4E4K4B4]5m5n4[4U4D4C4]5o5j4I4\\4K5o5i4K4K4A4C4I5h4K5m5f5k4D4U4Z5o5f5m4D4A4G5d5i5j5d5k5d4O5j4K4@4C4K5h5k4K4_5h5i4U5j4C5h5f4_4U4D4]4Y5h5e5i5j4\\4D5k4K4O5j5k5i4G5h5o5j4F4K5h4K4A5f4G5i4Y4]4X4]4A4A5d5h5d5m5f4K4\\4K5h5o5h5i4]4E4K5j4F4K5h5m4O4D5d4B4K4Y4O5j4F4K5j5k4K5h5f4U4Z5d5d5n4C4K4D5j4B5f4]4D5j4F5h5o5i4X4K4M5d5k5f4K4D5d5n4Y4Y5d5i4K4]5n5i4O4A4C5j4A5j4U4C5i4]4O5f4K4A4E5o4F4D4C5d5j5f4@4D5i5j5k4F4A4F4@5k4E4_5j4E5f4F5i5o4]4E4V4^4E5j5m4_4D5f4F5h5h5k5h5j4K4F5h5o5n5h4D5h5i4K4U5j5k4O5d5h4X5f4M5j5d4]4O5i4K5m5f5o4D5o5h4\\4K4F4]4F4D4D4O5j5k5i4_4K5j5o4D5f4U5m5n4C4A4_5j5h5k5i4X4U4]4O5k5h4X5k4]5n4[4]4[5h4Dsqlejofpfquj`fgfebvowkjnpfoegfwbjop`lmwfmwpvsslqwpwbqwfgnfppbdfpv``fppebpkjlm?wjwof=`lvmwqzb``lvmw`qfbwfgpwlqjfpqfpvowpqvmmjmdsql`fpptqjwjmdlaif`wpujpjaoftfo`lnfbqwj`ofvmhmltmmfwtlqh`lnsbmzgzmbnj`aqltpfqsqjub`zsqlaofnPfquj`fqfpsf`wgjpsobzqfrvfpwqfpfquftfapjwfkjpwlqzeqjfmgplswjlmptlqhjmdufqpjlmnjoojlm`kbmmfotjmglt-bggqfppujpjwfgtfbwkfq`lqqf`wsqlgv`wfgjqf`welqtbqgzlv#`bmqfnlufgpvaif`w`lmwqlobq`kjuf`vqqfmwqfbgjmdojaqbqzojnjwfgnbmbdfqevqwkfqpvnnbqznb`kjmfnjmvwfpsqjubwf`lmwf{wsqldqbnpl`jfwzmvnafqptqjwwfmfmbaofgwqjddfqplvq`fpolbgjmdfofnfmwsbqwmfqejmboozsfqef`wnfbmjmdpzpwfnphffsjmd`vowvqf%rvlw8/ilvqmbosqlif`wpvqeb`fp%rvlw8f{sjqfpqfujftpabobm`fFmdojpk@lmwfmwwkqlvdkSofbpf#lsjmjlm`lmwb`wbufqbdfsqjnbqzujoobdfPsbmjpkdboofqzgf`ojmfnffwjmdnjppjlmslsvobqrvbojwznfbpvqfdfmfqbopsf`jfppfppjlmpf`wjlmtqjwfqp`lvmwfqjmjwjboqfslqwpejdvqfpnfnafqpklogjmdgjpsvwffbqojfqf{sqfppgjdjwbosj`wvqfBmlwkfqnbqqjfgwqbeej`ofbgjmd`kbmdfg`fmwqbouj`wlqzjnbdfp,qfbplmppwvgjfpefbwvqfojpwjmdnvpw#afp`kllopUfqpjlmvpvboozfsjplgfsobzjmddqltjmdlaujlvplufqobzsqfpfmwb`wjlmp?,vo=	tqbssfqboqfbgz`fqwbjmqfbojwzpwlqbdfbmlwkfqgfphwlsleefqfgsbwwfqmvmvpvboGjdjwbo`bsjwboTfapjwfebjovqf`lmmf`wqfgv`fgBmgqljggf`bgfpqfdvobq#%bns8#bmjnbopqfofbpfBvwlnbwdfwwjmdnfwklgpmlwkjmdSlsvobq`bswjlmofwwfqp`bswvqfp`jfm`foj`fmpf`kbmdfpFmdobmg>2%bns8Kjpwlqz#>#mft#@fmwqbovsgbwfgPsf`jboMfwtlqhqfrvjqf`lnnfmwtbqmjmd@loofdfwlloabqqfnbjmpaf`bvpffof`wfgGfvwp`kejmbm`ftlqhfqprvj`hozafwtffmf{b`wozpfwwjmdgjpfbpfPl`jfwztfbslmpf{kjajw%ow8\"..@lmwqlo`obppfp`lufqfglvwojmfbwwb`hpgfuj`fp+tjmgltsvqslpfwjwof>!Nlajof#hjoojmdpkltjmdJwbojbmgqlssfgkfbujozfeef`wp.2$^*8	`lmejqn@vqqfmwbgubm`fpkbqjmdlsfmjmdgqbtjmdajoojlmlqgfqfgDfqnbmzqfobwfg?,elqn=jm`ovgftkfwkfqgfejmfgP`jfm`f`bwboldBqwj`ofavwwlmpobqdfpwvmjelqnilvqmfzpjgfabq@kj`bdlklojgbzDfmfqbosbppbdf/%rvlw8bmjnbwfeffojmdbqqjufgsbppjmdmbwvqboqlvdkoz-		Wkf#avw#mlwgfmpjwzAqjwbjm@kjmfpfob`h#lewqjavwfJqfobmg!#gbwb.eb`wlqpqf`fjufwkbw#jpOjaqbqzkvpabmgjm#eb`wbeebjqp@kbqofpqbgj`boaqlvdkwejmgjmdobmgjmd9obmd>!qfwvqm#ofbgfqpsobmmfgsqfnjvnsb`hbdfBnfqj`bFgjwjlm^%rvlw8Nfppbdfmffg#wlubovf>!`lnsof{ollhjmdpwbwjlmafojfufpnboofq.nlajofqf`lqgptbmw#wlhjmg#leEjqfel{zlv#bqfpjnjobqpwvgjfgnb{jnvnkfbgjmdqbsjgoz`ojnbwfhjmdglnfnfqdfgbnlvmwpelvmgfgsjlmffqelqnvobgzmbpwzklt#wl#Pvsslqwqfufmvff`lmlnzQfpvowpaqlwkfqplogjfqobqdfoz`boojmd-%rvlw8B``lvmwFgtbqg#pfdnfmwQlafqw#feelqwpSb`jej`ofbqmfgvs#tjwkkfjdkw9tf#kbufBmdfofpmbwjlmp\\pfbq`kbssojfgb`rvjqfnbppjufdqbmwfg9#ebopfwqfbwfgajddfpwafmfejwgqjujmdPwvgjfpnjmjnvnsfqkbspnlqmjmdpfoojmdjp#vpfgqfufqpfubqjbmw#qlof>!njppjmdb`kjfufsqlnlwfpwvgfmwplnflmff{wqfnfqfpwlqfalwwln9fuloufgboo#wkfpjwfnbsfmdojpktbz#wl##Bvdvpwpznalop@lnsbmznbwwfqpnvpj`bobdbjmpwpfqujmd~*+*8	sbznfmwwqlvaof`lm`fsw`lnsbqfsbqfmwpsobzfqpqfdjlmpnlmjwlq#$$Wkf#tjmmjmdf{solqfbgbswfgDboofqzsqlgv`fbajojwzfmkbm`f`bqffqp*-#Wkf#`loof`wPfbq`k#bm`jfmwf{jpwfgellwfq#kbmgofqsqjmwfg`lmplofFbpwfqmf{slqwptjmgltp@kbmmfojoofdbomfvwqbopvddfpw\\kfbgfqpjdmjmd-kwno!=pfwwofgtfpwfqm`bvpjmd.tfahjw`objnfgIvpwj`f`kbswfquj`wjnpWklnbp#nlyjoobsqlnjpfsbqwjfpfgjwjlmlvwpjgf9ebopf/kvmgqfgLoznsj`\\avwwlmbvwklqpqfb`kfg`kqlmj`gfnbmgppf`lmgpsqlwf`wbglswfgsqfsbqfmfjwkfqdqfbwozdqfbwfqlufqboojnsqluf`lnnbmgpsf`jbopfbq`k-tlqpkjsevmgjmdwklvdkwkjdkfpwjmpwfbgvwjojwzrvbqwfq@vowvqfwfpwjmd`ofbqozf{slpfgAqltpfqojafqbo~#`bw`kSqlif`wf{bnsofkjgf+*8EolqjgbbmptfqpbooltfgFnsfqlqgfefmpfpfqjlvpeqffglnPfufqbo.avwwlmEvqwkfqlvw#le#\">#mvoowqbjmfgGfmnbqhuljg+3*,boo-ipsqfufmwQfrvfpwPwfskfm		Tkfm#lapfquf?,k1=	Nlgfqm#sqlujgf!#bow>!alqgfqp-		Elq#		Nbmz#bqwjpwpsltfqfgsfqelqnej`wjlmwzsf#lenfgj`bowj`hfwplsslpfg@lvm`jotjwmfppivpwj`fDflqdf#Afodjvn---?,b=wtjwwfqmlwbaoztbjwjmdtbqebqf#Lwkfq#qbmhjmdskqbpfpnfmwjlmpvqujufp`klobq?,s=	#@lvmwqzjdmlqfgolpp#leivpw#bpDflqdjbpwqbmdf?kfbg=?pwlssfg2$^*8	jpobmgpmlwbaofalqgfq9ojpw#le`bqqjfg233/333?,k0=	#pfufqboaf`lnfppfof`w#tfggjmd33-kwnonlmbq`klee#wkfwfb`kfqkjdkoz#ajloldzojef#lelq#fufmqjpf#le%qbrvl8sovplmfkvmwjmd+wklvdkGlvdobpiljmjmd`jq`ofpElq#wkfBm`jfmwUjfwmbnufkj`ofpv`k#bp`qzpwboubovf#>Tjmgltpfmilzfgb#pnboobppvnfg?b#jg>!elqfjdm#Boo#qjklt#wkfGjpsobzqfwjqfgkltfufqkjggfm8abwwofppffhjmd`bajmfwtbp#mlwollh#bw`lmgv`wdfw#wkfIbmvbqzkbssfmpwvqmjmdb9klufqLmojmf#Eqfm`k#ob`hjmdwzsj`bof{wqb`wfmfnjfpfufm#jedfmfqbwgf`jgfgbqf#mlw,pfbq`kafojfep.jnbdf9ol`bwfgpwbwj`-oldjm!=`lmufqwujlofmwfmwfqfgejqpw!=`jq`vjwEjmobmg`kfnjpwpkf#tbp23s{8!=bp#pv`kgjujgfg?,psbm=tjoo#afojmf#leb#dqfbwnzpwfqz,jmgf{-eboojmdgvf#wl#qbjotbz`loofdfnlmpwfqgfp`fmwjw#tjwkmv`ofbqIftjpk#sqlwfpwAqjwjpkeoltfqpsqfgj`wqfelqnpavwwlm#tkl#tbpof`wvqfjmpwbmwpvj`jgfdfmfqj`sfqjlgpnbqhfwpPl`jbo#ejpkjmd`lnajmfdqbskj`tjmmfqp?aq#,=?az#wkf#MbwvqboSqjub`z`llhjfplvw`lnfqfploufPtfgjpkaqjfeozSfqpjbmpl#nv`k@fmwvqzgfsj`wp`lovnmpklvpjmdp`qjswpmf{w#wlafbqjmdnbssjmdqfujpfgiRvfqz+.tjgwk9wjwof!=wllowjsPf`wjlmgfpjdmpWvqhjpkzlvmdfq-nbw`k+~*+*8		avqmjmdlsfqbwfgfdqffpplvq`f>Qj`kbqg`olpfozsobpwj`fmwqjfp?,wq=	`lolq9 vo#jg>!slppfppqloojmdskzpj`pebjojmdf{f`vwf`lmwfpwojmh#wlGfebvow?aq#,=	9#wqvf/`kbqwfqwlvqjpn`obppj`sql`ffgf{sobjm?,k2=	lmojmf-<{no#ufkfosjmdgjbnlmgvpf#wkfbjqojmffmg#..=*-bwwq+qfbgfqpklpwjmd eeeeeeqfbojyfUjm`fmwpjdmbop#pq`>!,Sqlgv`wgfpsjwfgjufqpfwfoojmdSvaoj`#kfog#jmIlpfsk#wkfbwqfbeef`wp?pwzof=b#obqdfglfpm$wobwfq/#Fofnfmwebuj`lm`qfbwlqKvmdbqzBjqslqwpff#wkfpl#wkbwNj`kbfoPzpwfnpSqldqbnp/#bmg##tjgwk>f%rvlw8wqbgjmdofew!=	sfqplmpDlogfm#Beebjqpdqbnnbqelqnjmdgfpwqlzjgfb#le`bpf#lelogfpw#wkjp#jp-pq`#>#`bqwllmqfdjpwq@lnnlmpNvpojnpTkbw#jpjm#nbmznbqhjmdqfufbopJmgffg/frvbooz,pklt\\blvwgllqfp`bsf+Bvpwqjbdfmfwj`pzpwfn/Jm#wkf#pjwwjmdKf#boplJpobmgpB`bgfnz	\n\n?\"..Gbmjfo#ajmgjmdaol`h!=jnslpfgvwjojyfBaqbkbn+f{`fswxtjgwk9svwwjmd*-kwno+#X^8	GBWBX#)hjw`kfmnlvmwfgb`wvbo#gjbof`wnbjmoz#\\aobmh$jmpwboof{sfqwpje+wzsfJw#bopl%`lsz8#!=Wfqnpalqm#jmLswjlmpfbpwfqmwbohjmd`lm`fqmdbjmfg#lmdljmdivpwjez`qjwj`peb`wlqzjwp#ltmbppbvowjmujwfgobpwjmdkjp#ltmkqfe>!,!#qfo>!gfufols`lm`fqwgjbdqbngloobqp`ovpwfqsks<jg>bo`lklo*8~*+*8vpjmd#b=?psbm=ufppfopqfujuboBggqfppbnbwfvqbmgqljgboofdfgjoomfpptbohjmd`fmwfqprvbojeznbw`kfpvmjejfgf{wjm`wGfefmpfgjfg#jm	\n?\"..#`vpwlnpojmhjmdOjwwof#Allh#lefufmjmdnjm-ip<bqf#wkfhlmwbhwwlgbz$p-kwno!#wbqdfw>tfbqjmdBoo#Qjd8	~*+*8qbjpjmd#Bopl/#`qv`jbobalvw!=gf`obqf..=	?p`ejqfel{bp#nv`kbssojfpjmgf{/#p/#avw#wzsf#>#		?\"..wltbqgpQf`lqgpSqjubwfElqfjdmSqfnjfq`klj`fpUjqwvboqfwvqmp@lnnfmwSltfqfgjmojmf8slufqwz`kbnafqOjujmd#ulovnfpBmwklmzoldjm!#QfobwfgF`lmlnzqfb`kfp`vwwjmddqbujwzojef#jm@kbswfq.pkbgltMlwbaof?,wg=	#qfwvqmpwbgjvntjgdfwpubqzjmdwqbufopkfog#aztkl#bqftlqh#jmeb`vowzbmdvobqtkl#kbgbjqslqwwltm#le		Plnf#$`oj`h$`kbqdfphfztlqgjw#tjoo`jwz#le+wkjp*8Bmgqft#vmjrvf#`kf`hfglq#nlqf033s{8#qfwvqm8qpjlm>!sovdjmptjwkjm#kfqpfoePwbwjlmEfgfqboufmwvqfsvaojpkpfmw#wlwfmpjlmb`wqfpp`lnf#wlejmdfqpGvhf#lesflsof/f{soljwtkbw#jpkbqnlmzb#nbilq!9!kwwsjm#kjp#nfmv!=	nlmwkozleej`fq`lvm`jodbjmjmdfufm#jmPvnnbqzgbwf#leolzbowzejwmfppbmg#tbpfnsfqlqpvsqfnfPf`lmg#kfbqjmdQvppjbmolmdfpwBoafqwbobwfqbopfw#le#pnboo!=-bssfmggl#tjwkefgfqboabmh#leafmfbwkGfpsjwf@bsjwbodqlvmgp*/#bmg#sfq`fmwjw#eqln`olpjmd`lmwbjmJmpwfbgejewffmbp#tfoo-zbkll-qfpslmgejdkwfqlap`vqfqfeof`wlqdbmj`>#Nbwk-fgjwjmdlmojmf#sbggjmdb#tkloflmfqqlqzfbq#lefmg#le#abqqjfqtkfm#jwkfbgfq#klnf#leqfpvnfgqfmbnfgpwqlmd=kfbwjmdqfwbjmp`olvgeqtbz#le#Nbq`k#2hmltjmdjm#sbqwAfwtffmofpplmp`olpfpwujqwvboojmhp!=`qlppfgFMG#..=ebnlvp#btbqgfgOj`fmpfKfbowk#ebjqoz#tfbowkznjmjnboBeqj`bm`lnsfwfobafo!=pjmdjmdebqnfqpAqbpjo*gjp`vppqfsob`fDqfdlqzelmw#`lsvqpvfgbssfbqpnbhf#vsqlvmgfgalwk#leaol`hfgpbt#wkfleej`fp`lolvqpje+gl`vtkfm#kffmelq`fsvpk+evBvdvpw#VWE.;!=Ebmwbpzjm#nlpwjmivqfgVpvboozebqnjmd`olpvqflaif`w#gfefm`fvpf#le#Nfgj`bo?algz=	fujgfmwaf#vpfghfz@lgfpj{wffmJpobnj` 333333fmwjqf#tjgfoz#b`wjuf#+wzsflelmf#`bm`lolq#>psfbhfqf{wfmgpSkzpj`pwfqqbjm?walgz=evmfqboujftjmdnjggof#`qj`hfwsqlskfwpkjewfggl`wlqpQvppfoo#wbqdfw`lnsb`wbodfaqbpl`jbo.avoh#lenbm#bmg?,wg=	#kf#ofew*-ubo+*ebopf*8oldj`boabmhjmdklnf#wlmbnjmd#Bqjylmb`qfgjwp*8	~*8	elvmgfqjm#wvqm@loojmpafelqf#Avw#wkf`kbqdfgWjwof!=@bswbjmpsfoofgdlggfppWbd#..=Bggjmd9avw#tbpQf`fmw#sbwjfmwab`h#jm>ebopf%Ojm`lomtf#hmlt@lvmwfqIvgbjpnp`qjsw#bowfqfg$^*8	##kbp#wkfvm`ofbqFufmw$/alwk#jmmlw#boo		?\"..#sob`jmdkbqg#wl#`fmwfqplqw#le`ojfmwppwqffwpAfqmbqgbppfqwpwfmg#wlebmwbpzgltm#jmkbqalvqEqffglniftfoqz,balvw--pfbq`kofdfmgpjp#nbgfnlgfqm#lmoz#lmlmoz#wljnbdf!#ojmfbq#sbjmwfqbmg#mlwqbqfoz#b`qlmzngfojufqpklqwfq33%bns8bp#nbmztjgwk>!,)#?\"X@wjwof#>le#wkf#oltfpw#sj`hfg#fp`bsfgvpfp#lesflsofp#Svaoj`Nbwwkftwb`wj`pgbnbdfgtbz#elqobtp#lefbpz#wl#tjmgltpwqlmd##pjnsof~`bw`k+pfufmwkjmelal{tfmw#wlsbjmwfg`jwjyfmJ#glm$wqfwqfbw-#Plnf#tt-!*8	alnajmdnbjowl9nbgf#jm-#Nbmz#`bqqjfpx~8tjtlqh#lepzmlmzngfefbwpebulqfglswj`bosbdfWqbvmofpp#pfmgjmdofew!=?`lnP`lqBoo#wkfiRvfqz-wlvqjpw@obppj`ebopf!#Tjokfonpvavqapdfmvjmfajpklsp-psojw+dolabo#elooltpalgz#lemlnjmbo@lmwb`wpf`vobqofew#wl`kjfeoz.kjggfm.abmmfq?,oj=		-#Tkfm#jm#alwkgjpnjppF{solqfbotbzp#ujb#wkfpsb/]lotfoebqfqvojmd#bqqbmdf`bswbjmkjp#plmqvof#lekf#wllhjwpfoe/>3%bns8+`boofgpbnsofpwl#nbhf`ln,sbdNbqwjm#Hfmmfgzb``fswpevoo#lekbmgofgAfpjgfp,,..=?,baof#wlwbqdfwpfppfm`fkjn#wl#jwp#az#`lnnlm-njmfqbowl#wbhftbzp#wlp-lqd,obgujpfgsfmbowzpjnsof9je#wkfzOfwwfqpb#pklqwKfqafqwpwqjhfp#dqlvsp-ofmdwkeojdkwplufqobspoltoz#ofppfq#pl`jbo#?,s=	\n\njw#jmwlqbmhfg#qbwf#levo=	##bwwfnswsbjq#lenbhf#jwHlmwbhwBmwlmjlkbujmd#qbwjmdp#b`wjufpwqfbnpwqbssfg!*-`pp+klpwjofofbg#wlojwwof#dqlvsp/Sj`wvqf..=		#qltp>!#laif`wjmufqpf?ellwfq@vpwlnU=?_,p`qploujmd@kbnafqpobufqztlvmgfgtkfqfbp\">#$vmgelq#boosbqwoz#.qjdkw9Bqbajbmab`hfg#`fmwvqzvmjw#lenlajof.Fvqlsf/jp#klnfqjph#legfpjqfg@ojmwlm`lpw#lebdf#le#af`lnf#mlmf#les%rvlw8Njggof#fbg$*X3@qjwj`ppwvgjlp=%`lsz8dqlvs!=bppfnaonbhjmd#sqfppfgtjgdfw-sp9!#<#qfavjowaz#plnfElqnfq#fgjwlqpgfobzfg@bmlmj`kbg#wkfsvpkjmd`obpp>!avw#bqfsbqwjboAbazolmalwwln#`bqqjfq@lnnbmgjwp#vpfBp#tjwk`lvqpfpb#wkjqggfmlwfpbopl#jmKlvpwlm13s{8!=b``vpfgglvaof#dlbo#leEbnlvp#*-ajmg+sqjfpwp#Lmojmfjm#Ivozpw#(#!d`lmpvowgf`jnbokfosevoqfujufgjp#ufqzq$($jswolpjmd#efnbofpjp#boplpwqjmdpgbzp#lebqqjuboevwvqf#?laif`welq`jmdPwqjmd+!#,=	\n\nkfqf#jpfm`lgfg-##Wkf#aboollmglmf#az,`lnnlmad`lolqobt#le#Jmgjbmbbuljgfgavw#wkf1s{#0s{irvfqz-bewfq#bsloj`z-nfm#bmgellwfq.>#wqvf8elq#vpfp`qffm-Jmgjbm#jnbdf#>ebnjoz/kwws9,,#%maps8gqjufqpfwfqmbopbnf#bpmlwj`fgujftfqp~*+*8	#jp#nlqfpfbplmpelqnfq#wkf#mftjp#ivpw`lmpfmw#Pfbq`ktbp#wkftkz#wkfpkjssfgaq=?aq=tjgwk9#kfjdkw>nbgf#le`vjpjmfjp#wkbwb#ufqz#Bgnjqbo#ej{fg8mlqnbo#NjppjlmSqfpp/#lmwbqjl`kbqpfwwqz#wl#jmubgfg>!wqvf!psb`jmdjp#nlpwb#nlqf#wlwboozeboo#le~*8	##jnnfmpfwjnf#jmpfw#lvwpbwjpezwl#ejmggltm#wlolw#le#Sobzfqpjm#Ivmfrvbmwvnmlw#wkfwjnf#wlgjpwbmwEjmmjpkpq`#>#+pjmdof#kfos#leDfqnbm#obt#bmgobafofgelqfpwp`llhjmdpsb`f!=kfbgfq.tfoo#bpPwbmofzaqjgdfp,dolabo@qlbwjb#Balvw#X3^8	##jw/#bmgdqlvsfgafjmd#b*xwkqltkf#nbgfojdkwfqfwkj`boEEEEEE!alwwln!ojhf#b#fnsolzpojuf#jmbp#pffmsqjmwfqnlpw#leva.ojmhqfif`wpbmg#vpfjnbdf!=pv``ffgeffgjmdMv`ofbqjmelqnbwl#kfosTlnfm$pMfjwkfqNf{j`bmsqlwfjm?wbaof#az#nbmzkfbowkzobtpvjwgfujpfg-svpk+xpfoofqppjnsoz#Wkqlvdk-`llhjf#Jnbdf+logfq!=vp-ip!=#Pjm`f#vmjufqpobqdfq#lsfm#wl\"..#fmgojfp#jm$^*8	##nbqhfwtkl#jp#+!GLN@lnbmbdfglmf#elqwzsfle#Hjmdglnsqlejwpsqlslpfwl#pklt`fmwfq8nbgf#jwgqfppfgtfqf#jmnj{wvqfsqf`jpfbqjpjmdpq`#>#$nbhf#b#pf`vqfgAbswjpwulwjmd#	\n\nubq#Nbq`k#1dqft#vs@ojnbwf-qfnlufphjoofgtbz#wkf?,kfbg=eb`f#leb`wjmd#qjdkw!=wl#tlqhqfgv`fpkbp#kbgfqf`wfgpklt+*8b`wjlm>allh#lebm#bqfb>>#!kww?kfbgfq	?kwno=`lmelqneb`jmd#`llhjf-qfoz#lmklpwfg#-`vpwlnkf#tfmwavw#elqpsqfbg#Ebnjoz#b#nfbmplvw#wkfelqvnp-ellwbdf!=Nlajo@ofnfmwp!#jg>!bp#kjdkjmwfmpf..=?\"..efnbof#jp#pffmjnsojfgpfw#wkfb#pwbwfbmg#kjpebpwfpwafpjgfpavwwlm\\alvmgfg!=?jnd#Jmelal{fufmwp/b#zlvmdbmg#bqfMbwjuf#`kfbsfqWjnflvwbmg#kbpfmdjmfptlm#wkf+nlpwozqjdkw9#ejmg#b#.alwwlnSqjm`f#bqfb#lenlqf#lepfbq`k\\mbwvqf/ofdboozsfqjlg/obmg#lelq#tjwkjmgv`fgsqlujmdnjppjofol`boozBdbjmpwwkf#tbzh%rvlw8s{8!=	svpkfg#babmglmmvnfqbo@fqwbjmJm#wkjpnlqf#jmlq#plnfmbnf#jpbmg/#jm`qltmfgJPAM#3.`qfbwfpL`wlafqnbz#mlw`fmwfq#obwf#jmGfefm`ffmb`wfgtjpk#wlaqlbgoz`llojmdlmolbg>jw-#Wkfqf`lufqNfnafqpkfjdkw#bppvnfp?kwno=	sflsof-jm#lmf#>tjmgltellwfq\\b#dllg#qfhobnblwkfqp/wl#wkjp\\`llhjfsbmfo!=Olmglm/gfejmfp`qvpkfgabswjpn`lbpwbopwbwvp#wjwof!#nluf#wlolpw#jmafwwfq#jnsojfpqjuboqzpfqufqp#PzpwfnSfqkbspfp#bmg#`lmwfmgeoltjmdobpwfg#qjpf#jmDfmfpjpujft#leqjpjmd#pffn#wlavw#jm#ab`hjmdkf#tjoodjufm#bdjujmd#`jwjfp-eolt#le#Obwfq#boo#avwKjdktbzlmoz#azpjdm#lekf#glfpgjeefqpabwwfqz%bns8obpjmdofpwkqfbwpjmwfdfqwbhf#lmqfevpfg`boofg#>VP%bnsPff#wkfmbwjufpaz#wkjppzpwfn-kfbg#le9klufq/ofpajbmpvqmbnfbmg#boo`lnnlm,kfbgfq\\\\sbqbnpKbqubqg,sj{fo-qfnlubopl#olmdqlof#leiljmwozphzp`qbVmj`lgfaq#,=	Bwobmwbmv`ofvp@lvmwz/svqfoz#`lvmw!=fbpjoz#avjog#blm`oj`hb#djufmsljmwfqk%rvlw8fufmwp#fopf#x	gjwjlmpmlt#wkf/#tjwk#nbm#tkllqd,Tfalmf#bmg`buboqzKf#gjfgpfbwwof33/333#xtjmgltkbuf#wlje+tjmgbmg#jwpplofoz#n%rvlw8qfmftfgGfwqljwbnlmdpwfjwkfq#wkfn#jmPfmbwlqVp?,b=?Hjmd#leEqbm`jp.sqlgv`kf#vpfgbqw#bmgkjn#bmgvpfg#azp`lqjmdbw#klnfwl#kbufqfobwfpjajojwzeb`wjlmAveebolojmh!=?tkbw#kfeqff#wl@jwz#le`lnf#jmpf`wlqp`lvmwfglmf#gbzmfqulvpprvbqf#~8je+dljm#tkbwjnd!#bojp#lmozpfbq`k,wvfpgbzollpfozPlolnlmpf{vbo#.#?b#kqnfgjvn!GL#MLW#Eqbm`f/tjwk#b#tbq#bmgpf`lmg#wbhf#b#=			nbqhfw-kjdktbzglmf#jm`wjujwz!obpw!=laojdfgqjpf#wl!vmgfejnbgf#wl#Fbqoz#sqbjpfgjm#jwp#elq#kjpbwkofwfIvsjwfqZbkll\"#wfqnfg#pl#nbmzqfbooz#p-#Wkf#b#tlnbm<ubovf>gjqf`w#qjdkw!#aj`z`ofb`jmd>!gbz#bmgpwbwjmdQbwkfq/kjdkfq#Leej`f#bqf#mltwjnfp/#tkfm#b#sbz#elqlm#wkjp.ojmh!=8alqgfqbqlvmg#bmmvbo#wkf#Mftsvw#wkf-`ln!#wbhjm#wlb#aqjfe+jm#wkfdqlvsp-8#tjgwkfmyznfppjnsof#jm#obwfxqfwvqmwkfqbszb#sljmwabmmjmdjmhp!=	+*8!#qfb#sob`f_v330@bbalvw#bwq=	\n\n``lvmw#djufp#b?P@QJSWQbjotbzwkfnfp,wlloal{AzJg+!{kvnbmp/tbw`kfpjm#plnf#je#+tj`lnjmd#elqnbwp#Vmgfq#avw#kbpkbmgfg#nbgf#azwkbm#jmefbq#legfmlwfg,jeqbnfofew#jmulowbdfjm#fb`kb%rvlw8abpf#leJm#nbmzvmgfqdlqfdjnfpb`wjlm#?,s=	?vpwlnUb8%dw8?,jnslqwplq#wkbwnlpwoz#%bns8qf#pjyf>!?,b=?,kb#`obppsbppjufKlpw#>#TkfwkfqefqwjofUbqjlvp>X^8+ev`bnfqbp,=?,wg=b`wp#bpJm#plnf=		?\"lqdbmjp#?aq#,=Afjijmd`bwbo/Lgfvwp`kfvqlsfvfvphbqbdbfjodfpufmphbfpsb/]bnfmpbifvpvbqjlwqbabiln/E{j`ls/Mdjmbpjfnsqfpjpwfnbl`wvaqfgvqbmwfb/]bgjqfnsqfpbnlnfmwlmvfpwqlsqjnfqbwqbu/Epdqb`jbpmvfpwqbsql`fplfpwbglp`bojgbgsfqplmbm/Vnfqlb`vfqgln/Vpj`bnjfnaqllefqwbpbodvmlpsb/Apfpfifnsolgfqf`klbgfn/Mpsqjubglbdqfdbqfmob`fpslpjaofklwfofppfujoobsqjnfql/Vowjnlfufmwlpbq`kjul`vowvqbnvifqfpfmwqbgbbmvm`jlfnabqdlnfq`bgldqbmgfpfpwvgjlnfilqfpefaqfqlgjpf/]lwvqjpnl`/_gjdlslqwbgbfpsb`jlebnjojbbmwlmjlsfqnjwfdvbqgbqbodvmbpsqf`jlpbodvjfmpfmwjglujpjwbpw/Awvol`lml`fqpfdvmgl`lmpfileqbm`jbnjmvwlppfdvmgbwfmfnlpfef`wlpn/Mobdbpfpj/_mqfujpwbdqbmbgb`lnsqbqjmdqfpldbq`/Abb``j/_mf`vbglqrvjfmfpjm`ovplgfafq/Mnbwfqjbklnaqfpnvfpwqbslgq/Abnb/]bmb/Vowjnbfpwbnlplej`jbowbnajfmmjmd/Vmpbovglpslgfnlpnfilqbqslpjwjlmavpjmfppklnfsbdfpf`vqjwzobmdvbdfpwbmgbqg`bnsbjdmefbwvqfp`bwfdlqzf{wfqmbo`kjogqfmqfpfqufgqfpfbq`kf{`kbmdfebulqjwfwfnsobwfnjojwbqzjmgvpwqzpfquj`fpnbwfqjbosqlgv`wpy.jmgf{9`lnnfmwpplewtbqf`lnsofwf`bofmgbqsobwelqnbqwj`ofpqfrvjqfgnlufnfmwrvfpwjlmavjogjmdslojwj`pslppjaofqfojdjlmskzpj`boeffgab`hqfdjpwfqsj`wvqfpgjpbaofgsqlwl`lobvgjfm`fpfwwjmdpb`wjujwzfofnfmwpofbqmjmdbmzwkjmdbapwqb`wsqldqfpplufqujftnbdbyjmff`lmlnj`wqbjmjmdsqfppvqfubqjlvp#?pwqlmd=sqlsfqwzpklssjmdwldfwkfqbgubm`fgafkbujlqgltmolbgefbwvqfgellwaboopfof`wfgObmdvbdfgjpwbm`fqfnfnafqwqb`hjmdsbpptlqgnlgjejfgpwvgfmwpgjqf`wozejdkwjmdmlqwkfqmgbwbabpfefpwjuboaqfbhjmdol`bwjlmjmwfqmfwgqlsgltmsqb`wj`ffujgfm`fevm`wjlmnbqqjbdfqfpslmpfsqlaofnpmfdbwjufsqldqbnpbmbozpjpqfofbpfgabmmfq!=svq`kbpfsloj`jfpqfdjlmbo`qfbwjufbqdvnfmwallhnbqhqfefqqfq`kfnj`bogjujpjlm`booab`hpfsbqbwfsqlif`wp`lmeoj`wkbqgtbqfjmwfqfpwgfojufqznlvmwbjmlawbjmfg>#ebopf8elq+ubq#b``fswfg`bsb`jwz`lnsvwfqjgfmwjwzbjq`qbewfnsolzfgsqlslpfgglnfpwj`jm`ovgfpsqlujgfgklpsjwboufqwj`bo`loobspfbssqlb`ksbqwmfqpoldl!=?bgbvdkwfqbvwklq!#`vowvqboebnjojfp,jnbdfp,bppfnaozsltfqevowfb`kjmdejmjpkfggjpwqj`w`qjwj`bo`dj.ajm,svqslpfpqfrvjqfpfof`wjlmaf`lnjmdsqlujgfpb`bgfnj`f{fq`jpfb`wvbooznfgj`jmf`lmpwbmwb``jgfmwNbdbyjmfgl`vnfmwpwbqwjmdalwwln!=lapfqufg9#%rvlw8f{wfmgfgsqfujlvpPlewtbqf`vpwlnfqgf`jpjlmpwqfmdwkgfwbjofgpojdkwozsobmmjmdwf{wbqfb`vqqfm`zfufqzlmfpwqbjdkwwqbmpefqslpjwjufsqlgv`fgkfqjwbdfpkjssjmdbaplovwfqf`fjufgqfofubmwavwwlm!#ujlofm`fbmztkfqfafmfejwpobvm`kfgqf`fmwozboojbm`felooltfgnvowjsofavoofwjmjm`ovgfgl``vqqfgjmwfqmbo'+wkjp*-qfsvaoj`=?wq=?wg`lmdqfppqf`lqgfgvowjnbwfplovwjlm?vo#jg>!gjp`lufqKlnf?,b=tfapjwfpmfwtlqhpbowklvdkfmwjqfoznfnlqjbonfppbdfp`lmwjmvfb`wjuf!=plnftkbwuj`wlqjbTfpwfqm##wjwof>!Ol`bwjlm`lmwqb`wujpjwlqpGltmolbgtjwklvw#qjdkw!=	nfbpvqfptjgwk#>#ubqjbaofjmuloufgujqdjmjbmlqnboozkbssfmfgb``lvmwppwbmgjmdmbwjlmboQfdjpwfqsqfsbqfg`lmwqlopb``vqbwfajqwkgbzpwqbwfdzleej`jbodqbskj`p`qjnjmboslppjaoz`lmpvnfqSfqplmbopsfbhjmdubojgbwfb`kjfufg-isd!#,=nb`kjmfp?,k1=	##hfztlqgpeqjfmgozaqlwkfqp`lnajmfglqjdjmbo`lnslpfgf{sf`wfgbgfrvbwfsbhjpwbmeloolt!#ubovbaof?,obafo=qfobwjufaqjmdjmdjm`qfbpfdlufqmlqsovdjmp,Ojpw#le#Kfbgfq!=!#mbnf>!#+%rvlw8dqbgvbwf?,kfbg=	`lnnfq`fnbobzpjbgjqf`wlqnbjmwbjm8kfjdkw9p`kfgvof`kbmdjmdab`h#wl#`bwkloj`sbwwfqmp`lolq9# dqfbwfpwpvssojfpqfojbaof?,vo=	\n\n?pfof`w#`jwjyfmp`olwkjmdtbw`kjmd?oj#jg>!psf`jej``bqqzjmdpfmwfm`f?`fmwfq=`lmwqbpwwkjmhjmd`bw`k+f*plvwkfqmNj`kbfo#nfq`kbmw`bqlvpfosbggjmd9jmwfqjlq-psojw+!ojybwjlmL`wlafq#*xqfwvqmjnsqlufg..%dw8		`lufqbdf`kbjqnbm-smd!#,=pvaif`wpQj`kbqg#tkbwfufqsqlabaozqf`lufqzabpfabooivgdnfmw`lmmf`w--`pp!#,=#tfapjwfqfslqwfggfebvow!,=?,b=	fof`wqj`p`lwobmg`qfbwjlmrvbmwjwz-#JPAM#3gjg#mlw#jmpwbm`f.pfbq`k.!#obmd>!psfbhfqp@lnsvwfq`lmwbjmpbq`kjufpnjmjpwfqqfb`wjlmgjp`lvmwJwbojbml`qjwfqjbpwqlmdoz9#$kwws9$p`qjsw$`lufqjmdleefqjmdbssfbqfgAqjwjpk#jgfmwjezEb`fallhmvnfqlvpufkj`ofp`lm`fqmpBnfqj`bmkbmgojmdgju#jg>!Tjoojbn#sqlujgfq\\`lmwfmwb``vqb`zpf`wjlm#bmgfqplmeof{jaof@bwfdlqzobtqfm`f?p`qjsw=obzlvw>!bssqlufg#nb{jnvnkfbgfq!=?,wbaof=Pfquj`fpkbnjowlm`vqqfmw#`bmbgjbm`kbmmfop,wkfnfp,,bqwj`oflswjlmboslqwvdboubovf>!!jmwfqubotjqfofppfmwjwofgbdfm`jfpPfbq`k!#nfbpvqfgwklvpbmgpsfmgjmd%kfoojs8mft#Gbwf!#pjyf>!sbdfMbnfnjggof!#!#,=?,b=kjggfm!=pfrvfm`fsfqplmbolufqeoltlsjmjlmpjoojmljpojmhp!=	\n?wjwof=ufqpjlmppbwvqgbzwfqnjmbojwfnsqlsfmdjmffqpf`wjlmpgfpjdmfqsqlslpbo>!ebopf!Fpsb/]loqfofbpfppvanjw!#fq%rvlw8bggjwjlmpznswlnplqjfmwfgqfplvq`fqjdkw!=?sofbpvqfpwbwjlmpkjpwlqz-ofbujmd##alqgfq>`lmwfmwp`fmwfq!=-		Plnf#gjqf`wfgpvjwbaofavodbqjb-pklt+*8gfpjdmfgDfmfqbo#`lm`fswpF{bnsofptjoojbnpLqjdjmbo!=?psbm=pfbq`k!=lsfqbwlqqfrvfpwpb#%rvlw8booltjmdGl`vnfmwqfujpjlm-#		Wkf#zlvqpfoe@lmwb`w#nj`kjdbmFmdojpk#`lovnajbsqjlqjwzsqjmwjmdgqjmhjmdeb`jojwzqfwvqmfg@lmwfmw#leej`fqpQvppjbm#dfmfqbwf.;;6:.2!jmgj`bwfebnjojbq#rvbojwznbqdjm93#`lmwfmwujftslqw`lmwb`wp.wjwof!=slqwbaof-ofmdwk#fojdjaofjmuloufpbwobmwj`lmolbg>!gfebvow-pvssojfgsbznfmwpdolppbqz		Bewfq#dvjgbm`f?,wg=?wgfm`lgjmdnjggof!=`bnf#wl#gjpsobzpp`lwwjpkilmbwkbmnbilqjwztjgdfwp-`ojmj`bowkbjobmgwfb`kfqp?kfbg=	\nbeef`wfgpvsslqwpsljmwfq8wlPwqjmd?,pnboo=lhobklnbtjoo#af#jmufpwlq3!#bow>!klojgbzpQfplvq`foj`fmpfg#+tkj`k#-#Bewfq#`lmpjgfqujpjwjmdf{solqfqsqjnbqz#pfbq`k!#bmgqljg!rvj`hoz#nffwjmdpfpwjnbwf8qfwvqm#8`lolq9 #kfjdkw>bssqlubo/#%rvlw8#`kf`hfg-njm-ip!nbdmfwj`=?,b=?,kelqf`bpw-#Tkjof#wkvqpgbzgufqwjpf%fb`vwf8kbp@obppfubovbwflqgfqjmdf{jpwjmdsbwjfmwp#Lmojmf#`lolqbglLswjlmp!`bnsafoo?\"..#fmg?,psbm=??aq#,=	\\slsvspp`jfm`fp/%rvlw8#rvbojwz#Tjmgltp#bppjdmfgkfjdkw9#?a#`obppof%rvlw8#ubovf>!#@lnsbmzf{bnsofp?jeqbnf#afojfufpsqfpfmwpnbqpkboosbqw#le#sqlsfqoz*-		Wkf#wb{lmlnznv`k#le#?,psbm=	!#gbwb.pqwvdv/Fpp`qlooWl#sqlif`w?kfbg=	bwwlqmfzfnskbpjppslmplqpebm`zal{tlqog$p#tjogojef`kf`hfg>pfppjlmpsqldqbnns{8elmw.#Sqlif`wilvqmbopafojfufgub`bwjlmwklnsplmojdkwjmdbmg#wkf#psf`jbo#alqgfq>3`kf`hjmd?,walgz=?avwwlm#@lnsofwf`ofbqej{	?kfbg=	bqwj`of#?pf`wjlmejmgjmdpqlof#jm#slsvobq##L`wlafqtfapjwf#f{slpvqfvpfg#wl##`kbmdfplsfqbwfg`oj`hjmdfmwfqjmd`lnnbmgpjmelqnfg#mvnafqp##?,gju=`qfbwjmdlmPvanjwnbqzobmg`loofdfpbmbozwj`ojpwjmdp`lmwb`w-olddfgJmbgujplqzpjaojmdp`lmwfmw!p%rvlw8*p-#Wkjp#sb`hbdfp`kf`hal{pvddfpwpsqfdmbmwwlnlqqltpsb`jmd>j`lm-smdibsbmfpf`lgfabpfavwwlm!=dbnaojmdpv`k#bp#/#tkjof#?,psbm=#njpplvqjpslqwjmdwls92s{#-?,psbm=wfmpjlmptjgwk>!1obyzolbgmlufnafqvpfg#jm#kfjdkw>!`qjsw!=	%maps8?,?wq=?wg#kfjdkw91,sqlgv`w`lvmwqz#jm`ovgf#ellwfq!#%ow8\"..#wjwof!=?,irvfqz-?,elqn=	+\vBl\bQ*+\vUmGx*kqubwphjjwbojbmlqln/Nm(ow/Pqh/Kf4K4]4C5dwbnaj/Emmlwj`jbpnfmpbifpsfqplmbpgfqf`klpmb`jlmbopfquj`jl`lmwb`wlvpvbqjlpsqldqbnbdlajfqmlfnsqfpbpbmvm`jlpubofm`jb`lolnajbgfpsv/Epgfslqwfpsqlzf`wlsqlgv`wls/Vaoj`lmlplwqlpkjpwlqjbsqfpfmwfnjoolmfpnfgjbmwfsqfdvmwbbmwfqjlqqf`vqplpsqlaofnbpbmwjbdlmvfpwqlplsjmj/_mjnsqjnjqnjfmwqbpbn/Eqj`bufmgfglqpl`jfgbgqfpsf`wlqfbojybqqfdjpwqlsbobaqbpjmwfq/Epfmwlm`fpfpsf`jbonjfnaqlpqfbojgbg`/_qglabybqbdlybs/Mdjmbppl`jbofpaolrvfbqdfpwj/_mborvjofqpjpwfnbp`jfm`jbp`lnsofwlufqpj/_m`lnsofwbfpwvgjlps/Vaoj`blaifwjulboj`bmwfavp`bglq`bmwjgbgfmwqbgbpb``jlmfpbq`kjulppvsfqjlqnbzlq/Abbofnbmjbevm`j/_m/Vowjnlpkb`jfmglbrvfoolpfgj`j/_mefqmbmglbnajfmwfeb`fallhmvfpwqbp`ojfmwfpsql`fplpabpwbmwfsqfpfmwbqfslqwbq`lmdqfplsvaoj`bq`lnfq`jl`lmwqbwli/_ufmfpgjpwqjwlw/E`mj`b`lmivmwlfmfqd/Abwqbabibqbpwvqjbpqf`jfmwfvwjojybqalofw/Ampboubglq`lqqf`wbwqbabilpsqjnfqlpmfdl`jlpojafqwbggfwboofpsbmwboobsq/_{jnlbonfq/Abbmjnbofprvj/Emfp`lqby/_mpf``j/_mavp`bmglls`jlmfpf{wfqjlq`lm`fswlwlgbu/Abdbofq/Abfp`qjajqnfgj`jmboj`fm`jb`lmpvowbbpsf`wlp`q/Awj`bg/_obqfpivpwj`jbgfafq/Mmsfq/Alglmf`fpjwbnbmwfmfqsfrvf/]lqf`jajgbwqjavmbowfmfqjef`bm`j/_m`bmbqjbpgfp`bqdbgjufqplpnboolq`bqfrvjfqfw/E`mj`lgfafq/Abujujfmgbejmbmybpbgfobmwfevm`jlmb`lmpfilpgje/A`jo`jvgbgfpbmwjdvbpbubmybgbw/Eqnjmlvmjgbgfpp/Mm`kfy`bnsb/]bplewlmj`qfujpwbp`lmwjfmfpf`wlqfpnlnfmwlpeb`vowbg`q/Egjwlgjufqpbppvsvfpwleb`wlqfppfdvmglpsfrvf/]b<_<R<X<\\<Y=m<W<T<Y=m=n=`<]=g<W<R<]=g=n=`=a=n<R<P<y=m<W<T=n<R<_<R<P<Y<Q=c<^=m<Y=i=a=n<R<U<X<\\<Z<Y<]=g<W<T<_<R<X=o<X<Y<Q=`=a=n<R=n<]=g<W<\\=m<Y<]=c<R<X<T<Q=m<Y<]<Y<Q<\\<X<R=m<\\<U=n=h<R=n<R<Q<Y<_<R=m<^<R<T=m<^<R<U<T<_=l=g=n<R<Z<Y<^=m<Y<P=m<^<R=b<W<T=d=`=a=n<T=i<S<R<V<\\<X<Q<Y<U<X<R<P<\\<P<T=l<\\<W<T<]<R=n<Y<P=o=i<R=n=c<X<^=o=i=m<Y=n<T<W=b<X<T<X<Y<W<R<P<T=l<Y=n<Y<]=c=m<^<R<Y<^<T<X<Y=k<Y<_<R=a=n<T<P=m=k<Y=n=n<Y<P=g=j<Y<Q=g=m=n<\\<W<^<Y<X=`=n<Y<P<Y<^<R<X=g=n<Y<]<Y<^=g=d<Y<Q<\\<P<T=n<T<S<\\=n<R<P=o<S=l<\\<^<W<T=j<\\<R<X<Q<\\<_<R<X=g<[<Q<\\=b<P<R<_=o<X=l=o<_<^=m<Y<U<T<X<Y=n<V<T<Q<R<R<X<Q<R<X<Y<W<\\<X<Y<W<Y=m=l<R<V<T=b<Q=c<^<Y=m=`<y=m=n=`=l<\\<[<\\<Q<\\=d<T4K5h5h5k4K5h4F5f4@5i5f4U4B4K4Y4E4K5h4\\5f4U5h5f5k4@4C5f4C4K5h4N5j4K5h4]4C4F4A5o5i4Y5m4A4E5o4K5j4F4K5h5h5f5f5o5d5j4X4D5o4E5m5f5k4K4D5j4K4F4A5d4K4M4O5o4G4]4B5h4K5h4K5h4A4D4C5h5f5h4C4]5d4_4K4Z4V4[4F5o5d5j5k5j4K5o4_4K4A4E5j4K4C5f4K5h4[4D4U5h5f5o4X5o4]4K5f5i5o5j5i5j5k4K4X4]5o4E4]4J5f4_5j4X5f4[5i4K4\\4K4K5h5m5j4X4D4K4D4F4U4D4]4]4A5i4E5o4K5m4E5f5n5d5h5i4]5o4^5o5h5i4E4O4A5i4C5n5h4D5f5f4U5j5f4Y5d4]4E4[4]5f5n4X4K4]5o4@5d4K5h4O4B4]5e5i4U5j4K4K4D4A4G4U4]5d4Z4D4X5o5h5i4_4@5h4D5j4K5j4B4K5h4C5o4F4K4D5o5h5f4E4D4C5d5j4O5f4Z4K5f5d4@4C5m4]5f5n5o4F4D4F4O5m4Z5h5i4[4D4B4K5o4G4]4D4K4]5o4K5m4Z5h4K4A5h5e5j5m4_5k4O5f4K5i4]4C5d4C4O5j5k4K4C5f5j4K4K5h4K5j5i4U4]4Z4F4U5h5i4C4K4B5h5i5i5o5j\x07\x07\x07\x07\0\x07\x07\0\v\n	\b\r\f\f\r\b	\n\v\x1B\x1B\0\v\v\v\v\0\x07qfplvq`fp`lvmwqjfprvfpwjlmpfrvjsnfmw`lnnvmjwzbubjobaofkjdkojdkwGWG,{kwnonbqhfwjmdhmltofgdfplnfwkjmd`lmwbjmfqgjqf`wjlmpvap`qjafbgufqwjpf`kbqb`wfq!#ubovf>!?,pfof`w=Bvpwqbojb!#`obpp>!pjwvbwjlmbvwklqjwzelooltjmdsqjnbqjozlsfqbwjlm`kboofmdfgfufolsfgbmlmznlvpevm`wjlm#evm`wjlmp`lnsbmjfppwqv`wvqfbdqffnfmw!#wjwof>!slwfmwjbofgv`bwjlmbqdvnfmwppf`lmgbqz`lszqjdkwobmdvbdfpf{`ovpjuf`lmgjwjlm?,elqn=	pwbwfnfmwbwwfmwjlmAjldqbskz~#fopf#x	plovwjlmptkfm#wkf#Bmbozwj`pwfnsobwfpgbmdfqlvppbwfoojwfgl`vnfmwpsvaojpkfqjnslqwbmwsqlwlwzsfjmeovfm`f%qbrvl8?,feef`wjufdfmfqboozwqbmpelqnafbvwjevowqbmpslqwlqdbmjyfgsvaojpkfgsqlnjmfmwvmwjo#wkfwkvnambjoMbwjlmbo#-el`vp+*8lufq#wkf#njdqbwjlmbmmlvm`fgellwfq!=	f{`fswjlmofpp#wkbmf{sfmpjufelqnbwjlmeqbnftlqhwfqqjwlqzmgj`bwjlm`vqqfmwoz`obppMbnf`qjwj`jpnwqbgjwjlmfopftkfqfBof{bmgfqbssljmwfgnbwfqjbopaqlbg`bpwnfmwjlmfgbeejojbwf?,lswjlm=wqfbwnfmwgjeefqfmw,gfebvow-Sqfpjgfmwlm`oj`h>!ajldqbskzlwkfqtjpfsfqnbmfmwEqbm/KbjpKlooztllgf{sbmpjlmpwbmgbqgp?,pwzof=	qfgv`wjlmGf`fnafq#sqfefqqfg@bnaqjgdflsslmfmwpAvpjmfpp#`lmevpjlm=	?wjwof=sqfpfmwfgf{sobjmfgglfp#mlw#tlqogtjgfjmwfqeb`fslpjwjlmpmftpsbsfq?,wbaof=	nlvmwbjmpojhf#wkf#fppfmwjboejmbm`jbopfof`wjlmb`wjlm>!,babmglmfgFgv`bwjlmsbqpfJmw+pwbajojwzvmbaof#wl?,wjwof=	qfobwjlmpMlwf#wkbwfeej`jfmwsfqelqnfgwtl#zfbqpPjm`f#wkfwkfqfelqftqbssfq!=bowfqmbwfjm`qfbpfgAbwwof#lesfq`fjufgwqzjmd#wlmf`fppbqzslqwqbzfgfof`wjlmpFojybafwk?,jeqbnf=gjp`lufqzjmpvqbm`fp-ofmdwk8ofdfmgbqzDfldqbskz`bmgjgbwf`lqslqbwfplnfwjnfppfquj`fp-jmkfqjwfg?,pwqlmd=@lnnvmjwzqfojdjlvpol`bwjlmp@lnnjwwffavjogjmdpwkf#tlqogml#olmdfqafdjmmjmdqfefqfm`f`bmmlw#afeqfrvfm`zwzsj`boozjmwl#wkf#qfobwjuf8qf`lqgjmdsqfpjgfmwjmjwjboozwf`kmjrvfwkf#lwkfqjw#`bm#aff{jpwfm`fvmgfqojmfwkjp#wjnfwfofsklmfjwfnp`lsfsqb`wj`fpbgubmwbdf*8qfwvqm#Elq#lwkfqsqlujgjmdgfnl`qb`zalwk#wkf#f{wfmpjufpveefqjmdpvsslqwfg`lnsvwfqp#evm`wjlmsqb`wj`bopbjg#wkbwjw#nbz#afFmdojpk?,eqln#wkf#p`kfgvofggltmolbgp?,obafo=	pvpsf`wfgnbqdjm9#3psjqjwvbo?,kfbg=		nj`qlplewdqbgvboozgjp`vppfgkf#af`bnff{f`vwjufirvfqz-ipklvpfklog`lmejqnfgsvq`kbpfgojwfqboozgfpwqlzfgvs#wl#wkfubqjbwjlmqfnbjmjmdjw#jp#mlw`fmwvqjfpIbsbmfpf#bnlmd#wkf`lnsofwfgbodlqjwknjmwfqfpwpqfafoojlmvmgfejmfgfm`lvqbdfqfpjybaofjmuloujmdpfmpjwjufvmjufqpbosqlujpjlm+bowklvdkefbwvqjmd`lmgv`wfg*/#tkj`k#`lmwjmvfg.kfbgfq!=Efaqvbqz#mvnfqlvp#lufqeolt9`lnslmfmweqbdnfmwpf{`foofmw`lopsbm>!wf`kmj`bomfbq#wkf#Bgubm`fg#plvq`f#lef{sqfppfgKlmd#Hlmd#Eb`fallhnvowjsof#nf`kbmjpnfofubwjlmleefmpjuf?,elqn=	\npslmplqfggl`vnfmw-lq#%rvlw8wkfqf#bqfwklpf#tklnlufnfmwpsql`fppfpgjeej`vowpvanjwwfgqf`lnnfmg`lmujm`fgsqlnlwjmd!#tjgwk>!-qfsob`f+`obppj`bo`lbojwjlmkjp#ejqpwgf`jpjlmpbppjpwbmwjmgj`bwfgfulovwjlm.tqbssfq!fmlvdk#wlbolmd#wkfgfojufqfg..=	?\"..Bnfqj`bm#sqlwf`wfgMlufnafq#?,pwzof=?evqmjwvqfJmwfqmfw##lmaovq>!pvpsfmgfgqf`jsjfmwabpfg#lm#Nlqflufq/balojpkfg`loof`wfgtfqf#nbgffnlwjlmbofnfqdfm`zmbqqbwjufbgul`bwfps{8alqgfq`lnnjwwfggjq>!owq!fnsolzffpqfpfbq`k-#pfof`wfgpv``fpplq`vpwlnfqpgjpsobzfgPfswfnafqbgg@obpp+Eb`fallh#pvddfpwfgbmg#obwfqlsfqbwjmdfobalqbwfPlnfwjnfpJmpwjwvwf`fqwbjmozjmpwboofgelooltfqpIfqvpbofnwkfz#kbuf`lnsvwjmddfmfqbwfgsqlujm`fpdvbqbmwffbqajwqbqzqf`ldmjyftbmwfg#wls{8tjgwk9wkflqz#leafkbujlvqTkjof#wkffpwjnbwfgafdbm#wl#jw#af`bnfnbdmjwvgfnvpw#kbufnlqf#wkbmGjqf`wlqzf{wfmpjlmpf`qfwbqzmbwvqboozl``vqqjmdubqjbaofpdjufm#wkfsobwelqn-?,obafo=?ebjofg#wl`lnslvmgphjmgp#le#pl`jfwjfpbolmdpjgf#..%dw8		plvwktfpwwkf#qjdkwqbgjbwjlmnbz#kbuf#vmfp`bsf+pslhfm#jm!#kqfe>!,sqldqbnnflmoz#wkf#`lnf#eqlngjqf`wlqzavqjfg#jmb#pjnjobqwkfz#tfqf?,elmw=?,Mlqtfdjbmpsf`jejfgsqlgv`jmdsbppfmdfq+mft#Gbwfwfnslqbqzej`wjlmboBewfq#wkffrvbwjlmpgltmolbg-qfdvobqozgfufolsfqbaluf#wkfojmhfg#wlskfmlnfmbsfqjlg#lewllowjs!=pvapwbm`fbvwlnbwj`bpsf`w#leBnlmd#wkf`lmmf`wfgfpwjnbwfpBjq#Elq`fpzpwfn#lelaif`wjufjnnfgjbwfnbhjmd#jwsbjmwjmdp`lmrvfqfgbqf#pwjoosql`fgvqfdqltwk#lekfbgfg#azFvqlsfbm#gjujpjlmpnlof`vofpeqbm`kjpfjmwfmwjlmbwwqb`wfg`kjogkllgbopl#vpfggfgj`bwfgpjmdbslqfgfdqff#leebwkfq#le`lmeoj`wp?,b=?,s=	`bnf#eqlntfqf#vpfgmlwf#wkbwqf`fjujmdF{f`vwjuffufm#nlqfb``fpp#wl`lnnbmgfqSlojwj`bonvpj`jbmpgfoj`jlvpsqjplmfqpbgufmw#leVWE.;!#,=?\"X@GBWBX!=@lmwb`wPlvwkfqm#ad`lolq>!pfqjfp#le-#Jw#tbp#jm#Fvqlsfsfqnjwwfgubojgbwf-bssfbqjmdleej`jboppfqjlvpoz.obmdvbdfjmjwjbwfgf{wfmgjmdolmd.wfqnjmeobwjlmpv`k#wkbwdfw@llhjfnbqhfg#az?,avwwlm=jnsofnfmwavw#jw#jpjm`qfbpfpgltm#wkf#qfrvjqjmdgfsfmgfmw..=	?\"..#jmwfqujftTjwk#wkf#`lsjfp#le`lmpfmpvptbp#avjowUfmfyvfob+elqnfqozwkf#pwbwfsfqplmmfopwqbwfdj`ebulvq#lejmufmwjlmTjhjsfgjb`lmwjmfmwujqwvbooztkj`k#tbpsqjm`jsof@lnsofwf#jgfmwj`bopklt#wkbwsqjnjwjufbtbz#eqlnnlof`vobqsqf`jpfozgjpploufgVmgfq#wkfufqpjlm>!=%maps8?,Jw#jp#wkf#Wkjp#jp#tjoo#kbuflqdbmjpnpplnf#wjnfEqjfgqj`ktbp#ejqpwwkf#lmoz#eb`w#wkbwelqn#jg>!sqf`fgjmdWf`kmj`boskzpj`jpwl``vqp#jmmbujdbwlqpf`wjlm!=psbm#jg>!plvdkw#wlafolt#wkfpvqujujmd~?,pwzof=kjp#gfbwkbp#jm#wkf`bvpfg#azsbqwjboozf{jpwjmd#vpjmd#wkftbp#djufmb#ojpw#leofufop#lemlwjlm#leLeej`jbo#gjpnjppfgp`jfmwjpwqfpfnaofpgvsoj`bwff{solpjufqf`lufqfgboo#lwkfqdboofqjfpxsbggjmd9sflsof#leqfdjlm#lebggqfppfpbppl`jbwfjnd#bow>!jm#nlgfqmpklvog#afnfwklg#leqfslqwjmdwjnfpwbnsmffgfg#wlwkf#Dqfbwqfdbqgjmdpffnfg#wlujftfg#bpjnsb`w#lmjgfb#wkbwwkf#Tlqogkfjdkw#lef{sbmgjmdWkfpf#bqf`vqqfmw!=`bqfevooznbjmwbjmp`kbqdf#le@obppj`bobggqfppfgsqfgj`wfgltmfqpkjs?gju#jg>!qjdkw!=	qfpjgfm`fofbuf#wkf`lmwfmw!=bqf#lewfm##~*+*8	sqlabaoz#Sqlefpplq.avwwlm!#qfpslmgfgpbzp#wkbwkbg#wl#afsob`fg#jmKvmdbqjbmpwbwvp#lepfqufp#bpVmjufqpbof{f`vwjlmbddqfdbwfelq#tkj`kjmef`wjlmbdqffg#wlkltfufq/#slsvobq!=sob`fg#lm`lmpwqv`wfof`wlqbopznalo#lejm`ovgjmdqfwvqm#wlbq`kjwf`w@kqjpwjbmsqfujlvp#ojujmd#jmfbpjfq#wlsqlefpplq	%ow8\"..#feef`w#lebmbozwj`ptbp#wbhfmtkfqf#wkfwllh#lufqafojfe#jmBeqjhbbmpbp#ebq#bpsqfufmwfgtlqh#tjwkb#psf`jbo?ejfogpfw@kqjpwnbpQfwqjfufg		Jm#wkf#ab`h#jmwlmlqwkfbpwnbdbyjmfp=?pwqlmd=`lnnjwwffdlufqmjmddqlvsp#lepwlqfg#jmfpwbaojpkb#dfmfqbojwp#ejqpwwkfjq#ltmslsvobwfgbm#laif`w@bqjaafbmboolt#wkfgjpwqj`wptjp`lmpjmol`bwjlm-8#tjgwk9#jmkbajwfgPl`jbojpwIbmvbqz#2?,ellwfq=pjnjobqoz`klj`f#lewkf#pbnf#psf`jej`#avpjmfpp#Wkf#ejqpw-ofmdwk8#gfpjqf#wlgfbo#tjwkpjm`f#wkfvpfqBdfmw`lm`fjufgjmgf{-sksbp#%rvlw8fmdbdf#jmqf`fmwoz/eft#zfbqptfqf#bopl	?kfbg=	?fgjwfg#azbqf#hmltm`jwjfp#jmb``fpphfz`lmgfnmfgbopl#kbufpfquj`fp/ebnjoz#leP`kllo#le`lmufqwfgmbwvqf#le#obmdvbdfnjmjpwfqp?,laif`w=wkfqf#jp#b#slsvobqpfrvfm`fpbgul`bwfgWkfz#tfqfbmz#lwkfqol`bwjlm>fmwfq#wkfnv`k#nlqfqfeof`wfgtbp#mbnfglqjdjmbo#b#wzsj`botkfm#wkfzfmdjmffqp`lvog#mlwqfpjgfmwptfgmfpgbzwkf#wkjqg#sqlgv`wpIbmvbqz#1tkbw#wkfzb#`fqwbjmqfb`wjlmpsql`fpplqbewfq#kjpwkf#obpw#`lmwbjmfg!=?,gju=	?,b=?,wg=gfsfmg#lmpfbq`k!=	sjf`fp#le`lnsfwjmdQfefqfm`fwfmmfppfftkj`k#kbp#ufqpjlm>?,psbm=#??,kfbgfq=djufp#wkfkjpwlqjbmubovf>!!=sbggjmd93ujft#wkbwwldfwkfq/wkf#nlpw#tbp#elvmgpvapfw#lebwwb`h#lm`kjogqfm/sljmwp#lesfqplmbo#slpjwjlm9boofdfgoz@ofufobmgtbp#obwfqbmg#bewfqbqf#djufmtbp#pwjoop`qloojmdgfpjdm#lenbhfp#wkfnv`k#ofppBnfqj`bmp-		Bewfq#/#avw#wkfNvpfvn#leolvjpjbmb+eqln#wkfnjmmfplwbsbqwj`ofpb#sql`fppGlnjmj`bmulovnf#leqfwvqmjmdgfefmpjuf33s{qjdknbgf#eqlnnlvpflufq!#pwzof>!pwbwfp#le+tkj`k#jp`lmwjmvfpEqbm`jp`lavjogjmd#tjwklvw#btjwk#plnftkl#tlvogb#elqn#leb#sbqw#leafelqf#jwhmltm#bp##Pfquj`fpol`bwjlm#bmg#lewfmnfbpvqjmdbmg#jw#jpsbsfqab`hubovfp#le	?wjwof=>#tjmglt-gfwfqnjmffq%rvlw8#sobzfg#azbmg#fbqoz?,`fmwfq=eqln#wkjpwkf#wkqffsltfq#bmgle#%rvlw8jmmfqKWNO?b#kqfe>!z9jmojmf8@kvq`k#lewkf#fufmwufqz#kjdkleej`jbo#.kfjdkw9#`lmwfmw>!,`dj.ajm,wl#`qfbwfbeqjhbbmpfpsfqbmwleqbm/Kbjpobwujf)Mvojfwvuj)_(`f)Mwjmb(af)Mwjmb\fUh\fT{\fTN\n{I\np@Fr\vBl\bQ	A{\vUmGx	A{ypYA\0zX\bTV\bWl\bUdBM\vB{\npV\v@xB\\\np@DbGz	al\npa	fM	uD\bV~mx\vQ}\ndS	p\\\bVK\bS]\bU|oD	kV\ved\vHR\nb~M`\nJpoD|Q\nLPSw\bTl\nAI\nxC\bWt	BqF`Cm\vLm	Kx	}t\bPv\ny\\\naB	V\nZdXUli	fr	i@	BHBDBV	`V\n[]	p_	Tn\n~A\nxR	uD	`{\bV@	Tn	HK	AJ\vxsZf\nqIZf\vBM\v|j	}t\bSM\nmC\vQ}pfquj`jlpbqw/A`volbqdfmwjmbabq`folmb`vborvjfqsvaoj`bglsqlgv`wlpslo/Awj`bqfpsvfpwbtjhjsfgjbpjdvjfmwfa/Vprvfgb`lnvmjgbgpfdvqjgbgsqjm`jsbosqfdvmwbp`lmwfmjglqfpslmgfqufmfyvfobsqlaofnbpgj`jfnaqfqfob`j/_mmlujfnaqfpjnjobqfpsqlzf`wlpsqldqbnbpjmpwjwvwlb`wjujgbgfm`vfmwqbf`lmln/Abjn/Mdfmfp`lmwb`wbqgfp`bqdbqmf`fpbqjlbwfm`j/_mwfo/Eelml`lnjpj/_m`bm`jlmfp`bsb`jgbgfm`lmwqbqbm/Mojpjpebulqjwlpw/Eqnjmlpsqlujm`jbfwjrvfwbpfofnfmwlpevm`jlmfpqfpvowbgl`bq/M`wfqsqlsjfgbgsqjm`jsjlmf`fpjgbgnvmj`jsbo`qfb`j/_mgfp`bqdbpsqfpfm`jb`lnfq`jbolsjmjlmfpfifq`j`jlfgjwlqjbopbobnbm`bdlmy/Mofygl`vnfmwlsfo/A`vobqf`jfmwfpdfmfqbofpwbqqbdlmbsq/M`wj`bmlufgbgfpsqlsvfpwbsb`jfmwfpw/E`mj`bplaifwjulp`lmwb`wlp\fHB\fIk\fHn\fH^\fHS\fHc\fHU\fId\fHn\fH{\fHC\fHR\fHT\fHR\fHI\fHc\fHY\fHn\fH\\\fHU\fIk\fHy\fIg\fHd\fHy\fIm\fHw\fH\\\fHU\fHR\fH@\fHR\fHJ\fHy\fHU\fHR\fHT\fHA\fIl\fHU\fIm\fHc\fH\\\fHU\fIl\fHB\fId\fHn\fHJ\fHS\fHD\fH@\fHR\fHHgjsolgl`p\fHT\fHB\fHC\fH\\\fIn\fHF\fHD\fHR\fHB\fHF\fHH\fHR\fHG\fHS\fH\\\fHx\fHT\fHH\fHH\fH\\\fHU\fH^\fIg\fH{\fHU\fIm\fHj\fH@\fHR\fH\\\fHJ\fIk\fHZ\fHU\fIm\fHd\fHz\fIk\fH^\fHC\fHJ\fHS\fHy\fHR\fHB\fHY\fIk\fH@\fHH\fIl\fHD\fH@\fIl\fHv\fHB\fI`\fHH\fHT\fHR\fH^\fH^\fIk\fHz\fHp\fIe\fH@\fHB\fHJ\fHJ\fHH\fHI\fHR\fHD\fHU\fIl\fHZ\fHU\fH\\\fHi\fH^\fH{\fHy\fHA\fIl\fHD\fH{\fH\\\fHF\fHR\fHT\fH\\\fHR\fHH\fHy\fHS\fHc\fHe\fHT\fIk\fH{\fHC\fIl\fHU\fIn\fHm\fHj\fH{\fIk\fHs\fIl\fHB\fHz\fIg\fHp\fHy\fHR\fH\\\fHi\fHA\fIl\fH{\fHC\fIk\fHH\fIm\fHB\fHY\fIg\fHs\fHJ\fIk\fHn\fHi\fH{\fH\\\fH|\fHT\fIk\fHB\fIk\fH^\fH^\fH{\fHR\fHU\fHR\fH^\fHf\fHF\fH\\\fHv\fHR\fH\\\fH|\fHT\fHR\fHJ\fIk\fH\\\fHp\fHS\fHT\fHJ\fHS\fH^\fH@\fHn\fHJ\fH@\fHD\fHR\fHU\fIn\fHn\fH^\fHR\fHz\fHp\fIl\fHH\fH@\fHs\fHD\fHB\fHS\fH^\fHk\fHT\fIk\fHj\fHD\fIk\fHD\fHC\fHR\fHy\fIm\fH^\fH^\fIe\fH{\fHA\fHR\fH{\fH\\\fIk\fH^\fHp\fH{\fHU\fH\\\fHR\fHB\fH^\fH{\fIk\fHF\fIk\fHp\fHU\fHR\fHI\fHk\fHT\fIl\fHT\fHU\fIl\fHy\fH^\fHR\fHL\fIl\fHy\fHU\fHR\fHm\fHJ\fIn\fH\\\fHH\fHU\fHH\fHT\fHR\fHH\fHC\fHR\fHJ\fHj\fHC\fHR\fHF\fHR\fHy\fHy\fI`\fHD\fHZ\fHR\fHB\fHJ\fIk\fHz\fHC\fHU\fIl\fH\\\fHR\fHC\fHz\fIm\fHJ\fH^\fH{\fIl`bwfdlqjfpf{sfqjfm`f?,wjwof=	@lszqjdkw#ibubp`qjsw`lmgjwjlmpfufqzwkjmd?s#`obpp>!wf`kmloldzab`hdqlvmg?b#`obpp>!nbmbdfnfmw%`lsz8#132ibubP`qjsw`kbqb`wfqpaqfbg`qvnawkfnpfoufpklqjylmwbodlufqmnfmw@bojelqmjbb`wjujwjfpgjp`lufqfgMbujdbwjlmwqbmpjwjlm`lmmf`wjlmmbujdbwjlmbssfbqbm`f?,wjwof=?n`kf`hal{!#wf`kmjrvfpsqlwf`wjlmbssbqfmwozbp#tfoo#bpvmw$/#$VB.qfplovwjlmlsfqbwjlmpwfofujpjlmwqbmpobwfgTbpkjmdwlmmbujdbwlq-#>#tjmglt-jnsqfppjlm%ow8aq%dw8ojwfqbwvqfslsvobwjlmad`lolq>! fpsf`jbooz#`lmwfmw>!sqlgv`wjlmmftpofwwfqsqlsfqwjfpgfejmjwjlmofbgfqpkjsWf`kmloldzSbqojbnfmw`lnsbqjplmvo#`obpp>!-jmgf{Le+!`lm`ovpjlmgjp`vppjlm`lnslmfmwpajloldj`boQfulovwjlm\\`lmwbjmfqvmgfqpwllgmlp`qjsw=?sfqnjppjlmfb`k#lwkfqbwnlpskfqf#lmel`vp>!?elqn#jg>!sql`fppjmdwkjp-ubovfdfmfqbwjlm@lmefqfm`fpvapfrvfmwtfoo.hmltmubqjbwjlmpqfsvwbwjlmskfmlnfmlmgjp`jsojmfoldl-smd!#+gl`vnfmw/alvmgbqjfpf{sqfppjlmpfwwofnfmwAb`hdqlvmglvw#le#wkffmwfqsqjpf+!kwwsp9!#vmfp`bsf+!sbpptlqg!#gfnl`qbwj`?b#kqfe>!,tqbssfq!=	nfnafqpkjsojmdvjpwj`s{8sbggjmdskjolplskzbppjpwbm`fvmjufqpjwzeb`jojwjfpqf`ldmjyfgsqfefqfm`fje#+wzsflenbjmwbjmfgul`bavobqzkzslwkfpjp-pvanjw+*8%bns8maps8bmmlwbwjlmafkjmg#wkfElvmgbwjlmsvaojpkfq!bppvnswjlmjmwqlgv`fg`lqqvswjlmp`jfmwjpwpf{soj`jwozjmpwfbg#legjnfmpjlmp#lm@oj`h>!`lmpjgfqfggfsbqwnfmwl``vsbwjlmpllm#bewfqjmufpwnfmwsqlmlvm`fgjgfmwjejfgf{sfqjnfmwNbmbdfnfmwdfldqbskj`!#kfjdkw>!ojmh#qfo>!-qfsob`f+,gfsqfppjlm`lmefqfm`fsvmjpknfmwfojnjmbwfgqfpjpwbm`fbgbswbwjlmlsslpjwjlmtfoo#hmltmpvssofnfmwgfwfqnjmfgk2#`obpp>!3s{8nbqdjmnf`kbmj`bopwbwjpwj`p`fofaqbwfgDlufqmnfmw		Gvqjmd#wgfufolsfqpbqwjej`jbofrvjubofmwlqjdjmbwfg@lnnjppjlmbwwb`knfmw?psbm#jg>!wkfqf#tfqfMfgfqobmgpafzlmg#wkfqfdjpwfqfgilvqmbojpweqfrvfmwozboo#le#wkfobmd>!fm!#?,pwzof=	baplovwf8#pvsslqwjmdf{wqfnfoz#nbjmpwqfbn?,pwqlmd=#slsvobqjwzfnsolznfmw?,wbaof=	#`lopsbm>!?,elqn=	##`lmufqpjlmbalvw#wkf#?,s=?,gju=jmwfdqbwfg!#obmd>!fmSlqwvdvfpfpvapwjwvwfjmgjujgvbojnslppjaofnvowjnfgjbbonlpw#boos{#plojg# bsbqw#eqlnpvaif`w#wljm#Fmdojpk`qjwj`jyfgf{`fsw#elqdvjgfojmfplqjdjmboozqfnbqhbaofwkf#pf`lmgk1#`obpp>!?b#wjwof>!+jm`ovgjmdsbqbnfwfqpsqlkjajwfg>#!kwws9,,gj`wjlmbqzsfq`fswjlmqfulovwjlmelvmgbwjlms{8kfjdkw9pv``fppevopvsslqwfqpnjoofmmjvnkjp#ebwkfqwkf#%rvlw8ml.qfsfbw8`lnnfq`jbojmgvpwqjbofm`lvqbdfgbnlvmw#le#vmleej`jbofeej`jfm`zQfefqfm`fp`llqgjmbwfgjp`objnfqf{sfgjwjlmgfufolsjmd`bo`vobwfgpjnsojejfgofdjwjnbwfpvapwqjmd+3!#`obpp>!`lnsofwfozjoovpwqbwfejuf#zfbqpjmpwqvnfmwSvaojpkjmd2!#`obpp>!spz`kloldz`lmejgfm`fmvnafq#le#bapfm`f#leel`vpfg#lmiljmfg#wkfpwqv`wvqfpsqfujlvpoz=?,jeqbnf=lm`f#bdbjmavw#qbwkfqjnnjdqbmwple#`lvqpf/b#dqlvs#leOjwfqbwvqfVmojhf#wkf?,b=%maps8	evm`wjlm#jw#tbp#wkf@lmufmwjlmbvwlnlajofSqlwfpwbmwbddqfppjufbewfq#wkf#Pjnjobqoz/!#,=?,gju=`loof`wjlm	evm`wjlmujpjajojwzwkf#vpf#leulovmwffqpbwwqb`wjlmvmgfq#wkf#wkqfbwfmfg)?\"X@GBWBXjnslqwbm`fjm#dfmfqbowkf#obwwfq?,elqn=	?,-jmgf{Le+$j#>#38#j#?gjeefqfm`fgfulwfg#wlwqbgjwjlmppfbq`k#elqvowjnbwfozwlvqmbnfmwbwwqjavwfppl.`boofg#~	?,pwzof=fubovbwjlmfnskbpjyfgb``fppjaof?,pf`wjlm=pv``fppjlmbolmd#tjwkNfbmtkjof/jmgvpwqjfp?,b=?aq#,=kbp#af`lnfbpsf`wp#leWfofujpjlmpveej`jfmwabphfwabooalwk#pjgfp`lmwjmvjmdbm#bqwj`of?jnd#bow>!bgufmwvqfpkjp#nlwkfqnbm`kfpwfqsqjm`jsofpsbqwj`vobq`lnnfmwbqzfeef`wp#legf`jgfg#wl!=?pwqlmd=svaojpkfqpIlvqmbo#legjeej`vowzeb`jojwbwfb``fswbaofpwzof-`pp!\nevm`wjlm#jmmlubwjlm=@lszqjdkwpjwvbwjlmptlvog#kbufavpjmfppfpGj`wjlmbqzpwbwfnfmwplewfm#vpfgsfqpjpwfmwjm#Ibmvbqz`lnsqjpjmd?,wjwof=	\ngjsolnbwj``lmwbjmjmdsfqelqnjmdf{wfmpjlmpnbz#mlw#af`lm`fsw#le#lm`oj`h>!Jw#jp#boplejmbm`jbo#nbhjmd#wkfOv{fnalvqdbggjwjlmbobqf#`boofgfmdbdfg#jm!p`qjsw!*8avw#jw#tbpfof`wqlmj`lmpvanjw>!	?\"..#Fmg#fof`wqj`boleej`jboozpvddfpwjlmwls#le#wkfvmojhf#wkfBvpwqbojbmLqjdjmboozqfefqfm`fp	?,kfbg=	qf`ldmjpfgjmjwjbojyfojnjwfg#wlBof{bmgqjbqfwjqfnfmwBgufmwvqfpelvq#zfbqp		%ow8\"..#jm`qfbpjmdgf`lqbwjlmk0#`obpp>!lqjdjmp#lelaojdbwjlmqfdvobwjlm`obppjejfg+evm`wjlm+bgubmwbdfpafjmd#wkf#kjpwlqjbmp?abpf#kqfeqfsfbwfgoztjoojmd#wl`lnsbqbaofgfpjdmbwfgmlnjmbwjlmevm`wjlmbojmpjgf#wkfqfufobwjlmfmg#le#wkfp#elq#wkf#bvwklqjyfgqfevpfg#wlwbhf#sob`fbvwlmlnlvp`lnsqlnjpfslojwj`bo#qfpwbvqbmwwtl#le#wkfEfaqvbqz#1rvbojwz#leptelaif`w-vmgfqpwbmgmfbqoz#bootqjwwfm#azjmwfqujftp!#tjgwk>!2tjwkgqbtboeolbw9ofewjp#vpvbooz`bmgjgbwfpmftpsbsfqpnzpwfqjlvpGfsbqwnfmwafpw#hmltmsbqojbnfmwpvssqfppfg`lmufmjfmwqfnfnafqfggjeefqfmw#pzpwfnbwj`kbp#ofg#wlsqlsbdbmgb`lmwqloofgjmeovfm`fp`fqfnlmjbosql`objnfgSqlwf`wjlmoj#`obpp>!P`jfmwjej``obpp>!ml.wqbgfnbqhpnlqf#wkbm#tjgfpsqfbgOjafqbwjlmwllh#sob`fgbz#le#wkfbp#olmd#bpjnsqjplmfgBggjwjlmbo	?kfbg=	?nObalqbwlqzMlufnafq#1f{`fswjlmpJmgvpwqjboubqjfwz#leeolbw9#ofeGvqjmd#wkfbppfppnfmwkbuf#affm#gfbop#tjwkPwbwjpwj`pl``vqqfm`f,vo=?,gju=`ofbqej{!=wkf#svaoj`nbmz#zfbqptkj`k#tfqflufq#wjnf/pzmlmznlvp`lmwfmw!=	sqfpvnbaozkjp#ebnjozvpfqBdfmw-vmf{sf`wfgjm`ovgjmd#`kboofmdfgb#njmlqjwzvmgfejmfg!afolmdp#wlwbhfm#eqlnjm#L`wlafqslpjwjlm9#pbjg#wl#afqfojdjlvp#Efgfqbwjlm#qltpsbm>!lmoz#b#eftnfbmw#wkbwofg#wl#wkf..=	?gju#?ejfogpfw=Bq`kajpkls#`obpp>!mlafjmd#vpfgbssqlb`kfpsqjujofdfpmlp`qjsw=	qfpvowp#jmnbz#af#wkfFbpwfq#fddnf`kbmjpnpqfbplmbaofSlsvobwjlm@loof`wjlmpfof`wfg!=mlp`qjsw=,jmgf{-sksbqqjubo#le.ippgh$**8nbmbdfg#wljm`lnsofwf`bpvbowjfp`lnsofwjlm@kqjpwjbmpPfswfnafq#bqjwknfwj`sql`fgvqfpnjdkw#kbufSqlgv`wjlmjw#bssfbqpSkjolplskzeqjfmgpkjsofbgjmd#wldjujmd#wkfwltbqg#wkfdvbqbmwffggl`vnfmwfg`lolq9 333ujgfl#dbnf`lnnjppjlmqfeof`wjmd`kbmdf#wkfbppl`jbwfgpbmp.pfqjelmhfzsqfpp8#sbggjmd9Kf#tbp#wkfvmgfqozjmdwzsj`booz#/#bmg#wkf#pq`Fofnfmwpv``fppjufpjm`f#wkf#pklvog#af#mfwtlqhjmdb``lvmwjmdvpf#le#wkfoltfq#wkbmpkltp#wkbw?,psbm=	\n\n`lnsobjmwp`lmwjmvlvprvbmwjwjfpbpwqlmlnfqkf#gjg#mlwgvf#wl#jwpbssojfg#wlbm#bufqbdffeelqwp#wlwkf#evwvqfbwwfnsw#wlWkfqfelqf/`bsbajojwzQfsvaoj`bmtbp#elqnfgFof`wqlmj`hjolnfwfqp`kboofmdfpsvaojpkjmdwkf#elqnfqjmgjdfmlvpgjqf`wjlmppvapjgjbqz`lmpsjqb`zgfwbjop#lebmg#jm#wkfbeelqgbaofpvapwbm`fpqfbplm#elq`lmufmwjlmjwfnwzsf>!baplovwfozpvsslpfgozqfnbjmfg#bbwwqb`wjufwqbufoojmdpfsbqbwfozel`vpfp#lmfofnfmwbqzbssoj`baofelvmg#wkbwpwzofpkffwnbmvp`qjswpwbmgp#elq#ml.qfsfbw+plnfwjnfp@lnnfq`jbojm#Bnfqj`bvmgfqwbhfmrvbqwfq#lebm#f{bnsofsfqplmboozjmgf{-sks<?,avwwlm=	sfq`fmwbdfafpw.hmltm`qfbwjmd#b!#gjq>!owqOjfvwfmbmw	?gju#jg>!wkfz#tlvogbajojwz#lenbgf#vs#lemlwfg#wkbw`ofbq#wkbwbqdvf#wkbwwl#bmlwkfq`kjogqfm$psvqslpf#leelqnvobwfgabpfg#vslmwkf#qfdjlmpvaif`w#lesbppfmdfqpslppfppjlm-		Jm#wkf#Afelqf#wkfbewfqtbqgp`vqqfmwoz#b`qlpp#wkfp`jfmwjej``lnnvmjwz-`bsjwbojpnjm#Dfqnbmzqjdkw.tjmdwkf#pzpwfnPl`jfwz#leslojwj`jbmgjqf`wjlm9tfmw#lm#wlqfnlubo#le#Mft#Zlqh#bsbqwnfmwpjmgj`bwjlmgvqjmd#wkfvmofpp#wkfkjpwlqj`bokbg#affm#bgfejmjwjufjmdqfgjfmwbwwfmgbm`f@fmwfq#elqsqlnjmfm`fqfbgzPwbwfpwqbwfdjfpavw#jm#wkfbp#sbqw#le`lmpwjwvwf`objn#wkbwobalqbwlqz`lnsbwjaofebjovqf#le/#pv`k#bp#afdbm#tjwkvpjmd#wkf#wl#sqlujgfefbwvqf#leeqln#tkj`k,!#`obpp>!dfloldj`bopfufqbo#legfojafqbwfjnslqwbmw#klogp#wkbwjmd%rvlw8#ubojdm>wlswkf#Dfqnbmlvwpjgf#lemfdlwjbwfgkjp#`bqffqpfsbqbwjlmjg>!pfbq`ktbp#`boofgwkf#elvqwkqf`qfbwjlmlwkfq#wkbmsqfufmwjlmtkjof#wkf#fgv`bwjlm/`lmmf`wjmdb``vqbwfoztfqf#avjowtbp#hjoofgbdqffnfmwpnv`k#nlqf#Gvf#wl#wkftjgwk9#233plnf#lwkfqHjmdgln#lewkf#fmwjqfebnlvp#elqwl#`lmmf`wlaif`wjufpwkf#Eqfm`ksflsof#bmgefbwvqfg!=jp#pbjg#wlpwqv`wvqboqfefqfmgvnnlpw#lewfmb#pfsbqbwf.=	?gju#jg#Leej`jbo#tlqogtjgf-bqjb.obafowkf#sobmfwbmg#jw#tbpg!#ubovf>!ollhjmd#bwafmfej`jbobqf#jm#wkfnlmjwlqjmdqfslqwfgozwkf#nlgfqmtlqhjmd#lmbooltfg#wltkfqf#wkf#jmmlubwjuf?,b=?,gju=plvmgwqb`hpfbq`kElqnwfmg#wl#afjmsvw#jg>!lsfmjmd#leqfpwqj`wfgbglswfg#azbggqfppjmdwkfloldjbmnfwklgp#leubqjbmw#le@kqjpwjbm#ufqz#obqdfbvwlnlwjufaz#ebq#wkfqbmdf#eqlnsvqpvjw#leeloolt#wkfaqlvdkw#wljm#Fmdobmgbdqff#wkbwb``vpfg#le`lnfp#eqlnsqfufmwjmdgju#pwzof>kjp#lq#kfqwqfnfmglvpeqffgln#le`lm`fqmjmd3#2fn#2fn8Abphfwaboo,pwzof-`ppbm#fbqojfqfufm#bewfq,!#wjwof>!-`ln,jmgf{wbhjmd#wkfsjwwpavqdk`lmwfmw!=?p`qjsw=+ewvqmfg#lvwkbujmd#wkf?,psbm=	#l``bpjlmboaf`bvpf#jwpwbqwfg#wlskzpj`booz=?,gju=	##`qfbwfg#az@vqqfmwoz/#ad`lolq>!wbajmgf{>!gjpbpwqlvpBmbozwj`p#bopl#kbp#b=?gju#jg>!?,pwzof=	?`boofg#elqpjmdfq#bmg-pq`#>#!,,ujlobwjlmpwkjp#sljmw`lmpwbmwozjp#ol`bwfgqf`lqgjmdpg#eqln#wkfmfgfqobmgpslqwvdv/Fp;N;};D;u;F5m4K4]4_7`gfpbqqlool`lnfmwbqjlfgv`b`j/_mpfswjfnaqfqfdjpwqbglgjqf``j/_mvaj`b`j/_msvaoj`jgbgqfpsvfpwbpqfpvowbglpjnslqwbmwfqfpfqubglpbqw/A`volpgjefqfmwfppjdvjfmwfpqfs/Vaoj`bpjwvb`j/_mnjmjpwfqjlsqjub`jgbggjqf`wlqjlelqnb`j/_mslaob`j/_msqfpjgfmwf`lmw", "fmjglpb``fplqjlpwf`kmlqbwjsfqplmbofp`bwfdlq/Abfpsf`jbofpgjpslmjaofb`wvbojgbgqfefqfm`jbuboobglojgajaojlwf`bqfob`jlmfp`bofmgbqjlslo/Awj`bpbmwfqjlqfpgl`vnfmwlpmbwvqbofybnbwfqjbofpgjefqfm`jbf`lm/_nj`bwqbmpslqwfqlgq/Advfysbqwj`jsbqfm`vfmwqbmgjp`vpj/_mfpwqv`wvqbevmgb`j/_meqf`vfmwfpsfqnbmfmwfwlwbonfmwf<P<R<Z<Q<R<]=o<X<Y=n<P<R<Z<Y=n<^=l<Y<P=c=n<\\<V<Z<Y=k=n<R<]=g<]<R<W<Y<Y<R=k<Y<Q=`=a=n<R<_<R<V<R<_<X<\\<S<R=m<W<Y<^=m<Y<_<R=m<\\<U=n<Y=k<Y=l<Y<[<P<R<_=o=n=m<\\<U=n<\\<Z<T<[<Q<T<P<Y<Z<X=o<]=o<X=o=n<s<R<T=m<V<[<X<Y=m=`<^<T<X<Y<R=m<^=c<[<T<Q=o<Z<Q<R=m<^<R<Y<U<W=b<X<Y<U<S<R=l<Q<R<P<Q<R<_<R<X<Y=n<Y<U=m<^<R<T=i<S=l<\\<^<\\=n<\\<V<R<U<P<Y=m=n<R<T<P<Y<Y=n<Z<T<[<Q=`<R<X<Q<R<U<W=o=k=d<Y<S<Y=l<Y<X=k<\\=m=n<T=k<\\=m=n=`=l<\\<]<R=n<Q<R<^=g=i<S=l<\\<^<R=m<R<]<R<U<S<R=n<R<P<P<Y<Q<Y<Y=k<T=m<W<Y<Q<R<^=g<Y=o=m<W=o<_<R<V<R<W<R<Q<\\<[<\\<X=n<\\<V<R<Y=n<R<_<X<\\<S<R=k=n<T<s<R=m<W<Y=n<\\<V<T<Y<Q<R<^=g<U=m=n<R<T=n=n<\\<V<T=i=m=l<\\<[=o<M<\\<Q<V=n=h<R=l=o<P<v<R<_<X<\\<V<Q<T<_<T=m<W<R<^<\\<Q<\\=d<Y<U<Q<\\<U=n<T=m<^<R<T<P=m<^=c<[=`<W=b<]<R<U=k<\\=m=n<R=m=l<Y<X<T<v=l<R<P<Y<H<R=l=o<P=l=g<Q<V<Y=m=n<\\<W<T<S<R<T=m<V=n=g=m=c=k<P<Y=m=c=j=j<Y<Q=n=l=n=l=o<X<\\=m<\\<P=g=i=l=g<Q<V<\\<q<R<^=g<U=k<\\=m<R<^<P<Y=m=n<\\=h<T<W=`<P<P<\\=l=n<\\=m=n=l<\\<Q<P<Y=m=n<Y=n<Y<V=m=n<Q<\\=d<T=i<P<T<Q=o=n<T<P<Y<Q<T<T<P<Y=b=n<Q<R<P<Y=l<_<R=l<R<X=m<\\<P<R<P=a=n<R<P=o<V<R<Q=j<Y=m<^<R<Y<P<V<\\<V<R<U<|=l=i<T<^5i5j4F4C5e4I4]4_4K5h4]4_4K5h4E4K5h4U4K5i5o4F4D5k4K4D4]4K5i4@4K5h5f5d5i4K5h4Y5d4]4@4C5f4C4E4K5h4U4Z5d4I4Z4K5m4E4K5h5n4_5i4K5h4U4K4D4F4A5i5f5h5i5h5m4K4F5i5h4F5n5e4F4U4C5f5h4K5h4X4U4]4O4B4D4K4]4F4[5d5f4]4U5h5f5o5i4I4]5m4K5n4[5h4D4K4F4K5h5h4V4E4F4]4F5f4D4K5h5j4K4_4K5h4X5f4B5i5j4F4C5f4K5h4U4]4D4K5h5n4Y4Y4K5m5h4K5i4U5h5f5k4K4F4A4C5f4G4K5h5h5k5i4K5h4U5i5h5i5o4F4D4E5f5i5o5j5o4K5h4[5m5h5m5f4C5f5d4I4C4K4]4E4F4K4]5f4B4K5h4Y4A4E4F4_4@5f5h4K5h5d5n4F4U5j4C5i4K5i4C5f5j4E4F4Y5i5f5i4O4]4X5f5m4K5h4\\5f5j4U4]4D5f4E4D5d4K4D4E4O5h4U4K4D4K5h4_5m4]5i4X4K5o5h4F4U4K5h5e4K5h4O5d5h4K5h4_5j4E4@4K5i4U4E4K5h4Y4A5m4K5h4C5f5j5o5h5i4K4F4K5h4B4K4Y4K5h5i5h5m4O4U4Z4K4M5o4F4K4D4E4K5h4B5f4]4]4_4K4J5h4K5h5n5h4D4K5h4O4C4D5i5n4K4[4U5i4]4K4_5h5i5j4[5n4E4K5h5o4F4D4K5h4]4@5h4K4X4F4]5o4K5h5n4C5i5f4U4[5f5opAzWbdMbnf+-isd!#bow>!2s{#plojg# -dje!#bow>!wqbmpsbqfmwjmelqnbwjlmbssoj`bwjlm!#lm`oj`h>!fpwbaojpkfgbgufqwjpjmd-smd!#bow>!fmujqlmnfmwsfqelqnbm`fbssqlsqjbwf%bns8ngbpk8jnnfgjbwfoz?,pwqlmd=?,qbwkfq#wkbmwfnsfqbwvqfgfufolsnfmw`lnsfwjwjlmsob`fklogfqujpjajojwz9`lszqjdkw!=3!#kfjdkw>!fufm#wklvdkqfsob`fnfmwgfpwjmbwjlm@lqslqbwjlm?vo#`obpp>!Bppl`jbwjlmjmgjujgvbopsfqpsf`wjufpfwWjnflvw+vqo+kwws9,,nbwkfnbwj`pnbqdjm.wls9fufmwvbooz#gfp`qjswjlm*#ml.qfsfbw`loof`wjlmp-ISDwkvnasbqwj`jsbwf,kfbg=?algzeolbw9ofew8?oj#`obpp>!kvmgqfgp#le		Kltfufq/#`lnslpjwjlm`ofbq9alwk8`llsfqbwjlmtjwkjm#wkf#obafo#elq>!alqgfq.wls9Mft#Yfbobmgqf`lnnfmgfgsklwldqbskzjmwfqfpwjmd%ow8pvs%dw8`lmwqlufqpzMfwkfqobmgpbowfqmbwjufnb{ofmdwk>!ptjwyfqobmgGfufolsnfmwfppfmwjbooz		Bowklvdk#?,wf{wbqfb=wkvmgfqajqgqfsqfpfmwfg%bns8mgbpk8psf`vobwjlm`lnnvmjwjfpofdjpobwjlmfof`wqlmj`p	\n?gju#jg>!joovpwqbwfgfmdjmffqjmdwfqqjwlqjfpbvwklqjwjfpgjpwqjavwfg5!#kfjdkw>!pbmp.pfqje8`bsbaof#le#gjpbssfbqfgjmwfqb`wjufollhjmd#elqjw#tlvog#afBedkbmjpwbmtbp#`qfbwfgNbwk-eollq+pvqqlvmgjmd`bm#bopl#aflapfqubwjlmnbjmwfmbm`ffm`lvmwfqfg?k1#`obpp>!nlqf#qf`fmwjw#kbp#affmjmubpjlm#le*-dfwWjnf+*evmgbnfmwboGfpsjwf#wkf!=?gju#jg>!jmpsjqbwjlmf{bnjmbwjlmsqfsbqbwjlmf{sobmbwjlm?jmsvw#jg>!?,b=?,psbm=ufqpjlmp#lejmpwqvnfmwpafelqf#wkf##>#$kwws9,,Gfp`qjswjlmqfobwjufoz#-pvapwqjmd+fb`k#le#wkff{sfqjnfmwpjmeovfmwjbojmwfdqbwjlmnbmz#sflsofgvf#wl#wkf#`lnajmbwjlmgl#mlw#kbufNjggof#Fbpw?mlp`qjsw=?`lszqjdkw!#sfqkbsp#wkfjmpwjwvwjlmjm#Gf`fnafqbqqbmdfnfmwnlpw#ebnlvpsfqplmbojwz`qfbwjlm#leojnjwbwjlmpf{`ovpjufozplufqfjdmwz.`lmwfmw!=	?wg#`obpp>!vmgfqdqlvmgsbqboofo#wlgl`wqjmf#lel``vsjfg#azwfqnjmloldzQfmbjppbm`fb#mvnafq#lepvsslqw#elqf{solqbwjlmqf`ldmjwjlmsqfgf`fpplq?jnd#pq`>!,?k2#`obpp>!svaoj`bwjlmnbz#bopl#afpsf`jbojyfg?,ejfogpfw=sqldqfppjufnjoojlmp#lepwbwfp#wkbwfmelq`fnfmwbqlvmg#wkf#lmf#bmlwkfq-sbqfmwMlgfbdqj`vowvqfBowfqmbwjufqfpfbq`kfqpwltbqgp#wkfNlpw#le#wkfnbmz#lwkfq#+fpsf`jbooz?wg#tjgwk>!8tjgwk9233&jmgfsfmgfmw?k0#`obpp>!#lm`kbmdf>!*-bgg@obpp+jmwfqb`wjlmLmf#le#wkf#gbvdkwfq#leb``fpplqjfpaqbm`kfp#le	?gju#jg>!wkf#obqdfpwgf`obqbwjlmqfdvobwjlmpJmelqnbwjlmwqbmpobwjlmgl`vnfmwbqzjm#lqgfq#wl!=	?kfbg=	?!#kfjdkw>!2b`qlpp#wkf#lqjfmwbwjlm*8?,p`qjsw=jnsofnfmwfg`bm#af#pffmwkfqf#tbp#bgfnlmpwqbwf`lmwbjmfq!=`lmmf`wjlmpwkf#Aqjwjpktbp#tqjwwfm\"jnslqwbmw8s{8#nbqdjm.elooltfg#azbajojwz#wl#`lnsoj`bwfggvqjmd#wkf#jnnjdqbwjlmbopl#`boofg?k7#`obpp>!gjpwjm`wjlmqfsob`fg#azdlufqmnfmwpol`bwjlm#lejm#Mlufnafqtkfwkfq#wkf?,s=	?,gju=b`rvjpjwjlm`boofg#wkf#sfqpf`vwjlmgfpjdmbwjlmxelmw.pjyf9bssfbqfg#jmjmufpwjdbwff{sfqjfm`fgnlpw#ojhfoztjgfoz#vpfggjp`vppjlmpsqfpfm`f#le#+gl`vnfmw-f{wfmpjufozJw#kbp#affmjw#glfp#mlw`lmwqbqz#wljmkbajwbmwpjnsqlufnfmwp`klobqpkjs`lmpvnswjlmjmpwqv`wjlmelq#f{bnsoflmf#lq#nlqfs{8#sbggjmdwkf#`vqqfmwb#pfqjfp#lebqf#vpvboozqlof#jm#wkfsqfujlvpoz#gfqjubwjufpfujgfm`f#lef{sfqjfm`fp`lolqp`kfnfpwbwfg#wkbw`fqwjej`bwf?,b=?,gju=	#pfof`wfg>!kjdk#p`klloqfpslmpf#wl`lnelqwbaofbglswjlm#lewkqff#zfbqpwkf#`lvmwqzjm#Efaqvbqzpl#wkbw#wkfsflsof#tkl#sqlujgfg#az?sbqbn#mbnfbeef`wfg#azjm#wfqnp#lebssljmwnfmwJPL.;;6:.2!tbp#alqm#jmkjpwlqj`bo#qfdbqgfg#bpnfbpvqfnfmwjp#abpfg#lm#bmg#lwkfq#9#evm`wjlm+pjdmjej`bmw`fofaqbwjlmwqbmpnjwwfg,ip,irvfqz-jp#hmltm#bpwkflqfwj`bo#wbajmgf{>!jw#`lvog#af?mlp`qjsw=	kbujmd#affm	?kfbg=	?#%rvlw8Wkf#`lnsjobwjlmkf#kbg#affmsqlgv`fg#azskjolplskfq`lmpwqv`wfgjmwfmgfg#wlbnlmd#lwkfq`lnsbqfg#wlwl#pbz#wkbwFmdjmffqjmdb#gjeefqfmwqfefqqfg#wlgjeefqfm`fpafojfe#wkbwsklwldqbskpjgfmwjezjmdKjpwlqz#le#Qfsvaoj`#lemf`fppbqjozsqlabajojwzwf`kmj`boozofbujmd#wkfpsf`wb`vobqeqb`wjlm#lefof`wqj`jwzkfbg#le#wkfqfpwbvqbmwpsbqwmfqpkjsfnskbpjp#lmnlpw#qf`fmwpkbqf#tjwk#pbzjmd#wkbwejoofg#tjwkgfpjdmfg#wljw#jp#lewfm!=?,jeqbnf=bp#elooltp9nfqdfg#tjwkwkqlvdk#wkf`lnnfq`jbo#sljmwfg#lvwlsslqwvmjwzujft#le#wkfqfrvjqfnfmwgjujpjlm#lesqldqbnnjmdkf#qf`fjufgpfwJmwfqubo!=?,psbm=?,jm#Mft#Zlqhbggjwjlmbo#`lnsqfppjlm		?gju#jg>!jm`lqslqbwf8?,p`qjsw=?bwwb`kFufmwaf`bnf#wkf#!#wbqdfw>!\\`bqqjfg#lvwPlnf#le#wkfp`jfm`f#bmgwkf#wjnf#le@lmwbjmfq!=nbjmwbjmjmd@kqjpwlskfqNv`k#le#wkftqjwjmdp#le!#kfjdkw>!1pjyf#le#wkfufqpjlm#le#nj{wvqf#le#afwtffm#wkfF{bnsofp#lefgv`bwjlmbo`lnsfwjwjuf#lmpvanjw>!gjqf`wlq#legjpwjm`wjuf,GWG#[KWNO#qfobwjmd#wlwfmgfm`z#wlsqlujm`f#letkj`k#tlvoggfpsjwf#wkfp`jfmwjej`#ofdjpobwvqf-jmmfqKWNO#boofdbwjlmpBdqj`vowvqftbp#vpfg#jmbssqlb`k#wljmwfoojdfmwzfbqp#obwfq/pbmp.pfqjegfwfqnjmjmdSfqelqnbm`fbssfbqbm`fp/#tkj`k#jp#elvmgbwjlmpbaaqfujbwfgkjdkfq#wkbmp#eqln#wkf#jmgjujgvbo#`lnslpfg#lepvsslpfg#wl`objnp#wkbwbwwqjavwjlmelmw.pjyf92fofnfmwp#leKjpwlqj`bo#kjp#aqlwkfqbw#wkf#wjnfbmmjufqpbqzdlufqmfg#azqfobwfg#wl#vowjnbwfoz#jmmlubwjlmpjw#jp#pwjoo`bm#lmoz#afgfejmjwjlmpwlDNWPwqjmdB#mvnafq#lejnd#`obpp>!Fufmwvbooz/tbp#`kbmdfgl``vqqfg#jmmfjdkalqjmdgjpwjmdvjpktkfm#kf#tbpjmwqlgv`jmdwfqqfpwqjboNbmz#le#wkfbqdvfp#wkbwbm#Bnfqj`bm`lmrvfpw#letjgfpsqfbg#tfqf#hjoofgp`qffm#bmg#Jm#lqgfq#wlf{sf`wfg#wlgfp`fmgbmwpbqf#ol`bwfgofdjpobwjufdfmfqbwjlmp#ab`hdqlvmgnlpw#sflsofzfbqp#bewfqwkfqf#jp#mlwkf#kjdkfpweqfrvfmwoz#wkfz#gl#mlwbqdvfg#wkbwpkltfg#wkbwsqfglnjmbmwwkfloldj`boaz#wkf#wjnf`lmpjgfqjmdpklqw.ojufg?,psbm=?,b=`bm#af#vpfgufqz#ojwwoflmf#le#wkf#kbg#boqfbgzjmwfqsqfwfg`lnnvmj`bwfefbwvqfp#ledlufqmnfmw/?,mlp`qjsw=fmwfqfg#wkf!#kfjdkw>!0Jmgfsfmgfmwslsvobwjlmpobqdf.p`bof-#Bowklvdk#vpfg#jm#wkfgfpwqv`wjlmslppjajojwzpwbqwjmd#jmwtl#lq#nlqff{sqfppjlmppvalqgjmbwfobqdfq#wkbmkjpwlqz#bmg?,lswjlm=	@lmwjmfmwbofojnjmbwjmdtjoo#mlw#afsqb`wj`f#lejm#eqlmw#lepjwf#le#wkffmpvqf#wkbwwl#`qfbwf#bnjppjppjssjslwfmwjboozlvwpwbmgjmdafwwfq#wkbmtkbw#jp#mltpjwvbwfg#jmnfwb#mbnf>!WqbgjwjlmbopvddfpwjlmpWqbmpobwjlmwkf#elqn#lebwnlpskfqj`jgfloldj`bofmwfqsqjpfp`bo`vobwjmdfbpw#le#wkfqfnmbmwp#lesovdjmpsbdf,jmgf{-sks<qfnbjmfg#jmwqbmpelqnfgKf#tbp#bopltbp#boqfbgzpwbwjpwj`bojm#ebulq#leNjmjpwqz#lenlufnfmw#leelqnvobwjlmjp#qfrvjqfg?ojmh#qfo>!Wkjp#jp#wkf#?b#kqfe>!,slsvobqjyfgjmuloufg#jmbqf#vpfg#wlbmg#pfufqbonbgf#az#wkfpffnp#wl#afojhfoz#wkbwSbofpwjmjbmmbnfg#bewfqjw#kbg#affmnlpw#`lnnlmwl#qfefq#wlavw#wkjp#jp`lmpf`vwjufwfnslqbqjozJm#dfmfqbo/`lmufmwjlmpwbhfp#sob`fpvagjujpjlmwfqqjwlqjbolsfqbwjlmbosfqnbmfmwoztbp#obqdfozlvwaqfbh#lejm#wkf#sbpwelooltjmd#b#{nomp9ld>!=?b#`obpp>!`obpp>!wf{w@lmufqpjlm#nbz#af#vpfgnbmveb`wvqfbewfq#afjmd`ofbqej{!=	rvfpwjlm#letbp#fof`wfgwl#af`lnf#baf`bvpf#le#plnf#sflsofjmpsjqfg#azpv``fppevo#b#wjnf#tkfmnlqf#`lnnlmbnlmdpw#wkfbm#leej`jbotjgwk9233&8wf`kmloldz/tbp#bglswfgwl#hffs#wkfpfwwofnfmwpojuf#ajqwkpjmgf{-kwno!@lmmf`wj`vwbppjdmfg#wl%bns8wjnfp8b``lvmw#elqbojdm>qjdkwwkf#`lnsbmzbotbzp#affmqfwvqmfg#wljmuloufnfmwAf`bvpf#wkfwkjp#sfqjlg!#mbnf>!r!#`lmejmfg#wlb#qfpvow#leubovf>!!#,=jp#b`wvboozFmujqlmnfmw	?,kfbg=	@lmufqpfoz/=	?gju#jg>!3!#tjgwk>!2jp#sqlabaozkbuf#af`lnf`lmwqloojmdwkf#sqlaofn`jwjyfmp#leslojwj`jbmpqfb`kfg#wkfbp#fbqoz#bp9mlmf8#lufq?wbaof#`fooubojgjwz#legjqf`woz#wllmnlvpfgltmtkfqf#jw#jptkfm#jw#tbpnfnafqp#le#qfobwjlm#wlb``lnnlgbwfbolmd#tjwk#Jm#wkf#obwfwkf#Fmdojpkgfoj`jlvp!=wkjp#jp#mlwwkf#sqfpfmwje#wkfz#bqfbmg#ejmboozb#nbwwfq#le	\n?,gju=		?,p`qjsw=ebpwfq#wkbmnbilqjwz#lebewfq#tkj`k`lnsbqbwjufwl#nbjmwbjmjnsqluf#wkfbtbqgfg#wkffq!#`obpp>!eqbnfalqgfqqfpwlqbwjlmjm#wkf#pbnfbmbozpjp#lewkfjq#ejqpwGvqjmd#wkf#`lmwjmfmwbopfrvfm`f#leevm`wjlm+*xelmw.pjyf9#tlqh#lm#wkf?,p`qjsw=	?afdjmp#tjwkibubp`qjsw9`lmpwjwvfmwtbp#elvmgfgfrvjojaqjvnbppvnf#wkbwjp#djufm#azmffgp#wl#af`llqgjmbwfpwkf#ubqjlvpbqf#sbqw#lelmoz#jm#wkfpf`wjlmp#lejp#b#`lnnlmwkflqjfp#legjp`lufqjfpbppl`jbwjlmfgdf#le#wkfpwqfmdwk#leslpjwjlm#jmsqfpfmw.gbzvmjufqpboozwl#elqn#wkfavw#jmpwfbg`lqslqbwjlmbwwb`kfg#wljp#`lnnlmozqfbplmp#elq#%rvlw8wkf#`bm#af#nbgftbp#baof#wltkj`k#nfbmpavw#gjg#mlwlmNlvpfLufqbp#slppjaoflsfqbwfg#az`lnjmd#eqlnwkf#sqjnbqzbggjwjlm#leelq#pfufqbowqbmpefqqfgb#sfqjlg#lebqf#baof#wlkltfufq/#jwpklvog#kbufnv`k#obqdfq	\n?,p`qjsw=bglswfg#wkfsqlsfqwz#legjqf`wfg#azfeef`wjufoztbp#aqlvdkw`kjogqfm#leSqldqbnnjmdolmdfq#wkbmnbmvp`qjswptbq#bdbjmpwaz#nfbmp#lebmg#nlpw#lepjnjobq#wl#sqlsqjfwbqzlqjdjmbwjmdsqfpwjdjlvpdqbnnbwj`bof{sfqjfm`f-wl#nbhf#wkfJw#tbp#bopljp#elvmg#jm`lnsfwjwlqpjm#wkf#V-P-qfsob`f#wkfaqlvdkw#wkf`bo`vobwjlmeboo#le#wkfwkf#dfmfqbosqb`wj`boozjm#klmlq#leqfofbpfg#jmqfpjgfmwjbobmg#plnf#lehjmd#le#wkfqfb`wjlm#wl2pw#Fbqo#le`vowvqf#bmgsqjm`jsbooz?,wjwof=	##wkfz#`bm#afab`h#wl#wkfplnf#le#kjpf{slpvqf#wlbqf#pjnjobqelqn#le#wkfbggEbulqjwf`jwjyfmpkjssbqw#jm#wkfsflsof#tjwkjm#sqb`wj`fwl#`lmwjmvf%bns8njmvp8bssqlufg#az#wkf#ejqpw#booltfg#wkfbmg#elq#wkfevm`wjlmjmdsobzjmd#wkfplovwjlm#wlkfjdkw>!3!#jm#kjp#allhnlqf#wkbm#belooltp#wkf`qfbwfg#wkfsqfpfm`f#jm%maps8?,wg=mbwjlmbojpwwkf#jgfb#leb#`kbqb`wfqtfqf#elq`fg#`obpp>!awmgbzp#le#wkfefbwvqfg#jmpkltjmd#wkfjmwfqfpw#jmjm#sob`f#lewvqm#le#wkfwkf#kfbg#leOlqg#le#wkfslojwj`boozkbp#jwp#ltmFgv`bwjlmbobssqlubo#leplnf#le#wkffb`k#lwkfq/afkbujlq#lebmg#af`bvpfbmg#bmlwkfqbssfbqfg#lmqf`lqgfg#jmaob`h%rvlw8nbz#jm`ovgfwkf#tlqog$p`bm#ofbg#wlqfefqp#wl#balqgfq>!3!#dlufqmnfmw#tjmmjmd#wkfqfpvowfg#jm#tkjof#wkf#Tbpkjmdwlm/wkf#pvaif`w`jwz#jm#wkf=?,gju=	\n\nqfeof`w#wkfwl#`lnsofwfaf`bnf#nlqfqbgjlb`wjufqfif`wfg#aztjwklvw#bmzkjp#ebwkfq/tkj`k#`lvog`lsz#le#wkfwl#jmgj`bwfb#slojwj`bob``lvmwp#le`lmpwjwvwfptlqhfg#tjwkfq?,b=?,oj=le#kjp#ojefb``lnsbmjfg`ojfmwTjgwksqfufmw#wkfOfdjpobwjufgjeefqfmwozwldfwkfq#jmkbp#pfufqboelq#bmlwkfqwf{w#le#wkfelvmgfg#wkff#tjwk#wkf#jp#vpfg#elq`kbmdfg#wkfvpvbooz#wkfsob`f#tkfqftkfqfbp#wkf=#?b#kqfe>!!=?b#kqfe>!wkfnpfoufp/bowklvdk#kfwkbw#`bm#afwqbgjwjlmboqlof#le#wkfbp#b#qfpvowqfnluf@kjoggfpjdmfg#aztfpw#le#wkfPlnf#sflsofsqlgv`wjlm/pjgf#le#wkfmftpofwwfqpvpfg#az#wkfgltm#wl#wkfb``fswfg#azojuf#jm#wkfbwwfnswp#wllvwpjgf#wkfeqfrvfm`jfpKltfufq/#jmsqldqbnnfqpbw#ofbpw#jmbssql{jnbwfbowklvdk#jwtbp#sbqw#lebmg#ubqjlvpDlufqmlq#lewkf#bqwj`ofwvqmfg#jmwl=?b#kqfe>!,wkf#f`lmlnzjp#wkf#nlpwnlpw#tjgfoztlvog#obwfqbmg#sfqkbspqjpf#wl#wkfl``vqp#tkfmvmgfq#tkj`k`lmgjwjlmp-wkf#tfpwfqmwkflqz#wkbwjp#sqlgv`fgwkf#`jwz#lejm#tkj`k#kfpffm#jm#wkfwkf#`fmwqboavjogjmd#lenbmz#le#kjpbqfb#le#wkfjp#wkf#lmoznlpw#le#wkfnbmz#le#wkfwkf#TfpwfqmWkfqf#jp#mlf{wfmgfg#wlPwbwjpwj`bo`lopsbm>1#pklqw#pwlqzslppjaof#wlwlsloldj`bo`qjwj`bo#leqfslqwfg#wlb#@kqjpwjbmgf`jpjlm#wljp#frvbo#wlsqlaofnp#leWkjp#`bm#afnfq`kbmgjpfelq#nlpw#leml#fujgfm`ffgjwjlmp#lefofnfmwp#jm%rvlw8-#Wkf`ln,jnbdfp,tkj`k#nbhfpwkf#sql`fppqfnbjmp#wkfojwfqbwvqf/jp#b#nfnafqwkf#slsvobqwkf#bm`jfmwsqlaofnp#jmwjnf#le#wkfgfefbwfg#azalgz#le#wkfb#eft#zfbqpnv`k#le#wkfwkf#tlqh#le@bojelqmjb/pfqufg#bp#bdlufqmnfmw-`lm`fswp#lenlufnfmw#jm\n\n?gju#jg>!jw!#ubovf>!obmdvbdf#lebp#wkfz#bqfsqlgv`fg#jmjp#wkbw#wkff{sobjm#wkfgju=?,gju=	Kltfufq#wkfofbg#wl#wkf\n?b#kqfe>!,tbp#dqbmwfgsflsof#kbuf`lmwjmvbooztbp#pffm#bpbmg#qfobwfgwkf#qlof#lesqlslpfg#azle#wkf#afpwfb`k#lwkfq-@lmpwbmwjmfsflsof#eqlngjbof`wp#lewl#qfujpjlmtbp#qfmbnfgb#plvq`f#lewkf#jmjwjboobvm`kfg#jmsqlujgf#wkfwl#wkf#tfpwtkfqf#wkfqfbmg#pjnjobqafwtffm#wtljp#bopl#wkfFmdojpk#bmg`lmgjwjlmp/wkbw#jw#tbpfmwjwofg#wlwkfnpfoufp-rvbmwjwz#leqbmpsbqfm`zwkf#pbnf#bpwl#iljm#wkf`lvmwqz#bmgwkjp#jp#wkfWkjp#ofg#wlb#pwbwfnfmw`lmwqbpw#wlobpwJmgf{Lewkqlvdk#kjpjp#gfpjdmfgwkf#wfqn#jpjp#sqlujgfgsqlwf`w#wkfmd?,b=?,oj=Wkf#`vqqfmwwkf#pjwf#lepvapwbmwjbof{sfqjfm`f/jm#wkf#Tfpwwkfz#pklvogpolufm(ajmb`lnfmwbqjlpvmjufqpjgbg`lmgj`jlmfpb`wjujgbgfpf{sfqjfm`jbwf`mlold/Absqlgv``j/_msvmwvb`j/_mbsoj`b`j/_m`lmwqbpf/]b`bwfdlq/Abpqfdjpwqbqpfsqlefpjlmbowqbwbnjfmwlqfd/Apwqbwfpf`qfwbq/Absqjm`jsbofpsqlwf``j/_mjnslqwbmwfpjnslqwbm`jbslpjajojgbgjmwfqfpbmwf`qf`jnjfmwlmf`fpjgbgfppvp`qjajqpfbpl`jb`j/_mgjpslmjaofpfubovb`j/_mfpwvgjbmwfpqfpslmpbaofqfplov`j/_mdvbgbobibqbqfdjpwqbglplslqwvmjgbg`lnfq`jbofpelwldqbe/Abbvwlqjgbgfpjmdfmjfq/Abwfofujpj/_m`lnsfwfm`jblsfqb`jlmfpfpwbaof`jglpjnsofnfmwfb`wvbonfmwfmbufdb`j/_m`lmelqnjgbgojmf.kfjdkw9elmw.ebnjoz9!#9#!kwws9,,bssoj`bwjlmpojmh!#kqfe>!psf`jej`booz,,?\"X@GBWBX	Lqdbmjybwjlmgjpwqjavwjlm3s{8#kfjdkw9qfobwjlmpkjsgfuj`f.tjgwk?gju#`obpp>!?obafo#elq>!qfdjpwqbwjlm?,mlp`qjsw=	,jmgf{-kwno!tjmglt-lsfm+#\"jnslqwbmw8bssoj`bwjlm,jmgfsfmgfm`f,,ttt-dlldoflqdbmjybwjlmbvwl`lnsofwfqfrvjqfnfmwp`lmpfqubwjuf?elqn#mbnf>!jmwfoof`wvbonbqdjm.ofew92;wk#`fmwvqzbm#jnslqwbmwjmpwjwvwjlmpbaaqfujbwjlm?jnd#`obpp>!lqdbmjpbwjlm`jujojybwjlm2:wk#`fmwvqzbq`kjwf`wvqfjm`lqslqbwfg13wk#`fmwvqz.`lmwbjmfq!=nlpw#mlwbaoz,=?,b=?,gju=mlwjej`bwjlm$vmgfejmfg$*Evqwkfqnlqf/afojfuf#wkbwjmmfqKWNO#>#sqjlq#wl#wkfgqbnbwj`boozqfefqqjmd#wlmfdlwjbwjlmpkfbgrvbqwfqpPlvwk#Beqj`bvmpv``fppevoSfmmpzoubmjbBp#b#qfpvow/?kwno#obmd>!%ow8,pvs%dw8gfbojmd#tjwkskjobgfoskjbkjpwlqj`booz*8?,p`qjsw=	sbggjmd.wls9f{sfqjnfmwbodfwBwwqjavwfjmpwqv`wjlmpwf`kmloldjfpsbqw#le#wkf#>evm`wjlm+*xpvap`qjswjlmo-gwg!=	?kwdfldqbskj`bo@lmpwjwvwjlm$/#evm`wjlm+pvsslqwfg#azbdqj`vowvqbo`lmpwqv`wjlmsvaoj`bwjlmpelmw.pjyf9#2b#ubqjfwz#le?gju#pwzof>!Fm`z`olsfgjbjeqbnf#pq`>!gfnlmpwqbwfgb``lnsojpkfgvmjufqpjwjfpGfnldqbskj`p*8?,p`qjsw=?gfgj`bwfg#wlhmltofgdf#lepbwjpeb`wjlmsbqwj`vobqoz?,gju=?,gju=Fmdojpk#+VP*bssfmg@kjog+wqbmpnjppjlmp-#Kltfufq/#jmwfoojdfm`f!#wbajmgf{>!eolbw9qjdkw8@lnnlmtfbowkqbmdjmd#eqlnjm#tkj`k#wkfbw#ofbpw#lmfqfsqlgv`wjlmfm`z`olsfgjb8elmw.pjyf92ivqjpgj`wjlmbw#wkbw#wjnf!=?b#`obpp>!Jm#bggjwjlm/gfp`qjswjlm(`lmufqpbwjlm`lmwb`w#tjwkjp#dfmfqboozq!#`lmwfmw>!qfsqfpfmwjmd%ow8nbwk%dw8sqfpfmwbwjlml``bpjlmbooz?jnd#tjgwk>!mbujdbwjlm!=`lnsfmpbwjlm`kbnsjlmpkjsnfgjb>!boo!#ujlobwjlm#leqfefqfm`f#wlqfwvqm#wqvf8Pwqj`w,,FM!#wqbmpb`wjlmpjmwfqufmwjlmufqjej`bwjlmJmelqnbwjlm#gjeej`vowjfp@kbnsjlmpkjs`bsbajojwjfp?\"Xfmgje^..=~	?,p`qjsw=	@kqjpwjbmjwzelq#f{bnsof/Sqlefppjlmboqfpwqj`wjlmppvddfpw#wkbwtbp#qfofbpfg+pv`k#bp#wkfqfnluf@obpp+vmfnsolznfmwwkf#Bnfqj`bmpwqv`wvqf#le,jmgf{-kwno#svaojpkfg#jmpsbm#`obpp>!!=?b#kqfe>!,jmwqlgv`wjlmafolmdjmd#wl`objnfg#wkbw`lmpfrvfm`fp?nfwb#mbnf>!Dvjgf#wl#wkflufqtkfonjmdbdbjmpw#wkf#`lm`fmwqbwfg/	-mlmwlv`k#lapfqubwjlmp?,b=	?,gju=	e#+gl`vnfmw-alqgfq9#2s{#xelmw.pjyf92wqfbwnfmw#le3!#kfjdkw>!2nlgjej`bwjlmJmgfsfmgfm`fgjujgfg#jmwldqfbwfq#wkbmb`kjfufnfmwpfpwbaojpkjmdIbubP`qjsw!#mfufqwkfofpppjdmjej`bm`fAqlbg`bpwjmd=%maps8?,wg=`lmwbjmfq!=	pv`k#bp#wkf#jmeovfm`f#leb#sbqwj`vobqpq`>$kwws9,,mbujdbwjlm!#kboe#le#wkf#pvapwbmwjbo#%maps8?,gju=bgubmwbdf#legjp`lufqz#leevmgbnfmwbo#nfwqlslojwbmwkf#lsslpjwf!#{no9obmd>!gfojafqbwfozbojdm>`fmwfqfulovwjlm#lesqfpfqubwjlmjnsqlufnfmwpafdjmmjmd#jmIfpvp#@kqjpwSvaoj`bwjlmpgjpbdqffnfmwwf{w.bojdm9q/#evm`wjlm+*pjnjobqjwjfpalgz=?,kwno=jp#`vqqfmwozboskbafwj`bojp#plnfwjnfpwzsf>!jnbdf,nbmz#le#wkf#eolt9kjggfm8bubjobaof#jmgfp`qjaf#wkff{jpwfm`f#leboo#lufq#wkfwkf#Jmwfqmfw\n?vo#`obpp>!jmpwboobwjlmmfjdkalqkllgbqnfg#elq`fpqfgv`jmd#wkf`lmwjmvfp#wlMlmfwkfofpp/wfnsfqbwvqfp	\n\n?b#kqfe>!`olpf#wl#wkff{bnsofp#le#jp#balvw#wkf+pff#afolt*-!#jg>!pfbq`ksqlefppjlmbojp#bubjobaofwkf#leej`jbo\n\n?,p`qjsw=		\n\n?gju#jg>!b``fofqbwjlmwkqlvdk#wkf#Kboo#le#Ebnfgfp`qjswjlmpwqbmpobwjlmpjmwfqefqfm`f#wzsf>$wf{w,qf`fmw#zfbqpjm#wkf#tlqogufqz#slsvobqxab`hdqlvmg9wqbgjwjlmbo#plnf#le#wkf#`lmmf`wfg#wlf{soljwbwjlmfnfqdfm`f#le`lmpwjwvwjlmB#Kjpwlqz#lepjdmjej`bmw#nbmveb`wvqfgf{sf`wbwjlmp=?mlp`qjsw=?`bm#af#elvmgaf`bvpf#wkf#kbp#mlw#affmmfjdkalvqjmdtjwklvw#wkf#bggfg#wl#wkf\n?oj#`obpp>!jmpwqvnfmwboPlujfw#Vmjlmb`hmltofgdfgtkj`k#`bm#afmbnf#elq#wkfbwwfmwjlm#wlbwwfnswp#wl#gfufolsnfmwpJm#eb`w/#wkf?oj#`obpp>!bjnsoj`bwjlmppvjwbaof#elqnv`k#le#wkf#`lolmjybwjlmsqfpjgfmwjbo`bm`foAvaaof#Jmelqnbwjlmnlpw#le#wkf#jp#gfp`qjafgqfpw#le#wkf#nlqf#lq#ofppjm#PfswfnafqJmwfoojdfm`fpq`>!kwws9,,s{8#kfjdkw9#bubjobaof#wlnbmveb`wvqfqkvnbm#qjdkwpojmh#kqfe>!,bubjobajojwzsqlslqwjlmbolvwpjgf#wkf#bpwqlmlnj`bokvnbm#afjmdpmbnf#le#wkf#bqf#elvmg#jmbqf#abpfg#lmpnboofq#wkbmb#sfqplm#tklf{sbmpjlm#lebqdvjmd#wkbwmlt#hmltm#bpJm#wkf#fbqozjmwfqnfgjbwfgfqjufg#eqlnP`bmgjmbujbm?,b=?,gju=	`lmpjgfq#wkfbm#fpwjnbwfgwkf#Mbwjlmbo?gju#jg>!sbdqfpvowjmd#jm`lnnjppjlmfgbmboldlvp#wlbqf#qfrvjqfg,vo=	?,gju=	tbp#abpfg#lmbmg#af`bnf#b%maps8%maps8w!#ubovf>!!#tbp#`bswvqfgml#nlqf#wkbmqfpsf`wjufoz`lmwjmvf#wl#=	?kfbg=	?tfqf#`qfbwfgnlqf#dfmfqbojmelqnbwjlm#vpfg#elq#wkfjmgfsfmgfmw#wkf#Jnsfqjbo`lnslmfmw#lewl#wkf#mlqwkjm`ovgf#wkf#@lmpwqv`wjlmpjgf#le#wkf#tlvog#mlw#afelq#jmpwbm`fjmufmwjlm#lenlqf#`lnsof{`loof`wjufozab`hdqlvmg9#wf{w.bojdm9#jwp#lqjdjmbojmwl#b``lvmwwkjp#sql`fppbm#f{wfmpjufkltfufq/#wkfwkfz#bqf#mlwqfif`wfg#wkf`qjwj`jpn#legvqjmd#tkj`ksqlabaoz#wkfwkjp#bqwj`of+evm`wjlm+*xJw#pklvog#afbm#bdqffnfmwb``jgfmwboozgjeefqp#eqlnBq`kjwf`wvqfafwwfq#hmltmbqqbmdfnfmwpjmeovfm`f#lmbwwfmgfg#wkfjgfmwj`bo#wlplvwk#le#wkfsbpp#wkqlvdk{no!#wjwof>!tfjdkw9alog8`qfbwjmd#wkfgjpsobz9mlmfqfsob`fg#wkf?jnd#pq`>!,jkwwsp9,,ttt-Tlqog#Tbq#JJwfpwjnlmjbopelvmg#jm#wkfqfrvjqfg#wl#bmg#wkbw#wkfafwtffm#wkf#tbp#gfpjdmfg`lmpjpwp#le#`lmpjgfqbaozsvaojpkfg#azwkf#obmdvbdf@lmpfqubwjlm`lmpjpwfg#leqfefq#wl#wkfab`h#wl#wkf#`pp!#nfgjb>!Sflsof#eqln#bubjobaof#lmsqlufg#wl#afpvddfpwjlmp!tbp#hmltm#bpubqjfwjfp#leojhfoz#wl#af`lnsqjpfg#lepvsslqw#wkf#kbmgp#le#wkf`lvsofg#tjwk`lmmf`w#bmg#alqgfq9mlmf8sfqelqnbm`fpafelqf#afjmdobwfq#af`bnf`bo`vobwjlmplewfm#`boofgqfpjgfmwp#lenfbmjmd#wkbw=?oj#`obpp>!fujgfm`f#elqf{sobmbwjlmpfmujqlmnfmwp!=?,b=?,gju=tkj`k#booltpJmwqlgv`wjlmgfufolsfg#azb#tjgf#qbmdflm#afkboe#leubojdm>!wls!sqjm`jsof#lebw#wkf#wjnf/?,mlp`qjsw=pbjg#wl#kbufjm#wkf#ejqpwtkjof#lwkfqpkzslwkfwj`boskjolplskfqpsltfq#le#wkf`lmwbjmfg#jmsfqelqnfg#azjmbajojwz#wltfqf#tqjwwfmpsbm#pwzof>!jmsvw#mbnf>!wkf#rvfpwjlmjmwfmgfg#elqqfif`wjlm#lejnsojfp#wkbwjmufmwfg#wkfwkf#pwbmgbqgtbp#sqlabaozojmh#afwtffmsqlefpplq#lejmwfqb`wjlmp`kbmdjmd#wkfJmgjbm#L`fbm#`obpp>!obpwtlqhjmd#tjwk$kwws9,,ttt-zfbqp#afelqfWkjp#tbp#wkfqf`qfbwjlmbofmwfqjmd#wkfnfbpvqfnfmwpbm#f{wqfnfozubovf#le#wkfpwbqw#le#wkf	?,p`qjsw=		bm#feelqw#wljm`qfbpf#wkfwl#wkf#plvwkpsb`jmd>!3!=pveej`jfmwozwkf#Fvqlsfbm`lmufqwfg#wl`ofbqWjnflvwgjg#mlw#kbuf`lmpfrvfmwozelq#wkf#mf{wf{wfmpjlm#lef`lmlnj`#bmgbowklvdk#wkfbqf#sqlgv`fgbmg#tjwk#wkfjmpveej`jfmwdjufm#az#wkfpwbwjmd#wkbwf{sfmgjwvqfp?,psbm=?,b=	wklvdkw#wkbwlm#wkf#abpjp`foosbggjmd>jnbdf#le#wkfqfwvqmjmd#wljmelqnbwjlm/pfsbqbwfg#azbppbppjmbwfgp!#`lmwfmw>!bvwklqjwz#lemlqwktfpwfqm?,gju=	?gju#!=?,gju=	##`lmpvowbwjlm`lnnvmjwz#lewkf#mbwjlmbojw#pklvog#afsbqwj`jsbmwp#bojdm>!ofewwkf#dqfbwfpwpfof`wjlm#lepvsfqmbwvqbogfsfmgfmw#lmjp#nfmwjlmfgbooltjmd#wkftbp#jmufmwfgb``lnsbmzjmdkjp#sfqplmbobubjobaof#bwpwvgz#le#wkflm#wkf#lwkfqf{f`vwjlm#leKvnbm#Qjdkwpwfqnp#le#wkfbppl`jbwjlmpqfpfbq`k#bmgpv``ffgfg#azgfefbwfg#wkfbmg#eqln#wkfavw#wkfz#bqf`lnnbmgfq#lepwbwf#le#wkfzfbqp#le#bdfwkf#pwvgz#le?vo#`obpp>!psob`f#jm#wkftkfqf#kf#tbp?oj#`obpp>!ewkfqf#bqf#mltkj`k#af`bnfkf#svaojpkfgf{sqfppfg#jmwl#tkj`k#wkf`lnnjppjlmfqelmw.tfjdkw9wfqqjwlqz#lef{wfmpjlmp!=Qlnbm#Fnsjqffrvbo#wl#wkfJm#`lmwqbpw/kltfufq/#bmgjp#wzsj`boozbmg#kjp#tjef+bopl#`boofg=?vo#`obpp>!feef`wjufoz#fuloufg#jmwlpffn#wl#kbuftkj`k#jp#wkfwkfqf#tbp#mlbm#f{`foofmwboo#le#wkfpfgfp`qjafg#azJm#sqb`wj`f/aqlbg`bpwjmd`kbqdfg#tjwkqfeof`wfg#jmpvaif`wfg#wlnjojwbqz#bmgwl#wkf#sljmwf`lmlnj`boozpfwWbqdfwjmdbqf#b`wvboozuj`wlqz#lufq+*8?,p`qjsw=`lmwjmvlvpozqfrvjqfg#elqfulovwjlmbqzbm#feef`wjufmlqwk#le#wkf/#tkj`k#tbp#eqlmw#le#wkflq#lwkfqtjpfplnf#elqn#lekbg#mlw#affmdfmfqbwfg#azjmelqnbwjlm-sfqnjwwfg#wljm`ovgfp#wkfgfufolsnfmw/fmwfqfg#jmwlwkf#sqfujlvp`lmpjpwfmwozbqf#hmltm#bpwkf#ejfog#lewkjp#wzsf#ledjufm#wl#wkfwkf#wjwof#le`lmwbjmp#wkfjmpwbm`fp#lejm#wkf#mlqwkgvf#wl#wkfjqbqf#gfpjdmfg`lqslqbwjlmptbp#wkbw#wkflmf#le#wkfpfnlqf#slsvobqpv``ffgfg#jmpvsslqw#eqlnjm#gjeefqfmwglnjmbwfg#azgfpjdmfg#elqltmfqpkjs#lebmg#slppjaozpwbmgbqgjyfgqfpslmpfWf{wtbp#jmwfmgfgqf`fjufg#wkfbppvnfg#wkbwbqfbp#le#wkfsqjnbqjoz#jmwkf#abpjp#lejm#wkf#pfmpfb``lvmwp#elqgfpwqlzfg#azbw#ofbpw#wtltbp#gf`obqfg`lvog#mlw#afPf`qfwbqz#lebssfbq#wl#afnbqdjm.wls92,]_p(_p(',df*xwkqlt#f~8wkf#pwbqw#lewtl#pfsbqbwfobmdvbdf#bmgtkl#kbg#affmlsfqbwjlm#legfbwk#le#wkfqfbo#mvnafqp\n?ojmh#qfo>!sqlujgfg#wkfwkf#pwlqz#le`lnsfwjwjlmpfmdojpk#+VH*fmdojpk#+VP*<p<R<Q<_<R<W<M=l<S=m<V<T=m=l<S=m<V<T=m=l<S=m<V<R5h4U4]4D5f4E\nAOGx\bTA\nzk\vBl\bQ\bTA\nzk\vUm\bQ\bTA\nzk\npeu|	i@	cT\bVV\n\\}\nxS	VptSk`	[X	[X\vHR\bPv\bTW\bUe\na\bQp\v_W\vWs\nxS\vAz\n_yKhjmelqnb`j/_mkfqqbnjfmwbpfof`wq/_mj`lgfp`qjs`j/_m`obpjej`bglp`lml`jnjfmwlsvaoj`b`j/_mqfob`jlmbgbpjmelqn/Mwj`bqfob`jlmbglpgfsbqwbnfmwlwqbabibglqfpgjqf`wbnfmwfbzvmwbnjfmwlnfq`bglOjaqf`lmw/M`wfmlpkbajwb`jlmfp`vnsojnjfmwlqfpwbvqbmwfpgjpslpj`j/_m`lmpf`vfm`jbfof`wq/_mj`bbsoj`b`jlmfpgfp`lmf`wbgljmpwbob`j/_mqfbojyb`j/_mvwjojyb`j/_mfm`j`olsfgjbfmefqnfgbgfpjmpwqvnfmwlpf{sfqjfm`jbpjmpwjwv`j/_msbqwj`vobqfppva`bwfdlqjb=n<R<W=`<V<R<L<R=m=m<T<T=l<\\<]<R=n=g<]<R<W=`=d<Y<S=l<R=m=n<R<P<R<Z<Y=n<Y<X=l=o<_<T=i=m<W=o=k<\\<Y=m<Y<U=k<\\=m<^=m<Y<_<X<\\<L<R=m=m<T=c<p<R=m<V<^<Y<X=l=o<_<T<Y<_<R=l<R<X<\\<^<R<S=l<R=m<X<\\<Q<Q=g=i<X<R<W<Z<Q=g<T<P<Y<Q<Q<R<p<R=m<V<^=g=l=o<]<W<Y<U<p<R=m<V<^<\\=m=n=l<\\<Q=g<Q<T=k<Y<_<R=l<\\<]<R=n<Y<X<R<W<Z<Y<Q=o=m<W=o<_<T=n<Y<S<Y=l=`<r<X<Q<\\<V<R<S<R=n<R<P=o=l<\\<]<R=n=o<\\<S=l<Y<W=c<^<R<R<]=e<Y<R<X<Q<R<_<R=m<^<R<Y<_<R=m=n<\\=n=`<T<X=l=o<_<R<U=h<R=l=o<P<Y=i<R=l<R=d<R<S=l<R=n<T<^=m=m=g<W<V<\\<V<\\<Z<X=g<U<^<W<\\=m=n<T<_=l=o<S<S=g<^<P<Y=m=n<Y=l<\\<]<R=n<\\=m<V<\\<[<\\<W<S<Y=l<^=g<U<X<Y<W<\\=n=`<X<Y<Q=`<_<T<S<Y=l<T<R<X<]<T<[<Q<Y=m<R=m<Q<R<^<Y<P<R<P<Y<Q=n<V=o<S<T=n=`<X<R<W<Z<Q<\\=l<\\<P<V<\\=i<Q<\\=k<\\<W<R<L<\\<]<R=n<\\<N<R<W=`<V<R=m<R<^=m<Y<P<^=n<R=l<R<U<Q<\\=k<\\<W<\\=m<S<T=m<R<V=m<W=o<Z<]=g=m<T=m=n<Y<P<S<Y=k<\\=n<T<Q<R<^<R<_<R<S<R<P<R=e<T=m<\\<U=n<R<^<S<R=k<Y<P=o<S<R<P<R=e=`<X<R<W<Z<Q<R=m=m=g<W<V<T<]=g=m=n=l<R<X<\\<Q<Q=g<Y<P<Q<R<_<T<Y<S=l<R<Y<V=n<M<Y<U=k<\\=m<P<R<X<Y<W<T=n<\\<V<R<_<R<R<Q<W<\\<U<Q<_<R=l<R<X<Y<^<Y=l=m<T=c=m=n=l<\\<Q<Y=h<T<W=`<P=g=o=l<R<^<Q=c=l<\\<[<Q=g=i<T=m<V<\\=n=`<Q<Y<X<Y<W=b=c<Q<^<\\=l=c<P<Y<Q=`=d<Y<P<Q<R<_<T=i<X<\\<Q<Q<R<U<[<Q<\\=k<T=n<Q<Y<W=`<[=c=h<R=l=o<P<\\<N<Y<S<Y=l=`<P<Y=m=c=j<\\<[<\\=e<T=n=g<w=o=k=d<T<Y\fHD\fHU\fIl\fHn\fHy\fH\\\fHD\fIk\fHi\fHF\fHD\fIk\fHy\fHS\fHC\fHR\fHy\fH\\\fIk\fHn\fHi\fHD\fIa\fHC\fHy\fIa\fHC\fHR\fH{\fHR\fHk\fHM\fH@\fHR\fH\\\fIk\fHy\fHS\fHT\fIl\fHJ\fHS\fHC\fHR\fHF\fHU\fH^\fIk\fHT\fHS\fHn\fHU\fHA\fHR\fH\\\fHH\fHi\fHF\fHD\fIl\fHY\fHR\fH^\fIk\fHT\fIk\fHY\fHR\fHy\fH\\\fHH\fIk\fHB\fIk\fH\\\fIk\fHU\fIg\fHD\fIk\fHT\fHy\fHH\fIk\fH@\fHU\fIm\fHH\fHT\fHR\fHk\fHs\fHU\fIg\fH{\fHR\fHp\fHR\fHD\fIk\fHB\fHS\fHD\fHs\fHy\fH\\\fHH\fHR\fHy\fH\\\fHD\fHR\fHe\fHD\fHy\fIk\fHC\fHU\fHR\fHm\fHT\fH@\fHT\fIk\fHA\fHR\fH[\fHR\fHj\fHF\fHy\fIk\fH^\fHS\fHC\fIk\fHZ\fIm\fH\\\fIn\fHk\fHT\fHy\fIk\fHt\fHn\fHs\fIk\fHB\fIk\fH\\\fIl\fHT\fHy\fHH\fHR\fHB\fIk\fH\\\fHR\fH^\fIk\fHy\fH\\\fHi\fHK\fHS\fHy\fHi\fHF\fHD\fHR\fHT\fHB\fHR\fHp\fHB\fIm\fHq\fIk\fHy\fHR\fH\\\fHO\fHU\fIg\fHH\fHR\fHy\fHM\fHP\fIl\fHC\fHU\fHR\fHn\fHU\fIg\fHs\fH^\fHZ\fH@\fIa\fHJ\fH^\fHS\fHC\fHR\fHp\fIl\fHY\fHD\fHp\fHR\fHH\fHR\fHy\fId\fHT\fIk\fHj\fHF\fHy\fHR\fHY\fHR\fH^\fIl\fHJ\fIk\fHD\fIk\fHF\fIn\fH\\\fIl\fHF\fHR\fHD\fIl\fHe\fHT\fHy\fIk\fHU\fIg\fH{\fIl\fH@\fId\fHL\fHy\fHj\fHF\fHy\fIl\fHY\fH\\\fIa\fH[\fH{\fHR\fHn\fHY\fHj\fHF\fHy\fIg\fHp\fHS\fH^\fHR\fHp\fHR\fHD\fHR\fHT\fHU\fHB\fHH\fHU\fHB\fIk\fHn\fHe\fHD\fHy\fIl\fHC\fHR\fHU\fIn\fHJ\fH\\\fIa\fHp\fHT\fIn\fHv\fIl\fHF\fHT\fHn\fHJ\fHT\fHY\fHR\fH^\fHU\fIg\fHD\fHR\fHU\fIg\fHH\fIl\fHp\fId\fHT\fIk\fHY\fHR\fHF\fHT\fHp\fHD\fHH\fHR\fHD\fIk\fHH\fHR\fHp\fHR\fH\\\fIl\fHt\fHR\fHC\fH^\fHp\fHS\fH^\fIk\fHD\fIl\fHv\fIk\fHp\fHR\fHn\fHv\fHF\fHH\fIa\fH\\\fH{\fIn\fH{\fH^\fHp\fHR\fHH\fIk\fH@\fHR\fHU\fH\\\fHj\fHF\fHD\fIk\fHY\fHR\fHU\fHD\fHk\fHT\fHy\fHR\fHT\fIm\fH@\fHU\fH\\\fHU\fHD\fIk\fHk\fHT\fHT\fIk\fHT\fHU\fHS\fHH\fH@\fHM\fHP\fIk\fHt\fHs\fHD\fHR\fHH\fH^\fHR\fHZ\fHF\fHR\fHn\fHv\fHZ\fIa\fH\\\fIl\fH@\fHM\fHP\fIl\fHU\fIg\fHH\fIk\fHT\fHR\fHd\fHs\fHZ\fHR\fHC\fHJ\fHT\fHy\fHH\fIl\fHp\fHR\fHH\fIl\fHY\fHR\fH^\fHR\fHU\fHp\fHR\fH\\\fHF\fHs\fHD\fHR\fH\\\fHz\fHD\fIk\fHT\fHM\fHP\fHy\fHB\fHS\fH^\fHR\fHe\fHT\fHy\fIl\fHy\fIk\fHY\fH^\fH^\fH{\fHH\fHR\fHz\fHR\fHD\fHR\fHi\fH\\\fIa\fHI\fHp\fHU\fHR\fHn\fHJ\fIk\fHz\fHR\fHF\fHU\fH^\fIl\fHD\fHS\fHC\fHB\fH@\fHS\fHD\fHR\fH@\fId\fHn\fHy\fHy\fHU\fIl\fHn\fHy\fHU\fHD\fHR\fHJ\fIk\fHH\fHR\fHU\fHB\fH^\fIk\fHy\fHR\fHG\fIl\fHp\fH@\fHy\fHS\fHH\fIm\fH\\\fHH\fHB\fHR\fHn\fH{\fHY\fHU\fIl\fHn\fH\\\fIg\fHp\fHP\fHB\fHS\fH^\fIl\fHj\fH\\\fIg\fHF\fHT\fIk\fHD\fHR\fHC\fHR\fHJ\fHY\fH^\fIk\fHD\fIk\fHz\fHR\fHH\fHR\fHy\fH\\\fIl\fH@\fHe\fHD\fHy\fHR\fHp\fHY\fHR\fH@\fHF\fIn\fH\\\fHR\fH@\fHM\fHP\fHR\fHT\fI`\fHJ\fHR\fHZ\fIk\fHC\fH\\\fHy\fHS\fHC\fIk\fHy\fHU\fHR\fHn\fHi\fHy\fHT\fH\\\fH@\fHD\fHR\fHc\fHY\fHU\fHR\fHn\fHT\fIa\fHI\fH^\fHB\fHS\fH^\fIk\fH^\fIk\fHz\fHy\fHY\fHS\fH[\fHC\fHy\fIa\fH\\\fHn\fHT\fHB\fIn\fHU\fHI\fHR\fHD\fHR4F4_4F4[5f4U5i4X4K4]5o4E4D5d4K4_4[4E4K5h4Y5m4A4E5i5d4K4Z5f4U4K5h4B4K4Y4E4K5h5i4^5f4C4K5h4U4K5i4E4K5h5o4K4F4D4K5h4]4C5d4C4D4]5j4K5i4@4K5h4C5d5h4E4K5h4U4K5h5i4K5h5i5d5n4U4K5h4U4]4D5f4K5h4_4]5f4U4K5h4@5d4K5h4K5h4\\5k4K4D4K5h4A5f4K4E4K5h4A5n5d5n4K5h5o4]5f5i4K5h4U4]4K5n5i4A5m5d4T4E4K5h4G4K5j5f5i4X4K5k4C4E4K5h5i4]4O4E4K5h5n4]4N5j4K5h4X4D4K4D4K5h4A5d4K4]4K5h4@4C5f4C4K5h4O4_4]4E4K5h4U5h5d5i5i4@5i5d4U4E4K5h4]4A5i5j4K5h5j5n4K4[5m5h4_4[5f5j4K5h5o5d5f4F4K5h4C5j5f4K4D4]5o4K4F5k4K5h4]5f4K4Z4F4A5f4K4F5f4D4F5d5n5f4F4K5h4O5d5h5e4K5h4D4]5f4C4K5h5o5h4K5i4K5h4]4K4D4[4K5h4X4B4Y5f4_5f4K4]4K4F4K5h4G4K5h4G4K5h4Y5h4K4E4K5h4A4C5f4G4K5h4^5d4K4]4K5h4B5h5f4@4K5h4@5i5f4U4K5h4U4K5i5k4K5h4@5i4K5h4K5h4_4K4U4E5i4X4K5k4C5k4K5h4]4J5f4_4K5h4C4B5d5h4K5h5m5j5f4E4K5h5o4F4K4D4K5h4C5d4]5f4K5h4C4]5d4_4K4_4F4V4]5n4F4Y4K5i5f5i4K5h4D5j4K4F4K5h4U4T5f5ifmwfqwbjmnfmwvmgfqpwbmgjmd#>#evm`wjlm+*-isd!#tjgwk>!`lmejdvqbwjlm-smd!#tjgwk>!?algz#`obpp>!Nbwk-qbmgln+*`lmwfnslqbqz#Vmjwfg#Pwbwfp`jq`vnpwbm`fp-bssfmg@kjog+lqdbmjybwjlmp?psbm#`obpp>!!=?jnd#pq`>!,gjpwjmdvjpkfgwklvpbmgp#le#`lnnvmj`bwjlm`ofbq!=?,gju=jmufpwjdbwjlmebuj`lm-j`l!#nbqdjm.qjdkw9abpfg#lm#wkf#Nbppb`kvpfwwpwbaof#alqgfq>jmwfqmbwjlmbobopl#hmltm#bpsqlmvm`jbwjlmab`hdqlvmg9 esbggjmd.ofew9Elq#f{bnsof/#njp`foobmflvp%ow8,nbwk%dw8spz`kloldj`bojm#sbqwj`vobqfbq`k!#wzsf>!elqn#nfwklg>!bp#lsslpfg#wlPvsqfnf#@lvqwl``bpjlmbooz#Bggjwjlmbooz/Mlqwk#Bnfqj`bs{8ab`hdqlvmglsslqwvmjwjfpFmwfqwbjmnfmw-wlOltfq@bpf+nbmveb`wvqjmdsqlefppjlmbo#`lnajmfg#tjwkElq#jmpwbm`f/`lmpjpwjmd#le!#nb{ofmdwk>!qfwvqm#ebopf8`lmp`jlvpmfppNfgjwfqqbmfbmf{wqblqgjmbqzbppbppjmbwjlmpvapfrvfmwoz#avwwlm#wzsf>!wkf#mvnafq#lewkf#lqjdjmbo#`lnsqfkfmpjufqfefqp#wl#wkf?,vo=	?,gju=	skjolplskj`bool`bwjlm-kqfetbp#svaojpkfgPbm#Eqbm`jp`l+evm`wjlm+*x	?gju#jg>!nbjmplskjpwj`bwfgnbwkfnbwj`bo#,kfbg=	?algzpvddfpwp#wkbwgl`vnfmwbwjlm`lm`fmwqbwjlmqfobwjlmpkjspnbz#kbuf#affm+elq#f{bnsof/Wkjp#bqwj`of#jm#plnf#`bpfpsbqwp#le#wkf#gfejmjwjlm#leDqfbw#Aqjwbjm#`foosbggjmd>frvjubofmw#wlsob`fklogfq>!8#elmw.pjyf9#ivpwjej`bwjlmafojfufg#wkbwpveefqfg#eqlnbwwfnswfg#wl#ofbgfq#le#wkf`qjsw!#pq`>!,+evm`wjlm+*#xbqf#bubjobaof	\n?ojmh#qfo>!#pq`>$kwws9,,jmwfqfpwfg#jm`lmufmwjlmbo#!#bow>!!#,=?,bqf#dfmfqboozkbp#bopl#affmnlpw#slsvobq#`lqqfpslmgjmd`qfgjwfg#tjwkwzof>!alqgfq9?,b=?,psbm=?,-dje!#tjgwk>!?jeqbnf#pq`>!wbaof#`obpp>!jmojmf.aol`h8b``lqgjmd#wl#wldfwkfq#tjwkbssql{jnbwfozsbqojbnfmwbqznlqf#bmg#nlqfgjpsobz9mlmf8wqbgjwjlmboozsqfglnjmbmwoz%maps8%maps8%maps8?,psbm=#`foopsb`jmd>?jmsvw#mbnf>!lq!#`lmwfmw>!`lmwqlufqpjbosqlsfqwz>!ld9,{.pkl`htbuf.gfnlmpwqbwjlmpvqqlvmgfg#azMfufqwkfofpp/tbp#wkf#ejqpw`lmpjgfqbaof#Bowklvdk#wkf#`loobalqbwjlmpklvog#mlw#afsqlslqwjlm#le?psbm#pwzof>!hmltm#bp#wkf#pklqwoz#bewfqelq#jmpwbm`f/gfp`qjafg#bp#,kfbg=	?algz#pwbqwjmd#tjwkjm`qfbpjmdoz#wkf#eb`w#wkbwgjp`vppjlm#lenjggof#le#wkfbm#jmgjujgvbogjeej`vow#wl#sljmw#le#ujftklnlpf{vbojwzb``fswbm`f#le?,psbm=?,gju=nbmveb`wvqfqplqjdjm#le#wkf`lnnlmoz#vpfgjnslqwbm`f#legfmlnjmbwjlmpab`hdqlvmg9# ofmdwk#le#wkfgfwfqnjmbwjlmb#pjdmjej`bmw!#alqgfq>!3!=qfulovwjlmbqzsqjm`jsofp#lejp#`lmpjgfqfgtbp#gfufolsfgJmgl.Fvqlsfbmuvomfqbaof#wlsqlslmfmwp#lebqf#plnfwjnfp`olpfq#wl#wkfMft#Zlqh#@jwz#mbnf>!pfbq`kbwwqjavwfg#wl`lvqpf#le#wkfnbwkfnbwj`jbmaz#wkf#fmg#lebw#wkf#fmg#le!#alqgfq>!3!#wf`kmloldj`bo-qfnluf@obpp+aqbm`k#le#wkffujgfm`f#wkbw\"Xfmgje^..=	Jmpwjwvwf#le#jmwl#b#pjmdofqfpsf`wjufoz-bmg#wkfqfelqfsqlsfqwjfp#lejp#ol`bwfg#jmplnf#le#tkj`kWkfqf#jp#bopl`lmwjmvfg#wl#bssfbqbm`f#le#%bns8mgbpk8#gfp`qjafp#wkf`lmpjgfqbwjlmbvwklq#le#wkfjmgfsfmgfmwozfrvjssfg#tjwkglfp#mlw#kbuf?,b=?b#kqfe>!`lmevpfg#tjwk?ojmh#kqfe>!,bw#wkf#bdf#lebssfbq#jm#wkfWkfpf#jm`ovgfqfdbqgofpp#le`lvog#af#vpfg#pwzof>%rvlw8pfufqbo#wjnfpqfsqfpfmw#wkfalgz=	?,kwno=wklvdkw#wl#afslsvobwjlm#leslppjajojwjfpsfq`fmwbdf#leb``fpp#wl#wkfbm#bwwfnsw#wlsqlgv`wjlm#leirvfqz,irvfqzwtl#gjeefqfmwafolmd#wl#wkffpwbaojpknfmwqfsob`jmd#wkfgfp`qjswjlm!#gfwfqnjmf#wkfbubjobaof#elqB``lqgjmd#wl#tjgf#qbmdf#le\n?gju#`obpp>!nlqf#`lnnlmozlqdbmjpbwjlmpevm`wjlmbojwztbp#`lnsofwfg#%bns8ngbpk8#sbqwj`jsbwjlmwkf#`kbqb`wfqbm#bggjwjlmbobssfbqp#wl#afeb`w#wkbw#wkfbm#f{bnsof#lepjdmjej`bmwozlmnlvpflufq>!af`bvpf#wkfz#bpzm`#>#wqvf8sqlaofnp#tjwkpffnp#wl#kbufwkf#qfpvow#le#pq`>!kwws9,,ebnjojbq#tjwkslppfppjlm#leevm`wjlm#+*#xwllh#sob`f#jmbmg#plnfwjnfppvapwbmwjbooz?psbm=?,psbm=jp#lewfm#vpfgjm#bm#bwwfnswdqfbw#gfbo#leFmujqlmnfmwbopv``fppevooz#ujqwvbooz#boo13wk#`fmwvqz/sqlefppjlmbopmf`fppbqz#wl#gfwfqnjmfg#az`lnsbwjajojwzaf`bvpf#jw#jpGj`wjlmbqz#lenlgjej`bwjlmpWkf#elooltjmdnbz#qfefq#wl9@lmpfrvfmwoz/Jmwfqmbwjlmbobowklvdk#plnfwkbw#tlvog#aftlqog$p#ejqpw`obppjejfg#bpalwwln#le#wkf+sbqwj`vobqozbojdm>!ofew!#nlpw#`lnnlmozabpjp#elq#wkfelvmgbwjlm#le`lmwqjavwjlmpslsvobqjwz#le`fmwfq#le#wkfwl#qfgv`f#wkfivqjpgj`wjlmpbssql{jnbwjlm#lmnlvpflvw>!Mft#Wfpwbnfmw`loof`wjlm#le?,psbm=?,b=?,jm#wkf#Vmjwfgejon#gjqf`wlq.pwqj`w-gwg!=kbp#affm#vpfgqfwvqm#wl#wkfbowklvdk#wkjp`kbmdf#jm#wkfpfufqbo#lwkfqavw#wkfqf#bqfvmsqf`fgfmwfgjp#pjnjobq#wlfpsf`jbooz#jmtfjdkw9#alog8jp#`boofg#wkf`lnsvwbwjlmbojmgj`bwf#wkbwqfpwqj`wfg#wl\n?nfwb#mbnf>!bqf#wzsj`booz`lmeoj`w#tjwkKltfufq/#wkf#Bm#f{bnsof#le`lnsbqfg#tjwkrvbmwjwjfp#leqbwkfq#wkbm#b`lmpwfoobwjlmmf`fppbqz#elqqfslqwfg#wkbwpsf`jej`bwjlmslojwj`bo#bmg%maps8%maps8?qfefqfm`fp#wlwkf#pbnf#zfbqDlufqmnfmw#ledfmfqbwjlm#lekbuf#mlw#affmpfufqbo#zfbqp`lnnjwnfmw#wl\n\n?vo#`obpp>!ujpvbojybwjlm2:wk#`fmwvqz/sqb`wjwjlmfqpwkbw#kf#tlvogbmg#`lmwjmvfgl``vsbwjlm#lejp#gfejmfg#bp`fmwqf#le#wkfwkf#bnlvmw#le=?gju#pwzof>!frvjubofmw#legjeefqfmwjbwfaqlvdkw#balvwnbqdjm.ofew9#bvwlnbwj`boozwklvdkw#le#bpPlnf#le#wkfpf	?gju#`obpp>!jmsvw#`obpp>!qfsob`fg#tjwkjp#lmf#le#wkffgv`bwjlm#bmgjmeovfm`fg#azqfsvwbwjlm#bp	?nfwb#mbnf>!b``lnnlgbwjlm?,gju=	?,gju=obqdf#sbqw#leJmpwjwvwf#elqwkf#pl.`boofg#bdbjmpw#wkf#Jm#wkjp#`bpf/tbp#bssljmwfg`objnfg#wl#afKltfufq/#wkjpGfsbqwnfmw#lewkf#qfnbjmjmdfeef`w#lm#wkfsbqwj`vobqoz#gfbo#tjwk#wkf	?gju#pwzof>!bonlpw#botbzpbqf#`vqqfmwozf{sqfppjlm#leskjolplskz#leelq#nlqf#wkbm`jujojybwjlmplm#wkf#jpobmgpfof`wfgJmgf{`bm#qfpvow#jm!#ubovf>!!#,=wkf#pwqv`wvqf#,=?,b=?,gju=Nbmz#le#wkfpf`bvpfg#az#wkfle#wkf#Vmjwfgpsbm#`obpp>!n`bm#af#wqb`fgjp#qfobwfg#wlaf`bnf#lmf#lejp#eqfrvfmwozojujmd#jm#wkfwkflqfwj`boozElooltjmd#wkfQfulovwjlmbqzdlufqmnfmw#jmjp#gfwfqnjmfgwkf#slojwj`bojmwqlgv`fg#jmpveej`jfmw#wlgfp`qjswjlm!=pklqw#pwlqjfppfsbqbwjlm#lebp#wl#tkfwkfqhmltm#elq#jwptbp#jmjwjboozgjpsobz9aol`hjp#bm#f{bnsofwkf#sqjm`jsbo`lmpjpwp#le#bqf`ldmjyfg#bp,algz=?,kwno=b#pvapwbmwjboqf`lmpwqv`wfgkfbg#le#pwbwfqfpjpwbm`f#wlvmgfqdqbgvbwfWkfqf#bqf#wtldqbujwbwjlmbobqf#gfp`qjafgjmwfmwjlmboozpfqufg#bp#wkf`obpp>!kfbgfqlsslpjwjlm#wlevmgbnfmwboozglnjmbwfg#wkfbmg#wkf#lwkfqboojbm`f#tjwktbp#elq`fg#wlqfpsf`wjufoz/bmg#slojwj`bojm#pvsslqw#lesflsof#jm#wkf13wk#`fmwvqz-bmg#svaojpkfgolbg@kbqwafbwwl#vmgfqpwbmgnfnafq#pwbwfpfmujqlmnfmwboejqpw#kboe#le`lvmwqjfp#bmgbq`kjwf`wvqboaf#`lmpjgfqfg`kbqb`wfqjyfg`ofbqJmwfqubobvwklqjwbwjufEfgfqbwjlm#letbp#pv``ffgfgbmg#wkfqf#bqfb#`lmpfrvfm`fwkf#Sqfpjgfmwbopl#jm`ovgfgeqff#plewtbqfpv``fppjlm#legfufolsfg#wkftbp#gfpwqlzfgbtbz#eqln#wkf8	?,p`qjsw=	?bowklvdk#wkfzelooltfg#az#bnlqf#sltfqevoqfpvowfg#jm#bVmjufqpjwz#leKltfufq/#nbmzwkf#sqfpjgfmwKltfufq/#plnfjp#wklvdkw#wlvmwjo#wkf#fmgtbp#bmmlvm`fgbqf#jnslqwbmwbopl#jm`ovgfp=?jmsvw#wzsf>wkf#`fmwfq#le#GL#MLW#BOWFQvpfg#wl#qfefqwkfnfp,<plqw>wkbw#kbg#affmwkf#abpjp#elqkbp#gfufolsfgjm#wkf#pvnnfq`lnsbqbwjufozgfp`qjafg#wkfpv`k#bp#wklpfwkf#qfpvowjmdjp#jnslppjaofubqjlvp#lwkfqPlvwk#Beqj`bmkbuf#wkf#pbnffeef`wjufmfppjm#tkj`k#`bpf8#wf{w.bojdm9pwqv`wvqf#bmg8#ab`hdqlvmg9qfdbqgjmd#wkfpvsslqwfg#wkfjp#bopl#hmltmpwzof>!nbqdjmjm`ovgjmd#wkfabkbpb#Nfobzvmlqph#alhn/Iomlqph#mzmlqphpolufm)M(ajmbjmwfqmb`jlmbo`bojej`b`j/_m`lnvmj`b`j/_m`lmpwqv``j/_m!=?gju#`obpp>!gjpbnajdvbwjlmGlnbjmMbnf$/#$bgnjmjpwqbwjlmpjnvowbmflvpozwqbmpslqwbwjlmJmwfqmbwjlmbo#nbqdjm.alwwln9qfpslmpjajojwz?\"Xfmgje^..=	?,=?nfwb#mbnf>!jnsofnfmwbwjlmjmeqbpwqv`wvqfqfsqfpfmwbwjlmalqgfq.alwwln9?,kfbg=	?algz=>kwws&0B&1E&1E?elqn#nfwklg>!nfwklg>!slpw!#,ebuj`lm-j`l!#~*8	?,p`qjsw=	-pfwBwwqjavwf+Bgnjmjpwqbwjlm>#mft#Bqqbz+*8?\"Xfmgje^..=	gjpsobz9aol`h8Vmelqwvmbwfoz/!=%maps8?,gju=,ebuj`lm-j`l!=>$pwzofpkffw$#jgfmwjej`bwjlm/#elq#f{bnsof/?oj=?b#kqfe>!,bm#bowfqmbwjufbp#b#qfpvow#lesw!=?,p`qjsw=	wzsf>!pvanjw!#	+evm`wjlm+*#xqf`lnnfmgbwjlmelqn#b`wjlm>!,wqbmpelqnbwjlmqf`lmpwqv`wjlm-pwzof-gjpsobz#B``lqgjmd#wl#kjggfm!#mbnf>!bolmd#tjwk#wkfgl`vnfmw-algz-bssql{jnbwfoz#@lnnvmj`bwjlmpslpw!#b`wjlm>!nfbmjmd#%rvlw8..?\"Xfmgje^..=Sqjnf#Njmjpwfq`kbqb`wfqjpwj`?,b=#?b#`obpp>wkf#kjpwlqz#le#lmnlvpflufq>!wkf#dlufqmnfmwkqfe>!kwwsp9,,tbp#lqjdjmbooztbp#jmwqlgv`fg`obppjej`bwjlmqfsqfpfmwbwjufbqf#`lmpjgfqfg?\"Xfmgje^..=		gfsfmgp#lm#wkfVmjufqpjwz#le#jm#`lmwqbpw#wl#sob`fklogfq>!jm#wkf#`bpf#lejmwfqmbwjlmbo#`lmpwjwvwjlmbopwzof>!alqgfq.9#evm`wjlm+*#xAf`bvpf#le#wkf.pwqj`w-gwg!=	?wbaof#`obpp>!b``lnsbmjfg#azb``lvmw#le#wkf?p`qjsw#pq`>!,mbwvqf#le#wkf#wkf#sflsof#jm#jm#bggjwjlm#wlp*8#ip-jg#>#jg!#tjgwk>!233&!qfdbqgjmd#wkf#Qlnbm#@bwkloj`bm#jmgfsfmgfmwelooltjmd#wkf#-dje!#tjgwk>!2wkf#elooltjmd#gjp`qjnjmbwjlmbq`kbfloldj`bosqjnf#njmjpwfq-ip!=?,p`qjsw=`lnajmbwjlm#le#nbqdjmtjgwk>!`qfbwfFofnfmw+t-bwwb`kFufmw+?,b=?,wg=?,wq=pq`>!kwwsp9,,bJm#sbqwj`vobq/#bojdm>!ofew!#@yf`k#Qfsvaoj`Vmjwfg#Hjmdgln`lqqfpslmgfm`f`lm`ovgfg#wkbw-kwno!#wjwof>!+evm`wjlm#+*#x`lnfp#eqln#wkfbssoj`bwjlm#le?psbm#`obpp>!pafojfufg#wl#affnfmw+$p`qjsw$?,b=	?,oj=	?ojufqz#gjeefqfmw=?psbm#`obpp>!lswjlm#ubovf>!+bopl#hmltm#bp\n?oj=?b#kqfe>!=?jmsvw#mbnf>!pfsbqbwfg#eqlnqfefqqfg#wl#bp#ubojdm>!wls!=elvmgfq#le#wkfbwwfnswjmd#wl#`bqalm#gjl{jgf		?gju#`obpp>!`obpp>!pfbq`k.,algz=	?,kwno=lsslqwvmjwz#wl`lnnvmj`bwjlmp?,kfbg=	?algz#pwzof>!tjgwk9Wj\rVSmd#Uj\rWkw`kbmdfp#jm#wkfalqgfq.`lolq9 3!#alqgfq>!3!#?,psbm=?,gju=?tbp#gjp`lufqfg!#wzsf>!wf{w!#*8	?,p`qjsw=		Gfsbqwnfmw#le#f``ofpjbpwj`bowkfqf#kbp#affmqfpvowjmd#eqln?,algz=?,kwno=kbp#mfufq#affmwkf#ejqpw#wjnfjm#qfpslmpf#wlbvwlnbwj`booz#?,gju=		?gju#jtbp#`lmpjgfqfgsfq`fmw#le#wkf!#,=?,b=?,gju=`loof`wjlm#le#gfp`fmgfg#eqlnpf`wjlm#le#wkfb``fsw.`kbqpfwwl#af#`lmevpfgnfnafq#le#wkf#sbggjmd.qjdkw9wqbmpobwjlm#lejmwfqsqfwbwjlm#kqfe>$kwws9,,tkfwkfq#lq#mlwWkfqf#bqf#boplwkfqf#bqf#nbmzb#pnboo#mvnafqlwkfq#sbqwp#lejnslppjaof#wl##`obpp>!avwwlmol`bwfg#jm#wkf-#Kltfufq/#wkfbmg#fufmwvboozBw#wkf#fmg#le#af`bvpf#le#jwpqfsqfpfmwp#wkf?elqn#b`wjlm>!#nfwklg>!slpw!jw#jp#slppjaofnlqf#ojhfoz#wlbm#jm`qfbpf#jmkbuf#bopl#affm`lqqfpslmgp#wlbmmlvm`fg#wkbwbojdm>!qjdkw!=nbmz#`lvmwqjfpelq#nbmz#zfbqpfbqojfpw#hmltmaf`bvpf#jw#tbpsw!=?,p`qjsw=#ubojdm>!wls!#jmkbajwbmwp#leelooltjmd#zfbq	?gju#`obpp>!njoojlm#sflsof`lmwqlufqpjbo#`lm`fqmjmd#wkfbqdvf#wkbw#wkfdlufqmnfmw#bmgb#qfefqfm`f#wlwqbmpefqqfg#wlgfp`qjajmd#wkf#pwzof>!`lolq9bowklvdk#wkfqfafpw#hmltm#elqpvanjw!#mbnf>!nvowjsoj`bwjlmnlqf#wkbm#lmf#qf`ldmjwjlm#le@lvm`jo#le#wkffgjwjlm#le#wkf##?nfwb#mbnf>!Fmwfqwbjmnfmw#btbz#eqln#wkf#8nbqdjm.qjdkw9bw#wkf#wjnf#lejmufpwjdbwjlmp`lmmf`wfg#tjwkbmg#nbmz#lwkfqbowklvdk#jw#jpafdjmmjmd#tjwk#?psbm#`obpp>!gfp`fmgbmwp#le?psbm#`obpp>!j#bojdm>!qjdkw!?,kfbg=	?algz#bpsf`wp#le#wkfkbp#pjm`f#affmFvqlsfbm#Vmjlmqfnjmjp`fmw#lenlqf#gjeej`vowUj`f#Sqfpjgfmw`lnslpjwjlm#lesbppfg#wkqlvdknlqf#jnslqwbmwelmw.pjyf922s{f{sobmbwjlm#lewkf#`lm`fsw#letqjwwfm#jm#wkf\n?psbm#`obpp>!jp#lmf#le#wkf#qfpfnaobm`f#wllm#wkf#dqlvmgptkj`k#`lmwbjmpjm`ovgjmd#wkf#gfejmfg#az#wkfsvaoj`bwjlm#lenfbmp#wkbw#wkflvwpjgf#le#wkfpvsslqw#le#wkf?jmsvw#`obpp>!?psbm#`obpp>!w+Nbwk-qbmgln+*nlpw#sqlnjmfmwgfp`qjswjlm#le@lmpwbmwjmlsoftfqf#svaojpkfg?gju#`obpp>!pfbssfbqp#jm#wkf2!#kfjdkw>!2!#nlpw#jnslqwbmwtkj`k#jm`ovgfptkj`k#kbg#affmgfpwqv`wjlm#lewkf#slsvobwjlm	\n?gju#`obpp>!slppjajojwz#leplnfwjnfp#vpfgbssfbq#wl#kbufpv``fpp#le#wkfjmwfmgfg#wl#afsqfpfmw#jm#wkfpwzof>!`ofbq9a	?,p`qjsw=	?tbp#elvmgfg#jmjmwfqujft#tjwk\\jg!#`lmwfmw>!`bsjwbo#le#wkf	?ojmh#qfo>!pqfofbpf#le#wkfsljmw#lvw#wkbw{NOKwwsQfrvfpwbmg#pvapfrvfmwpf`lmg#obqdfpwufqz#jnslqwbmwpsf`jej`bwjlmppvqeb`f#le#wkfbssojfg#wl#wkfelqfjdm#sloj`z\\pfwGlnbjmMbnffpwbaojpkfg#jmjp#afojfufg#wlJm#bggjwjlm#wlnfbmjmd#le#wkfjp#mbnfg#bewfqwl#sqlwf`w#wkfjp#qfsqfpfmwfgGf`obqbwjlm#lenlqf#feej`jfmw@obppjej`bwjlmlwkfq#elqnp#lekf#qfwvqmfg#wl?psbm#`obpp>!`sfqelqnbm`f#le+evm`wjlm+*#xje#bmg#lmoz#jeqfdjlmp#le#wkfofbgjmd#wl#wkfqfobwjlmp#tjwkVmjwfg#Mbwjlmppwzof>!kfjdkw9lwkfq#wkbm#wkfzsf!#`lmwfmw>!Bppl`jbwjlm#le	?,kfbg=	?algzol`bwfg#lm#wkfjp#qfefqqfg#wl+jm`ovgjmd#wkf`lm`fmwqbwjlmpwkf#jmgjujgvbobnlmd#wkf#nlpwwkbm#bmz#lwkfq,=	?ojmh#qfo>!#qfwvqm#ebopf8wkf#svqslpf#lewkf#bajojwz#wl8`lolq9 eee~	-	?psbm#`obpp>!wkf#pvaif`w#legfejmjwjlmp#le=	?ojmh#qfo>!`objn#wkbw#wkfkbuf#gfufolsfg?wbaof#tjgwk>!`fofaqbwjlm#leElooltjmd#wkf#wl#gjpwjmdvjpk?psbm#`obpp>!awbhfp#sob`f#jmvmgfq#wkf#mbnfmlwfg#wkbw#wkf=?\"Xfmgje^..=	pwzof>!nbqdjm.jmpwfbg#le#wkfjmwqlgv`fg#wkfwkf#sql`fpp#lejm`qfbpjmd#wkfgjeefqfm`fp#jmfpwjnbwfg#wkbwfpsf`jbooz#wkf,gju=?gju#jg>!tbp#fufmwvboozwkqlvdklvw#kjpwkf#gjeefqfm`fplnfwkjmd#wkbwpsbm=?,psbm=?,pjdmjej`bmwoz#=?,p`qjsw=		fmujqlmnfmwbo#wl#sqfufmw#wkfkbuf#affm#vpfgfpsf`jbooz#elqvmgfqpwbmg#wkfjp#fppfmwjbooztfqf#wkf#ejqpwjp#wkf#obqdfpwkbuf#affm#nbgf!#pq`>!kwws9,,jmwfqsqfwfg#bppf`lmg#kboe#le`qloojmd>!ml!#jp#`lnslpfg#leJJ/#Kloz#Qlnbmjp#f{sf`wfg#wlkbuf#wkfjq#ltmgfejmfg#bp#wkfwqbgjwjlmbooz#kbuf#gjeefqfmwbqf#lewfm#vpfgwl#fmpvqf#wkbwbdqffnfmw#tjwk`lmwbjmjmd#wkfbqf#eqfrvfmwozjmelqnbwjlm#lmf{bnsof#jp#wkfqfpvowjmd#jm#b?,b=?,oj=?,vo=#`obpp>!ellwfqbmg#fpsf`jboozwzsf>!avwwlm!#?,psbm=?,psbm=tkj`k#jm`ovgfg=	?nfwb#mbnf>!`lmpjgfqfg#wkf`bqqjfg#lvw#azKltfufq/#jw#jpaf`bnf#sbqw#lejm#qfobwjlm#wlslsvobq#jm#wkfwkf#`bsjwbo#letbp#leej`jbooztkj`k#kbp#affmwkf#Kjpwlqz#lebowfqmbwjuf#wlgjeefqfmw#eqlnwl#pvsslqw#wkfpvddfpwfg#wkbwjm#wkf#sql`fpp##?gju#`obpp>!wkf#elvmgbwjlmaf`bvpf#le#kjp`lm`fqmfg#tjwkwkf#vmjufqpjwzlsslpfg#wl#wkfwkf#`lmwf{w#le?psbm#`obpp>!swf{w!#mbnf>!r!\n\n?gju#`obpp>!wkf#p`jfmwjej`qfsqfpfmwfg#aznbwkfnbwj`jbmpfof`wfg#az#wkfwkbw#kbuf#affm=?gju#`obpp>!`gju#jg>!kfbgfqjm#sbqwj`vobq/`lmufqwfg#jmwl*8	?,p`qjsw=	?skjolplskj`bo#pqsphlkqubwphjwj\rVSmd#Uj\rWkw<L=o=m=m<V<T<U=l=o=m=m<V<T<Ujmufpwjdb`j/_msbqwj`jsb`j/_m<V<R=n<R=l=g<Y<R<]<W<\\=m=n<T<V<R=n<R=l=g<U=k<Y<W<R<^<Y<V=m<T=m=n<Y<P=g<q<R<^<R=m=n<T<V<R=n<R=l=g=i<R<]<W<\\=m=n=`<^=l<Y<P<Y<Q<T<V<R=n<R=l<\\=c=m<Y<_<R<X<Q=c=m<V<\\=k<\\=n=`<Q<R<^<R=m=n<T<O<V=l<\\<T<Q=g<^<R<S=l<R=m=g<V<R=n<R=l<R<U=m<X<Y<W<\\=n=`<S<R<P<R=e=`=b=m=l<Y<X=m=n<^<R<]=l<\\<[<R<P=m=n<R=l<R<Q=g=o=k<\\=m=n<T<Y=n<Y=k<Y<Q<T<Y<<W<\\<^<Q<\\=c<T=m=n<R=l<T<T=m<T=m=n<Y<P<\\=l<Y=d<Y<Q<T=c<M<V<\\=k<\\=n=`<S<R=a=n<R<P=o=m<W<Y<X=o<Y=n=m<V<\\<[<\\=n=`=n<R<^<\\=l<R<^<V<R<Q<Y=k<Q<R=l<Y=d<Y<Q<T<Y<V<R=n<R=l<R<Y<R=l<_<\\<Q<R<^<V<R=n<R=l<R<P<L<Y<V<W<\\<P<\\4K5h5i5j4F4C5e5i5j4F4C5f4K4F4K5h5i5d4Z5d4U4K5h4D4]4K5i4@4K5h5i5d4K5n4U4K5h4]4_4K4J5h5i4X4K4]5o4K4F4K5h4O4U4Z4K4M4K5h4]5f4K4Z4E4K5h4F4Y5i5f5i4K5h4K4U4Z4K4M4K5h5j4F4K4J4@4K5h4O5h4U4K4D4K5h4F4_4@5f5h4K5h4O5n4_4K5i4K5h4Z4V4[4K4F4K5h5m5f4C5f5d4K5h4F4]4A5f4D4K5h4@4C5f4C4E4K5h4F4U5h5f5i4K5h4O4B4D4K4]4K5h4K5m5h4K5i4K5h4O5m5h4K5i4K5h4F4K4]5f4B4K5h4F5n5j5f4E4K5h4K5h4U4K4D4K5h4B5d4K4[4]4K5h5i4@4F5i4U4K5h4C5f5o5d4]4K5h4_5f4K4A4E4U4D4C4K5h5h5k4K5h4F4]4D5f4E4K5h4]5d4K4D4[4K5h4O4C4D5f4E4K5h4K4B4D4K4]4K5h5i4F4A4C4E4K5h4K4V4K5j5f`vqplq9sljmwfq8?,wjwof=	?nfwb#!#kqfe>!kwws9,,!=?psbm#`obpp>!nfnafqp#le#wkf#tjmglt-ol`bwjlmufqwj`bo.bojdm9,b=##?b#kqfe>!?\"gl`wzsf#kwno=nfgjb>!p`qffm!#?lswjlm#ubovf>!ebuj`lm-j`l!#,=	\n\n?gju#`obpp>!`kbqb`wfqjpwj`p!#nfwklg>!dfw!#,algz=	?,kwno=	pklqw`vw#j`lm!#gl`vnfmw-tqjwf+sbggjmd.alwwln9qfsqfpfmwbwjufppvanjw!#ubovf>!bojdm>!`fmwfq!#wkqlvdklvw#wkf#p`jfm`f#ej`wjlm	##?gju#`obpp>!pvanjw!#`obpp>!lmf#le#wkf#nlpw#ubojdm>!wls!=?tbp#fpwbaojpkfg*8	?,p`qjsw=	qfwvqm#ebopf8!=*-pwzof-gjpsobzaf`bvpf#le#wkf#gl`vnfmw-`llhjf?elqn#b`wjlm>!,~algzxnbqdjm938Fm`z`olsfgjb#leufqpjlm#le#wkf#-`qfbwfFofnfmw+mbnf!#`lmwfmw>!?,gju=	?,gju=		bgnjmjpwqbwjuf#?,algz=	?,kwno=kjpwlqz#le#wkf#!=?jmsvw#wzsf>!slqwjlm#le#wkf#bp#sbqw#le#wkf#%maps8?b#kqfe>!lwkfq#`lvmwqjfp!=	?gju#`obpp>!?,psbm=?,psbm=?Jm#lwkfq#tlqgp/gjpsobz9#aol`h8`lmwqlo#le#wkf#jmwqlgv`wjlm#le,=	?nfwb#mbnf>!bp#tfoo#bp#wkf#jm#qf`fmw#zfbqp	\n?gju#`obpp>!?,gju=	\n?,gju=	jmpsjqfg#az#wkfwkf#fmg#le#wkf#`lnsbwjaof#tjwkaf`bnf#hmltm#bp#pwzof>!nbqdjm9-ip!=?,p`qjsw=?#Jmwfqmbwjlmbo#wkfqf#kbuf#affmDfqnbm#obmdvbdf#pwzof>!`lolq9 @lnnvmjpw#Sbqwz`lmpjpwfmw#tjwkalqgfq>!3!#`foo#nbqdjmkfjdkw>!wkf#nbilqjwz#le!#bojdm>!`fmwfqqfobwfg#wl#wkf#nbmz#gjeefqfmw#Lqwklgl{#@kvq`kpjnjobq#wl#wkf#,=	?ojmh#qfo>!ptbp#lmf#le#wkf#vmwjo#kjp#gfbwk~*+*8	?,p`qjsw=lwkfq#obmdvbdfp`lnsbqfg#wl#wkfslqwjlmp#le#wkfwkf#Mfwkfqobmgpwkf#nlpw#`lnnlmab`hdqlvmg9vqo+bqdvfg#wkbw#wkfp`qloojmd>!ml!#jm`ovgfg#jm#wkfMlqwk#Bnfqj`bm#wkf#mbnf#le#wkfjmwfqsqfwbwjlmpwkf#wqbgjwjlmbogfufolsnfmw#le#eqfrvfmwoz#vpfgb#`loof`wjlm#leufqz#pjnjobq#wlpvqqlvmgjmd#wkff{bnsof#le#wkjpbojdm>!`fmwfq!=tlvog#kbuf#affmjnbdf\\`bswjlm#>bwwb`kfg#wl#wkfpvddfpwjmd#wkbwjm#wkf#elqn#le#jmuloufg#jm#wkfjp#gfqjufg#eqlnmbnfg#bewfq#wkfJmwqlgv`wjlm#wlqfpwqj`wjlmp#lm#pwzof>!tjgwk9#`bm#af#vpfg#wl#wkf#`qfbwjlm#lenlpw#jnslqwbmw#jmelqnbwjlm#bmgqfpvowfg#jm#wkf`loobspf#le#wkfWkjp#nfbmp#wkbwfofnfmwp#le#wkftbp#qfsob`fg#azbmbozpjp#le#wkfjmpsjqbwjlm#elqqfdbqgfg#bp#wkfnlpw#pv``fppevohmltm#bp#%rvlw8b#`lnsqfkfmpjufKjpwlqz#le#wkf#tfqf#`lmpjgfqfgqfwvqmfg#wl#wkfbqf#qfefqqfg#wlVmplvq`fg#jnbdf=	\n?gju#`obpp>!`lmpjpwp#le#wkfpwlsSqlsbdbwjlmjmwfqfpw#jm#wkfbubjobajojwz#lebssfbqp#wl#kbuffof`wqlnbdmfwj`fmbaofPfquj`fp+evm`wjlm#le#wkfJw#jp#jnslqwbmw?,p`qjsw=?,gju=evm`wjlm+*xubq#qfobwjuf#wl#wkfbp#b#qfpvow#le#wkf#slpjwjlm#leElq#f{bnsof/#jm#nfwklg>!slpw!#tbp#elooltfg#az%bns8ngbpk8#wkfwkf#bssoj`bwjlmip!=?,p`qjsw=	vo=?,gju=?,gju=bewfq#wkf#gfbwktjwk#qfpsf`w#wlpwzof>!sbggjmd9jp#sbqwj`vobqozgjpsobz9jmojmf8#wzsf>!pvanjw!#jp#gjujgfg#jmwl\bTA\nzk#+\vBl\bQ*qfpslmpbajojgbgbgnjmjpwqb`j/_mjmwfqmb`jlmbofp`lqqfpslmgjfmwf\fHe\fHF\fHC\fIg\fH{\fHF\fIn\fH\\\fIa\fHY\fHU\fHB\fHR\fH\\\fIk\fH^\fIg\fH{\fIg\fHn\fHv\fIm\fHD\fHR\fHY\fH^\fIk\fHy\fHS\fHD\fHT\fH\\\fHy\fHR\fH\\\fHF\fIm\fH^\fHS\fHT\fHz\fIg\fHp\fIk\fHn\fHv\fHR\fHU\fHS\fHc\fHA\fIk\fHp\fIk\fHn\fHZ\fHR\fHB\fHS\fH^\fHU\fHB\fHR\fH\\\fIl\fHp\fHR\fH{\fH\\\fHO\fH@\fHD\fHR\fHD\fIk\fHy\fIm\fHB\fHR\fH\\\fH@\fIa\fH^\fIe\fH{\fHB\fHR\fH^\fHS\fHy\fHB\fHU\fHS\fH^\fHR\fHF\fIo\fH[\fIa\fHL\fH@\fHN\fHP\fHH\fIk\fHA\fHR\fHp\fHF\fHR\fHy\fIa\fH^\fHS\fHy\fHs\fIa\fH\\\fIk\fHD\fHz\fHS\fH^\fHR\fHG\fHJ\fI`\fH\\\fHR\fHD\fHB\fHR\fHB\fH^\fIk\fHB\fHH\fHJ\fHR\fHD\fH@\fHR\fHp\fHR\fH\\\fHY\fHS\fHy\fHR\fHT\fHy\fIa\fHC\fIg\fHn\fHv\fHR\fHU\fHH\fIk\fHF\fHU\fIm\fHm\fHv\fH@\fHH\fHR\fHC\fHR\fHT\fHn\fHY\fHR\fHJ\fHJ\fIk\fHz\fHD\fIk\fHF\fHS\fHw\fH^\fIk\fHY\fHS\fHZ\fIk\fH[\fH\\\fHR\fHp\fIa\fHC\fHe\fHH\fIa\fHH\fH\\\fHB\fIm\fHn\fH@\fHd\fHJ\fIg\fHD\fIg\fHn\fHe\fHF\fHy\fH\\\fHO\fHF\fHN\fHP\fIk\fHn\fHT\fIa\fHI\fHS\fHH\fHG\fHS\fH^\fIa\fHB\fHB\fIm\fHz\fIa\fHC\fHi\fHv\fIa\fHw\fHR\fHw\fIn\fHs\fHH\fIl\fHT\fHn\fH{\fIl\fHH\fHp\fHR\fHc\fH{\fHR\fHY\fHS\fHA\fHR\fH{\fHt\fHO\fIa\fHs\fIk\fHJ\fIn\fHT\fH\\\fIk\fHJ\fHS\fHD\fIg\fHn\fHU\fHH\fIa\fHC\fHR\fHT\fIk\fHy\fIa\fHT\fH{\fHR\fHn\fHK\fIl\fHY\fHS\fHZ\fIa\fHY\fH\\\fHR\fHH\fIk\fHn\fHJ\fId\fHs\fIa\fHT\fHD\fHy\fIa\fHZ\fHR\fHT\fHR\fHB\fHD\fIk\fHi\fHJ\fHR\fH^\fHH\fH@\fHS\fHp\fH^\fIl\fHF\fIm\fH\\\fIn\fH[\fHU\fHS\fHn\fHJ\fIl\fHB\fHS\fHH\fIa\fH\\\fHy\fHY\fHS\fHH\fHR\fH\\\fIm\fHF\fHC\fIk\fHT\fIa\fHI\fHR\fHD\fHy\fH\\\fIg\fHM\fHP\fHB\fIm\fHy\fIa\fHH\fHC\fIg\fHp\fHD\fHR\fHy\fIo\fHF\fHC\fHR\fHF\fIg\fHT\fIa\fHs\fHt\fH\\\fIk\fH^\fIn\fHy\fHR\fH\\\fIa\fHC\fHY\fHS\fHv\fHR\fH\\\fHT\fIn\fHv\fHD\fHR\fHB\fIn\fH^\fIa\fHC\fHJ\fIk\fHz\fIk\fHn\fHU\fHB\fIk\fHZ\fHR\fHT\fIa\fHy\fIn\fH^\fHB\fId\fHn\fHD\fIk\fHH\fId\fHC\fHR\fH\\\fHp\fHS\fHT\fHy\fIkqpp({no!#wjwof>!.wzsf!#`lmwfmw>!wjwof!#`lmwfmw>!bw#wkf#pbnf#wjnf-ip!=?,p`qjsw=	?!#nfwklg>!slpw!#?,psbm=?,b=?,oj=ufqwj`bo.bojdm9w,irvfqz-njm-ip!=-`oj`h+evm`wjlm+#pwzof>!sbggjmd.~*+*8	?,p`qjsw=	?,psbm=?b#kqfe>!?b#kqfe>!kwws9,,*8#qfwvqm#ebopf8wf{w.gf`lqbwjlm9#p`qloojmd>!ml!#alqgfq.`loobspf9bppl`jbwfg#tjwk#Abkbpb#JmglmfpjbFmdojpk#obmdvbdf?wf{w#{no9psb`f>-dje!#alqgfq>!3!?,algz=	?,kwno=	lufqeolt9kjggfm8jnd#pq`>!kwws9,,bggFufmwOjpwfmfqqfpslmpjaof#elq#p-ip!=?,p`qjsw=	,ebuj`lm-j`l!#,=lsfqbwjmd#pzpwfn!#pwzof>!tjgwk92wbqdfw>!\\aobmh!=Pwbwf#Vmjufqpjwzwf{w.bojdm9ofew8	gl`vnfmw-tqjwf+/#jm`ovgjmd#wkf#bqlvmg#wkf#tlqog*8	?,p`qjsw=	?!#pwzof>!kfjdkw98lufqeolt9kjggfmnlqf#jmelqnbwjlmbm#jmwfqmbwjlmbob#nfnafq#le#wkf#lmf#le#wkf#ejqpw`bm#af#elvmg#jm#?,gju=	\n\n?,gju=	gjpsobz9#mlmf8!=!#,=	?ojmh#qfo>!	##+evm`wjlm+*#xwkf#26wk#`fmwvqz-sqfufmwGfebvow+obqdf#mvnafq#le#Azybmwjmf#Fnsjqf-isdwkvnaofewubpw#nbilqjwz#lenbilqjwz#le#wkf##bojdm>!`fmwfq!=Vmjufqpjwz#Sqfppglnjmbwfg#az#wkfPf`lmg#Tlqog#Tbqgjpwqjavwjlm#le#pwzof>!slpjwjlm9wkf#qfpw#le#wkf#`kbqb`wfqjyfg#az#qfo>!mleloolt!=gfqjufp#eqln#wkfqbwkfq#wkbm#wkf#b#`lnajmbwjlm#lepwzof>!tjgwk9233Fmdojpk.psfbhjmd`lnsvwfq#p`jfm`falqgfq>!3!#bow>!wkf#f{jpwfm`f#leGfnl`qbwj`#Sbqwz!#pwzof>!nbqdjm.Elq#wkjp#qfbplm/-ip!=?,p`qjsw=	\npAzWbdMbnf+p*X3^ip!=?,p`qjsw=	?-ip!=?,p`qjsw=	ojmh#qfo>!j`lm!#$#bow>$$#`obpp>$elqnbwjlm#le#wkfufqpjlmp#le#wkf#?,b=?,gju=?,gju=,sbdf=	##?sbdf=	?gju#`obpp>!`lmwaf`bnf#wkf#ejqpwabkbpb#Jmglmfpjbfmdojpk#+pjnsof*\"y\"W\"W\"[\"Q\"U\"V\"@=i=l<^<\\=n=m<V<T<V<R<P<S<\\<Q<T<T=c<^<W=c<Y=n=m=c<x<R<]<\\<^<T=n=`=k<Y<W<R<^<Y<V<\\=l<\\<[<^<T=n<T=c<t<Q=n<Y=l<Q<Y=n<r=n<^<Y=n<T=n=`<Q<\\<S=l<T<P<Y=l<T<Q=n<Y=l<Q<Y=n<V<R=n<R=l<R<_<R=m=n=l<\\<Q<T=j=g<V<\\=k<Y=m=n<^<Y=o=m<W<R<^<T=c=i<S=l<R<]<W<Y<P=g<S<R<W=o=k<T=n=`=c<^<W=c=b=n=m=c<Q<\\<T<]<R<W<Y<Y<V<R<P<S<\\<Q<T=c<^<Q<T<P<\\<Q<T<Y=m=l<Y<X=m=n<^<\\4K5h5i5d4K4Z5f4U4K5h4]4J5f4_5f4E4K5h4K5j4F5n4K5h5i4X4K4]5o4K4F5o4K5h4_5f4K4]4K4F4K5h5i5o4F5d4D4E4K5h4_4U5d4C5f4E4K4A4Y4K4J5f4K4F4K5h4U4K5h5i5f4E4K5h4Y5d4F5f4K4F4K5h4K5j4F4]5j4F4K5h4F4Y4K5i5f5i4K5h4I4_5h4K5i5f4K5h5i4X4K4]5o4E4K5h5i4]4J5f4K4Fqlalwp!#`lmwfmw>!?gju#jg>!ellwfq!=wkf#Vmjwfg#Pwbwfp?jnd#pq`>!kwws9,,-isdqjdkwwkvna-ip!=?,p`qjsw=	?ol`bwjlm-sqlwl`loeqbnfalqgfq>!3!#p!#,=	?nfwb#mbnf>!?,b=?,gju=?,gju=?elmw.tfjdkw9alog8%rvlw8#bmg#%rvlw8gfsfmgjmd#lm#wkf#nbqdjm938sbggjmd9!#qfo>!mleloolt!#Sqfpjgfmw#le#wkf#wtfmwjfwk#`fmwvqzfujpjlm=	##?,sbdfJmwfqmfw#F{solqfqb-bpzm`#>#wqvf8	jmelqnbwjlm#balvw?gju#jg>!kfbgfq!=!#b`wjlm>!kwws9,,?b#kqfe>!kwwsp9,,?gju#jg>!`lmwfmw!?,gju=	?,gju=	?gfqjufg#eqln#wkf#?jnd#pq`>$kwws9,,b``lqgjmd#wl#wkf#	?,algz=	?,kwno=	pwzof>!elmw.pjyf9p`qjsw#obmdvbdf>!Bqjbo/#Kfoufwj`b/?,b=?psbm#`obpp>!?,p`qjsw=?p`qjsw#slojwj`bo#sbqwjfpwg=?,wq=?,wbaof=?kqfe>!kwws9,,ttt-jmwfqsqfwbwjlm#leqfo>!pwzofpkffw!#gl`vnfmw-tqjwf+$?`kbqpfw>!vwe.;!=	afdjmmjmd#le#wkf#qfufbofg#wkbw#wkfwfofujpjlm#pfqjfp!#qfo>!mleloolt!=#wbqdfw>!\\aobmh!=`objnjmd#wkbw#wkfkwws&0B&1E&1Ettt-nbmjefpwbwjlmp#leSqjnf#Njmjpwfq#lejmeovfm`fg#az#wkf`obpp>!`ofbqej{!=,gju=	?,gju=		wkqff.gjnfmpjlmbo@kvq`k#le#Fmdobmgle#Mlqwk#@bqlojmbprvbqf#hjolnfwqfp-bggFufmwOjpwfmfqgjpwjm`w#eqln#wkf`lnnlmoz#hmltm#bpSklmfwj`#Boskbafwgf`obqfg#wkbw#wkf`lmwqloofg#az#wkfAfmibnjm#Eqbmhojmqlof.sobzjmd#dbnfwkf#Vmjufqpjwz#lejm#Tfpwfqm#Fvqlsfsfqplmbo#`lnsvwfqSqlif`w#Dvwfmafqdqfdbqgofpp#le#wkfkbp#affm#sqlslpfgwldfwkfq#tjwk#wkf=?,oj=?oj#`obpp>!jm#plnf#`lvmwqjfpnjm-ip!=?,p`qjsw=le#wkf#slsvobwjlmleej`jbo#obmdvbdf?jnd#pq`>!jnbdfp,jgfmwjejfg#az#wkfmbwvqbo#qfplvq`fp`obppjej`bwjlm#le`bm#af#`lmpjgfqfgrvbmwvn#nf`kbmj`pMfufqwkfofpp/#wkfnjoojlm#zfbqp#bdl?,algz=	?,kwno=\"y\"W\"W\"[\"Q\"U\"V\"@	wbhf#bgubmwbdf#lebmg/#b``lqgjmd#wlbwwqjavwfg#wl#wkfNj`qlplew#Tjmgltpwkf#ejqpw#`fmwvqzvmgfq#wkf#`lmwqlogju#`obpp>!kfbgfqpklqwoz#bewfq#wkfmlwbaof#f{`fswjlmwfmp#le#wklvpbmgppfufqbo#gjeefqfmwbqlvmg#wkf#tlqog-qfb`kjmd#njojwbqzjplobwfg#eqln#wkflsslpjwjlm#wl#wkfwkf#Log#WfpwbnfmwBeqj`bm#Bnfqj`bmpjmpfqwfg#jmwl#wkfpfsbqbwf#eqln#wkfnfwqlslojwbm#bqfbnbhfp#jw#slppjaofb`hmltofgdfg#wkbwbqdvbaoz#wkf#nlpwwzsf>!wf{w,`pp!=	wkf#JmwfqmbwjlmboB``lqgjmd#wl#wkf#sf>!wf{w,`pp!#,=	`ljm`jgf#tjwk#wkfwtl.wkjqgp#le#wkfGvqjmd#wkjp#wjnf/gvqjmd#wkf#sfqjlgbmmlvm`fg#wkbw#kfwkf#jmwfqmbwjlmbobmg#nlqf#qf`fmwozafojfufg#wkbw#wkf`lmp`jlvpmfpp#bmgelqnfqoz#hmltm#bppvqqlvmgfg#az#wkfejqpw#bssfbqfg#jml``bpjlmbooz#vpfgslpjwjlm9baplovwf8!#wbqdfw>!\\aobmh!#slpjwjlm9qfobwjuf8wf{w.bojdm9`fmwfq8ib{,ojap,irvfqz,2-ab`hdqlvmg.`lolq9 wzsf>!bssoj`bwjlm,bmdvbdf!#`lmwfmw>!?nfwb#kwws.frvju>!Sqjub`z#Sloj`z?,b=f+!&0@p`qjsw#pq`>$!#wbqdfw>!\\aobmh!=Lm#wkf#lwkfq#kbmg/-isdwkvnaqjdkw1?,gju=?gju#`obpp>!?gju#pwzof>!eolbw9mjmfwffmwk#`fmwvqz?,algz=	?,kwno=	?jnd#pq`>!kwws9,,p8wf{w.bojdm9`fmwfqelmw.tfjdkw9#alog8#B``lqgjmd#wl#wkf#gjeefqfm`f#afwtffm!#eqbnfalqgfq>!3!#!#pwzof>!slpjwjlm9ojmh#kqfe>!kwws9,,kwno7,ollpf-gwg!=	gvqjmd#wkjp#sfqjlg?,wg=?,wq=?,wbaof=`olpfoz#qfobwfg#wlelq#wkf#ejqpw#wjnf8elmw.tfjdkw9alog8jmsvw#wzsf>!wf{w!#?psbm#pwzof>!elmw.lmqfbgzpwbwf`kbmdf\n?gju#`obpp>!`ofbqgl`vnfmw-ol`bwjlm-#Elq#f{bnsof/#wkf#b#tjgf#ubqjfwz#le#?\"GL@WZSF#kwno=	?%maps8%maps8%maps8!=?b#kqfe>!kwws9,,pwzof>!eolbw9ofew8`lm`fqmfg#tjwk#wkf>kwws&0B&1E&1Ettt-jm#slsvobq#`vowvqfwzsf>!wf{w,`pp!#,=jw#jp#slppjaof#wl#Kbqubqg#Vmjufqpjwzwzofpkffw!#kqfe>!,wkf#nbjm#`kbqb`wfqL{elqg#Vmjufqpjwz##mbnf>!hfztlqgp!#`pwzof>!wf{w.bojdm9wkf#Vmjwfg#Hjmdglnefgfqbo#dlufqmnfmw?gju#pwzof>!nbqdjm#gfsfmgjmd#lm#wkf#gfp`qjswjlm#le#wkf?gju#`obpp>!kfbgfq-njm-ip!=?,p`qjsw=gfpwqv`wjlm#le#wkfpojdkwoz#gjeefqfmwjm#b``lqgbm`f#tjwkwfof`lnnvmj`bwjlmpjmgj`bwfp#wkbw#wkfpklqwoz#wkfqfbewfqfpsf`jbooz#jm#wkf#Fvqlsfbm#`lvmwqjfpKltfufq/#wkfqf#bqfpq`>!kwws9,,pwbwj`pvddfpwfg#wkbw#wkf!#pq`>!kwws9,,ttt-b#obqdf#mvnafq#le#Wfof`lnnvmj`bwjlmp!#qfo>!mleloolt!#wKloz#Qlnbm#Fnsfqlqbonlpw#f{`ovpjufoz!#alqgfq>!3!#bow>!Pf`qfwbqz#le#Pwbwf`vonjmbwjmd#jm#wkf@JB#Tlqog#Eb`wallhwkf#nlpw#jnslqwbmwbmmjufqpbqz#le#wkfpwzof>!ab`hdqlvmg.?oj=?fn=?b#kqfe>!,wkf#Bwobmwj`#L`fbmpwqj`woz#psfbhjmd/pklqwoz#afelqf#wkfgjeefqfmw#wzsfp#lewkf#Lwwlnbm#Fnsjqf=?jnd#pq`>!kwws9,,Bm#Jmwqlgv`wjlm#wl`lmpfrvfm`f#le#wkfgfsbqwvqf#eqln#wkf@lmefgfqbwf#Pwbwfpjmgjdfmlvp#sflsofpSql`ffgjmdp#le#wkfjmelqnbwjlm#lm#wkfwkflqjfp#kbuf#affmjmuloufnfmw#jm#wkfgjujgfg#jmwl#wkqffbgib`fmw#`lvmwqjfpjp#qfpslmpjaof#elqgjpplovwjlm#le#wkf`loobalqbwjlm#tjwktjgfoz#qfdbqgfg#bpkjp#`lmwfnslqbqjfpelvmgjmd#nfnafq#leGlnjmj`bm#Qfsvaoj`dfmfqbooz#b``fswfgwkf#slppjajojwz#lebqf#bopl#bubjobaofvmgfq#`lmpwqv`wjlmqfpwlqbwjlm#le#wkfwkf#dfmfqbo#svaoj`jp#bonlpw#fmwjqfozsbppfp#wkqlvdk#wkfkbp#affm#pvddfpwfg`lnsvwfq#bmg#ujgflDfqnbmj`#obmdvbdfp#b``lqgjmd#wl#wkf#gjeefqfmw#eqln#wkfpklqwoz#bewfqtbqgpkqfe>!kwwsp9,,ttt-qf`fmw#gfufolsnfmwAlbqg#le#Gjqf`wlqp?gju#`obpp>!pfbq`k#?b#kqfe>!kwws9,,Jm#sbqwj`vobq/#wkfNvowjsof#ellwmlwfplq#lwkfq#pvapwbm`fwklvpbmgp#le#zfbqpwqbmpobwjlm#le#wkf?,gju=	?,gju=		?b#kqfe>!jmgf{-skstbp#fpwbaojpkfg#jmnjm-ip!=?,p`qjsw=	sbqwj`jsbwf#jm#wkfb#pwqlmd#jmeovfm`fpwzof>!nbqdjm.wls9qfsqfpfmwfg#az#wkfdqbgvbwfg#eqln#wkfWqbgjwjlmbooz/#wkfFofnfmw+!p`qjsw!*8Kltfufq/#pjm`f#wkf,gju=	?,gju=	?gju#ofew8#nbqdjm.ofew9sqlwf`wjlm#bdbjmpw38#ufqwj`bo.bojdm9Vmelqwvmbwfoz/#wkfwzsf>!jnbdf,{.j`lm,gju=	?gju#`obpp>!#`obpp>!`ofbqej{!=?gju#`obpp>!ellwfq\n\n?,gju=	\n\n?,gju=	wkf#nlwjlm#sj`wvqf<}=f<W<_<\\=l=m<V<T<]=f<W<_<\\=l=m<V<T<H<Y<X<Y=l<\\=j<T<T<Q<Y=m<V<R<W=`<V<R=m<R<R<]=e<Y<Q<T<Y=m<R<R<]=e<Y<Q<T=c<S=l<R<_=l<\\<P<P=g<r=n<S=l<\\<^<T=n=`<]<Y=m<S<W<\\=n<Q<R<P<\\=n<Y=l<T<\\<W=g<S<R<[<^<R<W=c<Y=n<S<R=m<W<Y<X<Q<T<Y=l<\\<[<W<T=k<Q=g=i<S=l<R<X=o<V=j<T<T<S=l<R<_=l<\\<P<P<\\<S<R<W<Q<R=m=n=`=b<Q<\\=i<R<X<T=n=m=c<T<[<]=l<\\<Q<Q<R<Y<Q<\\=m<Y<W<Y<Q<T=c<T<[<P<Y<Q<Y<Q<T=c<V<\\=n<Y<_<R=l<T<T<|<W<Y<V=m<\\<Q<X=l\fHJ\fIa\fHY\fHR\fH\\\fHR\fHB\fId\fHD\fIm\fHi\fH^\fHF\fIa\fH\\\fHJ\fHR\fHD\fHA\fHR\fH\\\fHH\fIl\fHC\fHi\fHD\fIm\fHJ\fIk\fHZ\fHU\fHS\fHD\fIa\fHJ\fIl\fHk\fHn\fHM\fHS\fHC\fHR\fHJ\fHS\fH^\fIa\fH^\fIl\fHi\fHK\fHS\fHy\fHR\fH\\\fHY\fIl\fHM\fHS\fHC\fIg\fHv\fHS\fHs\fIa\fHL\fIk\fHT\fHB\fHR\fHv\fHR\fH\\\fHp\fHn\fHy\fIa\fHZ\fHD\fHJ\fIm\fHD\fHS\fHC\fHR\fHF\fIa\fH\\\fHC\fIg\fH{\fHi\fHD\fIm\fHT\fHR\fH\\\fH}\fHD\fH^\fHR\fHk\fHD\fHF\fHR\fH\\\fIa\fHs\fIl\fHZ\fH\\\fIa\fHH\fIg\fHn\fH^\fIg\fHy\fHT\fHA\fHR\fHG\fHP\fIa\fH^\fId\fHZ\fHZ\fH\\\fIa\fHH\fIk\fHn\fHF\fIa\fH\\\fHJ\fIk\fHZ\fHF\fIa\fH^\fIk\fHC\fH\\\fHy\fIk\fHn\fHJ\fIa\fH\\\fHT\fIa\fHI\fHS\fHH\fHS\fHe\fHH\fIa\fHF\fHR\fHJ\fHe\fHD\fIa\fHU\fIk\fHn\fHv\fHS\fHs\fIa\fHL\fHR\fHC\fHR\fHH\fIa\fH\\\fHR\fHp\fIa\fHC\fHR\fHJ\fHR\fHF\fIm\fH\\\fHR\fHD\fIk\fHp\fIg\fHM\fHP\fIk\fHn\fHi\fHD\fIm\fHY\fHR\fHJ\fHZ\fIa\fH\\\fIk\fHO\fIl\fHZ\fHS\fHy\fIa\fH[\fHR\fHT\fH\\\fHy\fHR\fH\\\fIl\fHT\fHn\fH{\fIa\fH\\\fHU\fHF\fH\\\fHS\fHO\fHR\fHB\fH@\fIa\fH\\\fHR\fHn\fHM\fH@\fHv\fIa\fHv\fIg\fHn\fHe\fHF\fH^\fH@\fIa\fHK\fHB\fHn\fHH\fIa\fH\\\fIl\fHT\fHn\fHF\fH\\\fIa\fHy\fHe\fHB\fIa\fHB\fIl\fHJ\fHB\fHR\fHK\fIa\fHC\fHB\fHT\fHU\fHR\fHC\fHH\fHR\fHZ\fH@\fIa\fHJ\fIg\fHn\fHB\fIl\fHM\fHS\fHC\fHR\fHj\fHd\fHF\fIl\fHc\fH^\fHB\fIg\fH@\fHR\fHk\fH^\fHT\fHn\fHz\fIa\fHC\fHR\fHj\fHF\fH\\\fIk\fHZ\fHD\fHi\fHD\fIm\fH@\fHn\fHK\fH@\fHR\fHp\fHP\fHR\fH\\\fHD\fHY\fIl\fHD\fHH\fHB\fHF\fIa\fH\\\fHB\fIm\fHz\fHF\fIa\fH\\\fHZ\fIa\fHD\fHF\fH\\\fHS\fHY\fHR\fH\\\fHD\fIm\fHy\fHT\fHR\fHD\fHT\fHB\fH\\\fIa\fHI\fHD\fHj\fHC\fIg\fHp\fHS\fHH\fHT\fIg\fHB\fHY\fHR\fH\\4K5h5i4X4K4]5o4K4F4K5h5i5j4F4C5f4K4F4K5h5o5i4D5f5d4F4]4K5h5i4X4K5k4C4K4F4U4C4C4K5h4^5d4K4]4U4C4C4K5h4]4C5d4C4K5h4I4_5h4K5i5f4E4K5h5m5d4F5d4X5d4D4K5h5i4_4K4D5n4K4F4K5h5i4U5h5d5i4K4F4K5h5i4_5h4_5h4K4F4K5h4@4]4K5m5f5o4_4K5h4K4_5h4K5i5f4E4K5h4K4F4Y4K5h4K4Fhfztlqgp!#`lmwfmw>!t0-lqd,2:::,{kwno!=?b#wbqdfw>!\\aobmh!#wf{w,kwno8#`kbqpfw>!#wbqdfw>!\\aobmh!=?wbaof#`foosbggjmd>!bvwl`lnsofwf>!lee!#wf{w.bojdm9#`fmwfq8wl#obpw#ufqpjlm#az#ab`hdqlvmg.`lolq9# !#kqfe>!kwws9,,ttt-,gju=?,gju=?gju#jg>?b#kqfe>! !#`obpp>!!=?jnd#pq`>!kwws9,,`qjsw!#pq`>!kwws9,,	?p`qjsw#obmdvbdf>!,,FM!#!kwws9,,ttt-tfm`lgfVQJ@lnslmfmw+!#kqfe>!ibubp`qjsw9?gju#`obpp>!`lmwfmwgl`vnfmw-tqjwf+$?p`slpjwjlm9#baplovwf8p`qjsw#pq`>!kwws9,,#pwzof>!nbqdjm.wls9-njm-ip!=?,p`qjsw=	?,gju=	?gju#`obpp>!t0-lqd,2:::,{kwno!#		?,algz=	?,kwno=gjpwjm`wjlm#afwtffm,!#wbqdfw>!\\aobmh!=?ojmh#kqfe>!kwws9,,fm`lgjmd>!vwe.;!<=	t-bggFufmwOjpwfmfq<b`wjlm>!kwws9,,ttt-j`lm!#kqfe>!kwws9,,#pwzof>!ab`hdqlvmg9wzsf>!wf{w,`pp!#,=	nfwb#sqlsfqwz>!ld9w?jmsvw#wzsf>!wf{w!##pwzof>!wf{w.bojdm9wkf#gfufolsnfmw#le#wzofpkffw!#wzsf>!wfkwno8#`kbqpfw>vwe.;jp#`lmpjgfqfg#wl#afwbaof#tjgwk>!233&!#Jm#bggjwjlm#wl#wkf#`lmwqjavwfg#wl#wkf#gjeefqfm`fp#afwtffmgfufolsnfmw#le#wkf#Jw#jp#jnslqwbmw#wl#?,p`qjsw=		?p`qjsw##pwzof>!elmw.pjyf92=?,psbm=?psbm#jg>daOjaqbqz#le#@lmdqfpp?jnd#pq`>!kwws9,,jnFmdojpk#wqbmpobwjlmB`bgfnz#le#P`jfm`fpgju#pwzof>!gjpsobz9`lmpwqv`wjlm#le#wkf-dfwFofnfmwAzJg+jg*jm#`lmivm`wjlm#tjwkFofnfmw+$p`qjsw$*8#?nfwb#sqlsfqwz>!ld9<}=f<W<_<\\=l=m<V<T	#wzsf>!wf{w!#mbnf>!=Sqjub`z#Sloj`z?,b=bgnjmjpwfqfg#az#wkffmbaofPjmdofQfrvfpwpwzof>%rvlw8nbqdjm9?,gju=?,gju=?,gju=?=?jnd#pq`>!kwws9,,j#pwzof>%rvlw8eolbw9qfefqqfg#wl#bp#wkf#wlwbo#slsvobwjlm#lejm#Tbpkjmdwlm/#G-@-#pwzof>!ab`hdqlvmg.bnlmd#lwkfq#wkjmdp/lqdbmjybwjlm#le#wkfsbqwj`jsbwfg#jm#wkfwkf#jmwqlgv`wjlm#lejgfmwjejfg#tjwk#wkfej`wjlmbo#`kbqb`wfq#L{elqg#Vmjufqpjwz#njpvmgfqpwbmgjmd#leWkfqf#bqf/#kltfufq/pwzofpkffw!#kqfe>!,@lovnajb#Vmjufqpjwzf{sbmgfg#wl#jm`ovgfvpvbooz#qfefqqfg#wljmgj`bwjmd#wkbw#wkfkbuf#pvddfpwfg#wkbwbeejojbwfg#tjwk#wkf`lqqfobwjlm#afwtffmmvnafq#le#gjeefqfmw=?,wg=?,wq=?,wbaof=Qfsvaoj`#le#Jqfobmg	?,p`qjsw=	?p`qjsw#vmgfq#wkf#jmeovfm`f`lmwqjavwjlm#wl#wkfLeej`jbo#tfapjwf#lekfbgrvbqwfqp#le#wkf`fmwfqfg#bqlvmg#wkfjnsoj`bwjlmp#le#wkfkbuf#affm#gfufolsfgEfgfqbo#Qfsvaoj`#leaf`bnf#jm`qfbpjmdoz`lmwjmvbwjlm#le#wkfMlwf/#kltfufq/#wkbwpjnjobq#wl#wkbw#le#`bsbajojwjfp#le#wkfb``lqgbm`f#tjwk#wkfsbqwj`jsbmwp#jm#wkfevqwkfq#gfufolsnfmwvmgfq#wkf#gjqf`wjlmjp#lewfm#`lmpjgfqfgkjp#zlvmdfq#aqlwkfq?,wg=?,wq=?,wbaof=?b#kwws.frvju>![.VB.skzpj`bo#sqlsfqwjfple#Aqjwjpk#@lovnajbkbp#affm#`qjwj`jyfg+tjwk#wkf#f{`fswjlmrvfpwjlmp#balvw#wkfsbppjmd#wkqlvdk#wkf3!#`foosbggjmd>!3!#wklvpbmgp#le#sflsofqfgjqf`wp#kfqf-#Elqkbuf#`kjogqfm#vmgfq&0F&0@,p`qjsw&0F!**8?b#kqfe>!kwws9,,ttt-?oj=?b#kqfe>!kwws9,,pjwf\\mbnf!#`lmwfmw>!wf{w.gf`lqbwjlm9mlmfpwzof>!gjpsobz9#mlmf?nfwb#kwws.frvju>![.mft#Gbwf+*-dfwWjnf+*#wzsf>!jnbdf,{.j`lm!?,psbm=?psbm#`obpp>!obmdvbdf>!ibubp`qjswtjmglt-ol`bwjlm-kqfe?b#kqfe>!ibubp`qjsw9..=	?p`qjsw#wzsf>!w?b#kqfe>$kwws9,,ttt-klqw`vw#j`lm!#kqfe>!?,gju=	?gju#`obpp>!?p`qjsw#pq`>!kwws9,,!#qfo>!pwzofpkffw!#w?,gju=	?p`qjsw#wzsf>,b=#?b#kqfe>!kwws9,,#booltWqbmpsbqfm`z>![.VB.@lnsbwjaof!#`lmqfobwjlmpkjs#afwtffm	?,p`qjsw=	?p`qjsw#?,b=?,oj=?,vo=?,gju=bppl`jbwfg#tjwk#wkf#sqldqbnnjmd#obmdvbdf?,b=?b#kqfe>!kwws9,,?,b=?,oj=?oj#`obpp>!elqn#b`wjlm>!kwws9,,?gju#pwzof>!gjpsobz9wzsf>!wf{w!#mbnf>!r!?wbaof#tjgwk>!233&!#ab`hdqlvmg.slpjwjlm9!#alqgfq>!3!#tjgwk>!qfo>!pklqw`vw#j`lm!#k5=?vo=?oj=?b#kqfe>!##?nfwb#kwws.frvju>!`pp!#nfgjb>!p`qffm!#qfpslmpjaof#elq#wkf#!#wzsf>!bssoj`bwjlm,!#pwzof>!ab`hdqlvmg.kwno8#`kbqpfw>vwe.;!#booltwqbmpsbqfm`z>!pwzofpkffw!#wzsf>!wf	?nfwb#kwws.frvju>!=?,psbm=?psbm#`obpp>!3!#`foopsb`jmd>!3!=8	?,p`qjsw=	?p`qjsw#plnfwjnfp#`boofg#wkfglfp#mlw#mf`fppbqjozElq#nlqf#jmelqnbwjlmbw#wkf#afdjmmjmd#le#?\"GL@WZSF#kwno=?kwnosbqwj`vobqoz#jm#wkf#wzsf>!kjggfm!#mbnf>!ibubp`qjsw9uljg+3*8!feef`wjufmfpp#le#wkf#bvwl`lnsofwf>!lee!#dfmfqbooz#`lmpjgfqfg=?jmsvw#wzsf>!wf{w!#!=?,p`qjsw=	?p`qjswwkqlvdklvw#wkf#tlqog`lnnlm#njp`lm`fswjlmbppl`jbwjlm#tjwk#wkf?,gju=	?,gju=	?gju#`gvqjmd#kjp#ojefwjnf/`lqqfpslmgjmd#wl#wkfwzsf>!jnbdf,{.j`lm!#bm#jm`qfbpjmd#mvnafqgjsolnbwj`#qfobwjlmpbqf#lewfm#`lmpjgfqfgnfwb#`kbqpfw>!vwe.;!#?jmsvw#wzsf>!wf{w!#f{bnsofp#jm`ovgf#wkf!=?jnd#pq`>!kwws9,,jsbqwj`jsbwjlm#jm#wkfwkf#fpwbaojpknfmw#le	?,gju=	?gju#`obpp>!%bns8maps8%bns8maps8wl#gfwfqnjmf#tkfwkfqrvjwf#gjeefqfmw#eqlnnbqhfg#wkf#afdjmmjmdgjpwbm`f#afwtffm#wkf`lmwqjavwjlmp#wl#wkf`lmeoj`w#afwtffm#wkftjgfoz#`lmpjgfqfg#wltbp#lmf#le#wkf#ejqpwtjwk#ubqzjmd#gfdqffpkbuf#psf`vobwfg#wkbw+gl`vnfmw-dfwFofnfmwsbqwj`jsbwjmd#jm#wkflqjdjmbooz#gfufolsfgfwb#`kbqpfw>!vwe.;!=#wzsf>!wf{w,`pp!#,=	jmwfq`kbmdfbaoz#tjwknlqf#`olpfoz#qfobwfgpl`jbo#bmg#slojwj`bowkbw#tlvog#lwkfqtjpfsfqsfmgj`vobq#wl#wkfpwzof#wzsf>!wf{w,`ppwzsf>!pvanjw!#mbnf>!ebnjojfp#qfpjgjmd#jmgfufolsjmd#`lvmwqjfp`lnsvwfq#sqldqbnnjmdf`lmlnj`#gfufolsnfmwgfwfqnjmbwjlm#le#wkfelq#nlqf#jmelqnbwjlmlm#pfufqbo#l``bpjlmpslqwvdv/Fp#+Fvqlsfv*<O<V=l<\\={<Q=m=`<V<\\=o<V=l<\\={<Q=m=`<V<\\<L<R=m=m<T<U=m<V<R<U<P<\\=n<Y=l<T<\\<W<R<^<T<Q=h<R=l<P<\\=j<T<T=o<S=l<\\<^<W<Y<Q<T=c<Q<Y<R<]=i<R<X<T<P<R<T<Q=h<R=l<P<\\=j<T=c<t<Q=h<R=l<P<\\=j<T=c<L<Y=m<S=o<]<W<T<V<T<V<R<W<T=k<Y=m=n<^<R<T<Q=h<R=l<P<\\=j<T=b=n<Y=l=l<T=n<R=l<T<T<X<R=m=n<\\=n<R=k<Q<R4K5h5i4F5d4K4@4C5d5j4K5h4K4X4F4]4K5o4K4F4K5h4K5n4F4]4K4A4K4Fkwno8#`kbqpfw>VWE.;!#pfwWjnflvw+evm`wjlm+*gjpsobz9jmojmf.aol`h8?jmsvw#wzsf>!pvanjw!#wzsf#>#$wf{w,ibubp`qj?jnd#pq`>!kwws9,,ttt-!#!kwws9,,ttt-t0-lqd,pklqw`vw#j`lm!#kqfe>!!#bvwl`lnsofwf>!lee!#?,b=?,gju=?gju#`obpp>?,b=?,oj=	?oj#`obpp>!`pp!#wzsf>!wf{w,`pp!#?elqn#b`wjlm>!kwws9,,{w,`pp!#kqfe>!kwws9,,ojmh#qfo>!bowfqmbwf!#	?p`qjsw#wzsf>!wf{w,#lm`oj`h>!ibubp`qjsw9+mft#Gbwf*-dfwWjnf+*~kfjdkw>!2!#tjgwk>!2!#Sflsof$p#Qfsvaoj`#le##?b#kqfe>!kwws9,,ttt-wf{w.gf`lqbwjlm9vmgfqwkf#afdjmmjmd#le#wkf#?,gju=	?,gju=	?,gju=	fpwbaojpknfmw#le#wkf#?,gju=?,gju=?,gju=?,g ujftslqwxnjm.kfjdkw9	?p`qjsw#pq`>!kwws9,,lswjlm=?lswjlm#ubovf>lewfm#qfefqqfg#wl#bp#,lswjlm=	?lswjlm#ubov?\"GL@WZSF#kwno=	?\"..XJmwfqmbwjlmbo#Bjqslqw=	?b#kqfe>!kwws9,,ttt?,b=?b#kqfe>!kwws9,,t\fTL\fT^\fTE\fT^\fUh\fT{\fTN\roI\ro|\roL\ro{\roO\rov\rot\nAOGx\bTA\nzk#+\vUmGx*\fHD\fHS\fH\\\fIa\fHJ\fIk\fHZ\fHM\fHR\fHe\fHD\fH^\fIg\fHM\fHy\fIa\fH[\fIk\fHH\fIa\fH\\\fHp\fHR\fHD\fHy\fHR\fH\\\fIl\fHT\fHn\fH@\fHn\fHK\fHS\fHH\fHT\fIa\fHI\fHR\fHF\fHD\fHR\fHT\fIa\fHY\fIl\fHy\fHR\fH\\\fHT\fHn\fHT\fIa\fHy\fH\\\fHO\fHT\fHR\fHB\fH{\fIa\fH\\\fIl\fHv\fHS\fHs\fIa\fHL\fIg\fHn\fHY\fHS\fHp\fIa\fHr\fHR\fHD\fHi\fHB\fIk\fH\\\fHS\fHy\fHR\fHY\fHS\fHA\fHS\fHD\fIa\fHD\fH{\fHR\fHM\fHS\fHC\fHR\fHm\fHy\fIa\fHC\fIg\fHn\fHy\fHS\fHT\fIm\fH\\\fHy\fIa\fH[\fHR\fHF\fHU\fIm\fHm\fHv\fHH\fIl\fHF\fIa\fH\\\fH@\fHn\fHK\fHD\fHs\fHS\fHF\fIa\fHF\fHO\fIl\fHy\fIa\fH\\\fHS\fHy\fIk\fHs\fHF\fIa\fH\\\fHR\fH\\\fHn\fHA\fHF\fIa\fH\\\fHR\fHF\fIa\fHH\fHB\fHR\fH^\fHS\fHy\fIg\fHn\fH\\\fHG\fHP\fIa\fHH\fHR\fH\\\fHD\fHS\fH\\\fIa\fHB\fHR\fHO\fH^\fHS\fHB\fHS\fHs\fIk\fHMgfp`qjswjlm!#`lmwfmw>!gl`vnfmw-ol`bwjlm-sqlw-dfwFofnfmwpAzWbdMbnf+?\"GL@WZSF#kwno=	?kwno#?nfwb#`kbqpfw>!vwe.;!=9vqo!#`lmwfmw>!kwws9,,-`pp!#qfo>!pwzofpkffw!pwzof#wzsf>!wf{w,`pp!=wzsf>!wf{w,`pp!#kqfe>!t0-lqd,2:::,{kwno!#{nowzsf>!wf{w,ibubp`qjsw!#nfwklg>!dfw!#b`wjlm>!ojmh#qfo>!pwzofpkffw!##>#gl`vnfmw-dfwFofnfmwwzsf>!jnbdf,{.j`lm!#,=`foosbggjmd>!3!#`foops-`pp!#wzsf>!wf{w,`pp!#?,b=?,oj=?oj=?b#kqfe>!!#tjgwk>!2!#kfjdkw>!2!!=?b#kqfe>!kwws9,,ttt-pwzof>!gjpsobz9mlmf8!=bowfqmbwf!#wzsf>!bssoj.,,T0@,,GWG#[KWNO#2-3#foopsb`jmd>!3!#`foosbg#wzsf>!kjggfm!#ubovf>!,b=%maps8?psbm#qlof>!p	?jmsvw#wzsf>!kjggfm!#obmdvbdf>!IbubP`qjsw!##gl`vnfmw-dfwFofnfmwpAd>!3!#`foopsb`jmd>!3!#zsf>!wf{w,`pp!#nfgjb>!wzsf>$wf{w,ibubp`qjsw$tjwk#wkf#f{`fswjlm#le#zsf>!wf{w,`pp!#qfo>!pw#kfjdkw>!2!#tjgwk>!2!#>$(fm`lgfVQJ@lnslmfmw+?ojmh#qfo>!bowfqmbwf!#	algz/#wq/#jmsvw/#wf{wnfwb#mbnf>!qlalwp!#`lmnfwklg>!slpw!#b`wjlm>!=	?b#kqfe>!kwws9,,ttt-`pp!#qfo>!pwzofpkffw!#?,gju=?,gju=?gju#`obppobmdvbdf>!ibubp`qjsw!=bqjb.kjggfm>!wqvf!=.[?qjsw!#wzsf>!wf{w,ibubpo>38~*+*8	+evm`wjlm+*xab`hdqlvmg.jnbdf9#vqo+,b=?,oj=?oj=?b#kqfe>!k\n\n?oj=?b#kqfe>!kwws9,,bwlq!#bqjb.kjggfm>!wqv=#?b#kqfe>!kwws9,,ttt-obmdvbdf>!ibubp`qjsw!#,lswjlm=	?lswjlm#ubovf,gju=?,gju=?gju#`obpp>qbwlq!#bqjb.kjggfm>!wqf>+mft#Gbwf*-dfwWjnf+*slqwvdv/Fp#+gl#Aqbpjo*<R=l<_<\\<Q<T<[<\\=j<T<T<^<R<[<P<R<Z<Q<R=m=n=`<R<]=l<\\<[<R<^<\\<Q<T=c=l<Y<_<T=m=n=l<\\=j<T<T<^<R<[<P<R<Z<Q<R=m=n<T<R<]=c<[<\\=n<Y<W=`<Q<\\?\"GL@WZSF#kwno#SVAOJ@#!mw.Wzsf!#`lmwfmw>!wf{w,?nfwb#kwws.frvju>!@lmwfqbmpjwjlmbo,,FM!#!kwws9?kwno#{nomp>!kwws9,,ttt.,,T0@,,GWG#[KWNO#2-3#WGWG,{kwno2.wqbmpjwjlmbo,,ttt-t0-lqd,WQ,{kwno2,sf#>#$wf{w,ibubp`qjsw$8?nfwb#mbnf>!gfp`qjswjlmsbqfmwMlgf-jmpfqwAfelqf?jmsvw#wzsf>!kjggfm!#mbip!#wzsf>!wf{w,ibubp`qj+gl`vnfmw*-qfbgz+evm`wjp`qjsw#wzsf>!wf{w,ibubpjnbdf!#`lmwfmw>!kwws9,,VB.@lnsbwjaof!#`lmwfmw>wno8#`kbqpfw>vwe.;!#,=	ojmh#qfo>!pklqw`vw#j`lm?ojmh#qfo>!pwzofpkffw!#?,p`qjsw=	?p`qjsw#wzsf>>#gl`vnfmw-`qfbwfFofnfm?b#wbqdfw>!\\aobmh!#kqfe>#gl`vnfmw-dfwFofnfmwpAjmsvw#wzsf>!wf{w!#mbnf>b-wzsf#>#$wf{w,ibubp`qjmsvw#wzsf>!kjggfm!#mbnfkwno8#`kbqpfw>vwe.;!#,=gwg!=	?kwno#{nomp>!kwws.,,T0@,,GWG#KWNO#7-32#WfmwpAzWbdMbnf+$p`qjsw$*jmsvw#wzsf>!kjggfm!#mbn?p`qjsw#wzsf>!wf{w,ibubp!#pwzof>!gjpsobz9mlmf8!=gl`vnfmw-dfwFofnfmwAzJg+>gl`vnfmw-`qfbwfFofnfmw+$#wzsf>$wf{w,ibubp`qjsw$jmsvw#wzsf>!wf{w!#mbnf>!g-dfwFofnfmwpAzWbdMbnf+pmj`bo!#kqfe>!kwws9,,ttt-@,,GWG#KWNO#7-32#Wqbmpjw?pwzof#wzsf>!wf{w,`pp!=		?pwzof#wzsf>!wf{w,`pp!=jlmbo-gwg!=	?kwno#{nomp>kwws.frvju>!@lmwfmw.Wzsfgjmd>!3!#`foopsb`jmd>!3!kwno8#`kbqpfw>vwe.;!#,=	#pwzof>!gjpsobz9mlmf8!=??oj=?b#kqfe>!kwws9,,ttt-#wzsf>$wf{w,ibubp`qjsw$=<X<Y=c=n<Y<W=`<Q<R=m=n<T=m<R<R=n<^<Y=n=m=n<^<T<T<S=l<R<T<[<^<R<X=m=n<^<\\<]<Y<[<R<S<\\=m<Q<R=m=n<T\fHF\fIm\fHT\fIa\fHH\fHS\fHy\fHR\fHy\fHR\fHn\fH{\fIa\fH\\\fIk\fHT\fHe\fHD\fIa\fHU\fIg\fHn\fHD\fIk\fHY\fHS\fHK\fHR\fHD\fHT\fHA\fHR\fHG\fHS\fHy\fIa\fHT\fHS\fHn\fH{\fHT\fIm\fH\\\fHy\fIa\fH[\fHS\fHH\fHy\fIe\fHF\fIl\fH\\\fHR\fHk\fHs\fHY\fHS\fHp\fIa\fHr\fHR\fHF\fHD\fHy\fHR\fH\\\fIa\fH\\\fHY\fHR\fHd\fHT\fHy\fIa\fH\\\fHS\fHC\fHH\fHR", "۷%ƌ'T%'W%×%O%g%¦&Ɠ%ǥ&>&*&'&^&Ÿా&ƭ&ƒ&)&^&%&'&&P&1&±&3&]&m&u&E&t&C&Ï&V&V&/&>&6&ྲྀ᝼o&p&@&E&M&P&x&@&F&e&Ì&7&:&(&D&0&C&)&.&F&-&1&(&L&F&1ɞ*Ϫ⇳&፲&K&;&)&E&H&P&0&?&9&V&&-&v&a&,&E&)&?&=&'&'&B&മ&ԃ&̖*&*8&%&%&&&%,)&&>&&7&]&F&2&>&J&6&n&2&%&?&&2&6&J&g&-&0&,&*&J&*&O&)&6&(&<&B&N&.&P&@&2&.&W&M&%Լ(,(<&,&Ϛ&ᣇ&-&,(%&(&%&(Ļ0&X&D&&j&'&J&(&.&B&3&Z&R&h&3&E&E&<Æ-͠ỳ&%8?&@&,&Z&@&0&J&,&^&x&_&6&C&6&Cܬ⨥&f&-&-&-&-&,&J&2&8&z&8&C&Y&8&-&d&ṸÌ-&7&1&F&7&t&W&7&I&.&.&^&=ྜ᧓&8(>&/&/&ݻ')'ၥ')'%@/&0&%оী*&*@&CԽהɴ׫4෗ܚӑ6඄&/Ÿ̃Z&*%ɆϿ&Ĵ&1¨ҴŴ", dictionarySizeBits, "AAAAKKLLKKKKKJJIHHIHHGGFF");
	setData(dictionaryData, dictionarySizeBits);
}
var InputStream = class {
	data = new Int8Array(0);
	offset = 0;
	constructor(data$1) {
		this.data = data$1;
	}
};
function readInput(s, dst, offset, length) {
	if (s.input === null) return -1;
	const src = s.input;
	const end = Math.min(src.offset + length, src.data.length);
	const bytesRead = end - src.offset;
	dst.set(src.data.subarray(src.offset, end), offset);
	src.offset += bytesRead;
	return bytesRead;
}
function closeInput(s) {
	s.input = new InputStream(new Int8Array(0));
}
function toUsAsciiBytes(src) {
	const n = src.length;
	const result = new Int8Array(n);
	for (let i = 0; i < n; ++i) result[i] = src.charCodeAt(i);
	return result;
}
function toUtf8Runes(src) {
	const n = src.length;
	const result = new Int32Array(n);
	for (let i = 0; i < n; ++i) result[i] = src.charCodeAt(i);
	return result;
}
function makeError(s, code) {
	if (code >= 0) return code;
	if (s.runningState >= 0) s.runningState = code;
	throw new Error("Brotli error code: " + code);
}
/**
* Decodes brotli stream.
*/
function brotliDecode(bytes) {
	const s = new State();
	s.input = new InputStream(bytes);
	initState(s);
	let totalOutput = 0;
	const chunks = [];
	while (true) {
		const chunk = new Int8Array(16384);
		chunks.push(chunk);
		s.output = chunk;
		s.outputOffset = 0;
		s.outputLength = 16384;
		s.outputUsed = 0;
		decompress(s);
		totalOutput += s.outputUsed;
		if (s.outputUsed < 16384) break;
	}
	close(s);
	closeInput(s);
	const result = new Int8Array(totalOutput);
	let offset = 0;
	for (let i = 0; i < chunks.length; ++i) {
		const chunk = chunks[i];
		const len = Math.min(totalOutput, offset + 16384) - offset;
		if (len < 16384) result.set(chunk.subarray(0, len), offset);
		else result.set(chunk, offset);
		offset += len;
	}
	return result;
}

//#endregion
//#region src/glyph/WOFF2Glyph.ts
/**
* Represents a TrueType glyph in the WOFF2 format, which compresses glyphs differently.
*/
var WOFF2Glyph = class extends TTFGlyph {
	type = "WOFF2";
	_decode() {
		return this._font._transformedGlyphs[this.id];
	}
	_getCBox() {
		return this.path.bbox;
	}
};

//#endregion
//#region src/tables/WOFF2Directory.ts
const Base128 = { decode(stream) {
	let result = 0;
	for (let i = 0; i < 5; i++) {
		const code = stream.readUInt8();
		if (result & 3758096384) throw new Error("Overflow");
		result = result << 7 | code & 127;
		if ((code & 128) === 0) return result;
	}
	throw new Error("Bad base 128 number");
} };
const knownTags = [
	"cmap",
	"head",
	"hhea",
	"hmtx",
	"maxp",
	"name",
	"OS/2",
	"post",
	"cvt ",
	"fpgm",
	"glyf",
	"loca",
	"prep",
	"CFF ",
	"VORG",
	"EBDT",
	"EBLC",
	"gasp",
	"hdmx",
	"kern",
	"LTSH",
	"PCLT",
	"VDMX",
	"vhea",
	"vmtx",
	"BASE",
	"GDEF",
	"GPOS",
	"GSUB",
	"EBSC",
	"JSTF",
	"MATH",
	"CBDT",
	"CBLC",
	"COLR",
	"CPAL",
	"SVG ",
	"sbix",
	"acnt",
	"avar",
	"bdat",
	"bloc",
	"bsln",
	"cvar",
	"fdsc",
	"feat",
	"fmtx",
	"fvar",
	"gvar",
	"hsty",
	"just",
	"lcar",
	"mort",
	"morx",
	"opbd",
	"prop",
	"trak",
	"Zapf",
	"Silf",
	"Glat",
	"Gloc",
	"Feat",
	"Sill"
];
const WOFF2DirectoryEntry = new Struct({
	flags: uint8,
	customTag: new Optional(new StringT(4), (t) => (t.flags & 63) === 63),
	tag: (t) => t.customTag || knownTags[t.flags & 63],
	length: Base128,
	transformVersion: (t) => t.flags >>> 6 & 3,
	transformed: (t) => t.tag === "glyf" || t.tag === "loca" ? t.transformVersion === 0 : t.transformVersion !== 0,
	transformLength: new Optional(Base128, (t) => t.transformed)
});
const WOFF2Directory = new Struct({
	tag: new StringT(4),
	flavor: uint32,
	length: uint32,
	numTables: uint16,
	reserved: new Reserved(uint16),
	totalSfntSize: uint32,
	totalCompressedSize: uint32,
	majorVersion: uint16,
	minorVersion: uint16,
	metaOffset: uint32,
	metaLength: uint32,
	metaOrigLength: uint32,
	privOffset: uint32,
	privLength: uint32,
	tables: new ArrayT(WOFF2DirectoryEntry, "numTables")
});
WOFF2Directory.process = function() {
	this.tables = Object.fromEntries(this.tables.map((table) => [table.tag, table]));
};
var WOFF2Directory_default = WOFF2Directory;

//#endregion
//#region src/WOFF2Font.ts
/**
* Subclass of TTFFont that represents a TTF/OTF font compressed by WOFF2
* See spec here: http://www.w3.org/TR/WOFF2/
*/
var WOFF2Font = class extends TTFFont {
	type = "WOFF2";
	_decompressed = false;
	static probe(buffer) {
		return asciiDecoder.decode(buffer.slice(0, 4)) === "wOF2";
	}
	_decodeDirectory() {
		const directory = WOFF2Directory_default.decode(this.stream);
		this._dataPos = this.stream.pos;
		return directory;
	}
	#decompress() {
		if (!this._decompressed) {
			this.stream.pos = this._dataPos;
			const buffer = this.stream.readBuffer(this.directory.totalCompressedSize);
			let decompressedSize = 0;
			for (const tag in this.directory.tables) {
				const entry = this.directory.tables[tag];
				entry.offset = decompressedSize;
				decompressedSize += entry.transformLength != null ? entry.transformLength : entry.length;
			}
			const decompressed = brotliDecode(new Int8Array(buffer));
			if (!decompressed) throw new Error("Error decoding compressed data in WOFF2");
			this.stream = new DecodeStream(decompressed);
			this._decompressed = true;
		}
	}
	_decodeTable(table) {
		this.#decompress();
		return super._decodeTable(table);
	}
	_getBaseGlyph(glyph, characters = []) {
		if (!this._glyphs[glyph]) if (this.directory.tables.glyf?.transformed) {
			if (!this._transformedGlyphs) this._transformGlyfTable();
			return this._glyphs[glyph] = new WOFF2Glyph(glyph, characters, this);
		} else return super._getBaseGlyph(glyph, characters);
		else return this._glyphs[glyph];
	}
	_transformGlyfTable() {
		this.#decompress();
		this.stream.pos = this.directory.tables.glyf.offset;
		const table = GlyfTable.decode(this.stream);
		const glyphs = [];
		for (let index = 0; index < table.numGlyphs; index++) {
			const nContours = table.nContours.readInt16BE();
			const glyph = { numberOfContours: nContours };
			if (nContours > 0) {
				const nPoints = [];
				let totalPoints = 0;
				for (let i = 0; i < nContours; i++) {
					totalPoints += read255UInt16(table.nPoints);
					nPoints.push(totalPoints);
				}
				glyph.points = decodeTriplet(table.flags, table.glyphs, totalPoints);
				for (let i = 0; i < nContours; i++) glyph.points[nPoints[i] - 1].endContour = true;
				read255UInt16(table.glyphs);
			} else if (nContours < 0) {
				if (TTFGlyph.prototype._decodeComposite.call({ _font: this }, glyph, table.composites)) read255UInt16(table.glyphs);
			}
			glyphs.push(glyph);
		}
		this._transformedGlyphs = glyphs;
	}
};
var Substream = class {
	#buf;
	constructor(length) {
		this.length = length;
		this.#buf = new BufferT(length);
	}
	decode(stream, parent) {
		return new DecodeStream(this.#buf.decode(stream, parent));
	}
};
const GlyfTable = new Struct({
	version: uint32,
	numGlyphs: uint16,
	indexFormat: uint16,
	nContourStreamSize: uint32,
	nPointsStreamSize: uint32,
	flagStreamSize: uint32,
	glyphStreamSize: uint32,
	compositeStreamSize: uint32,
	bboxStreamSize: uint32,
	instructionStreamSize: uint32,
	nContours: new Substream("nContourStreamSize"),
	nPoints: new Substream("nPointsStreamSize"),
	flags: new Substream("flagStreamSize"),
	glyphs: new Substream("glyphStreamSize"),
	composites: new Substream("compositeStreamSize"),
	bboxes: new Substream("bboxStreamSize"),
	instructions: new Substream("instructionStreamSize")
});
const WORD_CODE = 253;
const ONE_MORE_BYTE_CODE2 = 254;
const ONE_MORE_BYTE_CODE1 = 255;
const LOWEST_U_CODE = 253;
function read255UInt16(stream) {
	const code = stream.readUInt8();
	switch (code) {
		case WORD_CODE: return stream.readUInt16BE();
		case ONE_MORE_BYTE_CODE1: return stream.readUInt8() + LOWEST_U_CODE;
		case ONE_MORE_BYTE_CODE2: return stream.readUInt8() + LOWEST_U_CODE * 2;
		default: return code;
	}
}
function withSign(flag, baseval) {
	return flag & 1 ? baseval : -baseval;
}
function decodeTriplet(flags, glyphs, nPoints) {
	let y;
	let x = y = 0;
	const res = [];
	for (let i = 0; i < nPoints; i++) {
		let dx = 0, dy = 0;
		let flag = flags.readUInt8();
		const onCurve = !(flag >> 7);
		flag &= 127;
		if (flag < 10) {
			dx = 0;
			dy = withSign(flag, ((flag & 14) << 7) + glyphs.readUInt8());
		} else if (flag < 20) {
			dx = withSign(flag, ((flag - 10 & 14) << 7) + glyphs.readUInt8());
			dy = 0;
		} else if (flag < 84) {
			var b0 = flag - 20;
			var b1 = glyphs.readUInt8();
			dx = withSign(flag, 1 + (b0 & 48) + (b1 >> 4));
			dy = withSign(flag >> 1, 1 + ((b0 & 12) << 2) + (b1 & 15));
		} else if (flag < 120) {
			var b0 = flag - 84;
			dx = withSign(flag, 1 + (b0 / 12 << 8) + glyphs.readUInt8());
			dy = withSign(flag >> 1, 1 + (b0 % 12 >> 2 << 8) + glyphs.readUInt8());
		} else if (flag < 124) {
			var b1 = glyphs.readUInt8();
			let b2 = glyphs.readUInt8();
			dx = withSign(flag, (b1 << 4) + (b2 >> 4));
			dy = withSign(flag >> 1, ((b2 & 15) << 8) + glyphs.readUInt8());
		} else {
			dx = withSign(flag, glyphs.readUInt16BE());
			dy = withSign(flag >> 1, glyphs.readUInt16BE());
		}
		x += dx;
		y += dy;
		res.push(new Point(onCurve, false, x, y));
	}
	return res;
}

//#endregion
//#region src/TrueTypeCollection.ts
const TTCHeader = new VersionedStruct(uint32, {
	65536: {
		numFonts: uint32,
		offsets: new ArrayT(uint32, "numFonts")
	},
	131072: {
		numFonts: uint32,
		offsets: new ArrayT(uint32, "numFonts"),
		dsigTag: uint32,
		dsigLength: uint32,
		dsigOffset: uint32
	}
});
var TrueTypeCollection = class {
	type = "TTC";
	isCollection = true;
	static probe(buffer) {
		return asciiDecoder.decode(buffer.slice(0, 4)) === "ttcf";
	}
	constructor(stream) {
		this.stream = stream;
		if (stream.readString(4) !== "ttcf") throw new Error("Not a TrueType collection");
		this.header = TTCHeader.decode(stream);
	}
	getFont(name) {
		for (const offset of this.header.offsets) {
			const stream = new DecodeStream(this.stream.buffer);
			stream.pos = offset;
			const font = new TTFFont(stream);
			if (font.postscriptName === name || font.postscriptName instanceof Uint8Array && name instanceof Uint8Array && font.postscriptName.every((v, i) => name[i] === v)) return font;
		}
		return null;
	}
	get fonts() {
		return this.header.offsets.map((offset) => {
			const stream = new DecodeStream(this.stream.buffer);
			stream.pos = offset;
			return new TTFFont(stream);
		});
	}
};

//#endregion
//#region src/DFont.ts
const DFontName = new StringT(uint8);
const Ref = new Struct({
	id: uint16,
	nameOffset: int16,
	attr: uint8,
	dataOffset: uint24,
	handle: uint32
});
const Type = new Struct({
	name: new StringT(4),
	maxTypeIndex: uint16,
	refList: new Pointer(uint16, new ArrayT(Ref, (t) => t.maxTypeIndex + 1), { type: "parent" })
});
const TypeList = new Struct({
	length: uint16,
	types: new ArrayT(Type, (t) => t.length + 1)
});
const DFontMap = new Struct({
	reserved: new Reserved(uint8, 24),
	typeList: new Pointer(uint16, TypeList),
	nameListOffset: new Pointer(uint16, "void")
});
const DFontHeader = new Struct({
	dataOffset: uint32,
	map: new Pointer(uint32, DFontMap),
	dataLength: uint32,
	mapLength: uint32
});
var DFont = class {
	type = "DFont";
	isCollection = true;
	static probe(buffer) {
		const stream = new DecodeStream(buffer);
		let header;
		try {
			header = DFontHeader.decode(stream);
		} catch {
			return false;
		}
		for (const type of header.map.typeList.types) if (type.name === "sfnt") return true;
		return false;
	}
	constructor(stream) {
		this.stream = stream;
		this.header = DFontHeader.decode(this.stream);
		for (const type of this.header.map.typeList.types) {
			for (const ref of type.refList) if (ref.nameOffset >= 0) {
				this.stream.pos = ref.nameOffset + this.header.map.nameListOffset;
				ref.name = DFontName.decode(this.stream);
			} else ref.name = null;
			if (type.name === "sfnt") this.sfnt = type;
		}
	}
	getFont(name) {
		if (!this.sfnt) return null;
		for (const ref of this.sfnt.refList) {
			const pos = this.header.dataOffset + ref.dataOffset + 4;
			const font = new TTFFont(new DecodeStream(this.stream.buffer.slice(pos)));
			if (font.postscriptName === name || font.postscriptName instanceof Uint8Array && name instanceof Uint8Array && font.postscriptName.every((v, i) => name[i] === v)) return font;
		}
		return null;
	}
	get fonts() {
		return this.sfnt.refList.map((ref) => {
			const pos = this.header.dataOffset + ref.dataOffset + 4;
			return new TTFFont(new DecodeStream(this.stream.buffer.slice(pos)));
		});
	}
};

//#endregion
//#region src/index.ts
const formats = [
	TTFFont,
	WOFFFont,
	WOFF2Font,
	TrueTypeCollection,
	DFont
];
/**
* Returns a font object for the given buffer.
* For collection fonts (such as TrueType collection files), you can pass a postscriptName to get
* that font out of the collection instead of a collection object.
* @param buffer `Buffer` containing font data
* @param postscriptName Optional PostScript name of font to extract from collection file.
*/
function create(buffer, postscriptName) {
	for (const format of formats) if (format.probe(buffer)) {
		const font = new format(new DecodeStream(buffer));
		if (postscriptName) return font.getFont(postscriptName);
		return font;
	}
	throw new Error("Unknown font format");
}

//#endregion
export { create };