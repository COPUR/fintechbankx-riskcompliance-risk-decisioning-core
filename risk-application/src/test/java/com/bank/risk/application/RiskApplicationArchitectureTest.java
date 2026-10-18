package com.bank.risk.application;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class RiskApplicationArchitectureTest {

    @Test
    void applicationShouldNotDependOnInfrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bank.risk.application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.bank.risk.infrastructure..")
                .allowEmptyShould(true);

        rule.check(new ClassFileImporter().importPackages("com.bank.risk"));
    }

    /** Transactions and messaging are adapters' business; the use cases stay plain Java. */
    @Test
    void applicationIsFreeOfFrameworkPersistenceAndMessagingTypes() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bank.risk.application..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..",
                        "com.fasterxml.jackson..", "org.apache.kafka..");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bank.risk.application"));
    }
}
