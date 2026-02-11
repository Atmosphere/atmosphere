# unifont

[![npm version][npm-version-src]][npm-version-href]
[![npm downloads][npm-downloads-src]][npm-downloads-href]
[![Github Actions][github-actions-src]][github-actions-href]
[![Codecov][codecov-src]][codecov-href]

> Framework agnostic tools for accessing data from font CDNs and providers.

## Installation

Using npm:

```
npm i unifont
```

Using pnpm:

```
pnpm add unifont
```

Using yarn:

```
yarn add unifont
```

## Getting started

This package is ESM-only.

```js
import { createUnifont, providers } from 'unifont'

const unifont = await createUnifont([
  providers.google(),
])

const availableFonts = await unifont.listFonts()
const { fonts } = await unifont.resolveFont('Poppins')
```

## Built-in providers

The following providers are built-in but you can build [custom providers](#building-your-own-provider) too.

### Adobe

A provider for [Adobe Fonts](https://fonts.adobe.com/).

```js
import { providers } from 'unifont'

providers.adobe({ /* options */ })
```

#### Options

##### `id`

- Type: `string | string[]`
- Required

```js
import { providers } from 'unifont'

providers.adobe({ id: 'your-id' })
providers.adobe({ id: ['foo', 'bar'] })
```

It is recommended to load these IDs as environment variables.

### Bunny

A provider for [Bunny Fonts](https://fonts.bunny.net/).

```js
import { providers } from 'unifont'

providers.bunny()
```

### Fontshare

A provider for [Fontshare](https://www.fontshare.com/).

```js
import { providers } from 'unifont'

providers.fontshare()
```

### Fontsource

A provider for [Fontsource](https://fontsource.org/).

```js
import { providers } from 'unifont'

providers.fontsource()
```

It uses the API, not installed NPM packages (see [PR #189](https://github.com/unjs/unifont/pull/189)).

### Google

A provider for [Google Fonts](https://fonts.google.com/).

```js
import { providers } from 'unifont'

providers.google()
```

#### Options

##### `experimental.variableAxis`

- Type: `{ [fontFamily: string]: Partial<Record<VariableAxis, ([string, string] | string)[]>> }`

Allows setting variable axis configuration on a per-font basis:

```js
import { providers } from 'unifont'

providers.google({
  experimental: {
    variableAxis: {
      Poppins: {
        slnt: [['-15', '0']],
        CASL: [['0', '1']],
        CRSV: ['1'],
        MONO: [['0', '1']],
      },
    },
  },
})
```

Overriden by the `experimental.variableAxis` family option.

##### `experimental.glyphs`

- Type: `{ [fontFamily: string]: string[] }`

Allows specifying a list of glyphs to be included in the font for each font family. This can reduce the size of the font file:

```js
import { providers } from 'unifont'

providers.google({
  experimental: {
    glyphs: {
      Poppins: ['Hello', 'World']
    },
  },
})
```

Overriden by the `experimental.glyphs` family option.

#### Family options

##### `experimental.variableAxis`

- Type: `Partial<Record<VariableAxis, ([string, string] | string)[]>>`

Allows setting variable axis configuration on a per-font basis:

```js
import { createUnifont, providers } from 'unifont'

const unifont = await createUnifont([
  providers.google(),
])

const { fonts } = await unifont.resolveFont('Poppins', {
  options: {
    google: {
      experimental: {
        variableAxis: {
          slnt: [['-15', '0']],
          CASL: [['0', '1']],
          CRSV: ['1'],
          MONO: [['0', '1']],
        },
      },
    },
  },
})
```

##### `experimental.glyphs`

- Type: `string[]`

Allows specifying a list of glyphs to be included in the font for each font family. This can reduce the size of the font file:

```js
import { createUnifont, providers } from 'unifont'

const unifont = await createUnifont([
  providers.google(),
])

const { fonts } = await unifont.resolveFont('Poppins', {
  options: {
    google: {
      experimental: {
        glyphs: ['Hello', 'World'],
      },
    },
  },
})
```

### Google icons

A provider for [Google Icons](https://fonts.google.com/icons).

```js
import { providers } from 'unifont'

providers.googleicons()
```

#### Options

##### `experimental.glyphs`

- Type: `{ [fontFamily: string]: string[] }`

Allows specifying a list of glyphs to be included in the font for each font family. This can reduce the size of the font file:

```js
import { providers } from 'unifont'

providers.googleicons({
  experimental: {
    glyphs: {
      'Material Symbols Outlined': ['arrow_right', 'favorite', 'arrow_drop_down']
    },
  },
})
```

Only available when resolving the new `Material Symbols` icons. Overriden by the `experimental.glyphs` family option.

#### Family options

##### `experimental.glyphs`

- Type: `string[]`

Allows specifying a list of glyphs to be included in the font for each font family. This can reduce the size of the font file:

```js
import { createUnifont, providers } from 'unifont'

const unifont = await createUnifont([
  providers.googleicons(),
])

const { fonts } = await unifont.resolveFont('Poppins', {
  options: {
    googleicons: {
      experimental: {
        'Material Symbols Outlined': ['arrow_right', 'favorite', 'arrow_drop_down']
      },
    },
  },
})
```

Only available when resolving the new `Material Symbols` icons.

## `Unifont`

Use `createUnifont()` to create a `Unifont` instance. It requires an array of font providers at this first parameter:

```js
import { createUnifont, providers } from 'unifont'

const unifont = await createUnifont([
  providers.google(),
])
```

### Options

`createUnifont()` accepts options as its 2nd parameter.

#### `storage`

- `Type`: `Storage`

Allows caching the results of font APIs to avoid unnecessary hits to them. Uses a memory cache by default.

This storage type is compatible with [`unstorage`](https://unstorage.unjs.io.):

```ts
import { createUnifont, providers } from 'unifont'
import { createStorage } from 'unstorage'
import fsDriver from 'unstorage/drivers/fs-lite'

const storage = createStorage({
  driver: fsDriver({ base: 'node_modules/.cache/unifont' }),
})

const unifont = await createUnifont([
  providers.google()
], { storage })

// cached data is stored in `node_modules/.cache/unifont`
await unifont.resolveFont('Poppins')
```

#### `throwOnError`

- Type: `boolean`

Allows throwing on error if a font provider:

- Fails to initialize
- Fails while calling `resolveFont()`
- Fails while calling `listFonts()`

If set to `false` (default), an error will be logged to the console instead:

```ts
import { createUnifont, providers } from 'unifont'

const unifont = await createUnifont([
  providers.google()
], { throwOnError: true })
```

### Methods

#### `resolveFont()`

- Type: `(fontFamily: string, options?: Partial<ResolveFontOptions>, providers?: T[]) => Promise<ResolveFontResult & { provider?: T }>`

Retrieves font face data from available providers:

```js
import { createUnifont, providers } from 'unifont'

const unifont = await createUnifont([
  providers.google(),
  providers.fontsource(),
])

const { fonts } = await unifont.resolveFont('Poppins')
```

It loops through all providers and returns the result of the first provider that can return some data.

##### Options

It accepts options as the 2nd parameter. Each provider chooses to support them or not.

###### `weights`

- Type: `string[]`
- Default: `['400']`

Specifies what weights to retrieve. Variable weights must me in the format `<min> <max>`:

```js
import { createUnifont, providers } from 'unifont'

const unifont = await createUnifont([
  providers.google(),
])

const { fonts } = await unifont.resolveFont('Poppins', {
  weights: ['300', '500 900']
})
```

###### `styles`

- Type: `('normal' | 'italic' | 'oblique')[]`
- Default: `['normal', 'italic']`

Specifies what styles to retrieve:

```js
import { createUnifont, providers } from 'unifont'

const unifont = await createUnifont([
  providers.google(),
])

const { fonts } = await unifont.resolveFont('Poppins', {
  styles: ['normal']
})
```

###### `subsets`

- Type: `string[]`
- Default: `['cyrillic-ext', 'cyrillic', 'greek-ext', 'greek', 'vietnamese', 'latin-ext', 'latin']`

Specifies what subsets to retrieve:

```js
import { createUnifont, providers } from 'unifont'

const unifont = await createUnifont([
  providers.google(),
])

const { fonts } = await unifont.resolveFont('Poppins', {
  subsets: ['latin']
})
```

###### `options`

- Type: `{ [key: string]?: Record<string, any> }`

A provider can define options to provide on a font family basis. Types will be automatically inferred:

```js
import { createUnifont, providers } from 'unifont'

const unifont = await createUnifont([
  providers.google(),
])

const { fonts } = await unifont.resolveFont('Poppins', {
  options: {
    google: {
      experimental: {
        glyphs: ['Hello', 'World']
      }
    }
  },
})
```

###### `formats`

- Type: `('woff2' | 'woff' | 'otf' | 'ttf' | 'eot')[]`
- Default: `['woff2']`

Specifies what font formats to retrieve:

```js
import { createUnifont, providers } from 'unifont'

const unifont = await createUnifont([
  providers.google(),
])

const { fonts } = await unifont.resolveFont('Poppins', {
  formats: ['woff2', 'woff2']
})
```

##### Providers

- Type: `string[]`

By default it uses all the providers provided to `createUnifont()`. However you can restrict usage to only a subset:

```js
import { createUnifont, providers } from 'unifont'

const unifont = await createUnifont([
  providers.google(),
  providers.fontsource(),
])

const { fonts } = await unifont.resolveFont('Poppins', {}, ['google'])
```

#### `listFonts()`

- Type: `(providers?: T[]) => Promise<string[] | undefined>`

Retrieves font names available for all providers:

```js
import { createUnifont, providers } from 'unifont'

const unifont = await createUnifont([
  providers.google(),
])

const availableFonts = await unifont.listFont()
```

It may return `undefined` if no provider is able to return names.

##### Providers

- Type: `string[]`

By default it uses all the providers provided to `createUnifont()`. However you can restrict usage to only a subset:

```js
import { createUnifont, providers } from 'unifont'

const unifont = await createUnifont([
  providers.google(),
  providers.fontsource(),
])

const availableFonts = await unifont.listFont(['google'])
```

## Building your own provider

### Defining a provider

To build your own font provider, use the `defineFontProvider()` helper:

```ts
import { defineFontProvider } from 'unifont'

export const myProvider = defineFontProvider(/* ... */)
```

It accepts a unique name as a first argument and a callback function as 2nd argument:

```ts
import { defineFontProvider } from 'unifont'

export const myProvider = defineFontProvider('my-provider', async (options, ctx) => {
  // ...
})
```

If you use options, you can simply annotate it:

```ts
import { defineFontProvider } from 'unifont'

export interface MyProviderOptions {
  foo?: string
}

export const myProvider = defineFontProvider('my-provider', async (options: MyProviderOptions, ctx) => {
  // ...
})
```

The context (`ctx`) gives access to the [`storage`](#storage), allowing you to cache results. We'll see how below.

### Initialization

The callback runs when a `Unifont` instance is created. It is used for initialization logic, such as fetching the list of available fonts:

```ts
import { defineFontProvider } from 'unifont'

export const myProvider = defineFontProvider('my-provider', async (options, ctx) => {
  const fonts: { name: string, cssUrl: string }[] = await ctx.storage.getItem('my-provider:meta.json', () => fetch('https://api.example.com/fonts.json').then(res => res.json()))

  // ...
})
```

You can now use this data in the methods.

### `listFonts()`

While optional, it's easy to implement this method now that we have the full list:

```ts
import { defineFontProvider } from 'unifont'

export const myProvider = defineFontProvider('my-provider', async (options, ctx) => {
  const fonts: { name: string, cssUrl: string }[] = [/* ... */]

  return {
    listFonts() {
      return fonts.map(font => font.name)
    }
    // ...
  }
})
```

### `resolveFont()`

This is where most of the logic lies. It depends a lot on how the provider works, and often involves parsing CSS files. Have a look at the implementation of built-in providers for inspiration!

```ts
import { hash } from 'ohash'
import { defineFontProvider } from 'unifont'

export const myProvider = defineFontProvider('my-provider', async (options, ctx) => {
  const fonts: { name: string, cssUrl: string }[] = [/* ... */]

  return {
    // ...
    async resolveFont(fontFamily, options) {
      const font = fonts.find(font => font.name === fontFamily)
      if (!font) {
        return
      }

      return {
        fonts: await ctx.storage.getItem(`my-provider:${fontFamily}-${hash(options)}-data.json`, async () => {
          // Fetch an API, extract CSS...
          return [/* ... */]
        })
      }
    }
  }
})
```

If you use family options, you can override the type of `options` and it will be inferred:

```ts
import type { ResolveFontOptions } from 'unifont'
import { hash } from 'ohash'
import { defineFontProvider } from 'unifont'

export interface MyProviderFamilyOptions {
  foo?: string
}

export const myProvider = defineFontProvider('my-provider', async (options, ctx) => {
  // ...

  return {
    // ...
    async resolveFont(fontFamily, options: ResolveFontOptions<MyProviderFamilyOptions>) {
      // ...
    }
  }
})
```

## 💻 Development

- Clone this repository
- Enable [Corepack](https://github.com/nodejs/corepack) using `corepack enable`
- Install dependencies using `pnpm install`
- Run interactive tests using `pnpm dev`

## License

Made with ❤️

Published under [MIT License](./LICENCE).

<!-- Badges -->

[npm-version-src]: https://img.shields.io/npm/v/unifont?style=flat-square
[npm-version-href]: https://npmjs.com/package/unifont
[npm-downloads-src]: https://img.shields.io/npm/dm/unifont?style=flat-square
[npm-downloads-href]: https://npm.chart.dev/unifont
[github-actions-src]: https://img.shields.io/github/actions/workflow/status/unjs/unifont/ci.yml?branch=main&style=flat-square
[github-actions-href]: https://github.com/unjs/unifont/actions?query=workflow%3Aci
[codecov-src]: https://img.shields.io/codecov/c/gh/unjs/unifont/main?style=flat-square
[codecov-href]: https://codecov.io/gh/unjs/unifont
