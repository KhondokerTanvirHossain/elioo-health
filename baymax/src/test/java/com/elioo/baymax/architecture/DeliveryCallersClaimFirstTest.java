package com.elioo.baymax.architecture;

import com.elioo.baymax.outbound.application.port.out.MessageDeliveryPort;
import com.elioo.baymax.outbound.application.port.out.OutboundMessagePort;
import com.elioo.baymax.outbound.domain.OutboundMessage;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * F4: a stored row reaches a family at most once. Derived from the bytecode, not from a list of callers someone
 * remembered: a new class that hands a row to the channel fails the first rule until it is reviewed, and the ones
 * delivering stored rows that a second asker can also reach must claim the row first.
 *
 * <p>NudgeComposer is allowed without a claim: it delivers only the row it has just created in the same flow, so no
 * second asker for that row exists.
 */
class DeliveryCallersClaimFirstTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS).importPackages("com.elioo.baymax");

    @Test
    void onlyKnownCallersDeliverAndTheOnesDeliveringStoredRowsClaimFirst() {
        noClasses().that().doNotHaveSimpleName("ExplanationService").and().doNotHaveSimpleName("ReviewGateService")
                .and().doNotHaveSimpleName("NudgeComposer")
                .should().callMethod(MessageDeliveryPort.class, "deliver", OutboundMessage.class)
                .because("a new deliverer must claim the row first (OutboundMessagePort.claimDelivery)")
                .check(CLASSES);
        classes().that().haveSimpleName("ExplanationService").or().haveSimpleName("ReviewGateService")
                .should().callMethod(OutboundMessagePort.class, "claimDelivery", UUID.class)
                .check(CLASSES);
    }
}
