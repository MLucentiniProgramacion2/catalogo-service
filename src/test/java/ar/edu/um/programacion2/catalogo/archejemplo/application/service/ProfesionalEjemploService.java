package ar.edu.um.programacion2.catalogo.archejemplo.application.service;

import ar.edu.um.programacion2.catalogo.archejemplo.domain.model.ProfesionalEjemplo;
import ar.edu.um.programacion2.catalogo.archejemplo.domain.ports.in.BuscarProfesionalEjemploUseCase;
import java.util.List;

public class ProfesionalEjemploService {

    private final BuscarProfesionalEjemploUseCase buscarProfesional;

    public ProfesionalEjemploService(BuscarProfesionalEjemploUseCase buscarProfesional) {
        this.buscarProfesional = buscarProfesional;
    }

    public List<ProfesionalEjemplo> buscarHabilitados(long categoriaId) {
        return buscarProfesional.buscarHabilitados(categoriaId);
    }
}
