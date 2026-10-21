import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import { MessageBubble } from "./MessageBubble";

afterEach(cleanup);

describe("MessageBubble", () => {
  it("theSurfacePicksTheClass", () => {
    for (const surface of ["recv", "sent"] as const) {
      const { container } = render(
        <MessageBubble surface={surface}>
          <b>one</b> two
        </MessageBubble>,
      );
      const bubble = container.firstElementChild!;
      expect(bubble.classList.contains("message-bubble"), surface).toBe(true);
      expect(bubble.classList.contains(`message-bubble--${surface}`), surface).toBe(true);
      expect(bubble.classList.contains(`message-bubble--${surface === "recv" ? "sent" : "recv"}`), surface).toBe(false);
      // Children verbatim: the element and the text, in order, nothing added around them.
      expect(bubble.querySelector(".message-bubble__body")!.innerHTML).toBe("<b>one</b> two");
      cleanup();
    }
  });

  it("theTailIsAskedFor", () => {
    const tail = render(<MessageBubble surface="recv" tail>x</MessageBubble>).container.firstElementChild!;
    expect(tail.classList.contains("message-bubble--tail")).toBe(true);
    cleanup();
    const none = render(<MessageBubble surface="recv">x</MessageBubble>).container.firstElementChild!;
    expect(none.classList.contains("message-bubble--tail")).toBe(false);
  });
});
