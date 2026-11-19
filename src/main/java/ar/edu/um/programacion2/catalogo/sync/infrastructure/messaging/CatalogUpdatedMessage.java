package ar.edu.um.programacion2.catalogo.sync.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Cuerpo de {@code CatalogUpdated} (contrato §15.3), tal como llega por Kafka, con los dos campos que usa CU-03.
 * <p>
 * {@code newVersion} es un tipo envoltorio: si falta llega como {@code null} y el mapper lo rechaza, en vez de convertirse
 * en {@code 0}. Lo demás ({@code eventType}, {@code occurredAt}, {@code source}, {@code schemaVersion}) y los campos
 * nuevos se ignoran de forma explícita (§15.1), sin depender de la configuración de Jackson. Es package-private: los DTO
 * no salen de este paquete.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record CatalogUpdatedMessage(String eventId, Long newVersion) {}
