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

import { describe, it, expect } from 'vitest';
import {
  containerStyle,
  headerStyle,
  titleStyle,
  subtitleStyle,
  statusBarStyle,
  statusDotStyle,
  messageAreaStyle,
  bubbleStyle,
  authorStyle,
  timeStyle,
  inputBarStyle,
  inputStyle,
  sendButtonStyle,
  systemStyle,
  welcomeStyle,
  joinInputStyle,
  joinButtonStyle,
  cursorStyle,
  progressStyle,
  errorStyle,
} from '../../../src/chat/styles';
import { themes } from '../../../src/chat/types';

describe('containerStyle', () => {
  it('should return light theme by default', () => {
    const s = containerStyle();
    expect(s.background).toBe('#f5f6fa');
    expect(s.color).toBe('#333');
    expect(s.display).toBe('flex');
    expect(s.flexDirection).toBe('column');
    expect(s.height).toBe('100vh');
  });

  it('should return dark theme when dark=true', () => {
    const s = containerStyle(true);
    expect(s.background).toBe('#1a1a2e');
    expect(s.color).toBe('#e0e0e0');
  });
});

describe('headerStyle', () => {
  it('should use theme gradient', () => {
    const s = headerStyle(themes.default);
    expect(s.background).toContain(themes.default.gradient[0]);
    expect(s.background).toContain(themes.default.gradient[1]);
    expect(s.color).toBe('#fff');
  });
});

describe('static styles', () => {
  it('titleStyle should have expected properties', () => {
    expect(titleStyle.margin).toBe(0);
    expect(titleStyle.fontWeight).toBe(700);
  });

  it('subtitleStyle should have opacity', () => {
    expect(subtitleStyle.opacity).toBe(0.85);
  });

  it('authorStyle should be bold', () => {
    expect(authorStyle.fontWeight).toBe(600);
  });

  it('timeStyle should be small and faded', () => {
    expect(timeStyle.fontSize).toBe(11);
    expect(timeStyle.opacity).toBe(0.5);
  });

  it('systemStyle should have green color', () => {
    expect(systemStyle.color).toBe('#28a745');
    expect(systemStyle.textAlign).toBe('center');
  });

  it('welcomeStyle should center content', () => {
    expect(welcomeStyle.textAlign).toBe('center');
  });

  it('cursorStyle should have animation', () => {
    expect(cursorStyle.animation).toContain('blink');
  });

  it('progressStyle should use warning color', () => {
    expect(progressStyle.color).toBe('#ffc107');
  });

  it('errorStyle should use danger color', () => {
    expect(errorStyle.color).toBe('#dc3545');
  });
});

describe('statusDotStyle', () => {
  it('should return green for connected', () => {
    expect(statusDotStyle('connected').background).toBe('#28a745');
  });

  it('should return yellow for reconnecting', () => {
    expect(statusDotStyle('reconnecting').background).toBe('#ffc107');
  });

  it('should return red for error', () => {
    expect(statusDotStyle('error').background).toBe('#dc3545');
  });

  it('should return grey for unknown state', () => {
    expect(statusDotStyle('unknown').background).toBe('#adb5bd');
  });
});

describe('messageAreaStyle', () => {
  it('should use light background by default', () => {
    expect(messageAreaStyle().background).toBe('#f5f6fa');
  });

  it('should use dark background when dark=true', () => {
    expect(messageAreaStyle(true).background).toBe('#16213e');
  });
});

describe('bubbleStyle', () => {
  it('should style own messages with accent in light mode', () => {
    const s = bubbleStyle(true, themes.default);
    expect(s.alignSelf).toBe('flex-end');
    expect(s.borderLeft).toContain(themes.default.accent);
  });

  it('should style other messages with white bg in light mode', () => {
    const s = bubbleStyle(false, themes.default);
    expect(s.alignSelf).toBe('flex-start');
    expect(s.background).toBe('#fff');
  });

  it('should use gradient for own messages in dark mode', () => {
    const s = bubbleStyle(true, themes.ai);
    expect(s.alignSelf).toBe('flex-end');
    expect(s.background).toContain(themes.ai.gradient[0]);
    expect(s.color).toBe('#fff');
  });

  it('should use dark bg for other messages in dark mode', () => {
    const s = bubbleStyle(false, themes.ai);
    expect(s.alignSelf).toBe('flex-start');
    expect(s.background).toBe('#16213e');
    expect(s.color).toBe('#e0e0e0');
  });
});

describe('inputBarStyle', () => {
  it('should use white bg in light mode', () => {
    expect(inputBarStyle().background).toBe('#fff');
  });

  it('should use dark bg in dark mode', () => {
    expect(inputBarStyle(true).background).toBe('#1a1a2e');
  });
});

describe('inputStyle', () => {
  it('should adapt to theme dark mode', () => {
    const light = inputStyle(themes.default);
    expect(light.background).toBe('#fff');
    expect(light.color).toBe('#333');

    const dark = inputStyle(themes.ai);
    expect(dark.background).toBe('#16213e');
    expect(dark.color).toBe('#e0e0e0');
  });
});

describe('sendButtonStyle', () => {
  it('should use theme gradient', () => {
    const s = sendButtonStyle(themes.langchain4j);
    expect(s.background).toContain(themes.langchain4j.gradient[0]);
    expect(s.color).toBe('#fff');
  });
});

describe('joinInputStyle', () => {
  it('should use theme accent for border', () => {
    const s = joinInputStyle(themes.embabel);
    expect(s.border).toContain(themes.embabel.accent);
  });
});

describe('joinButtonStyle', () => {
  it('should extend sendButtonStyle', () => {
    const s = joinButtonStyle(themes.default);
    expect(s.background).toContain(themes.default.gradient[0]);
    expect(s.borderRadius).toBe(8);
  });
});
