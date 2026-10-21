import { cleanup, fireEvent, render, within } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { t } from "@/copy";
import { PhoneField } from "./PhoneField";

afterEach(cleanup);

describe("PhoneField", () => {
  it("aTelFieldAndNoParsing", () => {
    for (const lang of ["bn", "en"] as const) {
      const { container } = render(<PhoneField lang={lang} name="whatsapp_number" />);
      const inputs = container.querySelectorAll("input");
      expect(inputs.length, `${lang}: inputs`).toBe(1);
      const input = inputs[0]!;
      expect(
        Object.fromEntries(["type", "inputmode", "autocomplete", "name"].map((a) => [a, input.getAttribute(a)])),
        lang,
      ).toEqual({ type: "tel", inputmode: "tel", autocomplete: "tel", name: "whatsapp_number" });
      expect(input.classList.contains("phone-field__input"), `${lang}: the input itself carries .phone-field__input`).toBe(true);
      // Nothing parsed or constrained in the browser: Java's PhoneDisplay.normalise decides what a number is.
      expect(input.hasAttribute("pattern"), `${lang}: pattern`).toBe(false);
      expect(input.hasAttribute("maxlength"), `${lang}: maxlength`).toBe(false);

      // Typed with a country code, a space and a dash, it stays exactly as typed.
      fireEvent.input(input, { target: { value: "+880 1700-000001" } });
      expect(input.value, `${lang}: value as typed`).toBe("+880 1700-000001");

      // The sign-in page's label and hint keys: the label names the field, the hint describes it.
      expect(container.querySelector(`label[for="${input.id}"]`)?.textContent, `${lang}: label`).toBe(t(lang, "login.number"));
      const described = (input.getAttribute("aria-describedby") ?? "").split(" ").filter(Boolean);
      const texts = described.map((id) => container.querySelector(`[id="${id}"]`)?.textContent);
      expect(texts, `${lang}: described by`).toEqual([t(lang, "login.hint")]);
      expect(input.hasAttribute("aria-invalid"), `${lang}: valid at rest`).toBe(false);
      cleanup();
    }
  });

  it("anErrorIsSaidAndMarked", () => {
    for (const lang of ["bn", "en"] as const) {
      const { container } = render(<PhoneField lang={lang} name="whatsapp_number" error="login.number.invalid" />);
      const input = container.querySelector("input")!;
      expect(input.getAttribute("aria-invalid"), lang).toBe("true");
      const described = (input.getAttribute("aria-describedby") ?? "").split(" ").filter(Boolean);
      const texts = described.map((id) => container.querySelector(`[id="${id}"]`)?.textContent);
      // The error first, then the hint: both read, the error never in place of the hint.
      expect(texts, `${lang}: described by`).toEqual([t(lang, "login.number.invalid"), t(lang, "login.hint")]);
      // Said aloud when it appears, as the HTML page says it (SignInPages.numberPage, `<div class="err" role="alert">`):
      // after a submit focus sits on the button, so aria-describedby alone is never read.
      expect(within(container).getByRole("alert").textContent, `${lang}: the alert`).toBe(t(lang, "login.number.invalid"));
      cleanup();
    }
  });

  it("anErrorIsASignInRefusalNeverOtherCopy", () => {
    // Checked by `pnpm typecheck`: the error line under a field is a refusal a number comes back with, never any copy
    // key (a hint, a NOW sentence, a medicine). If `error` widens back to CopyKey this directive is unused and fails.
    // @ts-expect-error login.hint is copy, not a refusal
    const widened = <PhoneField lang="en" name="whatsapp_number" error="login.hint" />;
    expect(widened.props.error).toBe("login.hint");
  });

  it("theAlertExistsBeforeTheRefusalArrives", () => {
    // A React screen sets `error` after the fetch returns INVALID_NUMBER, into a page that is already open. A live
    // region announces a change to its content, not its own arrival, so it is in the page, empty, before the refusal.
    for (const lang of ["bn", "en"] as const) {
      const { container, rerender } = render(<PhoneField lang={lang} name="whatsapp_number" />);
      const before = within(container).getByRole("alert");
      expect(before.textContent, `${lang}: empty at rest`).toBe("");
      const input = container.querySelector("input")!;
      // An empty alert is not a description: the hint alone is read at rest.
      expect(input.getAttribute("aria-describedby")?.split(" "), `${lang}: described by at rest`).not.toContain(before.id);

      rerender(<PhoneField lang={lang} name="whatsapp_number" error="login.number.invalid" />);
      const after = within(container).getByRole("alert");
      expect(after, `${lang}: the same live region, filled`).toBe(before);
      expect(after.textContent, `${lang}: the refusal`).toBe(t(lang, "login.number.invalid"));
      expect(input.getAttribute("aria-describedby")?.split(" ")[0], `${lang}: the error is read first`).toBe(after.id);
      cleanup();
    }
  });
});
