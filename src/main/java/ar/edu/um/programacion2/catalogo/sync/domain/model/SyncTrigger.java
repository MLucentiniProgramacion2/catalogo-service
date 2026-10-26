package ar.edu.um.programacion2.catalogo.sync.domain.model;

/** Qué disparó una sincronización (CU-09): evento de Kafka, arranque, comprobación periódica o pedido manual. */
public enum SyncTrigger {
    KAFKA,
    STARTUP,
    PERIODIC,
    MANUAL,
}
