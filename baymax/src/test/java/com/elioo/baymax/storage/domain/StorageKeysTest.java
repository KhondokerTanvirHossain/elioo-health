package com.elioo.baymax.storage.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageKeysTest {

    private final UUID family = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private final UUID patient = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private final UUID document = UUID.fromString("cccccccc-0000-0000-0000-000000000003");

    @Test
    void keysFollowTheTicketLayout() {
        String prefix = "aaaaaaaa-0000-0000-0000-000000000001/bbbbbbbb-0000-0000-0000-000000000002/cccccccc-0000-0000-0000-000000000003/";
        assertThat(StorageKeys.documentPrefix(family, patient, document)).isEqualTo(prefix);
        assertThat(StorageKeys.page(family, patient, document, 2)).isEqualTo(prefix + "page-2.jpg");
        assertThat(StorageKeys.crop(family, patient, document, "hba1c")).isEqualTo(prefix + "crop-hba1c.jpg");
        assertThat(StorageKeys.patientPrefix(family, patient)).isEqualTo(
                "aaaaaaaa-0000-0000-0000-000000000001/bbbbbbbb-0000-0000-0000-000000000002/");
        assertThat(StorageKeys.familyPrefix(family)).isEqualTo("aaaaaaaa-0000-0000-0000-000000000001/");
    }

    @Test
    void prefixesNestSoDeletingAParentCoversItsChildren() {
        assertThat(StorageKeys.page(family, patient, document, 1)).startsWith(StorageKeys.documentPrefix(family, patient, document));
        assertThat(StorageKeys.documentPrefix(family, patient, document)).startsWith(StorageKeys.patientPrefix(family, patient));
        assertThat(StorageKeys.patientPrefix(family, patient)).startsWith(StorageKeys.familyPrefix(family));
    }

    @Test
    void rejectsBadInput() {
        assertThatThrownBy(() -> StorageKeys.page(family, patient, document, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.crop(family, patient, document, "../x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.crop(family, patient, document, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StorageKeys.familyPrefix(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
