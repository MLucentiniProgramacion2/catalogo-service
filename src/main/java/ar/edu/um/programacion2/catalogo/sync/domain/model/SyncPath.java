package ar.edu.um.programacion2.catalogo.sync.domain.model;

/** Camino por el que se llegó a la versión local: snapshot REST (CU-02) o cambios incrementales de Redis (CU-04). */
public enum SyncPath {
    SNAPSHOT,
    INCREMENTAL,
}
