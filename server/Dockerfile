# syntax=docker/dockerfile:1
FROM golang:1.24-bookworm AS builder
WORKDIR /src

# Cache dependency downloads
COPY go.mod go.sum ./
RUN go mod download

# Copy source tree
COPY . .

# Run unit tests and build optimized static binary with CGO (SQLite)
RUN --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=1 go test -v ./... && \
    CGO_ENABLED=1 go build -trimpath -ldflags="-s -w" -o /yaop-server ./cmd/yaop-server

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*

COPY --from=builder /yaop-server /app/yaop-server
RUN mkdir -p /data/yaop && chown -R 1000:1000 /data
USER 1000:1000

ENV YAOP_LISTEN=0.0.0.0:8765 \
    YAOP_DATA_DIR=/data/yaop

EXPOSE 8765

HEALTHCHECK --interval=20s --timeout=5s --start-period=10s CMD ["/app/yaop-server", "-healthcheck"]

ENTRYPOINT ["/app/yaop-server"]
