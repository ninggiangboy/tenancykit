package dev.ngb.mailroom.architecture;

import com.tngtech.archunit.core.domain.JavaClass;
import dev.ngb.mailroom.Application;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Spring Modulith owns module verification (cycles, access to a module's internals) and the generated module
 * documentation. Layer, purity and layout rules stay in ArchUnit.
 */
class ModulithVerificationTest {

    // boot is wiring and the runtime edge, and shared is the shared core every context may use; neither is a bounded
    // context. ArchUnit keeps the shared core independent.
    private final ApplicationModules modules = ApplicationModules.of(
            Application.class,
            JavaClass.Predicates.resideInAnyPackage("dev.ngb.mailroom.boot..", "dev.ngb.mailroom.shared.."));

    @Test
    void modulesAreWellFormed() {
        modules.verify();
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(modules).writeModulesAsPlantUml().writeIndividualModulesAsPlantUml();
    }
}
