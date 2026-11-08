package com.paymentcore.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Executable architecture rules.
 *
 * <p>Reviewed diagrams go stale; tests do not. These rules encode the layering
 * the payment module is allowed to have, so a pull request that makes the
 * domain depend on Spring services or lets a controller reach into a repository
 * fails the build instead of being caught in review.</p>
 */
@AnalyzeClasses(packages = "com.paymentcore", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

	@ArchTest
	static final ArchRule domainIsIndependentOfOuterLayers = noClasses().that()
		.resideInAPackage("com.paymentcore.payment.domain..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.paymentcore.payment.api..", "com.paymentcore.payment.service..",
				"com.paymentcore.payment.repository..", "com.paymentcore.payment.outbox..",
				"com.paymentcore.payment.event..", "com.paymentcore.payment.dto..");

	@ArchTest
	static final ArchRule apiDoesNotReachIntoPersistence = noClasses().that()
		.resideInAPackage("com.paymentcore.payment.api..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.paymentcore.payment.repository..", "com.paymentcore.payment.outbox..");

	@ArchTest
	static final ArchRule outboxDoesNotDependOnWebOrApplicationServices = noClasses().that()
		.resideInAPackage("com.paymentcore.payment.outbox..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.paymentcore.payment.api..", "com.paymentcore.payment.service..");

	@ArchTest
	static final ArchRule dtoPackageOnlyDependsOnDomain = noClasses().that()
		.resideInAPackage("com.paymentcore.payment.dto..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.paymentcore.payment.api..", "com.paymentcore.payment.service..",
				"com.paymentcore.payment.repository..", "com.paymentcore.payment.outbox..");

	@ArchTest
	static final ArchRule paymentSlicesAreFreeOfCycles = slices().matching("com.paymentcore.payment.(*)..")
		.should()
		.beFreeOfCycles();

	@ArchTest
	static final ArchRule ledgerDoesNotDependOnReconciliation = noClasses().that()
		.resideInAPackage("com.paymentcore.ledger..")
		.should()
		.dependOnClassesThat()
		.resideInAPackage("com.paymentcore.reconciliation..");

	@ArchTest
	static final ArchRule reconciliationDoesNotDependOnWeb = noClasses().that()
		.resideInAPackage("com.paymentcore.reconciliation..")
		.should()
		.dependOnClassesThat()
		.resideInAnyPackage("com.paymentcore.payment.api..", "com.paymentcore.common.observability..");

	@ArchTest
	static final ArchRule controllersAreNamedAsControllers = classes().that()
		.areAnnotatedWith(RestController.class)
		.should()
		.haveSimpleNameEndingWith("Controller");

	@ArchTest
	static final ArchRule applicationServicesAreNamedAsServices = classes().that()
		.resideInAPackage("com.paymentcore.payment.service..")
		.and()
		.areAnnotatedWith(Service.class)
		.should()
		.haveSimpleNameEndingWith("Service");

	@ArchTest
	static final ArchRule springDataRepositoriesAreNamedAsRepositories = classes().that()
		.areAssignableTo(JpaRepository.class)
		.should()
		.haveSimpleNameEndingWith("Repository");

	@ArchTest
	static final ArchRule configurationPropertiesAreNamedAsProperties = classes().that()
		.areAnnotatedWith(ConfigurationProperties.class)
		.should()
		.haveSimpleNameEndingWith("Properties");

	@ArchTest
	static final ArchRule noFieldInjection = noFields().should()
		.beAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class);

}
