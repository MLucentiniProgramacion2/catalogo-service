# syntax=docker/dockerfile:1
#
# Imagen del servicio de catálogo, en dos etapas. La usa el docker-compose.yml de la raíz:
#   docker compose up -d --build
# Solo hace falta Docker: Java y Maven están adentro de la primera etapa.

# --- Etapa 1: compila con el wrapper de Maven y el perfil prod ---
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /build

# Lo que necesita Maven, nada más (el .dockerignore es una lista blanca: el .env nunca entra).
# sonar-project.properties lo lee el properties-maven-plugin en todas las construcciones.
COPY .mvn/ .mvn/
COPY mvnw pom.xml checkstyle.xml sonar-project.properties ./
COPY src/ src/
# Solo para git-commit-id-maven-plugin: /management/info muestra el commit de la imagen. No llega a la etapa final.
COPY .git/ .git/

# La caché de Maven vive afuera de la imagen (BuildKit): una reconstrucción no vuelve a bajar las dependencias.
# Los tests no corren acá: los corre ./mvnw clean verify (y el CI). El JAR se separa en capas para que un cambio
# de código no vuelva a copiar las dependencias.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -Pprod -DskipTests package \
    && java -Djarmode=tools -jar target/catalogo-service-*.jar extract --layers --launcher --destination /build/extracted

# --- Etapa 2: solo el JRE y la aplicación ---
FROM eclipse-temurin:25-jre-noble
WORKDIR /app
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./

# Sin root: el usuario 1000 ("ubuntu") ya existe en la imagen base.
USER 1000
EXPOSE 8081
# Forma exec: java es el PID 1 y recibe el SIGTERM de docker compose stop (cierre ordenado, sale con 143).
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
