/**
 * How often a reading page asks again, in seconds (DocumentPages.POLL_SECONDS): ReadingProgress's status poll with
 * JavaScript, and the reading page's `<noscript>` reload without it (PO ruling P6). No directive, so the server page
 * and the client island read the one number.
 */
export const POLL_SECONDS = 5;
