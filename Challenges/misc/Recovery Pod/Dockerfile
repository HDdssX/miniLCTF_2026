FROM python:3.11-alpine

WORKDIR /app

RUN apk add --no-cache git

COPY app.py /app/app.py
COPY build_assets/entrypoint.sh /app/entrypoint.sh
COPY site /app/site

RUN chmod 755 /app/entrypoint.sh

EXPOSE 1337

ENTRYPOINT ["/app/entrypoint.sh"]
