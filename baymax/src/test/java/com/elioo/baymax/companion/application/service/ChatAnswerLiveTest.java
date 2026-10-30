package com.elioo.baymax.companion.application.service;

import com.elioo.baymax.aicall.application.port.out.AiCallLogPort;
import com.elioo.baymax.aicall.application.service.AiCostCalculator;
import com.elioo.baymax.aicall.application.service.MeteredLlmClient;
import com.elioo.baymax.companion.application.port.in.AskUseCase.AskAnswer;
import com.elioo.baymax.companion.application.port.out.ChatTurnPort;
import com.elioo.baymax.companion.domain.AnswerKind;
import com.elioo.baymax.companion.domain.ChatContext;
import com.elioo.baymax.companion.domain.ChatOutcome;
import com.elioo.baymax.config.BaymaxProperties;
import com.elioo.baymax.extraction.config.BaymaxModelClient;
import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.elioo.baymax.healthrecord.domain.FamilyAccount;
import com.elioo.baymax.healthrecord.domain.PatientAccess;
import com.elioo.baymax.healthrecord.domain.PatientProfile;
import com.elioo.baymax.outbound.application.service.Copy;
import com.elioo.baymax.web.adapter.in.ui.UiChatCopyAdapter;
import com.elioo.baymax.web.adapter.in.ui.UiCopy;
import com.elioo.baymax.outbound.domain.ValueVerdict;
import com.elioo.baymax.web.application.port.in.AttentionUseCase;
import com.elioo.baymax.web.application.port.in.HealthUseCase;
import com.elioo.baymax.web.domain.LabSeries;
import com.elioo.baymax.web.domain.LatestPrescription;
import com.elioo.baymax.web.domain.PrintedBand;
import com.elioo.healthcare.llm.api.LlmClient;
import com.elioo.healthcare.llm.config.LlmProperties;
import com.elioo.healthcare.llm.provider.anthropic.AnthropicLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The one real Claude Sonnet call in the chat's tests (DR-50), gated as every real model test is:
 * {@code RUN_LLM_INTEGRATION_TESTS=true ANTHROPIC_API_KEY=… ./gradlew :baymax:test --tests '*ChatAnswerLiveTest'}. A synthetic
 * patient; the pipeline as production runs it. What is asserted holds whatever the model writes: the code checks decide,
 * so the outcome is an answer that passed them or the fixed reply, the appendix is always there, and a dose question
 * always carries the doctor line.
 */
@EnabledIfEnvironmentVariable(named = "RUN_LLM_INTEGRATION_TESTS", matches = "true")
class ChatAnswerLiveTest {

    static final UUID FAMILY = UUID.randomUUID();
    static final UUID PATIENT = UUID.randomUUID();
    static final String RX1 = "Tab. Demoprol 5 mg · 1+0+0 · খাবারের পরে";

