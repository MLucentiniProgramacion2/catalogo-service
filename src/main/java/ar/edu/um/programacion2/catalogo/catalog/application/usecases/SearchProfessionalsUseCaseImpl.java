package ar.edu.um.programacion2.catalogo.catalog.application.usecases;

import ar.edu.um.programacion2.catalogo.catalog.application.exception.CatalogNotInitializedException;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.PageQuery;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalSearchCriteria;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ResultPage;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.in.SearchProfessionalsUseCase;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRetryHint;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.LocalCatalogVersionProvider;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CU-05: lee la versión local y, si hay una, la página de profesionales y su total, en una sola transacción de solo
 * lectura.
 * <p>
 * Igual que en {@link ListCategoriesUseCaseImpl}, {@code REPEATABLE_READ} hace que PostgreSQL tome una sola foto en la
 * primera sentencia: acá son tres (la versión, la página y el count), y las tres salen de la misma. Con
 * {@code READ COMMITTED}, un snapshot confirmado en el medio podría dar la versión anterior con la página de la nueva, o
 * una página que no coincide con el total. La transacción solo lee: no bloquea al snapshot (DP-03).
 * <p>
 * La versión se lee primero: sin versión aplicada no se consulta nada más.
 */
@Component
@RequiredArgsConstructor
public class SearchProfessionalsUseCaseImpl implements SearchProfessionalsUseCase {

    private final LocalCatalogVersionProvider versionProvider;
    private final CatalogRetryHint retryHint;
    private final CatalogRepository catalogRepository;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Versioned<ResultPage<ProfessionalWithCategory>> searchProfessionals(ProfessionalSearchCriteria criteria, PageQuery pageQuery) {
        Objects.requireNonNull(criteria, "Falta el criterio de búsqueda");
        Objects.requireNonNull(pageQuery, "Falta la página pedida");
        long version = versionProvider.findAppliedVersion().orElseThrow(() -> new CatalogNotInitializedException(retryHint.retryAfter()));
        return new Versioned<>(version, catalogRepository.searchProfessionals(criteria, pageQuery));
    }
}
