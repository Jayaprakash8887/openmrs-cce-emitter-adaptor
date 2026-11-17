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

> **Status:** Implemented on branch `demo-beckn` (package `org.openphc.cce.emitter.beckn`). Not yet tested against real onix containers. See [beckn-integration.md](beckn-integration.md).

With `OPENMRS_EMITTER_TARGET=openhim` the deployment above is all that is needed. On the `demo-beckn` branch the default is `beckn`, so set `OPENMRS_EMITTER_TARGET=openhim` explicitly to deploy without Beckn. Beckn mode adds the two `openmrs-onix` adapter containers and their Redis, as an optional group.

| Container | Role | Port on `cce-net` | Host port on the dev host |
|-----------|------|-------------------|---------------------------|
| `openmrs-cce-emitter-adaptor` | Emitter | `8085` | `8099` |
| `onix-bap` | OpenMRS as consumer. Caller `/bap/caller` | `8081` | `8181` |
| `onix-bpp` | OpenMRS as provider. Caller `/bpp/caller` | `8082` | `8182` |
| Redis for onix | Adapter's short-lived data | — | not published |

- All of them join the `cce-net` Docker network. The emitter calls the onix containers by name, on their own ports.
- On the dev host, host ports `8081` (`keycloak`) and `8082` (`cce-openhim-core`) are taken. Publish the onix containers on `8181` and `8182`. Inside `cce-net` they keep `8081` and `8082`.
- Both onix receiver routings point at the receiver adaptor, not at the emitter.

Extra emitter environment:

```yaml
    environment:
      OPENMRS_EMITTER_TARGET: beckn
      OPENMRS_EMITTER_BECKN_ONIX_BAP_CALLER_URL: http://onix-bap:8081/bap/caller
      OPENMRS_EMITTER_BECKN_ONIX_BPP_CALLER_URL: http://onix-bpp:8082/bpp/caller
      OPENMRS_EMITTER_BECKN_SCHEMA_BASE_URL: https://raw.githubusercontent.com/openphc/cce-beckn-service/main/schema
      OPENHIM_RETRY_MAX_ATTEMPTS: "6"
    networks:
      - cce-net
```

The Beckn ids, contract ids and facility codes go in YAML ([configuration-guide.md](configuration-guide.md)).

### Beckn Mode Checklist

- [ ] `onix-bap` and `onix-bpp` are running on `cce-net` and reachable from the emitter
- [ ] Both onix containers have OpenMRS's own Beckn ids and keys, and the `CceClinicalEvent` schema (from the cce-beckn-service `schema/` folder)
- [ ] `responseHeaderTimeout` on `onix-bap` is raised from the 5 s default to at least 45 s, and the emitter's `emitter.beckn.onix.timeout` is 50 s ([beckn-integration.md](beckn-integration.md) § 10.4)
- [ ] The HIE feed contract id and `transactionId` are issued by the OpenHIM admin and set in `emitter.beckn.openhim`
- [ ] The "referrals in" and "referrals out" ids are agreed with Spice and set in `emitter.beckn.spice`
- [ ] Spice's facility codes are set in `emitter.beckn.spice.facility-codes`
- [ ] `OPENMRS_EMITTER_TARGET=beckn` is set, and the emitter is restarted

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
