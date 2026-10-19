package com.elioo.baymax.web.application.port.in;

import com.elioo.baymax.extraction.domain.Document;
import com.elioo.baymax.extraction.domain.Upload;
import com.elioo.baymax.web.domain.UploadOptions;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/** Adding papers on the web: what the sheet may offer, and the upload itself (A7); "আলাদা আলাদা" and the key (D3). */
public interface UploadUseCase {

    Mono<UploadOptions> uploadOptions(UUID familyId);

    /** 404 for a patient this family cannot see, 403 for one it may only see (share member, D30). */
    Mono<Document> upload(UUID familyId, UUID patientId, List<Upload> uploads);
}
