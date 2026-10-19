/**
 * Makes the math in an assistant reply readable before it reaches
 * react-native-markdown-display.
 *
 * Models write math as LaTeX: `\( b = \sqrt{144} \)` inline and
 * `\[ x^2 + \frac{b}{a}x = 0 \]` on its own line, sometimes `$…$` / `$$…$$`.
 * The markdown renderer has no math support, and CommonMark reads `\(` and
 * `\[` as escaped punctuation, so the reader saw `(b = \sqrt{144})`: the
 * delimiters lost their backslash and the commands kept theirs.
 *
 * Each complete math span is rewritten as Unicode text (`b = √144`,
 * `x² + b/a·x = 0` style) and escaped so markdown shows it as written. A span
 * whose closing delimiter has not streamed in yet is left alone until it
 * arrives. Code spans and fences are never touched.
 */

const SYMBOLS: Record<string, string> = {
  alpha: 'α', beta: 'β', gamma: 'γ', delta: 'δ', epsilon: 'ε', varepsilon: 'ε', zeta: 'ζ',
  eta: 'η', theta: 'θ', vartheta: 'ϑ', iota: 'ι', kappa: 'κ', lambda: 'λ', mu: 'μ', nu: 'ν',
  xi: 'ξ', pi: 'π', varpi: 'ϖ', rho: 'ρ', sigma: 'σ', varsigma: 'ς', tau: 'τ', upsilon: 'υ',
  phi: 'φ', varphi: 'φ', chi: 'χ', psi: 'ψ', omega: 'ω',
  Gamma: 'Γ', Delta: 'Δ', Theta: 'Θ', Lambda: 'Λ', Xi: 'Ξ', Pi: 'Π', Sigma: 'Σ',
  Upsilon: 'Υ', Phi: 'Φ', Psi: 'Ψ', Omega: 'Ω',
  times: '×', cdot: '·', div: '÷', pm: '±', mp: '∓', ast: '∗', star: '⋆', circ: '∘',
  bullet: '•', le: '≤', leq: '≤', ge: '≥', geq: '≥', neq: '≠', ne: '≠', approx: '≈',
  equiv: '≡', sim: '∼', simeq: '≃', cong: '≅', propto: '∝', ll: '≪', gg: '≫',
  infty: '∞', partial: '∂', nabla: '∇', sum: '∑', prod: '∏', int: '∫', iint: '∬', oint: '∮',
  to: '→', rightarrow: '→', leftarrow: '←', Rightarrow: '⇒', Leftarrow: '⇐',
  leftrightarrow: '↔', Leftrightarrow: '⇔', implies: '⇒', iff: '⇔', mapsto: '↦',
  in: '∈', notin: '∉', ni: '∋', subset: '⊂', subseteq: '⊆', supset: '⊃', supseteq: '⊇',
  cup: '∪', cap: '∩', setminus: '∖', emptyset: '∅', varnothing: '∅', forall: '∀', exists: '∃',
  neg: '¬', lnot: '¬', land: '∧', wedge: '∧', lor: '∨', vee: '∨', oplus: '⊕', otimes: '⊗',
  angle: '∠', triangle: '△', perp: '⊥', parallel: '∥', degree: '°', prime: '′',
  ldots: '…', cdots: '⋯', dots: '…', vdots: '⋮', ddots: '⋱', therefore: '∴', because: '∵',
  langle: '⟨', rangle: '⟩', lfloor: '⌊', rfloor: '⌋', lceil: '⌈', rceil: '⌉',
  mid: '|', vert: '|', lvert: '|', rvert: '|', Vert: '‖', backslash: '\\',
  hbar: 'ħ', ell: 'ℓ', Re: 'ℜ', Im: 'ℑ', aleph: 'ℵ',
  quad: '  ', qquad: '    ',
};

/** Rendered as their name: `\sin x` reads "sin x". */
const FUNCTIONS = new Set([
  'sin', 'cos', 'tan', 'sec', 'csc', 'cot', 'arcsin', 'arccos', 'arctan', 'sinh', 'cosh',
  'tanh', 'log', 'ln', 'lg', 'exp', 'lim', 'max', 'min', 'sup', 'inf', 'det', 'gcd', 'lcm',
  'deg', 'arg', 'dim', 'ker', 'Pr', 'mod',
]);

/** Commands that only size or style what follows: dropped, their argument kept. */
const IGNORED = new Set([
  'left', 'right', 'big', 'Big', 'bigg', 'Bigg', 'bigl', 'bigr', 'Bigl', 'Bigr', 'biggl',
  'biggr', 'displaystyle', 'textstyle', 'scriptstyle', 'limits', 'nolimits', 'middle',
]);

