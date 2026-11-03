package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.mapper;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.PageQuery;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalSearchCriteria;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ResultPage;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.CategorySummaryResponse;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.ProfessionalPageResponse;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.ProfessionalResponse;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.ProfessionalSearchParams;
import org.springframework.stereotype.Component;

/**
 * Traduce entre la API de búsqueda (CU-05) y el dominio: los parámetros de la query al criterio y la página pedida, y
 * la página de resultado a la respuesta.
 * <p>
 * Aplica los valores por defecto de la paginación, que son de la API. El de {@code enabled} no: lo pasa tal cual
 * ({@code null} si no vino) y lo resuelve el dominio (DP-01).
 */
@Component
public class ProfessionalDtoMapper {

    /** Página por defecto: la primera. */
    public static final int DEFAULT_PAGE = 0;

    /** Tamaño de página por defecto. */
    public static final int DEFAULT_SIZE = 20;

    public ProfessionalSearchCriteria toCriteria(ProfessionalSearchParams params) {
        return ProfessionalSearchCriteria.of(params.categoryId(), params.name(), params.enabled());
    }

    public PageQuery toPageQuery(ProfessionalSearchParams params) {
        return new PageQuery(
            params.page() == null ? DEFAULT_PAGE : params.page(),
            params.size() == null ? DEFAULT_SIZE : params.size()
        );
    }

    public ProfessionalPageResponse toPageResponse(Versioned<ResultPage<ProfessionalWithCategory>> result) {
        ResultPage<ProfessionalWithCategory> page = result.content();
        return new ProfessionalPageResponse(
            result.version(),
            page.number(),
            page.size(),
            page.totalElements(),
            page.totalPages(),
            page.items().stream().map(this::toResponse).toList()
        );
    }

    public ProfessionalResponse toResponse(ProfessionalWithCategory item) {
        ProfessionalCategory category = item.category();
        return new ProfessionalResponse(
            item.professional().id(),
            item.professional().firstName(),
            item.professional().lastName(),
            item.professional().enabled(),
            new CategorySummaryResponse(category.id(), category.name(), category.enabled())
        );
    }
}
