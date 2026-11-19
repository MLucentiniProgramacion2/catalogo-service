package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.mapper;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.Professional;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalAgenda;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.AgendaCategoryResponse;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.AgendaProfessionalResponse;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.AgendaResponse;
import ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto.AgendaScheduleResponse;
import org.springframework.stereotype.Component;

/** Traduce la agenda del dominio al contrato con turnos (CU-07). El orden de los horarios ya lo puso el dominio. */
@Component
public class AgendaDtoMapper {

    public AgendaResponse toResponse(Versioned<ProfessionalAgenda> agenda) {
        ProfessionalAgenda content = agenda.content();
        return new AgendaResponse(
            agenda.version(),
            toProfessional(content.professional().professional(), content.professional().category()),
            content.weeklySchedules().stream().map(AgendaDtoMapper::toSchedule).toList()
        );
    }

    private static AgendaProfessionalResponse toProfessional(Professional professional, ProfessionalCategory category) {
        return new AgendaProfessionalResponse(
            professional.id(),
            professional.firstName(),
            professional.lastName(),
            professional.enabled(),
            new AgendaCategoryResponse(category.id(), category.name(), category.enabled())
        );
    }

    private static AgendaScheduleResponse toSchedule(WeeklySchedule schedule) {
        return new AgendaScheduleResponse(
            schedule.id(),
            schedule.dayOfWeek(),
            schedule.startTime(),
            schedule.endTime(),
            schedule.slotDurationMinutes()
        );
    }
}
