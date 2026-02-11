import type { TransformOptions } from '@astrojs/compiler';
import { type ResolvedConfig } from 'vite';
import type { AstroConfig } from '../../types/public/config.js';
import type { CompileCssResult } from './types.js';
export type PartialCompileCssResult = Pick<CompileCssResult, 'isGlobal' | 'dependencies'>;
export declare function createStylePreprocessor({ filename, viteConfig, astroConfig, cssPartialCompileResults, cssTransformErrors, }: {
    filename: string;
    viteConfig: ResolvedConfig;
    astroConfig: AstroConfig;
    cssPartialCompileResults: Partial<CompileCssResult>[];
    cssTransformErrors: Error[];
}): TransformOptions['preprocessStyle'];
