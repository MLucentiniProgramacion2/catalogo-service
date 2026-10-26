package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Fila de {@code professional} (changelog {@code catalog/002-professional.xml}).
 * <p>
 * La categoría es {@code LAZY} y queda fuera del {@code toString}: así imprimir o comparar un profesional no dispara
 * una consulta. No hay {@code @OneToMany} de vuelta desde la categoría porque nadie la necesita.
 */
@Entity
@Table(name = "professional")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class ProfessionalEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    @ToString.Exclude
    private ProfessionalCategoryEntity category;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name", nullable = false)
    private String lastName;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
