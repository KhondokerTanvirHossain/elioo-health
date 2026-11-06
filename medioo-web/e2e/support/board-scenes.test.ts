import { expect, test } from "vitest";
import { BOARD_SCENES, VIEWPORTS, protoFor } from "./board-scenes";

// Desktop follows desktop.html (plan 2026-11-07, Global Constraints): a capture at 900 px or wider beside a phone.html
// scene judges the desktop layout against the wrong design, and every board before this one did exactly that.
test("every capture at 900 px or wider is compared with desktop.html", () => {
  const wrong = BOARD_SCENES.flatMap((s) =>
    Object.entries(s.viewports ?? VIEWPORTS)
      .filter(([, v]) => v.width >= 900 && protoFor(s, v.width).file !== "desktop.html")
      .map(([name]) => `${s.screen}/${s.state}@${name}`),
  );
  expect(wrong).toEqual([]);
});

test("the guard is not vacuous", () => {
  expect(BOARD_SCENES.some((s) => Object.values(s.viewports ?? VIEWPORTS).some((v) => v.width >= 900))).toBe(true);
});

// The other direction: below 900 px Medioo is still the phone layout, so a phone capture keeps its own scene.
test("every capture under 900 px keeps the scene it was built from", () => {
  const moved = BOARD_SCENES.flatMap((s) =>
    Object.entries(s.viewports ?? VIEWPORTS)
      .filter(([, v]) => v.width < 900)
      .filter(([, v]) => {
        const p = protoFor(s, v.width);
        return p.file !== s.proto.file || p.scene !== s.proto.scene || p.act !== s.proto.act || p.click !== s.proto.click;
      })
      .map(([name]) => `${s.screen}/${s.state}@${name}`),
  );
  expect(moved).toEqual([]);
  expect(BOARD_SCENES.some((s) => s.proto.file === "phone.html" && Object.values(s.viewports ?? VIEWPORTS).some((v) => v.width < 900))).toBe(true);
});
