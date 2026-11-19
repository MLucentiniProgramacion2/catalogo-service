package ar.edu.um.programacion2.catalogo.catalog.domain.ports.in;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.PageQuery;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalSearchCriteria;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ResultPage;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;

/** CU-05: buscar profesionales en la copia local, con filtros y paginado. */
public interface SearchProfessionalsUseCase {
    /**
     * Devuelve la página pedida de los profesionales que cumplen el criterio, ordenados por ID y con su categoría, junto
     * con la versión local de la que salen. La página y el total corresponden a esa versión.
     * <p>
     * Si la copia local nunca recibió un snapshot, falla con {@code CatalogNotInitializedException} (CU-05, 3a). Que
     * ningún profesional cumpla el criterio no es un error: la página viene vacía (5a).
     */
    Versioned<ResultPage<ProfessionalWithCategory>> searchProfessionals(ProfessionalSearchCriteria criteria, PageQuery pageQuery);
}
