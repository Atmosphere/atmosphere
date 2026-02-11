//#region src/types.d.ts
/**
* There are several different types of font objects that are returned by fontkit depending on the font format.
* They all inherit from the TTFFont class and have the same public API.
*/
interface Font {
  type: "TTF" | "WOFF" | "WOFF2";
  isCollection: false;
  postscriptName: string;
  fullName: string;
  familyName: string;
  subfamilyName: string;
  copyright: string;
  version: string;
  /** the size of the font’s internal coordinate grid */
  unitsPerEm: number;
  /** the font’s ascender */
  ascent: number;
  /** the font’s descender */
  descent: number;
  /** the amount of space that should be included between lines */
  lineGap: number;
  /** the offset from the normal underline position that should be used */
  underlinePosition: number;
  /** the weight of the underline that should be used */
  underlineThickness: number;
  /** if this is an italic font, the angle the cursor should be drawn at to match the font design */
  italicAngle: number;
  /** the height of capital letters above the baseline */
  capHeight: number;
  /** the height of lower case letters */
  xHeight: number;
  /** the font’s bounding box, i.e. the box that encloses all glyphs in the font */
  bbox: BBOX;
  /** the font metric table consisting of a set of metrics and other data required for OpenType fonts */
  "OS/2": Os2Table;
  /** the font's horizontal header table consisting of information needed to layout fonts with horizontal characters    */
  hhea: HHEA;
  /** the number of glyphs in the font */
  numGlyphs: number;
  /** an array of all of the unicode code points supported by the font */
  characterSet: number[];
  /** An object describing the available axes in a variable font. Keys are 4 letter axis tags. */
  variationAxes: Partial<Record<string, {
    name: string;
    min: number;
    default: number;
    max: number;
  }>>;
  /**
  * Maps a single unicode code point to a Glyph object.
  * Does not perform any advanced substitutions (there is no context to do so).
  */
  glyphForCodePoint(codePoint: number): Glyph;
  /**
  * Returns whether there is glyph in the font for the given unicode code point.
  */
  hasGlyphForCodePoint(codePoint: number): boolean;
  /**
  * Returns an array of Glyph objects for the given string.
  * This is only a one-to-one mapping from characters to glyphs.
  * For most uses, you should use font.layout (described below), which
  * provides a much more advanced mapping supporting AAT and OpenType shaping.
  */
  glyphsForString(string: string): Glyph[];
  /**
  * Returns a glyph object for the given glyph id. You can pass the array of
  * code points this glyph represents for your use later, and it will be
  * stored in the glyph object.
  */
  getGlyph(glyphId: number, codePoints?: number[]): Glyph;
  /**
  * Returns an object describing the named variation instances
  * that the font designer has specified. Keys are variation names
  * and values are the variation settings for this instance.
  */
  namedVariations: Record<string, Record<string, number>>;
  /**
  * Returns a new font with the given variation settings applied.
  * Settings can either be an instance name, or an object containing
  * variation tags as specified by the `variationAxes` property.
  */
  getVariation(settings: object | string): Font;
  getFont(name: string): Font;
  /**
  * Gets a string from the font's `name` table
  */
  getName(key: string): string | null;
}
/**
* Glyph objects represent a glyph in the font. They have various properties for accessing metrics and
* the actual vector path the glyph represents.
*
* You do not create glyph objects directly. They are created by various methods on the font object.
* There are several subclasses of the base Glyph class internally that may be returned depending
* on the font format, but they all inherit from this class.
*/
interface Glyph {
  /** the glyph id in the font */
  id: number;
  /**
  * An array of unicode code points that are represented by this glyph.
  * There can be multiple code points in the case of ligatures and other glyphs
  * that represent multiple visual characters.
  */
  codePoints: number[];
  /** a vector Path object representing the glyph */
  path: Path;
  /** the glyph’s bounding box, i.e. the rectangle that encloses the glyph outline as tightly as possible. */
  bbox: BBOX;
  /**
  * The glyph’s control box.
  * This is often the same as the bounding box, but is faster to compute.
  * Because of the way bezier curves are defined, some of the control points
  * can be outside of the bounding box. Where `bbox` takes this into account,
  * `cbox` does not. Thus, cbox is less accurate, but faster to compute.
  * See [here](http://www.freetype.org/freetype2/docs/glyphs/glyphs-6.html#section-2)
  * for a more detailed description.
  */
  cbox: BBOX;
  /** the glyph’s advance width */
  advanceWidth: number;
  /** the glyph’s advance height */
  advanceHeight: number;
  /** is a mark glyph (non-spacing combining glyph) */
  isMark: boolean;
  /** is a ligature glyph (multiple character, spacing glyph) */
  isLigature: boolean;
  /**  The glyph's name. Commonly the character, or 'space' or UTF**** */
  name: string;
}
/**
* Path objects are returned by glyphs and represent the actual
* vector outlines for each glyph in the font.
*/
interface Path {
  commands: PathCommand[];
  /**
  * Gets the exact bounding box of the path by evaluating curve segments.
  * Slower to compute than the control box, but more accurate.
  */
  bbox: BBOX;
  /**
  * Gets the "control box" of a path.
  * This is like the bounding box, but it includes all points including
  * control points of bezier segments and is much faster to compute than
  * the real bounding box.
  */
  cbox: BBOX;
  /** Converts the path to an SVG path data string */
  toSVG(): string;
  /** Transforms the path by the given matrix */
  transform(m0: number, m1: number, m2: number, m3: number, m4: number, m5: number): Path;
  /** Translates the path by the given offset */
  translate(x: number, y: number): Path;
  /** Rotates the path by the given angle (in radians) */
  rotate(angle: number): Path;
  /** Scales the path */
  scale(scaleX: number, scaleY?: number): Path;
}
interface PathCommand {
  command: "moveTo" | "lineTo" | "quadraticCurveTo" | "bezierCurveTo" | "closePath";
  args: number[];
}
interface BBOX {
  /** The minimum X position in the bounding box */
  minX: number;
  /** The minimum Y position in the bounding box */
  minY: number;
  /** The maximum X position in the bounding box */
  maxX: number;
  /** The maximum Y position in the bounding box */
  maxY: number;
  /** The width of the bounding box */
  width: number;
  /** The height of the bounding box */
  height: number;
}
interface Os2Table {
  breakChar: number;
  capHeight: number;
  codePageRange: number[];
  defaultChar: number;
  fsSelection: {
    italic: boolean;
    negative: boolean;
    outlined: boolean;
    strikeout: boolean;
    underscore: boolean;
    useTypoMetrics: boolean;
    wws: boolean;
    bold: boolean;
    regular: boolean;
    oblique: boolean;
  };
  fsType: {
    bitmapOnly: boolean;
    editable: boolean;
    noEmbedding: boolean;
    noSubsetting: boolean;
    viewOnly: boolean;
  };
  maxContent: number;
  panose: number[];
  sFamilyClass: number;
  typoAscender: number;
  typoDescender: number;
  typoLineGap: number;
  ulCharRange: number[];
  usFirstCharIndex: number;
  usLastCharIndex: number;
  usWeightClass: number;
  usWidthClass: number;
  vendorID: string;
  version: number;
  winAscent: number;
  winDescent: number;
  xAvgCharWidth: number;
  xHeight: number;
  yStrikeoutPosition: number;
  yStrikeoutSize: number;
  ySubscriptXOffset: number;
  ySubscriptXSize: number;
  ySubscriptYOffset: number;
  ySubscriptYSize: number;
  ySuperscriptXOffset: number;
  ySuperscriptXSize: number;
  ySuperscriptYOffset: number;
  ySuperscriptYSize: number;
}
interface HHEA {
  version: number;
  ascent: number;
  descent: number;
  lineGap: number;
  advanceWidthMax: number;
  minLeftSideBearing: number;
  minRightSideBearing: number;
  xMaxExtent: number;
  caretSlopeRise: number;
  caretSlopeRun: number;
  caretOffset: number;
  metricDataFormat: number;
  numberOfMetrics: number;
}
interface FontCollection {
  type: "TTC" | "DFont";
  isCollection: true;
  getFont(name: string): Font | null;
  fonts: Font[];
}
//#endregion
//#region src/index.d.ts
/**
* Returns a font object for the given buffer.
* For collection fonts (such as TrueType collection files), you can pass a postscriptName to get
* that font out of the collection instead of a collection object.
* @param buffer `Buffer` containing font data
* @param postscriptName Optional PostScript name of font to extract from collection file.
*/
declare function create(buffer: Buffer, postscriptName?: string): Font | FontCollection;
//#endregion
export { BBOX, Font, FontCollection, Glyph, HHEA, Os2Table, Path, PathCommand, create };