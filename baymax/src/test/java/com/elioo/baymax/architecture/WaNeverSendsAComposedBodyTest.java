package com.elioo.baymax.architecture;

import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * F4: a composed body reaches a family only through MessageDeliveryPort, which records the outcome (V13/V14) and
 * refuses anything not released or approved. The inbound WhatsApp layer sends fixed copy only — it never reads a
 * body. Before F4, WaIntakeService.onText sent message.body() itself: the double-send and the gate bypass.
 */
class WaNeverSendsAComposedBodyTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.elioo.baymax");

    @Test
    void theInboundWhatsappLayerNeverReadsAComposedBody() {
        noClasses().that().resideInAPackage("..wa.application..")
                .should().callMethod(OutboundMessage.class, "body")
                .because("a body is delivered only by ExplainDocumentUseCase.deliver → MessageDeliveryPort (F4)")
                .check(CLASSES);
    }
}
