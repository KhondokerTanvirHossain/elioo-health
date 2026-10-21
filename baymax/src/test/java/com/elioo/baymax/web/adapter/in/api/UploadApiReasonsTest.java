package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.testsupport.UploadRefusalSweep;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan B4b step 1: every reason the upload path can be refused for has an {@link ApiReason} name, and each name keeps
 * the status its reason is thrown with. The reasons are read from the source ({@link UploadRefusalSweep}), not listed
 * here by hand, so a refusal added to intake later fails this test until it is named.
 */
class UploadApiReasonsTest {

    @Test
    void everyReasonTheUploadPathCanThrowHasAName() throws Exception {
        Map<String, Integer> swept = UploadRefusalSweep.reasonsWithStatus();
        assertThat(swept).as("the sweep found the refusals; an empty set would pass vacuously").hasSizeGreaterThanOrEqualTo(10);
        // MultipartUploads throws these and mentions no UploadUseCase: swept only because the sweep names the file
        assertThat(swept).as("the size refusals were swept").containsKeys("file_too_large", "request_too_large");
        Map<String, Integer> reasons = new TreeMap<>(swept);
        reasons.put(FreeTierExceededException.DOCUMENTS, 402);
        assertThat(reasons.keySet()).as("reasons the upload path throws that have no ApiReason name")
                .allSatisfy(r -> assertThat(ApiReason.BY_REASON).as(r).containsKey(r));
    }

    @Test
    void eachNameKeepsTheStatusItsReasonIsThrownWith() throws Exception {
        Map<String, Integer> swept = UploadRefusalSweep.reasonsWithStatus();
        assertThat(swept).hasSizeGreaterThanOrEqualTo(10);
        swept.forEach((reason, status) -> assertThat(ApiReason.BY_REASON.get(reason).status().value())
                .as(reason + " → " + ApiReason.BY_REASON.get(reason)).isEqualTo(status));
        assertThat(ApiReason.BY_REASON.get(FreeTierExceededException.DOCUMENTS)).isEqualTo(ApiReason.FREE_TIER_DOCUMENTS);
        assertThat(ApiReason.FREE_TIER_DOCUMENTS.status().value()).isEqualTo(402);
    }
}
