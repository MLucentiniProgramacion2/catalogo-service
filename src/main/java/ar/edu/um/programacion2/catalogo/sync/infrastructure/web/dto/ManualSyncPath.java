package ar.edu.um.programacion2.catalogo.sync.infrastructure.web.dto;

/**
 * Camino que informa la respuesta de CU-08 (paso 4: "incremental, snapshot o sin cambios"). Es propio del DTO y no
 * {@code SyncPath}: {@code SyncPath} también es el camino de la última sincronización exitosa (CU-09), y "sin cambios"
 * nunca puede ser eso. {@code NONE} y no {@code null}, para que el cliente decida por un valor y no por una ausencia.
 */
public enum ManualSyncPath {
    INCREMENTAL,
    SNAPSHOT,
    NONE,
}
