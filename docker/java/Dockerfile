FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

COPY pom.xml .

RUN mvn -B dependency:go-offline

COPY src ./src

RUN mvn -B -DskipTests package


FROM eclipse-temurin:25-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 tracking \
    && useradd \
        --uid 10001 \
        --gid 10001 \
        --no-create-home \
        --shell /usr/sbin/nologin \
        tracking

WORKDIR /app

COPY --from=build \
    --chown=10001:10001 \
    /app/target/aporia-tracking-service-1.0-SNAPSHOT.jar \
    /app/app.jar

RUN mkdir -p /app/runtime \
    && chown -R 10001:10001 /app/runtime

USER 10001:10001

EXPOSE 5500 9100

ENTRYPOINT ["java", "-jar", "/app/app.jar"]