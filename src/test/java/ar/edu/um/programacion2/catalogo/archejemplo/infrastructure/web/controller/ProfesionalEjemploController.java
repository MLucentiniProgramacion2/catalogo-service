package ar.edu.um.programacion2.catalogo.archejemplo.infrastructure.web.controller;

import ar.edu.um.programacion2.catalogo.config.ApplicationProperties;
import ar.edu.um.programacion2.catalogo.security.SecurityUtils;
import ar.edu.um.programacion2.catalogo.archejemplo.application.service.ProfesionalEjemploService;
import org.springframework.http.ResponseEntity;

/**
 * Usa lo que una feature puede tomar de JHipster: security y ApplicationProperties. No usa Constants porque sus
 * campos son constantes que javac copia en línea y no dejan dependencia en el bytecode.
 */
public class ProfesionalEjemploController {

    private final ProfesionalEjemploService service;
    private final ApplicationProperties applicationProperties;

    public ProfesionalEjemploController(ProfesionalEjemploService service, ApplicationProperties applicationProperties) {
        this.service = service;
        this.applicationProperties = applicationProperties;
    }

    public ResponseEntity<Integer> contarHabilitados(long categoriaId) {
        if (SecurityUtils.getCurrentUserLogin().isEmpty()) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(service.buscarHabilitados(categoriaId).size());
    }
}
