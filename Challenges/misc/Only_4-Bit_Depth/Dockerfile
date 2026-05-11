FROM python:3.11-alpine

WORKDIR /app

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

RUN pip install --no-cache-dir Flask==3.1.1

COPY app.py /app/app.py
COPY codec.py /app/codec.py
COPY templates /app/templates
COPY static /app/static
COPY build /app/build

RUN chmod +x /app/build/entrypoint.sh

EXPOSE 8000

ENTRYPOINT ["/app/build/entrypoint.sh"]
