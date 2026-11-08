package ar.edu.um.programacion2.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.archejemplo.application.ApplicationQueUsaInfrastructure;
import ar.edu.um.programacion2.catalogo.archejemplo.domain.DominioQueUsaApplication;
import ar.edu.um.programacion2.catalogo.archejemplo.domain.DominioQueUsaHibernate;
import ar.edu.um.programacion2.catalogo.archejemplo.domain.DominioQueUsaInfrastructure;
import ar.edu.um.programacion2.catalogo.archejemplo.domain.DominioQueUsaJackson3;
import ar.edu.um.programacion2.catalogo.archejemplo.domain.DominioQueUsaJacksonAnotaciones;
import ar.edu.um.programacion2.catalogo.archejemplo.domain.DominioQueUsaJakartaPersistence;
import ar.edu.um.programacion2.catalogo.archejemplo.domain.DominioQueUsaLibreriaNoListada;
import ar.edu.um.programacion2.catalogo.archejemplo.domain.DominioQueUsaSecurityDeJHipster;
import ar.edu.um.programacion2.catalogo.archejemplo.domain.DominioQueUsaSpring;
import ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.FeatureQueUsaConfigDeJHipster;
import ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.FeatureQueUsaDomainDeJHipster;
import ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.FeatureQueUsaOtraFeature;
import ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.FeatureQueUsaWebDeJHipster;
import ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.messaging.MessagingQueUsaCatedra;
import ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.messaging.MessagingQueUsaPersistence;
import ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.web.WebQueUsaPersistence;
import ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.web.WebQueUsaRedis;
import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.lang.ArchRule;
import java.util.Arrays;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * Comprueba que las reglas reales de {@link HexagonalArchitectureTest} y {@link TechnicalStructureTest} detectan
 * cada violación de la feature de ejemplo {@code archejemplo} y no marcan las clases que cumplen.
 * <p>
 * Cada test compara el conjunto exacto de clases que violan la regla: si la regla dejara pasar una violación, o
 * marcara una clase que cumple, el conjunto no coincidiría.
 */
class ArquitecturaDetectaViolacionesTest {

    private static final String PAQUETE_EJEMPLO = "ar.edu.um.programacion2.catalogo.archejemplo";
    private static final String CARPETA_EJEMPLO = "/ar/edu/um/programacion2/catalogo/archejemplo/";

    private static final JavaClasses EJEMPLO = new ClassFileImporter().importPackages(PAQUETE_EJEMPLO);

    @Test
    void domainSoloDependeDeJavaYDelDominioDetectaCadaDependenciaNoPermitida() {
        assertThat(clasesQueViolan(HexagonalArchitectureTest.domainSoloDependeDeJavaYDelDominio, EJEMPLO)).containsExactlyInAnyOrder(
            nombres(
                DominioQueUsaApplication.class,
                DominioQueUsaInfrastructure.class,
                DominioQueUsaSpring.class,
                DominioQueUsaJakartaPersistence.class,
                DominioQueUsaHibernate.class,
                DominioQueUsaJacksonAnotaciones.class,
                DominioQueUsaJackson3.class,
                DominioQueUsaSecurityDeJHipster.class,
                DominioQueUsaLibreriaNoListada.class
            )
        );
    }

    @Test
    void domainNoDependeDeApplicationNiInfrastructureDetectaLasDosDirecciones() {
        assertThat(
            clasesQueViolan(HexagonalArchitectureTest.domainNoDependeDeApplicationNiInfrastructure, EJEMPLO)
        ).containsExactlyInAnyOrder(nombres(DominioQueUsaApplication.class, DominioQueUsaInfrastructure.class));
    }

    @Test
    void applicationNoDependeDeInfrastructureDetectaLaViolacion() {
        assertThat(clasesQueViolan(HexagonalArchitectureTest.applicationNoDependeDeInfrastructure, EJEMPLO)).containsExactly(
            nombres(ApplicationQueUsaInfrastructure.class)
        );
    }

    @Test
    void adaptadoresDeEntradaNoDependenDeAdaptadoresDeSalidaDetectaPersistenceRedisYCatedra() {
        // El controller de ejemplo también está en infrastructure.web y no se marca: llama al service, no a persistence.
        assertThat(
            clasesQueViolan(HexagonalArchitectureTest.adaptadoresDeEntradaNoDependenDeAdaptadoresDeSalida, EJEMPLO)
        ).containsExactlyInAnyOrder(
            nombres(WebQueUsaPersistence.class, MessagingQueUsaPersistence.class, WebQueUsaRedis.class, MessagingQueUsaCatedra.class)
        );
    }

    @Test
    void featureNoDependeDeDetectaLaDependenciaEntreFeatures() {
        // La misma construcción que catalogNoDependeDeSync, aplicada a la feature de ejemplo: así no hace falta poner
        // una clase de ejemplo dentro de catalog. Se importa sync además del ejemplo, para que exista el destino.
        JavaClasses ejemploYSync = new ClassFileImporter().importPackages(PAQUETE_EJEMPLO, "ar.edu.um.programacion2.catalogo.sync");

        assertThat(clasesQueViolan(HexagonalArchitectureTest.featureNoDependeDe("archejemplo", "sync"), ejemploYSync)).containsExactly(
            nombres(FeatureQueUsaOtraFeature.class)
        );
    }

    @Test
    void reglaPorCapasDeJHipsterSoloMarcaLasFeaturesQueUsanWebDomainOConfig() {
        // Las capas no opcionales (Config, Web, Security, Domain) no pueden quedar vacías: se importa main junto
        // con la feature de ejemplo, que es la única parte de test-classes que se incluye.
        ImportOption mainYEjemplo = location -> new DoNotIncludeTests().includes(location) || location.contains(CARPETA_EJEMPLO);
        JavaClasses clases = new ClassFileImporter().withImportOption(mainYEjemplo).importPackagesOf(CatalogoServiceApp.class);

        assertThat(clasesQueViolan(TechnicalStructureTest.respectsTechnicalArchitectureLayers, clases)).containsExactlyInAnyOrder(
            nombres(FeatureQueUsaWebDeJHipster.class, FeatureQueUsaDomainDeJHipster.class, FeatureQueUsaConfigDeJHipster.class)
        );
    }

    @Test
    void lasReglasRealesNoAnalizanLaFeatureDeEjemplo() {
        // Mismo import que @AnalyzeClasses(packagesOf = CatalogoServiceApp.class, importOptions = DoNotIncludeTests.class)
        JavaClasses reales = new ClassFileImporter().withImportOption(new DoNotIncludeTests()).importPackagesOf(CatalogoServiceApp.class);

        assertThat(reales.contain(CatalogoServiceApp.class)).isTrue();
        assertThat(reales.stream().map(JavaClass::getPackageName)).noneMatch(paquete -> paquete.startsWith(PAQUETE_EJEMPLO));
    }

    /** Nombres de las clases de origen de las dependencias que violan la regla. */
    private static Set<String> clasesQueViolan(ArchRule regla, JavaClasses clases) {
        Set<String> origenes = new TreeSet<>();
        regla
            .evaluate(clases)
            .handleViolations((Collection<Dependency> dependencias, String mensaje) ->
                dependencias.forEach(dependencia -> origenes.add(dependencia.getOriginClass().getName()))
            );
        return origenes;
    }

    private static String[] nombres(Class<?>... clases) {
        return Arrays.stream(clases).map(Class::getName).toArray(String[]::new);
    }
}
