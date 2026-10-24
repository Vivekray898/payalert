# PayAlert employee announcement delay investigation

**Date:** October 22, 2026
**Scope:** owner detection → local persistence → encrypted relay → Supabase → webhook/Edge Function → FCM → employee fetch/decrypt/store → TTS
**Test data:** synthetic identifiers and synthetic payloads only. No payment details, production payloads, service keys, FCM tokens, or session secrets were logged or sent.

## Executive result

The first confirmed fault is in the Edge Function event gate:

```text
Supabase Database Webhook: type = "INSERT"
PayAlert before fix:       checks eventType == "INSERT"
```

The handler therefore returned HTTP 200 (`Ignored non-relay insert`) before looking up the employee token or sending FCM. A 30-event reproduction against the pre-change handler dropped all 30 standard Database Webhook payloads and made zero outbound calls. This is consistent with employees receiving only the slower polling/recovery path, while the owner-local announcement remains instant.

The owner-open clue had a second code-level risk: `AppGraph.onAppOpen()` started `TeamController.refresh()` without awaiting its network/readback work. During startup, a local payment could be accepted while `RelayEngine` still had no pairing context; `RelayEngine.onStored()` previously returned silently. The repair now awaits `refreshNow()`, recovers recent durable local credits, and emits a payload-blind `owner_context_missing` marker when the context is absent. The live frequency of that condition still requires an isolated two-device deployment.

No evidence currently implicates payment detection, duplicate suppression, encryption, RLS, FCM priority, Doze, or TTS as the primary cause. The employee polling fallback is a contributor to the observed delay when push is absent: the foreground employee relay loop is 30 seconds, not 10 seconds; WorkManager is a slower recovery path.

## Pipeline sequence diagram

```mermaid
sequenceDiagram
    autonumber
    participant Bank as Bank/UPI signal
    participant Owner as Owner Android process
    participant Room as Owner Room DB
    participant Relay as Owner relay queue
    participant DB as Supabase RPC / relay_private.envelopes
    participant Hook as relay-notify Edge Function
    participant FCM as Firebase Cloud Messaging
    participant Emp as Employee FCM service
    participant Work as Employee WorkManager
    participant Fetch as Employee relay_fetch RPC
    participant TTS as Employee TTS/audio

    Bank->>Owner: SMS or payment notification
    Owner->>Owner: parse, source admission, exact + semantic dedup
    Owner->>Room: store accepted PaymentEvent
    Owner->>Owner: local TTS/chime immediately
    Owner->>Relay: onAccepted; deterministic route message ID
    Relay->>DB: authenticated relay_enqueue(opaque ciphertext)
    DB-->>Relay: server message_id / idempotent acceptance
    DB->>Hook: async Database Webhook type=INSERT
    Hook->>DB: service-role token lookup (no ciphertext readback)
    Hook->>FCM: HTTP v1 data-only wake-up: type + server message_id
    FCM-->>Emp: data message
    Emp->>Work: unique expedited relay work
    Work->>Fetch: authenticated relay_fetch
    Fetch-->>Work: leased opaque envelope
    Work->>Work: authenticate/decrypt locally
    Work->>Room: durable received_announcements insert/claim
    Work->>TTS: announcement request with client message ID
    TTS-->>Emp: actual audio callback / error marker

    Note over Emp,Fetch: If FCM is absent, employee foreground polling is ~30 s.
    Note over Owner,Relay: On startup, recent local credits are scanned for a bounded 5-minute recovery window.
```

## Evidence map

| Boundary | Finding | Evidence / state after change |
|---|---|---|
| Detection → local owner announcement | Working path | `PaymentPipeline` stores and announces locally before relay work. Existing parser/dedup tests remain passing. |
| Local owner event → relay context | Startup race was possible | `onAppOpen()` now awaits `refreshNow()`. `owner_context_missing` is emitted instead of silently hiding the condition. |
| Local owner event → durable handoff | Recovery gap existed before queue insertion | A bounded recent-history scan re-offers credits after startup; deterministic route IDs and the Room queue make re-offer idempotent. |
| Relay enqueue → Supabase row | Existing design is authenticated and encrypted | `relay_enqueue` accepts opaque ciphertext and enforces `(pairing_id, client_message_id)` uniqueness. No SQL/RLS change was needed. |
| Supabase webhook event gate | **Confirmed defect** | Before: `eventType`; after: standard `type`. Thirty pre-fix synthetic events were all ignored. |
| Webhook auth | Fail-open configuration risk | Function now requires a nonblank shared webhook secret because `verify_jwt=false`. |
| Webhook → token lookup/FCM | Previously unobservable and unbounded | Per-stage UTC + monotonic traces, 3-second HTTP attempt timeout, 15-second invocation budget, bounded transient retries, `Retry-After` handling. |
| FCM → Android service | Existing contract preserved | HTTP v1, high-priority, data-only payload with only `type` and server `message_id`. No payment plaintext or ciphertext is sent through FCM. |
| Android callback → worker | Distinction added | Separate FCM receipt and WorkManager start/complete/retry/failure markers. |
| Worker → employee fetch | Previous fetch timestamp was before the request | Fetch start/complete/failure/cancelled markers now bracket the actual `relay_fetch` call. |
| Fetch → decrypt/store/TTS | Existing idempotency preserved | Server lease + local message primary key + claim-before-announce remain unchanged. TTS request and real audio callbacks are correlated by client message ID. |
| Realtime | Not the actual payload path | The private envelope table is RLS-denied to clients; the app uses authenticated RPC fetch plus FCM/polling. |

