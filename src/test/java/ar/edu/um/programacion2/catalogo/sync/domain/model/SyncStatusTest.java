package ar.edu.um.programacion2.catalogo.sync.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class SyncStatusTest {

    private static final Instant T1 = Instant.parse("2026-09-10T13:45:00Z");
    private static final Instant T2 = Instant.parse("2026-09-10T14:00:00Z");

    @Nested
    class EstadoInicial {

        @Test
        void noTieneVersionLocalNiNingunDato() {
            SyncStatus estado = SyncStatus.initial();

            assertThat(estado.hasLocalCopy()).isFalse();
            assertThat(estado.getLocalVersion()).isNull();
            assertThat(estado.getLastSuccessAt()).isNull();
            assertThat(estado.getRemoteVersion()).isNull();
            assertThat(estado.getLastErrorAt()).isNull();
        }
    }

    @Nested
    class Snapshot {

        @Test
        void guardaLaVersionConFechaCaminoYDisparador() {
            SyncStatus estado = SyncStatus.initial();

            estado.recordSnapshotApplied(7, SyncTrigger.STARTUP, T1);

            assertThat(estado.hasLocalCopy()).isTrue();
            assertThat(estado.getLocalVersion()).isEqualTo(7);
            assertThat(estado.getLastSuccessAt()).isEqualTo(T1);
            assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.SNAPSHOT);
            assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.STARTUP);
        }

        @Test
        void aceptaUnaVersionMenorQueLaLocalPorqueLaVersionEsLaDeLosDatosGuardados() {
            SyncStatus estado = conVersionLocal(10);

            estado.recordSnapshotApplied(4, SyncTrigger.MANUAL, T2);

            assertThat(estado.getLocalVersion()).isEqualTo(4);
        }

        @Test
        void rechazaDisparadorOFechaNulos() {
            SyncStatus estado = SyncStatus.initial();

            assertThatNullPointerException().isThrownBy(() -> estado.recordSnapshotApplied(7, null, T1));
            assertThatNullPointerException().isThrownBy(() -> estado.recordSnapshotApplied(7, SyncTrigger.KAFKA, null));
            assertThat(estado.hasLocalCopy()).isFalse();
        }
    }

    @Nested
    class Incremental {

        @Test
        void avanzaALaVersionSiguiente() {
            SyncStatus estado = conVersionLocal(7);

            estado.recordIncrementalApplied(8, SyncTrigger.KAFKA, T2);

            assertThat(estado.getLocalVersion()).isEqualTo(8);
            assertThat(estado.getLastSuccessAt()).isEqualTo(T2);
            assertThat(estado.getLastSuccessPath()).isEqualTo(SyncPath.INCREMENTAL);
            assertThat(estado.getLastSuccessTrigger()).isEqualTo(SyncTrigger.KAFKA);
        }

        @Test
        void rechazaSinVersionLocalPorqueCorrespondeUnSnapshot() {
            SyncStatus estado = SyncStatus.initial();

            assertThatIllegalStateException()
                .isThrownBy(() -> estado.recordIncrementalApplied(1, SyncTrigger.KAFKA, T1))
                .withMessageContaining("snapshot");
            assertThat(estado.hasLocalCopy()).isFalse();
        }

        @Test
        void rechazaSaltearUnaVersion() {
            SyncStatus estado = conVersionLocal(7);

            assertThatIllegalStateException().isThrownBy(() -> estado.recordIncrementalApplied(9, SyncTrigger.KAFKA, T2));
            assertThat(estado.getLocalVersion()).isEqualTo(7);
        }

        @Test
        void rechazaReaplicarLaVersionLocal() {
            SyncStatus estado = conVersionLocal(7);

            assertThatIllegalStateException().isThrownBy(() -> estado.recordIncrementalApplied(7, SyncTrigger.KAFKA, T2));
            assertThat(estado.getLastSuccessAt()).isEqualTo(T1);
        }

        @Test
        void rechazaUnaVersionAnterior() {
            SyncStatus estado = conVersionLocal(7);

            assertThatIllegalStateException().isThrownBy(() -> estado.recordIncrementalApplied(6, SyncTrigger.PERIODIC, T2));
            assertThat(estado.getLocalVersion()).isEqualTo(7);
        }
    }

    @Nested
    class VersionRemota {

        @Test
        void guardaLaVersionYCuandoSeLeyo() {
            SyncStatus estado = SyncStatus.initial();

            estado.recordRemoteVersion(9, T1);

            assertThat(estado.getRemoteVersion()).isEqualTo(9);
            assertThat(estado.getRemoteVersionCheckedAt()).isEqualTo(T1);
        }

        @Test
        void noCambiaLaVersionLocal() {
            SyncStatus estado = conVersionLocal(7);

            estado.recordRemoteVersion(9, T2);

            assertThat(estado.getLocalVersion()).isEqualTo(7);
        }

        @Test
        void rechazaFechaNula() {
            assertThatNullPointerException().isThrownBy(() -> SyncStatus.initial().recordRemoteVersion(9, null));
        }
    }

    @Nested
    class UltimoError {

        @Test
        void guardaFechaTipoYDescripcionSinTocarElUltimoExito() {
            SyncStatus estado = conVersionLocal(7);

            estado.recordError(SyncErrorType.REDIS_UNAVAILABLE, "Redis no respondió", T2);

            assertThat(estado.getLastErrorAt()).isEqualTo(T2);
            assertThat(estado.getLastErrorType()).isEqualTo(SyncErrorType.REDIS_UNAVAILABLE);
            assertThat(estado.getLastErrorDescription()).isEqualTo("Redis no respondió");
            assertThat(estado.getLocalVersion()).isEqualTo(7);
            assertThat(estado.getLastSuccessAt()).isEqualTo(T1);
        }

        @Test
        void unExitoPosteriorNoBorraElUltimoError() {
            SyncStatus estado = SyncStatus.initial();
            estado.recordError(SyncErrorType.SNAPSHOT_FAILED, "La cátedra no respondió", T1);

            estado.recordSnapshotApplied(7, SyncTrigger.STARTUP, T2);

            assertThat(estado.getLastErrorType()).isEqualTo(SyncErrorType.SNAPSHOT_FAILED);
            assertThat(estado.getLastErrorAt()).isBefore(estado.getLastSuccessAt());
        }

        @Test
        void rechazaDescripcionEnBlanco() {
            SyncStatus estado = SyncStatus.initial();

            assertThatIllegalArgumentException().isThrownBy(() -> estado.recordError(SyncErrorType.LOCK_TIMEOUT, " ", T1));
            assertThat(estado.getLastErrorAt()).isNull();
        }

        @Test
        void rechazaTipoDescripcionOFechaNulos() {
            SyncStatus estado = SyncStatus.initial();

            assertThatNullPointerException().isThrownBy(() -> estado.recordError(null, "x", T1));
            assertThatNullPointerException().isThrownBy(() -> estado.recordError(SyncErrorType.LOCK_TIMEOUT, null, T1));
            assertThatNullPointerException().isThrownBy(() -> estado.recordError(SyncErrorType.LOCK_TIMEOUT, "x", null));
            assertThat(estado.getLastErrorAt()).isNull();
        }
    }

    @Nested
    class ConstructorCompleto {

        @Test
        void reconstruyeUnEstadoGuardado() {
            SyncStatus estado = new SyncStatus(
                7L,
                T1,
                SyncPath.INCREMENTAL,
                SyncTrigger.KAFKA,
                9L,
                T2,
                T2,
                SyncErrorType.REDIS_UNAVAILABLE,
                "Redis no respondió"
            );

            assertThat(estado.getLocalVersion()).isEqualTo(7);
            assertThat(estado.getRemoteVersion()).isEqualTo(9);
            assertThat(estado.getLastErrorType()).isEqualTo(SyncErrorType.REDIS_UNAVAILABLE);
        }

        @Test
        void rechazaUnExitoIncompleto() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new SyncStatus(7L, T1, null, SyncTrigger.KAFKA, null, null, null, null, null))
                .withMessageContaining("sincronización exitosa");
        }

        @Test
        void rechazaUnaVersionRemotaSinFecha() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new SyncStatus(null, null, null, null, 9L, null, null, null, null))
                .withMessageContaining("versión remota");
        }

        @Test
        void rechazaUnErrorIncompleto() {
            assertThatIllegalArgumentException()
                .isThrownBy(() -> new SyncStatus(null, null, null, null, null, null, T1, SyncErrorType.LOCK_TIMEOUT, null))
                .withMessageContaining("último error");
        }

        @Test
        void rechazaUnErrorConDescripcionEnBlanco() {
            assertThatIllegalArgumentException().isThrownBy(() ->
                new SyncStatus(null, null, null, null, null, null, T1, SyncErrorType.LOCK_TIMEOUT, "")
            );
        }
    }

    private static SyncStatus conVersionLocal(long version) {
        SyncStatus estado = SyncStatus.initial();
        estado.recordSnapshotApplied(version, SyncTrigger.STARTUP, T1);
        return estado;
    }
}
