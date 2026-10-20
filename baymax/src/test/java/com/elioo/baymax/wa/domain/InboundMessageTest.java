package com.elioo.baymax.wa.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** F9a's privacy rules on the row, in both directions: what must be kept is kept, what must not be is not. */
class InboundMessageTest {

    private static final String KEY = "a".repeat(64);
    private static final UUID FAMILY = UUID.randomUUID();

    @Test
    void aKnownFamilysTextIsKeptAndAnUnknownSendersIsNot() {
        assertThat(InboundMessage.received(KEY, FAMILY, "text", "আম্মার রিপোর্ট", null, null).body()).isEqualTo("আম্মার রিপোর্ট");
        assertThat(InboundMessage.received(KEY, null, "text", "আম্মার রিপোর্ট", null, null).body())
                .as("text from a number with no family cannot be deleted on request, so it is never stored").isNull();
    }

    /** A family that pastes its live login code back into WhatsApp must not leave it in a table (PR 12). */
    @ParameterizedTest
    @ValueSource(strings = {"483920", " 483920 ", "৪৮৩৯২০", "৪৮৩৯২০\n"})
    void aSixDigitCodeIsNeverStored(String typed) {
        assertThat(InboundMessage.received(KEY, FAMILY, "text", typed, null, null).body()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"আম্মার বয়স ৬৮", "HbA1c 6.5", "4839201", "48392", "কোড"})
    void textThatIsNotASixDigitCodeIsKept(String typed) {
        assertThat(InboundMessage.received(KEY, FAMILY, "text", typed, null, null).body()).isEqualTo(typed);
    }

    @Test
    void anEmptyTextIsNotStoredAsABody() {
        assertThat(InboundMessage.received(KEY, FAMILY, "image", "", "MEDIA-1", null).body()).isNull();
    }

    @Test
    void aTextLongerThanWhatsAppAllowsIsCutToTheLimit() {
        String longer = "ক".repeat(InboundMessage.MAX_BODY + 10);
        assertThat(InboundMessage.received(KEY, FAMILY, "text", longer, null, null).body()).hasSize(InboundMessage.MAX_BODY);
    }
}
