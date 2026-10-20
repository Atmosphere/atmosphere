import { describe, expect, test } from 'bun:test';
// The parser react-native-markdown-display renders from: what its text
// tokens hold is what the bubble shows.
import MarkdownIt from 'markdown-it';
import { renderMath, texToText } from './mathText';

const markdown = new MarkdownIt();

/** The text a reply shows once markdown has parsed it, one entry per block. */
function shown(reply: string): string[] {
  const blocks: string[] = [];
  for (const token of markdown.parse(reply, {})) {
    if (!token.children) continue;
    blocks.push(
      token.children
        .map((child: { type: string; content: string }) =>
          child.type === 'text' || child.type === 'code_inline'
            ? child.content
            : child.type === 'softbreak' || child.type === 'hardbreak'
              ? '\n'
              : '',
        )
        .join(''),
    );
  }
  return blocks;
}

// Verbatim from qwen2.5:3b in the math room (sweep 2026-10-19, raw server frames).
const SQUARE_ROOT_STEP =
  '4. Solve for \\(b\\) by taking the square root of both sides: \\(b = \\sqrt{144} = 12\\).';
const COMPLETING_THE_SQUARE =
  'Subtract \\(\\frac{c}{a}\\) from both sides:\n\\[ x^2 + \\frac{b}{a}x = -\\frac{c}{a} \\]\n';

describe('math in a reply, as the bubble shows it', () => {
  test('raw, markdown eats the delimiter backslashes and keeps the commands', () => {
    // The defect: this is the line the sweep saw in the app.
    expect(shown(SQUARE_ROOT_STEP)).toEqual([
      'Solve for (b) by taking the square root of both sides: (b = \\sqrt{144} = 12).',
    ]);
  });

  test('inline \\( \\) math renders as text with no TeX left', () => {
    expect(shown(renderMath(SQUARE_ROOT_STEP))).toEqual([
      'Solve for b by taking the square root of both sides: b = √144 = 12.',
    ]);
  });

  test('display \\[ \\] math renders on its own line', () => {
    expect(shown(renderMath(COMPLETING_THE_SQUARE))).toEqual([
      'Subtract c/a from both sides:\nx² + (b/a)x = -c/a',
    ]);
  });

  test('$$ $$ and $ $ delimiters', () => {
    expect(shown(renderMath('Area: $$A = \\pi r^2$$'))).toEqual(['Area: A = πr²']);
    expect(shown(renderMath('so $x_1 + x_2 = -\\frac{b}{a}$ holds'))).toEqual([
      'so x₁ + x₂ = -b/a holds',
    ]);
  });

  test('prices are not math', () => {
    const prose = 'It costs $5 and the other one costs $10.';
    expect(renderMath(prose)).toBe(prose);
  });

  test('code is never rewritten', () => {
    const code = 'Use `\\(x\\)` in TeX, or:\n\n```\n\\[ a^2 \\]\n```\n';
    expect(renderMath(code)).toBe(code);
  });

  test('a span still streaming in is left for the next token', () => {
    const partial = 'Then \\(b = \\sqrt{14';
    expect(renderMath(partial)).toBe(partial);
    expect(renderMath(partial + '4}\\)')).toBe('Then b = √144');
  });

  test('a degree sign written as ^\\circ reads as °', () => {
    // Verbatim shape from the math room (re-test 2026-10-19): it showed "30^∘".
    expect(renderMath('\\( \\sin(30^\\circ) = \\frac{a}{c} \\) and \\(90^{\\circ}\\)')).toBe(
      'sin(30°) = a/c and 90°',
    );
  });

  test('characters markdown would read as emphasis are escaped', () => {
    expect(shown(renderMath('\\(a_n * b_n\\)'))).toEqual(['aₙ * bₙ']);
    expect(shown(renderMath('\\(x_{big} * [y]\\)'))).toEqual(['x_(big) * [y]']);
  });
});

describe('texToText', () => {
  test.each([
    ['\\frac{1}{2}', '1/2'],
    ['\\frac{x+1}{x-1}', '(x+1)/(x-1)'],
    ['\\sqrt{b^2 - 4ac}', '√(b² - 4ac)'],
    ['\\sqrt[3]{27} = 3', '³√27 = 3'],
    ['x^{10} + e^{i\\pi}', 'x¹⁰ + e^(iπ)'],
    ['f\'(x) = 3x^2 \\sin x + x^3 \\cos x', "f'(x) = 3x² sin x + x³ cos x"],
    ['\\left( \\frac{a}{b} \\right)^2', '( a/b )²'],
    ['\\int_0^1 x\\,dx = \\frac{1}{2}', '∫₀¹ x dx = 1/2'],
    ['\\sum_{k=1}^{n} k = \\frac{n(n+1)}{2}', '∑ₖ₌₁ⁿ k = (n(n+1))/2'],
    ['a \\times b \\cdot c \\div d \\pm e', 'a × b · c ÷ d ± e'],
    ['x \\leq 3 \\neq 4 \\geq y \\approx \\infty', 'x ≤ 3 ≠ 4 ≥ y ≈ ∞'],
    ['\\text{Area} = \\pi r^2', 'Area = πr²'],
    ['\\binom{6}{2} = 15', 'C(6, 2) = 15'],
    ['x \\in \\mathbb{R}', 'x ∈ ℝ'],
    ['\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1', 'lim_(x → 0) (sin x)/x = 1'],
    ['\\begin{aligned} a &= b \\\\ c &= d \\end{aligned}', 'a = b\nc = d'],
    ['123456 \\times 789 = 97406784', '123456 × 789 = 97406784'],
    ['\\angle A = 45^\\circ', '∠ A = 45°'],
    ['f \\circ g', 'f ∘ g'],
  ])('%s', (tex, text) => {
    expect(texToText(tex)).toBe(text);
  });
});
