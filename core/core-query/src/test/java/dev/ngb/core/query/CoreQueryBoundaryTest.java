package dev.ngb.core.query;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

/** The contract sits next to the kernel, so a domain or application layer may name it: plain Java. */
class CoreQueryBoundaryTest {

    @Test
    void theQueryContractIsPlainJava() {
        JavaClasses query = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.ngb.core.query");
        assertThat(query.contain(QueryParser.class)).isTrue();
        classes().that().resideInAPackage("dev.ngb.core.query..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "javax..", "dev.ngb.core..")
                .check(query);
    }
}
