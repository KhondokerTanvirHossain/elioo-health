package com.elioo.baymax.architecture;

import com.elioo.baymax.outbound.application.service.UrgencyService;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.data.repository.Repository;
import org.springframework.stereotype.Service;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** BMX-4 acceptance: services talk to ports, never to repositories or adapters. */
@AnalyzeClasses(packages = "com.elioo.baymax", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule servicesDoNotUseRepositories = noClasses()
            .that().areAnnotatedWith(Service.class)
            .should().dependOnClassesThat().areAssignableTo(Repository.class)
            .because("every read and write goes through HealthRecordPort or another port (DR-1)");

    @ArchTest
    static final ArchRule applicationLayerDoesNotDependOnAdapters = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..")
            .because("the application layer sees ports and domain only");

    @ArchTest
    static final ArchRule domainDependsOnNothingInBaymaxButDomain = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..adapter..", "..config..")
            .because("domain records are plain values");

    /**
     * Global constraint (web portal plan): every page that shows urgency reads DocumentUrgencyUseCase; nothing under
     * web calls UrgencyService. The pure printed-range reads a page needs live on {@code PrintedRange} (domain).
     */
    @ArchTest
    static final ArchRule webDoesNotDependOnTheUrgencyRule = noClasses()
            .that().resideInAPackage("com.elioo.baymax.web..")
            .should().dependOnClassesThat().areAssignableTo(UrgencyService.class)
            .because("a page shows the stored urgency and stored verdicts; it never runs the rule that sets them");
}
