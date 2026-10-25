package ar.edu.um.programacion2.catalogo.archejemplo.domain.ports.out;

import ar.edu.um.programacion2.catalogo.archejemplo.domain.model.ProfesionalEjemplo;
import java.util.List;
import java.util.Optional;

public interface ProfesionalEjemploRepository {
    Optional<ProfesionalEjemplo> buscarPorId(long id);

    List<ProfesionalEjemplo> buscarTodos();
}
