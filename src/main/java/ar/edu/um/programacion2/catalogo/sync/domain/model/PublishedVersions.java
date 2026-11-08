package ar.edu.um.programacion2.catalogo.sync.domain.model;

/**
 * Las versiones que publica la cátedra (contrato §14.2 y §14.3): la última y la más vieja cuyos cambios incrementales
 * todavía están disponibles.
 * <p>
 * No se exige {@code oldestAvailableVersion <= currentVersion}: el contrato no lo dice, y la regla del camino (CU-03,
 * paso 8) da un resultado coherente igual. Con la local menor que {@code oldestAvailableVersion} va un snapshot; si no,
 * con la local mayor o igual que {@code currentVersion}, la copia está al día.
 */
public record PublishedVersions(long currentVersion, long oldestAvailableVersion) {}
