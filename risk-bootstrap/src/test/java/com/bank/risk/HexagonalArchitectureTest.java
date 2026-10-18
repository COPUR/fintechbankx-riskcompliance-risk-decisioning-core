package com.bank.risk;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The four ArchUnit rules of the FinTechBankX service guardrails (section 3,
 * ADR-028), checked over the production classes of every module. This module
 * is the only one whose test classpath holds domain, application and
 * infrastructure together, so the cross-layer rules live here and run on
 * ./gradlew check.
 */
class HexagonalArchitectureTest {

    private static final String ROOT = "com.bank.risk";
    private static final String DOMAIN = ROOT + ".domain..";
    private static final String PORT_IN = ROOT + ".domain.port.in..";
    private static final String PORT_OUT = ROOT + ".domain.port.out..";
    private static final String APPLICATION = ROOT + ".application..";
    private static final String INFRASTRUCTURE = ROOT + ".infrastructure..";

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    /** Rule 1: the domain is plain Java plus its own types. */
    @Test
    void domainDependsOnNoApplicationInfrastructureOrFrameworkPackage() {
        noClasses().that().resideInAPackage(DOMAIN)
                .should().dependOnClassesThat().resideInAnyPackage(
                        APPLICATION, INFRASTRUCTURE,
                        "org.springframework..", "org.springframework.data..",
                        "jakarta.persistence..", "org.hibernate..",
                        "org.apache.kafka..", "org.springframework.kafka..",
                        "com.mongodb..", "org.bson..",
                        "com.fasterxml.jackson..")
                .check(production);
    }

    /** Rule 2: use cases never reach into adapters. */
    @Test
    void applicationDependsOnNoInfrastructurePackage() {
        noClasses().that().resideInAPackage(APPLICATION)
                .should().dependOnClassesThat().resideInAPackage(INFRASTRUCTURE)
                .check(production);
    }

    /** Rule 3a: inbound adapters drive the use-case interfaces in domain.port.in. */
    @Test
    void controllersAndListenersDependOnInboundPorts() {
        classes().that(areControllersOrListeners())
                .should().dependOnClassesThat().resideInAPackage(PORT_IN)
                .check(production);
    }

    /** Rule 3b: inbound adapters and their error mapping never see application classes. */
    @Test
    void controllersListenersAndAdviceDoNotDependOnApplication() {
        noClasses().that(areControllersOrListeners().or(areControllerAdvice()))
                .should().dependOnClassesThat().resideInAPackage(APPLICATION)
                .check(production);
    }

    /** Rule 4: every implementation of an outbound port is an adapter. */
    @Test
    void outboundPortImplementationsLiveInInfrastructure() {
        classes().that().implement(JavaClass.Predicates.resideInAPackage(PORT_OUT))
                .and().areNotInterfaces()
                .should().resideInAPackage(INFRASTRUCTURE)
                .check(production);
    }

    /** Port packages hold contracts only, so rule 4 cannot be dodged by placing an adapter next to its port. */
    @Test
    void portPackagesHoldOnlyInterfacesAndTheirCommands() {
        classes().that().resideInAPackage(PORT_OUT)
                .should().beInterfaces()
                .check(production);
    }

    private static DescribedPredicate<JavaClass> areControllersOrListeners() {
        return DescribedPredicate.describe("are controllers or message listeners", javaClass ->
                javaClass.isAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                        || javaClass.isAnnotatedWith("org.springframework.stereotype.Controller")
                        || javaClass.isAnnotatedWith("org.springframework.kafka.annotation.KafkaListener")
                        || javaClass.getMethods().stream().anyMatch(method ->
                        method.isAnnotatedWith("org.springframework.kafka.annotation.KafkaListener")));
    }

    private static DescribedPredicate<JavaClass> areControllerAdvice() {
        return DescribedPredicate.describe("are controller advice", javaClass ->
                javaClass.isAnnotatedWith("org.springframework.web.bind.annotation.RestControllerAdvice")
                        || javaClass.isAnnotatedWith("org.springframework.web.bind.annotation.ControllerAdvice"));
    }
}
