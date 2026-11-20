# API Reference — OpenMRS CCE Emitter Adaptor

## Overview

This is a **polling service** — it does not expose REST endpoints for triggering event forwarding. It exposes standard **Spring Boot Actuator** endpoints for health and monitoring.

---

## Actuator Endpoints

| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/actuator/health` | Overall health status |
| GET | `/actuator/health/liveness` | Liveness probe |
| GET | `/actuator/health/readiness` | Readiness probe |
| GET | `/actuator/prometheus` | Prometheus metrics |
| GET | `/actuator/metrics` | Metrics (JSON) |
| GET | `/actuator/info` | Application info |

---

## Custom Metrics

| Metric Name | Type | Tags | Description |
|-------------|------|------|-------------|
| `openmrs.emitter.poll.executed` | Counter | `resourceType` | Poll cycles completed |
| `openmrs.emitter.poll.resources.detected` | Counter | `resourceType` | Changed resources detected |
| `openmrs.emitter.poll.duration` | Timer | `resourceType` | Poll cycle duration |
| `openmrs.emitter.forward.success` | Counter | `resourceType` | Successful forwards to OpenHIM |
| `openmrs.emitter.forward.failure` | Counter | `resourceType` | Failed forwards after all retries (or first non-retriable 4xx) |
| `openmrs.emitter.forward.skipped` | Counter | `resourceType`, `reason` | Resources skipped pre-forward (e.g. `missing-national-id`, `discontinue-revision`) |
| `openmrs.emitter.checkpoint.age.seconds` | Gauge | `resourceType` | Age of last checkpoint |
| `openmrs.emitter.nationalid.lookup` | Counter | `outcome` (`hit`, `miss`, `cache-hit`, `error`) | National-id resolution lookups |
| `openmrs.emitter.nationalid.cache.size` | Gauge | — | Current size of national-id cache |
| `openmrs.emitter.order.identifier.lookup` | Counter | `outcome` (`hit`, `miss`, `cache-hit`, `error`) | OpenMRS order identifier (accession number) lookups |

---

## OpenMRS APIs Consumed

### FHIR R4 API (`/ws/fhir2/R4`)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/{ResourceType}?_lastUpdated=gt{ts}&_count=50&_sort=-_lastUpdated` | Delta polling |
| GET | `/Patient/{uuid}` | Resolve OpenMRS Patient UUID → `national-id` identifier |
| GET | `/{ResourceType}/{uuid}` | Re-fetch a prior order (for discontinue-tombstone follow-priorOrder logic) |
| GET | `/metadata` | Health check (CapabilityStatement) |

### REST API (`/ws/rest/v1`)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| GET | `/order/{uuid}?v=custom:(uuid,accessionNumber,orderType:(uuid,display))` | Fetch OpenMRS order metadata stripped by fhir2 — used by `OrderIdentifierEnricher` for accession-number injection AND referral-response classification (via `orderType.display`) |

### OpenHIM (Target)

| Method | Endpoint | Purpose |
|--------|----------|---------|
| POST | `/{baseUrl}/{ResourceType}` | Forward FHIR resources |

Used when `OPENMRS_EMITTER_TARGET=openhim`. Unchanged by Beckn mode. On this branch the default is `beckn`.

### onix-openmrs (Target in Beckn Mode)

> **Status:** Implemented on branch `sygnal-actions` (package `org.openphc.cce.emitter.beckn`). Not yet tested against real onix containers. See [beckn-integration.md](beckn-integration.md).

Used only when `OPENMRS_EMITTER_TARGET=beckn`. The emitter then makes no call to OpenHIM's FHIR channel. It exposes **no new endpoint**, and no callback comes back.

| Method | Endpoint | Action | Purpose |
|--------|----------|--------|---------|
| POST | `{OPENMRS_EMITTER_BECKN_ONIX_CALLER_URL}/sygnal/event/report` (`http://onix-openmrs:8082/sygnal/caller/sygnal/event/report`) | `sygnal/event/report` | Every changed resource, to the HIE, which passes it on to CCE |

Body: a Sygnal message (`context` with `senderId` / `receiverId` / `receiverUri`, no `bapId` / `bppId`) with exactly one `sygnal:CoordinationEvent` in `message.events`. Its `eventAttributes` box is a `CceClinicalEvent` holding the FHIR resource. Sample: [beckn-integration.md](beckn-integration.md) § 9.

The answer comes back in the same call:

```json
{ "message": { "status": "ACK", "messageId": "<request messageId>" } }
{ "message": { "status": "NACK", "messageId": "<request messageId>", "error": { "code": "SCH_REQUIRED_FIELD_MISSING", "message": "<text>" } } }
```

| Answer | Emitter |
|--------|---------|
| HTTP 202 (any 2xx) and not `NACK` | Done |
| HTTP 400 (`NACK`), or other 4xx except 408 / 429 | Fail. No retry. Log `error.code` and `error.message` |
| HTTP 500 or other 5xx, 408, 429, timeout, or no answer | Retry with the same event id and a new `messageId` (`emitter.openhim.retry.*`), then drop |

The emitter waits up to 50 s (`emitter.beckn.onix.timeout`) for one answer. Details: [beckn-integration.md](beckn-integration.md) § 10.

### Beckn Metrics

| Metric Name | Type | Tags | Description |
|-------------|------|------|-------------|
| `openmrs.beckn.messages.sent` | Counter | `leg`, `action`, `outcome` | Messages finished, by final result (`action` is `sygnal/event/report`) |
| `openmrs.beckn.send.retries` | Counter | `leg`, `action` | Resends after a 5xx or timeout |
| `openmrs.beckn.messages.dropped` | Counter | `leg`, `action`, `reason` | Messages given up on (`nack`, `retries-exhausted`) |
| `openmrs.beckn.resources.skipped` | Counter | `resourceType`, `reason` | Resources not sent (`no-patient`, `all-legs-off`, `invalid-json`) |
| `openmrs.beckn.onix.latency` | Timer | `leg`, `action` | Time for one onix call |

In Prometheus these appear with the `openmrs_beckn_` prefix (for example `openmrs_beckn_messages_sent_total`).
