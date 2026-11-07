/**
 * Text Java wrote for WhatsApp, as the desktop conversation shows it: every line that holds an http(s) link removed,
 * nothing else changed (the remaining lines and their breaks as written). The same rule, character for character, as
 * Java's ThreadJson.webBody, which gives a message its `web_body`; web-text.test.ts holds this function to Java's own
 * output for every message of the thread fixtures. The desktop applies it to the one other WhatsApp text the
 * conversation shows, a card's released `wording` (the "kept in your timeline: https://…" line), so no raw link is shown.
 */
export function webText(text: string): string {
  return text
    .split("\n")
    .filter((line) => !line.includes("http://") && !line.includes("https://"))
    .join("\n");
}
