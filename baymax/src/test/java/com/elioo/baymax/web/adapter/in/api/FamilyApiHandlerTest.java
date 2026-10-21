package com.elioo.baymax.web.adapter.in.api;

import com.elioo.baymax.common.error.BaymaxException;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.nudge.application.port.in.NudgeOptOutUseCase;
import com.elioo.baymax.nudge.application.port.out.NudgeDataPort;
import com.elioo.baymax.web.adapter.in.router.SessionAuthFilter;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.web.application.port.in.TimelineUseCase;
import com.elioo.baymax.web.domain.FamilyOverview;
import com.elioo.baymax.web.domain.PatientSummary;
import com.elioo.baymax.web.domain.WebSession;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.reactive.function.server.MockServerRequest;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Plan B5, the handler's one decision the contract test cannot reach on a real database: a patient the family sees
 * whose nudge row is missing (deleted between {@code timeline.me} and the read, or a JOIN changed later). Its
 * reminders are unknown, and unknown is never said as "on" — the dishonest direction for a stop the family asked for.
 */
class FamilyApiHandlerTest {

    private static final Instant AT = Instant.parse("2026-09-14T04:00:00Z");

    @Test
    void aPatientWithNoNudgeRowIsNeverSaidToHaveRemindersOn() {
        UUID familyId = UUID.randomUUID();
        PatientProfile ma = new PatientProfile(UUID.randomUUID(), familyId, "Ma", 64, PatientProfile.Sex.FEMALE, List.of(), AT, AT);
        FamilyAccount account = new FamilyAccount(familyId, "+8801700000599", "Tanvir", FamilyAccount.Plan.FAMILY, AT, AT);
        TimelineUseCase timeline = mock(TimelineUseCase.class);
        NudgeDataPort nudgeData = mock(NudgeDataPort.class);
        NudgeOptOutUseCase optOut = mock(NudgeOptOutUseCase.class);
        when(timeline.me(familyId)).thenReturn(Mono.just(new FamilyOverview(account, List.of(new PatientSummary(ma, true, 0, null)))));
        when(nudgeData.patient(any())).thenReturn(Mono.empty());
        when(optOut.familyOptedOut(familyId)).thenReturn(Mono.just(false));
        FamilyApiHandler handler = new FamilyApiHandler(timeline, nudgeData, optOut, new UiCopy(), new BaymaxProperties());

        MockServerRequest request = MockServerRequest.builder()
                .attribute(SessionAuthFilter.SESSION_ATTRIBUTE, new WebSession(UUID.randomUUID(), familyId, AT, AT, AT.plusSeconds(3600)))
                .build();
        StepVerifier.create(handler.family(request))
                .expectErrorMatches(e -> e instanceof BaymaxException b && b.status().value() == 404 && "patient_not_found".equals(b.reason()))
                .verify();
    }
}
