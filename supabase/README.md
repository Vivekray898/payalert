# Supabase — PayAlert team pairing and encrypted relay

## Files

| File | Purpose |
|---|---|
| `schema.sql` | Optional cloud-sync tables (`users`, `devices`, `payment_events`, `cashbook_entries`). Non-destructive and re-runnable. |
| `team_pairing.sql` | Team pairing tables, RPCs, RLS. |
| `encrypted_relay.sql` | Encrypted owner → employee relay: `device_keys`, `peer_key_bindings`, `envelopes`, plus the authenticated RPCs. |
| `realtime_relay.sql` | Adds the relay table to the `supabase_realtime` publication. |
| `migrations/005_fcm_token.sql` | Adds FCM token freshness metadata. |
| `migrations/006_fcm_wakeup.sql` | Adds the ownership-checked RPC used to register device tokens. |
| `tests/team_pairing.sql` | SQL test suite run against a disposable PostgreSQL cluster. |
| `functions/relay-notify/index.ts` | Edge Function that sends an FCM data-only push when a relay row is inserted. |

## Apply order

1. `schema.sql`
2. `team_pairing.sql`
3. `encrypted_relay.sql`
4. `realtime_relay.sql`
5. `migrations/005_fcm_token.sql`
6. `migrations/006_fcm_wakeup.sql`

All six are non-destructive and safe to re-run. Apply via the Supabase SQL editor or:

```bash
supabase db push --linked
```

## Real-time relay setup

### 1. Enable Realtime on the relay table

`realtime_relay.sql` adds `relay_private.envelopes` to the `supabase_realtime` publication and sets `replica identity full`. Confirm it applied:

```sql
select schemaname, tablename from pg_publication_tables
where pubname = 'supabase_realtime';
```

Expected output:

```
 schemaname   | tablename
--------------+-----------
 relay_private| envelopes
```

### 2. Configure the FCM webhook

In the Supabase Dashboard → Database → Webhooks:

- Schema/table: `relay_private.envelopes`
- Events: `INSERT`
- Type: `Supabase Edge Function`
- Function: `relay-notify`
- Custom header: `x-relay-webhook-secret: <same value as RELAY_WEBHOOK_SECRET>`

### 3. Set the Edge Function secrets

```bash
supabase secrets set \
  SUPABASE_SERVICE_ROLE_KEY=<value> \
  FCM_SERVICE_ACCOUNT_JSON='<json>' \
  FIREBASE_PROJECT_ID=payment-announcer-43071 \
  RELAY_WEBHOOK_SECRET='<random-shared-secret>'
```

**Never commit these values.** They live in the project's secret store, not in this repository.

### 4. Deploy the Edge Function

```bash
supabase functions deploy relay-notify
```

### 5. Configure the app for Firebase

- Add `app/google-services.json` (obtained from the Firebase Console for this app's package name).
- The build already includes `com.google.firebase:firebase-bom` and `com.google.firebase:firebase-messaging-ktx`.

## What the WebSocket path does not deliver

The relay table is in the `relay_private` schema. `encrypted_relay.sql` revokes all privileges on it from `anon` and `authenticated`, and adds a RESTRICTIVE `relay_no_client_access` policy with `USING (false)`. Supabase Realtime filters each `postgres_changes` event by evaluating RLS as the subscriber's role, so an authenticated subscriber matches zero rows. The envelope table is invisible to clients by design; every read goes through the SECURITY DEFINER RPCs so the server stays payload-blind.

The employee device therefore receives through the existing authenticated `relay_fetch` RPC, pumped on a short cadence by `EmployeeRealtimeService` (foreground, 10 s) plus `RelayPumpScheduler` (WorkManager, 15 min floor), with `PayAlertMessagingService` as the immediate FCM wake-up path when FCM is configured. The app immediately posts a temporary high-importance notification, fetches/decrypts the envelope locally, and replaces it with the actual announcement. This keeps the server payload-blind.

## FCM is the immediate wake-up path

- **Primary:** the employee-side pump (`EmployeeRealtimeService` + `RelayPumpScheduler`).
- **Immediate wake-up:** high-priority, data-only FCM push carrying `message_id` only. The employee app fetches the envelope via the authenticated `relay_fetch` RPC after wake-up and posts a temporary visible notification before the fetch completes.

The push never contains the encrypted payload. Leaking it into FCM would expose it to Google's servers and the notification tray.
