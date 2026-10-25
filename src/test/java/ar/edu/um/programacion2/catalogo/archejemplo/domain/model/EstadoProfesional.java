package ar.edu.um.programacion2.catalogo.archejemplo.domain.model;

public enum EstadoProfesional {
    HABILITADO,
    DESHABILITADO;

    public boolean atiende() {
        return this == HABILITADO;
    }
}
