package ar.edu.um.programacion2.catalogo.sync;

import java.util.List;

/**
 * Cuerpos de {@code GET /api/synchronization/snapshot} (contrato §7) para la cátedra simulada de los tests de
 * integración. Solo arma JSON válido en formato; las referencias rotas se arman pasando IDs que no existen.
 */
public final class SnapshotJson {

    private static final String CREADO = "2026-07-01T12:00:00Z";

    private SnapshotJson() {}

    public static String snapshot(long version, List<String> categories, List<String> professionals, List<String> schedules) {
        return (
            "{\"snapshotVersion\": " +
            version +
            ", \"generatedAt\": \"2026-10-30T13:44:59Z\", \"professionalCategories\": [" +
            String.join(",", categories) +
            "], \"professionals\": [" +
            String.join(",", professionals) +
            "], \"weeklySchedules\": [" +
            String.join(",", schedules) +
            "]}"
        );
    }

    public static String category(long id) {
        return (
            "{\"id\": " +
            id +
            ", \"name\": \"Categoria " +
            id +
            "\", \"description\": null, \"enabled\": true, \"createdAt\": \"" +
            CREADO +
            "\", \"updatedAt\": \"" +
            CREADO +
            "\"}"
        );
    }

    public static String professional(long id, long categoryId) {
        return (
            "{\"id\": " +
            id +
            ", \"categoryId\": " +
            categoryId +
            ", \"firstName\": \"Ana\", \"lastName\": \"Gomez\", \"enabled\": true, \"createdAt\": \"" +
            CREADO +
            "\", \"updatedAt\": \"" +
            CREADO +
            "\"}"
        );
    }

    public static String schedule(long id, long professionalId) {
        return (
            "{\"id\": " +
            id +
            ", \"professionalId\": " +
            professionalId +
            ", \"dayOfWeek\": \"MONDAY\", \"startTime\": \"09:00\", \"endTime\": \"12:00:00\", \"slotDurationMinutes\": 30," +
            " \"enabled\": true, \"createdAt\": \"" +
            CREADO +
            "\", \"updatedAt\": \"" +
            CREADO +
            "\"}"
        );
    }
}
