FROM postgres:16-bookworm

# Instala os arquivos de extensão que permitem habilitar o PostGIS em cada banco.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       postgresql-16-postgis-3 \
       postgresql-16-postgis-3-scripts \
    && rm -rf /var/lib/apt/lists/*