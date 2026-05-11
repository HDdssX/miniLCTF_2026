FROM alpine:3.19

# Install dependencies, PostgreSQL, Python, and download PostgREST
RUN apk add --no-cache postgresql postgresql-contrib wget tar xz python3 py3-pip && \
    wget https://github.com/PostgREST/postgrest/releases/download/v12.2.0/postgrest-v12.2.0-linux-static-x64.tar.xz && \
    tar -xJvf postgrest-v12.2.0-linux-static-x64.tar.xz && \
    mv postgrest /usr/local/bin/ && \
    rm postgrest-v12.2.0-linux-static-x64.tar.xz

WORKDIR /app

# Setup Python environment
RUN pip3 install --no-cache-dir --break-system-packages flask requests

COPY app.py /app/app.py
COPY templates/ /app/templates/
COPY static/ /app/static/

# Copy PostgREST configuration
COPY build_assets/postgrest.conf /app/postgrest.conf

# Initialization script
COPY build_assets/start.sh /app/start.sh
RUN chmod +x /app/start.sh

# Configure PostgreSQL Database
RUN mkdir -p /run/postgresql && chown -R postgres:postgres /run/postgresql && \
    mkdir -p /var/lib/postgresql/data && chown -R postgres:postgres /var/lib/postgresql/data

USER postgres
COPY --chown=postgres:postgres build_assets/init.sql /tmp/init.sql
COPY --chown=postgres:postgres build_assets/omikuji_data.sql /tmp/omikuji_data.sql
RUN initdb -D /var/lib/postgresql/data && \
    pg_ctl start -D /var/lib/postgresql/data && sleep 3 && \
    psql -d postgres -f /tmp/omikuji_data.sql && \
    psql -d postgres -f /tmp/init.sql && \
    pg_ctl stop -D /var/lib/postgresql/data

USER root

# Expose Web port
EXPOSE 5000

CMD ["/app/start.sh"]
