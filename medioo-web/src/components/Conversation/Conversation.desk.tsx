"use client";

export { OpenAtNewest } from "@/app/app/(signed-in)/home/OpenAtNewest";
export { Composer } from "@/components/Composer/Composer";
export { TipsSheet } from "./TipsSheet";
export { FollowPaper } from "./FollowPaper";
// After the composer's and the card's own stylesheets, as when Conversation imported them directly.
import "@/components/Composer/Composer.desk.css";
import "@/components/safety/VerdictCard/VerdictCard.desk.css";
import "./Conversation.css";

/** The conversation's stylesheets, loaded by desk-load.tsx with its islands where the conversation renders. It draws nothing. */
export default function ConversationStyles() {
  return null;
}
