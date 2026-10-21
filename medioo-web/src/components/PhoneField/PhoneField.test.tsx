import { cleanup, fireEvent, render } from "@testing-library/react";
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
      cleanup();
    }
  });
});
