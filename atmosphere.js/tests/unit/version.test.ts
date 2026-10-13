/*
 * Copyright 2011-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// @vitest-environment node

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, it, expect } from 'vitest';
import tsupConfig from '../../tsup.config';
import { VERSION } from '../../src/version';
import { Atmosphere } from '../../src/core/atmosphere';
import { AtmosphereProtocol } from '../../src/utils/protocol';

/**
 * VERSION is sent to the server as `X-Atmosphere-Framework` on every request.
 * It was once a hand-maintained literal that drifted to '5.0.7' while the
 * package shipped as 5.0.47, so it is now injected from package.json at build
 * time. These tests pin both halves: the value the source sees, and the build
 * configuration that injects it into every published bundle.
 */
const pkg = JSON.parse(
  readFileSync(fileURLToPath(new URL('../../package.json', import.meta.url)), 'utf8'),
) as { version: string };

describe('VERSION', () => {
  it('equals the package.json version', () => {
    expect(VERSION).toBe(pkg.version);
  });

  it('is what Atmosphere#version reports', () => {
    expect(new Atmosphere().version).toBe(pkg.version);
  });

  it('is sent as X-Atmosphere-Framework', () => {
    const url = new AtmosphereProtocol().buildUrl({
      url: 'http://localhost/test',
      transport: 'websocket',
    });
    expect(new URL(url).searchParams.get('X-Atmosphere-Framework')).toBe(pkg.version);
  });

  it('is the build-time constant in src/version.ts, not a hard-coded literal', () => {
    const source = readFileSync(
      fileURLToPath(new URL('../../src/version.ts', import.meta.url)),
      'utf8',
    );
    expect(source).toMatch(/^export const VERSION(?:\s*:\s*string)?\s*=\s*__ATMOSPHERE_VERSION__\s*;/m);
  });

  it('is injected from package.json into every tsup build, including the IIFE global build', () => {
    const configs = Array.isArray(tsupConfig) ? tsupConfig : [tsupConfig];
    expect(configs.length).toBeGreaterThan(0);
    for (const config of configs) {
      expect(config.define?.__ATMOSPHERE_VERSION__).toBe(JSON.stringify(pkg.version));
    }
    expect(configs.some((config) => config.format?.includes('iife'))).toBe(true);
  });
});
