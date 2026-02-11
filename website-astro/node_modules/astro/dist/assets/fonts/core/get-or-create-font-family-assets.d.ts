import type { Logger } from '../../../core/logger/core.js';
import type { FontFamilyAssetsByUniqueKey, ResolvedFontFamily } from '../types.js';
export declare function getOrCreateFontFamilyAssets({ fontFamilyAssetsByUniqueKey, logger, bold, family, }: {
    fontFamilyAssetsByUniqueKey: FontFamilyAssetsByUniqueKey;
    logger: Logger;
    bold: (input: string) => string;
    family: ResolvedFontFamily;
}): import("../types.js").FontFamilyAssets;
