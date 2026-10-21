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

// A "use client" file is shipped to the browser with everything it imports, so it may not import the copy (all of bn
// and en) or Sheet.tsx (the server half, which calls t()). A client component takes its words already resolved, as
// props from a server parent: SheetDialog is the client sheet. A type-only import is erased and allowed. This sees a
// direct import; built/client-copy.built.test.ts judges what the browser actually receives, transitive imports included.
const CLIENT_FILE = 'Program:has(> ExpressionStatement[directive="use client"])';
const CLIENT_BANNED = "/^@\\/copy(\\/.*)?$|(^|\\/)copy(\\/index)?$|(^|\\/)Sheet\\/Sheet$|^\\.\\/Sheet$/";
const CLIENT_COPY_MESSAGE =
  'a "use client" file may not import @/copy or Sheet.tsx: take the words as props resolved by a server parent (SheetDialog for a sheet)';
const CLIENT_COPY_SYNTAX = [
  { selector: `${CLIENT_FILE} ImportDeclaration[importKind!="type"][source.value=${CLIENT_BANNED}]`, message: CLIENT_COPY_MESSAGE },
  { selector: `${CLIENT_FILE} ImportExpression[source.value=${CLIENT_BANNED}]`, message: CLIENT_COPY_MESSAGE },
  { selector: `${CLIENT_FILE} ExportNamedDeclaration[exportKind!="type"][source.value=${CLIENT_BANNED}]`, message: CLIENT_COPY_MESSAGE },
  { selector: `${CLIENT_FILE} ExportAllDeclaration[source.value=${CLIENT_BANNED}]`, message: CLIENT_COPY_MESSAGE },
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
      "no-restricted-syntax": ["error", ...GENERATED_COPY_SYNTAX, ...SAFETY_LITERAL_SYNTAX, ...CLIENT_COPY_SYNTAX],
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
    // The one place a safety line may be named. An override replaces the rule's options, so the generated-copy and
    // client-file syntax bans are repeated here or they would lift.
    files: ["src/components/safety/**"],
    rules: {
      "no-restricted-syntax": ["error", ...GENERATED_COPY_SYNTAX, ...CLIENT_COPY_SYNTAX],
    },
  },
];

export default config;
