# Stufe 1: bauen
# Der Build-Kontext ist das Projekt-Wurzelverzeichnis, weil das Modul
# das Eltern-POM braucht. Maven liest dabei alle Module aus dem
# Eltern-POM, darum auch das POM des chat-service.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY chat-service/pom.xml chat-service/pom.xml
COPY batch-writer/pom.xml batch-writer/pom.xml
COPY batch-writer/src batch-writer/src
# Tests werden hier übersprungen: Testcontainers bräuchte einen Docker-Daemon
# INNERHALB des Builds. Getestet wird vorher mit "mvn test".
RUN mvn -q -pl batch-writer -am package -DskipTests

# Stufe 2: laufen
FROM eclipse-temurin:21-jre
# Eigener Benutzer ohne Root-Rechte. Der Dienst braucht keine, und ein
# Fehler im Code bekommt so auch keine.
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build /build/batch-writer/target/batch-writer-0.1.0-SNAPSHOT.jar app.jar
USER app
# Kein EXPOSE: der batch-writer hat keinen Port. Er baut Verbindungen zu
# RabbitMQ und PostgreSQL nur selbst auf.
ENTRYPOINT ["java", "-jar", "app.jar"]
