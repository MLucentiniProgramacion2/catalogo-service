package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.mapper;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.CategoryListResponse;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.CategoryResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/** Traduce las categorías del dominio a los DTO de la API. Solo hacia afuera: CU-06 no recibe categorías. */
@Component
public class CategoryDtoMapper {

    public CategoryResponse toResponse(ProfessionalCategory category) {
        return new CategoryResponse(category.id(), category.name(), category.description(), category.enabled());
    }

    public CategoryListResponse toListResponse(Versioned<List<ProfessionalCategory>> categories) {
        return new CategoryListResponse(categories.version(), categories.content().stream().map(this::toResponse).toList());
    }
}
