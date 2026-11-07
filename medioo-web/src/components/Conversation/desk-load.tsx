"use client";

import dynamic from "next/dynamic";

/*
 * The conversation's loader (owner decision 2026-11-07, option a; DR-42): its stylesheet (with the desk additions of
 * the composer and the card, Composer.desk.css and VerdictCard.desk.css) and its client islands (Composer, OpenAtNewest,
 * FollowPaper, TipsSheet) come through next/dynamic from Conversation.desk, so Next links them only on a page that renders the
 * conversation (the desktop layout), never on a live page: the paper page, which has no composer, does not carry it.
 * The chat page keeps its own composer, imported as before. All of it is one chunk (one module).
 * desktop-chunks.built.test.ts holds that.
 */
export const ConversationStyles = dynamic(() => import("./Conversation.desk"));
export const Composer = dynamic(() => import("./Conversation.desk").then((m) => m.Composer));
export const OpenAtNewest = dynamic(() => import("./Conversation.desk").then((m) => m.OpenAtNewest));
export const FollowPaper = dynamic(() => import("./Conversation.desk").then((m) => m.FollowPaper));
export const TipsSheet = dynamic(() => import("./Conversation.desk").then((m) => m.TipsSheet));
