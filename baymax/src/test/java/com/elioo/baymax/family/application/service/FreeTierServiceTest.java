package com.elioo.baymax.family.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.application.port.out.DocumentRecordPort;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.DocumentAllowance;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FreeTierServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T23:30:00Z"), ZoneOffset.UTC);

    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final DocumentRecordPort documents = mock(DocumentRecordPort.class);
    private final FreeTierService service = new FreeTierService(records, documents, new BaymaxProperties(), CLOCK);

    private static FamilyAccount family(FamilyAccount.Plan plan) {
        return new FamilyAccount(UUID.randomUUID(), "+8801700000000", "Owner", plan, CLOCK.instant(), CLOCK.instant());
    }

    @Test
    void freeFamilyWithOnePatientCannotAddAnother() {
        FamilyAccount family = family(FamilyAccount.Plan.FREE);
        when(records.countPatients(family.id())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.checkCanAddPatient(family))
                .expectErrorMatches(e -> e instanceof FreeTierExceededException f && f.reason().equals("free_tier_patients")
                        && f.status().value() == 402)
                .verify();
    }

    @Test
    void freeFamilyWithNoPatientMayAddOne() {
        FamilyAccount family = family(FamilyAccount.Plan.FREE);
        when(records.countPatients(family.id())).thenReturn(Mono.just(0L));

        StepVerifier.create(service.checkCanAddPatient(family)).verifyComplete();
    }

    @Test
    void familyPlanIsNotCounted() {
        FamilyAccount family = family(FamilyAccount.Plan.FAMILY);

        StepVerifier.create(service.checkCanAddPatient(family)).verifyComplete();
        StepVerifier.create(service.checkCanUploadDocument(family)).verifyComplete();
        verify(records, never()).countPatients(any());
        verify(documents, never()).countInMonth(any(), any());
    }

    @Test
    void threeDocumentsThisUtcMonthBlockTheFourth() {
        FamilyAccount family = family(FamilyAccount.Plan.FREE);
        when(documents.countInMonth(eq(family.id()), eq(YearMonth.of(2026, 9)))).thenReturn(Mono.just(3L));

        StepVerifier.create(service.checkCanUploadDocument(family))
                .expectErrorMatches(e -> e instanceof FreeTierExceededException f && f.reason().equals("free_tier_documents"))
                .verify();
    }

    @Test
    void twoDocumentsThisMonthAllowTheThird() {
        FamilyAccount family = family(FamilyAccount.Plan.FREE);
        when(documents.countInMonth(eq(family.id()), eq(YearMonth.of(2026, 9)))).thenReturn(Mono.just(2L));

        StepVerifier.create(service.checkCanUploadDocument(family)).verifyComplete();
    }

    @Test
    void byIdLoadsTheFamilyOr404s() {
        UUID missing = UUID.randomUUID();
        when(records.findFamily(missing)).thenReturn(Mono.empty());

        StepVerifier.create(service.checkCanUploadDocument(missing))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 404)
                .verify();
    }

    /**
     * The sheet's "আরও ৩টি" and intake's refusal come from one rule. Swept over every count from 0 to past the cap,
     * not two hand-picked cases: a sheet that promised one more while intake refused it would be the page lying.
     */
    @Test
    void theAllowanceTheSheetShowsAgreesWithTheRefusalAtEveryCount() {
        FamilyAccount family = family(FamilyAccount.Plan.FREE);
        int max = new BaymaxProperties().getFree().getMaxDocsPerMonth();
        int checked = 0;
        for (long used = 0; used <= max + 1; used++) {
            when(documents.countInMonth(eq(family.id()), eq(YearMonth.of(2026, 9)))).thenReturn(Mono.just(used));
            DocumentAllowance allowance = service.documentAllowance(family).block();
            Boolean refused = service.checkCanUploadDocument(family).thenReturn(false)
                    .onErrorResume(FreeTierExceededException.class, e -> Mono.just(true)).block();
            assertThat(allowance.allows(1)).as("used=%d", used).isEqualTo(!refused);
            assertThat(allowance.remaining()).as("used=%d", used).isEqualTo(Math.max(0, max - used));
            checked++;
        }
        assertThat(checked).isEqualTo(max + 2);   // the sweep ran; an empty loop agrees with anything
    }

    /** "আলাদা আলাদা" with three pages is three documents: refused when two remain, allowed when three do. */
    @Test
    void separatePagesCountAsSeparateDocumentsInBothDirections() {
        FamilyAccount family = family(FamilyAccount.Plan.FREE);
        when(documents.countInMonth(eq(family.id()), eq(YearMonth.of(2026, 9)))).thenReturn(Mono.just(1L));
        StepVerifier.create(service.checkCanUploadDocuments(family, 3))
                .expectErrorMatches(e -> e instanceof FreeTierExceededException f && f.reason().equals("free_tier_documents"))
                .verify();
        StepVerifier.create(service.checkCanUploadDocuments(family, 2)).verifyComplete();
    }

    @Test
    void theCapLiftsOnTheFirstOfTheNextUtcMonthAndAPayingFamilyHasNoCap() {
        FamilyAccount free = family(FamilyAccount.Plan.FREE);
        when(documents.countInMonth(eq(free.id()), eq(YearMonth.of(2026, 9)))).thenReturn(Mono.just(3L));
        assertThat(service.documentAllowance(free).block().resetsOn()).isEqualTo(LocalDate.of(2026, 10, 1));

        FamilyAccount paying = family(FamilyAccount.Plan.FAMILY);
        DocumentAllowance paid = service.documentAllowance(paying).block();
        assertThat(paid.limited()).isFalse();
        assertThat(paid.allows(10)).isTrue();
        verify(documents, never()).countInMonth(eq(paying.id()), any());   // a paying family is never counted
    }
}