    @Test
    void sonnetAnswersADoseQuestionWithinTheCodeChecks() {
        String key = System.getenv("ANTHROPIC_API_KEY");
        assertThat(key).as("ANTHROPIC_API_KEY for the live chat test").isNotBlank();
        BaymaxProperties properties = new BaymaxProperties();
        BaymaxProperties.Price sonnet = new BaymaxProperties.Price();
        sonnet.setInput(new BigDecimal("2.00"));
        sonnet.setOutput(new BigDecimal("10.00"));
        properties.getLlm().getPrices().put("anthropic", new LinkedHashMap<>(Map.of("claude-sonnet-5", sonnet)));
        LlmProperties llm = new LlmProperties();
        LlmProperties.Anthropic cfg = new LlmProperties.Anthropic();
        cfg.setApiKey(key);
        cfg.setModel("claude-sonnet-5");
        cfg.setEffort("low");
        LlmClient claude = new AnthropicLlmClient(cfg, llm);

        Instant now = Instant.now();
        HealthRecordPort records = mock(HealthRecordPort.class);
        PatientProfile ma = new PatientProfile(PATIENT, FAMILY, "Ma", 68, PatientProfile.Sex.FEMALE, List.of(), now, now);
        when(records.visiblePatient(FAMILY, PATIENT)).thenReturn(Mono.just(new PatientAccess(ma, true)));
        when(records.findFamily(FAMILY)).thenReturn(Mono.just(new FamilyAccount(FAMILY, "+8801700000991", "Rahim", FamilyAccount.Plan.FREE, now, now)));
        ChatTurnPort turns = mock(ChatTurnPort.class);
        when(turns.reserveCounted(any(), any(), anyString(), anyString(), any(), anyInt())).thenReturn(Mono.just(UUID.randomUUID()));
        when(turns.complete(any(), any())).thenReturn(Mono.empty());
        // the context as production builds it (review of #164): the closed set, the dates and the verdicts are the builder's
        Map<String, Object> med = new LinkedHashMap<>();
        med.put("name", "Tab. Demoprol");
        med.put("dose_text", "5 mg");
        med.put("frequency_text", "1+0+0");
        med.put("timing_text", "খাবারের পরে");
        LabSeries creatinine = new LabSeries("creatinine", "mg/dL", "Creatinine", "mg/dL", List.of(new LabSeries.Point(UUID.randomUUID(),
                UUID.randomUUID(), "1.8", LocalDate.parse("2026-09-12"), false, false, ValueVerdict.Kind.OUTSIDE, "high", "k",
                PrintedBand.of("0.7", "1.3"))));
        LatestPrescription rx = new LatestPrescription(UUID.randomUUID(), LocalDate.parse("2026-09-20"), false, false, List.of(med),
                Optional.empty());
        ChatContext ctx = ChatContextBuilder.assemble("Ma", new HealthUseCase.HealthLabs(List.of(creatinine), new LabSeries.BpSeries(List.of())),
                rx, 6000);
        ChatContextBuilder contexts = mock(ChatContextBuilder.class);
        when(contexts.build(FAMILY, PATIENT, "Ma")).thenReturn(Mono.just(ctx));
        when(contexts.medicineNames(FAMILY, PATIENT)).thenReturn(Mono.just(ctx.medicineNames()));
        when(turns.reserveUncounted(any(), any(), anyString(), anyString())).thenReturn(Mono.just(UUID.randomUUID()));
        properties.getChat().setEnabled(true);
        AttentionUseCase attention = mock(AttentionUseCase.class);
        when(attention.urgent(FAMILY, PATIENT)).thenReturn(Mono.just(List.of()));
        AiCallLogPort log = mock(AiCallLogPort.class);
        when(log.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        @SuppressWarnings("unchecked")
        ObjectProvider<BaymaxModelClient> clients = mock(ObjectProvider.class);
        when(clients.getIfAvailable()).thenReturn(new BaymaxModelClient(claude));
        UiChatCopyAdapter copy = new UiChatCopyAdapter(new UiCopy(), new Copy());
        AiCostCalculator costs = new AiCostCalculator(properties);
        ChatAnswerService service = new ChatAnswerService(properties, records, turns, new RedFlagMatcher(properties), contexts, copy,
                attention, new MeteredLlmClient(claude, log, costs), costs, clients, Clock.systemUTC());

        AskAnswer a = service.ask(FAMILY, PATIENT, "ওষুধটা অর্ধেক করব? আর ক্রিয়েটিনিন কত?", "bn").block(Duration.ofSeconds(60));

        assertThat(a.kind()).isIn(AnswerKind.ANSWER, AnswerKind.FALLBACK);
        assertThat(a.answer()).contains(copy.line("bn", "chat.dose.ask_doctor"))
                .endsWith(copy.line("bn", "disclaimer") + "\n" + copy.line("bn", "safety.red_flag_line"));

        // the five D11 questions, the refusal rate measured: each question's kind, handler and violation codes, never its
        // text (review of #164, finding 21)
        for (String q : List.of("আম্মার ক্রিয়েটিনিন কত?", "ওষুধটা অর্ধেক করব?", "Napa কীসের?", "আম্মার বুকে ব্যথা", "ok thanks")) {
            AskAnswer each = service.ask(FAMILY, PATIENT, q, "bn").block(Duration.ofSeconds(60));
            ArgumentCaptor<ChatOutcome> outcome = ArgumentCaptor.forClass(ChatOutcome.class);
            verify(turns, atLeastOnce()).complete(any(), outcome.capture());
            ChatOutcome o = outcome.getValue();
            System.out.println("[live] q_chars=" + q.length() + " kind=" + o.kind() + " handler=" + o.handler() + " calls=" + o.modelCalls()
                    + " violations=" + o.violationCodes());
            assertThat(each.answer()).endsWith(copy.line("bn", "disclaimer") + "\n" + copy.line("bn", "safety.red_flag_line"));
        }
    }
}
