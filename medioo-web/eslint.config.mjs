import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { FlatCompat } from "@eslint/eslintrc";

const compat = new FlatCompat({ baseDirectory: dirname(fileURLToPath(import.meta.url)) });

// The import bans every file keeps, src/copy/** included. The src/copy/** override below re-declares
// no-restricted-imports, and an override replaces the whole rule's options, so these are repeated there by name or they
// would silently lift inside src/copy.
const RESTRICTED_PATHS = [
  { name: "next/image", message: "No next/image: crops are served by Java; images are plain <img> (plan, Web platform)." },
  { name: "next/font/google", message: "No Google Fonts: the Noto subsets are self-hosted under /fonts." },
  { name: "next/font/local", message: "next/font injects inline styles that style-src 'self' blocks; use @font-face in CSS." },
];

const config = [
  {
    ignores: [".next/**", "node_modules/**", "playwright-report/**", "test-results/**", "next-env.d.ts"],
  },
  ...compat.extends("next/core-web-vitals", "next/typescript"),
  {
    rules: {
      // style-src 'self' (Global Constraints): an inline style is a CSP violation in production, so it is a lint error here.
      "react/forbid-dom-props": ["error", { forbid: ["style"] }],
      "react/forbid-component-props": ["error", { forbid: ["style"] }],
      "no-restricted-imports": [
        "error",
        {
          paths: RESTRICTED_PATHS,
          // The generated copy is Java's (WebCopyExportTest); it is read only through @/copy, whose t() fills
          // placeholders by Java's rules. A direct import would bypass them.
          patterns: [{ group: ["**/copy/generated/*"], message: "import copy through @/copy" }],
        },
      ],
    },
  },
  {
    // src/copy/** is the one door to the generated copy: the same path bans, without the generated-copy pattern.
    files: ["src/copy/**"],
    rules: {
      "no-restricted-imports": ["error", { paths: RESTRICTED_PATHS }],
    },
  },
];

export default config;
