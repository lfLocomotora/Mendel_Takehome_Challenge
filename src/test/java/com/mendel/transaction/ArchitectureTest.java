package com.mendel.transaction;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ArchitectureTest {
  private final com.tngtech.archunit.core.domain.JavaClasses classes =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.mendel.transaction");

  @Test
  void keepsBusinessRulesIndependentOfHttpAndJson() {
    noClasses()
        .that()
        .resideInAnyPackage("..application..", "..domain..", "..storage..", "..concurrency..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "..api..", "org.springframework.web..", "org.springframework.http..", "tools.jackson..")
        .check(classes);
  }

  @Test
  void controllersCannotBypassTheService() {
    noClasses()
        .that()
        .resideInAPackage("..api..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage("..storage..", "..concurrency..")
        .check(classes);
  }

  @Test
  void componentsDoNotHaveCyclicDependencies() {
    slices().matching("com.mendel.transaction.(*)..").should().beFreeOfCycles().check(classes);
  }
}
