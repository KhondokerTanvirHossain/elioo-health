package com.elioo.baymax.web.application.port.in;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.web.domain.Grouping;
import com.elioo.baymax.web.domain.UploadOptions;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Adding papers on the web: what the sheet may offer, and the upload itself (A7); "আলাদা আলাদা" and the key (D3). */
public interface UploadUseCase {

    Mono<UploadOptions> uploadOptions(UUID familyId);

    /** 404 for a patient this family cannot see, 403 for one it may only see (share member, D30). */
    Mono<Document> upload(UUID familyId, UUID patientId, List<Upload> uploads);

    /**
     * D3: one press of "পাঠান" on the sheet. {@link Grouping#ONE} is one document of every file; {@link Grouping#SEPARATE}
     * is one document per file, all or nothing before any exists. A retry with the same {@code uploadKey} and the same
     * files returns the documents the first press made and records no second upload; other files under the key are a new
     * press. A null key is never a replay. The same 404/403 rules as the three-argument form.
     */
    Mono<List<Document>> upload(UUID familyId, UUID patientId, List<Upload> uploads, Grouping grouping, String uploadKey);
}
