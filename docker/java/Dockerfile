# Build em dois estágios: o Maven compila o JAR e a imagem final leva apenas o
# JRE e o artefato. Isso reduz tamanho e superfície de ataque.

# Estágio 1: resolve dependências antes de copiar o código para aproveitar cache.
FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

COPY pom.xml .

RUN mvn -B dependency:go-offline

COPY src ./src

# Os testes rodam fora da imagem; a imagem só empacota o artefato da revisão.
RUN mvn -B -DskipTests package


# Estágio 2: runtime mínimo, usuário sem privilégios e curl para o healthcheck.
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

# O diretório runtime guarda o arquivo de propriedade do dispatcher.
COPY --from=build \
    --chown=10001:10001 \
    /app/target/aporia-tracking-service-1.0-SNAPSHOT.jar \
    /app/app.jar

RUN mkdir -p /app/runtime \
    && chown -R 10001:10001 /app/runtime

USER 10001:10001

# 5500: TCP dos rastreadores. 9100: Actuator (management).
EXPOSE 5500 9100

ENTRYPOINT ["java", "-jar", "/app/app.jar"]