/** Commands whose single argument is shown as is. */
const PASS_THROUGH = new Set([
  'text', 'textrm', 'textbf', 'textit', 'textsf', 'texttt', 'mathrm', 'mathbf', 'mathit',
  'mathsf', 'mathtt', 'mathcal', 'mathscr', 'mathfrak', 'boldsymbol', 'operatorname', 'mbox',
  'hbox', 'boxed', 'emph', 'underline',
]);

const DOUBLE_STRUCK: Record<string, string> = {
  N: 'ℕ', Z: 'ℤ', Q: 'ℚ', R: 'ℝ', C: 'ℂ', P: 'ℙ', H: 'ℍ',
};

const COMBINING: Record<string, string> = {
  bar: '̄', overline: '̅', hat: '̂', widehat: '̂', tilde: '̃',
  widetilde: '̃', vec: '⃗', dot: '̇', ddot: '̈',
};

const SUPERSCRIPT: Record<string, string> = {
  '0': '⁰', '1': '¹', '2': '²', '3': '³', '4': '⁴', '5': '⁵', '6': '⁶', '7': '⁷', '8': '⁸',
  '9': '⁹', '+': '⁺', '-': '⁻', '−': '⁻', '=': '⁼', '(': '⁽', ')': '⁾', n: 'ⁿ', i: 'ⁱ',
  a: 'ᵃ', b: 'ᵇ', c: 'ᶜ', d: 'ᵈ', e: 'ᵉ', f: 'ᶠ', g: 'ᵍ', h: 'ʰ', j: 'ʲ', k: 'ᵏ', l: 'ˡ',
  m: 'ᵐ', o: 'ᵒ', p: 'ᵖ', r: 'ʳ', s: 'ˢ', t: 'ᵗ', u: 'ᵘ', v: 'ᵛ', w: 'ʷ', x: 'ˣ', y: 'ʸ',
  z: 'ᶻ', '′': '′', '∗': '*', '°': '°',
};

const SUBSCRIPT: Record<string, string> = {
  '0': '₀', '1': '₁', '2': '₂', '3': '₃', '4': '₄', '5': '₅', '6': '₆', '7': '₇', '8': '₈',
  '9': '₉', '+': '₊', '-': '₋', '−': '₋', '=': '₌', '(': '₍', ')': '₎', a: 'ₐ', e: 'ₑ',
  h: 'ₕ', i: 'ᵢ', j: 'ⱼ', k: 'ₖ', l: 'ₗ', m: 'ₘ', n: 'ₙ', o: 'ₒ', p: 'ₚ', r: 'ᵣ', s: 'ₛ',
  t: 'ₜ', u: 'ᵤ', v: 'ᵥ', x: 'ₓ',
};

/** One symbol, a number or an already bracketed group: needs no parentheses. */
function isAtom(s: string): boolean {
  return /^(?:[\p{L}\p{N}.,′]+|\(.*\)|√\S+)$/u.test(s) && !/^-/.test(s);
}

function group(s: string): string {
  return isAtom(s) ? s : `(${s})`;
}

function script(s: string, table: Record<string, string>, marker: string): string {
  const chars = [...s.replace(/\s+/g, '')];
  if (chars.length > 0 && chars.every((c) => table[c] !== undefined)) {
    return chars.map((c) => table[c]).join('');
  }
  return marker + (chars.length === 1 ? s : `(${s})`);
}

