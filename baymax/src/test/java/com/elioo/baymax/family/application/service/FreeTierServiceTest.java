package com.elioo.baymax.family.application.service;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.common.error.FreeTierExceededException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FreeTierServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-13T23:30:00Z"), ZoneOffset.UTC);

    private final HealthRecordPort records = mock(HealthRecordPort.class);
    private final FreeTierService service = new FreeTierService(records, new BaymaxProperties(), CLOCK);

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
        verify(records, never()).countDocumentsInMonth(any(), any());
    }

    @Test
    void threeDocumentsThisUtcMonthBlockTheFourth() {
        FamilyAccount family = family(FamilyAccount.Plan.FREE);
        when(records.countDocumentsInMonth(eq(family.id()), eq(YearMonth.of(2026, 9)))).thenReturn(Mono.just(3L));

        StepVerifier.create(service.checkCanUploadDocument(family))
                .expectErrorMatches(e -> e instanceof FreeTierExceededException f && f.reason().equals("free_tier_documents"))
                .verify();
    }

    @Test
    void twoDocumentsThisMonthAllowTheThird() {
        FamilyAccount family = family(FamilyAccount.Plan.FREE);
        when(records.countDocumentsInMonth(eq(family.id()), eq(YearMonth.of(2026, 9)))).thenReturn(Mono.just(2L));

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
}
