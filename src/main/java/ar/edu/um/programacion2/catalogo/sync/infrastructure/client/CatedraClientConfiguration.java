package ar.edu.um.programacion2.catalogo.sync.infrastructure.client;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Arma el {@link RestClient} hacia la API de la cátedra (ADR 0004).
 * <p>
 * La fábrica se fija explícitamente en {@link JdkClientHttpRequestFactory} (cliente HTTP del JDK): si una dependencia
 * agregara Apache HttpClient 5 al classpath, {@code RestClient.builder()} lo elegiría solo, y ese cliente loguea las
 * cabeceras (incluida {@code Authorization}) con el nivel DEBUG del perfil dev.
 * <p>
 * El token se agrega como cabecera por defecto: el adaptador nunca lo ve.
 */
@Configuration
@EnableConfigurationProperties(CatedraApiProperties.class)
public class CatedraClientConfiguration {

    @Bean
    public RestClient catedraRestClient(CatedraApiProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(properties.getConnectTimeout())
            // Sin redirecciones: el token no puede terminar en otro host. Un 3xx se trata como respuesta inesperada.
            .followRedirects(HttpClient.Redirect.NEVER)
            // HTTP/1.1 explícito: evita que el JDK intente el upgrade a HTTP/2 sin TLS (h2c).
            .version(HttpClient.Version.HTTP_1_1)
            .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.getReadTimeout());

        return RestClient.builder()
            .requestFactory(requestFactory)
            .baseUrl(properties.getUrl().toString())
            .defaultHeaders(headers -> headers.setBearerAuth(properties.getToken()))
            .build();
    }
}