/** Converts the TeX source of one math span to Unicode text. */
export function texToText(tex: string): string {
  let i = 0;

  const skipSpaces = () => {
    while (i < tex.length && /\s/.test(tex[i])) i++;
  };

  // One argument: a {group}, a \command or a single character.
  const arg = (): string => {
    skipSpaces();
    if (i >= tex.length) return '';
    if (tex[i] === '{') {
      i++;
      return seq(true).trim();
    }
    if (tex[i] === '\\') return command();
    return tex[i++];
  };

  const optional = (): string | null => {
    skipSpaces();
    if (tex[i] !== '[') return null;
    const end = tex.indexOf(']', i);
    if (end < 0) return null;
    const value = texToText(tex.slice(i + 1, end));
    i = end + 1;
    return value;
  };

  const command = (): string => {
    i++; // the backslash
    if (i >= tex.length) return '';
    if (!/[A-Za-z]/.test(tex[i])) {
      const c = tex[i++];
      if (c === '\\') return '\n';
      if (c === ',' || c === ':' || c === ';' || c === ' ' || c === '>') return ' ';
      if (c === '!') return '';
      return c; // \{ \} \% \$ \# \& \_ \|
    }
    let name = '';
    while (i < tex.length && /[A-Za-z]/.test(tex[i])) name += tex[i++];
    if (name in SYMBOLS) {
      const symbol = SYMBOLS[name];
      // TeX drops the space after a command: \pi r reads πr, a \times b keeps its spaces.
      if (/^\p{L}$/u.test(symbol) && /^\s+[\p{L}\p{N}]/u.test(tex.slice(i))) skipSpaces();
      return symbol;
    }
    if (FUNCTIONS.has(name)) {
      // TeX swallows the space after a command; keep the words apart.
      const next = tex.slice(i).match(/^\s*([\p{L}\p{N}\\])/u);
      return next ? `${name} ` : name;
    }
    if (IGNORED.has(name)) {
      skipSpaces();
      if (tex[i] === '.') i++; // \left. is an invisible delimiter
      return '';
    }
    if (PASS_THROUGH.has(name)) return arg();
    if (name === 'frac' || name === 'dfrac' || name === 'tfrac' || name === 'cfrac') {
      const num = arg();
      const den = arg();
      const fraction = `${group(num)}/${group(den)}`;
      // A factor right after it (\frac{b}{a}x) would read as part of the denominator.
      return /^[\p{L}\p{N}\\({]/u.test(tex.slice(i)) ? `(${fraction})` : fraction;
    }
    if (name === 'sqrt') {
      const index = optional();
      const radicand = arg();
      const root = index ? script(index, SUPERSCRIPT, '') : '';
      return `${root}√${group(radicand)}`;
    }
    if (name === 'binom' || name === 'dbinom' || name === 'tbinom') {
      const n = arg();
      const k = arg();
      return `C(${n}, ${k})`;
    }
    if (name === 'mathbb') {
      const letter = arg();
      return DOUBLE_STRUCK[letter] ?? letter;
    }
    if (name in COMBINING) {
      const base = arg();
      return [...base].length === 1 ? base + COMBINING[name] : group(base);
    }
    if (name === 'pmod') return ` (mod ${arg()})`;
    if (name === 'bmod') return ' mod ';
    if (name === 'begin' || name === 'end') {
      arg(); // the environment name
      return '';
    }
    return name; // unknown: its name reads better than a backslash
  };

  const seq = (inGroup: boolean): string => {
    let out = '';
    while (i < tex.length) {
      const c = tex[i];
      if (c === '}') {
        i++;
        if (inGroup) return out;
        continue;
      }
      if (c === '{') {
        i++;
        out += seq(true);
      } else if (c === '\\') {
        out += command();
      } else if (c === '^') {
        i++;
        out += script(arg(), SUPERSCRIPT, '^');
      } else if (c === '_') {
        i++;
        out += script(arg(), SUBSCRIPT, '_');
      } else if (c === '&') {
        i++;
      } else if (c === '~') {
        i++;
        out += ' ';
      } else {
        i++;
        out += c;
      }
    }
    return out;
  };

  return seq(false)
    .split('\n')
    .map((line) => line.replace(/[ \t]+/g, ' ').trim())
    .filter((line) => line.length > 0)
    .join('\n');
}

/** Escapes what markdown would otherwise read as emphasis, links, code or HTML. */
function escapeMarkdown(text: string): string {
  return text.replace(/[\\`*_[\]<>]/g, '\\$&');
}

// A code fence or code span (kept as is), then display math, then inline math.
// Single-dollar math needs a non-space after the opening `$` and before the
// closing one, and no digit after it, so "costs $5 and $10" stays prose.
const MATH = new RegExp(
  [
    '(```[\\s\\S]*?(?:```|$)|`[^`\\n]*`)',
    '\\$\\$([\\s\\S]+?)\\$\\$',
    '\\\\\\[([\\s\\S]+?)\\\\\\]',
    '\\\\\\(([\\s\\S]+?)\\\\\\)',
    '(?<![\\\\$\\w])\\$(?=\\S)([^$\\n]+?)(?<=\\S)\\$(?!\\d)',
  ].join('|'),
  'g',
);

/** Rewrites every complete math span of a markdown reply as readable text. */
export function renderMath(markdown: string): string {
  if (!/[\\$]/.test(markdown)) return markdown;
  return markdown.replace(
    MATH,
    (whole: string, code?: string, dd?: string, bracket?: string, paren?: string, dollar?: string) => {
      if (code !== undefined) return whole;
      const display = dd ?? bracket;
      if (display !== undefined) {
        // Display math keeps its own line(s); a markdown line break joins them.
        return escapeMarkdown(texToText(display)).split('\n').join('  \n');
      }
      return escapeMarkdown(texToText((paren ?? dollar ?? '').trim()).replace(/\n/g, ' '));
    },
  );
}
