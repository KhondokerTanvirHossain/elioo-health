package com.elioo.baymax.storage.domain;

import java.util.Locale;

/** What a stored object is: a full page image, or a crop shown to the family as proof of one extracted value. */
public enum ObjectKind {
    PAGE, CROP;

    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static ObjectKind fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
