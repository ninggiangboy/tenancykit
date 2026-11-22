package dev.ngb.core.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The shared webhook mechanics never reach into a service, so every app can depend on them safely. */
@AnalyzeClasses(packages = "dev.ngb.core")
class WebhookBoundaryTest {

    @ArchTest
    static final ArchRule webhookDoesNotDependOnAService = noClasses()
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("dev.ngb.billing..", "dev.ngb.platform..", "dev.ngb.mailer..",
                    "dev.ngb.mailroom..", "dev.ngb.notification..", "dev.ngb.survey..")
            .because("core-webhook is shared by every app that delivers a webhook, so no app may leak into it");
}
