package ar.edu.um.programacion2.catalogo.catalog.domain.ports.out;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalWithCategory;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.WeeklySchedule;
import java.util.List;
import java.util.Optional;

/**
 * Lo que CU-07 lee de la copia local: un profesional con su categoría y sus horarios habilitados.
 * <p>
 * Son dos lecturas y no una: el caso de uso decide si hace falta la segunda (un profesional deshabilitado no tiene agenda
 * vigente). Las dos se suman a la transacción de quien llama, que es la que les da la misma foto que la versión.
 */
public interface ProfessionalAgendaRepository {
    /** El profesional con su categoría, habilitado o no; vacío si no está en la copia. */
    Optional<ProfessionalWithCategory> findProfessionalWithCategory(long professionalId);

    /** Los horarios habilitados del profesional, en cualquier orden. */
    List<WeeklySchedule> findEnabledSchedules(long professionalId);
}
