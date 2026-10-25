package ar.edu.um.programacion2.catalogo;

import static ar.edu.um.programacion2.catalogo.HexagonalArchitectureTest.FEATURE_APPLICATION;
import static ar.edu.um.programacion2.catalogo.HexagonalArchitectureTest.FEATURE_DOMAIN;
import static ar.edu.um.programacion2.catalogo.HexagonalArchitectureTest.FEATURE_INFRASTRUCTURE;
import static com.tngtech.archunit.base.DescribedPredicate.alwaysTrue;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.belongToAnyOf;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packagesOf = CatalogoServiceApp.class, importOptions = DoNotIncludeTests.class)
class TechnicalStructureTest {

    // Las capas de JHipster están ancladas al paquete base (antes eran "..web..", "..domain..", etc.) para que
    // los paquetes de las features no caigan en ellas: catalog.infrastructure.web no es la capa Web de JHipster
    // ni catalog.domain es su capa Domain. Las features son una capa más, opcional mientras no tengan clases,
    // que solo puede usar Security (y Constants y ApplicationProperties, ignorados abajo para todos).
    // prettier-ignore
    @ArchTest
    static final ArchRule respectsTechnicalArchitectureLayers = layeredArchitecture()
        .consideringAllDependencies()
        .layer("Config").definedBy("ar.edu.um.programacion2.catalogo.config..")
        .layer("Web").definedBy("ar.edu.um.programacion2.catalogo.web..")
        .optionalLayer("Service").definedBy("ar.edu.um.programacion2.catalogo.service..")
        .layer("Security").definedBy("ar.edu.um.programacion2.catalogo.security..")
        .optionalLayer("Persistence").definedBy("ar.edu.um.programacion2.catalogo.repository..")
        .layer("Domain").definedBy("ar.edu.um.programacion2.catalogo.domain..")
        .optionalLayer("Features").definedBy(FEATURE_DOMAIN, FEATURE_APPLICATION, FEATURE_INFRASTRUCTURE)

        .whereLayer("Config").mayNotBeAccessedByAnyLayer()
        .whereLayer("Web").mayOnlyBeAccessedByLayers("Config")
        .whereLayer("Service").mayOnlyBeAccessedByLayers("Web", "Config")
        .whereLayer("Security").mayOnlyBeAccessedByLayers("Config", "Service", "Web", "Features")
        .whereLayer("Persistence").mayOnlyBeAccessedByLayers("Service", "Security", "Web", "Config")
        .whereLayer("Domain").mayOnlyBeAccessedByLayers("Persistence", "Service", "Security", "Web", "Config")

        .ignoreDependency(belongToAnyOf(CatalogoServiceApp.class), alwaysTrue())
        .ignoreDependency(alwaysTrue(), belongToAnyOf(
            ar.edu.um.programacion2.catalogo.config.Constants.class,
            ar.edu.um.programacion2.catalogo.config.ApplicationProperties.class
        ));
}
