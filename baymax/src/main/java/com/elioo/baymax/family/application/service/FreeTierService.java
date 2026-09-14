package com.elioo.baymax.family.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.family.application.port.in.FreeTierUseCase;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

/** Free tier: {@code baymax.free.max-patients} profiles and {@code baymax.free.max-docs-per-month} documents per UTC calendar month. */
@Service
@RequiredArgsConstructor
public class FreeTierService implements FreeTierUseCase {

    private final HealthRecordPort records;
    private final DocumentRecordPort documents;
    private final BaymaxProperties properties;
    private final Clock clock;

    @Override
    public Mono<Void> checkCanAddPatient(FamilyAccount family) {
        if (!family.isFree()) {
            return Mono.empty();
        }
        int max = properties.getFree().getMaxPatients();
        return records.countPatients(family.id())
                .flatMap(count -> count >= max
                        ? Mono.error(new FreeTierExceededException(FreeTierExceededException.PATIENTS,
                                "free plan allows " + max + " patient profile(s)"))
                        : Mono.empty());
    }

    @Override
    public Mono<Void> checkCanUploadDocument(FamilyAccount family) {
        if (!family.isFree()) {
            return Mono.empty();
        }
        int max = properties.getFree().getMaxDocsPerMonth();
        YearMonth month = YearMonth.now(clock.withZone(ZoneOffset.UTC));
        // Counted from the document table (BMX-2); before it existed this came from the storage ledger.
        return documents.countInMonth(family.id(), month)
                .flatMap(count -> count >= max
                        ? Mono.error(new FreeTierExceededException(FreeTierExceededException.DOCUMENTS,
                                "free plan allows " + max + " document(s) per calendar month"))
                        : Mono.empty());
    }

    @Override
    public Mono<Void> checkCanUploadDocument(UUID familyId) {
        return records.findFamily(familyId)
                .switchIfEmpty(Mono.error(BaymaxException.notFound("family_not_found", "no family with id " + familyId)))
                .flatMap(this::checkCanUploadDocument);
    }
}
