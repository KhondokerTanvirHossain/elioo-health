/*
 * The two numbers the composer shares with Java's chat (plan CHAT-web), in a plain module so a server file can read them
 * too (a value imported from a "use client" module is a client reference on the server, never the value).
 */

/**
 * How long the composer waits for Java's answer before it shows `reply`. Java ends the whole request by its answer
 * deadline (`baymax.chat.answer-deadline`, 45 s, counted from the request) plus its store's grace (3 s), and refuses at
 * startup a deadline this wait could not cover (ChatPropertiesCheck.WEB_ASK_TIMEOUT, held equal to this by Java's
 * ChatModelConfigurationTest; ask-limits.test.ts reads the shipped deadline). So no answer is stored after the family
 * was shown `reply`. A late answer after it is dropped (apiSend resolves `offline` at the timeout).
 */
export const ASK_TIMEOUT_MS = 50_000;

/** The longest question Java answers (`baymax.chat.question-max-chars`); a longer one is its 400 (`tooLong`). */
export const QUESTION_MAX_CHARS = 1000;
