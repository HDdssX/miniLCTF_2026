FROM bellsoft/liberica-openjdk-alpine:17

WORKDIR /app
COPY ezff.jar /app
COPY entrypoint.sh /
RUN chmod +x /entrypoint.sh
EXPOSE 8888
CMD ["/entrypoint.sh"]
