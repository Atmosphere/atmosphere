import type { AstroConfig } from '../../types/public/config.js';
import type { ImageMetadata } from '../types.js';
export declare function makeSvgComponent(meta: ImageMetadata, contents: Buffer | string, svgoConfig: AstroConfig['experimental']['svgo']): string;
