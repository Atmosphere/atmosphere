//#region src/types.d.ts
type StyleName = 'reset' | 'bold' | 'dim' | 'italic' | 'underline' | 'inverse' | 'hidden' | 'strikethrough' | 'black' | 'red' | 'green' | 'yellow' | 'blue' | 'magenta' | 'cyan' | 'white' | 'gray' | 'bgBlack' | 'bgRed' | 'bgGreen' | 'bgYellow' | 'bgBlue' | 'bgMagenta' | 'bgCyan' | 'bgWhite' | 'blackBright' | 'redBright' | 'greenBright' | 'yellowBright' | 'blueBright' | 'magentaBright' | 'cyanBright' | 'whiteBright' | 'bgBlackBright' | 'bgRedBright' | 'bgGreenBright' | 'bgYellowBright' | 'bgBlueBright' | 'bgMagentaBright' | 'bgCyanBright' | 'bgWhiteBright';
type Input = string | number | null | undefined | boolean;
type ColorAPI = Record<StyleName, (text?: Input) => string> & {
  isColorSupported: boolean;
};
//#endregion
//#region src/index.d.ts
declare let createColors: (enabled?: boolean) => ColorAPI;
declare const _default: ColorAPI;
//#endregion
export { createColors, _default as default };