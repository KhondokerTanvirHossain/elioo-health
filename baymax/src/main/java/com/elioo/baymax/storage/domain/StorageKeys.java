package com.elioo.baymax.storage.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Object keys: {@code {family_id}/{patient_id}/{document_id}/page-{n}.jpg} and
 * {@code .../crop-{item_id}.jpg}. Ids are UUIDs so that no phone number or name ever appears in a key;
 * the prefixes are what delete-on-request removes (document, patient or whole family).
 */
public final class StorageKeys {

    private static final Pattern ITEM_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private StorageKeys() {
    }

    public static String familyPrefix(UUID familyId) {
        return req(familyId, "familyId") + "/";
    }

    public static String patientPrefix(UUID familyId, UUID patientId) {
        return familyPrefix(familyId) + req(patientId, "patientId") + "/";
    }

    public static String documentPrefix(UUID familyId, UUID patientId, UUID documentId) {
        return patientPrefix(familyId, patientId) + req(documentId, "documentId") + "/";
    }

    public static String page(UUID familyId, UUID patientId, UUID documentId, int pageNo) {
        if (pageNo < 1) {
            throw new IllegalArgumentException("pageNo must be >= 1, got " + pageNo);
        }
        return documentPrefix(familyId, patientId, documentId) + "page-" + pageNo + ".jpg";
    }

    public static String crop(UUID familyId, UUID patientId, UUID documentId, String itemId) {
        if (itemId == null || !ITEM_ID.matcher(itemId).matches()) {
            throw new IllegalArgumentException("itemId must match " + ITEM_ID.pattern());
        }
        return documentPrefix(familyId, patientId, documentId) + "crop-" + itemId + ".jpg";
    }

    private static UUID req(UUID id, String name) {
        if (id == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return id;
    }
}
