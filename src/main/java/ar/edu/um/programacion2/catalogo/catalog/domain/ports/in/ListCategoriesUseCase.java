package ar.edu.um.programacion2.catalogo.catalog.domain.ports.in;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import java.util.List;

/** CU-06: listar las categorías de la copia local, para el filtro de categoría de la búsqueda (CU-05). */
public interface ListCategoriesUseCase {
    /**
     * Devuelve las categorías ordenadas por ID, con la versión local a la que corresponden.
     * <p>
     * Si la copia local nunca recibió un snapshot, falla con {@code CatalogNotInitializedException} (CU-06, 3a).
     *
     * @param enabled {@code null} para todas, incluidas las deshabilitadas; {@code true} o {@code false} para solo las
     *                de ese estado
     */
    Versioned<List<ProfessionalCategory>> listCategories(Boolean enabled);
}
