package ar.edu.um.programacion2.catalogo.catalog.domain.ports.in;

import ar.edu.um.programacion2.catalogo.catalog.domain.model.ProfessionalAgenda;
import ar.edu.um.programacion2.catalogo.catalog.domain.model.Versioned;

/** CU-07: la agenda vigente de un profesional, leída de la copia local, con la versión a la que corresponde. */
public interface GetProfessionalAgendaUseCase {
    Versioned<ProfessionalAgenda> getProfessionalAgenda(long professionalId);
}