## Root cause and confidence

### Confirmed code root cause

`supabase/functions/relay-notify/index.ts` compared `payload.eventType` with `INSERT`. The repository's own setup says this function is invoked by a Supabase Database Webhook. That webhook format supplies `type`, `table`, `schema`, `record`, and `old_record`. With a standard payload, the old function returned before token lookup and FCM.

### Why the symptom matches

When the webhook sends no FCM wake-up, an employee with the foreground relay service alive only notices the server row on its polling loop. The current loop is 30 seconds, so a 10–20 second observed delay is plausible depending on phase. If the employee service is not alive, WorkManager is a slower recovery mechanism. Opening an app can also start the service or complete pairing context, which explains why opening an app can appear to release a pending event without proving that the app itself was the original root cause.

### Not yet production-proven

The repository does not contain a Dashboard webhook definition or a linked-project readback. Therefore these remain deployment checks, not claims:

1. the production Dashboard webhook targets `relay_private.envelopes` and `INSERT`;
2. its custom header exactly matches `RELAY_WEBHOOK_SECRET`;
3. the employee token is current;
4. FCM accepts the configured Firebase project/service account;
5. device battery policy and foreground-service state permit the employee process to run.

The new traces are designed to separate those cases without logging private data.

## Trace schema

### Android logcat

Tag: `PayAlertRelay`

```text
relay_trace schema=1 stage=<allowlisted_stage>
  epoch_ms=<UTC wall-clock milliseconds>
  elapsedRealtime_ms=<Android monotonic milliseconds>
  clock_domain=<process UUID>
  client_message_id=<validated UUID, optional>
  server_message_id=<validated UUID, optional>
  fetch_id=<UUID, optional>
```

Stages include owner acceptance/context/enqueue/send, FCM receipt, worker lifecycle, fetch, decrypt,
store, announcement request, and TTS request/audio completion/error. Monotonic differences are valid
only inside one `clock_domain`; wall-clock timestamps across phones can be skewed.

### Edge Function logs

Structured JSON includes invocation UUID, stage, attempt ID, UTC `epoch_ms`, monotonic
`elapsed_ms`, allowlisted message UUIDs, bounded failure codes, and stage duration. It never includes
request/response bodies, device IDs, tokens, credentials, ciphertext, payment fields, or raw errors.

## Synthetic timing results

These are offline harness measurements, not network or phone measurements.

### Webhook gate before and after

| Run | Events | Accepted by webhook handler | FCM-like outbound calls | Failures | Duplicates |
|---|---:|---:|---:|---:|---:|
| Pre-change handler, standard `type=INSERT` | 30 | 0 | 0 | 0 HTTP failures; 30 silently ignored | 0 |
| Post-change handler, synthetic foreground | 30 | 30 | 90 stage calls (lookup/OAuth/FCM) | 0 | 0 |
| Post-change handler, synthetic background clock | 30 | 30 | 90 stage calls (lookup/OAuth/FCM) | 0 | 0 |

### Post-change stage timing

All values are milliseconds in the local Node harness. The foreground harness uses mocked immediate
responses; the background harness advances a synthetic clock by 2 ms per mocked outbound stage.

