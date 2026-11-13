package dev.ngb.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The kernel is the one module every layer of every service may see, so it is plain Java and names no service. The
 * same two facts the services' {@code core-is-self-contained} rule asserts, pinned at the module that owns them.
 */
class CoreKernelBoundaryTest {

    private static JavaClasses kernel;

    @BeforeAll
    static void importKernel() {
        kernel = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("dev.ngb.core");
        // Guards against an import that silently matches nothing and makes every rule pass.
        assertThat(kernel.contain("dev.ngb.core.runtime.UnitOfWork")).isTrue();
    }

    @Test
    void theKernelNamesNoService() {
        noClasses()
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("dev.ngb.billing..", "dev.ngb.tenant..", "dev.ngb.platform..")
                .because("core is shared by every service, so no service may leak into it")
                .check(kernel);
    }

    @Test
    void theKernelIsPlainJava() {
        classes()
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage("java..", "javax..", "dev.ngb.core..")
                .because("a domain or application layer sees the kernel, so it may name nothing but the JDK")
                .check(kernel);
    }
}
