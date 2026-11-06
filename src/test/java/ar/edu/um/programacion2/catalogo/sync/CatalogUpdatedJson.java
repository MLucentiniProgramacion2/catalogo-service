package ar.edu.um.programacion2.catalogo.sync;

/**
 * Un {@code CatalogUpdated} con la forma exacta del topic real, copiada con {@code kcat} el 6/11: el {@code eventId} es un
 * UUID, {@code occurredAt} trae nanosegundos y {@code source} es {@code SCHEDULED}. Sin headers.
 */
public final class CatalogUpdatedJson {

    private CatalogUpdatedJson() {}

    public static String of(String eventId, long newVersion) {
        return (
            "{\"eventId\":\"" +
            eventId +
            "\",\"eventType\":\"CatalogUpdated\",\"newVersion\":" +
            newVersion +
            ",\"occurredAt\":\"2026-11-06T01:19:28.029217849Z\",\"source\":\"SCHEDULED\",\"schemaVersion\":1}"
        );
    }
}
