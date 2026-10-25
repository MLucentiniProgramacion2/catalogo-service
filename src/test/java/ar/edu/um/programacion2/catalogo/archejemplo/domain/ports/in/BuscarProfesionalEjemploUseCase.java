package ar.edu.um.programacion2.catalogo.archejemplo.domain.ports.in;

import ar.edu.um.programacion2.catalogo.archejemplo.domain.model.ProfesionalEjemplo;
import java.util.List;

public interface BuscarProfesionalEjemploUseCase {
    List<ProfesionalEjemplo> buscarHabilitados(long categoriaId);
}
