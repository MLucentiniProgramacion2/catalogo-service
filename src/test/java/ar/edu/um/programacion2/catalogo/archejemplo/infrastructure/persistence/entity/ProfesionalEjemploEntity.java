package ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.persistence.entity;

/**
 * Hace de entidad JPA de la feature de ejemplo, sin {@code @Entity} (ver {@code package-info} de {@code archejemplo}).
 * Cumple todas las reglas: la usan las clases que violan "los adaptadores de entrada no dependen de persistence".
 */
public class ProfesionalEjemploEntity {

    private Long id;

    public Long getId() {
        return id;
    }
}
