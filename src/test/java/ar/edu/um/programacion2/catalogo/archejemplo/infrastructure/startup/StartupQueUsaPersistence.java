package ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.startup;

import ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.persistence.entity.ProfesionalEjemploEntity;

/** Viola la regla: un disparador de arranque que usa una entidad JPA en vez de llamar al service. */
public class StartupQueUsaPersistence {

    private ProfesionalEjemploEntity entidad;
}
