import { type AdobeProviderOptions, type GoogleFamilyOptions, type GoogleiconsFamilyOptions } from 'unifont';
import type { FontProvider } from '../types.js';
import { type LocalFamilyOptions } from './local.js';
/** [Adobe](https://fonts.adobe.com/) */
declare function adobe(config: AdobeProviderOptions): FontProvider;
/** [Bunny](https://fonts.bunny.net/) */
declare function bunny(): FontProvider;
/** [Fontshare](https://www.fontshare.com/) */
declare function fontshare(): FontProvider;
/** [Fontsource](https://fontsource.org/) */
declare function fontsource(): FontProvider;
/** [Google](https://fonts.google.com/) */
declare function google(): FontProvider<GoogleFamilyOptions | undefined>;
/** [Google Icons](https://fonts.google.com/icons) */
declare function googleicons(): FontProvider<GoogleiconsFamilyOptions | undefined>;
/** A provider that handles local files. */
declare function local(): FontProvider<LocalFamilyOptions>;
/**
 * Astro exports a few built-in providers:
 * - [Adobe](https://fonts.adobe.com/)
 * - [Bunny](https://fonts.bunny.net/)
 * - [Fontshare](https://www.fontshare.com/)
 * - [Fontsource](https://fontsource.org/)
 * - [Google](https://fonts.google.com/)
 * - [Google Icons](https://fonts.google.com/icons)
 * - Local
 */
export declare const fontProviders: {
    adobe: typeof adobe;
    bunny: typeof bunny;
    fontshare: typeof fontshare;
    fontsource: typeof fontsource;
    google: typeof google;
    googleicons: typeof googleicons;
    local: typeof local;
};
export {};
