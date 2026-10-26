package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Fila de {@code professional_category} (changelog {@code catalog/001-professional-category.xml}).
 * <p>
 * El ID es el de la cátedra, sin {@code @GeneratedValue}. {@code createdAt} y {@code updatedAt} son los que informa la
 * cátedra, no auditoría propia: por eso no extiende {@code AbstractAuditingEntity} de JHipster.
 * <p>
 * Sin {@code @Data} ni {@code @EqualsAndHashCode}: dentro de un contexto de persistencia Hibernate ya garantiza una
 * instancia por fila, y un {@code equals}/{@code hashCode} sobre campos que cambian rompe los {@code Set} y los proxies.
 */
@Entity
@Table(name = "professional_category")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class ProfessionalCategoryEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "description")
    private String description;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
