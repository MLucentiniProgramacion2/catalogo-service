package ar.edu.um.programacion2.catalogo.catalog.application.usecases;

import ar.edu.um.programacion2.catalogo.catalog.application.exception.CatalogNotInitializedException;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.in.ListCategoriesUseCase;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.CatalogRepository;
import ar.edu.um.programacion2.catalogo.catalog.domain.ports.out.LocalCatalogVersionProvider;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * CU-06: lee la versión local y, si hay una, las categorías, en una sola transacción de solo lectura.
 * <p>
 * <strong>{@code REPEATABLE_READ}</strong>: con el nivel por defecto de PostgreSQL ({@code READ COMMITTED}) cada
 * sentencia ve lo confirmado hasta ese momento. Si un snapshot se confirma entre las dos lecturas, se respondería la
 * versión anterior con las categorías de la nueva. En {@code REPEATABLE READ}, PostgreSQL toma una sola foto en la
 * primera sentencia y la usa en toda la transacción: la versión y las categorías salen de la misma. Una transacción que
 * solo lee no puede fallar por serialización, y tampoco bloquea al snapshot, que sigue escribiendo mientras tanto
 * (DP-03). Al terminar, Spring devuelve la conexión al pool con el nivel que tenía.
 * <p>
 * La versión se lee primero: sin versión aplicada no se consultan las categorías.
 */
@Component
@RequiredArgsConstructor
public class ListCategoriesUseCaseImpl implements ListCategoriesUseCase {

    private final LocalCatalogVersionProvider versionProvider;
    private final CatalogRepository catalogRepository;

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Versioned<List<ProfessionalCategory>> listCategories(Boolean enabled) {
        long version = versionProvider.findAppliedVersion().orElseThrow(CatalogNotInitializedException::new);
        return new Versioned<>(version, catalogRepository.findCategories(enabled));
    }
}
