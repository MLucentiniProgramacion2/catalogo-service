package ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.web;

import ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.redis.LectorRedisEjemplo;

public class WebQueUsaRedis {

    public String responder(LectorRedisEjemplo lector) {
        return lector.leer("catedra:sync:metadata");
    }
}
