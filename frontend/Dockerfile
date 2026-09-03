FROM nginx:1.26-alpine@sha256:1eadbb07820339e8bbfed18c771691970baee292ec4ab2558f1453d26153e22d
RUN mkdir -p /tmp/nginx/client_temp /tmp/nginx/proxy_temp /tmp/nginx/fastcgi_temp /tmp/nginx/uwsgi_temp /tmp/nginx/scgi_temp \
    && chown -R nginx:nginx /tmp/nginx /var/cache/nginx /usr/share/nginx/html \
    && sed -i 's#pid        /var/run/nginx.pid;#pid        /tmp/nginx.pid;#; s#^error_log  /var/log/nginx/error.log notice;#error_log  /dev/stderr notice;#; s#^    access_log  /var/log/nginx/access.log  main;#    access_log  /dev/stdout  main;#' /etc/nginx/nginx.conf
COPY --chown=nginx:nginx nginx.conf /etc/nginx/conf.d/default.conf
COPY --chown=nginx:nginx dist /usr/share/nginx/html
USER nginx
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -q -O /dev/null http://127.0.0.1:8080/ || exit 1
ENTRYPOINT ["nginx", "-g", "daemon off;"]
