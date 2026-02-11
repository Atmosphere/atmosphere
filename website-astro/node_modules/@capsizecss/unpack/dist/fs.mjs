import { t as _fromBuffer } from "./shared-KjM_oZR2.mjs";
import { readFile } from "node:fs/promises";

//#region src/fs.ts
const fromFile = async (path, options) => {
	return _fromBuffer(await readFile(path), "fromFile", "path", options);
};

//#endregion
export { fromFile };