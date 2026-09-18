package com.elioo.baymax.nudge;

import com.elioo.baymax.healthrecord.application.port.out.HealthRecordPort;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * BMX-8: chronic flags are asked, never inferred. The only write after creation is
 * {@link HealthRecordPort#updateChronicFlags}, and nothing in the extraction pipeline, the outbound composer or the
 * nudge engine may call it — or construct a PatientProfile at all. Asserted on the bytecode.
 */
class ChronicFlagsAreAskedNeverInferredTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.elioo.baymax");

    @Test
    void nothingInThePipelineOrTheEngineWritesChronicFlags() {
        ArchRule rule = noClasses().that().resideInAnyPackage("..extraction..", "..nudge..", "..outbound..", "..aicall..", "..storage..")
                .should().callMethod(HealthRecordPort.class, "updateChronicFlags", java.util.UUID.class, java.util.List.class)
                .orShould().callMethod(HealthRecordPort.class, "createPatient", com.elioo.baymax.healthrecord.domain.PatientProfile.class);
        rule.check(CLASSES);
    }
}
