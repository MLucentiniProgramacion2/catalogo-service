package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.ProfessionalCategoryEntity;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.ProfessionalEntity;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Traduce entre {@link ProfessionalEntity} y {@link Professional}.
 * <p>
 * Hacia la entidad no es campo a campo: el record tiene {@code categoryId} y la entidad una referencia a la categoría.
 * La referencia la consigue quien llama (el adaptador, dueño del contexto de persistencia) y el mapper verifica que
 * apunte a la categoría del record. Hacia el dominio solo lee el ID de la categoría, que no inicializa el proxy LAZY.
 */
@Component
public class ProfessionalMapper {

    public Professional toDomain(ProfessionalEntity entity) {
        Long id = Objects.requireNonNull(entity.getId(), "Un profesional de la base no tiene id");
        ProfessionalCategoryEntity category = Objects.requireNonNull(entity.getCategory(), "El profesional " + id + " no tiene categoría");
        return new Professional(
            id,
            Objects.requireNonNull(category.getId(), "La categoría del profesional " + id + " no tiene id"),
            entity.getFirstName(),
            entity.getLastName(),
            Objects.requireNonNull(entity.getEnabled(), "El profesional " + id + " tiene enabled nulo en la base"),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    /**
     * @param category referencia a la categoría {@code professional.categoryId()}, normalmente obtenida con
     *        {@code getReferenceById} para no consultar la base.
     * @throws IllegalArgumentException si la referencia apunta a otra categoría.
     */
    public ProfessionalEntity toEntity(Professional professional, ProfessionalCategoryEntity category) {
        Objects.requireNonNull(category, "Falta la referencia a la categoría del profesional " + professional.id());
        if (!Objects.equals(category.getId(), professional.categoryId())) {
            throw new IllegalArgumentException(
                "La referencia a la categoría " +
                    category.getId() +
                    " no coincide con la categoría " +
                    professional.categoryId() +
                    " del profesional " +
                    professional.id()
            );
        }
        ProfessionalEntity entity = new ProfessionalEntity();
        entity.setId(professional.id());
        entity.setCategory(category);
        entity.setFirstName(professional.firstName());
        entity.setLastName(professional.lastName());
        entity.setEnabled(professional.enabled());
        entity.setCreatedAt(professional.createdAt());
        entity.setUpdatedAt(professional.updatedAt());
        return entity;
    }
}
