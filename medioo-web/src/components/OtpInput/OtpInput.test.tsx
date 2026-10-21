import { cleanup, fireEvent, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { banglaDigits, t } from "@/copy";
import { OtpInput } from "./OtpInput";

afterEach(cleanup);

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
        // ASCII or Bangla digits, as the HTML sign-in page accepts (SignInPages.verifyPage); Java reads either.
        pattern: `[0-9${banglaDigits("0")}-${banglaDigits("9")}]{6}`,
        name: "code",
        type: "text",
      });
      expect(input.classList.contains("otp-input"), `${lang}: the input itself carries .otp-input`).toBe(true);

      // The pattern says what it is meant to: six digits of either script, nothing shorter.
      const pattern = new RegExp(`^(?:${attrs.pattern})$`, "u");
      expect(pattern.test("123456")).toBe(true);
      expect(pattern.test(banglaDigits("123456"))).toBe(true);
      expect(pattern.test("12345")).toBe(false);
      expect(pattern.test("12345a")).toBe(false);

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
});
