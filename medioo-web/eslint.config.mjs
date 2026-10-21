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

// The generated copy is read only through @/copy. no-restricted-imports sees static imports and re-exports; these see the
// forms it misses: a dynamic import() and a require(), by a plain or a template-literal path.
const GENERATED_COPY = "/copy\\/generated\\//";
const GENERATED_COPY_MESSAGE = "import copy through @/copy";
const GENERATED_COPY_SYNTAX = [
  { selector: `ImportExpression[source.value=${GENERATED_COPY}]`, message: GENERATED_COPY_MESSAGE },
  { selector: `ImportExpression[source.quasis.0.value.raw=${GENERATED_COPY}]`, message: GENERATED_COPY_MESSAGE },
  { selector: `CallExpression[callee.name="require"][arguments.0.value=${GENERATED_COPY}]`, message: GENERATED_COPY_MESSAGE },
];

// The safety lines are rendered only from what Java sent (a `*_key` or a flag in the /app/api JSON), never named as a
// literal: urgency wording only for a RELEASED explanation, red/NOW only for a stored NOW, 999 only when
// viewer.bangladesh. A component that renders them lives in src/components/safety/**, the one place exempt from this.
const SAFETY_KEY = "/^urgency\\.|999/";
const SAFETY_LITERAL_MESSAGE =
  "a safety line (urgency.*, 999) is rendered only from the key or flag Java sent, in src/components/safety/**";
const SAFETY_LITERAL_SYNTAX = [
  { selector: `CallExpression[callee.name="t"][arguments.1.value=${SAFETY_KEY}]`, message: SAFETY_LITERAL_MESSAGE },
  { selector: `CallExpression[callee.name="t"][arguments.1.quasis.0.value.raw=${SAFETY_KEY}]`, message: SAFETY_LITERAL_MESSAGE },
];

const config = [
  {
    ignores: [".next/**", ".next-catalog/**", "node_modules/**", "playwright-report/**", "test-results/**", "next-env.d.ts"],
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
          patterns: [{ group: ["**/copy/generated/*"], message: GENERATED_COPY_MESSAGE }],
        },
      ],
      "no-restricted-syntax": ["error", ...GENERATED_COPY_SYNTAX, ...SAFETY_LITERAL_SYNTAX],
    },
  },
  {
    // src/copy/** is the one door to the generated copy: the same path bans, without the generated-copy pattern.
    files: ["src/copy/**"],
    rules: {
      "no-restricted-imports": ["error", { paths: RESTRICTED_PATHS }],
    },
  },
  {
    // The one place a safety line may be named. An override replaces the rule's options, so the generated-copy syntax
    // bans are repeated here or they would lift.
    files: ["src/components/safety/**"],
    rules: {
      "no-restricted-syntax": ["error", ...GENERATED_COPY_SYNTAX],
    },
  },
];

export default config;
