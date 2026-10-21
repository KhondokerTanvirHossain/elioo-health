import type { ReactNode } from "react";
import "./MessageBubble.css";

/**
 * One line of the conversation, in the Apple Messages grammar the PO approved (direction contract, amendment
 * 2026-10-21): `recv` is Medioo's grey bubble on the left, `sent` the family's teal bubble on the right. `tail` draws
 * the curl under the last bubble of a run. Children are rendered as given; the bubble adds nothing to them.
 */
export function MessageBubble({ surface, children, tail }: { surface: "recv" | "sent"; children: ReactNode; tail?: boolean }) {
  const className = ["message-bubble", `message-bubble--${surface}`];
  if (tail) className.push("message-bubble--tail");
  return (
    <div className={className.join(" ")}>
      <div className="message-bubble__body">{children}</div>
    </div>
  );
}
