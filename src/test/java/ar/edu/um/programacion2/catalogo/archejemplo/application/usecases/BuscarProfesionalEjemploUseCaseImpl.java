package ar.edu.um.programacion2.catalogo.archejemplo.application.usecases;

import ar.edu.um.programacion2.catalogo.archejemplo.domain.model.ProfesionalEjemplo;
import ar.edu.um.programacion2.catalogo.archejemplo.domain.ports.in.BuscarProfesionalEjemploUseCase;
import ar.edu.um.programacion2.catalogo.archejemplo.domain.ports.out.ProfesionalEjemploRepository;
import java.util.List;

public class BuscarProfesionalEjemploUseCaseImpl implements BuscarProfesionalEjemploUseCase {

    private final ProfesionalEjemploRepository repository;

    public BuscarProfesionalEjemploUseCaseImpl(ProfesionalEjemploRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<ProfesionalEjemplo> buscarHabilitados(long categoriaId) {
        return repository
            .buscarTodos()
            .stream()
            .filter(ProfesionalEjemplo::atiende)
            .filter(profesional -> profesional.perteneceA(categoriaId))
            .toList();
    }
}
