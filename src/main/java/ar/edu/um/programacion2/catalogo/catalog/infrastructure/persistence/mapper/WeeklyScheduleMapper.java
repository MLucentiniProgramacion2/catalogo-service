package ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.mapper;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.ProfessionalEntity;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.persistence.entity.WeeklyScheduleEntity;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * Traduce entre {@link WeeklyScheduleEntity} y {@link WeeklySchedule}.
 * <p>
 * Igual que en {@link ProfessionalMapper}, la referencia al profesional la consigue quien llama y el mapper verifica
 * que apunte al profesional del record.
 */
@Component
public class WeeklyScheduleMapper {

    public WeeklySchedule toDomain(WeeklyScheduleEntity entity) {
        Long id = Objects.requireNonNull(entity.getId(), "Un horario de la base no tiene id");
        ProfessionalEntity professional = Objects.requireNonNull(entity.getProfessional(), "El horario " + id + " no tiene profesional");
        return new WeeklySchedule(
            id,
            Objects.requireNonNull(professional.getId(), "El profesional del horario " + id + " no tiene id"),
            entity.getDayOfWeek(),
            entity.getStartTime(),
            entity.getEndTime(),
            Objects.requireNonNull(entity.getSlotDurationMinutes(), "El horario " + id + " tiene slotDurationMinutes nulo en la base"),
            Objects.requireNonNull(entity.getEnabled(), "El horario " + id + " tiene enabled nulo en la base"),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    /**
     * @param professional referencia al profesional {@code schedule.professionalId()}, normalmente obtenida con
     *        {@code getReferenceById} para no consultar la base.
     * @throws IllegalArgumentException si la referencia apunta a otro profesional.
     */
    public WeeklyScheduleEntity toEntity(WeeklySchedule schedule, ProfessionalEntity professional) {
        Objects.requireNonNull(professional, "Falta la referencia al profesional del horario " + schedule.id());
        if (!Objects.equals(professional.getId(), schedule.professionalId())) {
            throw new IllegalArgumentException(
                "La referencia al profesional " +
                    professional.getId() +
                    " no coincide con el profesional " +
                    schedule.professionalId() +
                    " del horario " +
                    schedule.id()
            );
        }
        WeeklyScheduleEntity entity = new WeeklyScheduleEntity();
        entity.setId(schedule.id());
        entity.setProfessional(professional);
        entity.setDayOfWeek(schedule.dayOfWeek());
        entity.setStartTime(schedule.startTime());
        entity.setEndTime(schedule.endTime());
        entity.setSlotDurationMinutes(schedule.slotDurationMinutes());
        entity.setEnabled(schedule.enabled());
        entity.setCreatedAt(schedule.createdAt());
        entity.setUpdatedAt(schedule.updatedAt());
        return entity;
    }
}
