package com.elioo.baymax.web.domain;

/**
 * Spec §4.8 (s08): what several files in one press of "পাঠান" are. The family chooses; Medioo never matches pages itself.
 * {@link #ONE} is the sheet's default, and what every caller without the question (the JSON API, the legacy form) means.
 */
public enum Grouping {
    /** "একটা রিপোর্ট": every file is a page of one document. */
    ONE,
    /** "আলাদা আলাদা": each file is its own document, and each takes a slot. */
    SEPARATE
}
