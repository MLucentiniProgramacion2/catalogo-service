package ar.edu.um.programacion2.catalogo.catalog.infrastructure.web.dto;

/**
 * El profesional en la agenda (CU-07). {@code enabled} en {@code false} es una baja lógica: turnos no ofrece ni valida
 * turnos con él (4b). Nombre, apellido y categoría son los que turnos guarda en el proceso solo para mostrar (DPT-12).
 */
public record AgendaProfessionalResponse(long id, String firstName, String lastName, boolean enabled, AgendaCategoryResponse category) {}
