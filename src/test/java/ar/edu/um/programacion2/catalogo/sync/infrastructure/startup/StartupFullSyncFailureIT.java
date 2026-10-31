package ar.edu.um.programacion2.catalogo.sync.infrastructure.startup;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.um.programacion2.catalogo.IntegrationTest;
import ar.edu.um.programacion2.catalogo.sync.SyncTestDatabase;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncErrorType;
import ar.edu.um.programacion2.catalogo.sync.domain.model.SyncStatus;
import ar.edu.um.programacion2.catalogo.sync.domain.ports.out.SyncStatusRepository;
import ar.edu.um.programacion2.catalogo.sync.infrastructure.client.FakeCatedraServer;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CU-01, flujo 2a, con la cátedra rechazando la credencial técnica (401): el servicio queda levantado, no se reintenta y
 * queda {@code INVALID_CREDENTIALS} registrado, sin el token ni en el estado ni en el log.
 * <p>
 * {@link OutputCaptureExtension} captura la salida desde antes de crear el contexto: el arranque corre mientras se
 * prepara la instancia del test, así que sus logs quedan en {@link CapturedOutput}. Por eso hay un solo test y la
 * limpieza va en {@code @AfterEach}.
 */
@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@Import(EmptyDatabaseBeforeStartup.class)
@TestPropertySource(properties = "sync.startup.enabled=true")
class StartupFullSyncFailureIT {

    private static final String TOKEN = "token-falso-solo-para-tests";

    private static final FakeCatedraServer CATEDRA = startCatedra();

    @Autowired
    private ConfigurableApplicationContext context;

    @Autowired
    private SyncStatusRepository syncStatusRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private EntityManager em;

    @DynamicPropertySource
    static void catedraSimulada(DynamicPropertyRegistry registry) {
        registry.add("catedra.api.url", () -> CATEDRA.baseUrl().toString());
    }

    @AfterEach
    void dejarLaBaseVacia() {
        SyncTestDatabase.reset(transactionTemplate, em);
    }

    @AfterAll
    static void apagarCatedra() {
        CATEDRA.close();
    }

    @Test
    void conLaCredencialRechazadaElServicioQuedaLevantadoYElErrorRegistradoSinElToken(CapturedOutput salida) {
        assertThat(context.isActive()).isTrue();

        // Un solo pedido: el 401 no se reintenta.
        assertThat(CATEDRA.requests()).hasSize(1);

        SyncStatus estado = transactionTemplate.execute(tx -> syncStatusRepository.load());
        assertThat(estado.hasLocalCopy()).isFalse();
        assertThat(estado.getLastErrorType()).isEqualTo(SyncErrorType.INVALID_CREDENTIALS);
        assertThat(estado.getLastErrorDescription())
            .isEqualTo("La cátedra rechazó la credencial técnica: La cátedra respondió 401 (code=UNAUTHORIZED) al pedir el snapshot")
            .doesNotContain(TOKEN);
        assertThat(SyncTestDatabase.categoryIds(transactionTemplate, em)).isEmpty();

        // La captura funciona (está la línea del error) y el token no aparece en ninguna parte de la salida.
        assertThat(salida.getAll())
            .contains("Sincronización completa (STARTUP) fallida: La cátedra rechazó la credencial técnica")
            .doesNotContain(TOKEN);
    }

    private static FakeCatedraServer startCatedra() {
        try {
            FakeCatedraServer catedra = FakeCatedraServer.start();
            catedra.respondProblem(401, "UNAUTHORIZED");
            return catedra;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
