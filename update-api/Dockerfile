# syntax=docker/dockerfile:1

# Build: a static binary with no cgo, so the runtime image needs no libc.
FROM golang:1.27-alpine AS build
WORKDIR /src

# The build context is the repository root (Render always sends the whole repo;
# only the Dockerfile location is configurable), so every source path here is
# root-relative. `.dockerignore` at the root keeps the shipped context down to
# this module — the Android app carries the Linux rootfs in jniLibs.
COPY update-api/go.mod update-api/go.sum ./
RUN go mod download

COPY update-api/cmd ./cmd
COPY update-api/internal ./internal
COPY update-api/web ./web

ARG TARGETOS
ARG TARGETARCH
RUN CGO_ENABLED=0 GOOS=${TARGETOS:-linux} GOARCH=${TARGETARCH:-amd64} \
    go build -trimpath -ldflags "-s -w" -o /out/update-api ./cmd/server

# Runtime: distroless static, non-root, no shell, nothing to probe but the API.
FROM gcr.io/distroless/static-debian12:nonroot

COPY --from=build /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/
COPY --from=build /out/update-api /usr/local/bin/update-api

ENV PORT=8080 \
    STORAGE_PATH=/data \
    LOG_FORMAT=json \
    LOG_LEVEL=info

EXPOSE 8080

# Cached APKs and the metadata database live here. Bind a persistent volume:
# an ephemeral filesystem loses the cache on every restart and re-downloads
# every artifact from GitHub on the first pass.
VOLUME ["/data"]

USER 65532:65532

ENTRYPOINT ["/usr/local/bin/update-api"]
