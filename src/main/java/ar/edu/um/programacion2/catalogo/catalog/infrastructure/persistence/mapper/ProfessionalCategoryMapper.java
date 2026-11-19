package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.ProfessionalCategoryEntity;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Traduce entre {@link ProfessionalCategoryEntity} y {@link ProfessionalCategory}.
 * <p>
 * La entidad usa {@code Long} y {@code Boolean} y el record {@code long} y {@code boolean}: un {@code null} de la base
 * falla con un mensaje que nombra la fila, en vez de un {@code NullPointerException} sin contexto al desenvolverlo. Los
 * campos de tipo objeto los valida el constructor del record.
 */
@Component
public class ProfessionalCategoryMapper {

    public ProfessionalCategory toDomain(ProfessionalCategoryEntity entity) {
        Long id = Objects.requireNonNull(entity.getId(), "Una categoría de la base no tiene id");
        return new ProfessionalCategory(
            id,
            entity.getName(),
            entity.getDescription(),
            Objects.requireNonNull(entity.getEnabled(), "La categoría " + id + " tiene enabled nulo en la base"),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    public ProfessionalCategoryEntity toEntity(ProfessionalCategory category) {
        ProfessionalCategoryEntity entity = new ProfessionalCategoryEntity();
        entity.setId(category.id());
        entity.setName(category.name());
        entity.setDescription(category.description());
        entity.setEnabled(category.enabled());
        entity.setCreatedAt(category.createdAt());
        entity.setUpdatedAt(category.updatedAt());
        return entity;
    }
}
