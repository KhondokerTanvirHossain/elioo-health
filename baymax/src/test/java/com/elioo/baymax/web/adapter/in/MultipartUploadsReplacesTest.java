package com.elioo.baymax.web.adapter.in;

import org.junit.jupiter.api.Test;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * D1: the press's {@code replaces} field, read two ways. {@link MultipartUploads#replacesOrNull} is the HTML sheet's
 * lenient reading (anything that is not an id replaces nothing); {@link MultipartUploads#replacesMalformed} is what the
 * JSON route refuses as INVALID (present, not blank, not an id), so a client bug is never answered 200 and unlinked.
 */
class MultipartUploadsReplacesTest {

    private static final UUID PAPER = UUID.fromString("5e9edf6d-0a2a-4913-8675-9d07a4210009");

    private static MultiValueMap<String, Part> with(String value) {
        MultiValueMap<String, Part> parts = new LinkedMultiValueMap<>();
        if (value != null) {
            FormFieldPart field = mock(FormFieldPart.class);
            when(field.name()).thenReturn("replaces");
            when(field.value()).thenReturn(value);
            parts.add("replaces", field);
        }
        return parts;
    }

    @Test
    void anAbsentOrBlankReplacesNamesNothingAndIsNotMalformed() {
        for (String none : new String[]{null, "", "   "}) {
            assertThat(MultipartUploads.replacesOrNull(with(none))).as("replaces=[%s]", none).isNull();
            assertThat(MultipartUploads.replacesMalformed(with(none))).as("replaces=[%s]", none).isFalse();
        }
    }

    @Test
    void anIdIsReadTrimmedAndIsNotMalformed() {
        assertThat(MultipartUploads.replacesOrNull(with(PAPER.toString()))).isEqualTo(PAPER);
        assertThat(MultipartUploads.replacesOrNull(with("  " + PAPER + " "))).isEqualTo(PAPER);
        assertThat(MultipartUploads.replacesMalformed(with(PAPER.toString()))).isFalse();
    }

    /** The two readings part only here: the sheet reads garbage as nothing, the JSON route calls it malformed. */
    @Test
    void garbageIsNothingToTheSheetAndMalformedToTheApi() {
        for (String bad : new String[]{"undefined", "null", "5e9edf6d-0a2a-4913", "not an id"}) {
            assertThat(MultipartUploads.replacesOrNull(with(bad))).as("replaces=[%s]", bad).isNull();
            assertThat(MultipartUploads.replacesMalformed(with(bad))).as("replaces=[%s]", bad).isTrue();
        }
    }

    /** A file sent under the field's name is not a field: nothing, as {@code field} reads every other part. */
    @Test
    void aFileUnderTheNameIsNotAField() {
        MultiValueMap<String, Part> parts = new LinkedMultiValueMap<>();
        parts.add("replaces", mock(FilePart.class));
        assertThat(MultipartUploads.replacesOrNull(parts)).isNull();
        assertThat(MultipartUploads.replacesMalformed(parts)).isFalse();
    }
}
