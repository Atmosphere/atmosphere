import type { ResizeOptions, SharpOptions } from 'sharp';
import { type LocalImageService } from './service.js';
export interface SharpImageServiceConfig {
    /**
     * The `limitInputPixels` option passed to Sharp. See https://sharp.pixelplumbing.com/api-constructor for more information
     */
    limitInputPixels?: SharpOptions['limitInputPixels'];
    /**
     * The `kernel` option is passed to resize calls. See https://sharp.pixelplumbing.com/api-resize/ for more information
     */
    kernel?: ResizeOptions['kernel'];
}
declare const sharpService: LocalImageService<SharpImageServiceConfig>;
export default sharpService;
