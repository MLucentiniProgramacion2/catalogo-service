package ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncPath;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncTrigger;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.persistence.entity.SyncStatusEntity;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SyncStatusMapperTest {

    private static final Instant T1 = Instant.parse("2026-09-10T13:45:00Z");
    private static final Instant T2 = Instant.parse("2026-09-10T14:00:00Z");
    private static final Instant T3 = Instant.parse("2026-09-10T14:05:00Z");

    private final SyncStatusMapper mapper = new SyncStatusMapper();

    @Test
    void toEntityCopiaLosNueveCamposYFijaElIdDeLaFilaUnica() {
        SyncStatusEntity entidad = mapper.toEntity(estadoCompleto());

        assertThat(entidad.getId()).isEqualTo(SyncStatusEntity.SINGLE_ROW_ID);
        assertThat(entidad.getLocalVersion()).isEqualTo(7L);
        assertThat(entidad.getLastSuccessAt()).isEqualTo(T1);
        assertThat(entidad.getLastSuccessPath()).isEqualTo(SyncPath.SNAPSHOT);
        assertThat(entidad.getLastSuccessTrigger()).isEqualTo(SyncTrigger.STARTUP);
        assertThat(entidad.getRemoteVersion()).isEqualTo(8L);
        assertThat(entidad.getRemoteVersionCheckedAt()).isEqualTo(T2);
        assertThat(entidad.getLastErrorAt()).isEqualTo(T3);
        assertThat(entidad.getLastErrorType()).isEqualTo(SyncErrorType.REDIS_UNAVAILABLE);
        assertThat(entidad.getLastErrorDescription()).isEqualTo("Redis no respondió");
    }

    @Test
    void idaYVueltaConservaLosNueveCampos() {
        SyncStatus leido = mapper.toDomain(mapper.toEntity(estadoCompleto()));

        assertThat(leido).usingRecursiveComparison().isEqualTo(estadoCompleto());
    }

    @Test
    void idaYVueltaConservaElEstadoInicial() {
        SyncStatus leido = mapper.toDomain(mapper.toEntity(SyncStatus.initial()));

        assertThat(leido).usingRecursiveComparison().isEqualTo(SyncStatus.initial());
    }

    @Test
    void toDomainRechazaUnaFilaConUnGrupoAMedias() {
        // Versión local sin fecha, camino ni disparador: la base no lo impide, pero el dominio no lo acepta.
        SyncStatusEntity entidad = mapper.toEntity(SyncStatus.initial());
        entidad.setLocalVersion(7L);

        assertThatIllegalArgumentException()
            .isThrownBy(() -> mapper.toDomain(entidad))
            .withMessageContaining("la última sincronización exitosa");
    }

    private static SyncStatus estadoCompleto() {
        return new SyncStatus(
            7L,
            T1,
            SyncPath.SNAPSHOT,
            SyncTrigger.STARTUP,
            8L,
            T2,
            T3,
            SyncErrorType.REDIS_UNAVAILABLE,
            "Redis no respondió"
        );
    }
}
