package ar.edu.um.programacion2.catalogo.sync.domain.model;

import java.util.Objects;

/**
 * Lo que informa CU-09: el estado guardado más si hay una sincronización corriendo ahora. Van separados porque salen de
 * lugares distintos: el estado de la base y "en curso" de la exclusión mutua, que no se persiste (ver CU-09).
 */
public record SyncOverview(SyncStatus status, boolean inProgress) {
    public SyncOverview {
        Objects.requireNonNull(status, "Falta el estado de sincronización");
    }
}
