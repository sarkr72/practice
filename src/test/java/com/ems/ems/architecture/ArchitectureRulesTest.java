package com.ems.ems.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Pre-perf gate: catches the most common causes of perf regressions before
 * the BlazeMeter run. These are architectural — slow code patterns shouldn't
 * even compile-pass into the perf environment.
 */
class ArchitectureRulesTest {

    private static final JavaClasses APP_CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.ems.ems");

    /**
     * Synchronous HTTP / Kafka calls inside service implementations risk
     * holding a DB transaction open across a network round-trip — the classic
     * cause of pool exhaustion under load. The Kafka producer wrapper is the
     * only sanctioned async sender; service code should depend on that, not
     * on KafkaTemplate / RestTemplate / WebClient / HttpClient directly.
     */
    @Test
    void services_should_not_depend_on_synchronous_remote_clients() {
        noClasses()
                .that().resideInAPackage("..serviceImpl..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.web.client.RestTemplate")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.web.reactive.function.client.WebClient")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("java.net.http.HttpClient")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.springframework.kafka.core.KafkaTemplate")
                .because("synchronous remote calls inside @Transactional services exhaust the DB pool under load")
                .check(APP_CLASSES);
    }

    /**
     * Controllers shouldn't talk to repositories directly — keeps the
     * transactional boundary at the service layer where it belongs.
     */
    @Test
    void controllers_should_not_access_repositories_directly() {
        noClasses()
                .that().resideInAPackage("..controllers..")
                .should().dependOnClassesThat().resideInAPackage("..repositories..")
                .because("controllers must go through the service layer for the transactional boundary")
                .check(APP_CLASSES);
    }
}
