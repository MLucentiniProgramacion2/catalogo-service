package ar.edu.um.programacion2.catalogo.archejemplo.domain;

import ar.edu.um.programacion2.catalogo.security.SecurityUtils;

public class DominioQueUsaSecurityDeJHipster {

    public boolean puedeEditar() {
        return SecurityUtils.isAuthenticated();
    }
}
