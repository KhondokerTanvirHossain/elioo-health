import { readdirSync, readFileSync, statSync } from "node:fs";
import { dirname, join, relative, sep } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";

// Colour literals live only in src/styles/tokens.css (plan W2a). stylelint guards the CSS; this guards the TypeScript,
// where a hex in an SVG attribute or a style object would paint a colour no token, contrast test or NOW check sees.
const src = join(dirname(fileURLToPath(import.meta.url)), "..");

// CSS Color 4's 148 named colours, plus the two keywords that are colours too.
const NAMED = new Set(
  (
    "aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue blueviolet brown burlywood " +
    "cadetblue chartreuse chocolate coral cornflowerblue cornsilk crimson cyan darkblue darkcyan darkgoldenrod " +
    "darkgray darkgreen darkgrey darkkhaki darkmagenta darkolivegreen darkorange darkorchid darkred darksalmon " +
    "darkseagreen darkslateblue darkslategray darkslategrey darkturquoise darkviolet deeppink deepskyblue dimgray " +
    "dimgrey dodgerblue firebrick floralwhite forestgreen fuchsia gainsboro ghostwhite gold goldenrod gray green " +
    "greenyellow grey honeydew hotpink indianred indigo ivory khaki lavender lavenderblush lawngreen lemonchiffon " +
    "lightblue lightcoral lightcyan lightgoldenrodyellow lightgray lightgreen lightgrey lightpink lightsalmon " +
    "lightseagreen lightskyblue lightslategray lightslategrey lightsteelblue lightyellow lime limegreen linen magenta " +
    "maroon mediumaquamarine mediumblue mediumorchid mediumpurple mediumseagreen mediumslateblue mediumspringgreen " +
    "mediumturquoise mediumvioletred midnightblue mintcream mistyrose moccasin navajowhite navy oldlace olive " +
    "olivedrab orange orangered orchid palegoldenrod palegreen paleturquoise palevioletred papayawhip peachpuff peru " +
    "pink plum powderblue purple rebeccapurple red rosybrown royalblue saddlebrown salmon sandybrown seagreen " +
    "seashell sienna silver skyblue slateblue slategray slategrey snow springgreen steelblue tan teal thistle tomato " +
    "turquoise violet wheat white whitesmoke yellow yellowgreen"
  ).split(" "),
);
const KEYWORDS = ["transparent", "currentcolor"];

const HEX = /#[0-9a-fA-F]{3,8}\b/g;
const FUNCTION = /\b(rgba?|hsla?|hwb|lab|lch|oklab|oklch|color-mix)\(/g;
// fill="red", fill={"red"}, fill={'red'}, and style={{ color: "red" }} / backgroundColor: `red`.
const NAMED_VALUE =
  /\b(fill|stroke|stopColor|stop-color|floodColor|flood-color|color|background[A-Za-z-]*)\s*[=:]\s*\{?\s*(["'`])\s*([A-Za-z]+)\s*\2/g;

/** Each colour literal in one source text, as `<file>:<line> <literal>`. */
function scanText(file: string, text: string): string[] {
  const found: string[] = [];
  const lineOf = (index: number) => text.slice(0, index).split("\n").length;
  for (const m of text.matchAll(HEX)) found.push(`${file}:${lineOf(m.index!)} ${m[0]}`);
  for (const m of text.matchAll(FUNCTION)) found.push(`${file}:${lineOf(m.index!)} ${m[1]}(`);
  for (const m of text.matchAll(NAMED_VALUE)) {
    const name = m[3]!.toLowerCase();
    if (NAMED.has(name) || KEYWORDS.includes(name)) found.push(`${file}:${lineOf(m.index!)} ${m[1]}=${m[3]}`);
  }
  return found;
}

function sources(dir: string): string[] {
  const out: string[] = [];
  for (const entry of readdirSync(dir)) {
    const path = join(dir, entry);
    if (statSync(path).isDirectory()) out.push(...sources(path));
    else if (/\.(ts|tsx)$/.test(entry)) out.push(path);
  }
  return out;
}

describe("colour literals in TypeScript", () => {
  it("noColourLiteralInComponentCode", () => {
    const scanned = sources(src)
      .map((path) => relative(join(src, ".."), path).split(sep).join("/"))
      .filter((rel) => !rel.startsWith("src/styles/") && !/\.test\.(ts|tsx)$/.test(rel));
    const offenders = scanned.flatMap((rel) => scanText(rel, readFileSync(join(src, "..", rel), "utf8")));
    expect(offenders).toEqual([]);
    // The scan read the tree it claims to.
    expect(scanned).toContain("src/app/layout.tsx");
    expect(scanned).toContain("src/app/app/probe/page.tsx");
  });

  it("theScannerReportsEachForm", () => {
    expect(NAMED.size).toBe(148);
    expect(scanText("Probe.tsx", '<svg><path fill="#d12c24"/></svg>')).toEqual(["Probe.tsx:1 #d12c24"]);
    expect(scanText("P.tsx", '<path fill="red" />')).toEqual(["P.tsx:1 fill=red"]);
    expect(scanText("P.tsx", "<path fill={'Crimson'} />")).toEqual(["P.tsx:1 fill=Crimson"]);
    expect(scanText("P.tsx", '<stop stopColor="white" />')).toEqual(["P.tsx:1 stopColor=white"]);
    expect(scanText("P.tsx", 'const s = { backgroundColor: "transparent" };')).toEqual(["P.tsx:1 backgroundColor=transparent"]);
    expect(scanText("P.tsx", "const s = { color: `rgba(0, 0, 0, 0.5)` };")).toEqual(["P.tsx:1 rgba("]);
    expect(scanText("P.tsx", 'const s = "color-mix(in srgb, red, blue)";')).toEqual(["P.tsx:1 color-mix("]);
    // A token is not a literal, and a word that is not a colour is not one either.
    expect(scanText("P.tsx", '<path fill="var(--label3)" className="tabs" color="tint" />')).toEqual([]);
  });
});
