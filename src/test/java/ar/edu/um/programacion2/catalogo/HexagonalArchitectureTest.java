package ar.edu.um.programacion2.catalogo;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Reglas de la arquitectura hexagonal, válidas para cualquier feature ({@code <base>.<feature>.domain|application|infrastructure}).
 * <p>
 * En los patrones, {@code *} es exactamente un segmento de paquete: {@code catalogo.*.domain..} incluye
 * {@code catalogo.catalog.domain.model}, pero no el {@code catalogo.domain} de JHipster.
 * <p>
 * Todas las reglas usan {@code allowEmptyShould(true)}: mientras no haya clases en las features, ArchUnit
 * fallaría por no encontrar nada que analizar.
 */
@AnalyzeClasses(packagesOf = CatalogoServiceApp.class, importOptions = DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    static final String FEATURE_DOMAIN = "ar.edu.um.programacion2.catalogo.*.domain..";
    static final String FEATURE_APPLICATION = "ar.edu.um.programacion2.catalogo.*.application..";
    static final String FEATURE_INFRASTRUCTURE = "ar.edu.um.programacion2.catalogo.*.infrastructure..";

    // Lista blanca: lombok queda permitido para el issue siguiente, aunque hoy no esté en el pom.
    @ArchTest
    static final ArchRule domainSoloDependeDeJavaYDelDominio = classes()
        .that()
        .resideInAPackage(FEATURE_DOMAIN)
        .should()
        .onlyDependOnClassesThat()
        .resideInAnyPackage("java..", FEATURE_DOMAIN, "lombok..")
        .because("el dominio solo usa Java estándar, Lombok y clases de dominio")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule domainNoDependeDeApplicationNiInfrastructure = noClasses()
        .that()
        .resideInAPackage(FEATURE_DOMAIN)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(FEATURE_APPLICATION, FEATURE_INFRASTRUCTURE)
        .because("las dependencias apuntan hacia el dominio; lo que necesite de afuera va como puerto en domain.ports.out")
        .allowEmptyShould(true);

    @ArchTest
    static final ArchRule applicationNoDependeDeInfrastructure = noClasses()
        .that()
        .resideInAPackage(FEATURE_APPLICATION)
        .should()
        .dependOnClassesThat()
        .resideInAPackage(FEATURE_INFRASTRUCTURE)
        .because("los use cases dependen de puertos, no de adapters")
        .allowEmptyShould(true);
}