| Scenario / stage | Median | p95 | Max | Interpretation |
|---|---:|---:|---:|---|
| Foreground total wall time | 0 | 1 | 4 | Local CPU/harness only |
| Foreground device lookup | 0.042 | 0.120 | 0.406 | Mocked HTTP boundary |
| Foreground OAuth | 0.041 | 0.095 | 0.142 | First-call mint/cache path is synthetic |
| Foreground FCM | 0.035 | 0.103 | 0.454 | Mocked HTTP boundary |
| Background-clock device lookup | 2 | 2 | 2 | Deterministic synthetic delay |
| Background-clock OAuth | 2 | 2 | 2 | Deterministic synthetic delay |
| Background-clock FCM | 2 | 2 | 2 | Deterministic synthetic delay |

### Android tests available in this workspace

| Target | Result | Label / limitation |
|---|---|---|
| JVM unit tests | 270 passed, 0 failed | Offline; synthetic relay/crypto/trace tests |
| Supabase Edge Function contract/reliability tests | 41 passed, 0 failed | Offline Node v24.19.0; mocked `serve`, fetch, OAuth, clocks |
| Emulator `Pixel_8_Pro` API 34 | 22/22 instrumentation tests passed | Device UI/database smoke tests; no backend payment events |
| Physical `M2101K6I` API 36 | 15/22 passed; 7 failed before test body | Existing Compose/coroutines `ServiceLoader` exception-handler failure; relay persistence tests passed |
| Real 30-event foreground/background two-device run | **Not run** | Requires an isolated Supabase/Firebase project and explicit approval to use paired devices; no production payloads were touched |

The requested device-level median/p95/max/failure/duplicate table cannot honestly be populated from
the local harness. The table above labels emulator, physical, and synthetic results separately rather
than presenting mocked timings as phone behavior.

## Reliability audit

- **Stable event IDs:** `RelayMessageId` deterministically derives one UUID from owner, employee, and payment dedup key.
- **Server idempotency:** `relay_enqueue` uses the `(pairing_id, client_message_id)` unique key.
- **Employee idempotency:** received announcements use the client message ID as a local primary key and claim before announcing.
- **Lease recovery:** employee fetch leases expire; an unacknowledged row can be claimed again. Acknowledgement happens after the whole fetched batch has been announced.
- **FCM duplicate safety:** repeated webhook deliveries send the same server message ID and unique WorkManager work name.
- **Owner process-death gap:** the original fire-and-forget handoff could die before the outbound Room queue row existed. A bounded five-minute recovery scan now reads durable local payment history on app/service startup and re-offers credits; deterministic IDs prevent a duplicate send.
- **Recovery boundary:** the scan is deliberately five minutes, so it is not a replacement for a full transactional outbox. A future product requirement for arbitrarily long offline owner recovery should add a pairing-scoped Room outbox in one transaction with `payment_events`.

## Changed files

### Runtime code

- `app/src/main/java/com/vivekray898/payalert/relay/RelayTrace.kt` — payload-blind UTC/monotonic stage markers.
- `app/src/main/java/com/vivekray898/payalert/relay/OwnerForwarder.kt` — owner enqueue/send trace markers.
- `app/src/main/java/com/vivekray898/payalert/relay/RelayEngine.kt` — context-missing marker, awaited-start recovery support, and five-minute durable-history recovery.
- `app/src/main/java/com/vivekray898/payalert/AppGraph.kt` — await team refresh before owner recovery and wire recent local payment history.
- `app/src/main/java/com/vivekray898/payalert/services/PayAlertMessagingService.kt` — safe FCM receipt logging; removed token/data logging.
- `app/src/main/java/com/vivekray898/payalert/relay/RelayFcmWorkers.kt` — worker lifecycle markers and safe errors.
- `app/src/main/java/com/vivekray898/payalert/relay/EmployeeReceiver.kt` — actual fetch/decrypt/store/announce markers and client/server correlation.
- `app/src/main/java/com/vivekray898/payalert/tts/TtsEngine.kt` — relay correlation through TTS queue and platform callbacks.
- `supabase/functions/relay-notify/index.ts` — `type` webhook contract, fail-closed secret, validation, bounded retries/timeouts, trace schema, and preserved FCM payload.

### Tests and documentation

- `app/src/test/java/com/vivekray898/payalert/relay/RelayTraceTest.kt`
- `app/src/test/java/com/vivekray898/payalert/relay/RelayTraceReceiverTest.kt`
- `app/src/test/java/com/vivekray898/payalert/relay/OwnerForwarderTest.kt`
- `app/src/test/java/com/vivekray898/payalert/relay/RelayEngineTest.kt`
- `supabase/functions/relay-notify/tests/contract.test.mjs`
- `supabase/functions/relay-notify/tests/reliability.test.mjs`
- `supabase/functions/relay-notify/tests/harness.mjs`
- `README.md` and `supabase/README.md`
- This report: `RELAY_INVESTIGATION.md`

