import { cleanup, fireEvent, render, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { banglaDigits, t } from "@/copy";
import { OtpInput } from "./OtpInput";

afterEach(cleanup);

const describedTexts = (container: HTMLElement, input: HTMLInputElement) =>
  (input.getAttribute("aria-describedby") ?? "")
    .split(" ")
    .filter(Boolean)
    .map((id) => container.querySelector(`[id="${id}"]`)?.textContent);

describe("OtpInput", () => {
  it("oneFieldForSixDigitsWithOneTimeCode", () => {
    for (const lang of ["bn", "en"] as const) {
      const { container } = render(<OtpInput length={6} lang={lang} name="code" />);
      // One field, not six boxes: a screen reader reads one labelled field, and a pasted or autofilled code lands whole.
      const inputs = container.querySelectorAll("input");
      expect(inputs.length, `${lang}: inputs`).toBe(1);
      const input = inputs[0]!;
      const attrs = Object.fromEntries(
        ["inputmode", "autocomplete", "maxlength", "pattern", "name", "type"].map((a) => [a, input.getAttribute(a)]),
      );
      expect(attrs, lang).toEqual({
        inputmode: "numeric",
        autocomplete: "one-time-code",
        maxlength: "6",
        // The HTML verify page's own pattern (SignInPages.verifyPage: ASCII digits or Bangla zero to nine, six of
        // them), pinned by code point (U+09E6 BENGALI DIGIT ZERO to U+09EF NINE), so no Bangla literal is typed and
        // the expectation is not built by the expression under test (banglaDigits).
        pattern: `[0-9${String.fromCodePoint(0x09e6)}-${String.fromCodePoint(0x09ef)}]{6}`,
        name: "code",
        type: "text",
      });
      expect(input.classList.contains("otp-input"), `${lang}: the input itself carries .otp-input`).toBe(true);

      // Browsers compile `pattern` with the v flag, as the HTML spec says, and drop a pattern that is invalid under it,
      // which then validates nothing at all. So the pattern must compile under v.
      expect(() => new RegExp(`^(?:${attrs.pattern})$`, "v"), `${lang}: valid under the v flag`).not.toThrow();

      // What the element itself enforces (jsdom compiles the attribute with v, as the spec does): six digits of either
      // script, nothing shorter, nothing that is not a digit.
      const verdicts = ["123456", banglaDigits("123456"), "12345", "12345a"].map((value) => {
        input.value = value;
        return { value, patternMismatch: input.validity.patternMismatch, valid: input.checkValidity() };
      });
      expect(verdicts, lang).toEqual([
        { value: "123456", patternMismatch: false, valid: true },
        { value: banglaDigits("123456"), patternMismatch: false, valid: true },
        { value: "12345", patternMismatch: true, valid: false },
        { value: "12345a", patternMismatch: true, valid: false },
      ]);
      input.value = "";

      // Labelled by the sign-in page's own key, through for/id, so the label is the field's accessible name.
      const label = container.querySelector(`label[for="${input.id}"]`);
      expect(input.id, `${lang}: the input has an id`).not.toBe("");
      expect(label?.textContent, `${lang}: label`).toBe(t(lang, "login.code"));

      // A typed code stays as typed: nothing in the browser rewrites it (Java parses the digits).
      fireEvent.input(input, { target: { value: banglaDigits("042917") } });
      expect(input.value).toBe(banglaDigits("042917"));
      cleanup();
    }
  });

  it("twoFieldsOnOnePageHaveTheirOwnIds", () => {
    const { container } = render(
      <>
        <OtpInput length={6} lang="bn" name="code" />
        <OtpInput length={6} lang="en" name="code" />
      </>,
    );
    const ids = [...container.querySelectorAll("input")].map((i) => i.id);
    expect(ids.length).toBe(2);
    expect(new Set(ids).size, "distinct ids").toBe(2);
  });

  it("theTtlHintDescribesTheField", () => {
    for (const lang of ["bn", "en"] as const) {
      const { container } = render(<OtpInput length={6} lang={lang} name="code" ttlMinutes={10} />);
      const input = container.querySelector("input")!;
      // As the verify page writes it under the field: login.code.ttl with the code's lifetime in minutes.
      expect(describedTexts(container, input), lang).toEqual([t(lang, "login.code.ttl", 10)]);
      expect(input.hasAttribute("aria-invalid"), `${lang}: valid at rest`).toBe(false);
      cleanup();
    }
  });

  it("aRefusedCodeIsSaidAndMarked", () => {
    for (const lang of ["bn", "en"] as const) {
      for (const [error, words] of [
        [{ key: "login.bad.wrong" }, t(lang, "login.bad.wrong")],
        [{ key: "login.bad.expired" }, t(lang, "login.bad.expired")],
        [{ key: "login.bad.format" }, t(lang, "login.bad.format")],
        // BURNED says how many wrong tries it took, as SignInPages.refusal does.
        [{ key: "login.bad.burned", attempts: 5 }, t(lang, "login.bad.burned", 5)],
      ] as const) {
        const { container } = render(<OtpInput length={6} lang={lang} name="code" ttlMinutes={10} error={error} />);
        const input = container.querySelector("input")!;
        expect(input.getAttribute("aria-invalid"), `${lang} ${error.key}`).toBe("true");
        // The refusal first, then the lifetime: both read, the refusal never in place of the hint.
        expect(describedTexts(container, input), `${lang} ${error.key}`).toEqual([words, t(lang, "login.code.ttl", 10)]);
        // Said aloud when it appears, as the verify page says it (`<div class="err" role="alert">`).
        expect(within(container).getByRole("alert").textContent, `${lang} ${error.key}: the alert`).toBe(words);
        cleanup();
      }
    }
  });

  it("anErrorIsACodeRefusalNeverOtherCopy", () => {
    // Checked by `pnpm typecheck`: the error line is one of the verify page's refusals, and BURNED carries its count.
    // If either widens, its directive is unused and fails.
    // @ts-expect-error login.hint is copy, not a code refusal
    const widened = <OtpInput length={6} lang="en" name="code" error={{ key: "login.hint" }} />;
    // @ts-expect-error BURNED says how many wrong tries it took
    const uncounted = <OtpInput length={6} lang="en" name="code" error={{ key: "login.bad.burned" }} />;
    expect([widened.props.error.key, uncounted.props.error.key]).toEqual(["login.hint", "login.bad.burned"]);
  });

  it("theAlertExistsBeforeTheRefusalArrives", () => {
    // The code screen sets `error` after the verify fetch returns, into a page already open: the live region is there,
    // empty, before the refusal, because it announces a change to its content and not its own arrival.
    for (const lang of ["bn", "en"] as const) {
      const { container, rerender } = render(<OtpInput length={6} lang={lang} name="code" />);
      const before = within(container).getByRole("alert");
      expect(before.textContent, `${lang}: empty at rest`).toBe("");
      const input = container.querySelector("input")!;
      expect(input.hasAttribute("aria-describedby"), `${lang}: nothing described at rest`).toBe(false);

      rerender(<OtpInput length={6} lang={lang} name="code" error={{ key: "login.bad.wrong" }} />);
      const after = within(container).getByRole("alert");
      expect(after, `${lang}: the same live region, filled`).toBe(before);
      expect(after.textContent, `${lang}: the refusal`).toBe(t(lang, "login.bad.wrong"));
      expect(input.getAttribute("aria-describedby"), `${lang}: described by the refusal`).toBe(after.id);
      cleanup();
    }
  });
});
