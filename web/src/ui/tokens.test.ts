import { describe, expect, it } from "vitest";
import base from "./base.css?raw";
import tokens from "./tokens.css?raw";

// android/src/main/java/app/kaeru/ui/common/theme/Color.kt:10-24 — "nine values and no tenth".
const PALETTE: Array<[string, string]> = [
  ["--bg", "#0B0C10"],
  ["--surface", "#15171E"],
  ["--elevated", "#1E212B"],
  ["--line", "#2A2E3A"],
  ["--ink", "#F2F3F5"],
  ["--ink-soft", "#9AA0AA"],
  ["--accent", "#F5A524"],
  ["--on-accent", "#1A1200"],
  ["--error", "#E5484D"],
];

// Phone scale, size / line-height px / weight: android/src/main/java/app/kaeru/ui/common/theme/Type.kt:94-155.
const TYPE_SCALE: Array<[string, number, number, number]> = [
  ["t-display", 34, 48, 800],
  ["t-headline", 24, 34, 700],
  ["t-title", 17, 24, 600],
  ["t-title-sm", 15, 21, 600],
  ["t-body", 15, 22, 400],
  ["t-body-lg", 16, 24, 400],
  ["t-label", 13, 19, 600],
  ["t-label-sm", 11, 16, 600],
];

/** The text of the first `selector { … }` rule. */
function rule(css: string, selector: string): string {
  const start = css.indexOf(`${selector} {`);
  if (start < 0) throw new Error(`no rule for ${selector}`);
  return css.slice(start, css.indexOf("}", start) + 1);
}

/** The value of the first `property: value;` declaration. */
function value(css: string, property: string): string {
  const match = new RegExp(`(?:^|[\\s;{])${property}:\\s*([^;]+);`).exec(css);
  if (!match) throw new Error(`${property} is not declared`);
  return match[1].trim();
}

describe("tokens.css", () => {
  it("declares the nine palette colours with the Android values", () => {
    for (const [name, hex] of PALETTE) {
      expect(value(tokens, name).toUpperCase()).toBe(hex);
    }
  });

  it("has no tenth colour", () => {
    const hexes = new Set((tokens.match(/#[0-9a-f]{6}\b/gi) ?? []).map((hex) => hex.toUpperCase()));
    expect([...hexes].sort()).toEqual(PALETTE.map(([, hex]) => hex).sort());
  });

  it("keeps the spacing, radii and motion of KaeruTokens.kt", () => {
    expect(value(tokens, "--s1")).toBe("4px");
    expect(value(tokens, "--s2")).toBe("8px");
    expect(value(tokens, "--s3")).toBe("12px");
    expect(value(tokens, "--s4")).toBe("16px");
    expect(value(tokens, "--s6")).toBe("24px");
    expect(value(tokens, "--s8")).toBe("32px");
    expect(value(tokens, "--r-card")).toBe("12px");
    expect(value(tokens, "--r-chip")).toBe("20px");
    expect(value(tokens, "--fast")).toBe("150ms");
    expect(value(tokens, "--normal")).toBe("250ms");
    expect(value(tokens, "--hero")).toBe("400ms");
  });

  it("widens the gutter from 16px to 32px at 768px", () => {
    expect(value(tokens, "--gutter")).toBe("16px");
    const wide = tokens.slice(tokens.indexOf("@media (min-width: 768px)"));
    expect(value(wide, "--gutter")).toBe("32px");
  });

  it.each(TYPE_SCALE)(".%s follows the Android phone scale", (name, size, lineHeight, weight) => {
    const css = rule(tokens, `.${name}`);
    expect(value(css, "font-size")).toBe(`${size}px`);
    expect(value(css, "line-height")).toBe(`${lineHeight}px`);
    expect(value(css, "font-weight")).toBe(String(weight));
  });

  // Manrope's line box is 1.366 of the size; below 1.4 Cyrillic descenders get clipped (Type.kt:48-86).
  it.each(TYPE_SCALE)(".%s keeps line-height at least 1.4 times the size", (name) => {
    const css = rule(tokens, `.${name}`);
    const size = parseFloat(value(css, "font-size"));
    const lineHeight = parseFloat(value(css, "line-height"));
    expect(lineHeight * 10).toBeGreaterThanOrEqual(size * 14);
  });
});

describe("base.css", () => {
  it("self-hosts variable Manrope and loads no third-party font", () => {
    const face = rule(base, "@font-face");
    expect(value(face, "font-family")).toBe('"Manrope"');
    expect(value(face, "font-weight")).toBe("200 800");
    expect(value(face, "font-display")).toBe("swap");
    expect(face).toContain('url("/fonts/manrope.ttf")');
    expect(base).not.toMatch(/fonts\.googleapis|fonts\.gstatic/);
  });

  it("paints the page dark itself", () => {
    const body = rule(base, "body");
    expect(value(body, "background")).toBe("var(--bg)");
    expect(value(body, "color")).toBe("var(--ink)");
  });
});
