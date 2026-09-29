# syntax=docker/dockerfile:1

# Build: a static binary with no cgo, so the runtime image needs no libc.
FROM golang:1.27-alpine AS build
WORKDIR /src

COPY go.mod go.sum ./
RUN go mod download

COPY cmd ./cmd
COPY internal ./internal
COPY web ./web

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
