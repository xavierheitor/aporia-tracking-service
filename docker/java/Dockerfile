
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -B -DskipTests package


# A imagem de execução recebe o mesmo JAR usado pelo gateway e pelo worker.
FROM eclipse-temurin:25-jre

WORKDIR /app

COPY --from=build \
    /app/target/aporia-tracking-service-1.0-SNAPSHOT.jar \
    /app/app.jar

CMD ["java", "-cp", "/app/app.jar", "software.aporia.tracking.worker.WorkerApplication"]
