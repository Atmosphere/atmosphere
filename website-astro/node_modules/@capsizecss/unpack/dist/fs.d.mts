import { n as Options } from "./shared-CnZ3qQtb.mjs";

//#region src/fs.d.ts
declare const fromFile: (path: string, options?: Options) => Promise<{
  familyName: string;
  fullName: string;
  postscriptName: string;
  capHeight: number;
  ascent: number;
  descent: number;
  lineGap: number;
  unitsPerEm: number;
  xHeight: number;
  xWidthAvg: number;
  subsets: Record<"latin" | "thai", {
    xWidthAvg: number;
  }>;
}>;
//#endregion
export { fromFile };