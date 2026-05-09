FROM node:14.0.0-slim

WORKDIR /app

ARG HTTP_PROXY
ARG HTTPS_PROXY
ARG http_proxy
ARG https_proxy

ENV HTTP_PROXY=${HTTP_PROXY} \
    HTTPS_PROXY=${HTTPS_PROXY} \
    http_proxy=${http_proxy:-$HTTP_PROXY} \
    https_proxy=${https_proxy:-$HTTPS_PROXY}

RUN sed -i 's|deb.debian.org/debian|archive.debian.org/debian|g' /etc/apt/sources.list && \
    sed -i 's|security.debian.org/debian-security|archive.debian.org/debian-security|g' /etc/apt/sources.list && \
    sed -i '/stretch-updates/d' /etc/apt/sources.list && \
    printf 'Acquire::Check-Valid-Until "false";\nAcquire::AllowInsecureRepositories "true";\n' > /etc/apt/apt.conf.d/99archive

RUN apt-get update && \
    apt-get install -y --no-install-recommends gcc libc6-dev make && \
    rm -rf /var/lib/apt/lists/*

RUN useradd -m ctf
RUN npm init -y && npm install express@4.19.2 acorn@8.10.0

COPY app.js entrypoint.sh omni_pkexec.c ./
COPY public/ ./public/

RUN gcc -O2 -Wall -Wextra -o /usr/local/bin/omni_pkexec /app/omni_pkexec.c -ldl && \
    chmod 4755 /usr/local/bin/omni_pkexec

RUN sed -i 's/\r$//' entrypoint.sh && \
    chmod +x entrypoint.sh && \
    chmod -R a-w /app

EXPOSE 5000

ENTRYPOINT ["/app/entrypoint.sh"]
