/**
 * Feature de ejemplo que existe solo en tests. La usa {@code ArquitecturaDetectaViolacionesTest} para comprobar
 * que las reglas de ArchUnit detectan cada violación y no marcan las clases que cumplen.
 * <p>
 * Está directamente bajo {@code catalogo} para que los patrones reales ({@code catalogo.*.domain..}, etc.) la
 * incluyan. Las clases no llevan anotaciones de Spring ni {@code @Entity}: si las tuvieran, los tests de
 * integración las registrarían en el contexto al escanear {@code catalogo..}.
 * <p>
 * Las clases {@code DominioQueUsa*}, {@code ApplicationQueUsa*} y {@code FeatureQueUsa*} violan una regla cada una;
 * el resto cumple todas.
 */
package ar.edu.um.programacion2.catalogo.archejemplo;
