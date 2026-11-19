package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto;

import java.util.List;

/**
 * Respuesta de {@code GET /api/service/professionals/{id}/agenda} (CU-07): el contrato con el servicio de turnos.
 * <p>
 * Records propios, sin reusar los de la búsqueda de la app (CU-05): un cambio para la app no puede tocar el contrato con
 * turnos. La forma exacta la fija un test con el JSON completo.
 *
 * @param catalogVersion versión local del catálogo de la que salen el profesional y sus horarios (la misma lectura)
 * @param professional el profesional, habilitado o no, con su categoría
 * @param weeklySchedules los horarios habilitados, por día (lunes a domingo), hora de inicio e ID; vacío si el
 *        profesional está deshabilitado
 */
public record AgendaResponse(long catalogVersion, AgendaProfessionalResponse professional, List<AgendaScheduleResponse> weeklySchedules) {}
