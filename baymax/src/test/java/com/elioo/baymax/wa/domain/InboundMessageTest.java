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

    /**
     * The rule protects the code, not the one form first tested: a code with a word in front of it, or the whole
     * wa.login_code message forwarded or quoted back, carries the same live code.
     */
    @ParameterizedTest
    @ValueSource(strings = {"কোড 483920", "code: ৪৮৩৯২০", "483920 এটা কি ঠিক?", "কোড:৪৮৩৯২০।"})
    void textCarryingAStandaloneSixDigitRunIsNeverStored(String typed) {
        assertThat(InboundMessage.received(KEY, FAMILY, "text", typed, null, null).body()).as(typed).isNull();
    }

    @Test
    void theLoginCodeMessageForwardedBackIsNeverStored() {
        String rendered = new com.elioo.baymax.outbound.application.service.Copy()
                .bn("wa.login_code", java.util.Map.of("code", "483920", "minutes", "10"));
        assertThat(rendered).as("the fixture really is the login-code message with a code in it").contains("483920");
        assertThat(InboundMessage.received(KEY, FAMILY, "text", rendered, null, null).body()).isNull();
        assertThat(InboundMessage.received(KEY, FAMILY, "text", "> " + rendered.replace("\n", "\n> "), null, null).body())
                .as("quoted").isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"আম্মার বয়স ৬৮", "HbA1c 6.5", "4839201", "48392", "কোড", "কোড 4839201", "১২৩৪৫ ও ৬৭৮৯০"})
    void textThatIsNotASixDigitCodeIsKept(String typed) {
        assertThat(InboundMessage.received(KEY, FAMILY, "text", typed, null, null).body()).isEqualTo(typed);
    }

    /**
     * A media id is a handle on that person's photo at Meta for as long as Meta keeps it. A row with no family can never
     * be deleted on request, so it keeps no handle either; a family's is kept (it is deleted with the family).
     */
    @Test
    void aKnownFamilysMediaIdIsKeptAndAnUnknownSendersIsNot() {
        assertThat(InboundMessage.received(KEY, FAMILY, "image", null, "MEDIA-1", null).mediaId()).isEqualTo("MEDIA-1");
        assertThat(InboundMessage.received(KEY, null, "image", null, "MEDIA-1", null).mediaId()).isNull();
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
