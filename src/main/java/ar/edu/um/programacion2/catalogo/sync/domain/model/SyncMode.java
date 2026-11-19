package ar.edu.um.programacion2.catalogo.sync.domain.model;

/** Modo de la sincronización manual (CU-08, DP-10). */
public enum SyncMode {
    /** Incorporar los cambios pendientes con la lógica de CU-03: incremental o snapshot según Redis, o sin cambios. */
    NORMAL,

    /** Reconstruir la copia completa con CU-02, aunque la versión esté al día. */
    SNAPSHOT,
}
