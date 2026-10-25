package ar.edu.um.programacion2.catalogo.archejemplo.infrastructure;

import ar.edu.um.programacion2.catalogo.web.rest.errors.BadRequestAlertException;

public class FeatureQueUsaWebDeJHipster {

    public void rechazar() {
        throw new BadRequestAlertException("Profesional inválido", "profesional", "invalido");
    }
}
