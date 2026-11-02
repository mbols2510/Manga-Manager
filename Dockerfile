FROM python:3.12-slim

LABEL org.opencontainers.image.title="manga-manager" \
      org.opencontainers.image.description="Suwayomi to Kavita mover, double-chapter finder, collection manager and CBZ editor in one dashboard" \
      org.opencontainers.image.source="https://github.com/mbols2510/Manga-Manager"

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PUID=1000 \
    PGID=1000 \
    UMASK=022 \
    TZ=Etc/UTC \
    MANGA_MANAGER_CONFIG=/config/config.yml

RUN apt-get update \
 && apt-get install -y --no-install-recommends tzdata \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY manga_manager ./manga_manager
COPY config.example.yml /app/config.example.yml
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

VOLUME ["/config", "/data"]
EXPOSE 8080

HEALTHCHECK --interval=60s --timeout=5s --start-period=30s --retries=3 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8080/health', timeout=4)" || exit 1

ENTRYPOINT ["/entrypoint.sh"]
CMD ["serve"]