## Migrations and configuration

- **SQL migrations:** none added. Existing RLS, pairing, encrypted relay RPCs, leases, and unique keys are unchanged.
- **Room schema:** no version change; recovery reads the existing `payment_events` table.
- **Supabase function config:** `supabase/config.toml` remains `verify_jwt=false`; the function now requires the configured shared webhook header and must be deployed with that contract.
- **Secrets:** none committed or read from local secret files. Production secrets remain in Supabase's secret store.

## Deploy and verify

1. Apply the existing SQL files in the documented order if the target project is not current.
2. Deploy the function with JWT verification disabled because the Database Webhook authenticates with the shared header:

   ```bash
   supabase functions deploy relay-notify --no-verify-jwt
   ```

3. Set `PAYALERT_SUPABASE_SECRET_KEY`, `FCM_SERVICE_ACCOUNT_JSON`, `FIREBASE_PROJECT_ID`, and a random `RELAY_WEBHOOK_SECRET` in the Supabase function secret store. The secret key is sent to Supabase on `apikey` only; it is not a JWT bearer token. Do not place any of these values in the APK or repository.
4. In Supabase Dashboard → Database → Webhooks, configure `relay_private.envelopes`, `INSERT`, Edge Function `relay-notify`, and custom header `x-relay-webhook-secret` with the same secret.
5. Build the Android app with only the public Supabase URL and publishable key, install the debug/release build on the isolated owner and employee devices, and confirm the employee token is refreshed after pairing.
6. Capture only safe traces while running synthetic events:

   ```bash
   adb logcat -v threadtime -s PayAlertRelay PayAlertFcm
   ```

7. For each test event, correlate the owner `client_message_id`, Edge Function `client_message_id`/`message_id`, employee `server_message_id`/`client_message_id`, and TTS stage. Do not collect payment text or ciphertext.

## Verification commands

```bash
node --experimental-strip-types --check supabase/functions/relay-notify/index.ts
node --test supabase/functions/relay-notify/tests/*.test.mjs
make test
make lint
```

`make lint` still reports five pre-existing errors in unchanged theme, analytics, and manifest files;
the required command was run and is not reported as passing. The Edge Function and JVM test suites
pass. The connected instrumentation run is partially blocked on the physical device's existing
coroutines ServiceLoader failure, while the emulator and relay persistence tests pass.

## Limitations and next measurement

The remaining decisive check is a two-device run against an isolated project with synthetic encrypted
events: 30 with the employee foreground service alive and 30 with the app backgrounded, repeated on
the emulator and physical device. Export only the safe stage markers and calculate per-stage median,
p95, max, failure count, and duplicate count. If `FCM_RECEIVED` is absent while `owner_send_accepted`
and the Edge Function `sent` marker exist, investigate FCM token/project/device policy. If the Edge
Function `sent` marker is absent, investigate the webhook configuration or upstream retry logs. If
`FCM_RECEIVED` exists but `FETCH_START` is late, investigate Android worker scheduling. If fetch/decrypt
is prompt but TTS audio is late, investigate TTS engine/audio callbacks.

## Live deployment checks performed after the secret-key migration

- Deployed `relay-notify` to the PayAlert Supabase project ref used for this run with JWT verification disabled.
- Stored the server-only Supabase secret key under the custom function secret name `PAYALERT_SUPABASE_SECRET_KEY`; the CLI rejects user-defined names beginning with reserved `SUPABASE_`.
- Stored the Firebase service-account JSON, Firebase project ID, and webhook secret in Supabase's function secret store. The Firebase project ID matches the Android `google-services.json` project.
- Sent **30 direct synthetic Database Webhook-shaped requests** to the deployed function. Result: **30 HTTP 200**, all `skipped_no_token` for deliberately nonexistent synthetic employee IDs, median **584 ms**, p95 **1,298 ms**, max **1,749 ms**, 0 failures, 0 duplicates.
- Sent one synthetic wake-up to a real tokenized database device selected by physical-model metadata, without any envelope or payment data. The deployed function reached FCM but FCM returned HTTP **404**. This indicates the selected stored token is stale/unregistered or otherwise not valid for the configured Firebase project; it is not evidence of a payment-path failure. No payment was sent.
- A Dashboard webhook definition could not be read or changed with the supplied project secret key; Supabase Management/Dashboard credentials are separate. The `relay_private.envelopes` Database Webhook still must be confirmed manually before a real `relay_enqueue` insert can exercise the complete trigger path.
