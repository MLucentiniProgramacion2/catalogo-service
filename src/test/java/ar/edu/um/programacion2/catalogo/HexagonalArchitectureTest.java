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
 * fallaría por no encontrar nada que analizar. Que los patrones estén bien escritos lo comprueba
 * {@link ArquitecturaDetectaViolacionesTest} con la feature de ejemplo {@code archejemplo}.
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

    static final String FEATURE_WEB = "ar.edu.um.programacion2.catalogo.*.infrastructure.web..";
    static final String FEATURE_MESSAGING = "ar.edu.um.programacion2.catalogo.*.infrastructure.messaging..";
    static final String FEATURE_STARTUP = "ar.edu.um.programacion2.catalogo.*.infrastructure.startup..";
    static final String FEATURE_PERIODIC = "ar.edu.um.programacion2.catalogo.*.infrastructure.periodic..";
    static final String FEATURE_PERSISTENCE = "ar.edu.um.programacion2.catalogo.*.infrastructure.persistence..";
    static final String FEATURE_REDIS = "ar.edu.um.programacion2.catalogo.*.infrastructure.redis..";
    static final String FEATURE_CATEDRA = "ar.edu.um.programacion2.catalogo.*.infrastructure.catedra..";
    static final String FEATURE_CLIENT = "ar.edu.um.programacion2.catalogo.*.infrastructure.client..";

    /**
     * De cualquier feature a cualquier feature: un controller de catalog tampoco puede usar la persistencia de sync. Cubre
     * los adaptadores de salida a la base, a Redis y a la API REST de la cátedra, y las reglas de formato de la cátedra que
     * comparten los de salida ({@code infrastructure.catedra}, issue #33). Los de entrada son los controllers, el listener
     * de Kafka y los disparadores de arranque y periódico (issue #35).
     */
    @ArchTest
    static final ArchRule adaptadoresDeEntradaNoDependenDeAdaptadoresDeSalida = noClasses()
        .that()
        .resideInAnyPackage(FEATURE_WEB, FEATURE_MESSAGING, FEATURE_STARTUP, FEATURE_PERIODIC)
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(FEATURE_PERSISTENCE, FEATURE_REDIS, FEATURE_CATEDRA, FEATURE_CLIENT)
        .because(
            "los adaptadores de entrada llaman al XxxService; las entidades, los repositorios, los clientes de Redis y de la API y los DTO de la cátedra no salen de sus paquetes"
        )
        .allowEmptyShould(true);

    /**
     * {@code sync} usa a {@code catalog} (CU-02 reemplaza la copia local con {@code CatalogRepository}): si {@code catalog}
     * usara a {@code sync}, las dos features quedarían en un ciclo. Lo que {@code catalog} necesite de {@code sync} va
     * como puerto de salida de {@code catalog}, implementado por un adaptador de {@code sync}.
     */
    @ArchTest
    static final ArchRule catalogNoDependeDeSync = featureNoDependeDe("catalog", "sync");

    /**
     * Regla "la feature {@code origen} no depende de la feature {@code destino}". Es un método para que
     * {@link ArquitecturaDetectaViolacionesTest} pruebe la misma construcción con la feature de ejemplo, sin poner clases
     * de ejemplo dentro de {@code catalog}.
     */
    static ArchRule featureNoDependeDe(String origen, String destino) {
        return noClasses()
            .that()
            .resideInAPackage("ar.edu.um.programacion2.catalogo." + origen + "..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("ar.edu.um.programacion2.catalogo." + destino + "..")
            .because("la feature " + destino + " usa a " + origen + "; la dependencia inversa va como puerto de salida de " + origen)
            .allowEmptyShould(true);
    }
}
