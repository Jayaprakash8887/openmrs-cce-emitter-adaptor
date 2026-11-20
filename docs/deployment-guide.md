# Deployment Guide — OpenMRS CCE Emitter Adaptor

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Java | 21 LTS |
| Gradle | 8.x (wrapper included) |
| Docker | 24.x+ (for containerized deployment) |
| OpenMRS | 2.5+ with `fhir2` and `webservices.rest` modules |
| OpenHIM | 8.x+ |

---

## Build

```bash
./gradlew clean build          # with tests
./gradlew clean build -x test  # skip tests
docker build -t openmrs-cce-emitter-adaptor:latest .
```

---

## Run Locally

```bash
./gradlew bootRun --args='--spring.profiles.active=local'

# Verify
curl -s http://localhost:8080/actuator/health | jq
```

---

## Docker Compose

```yaml
services:
  openmrs-cce-emitter-adaptor:
    build: .
    image: openmrs-cce-emitter-adaptor:latest
    container_name: openmrs-cce-emitter-adaptor
    ports:
      - "8080:8080"
    restart: unless-stopped
    volumes:
      - emitter-data:/app/data
    environment:
      SERVER_PORT: 8080
      JAVA_TOOL_OPTIONS: "-Xms128m -Xmx512m -XX:+UseG1GC"
      OPENMRS_BASE_URL: http://openmrs:8080/openmrs
      OPENMRS_AUTH_TYPE: basic
      OPENMRS_AUTH_USERNAME: admin
      OPENMRS_AUTH_PASSWORD: Admin123
      OPENHIM_BASE_URL: https://openhim-api.cce.mdtlabs.org/fhir
      OPENHIM_AUTH_TYPE: basic
      OPENHIM_AUTH_USERNAME: openmrs-emitter-client
      OPENHIM_AUTH_PASSWORD: password
      OPENHIM_SSL_TRUST_ALL: "true"
      CHECKPOINT_FILE_PATH: /app/data/checkpoints.json
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health/liveness"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

volumes:
  emitter-data:
```

---

## Beckn Mode

> **Status:** Implemented on branch `sygnal-actions` (package `org.openphc.cce.emitter.beckn`). Not yet tested against real onix containers. See [beckn-integration.md](beckn-integration.md).

With `OPENMRS_EMITTER_TARGET=openhim` the deployment above is all that is needed. On this branch the default is `beckn`, so set `OPENMRS_EMITTER_TARGET=openhim` explicitly to deploy without Beckn. Beckn mode adds OpenMRS's onix adapter container (`onix-openmrs`) and its Redis, as an optional group. The emitter sends every resource to the HIE as `sygnal/event/report` through it.

| Container | Role | Port on `cce-net` | Host port on the dev host |
|-----------|------|-------------------|---------------------------|
| `openmrs-cce-emitter-adaptor` | Emitter | `8085` | `8099` |
| `onix-openmrs` | OpenMRS's Sygnal adapter. Caller `/sygnal/caller/` (sends), receiver `/sygnal/receiver/` (public) | `8082` | `8182` |
| Redis for onix | Adapter's short-lived data | — | not published |

- All of them join the `cce-net` Docker network. The emitter calls `onix-openmrs` by name, on port `8082`.
- On the dev host, host port `8082` (`cce-openhim-core`) is taken. Publish `onix-openmrs` on `8182`. Inside `cce-net` it keeps `8082`.
- The onix receiver routing (`sygnal/signal/emit` from CCE) points at the receiver adaptor, not at the emitter. The emitter receives nothing.
- The HIE's onix receiver (`https://<hie-host>/sygnal/receiver`) must be reachable from `onix-openmrs`; it is the emitter's `OPENMRS_EMITTER_BECKN_HIE_URI`.

Extra emitter environment:

```yaml
    environment:
      OPENMRS_EMITTER_TARGET: beckn
      OPENMRS_EMITTER_BECKN_ONIX_CALLER_URL: http://onix-openmrs:8082/sygnal/caller
      OPENMRS_EMITTER_BECKN_SCHEMA_BASE_URL: https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema
      OPENMRS_EMITTER_BECKN_SELF_ID: bap.openmrs.nextsteps.mdtlabs.org
      OPENMRS_EMITTER_BECKN_SELF_NETWORK_ID: <network id>
      OPENMRS_EMITTER_BECKN_HIE_ID: bap.openhim.nextsteps.mdtlabs.org
      OPENMRS_EMITTER_BECKN_HIE_URI: https://<hie-host>/sygnal/receiver
      OPENHIM_RETRY_MAX_ATTEMPTS: "6"
    networks:
      - cce-net
```

The full list of settings is in [configuration-guide.md](configuration-guide.md).

### Beckn Mode Checklist

- [ ] `onix-openmrs` is running on `cce-net` and reachable from the emitter
- [ ] `onix-openmrs` holds OpenMRS's keys for subscriber id `bap.openmrs.nextsteps.mdtlabs.org` (= `OPENMRS_EMITTER_BECKN_SELF_ID`), the CCE Sygnal profile and the `CceClinicalEvent` schema (from the cce-beckn-service `schema/` folder)
- [ ] `OPENMRS_EMITTER_BECKN_SELF_NETWORK_ID` and `OPENMRS_EMITTER_BECKN_HIE_URI` are set (the emitter does not start in Beckn mode without them)
- [ ] `responseHeaderTimeout` on `onix-openmrs` is raised from the 5 s default to at least 45 s, and the emitter's `emitter.beckn.onix.timeout` is 50 s ([beckn-integration.md](beckn-integration.md) § 10.4)
- [ ] `OPENMRS_EMITTER_TARGET=beckn` is set, and the emitter is restarted
- [ ] The old `OPENMRS_EMITTER_BECKN_ONIX_BAP_CALLER_URL` / `…_BPP_CALLER_URL`, `…_OPENHIM_*`, `…_SPICE_*` and `…_REFERRALS_*` variables are removed from the env file (they are ignored)

---

## Resource Allocation

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| CPU | 0.5 vCPU | 1 vCPU |
| Memory | 256 MB | 512 MB |
| Disk | 10 MB | 50 MB |

---

## Deployment Checklist

- [ ] OpenMRS instance is reachable from the emitter
- [ ] OpenMRS user credentials have sufficient privileges for FHIR + REST APIs
- [ ] OpenHIM channel is configured for the emitter client
- [ ] Checkpoint file volume is mounted and writable
- [ ] Environment variables for credentials are set (not hardcoded)
- [ ] `OPENHIM_SSL_TRUST_ALL` is `false` in production
- [ ] Health check endpoint responds: `GET /actuator/health`
- [ ] Prometheus scraping configured for `/actuator/prometheus`
