FROM ubuntu:22.04

WORKDIR /app

COPY build/out/check /app/check
COPY build/run_check.sh /app/run_check.sh
RUN mkdir -p /opt/tools
COPY build/recover_flag.py /opt/tools/recover_flag.py

RUN printf '%s\n' \
  'Temporary audit image' \
  'The checker binary survived.' \
  'A recovery helper was removed in a later layer.' \
  'Layer history matters more than the final filesystem.' \
  > /README.txt

RUN rm /opt/tools/recover_flag.py

CMD ["/app/run_check.sh"]
