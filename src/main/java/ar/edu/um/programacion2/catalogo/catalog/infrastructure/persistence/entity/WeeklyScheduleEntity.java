package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * Fila de {@code weekly_schedule} (changelog {@code catalog/003-weekly-schedule.xml}).
 * <p>
 * {@code dayOfWeek} se guarda por nombre ({@code EnumType.STRING}), igual que en el contrato: con {@code ORDINAL} la base
 * guardaría 0 a 6 y dependería del orden del enum.
 * <p>
 * <strong>Las horas se guardan sin convertir de zona</strong> ({@code @JdbcTypeCode(SqlTypes.LOCAL_TIME)}): son horas
 * locales de atención (contrato §4), y la base tiene que guardar exactamente las que publica la cátedra. Con la
 * configuración de JHipster ({@code hibernate.jdbc.time_zone: UTC}), Hibernate convierte cada {@code LocalTime} de la
 * zona de la JVM a UTC: con la JVM en -03, 18:00-21:00 quedaba guardado como 21:00-00:00, y un horario que cruzaba la
 * medianoche al correrse cambiaba de sentido. Con {@code LOCAL_TIME}, Hibernate le pasa la hora al driver tal cual. La
 * configuración global no se toca: los {@code Instant} se siguen guardando en UTC. Las consultas que comparan contra
 * estas columnas mandan la hora sin convertir (ver {@code ProfessionalSpecifications.attendsOn}).
 */
@Entity
@Table(name = "weekly_schedule")
@Getter
@Setter
@NoArgsConstructor
@ToString
public class WeeklyScheduleEntity {

    @Id
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "professional_id", nullable = false)
    @ToString.Exclude
    private ProfessionalEntity professional;

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, length = 9)
    private DayOfWeek dayOfWeek;

    @JdbcTypeCode(SqlTypes.LOCAL_TIME)
    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @JdbcTypeCode(SqlTypes.LOCAL_TIME)
    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "slot_duration_minutes", nullable = false)
    private Integer slotDurationMinutes;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
