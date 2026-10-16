# Production edge image: builds the React SPA (Node 22) and serves the static
# dist together with the Caddy reverse proxy that fronts the backend replicas.
# Caddy handles TLS (internal CA for local verification, ACME for a real domain),
# same-origin routing of /v1 and /actuator to the app service, and SPA fallback.

# WS7: base images are digest-pinned to the exact linux/amd64 manifests recorded
# 2026-10-06. Re-resolve when updating toolchains: docker pull <tag> && docker
# image inspect --format '{{index .RepoDigests 0}}' <tag>.

# syntax=docker/dockerfile:1

FROM node:22-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402 AS build
WORKDIR /build
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ .
RUN npm run build

FROM caddy:2.8-alpine@sha256:af32e97399febea808609119bb21544d0265c58a02836576e32a2d082c262c17

# Dedicated non-root edge identity. The base image ships root-only, so create
# the run user here. In a container the sub-1024 binds (80/443) are permitted
# for unprivileged users, and Caddy keeps its runtime state under /data and
# /config (tmpfs in compose), so the whole image runs read-only as 10001.
RUN addgroup -g 10001 -S caddy \
    && adduser -u 10001 -S -D -H -s /sbin/nologin -G caddy caddy \
    && setcap -r /usr/bin/caddy 2>/dev/null || true \
    && apk add --no-cache --virtual .hc-deps curl

COPY --from=build /build/dist /srv
COPY infrastructure/caddy/Caddyfile /etc/caddy/Caddyfile

RUN chown -R caddy:caddy /data /config \
    && chmod -R a+rX /srv

USER caddy
EXPOSE 80 443

HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=5 \
    CMD ["sh", "-c", "curl -fsSk https://localhost/healthz >/dev/null 2>&1 || exit 1"]

CMD ["caddy", "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"]