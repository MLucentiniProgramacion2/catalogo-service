package ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.periodic;

import ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.redis.LectorRedisEjemplo;

/** Viola la regla: un disparador periódico que lee Redis por su cuenta en vez de llamar al service. */
public class PeriodicQueUsaRedis {

    public String comprobar(LectorRedisEjemplo lector) {
        return lector.leer("catedra:sync:metadata");
    }
}
