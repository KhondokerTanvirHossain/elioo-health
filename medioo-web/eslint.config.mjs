import { dirname } from "node:path";
import { fileURLToPath } from "node:url";
import { FlatCompat } from "@eslint/eslintrc";

const compat = new FlatCompat({ baseDirectory: dirname(fileURLToPath(import.meta.url)) });

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
          paths: [
            { name: "next/image", message: "No next/image: crops are served by Java; images are plain <img> (plan, Web platform)." },
            { name: "next/font/google", message: "No Google Fonts: the Noto subsets are self-hosted under /fonts." },
            { name: "next/font/local", message: "next/font injects inline styles that style-src 'self' blocks; use @font-face in CSS." },
          ],
        },
      ],
    },
  },
];

export default config;
