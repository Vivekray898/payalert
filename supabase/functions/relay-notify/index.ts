/**
 * Supabase Edge Function: relay-notify
 *
 * This function is triggered by a Database Webhook on INSERT into relay_private.envelopes.
 * It looks up the employee device's FCM token and sends a data-only push notification
 * containing only the message_id (never the encrypted payload).
 *
 * The push wakes the employee app, which then fetches the envelope via authenticated RPC
 * and decrypts it locally using the X25519 shared secret.
 *
 * Environment variables required:
 * - PAYALERT_SUPABASE_SECRET_KEY: server-only Supabase secret key for database access
 * - FCM_SERVICE_ACCOUNT_JSON: Google service account JSON for FCM v1 API
 * - FIREBASE_PROJECT_ID: Firebase project id (optional when present in the service account JSON)
 * - RELAY_WEBHOOK_SECRET: required shared secret sent in x-relay-webhook-secret
 *
 * Security: This function must NEVER include the encrypted_envelope in the push payload.
 * The payload is opaque to the server and only decrypted on the employee device.
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';

// One invocation, including OAuth signing/waiting and all backoff, has 15 seconds.
// Each HTTP attempt (headers AND body) has at most 3 seconds; each stage gets at
// most 3 attempts, with at most 7 HTTP attempts total (at most 4 retries on a cold
// successful path). Retry-After is a lower bound, never clamped to fit the budget.
// Failure is observable to the caller; this does NOT assume pg_net retries it.
const INVOCATION_BUDGET_MS = 15_000;
const HTTP_TIMEOUT_MS = 3_000;
const MAX_STAGE_ATTEMPTS = 3;
const MAX_HTTP_ATTEMPTS = 7;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const RECORD_IDS = [
  'message_id', 'pairing_id', 'owner_device_id', 'employee_device_id',
  'client_message_id', 'employee_key_id',
] as const;

type Stage = 'request' | 'device_lookup' | 'oauth' | 'fcm';
type FailureCode = 'budget_exhausted' | 'timeout' | 'network_error' | 'upstream_error'
  | 'invalid_response' | 'configuration_error' | 'retry_budget_exhausted';

class RelayFailure extends Error {
  readonly code: FailureCode;
  readonly transient: boolean;
  readonly httpStatus?: number;
  readonly retryAfterMs: number;

  constructor(
    code: FailureCode,
    transient = false,
    httpStatus?: number,
    retryAfterMs = 0,
  ) {
    super(code);
    this.code = code;
    this.transient = transient;
    this.httpStatus = httpStatus;
    this.retryAfterMs = retryAfterMs;
  }
}

interface Invocation {
  id: string;
  startedAt: number;
  signal: AbortSignal;
  httpAttempts: number;
  stage: Stage;
  messageId?: string;
  clientMessageId?: string;
}

function trace(
  context: Invocation, stage: Stage, outcome: string, attempt = 0,
  details: { reason?: string; http_status?: number; duration_ms?: number;
    retry_delay_ms?: number; webhook_delay_ms?: number } = {},
): void {
  // Only allowlisted metadata and validated message UUIDs. Never log an Error,
  // request/response body, device ID, URL, credential, token, or ciphertext.
  console.log(JSON.stringify({
    event: 'relay_notify', stage, outcome,
    epoch_ms: Date.now(), elapsed_ms: performance.now() - context.startedAt,
    clock_domain: `invocation:${context.id}`, invocation_id: context.id,
    attempt_id: `${context.id}:${stage}:${attempt}`, attempt,
    message_id: context.messageId, client_message_id: context.clientMessageId,
    ...details,
  }));
}

function isObject(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function jsonResponse(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status, headers: { 'Content-Type': 'application/json' },
  });
}

serve(async (req) => {
  const controller = new AbortController();
  const context: Invocation = {
    id: crypto.randomUUID(), startedAt: performance.now(), signal: controller.signal,
    httpAttempts: 0, stage: 'request',
  };
  const timer = setTimeout(() => controller.abort(), INVOCATION_BUDGET_MS);
  trace(context, 'request', 'received');
  try {
    return await abortable(handleRequest(req, context), context.signal);
  } catch (error) {
    const reason = error instanceof RelayFailure ? error.code : 'internal_error';
    trace(context, context.stage, 'failed', 0, { reason });
    return jsonResponse({
      status: context.stage === 'fcm' ? 'fcm_failed' : 'failed',
      error: reason,
      upstream_status: error instanceof RelayFailure ? error.httpStatus : undefined,
      message_id: context.messageId,
    }, error instanceof RelayFailure && reason !== 'configuration_error' ? 502 : 500);
  } finally {
    clearTimeout(timer);
    controller.abort();
  }
});

async function handleRequest(req: Request, context: Invocation): Promise<Response> {
  // Only handle POST requests from Supabase webhook.
  if (req.method !== 'POST') {
    trace(context, 'request', 'method_rejected');
    return new Response('Method not allowed', { status: 405 });
  }

  const webhookSecret = Deno.env.get('RELAY_WEBHOOK_SECRET');
  // verify_jwt=false: missing configuration must never authorize a request.
  if (!webhookSecret?.trim()) {
    throw new RelayFailure('configuration_error');
  }
  if (req.headers.get('x-relay-webhook-secret') !== webhookSecret) {
    trace(context, 'request', 'unauthorized');
    return new Response('Unauthorized', { status: 401 });
  }

  let payload: unknown;
  try {
    payload = await req.json();
  } catch {
    trace(context, 'request', 'invalid_payload');
    return new Response('Invalid webhook payload', { status: 400 });
  }
  if (
    !isObject(payload) || !['INSERT', 'UPDATE', 'DELETE'].includes(payload.type as string) ||
    typeof payload.schema !== 'string' || !payload.schema ||
    typeof payload.table !== 'string' || !payload.table
  ) {
    trace(context, 'request', 'invalid_payload');
    return new Response('Invalid webhook payload', { status: 400 });
  }

  // Database Webhooks use `type`; `eventType` belongs to a different API.
  if (
    payload.type !== 'INSERT' || payload.schema !== 'relay_private' ||
    payload.table !== 'envelopes'
  ) {
    trace(context, 'request', 'ignored');
    return new Response('Ignored non-relay insert', { status: 200 });
  }

  const record = payload.record;
  if (!isObject(record) || !RECORD_IDS.every((key) =>
    typeof record[key] === 'string' && UUID.test(record[key] as string)
  )) {
    trace(context, 'request', 'invalid_payload');
    return new Response('Invalid webhook payload', { status: 400 });
  }
  const employeeDeviceId = record.employee_device_id as string;
  const messageId = record.message_id as string;
  context.messageId = messageId;
  context.clientMessageId = record.client_message_id as string;

  const createdAt = typeof record.created_at === 'string' ? Date.parse(record.created_at) : NaN;
  // Cross-clock estimate only; stage durations use the invocation's monotonic clock.
  trace(context, 'request', 'validated', 0, {
    webhook_delay_ms: Number.isFinite(createdAt) ? Date.now() - createdAt : undefined,
  });

  // Fetch the FCM token from the devices table.
  const supabaseSecretKey = Deno.env.get('PAYALERT_SUPABASE_SECRET_KEY');
  const supabaseUrl = Deno.env.get('SUPABASE_URL');
  if (!supabaseSecretKey || !supabaseUrl) throw new RelayFailure('configuration_error');

  const devices = await requestWithRetry(context, 'device_lookup',
    `${supabaseUrl}/rest/v1/devices?id=eq.${encodeURIComponent(employeeDeviceId)}&select=device_token`,
    {
      headers: {
        // Supabase secret keys are API keys, not JWTs. Send them on apikey only.
        'apikey': supabaseSecretKey,
        'Prefer': 'return=minimal',
      },
      method: 'GET',
    }, true,
  );
  if (!Array.isArray(devices) || (devices.length > 0 && !isObject(devices[0]))) {
    throw new RelayFailure('invalid_response');
  }
  const fcmToken = devices[0]?.device_token;
  if (fcmToken != null && typeof fcmToken !== 'string') {
    throw new RelayFailure('invalid_response');
  }
  if (!fcmToken) {
    trace(context, 'device_lookup', 'skipped_no_token');
    return jsonResponse({ status: 'skipped_no_token' });
  }

  const fcmServiceAccountJson = Deno.env.get('FCM_SERVICE_ACCOUNT_JSON');
  if (!fcmServiceAccountJson) throw new RelayFailure('configuration_error');
  const credentials = parseServiceAccount(fcmServiceAccountJson);
  const projectId = (Deno.env.get('FIREBASE_PROJECT_ID') || credentials.project_id || '').trim();
  if (!projectId) throw new RelayFailure('configuration_error');

  const accessToken = await getFcmAccessToken(fcmServiceAccountJson, context);

  // High-priority, data-only HTTP v1 wakeup. Only the stable server message_id
  // is sent with the existing type discriminator; client_message_id is trace-only.
  const fcmPayload = {
    message: {
      token: fcmToken,
      data: {
        type: 'relay_message',
        message_id: messageId,
      },
      android: {
        priority: 'high',
        ttl: '60s',
      },
    },
  };
  await requestWithRetry(context, 'fcm',
    `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${accessToken}`,
      },
      body: JSON.stringify(fcmPayload),
    }, false,
  );
  // A timeout/repeated webhook can produce duplicate wakeups; the unchanged
  // server message_id lets the client's existing message-id work deduplicate.
  trace(context, 'request', 'sent');
  return jsonResponse({ status: 'sent', message_id: messageId });
}

function remaining(context: Invocation): number {
  return INVOCATION_BUDGET_MS - (performance.now() - context.startedAt);
}

async function abortable<T>(work: Promise<T>, signal: AbortSignal): Promise<T> {
  let onAbort!: () => void;
  const aborted = new Promise<never>((_, reject) => {
    onAbort = () => reject(new RelayFailure('budget_exhausted'));
    signal.addEventListener('abort', onAbort, { once: true });
    if (signal.aborted) onAbort();
  });
  try {
    return await Promise.race([work, aborted]);
  } finally {
    signal.removeEventListener('abort', onAbort);
  }
}

function retryAfterMillis(value: string | null): number {
  if (!value) return 0;
  // Overflowing positive seconds means "beyond our budget", never "retry now".
  if (/^\d+$/.test(value.trim())) return Number(value) * 1000;
  const date = Date.parse(value);
  return Number.isFinite(date) ? Math.max(0, date - Date.now()) : 0;
}

async function waitForRetry(delayMs: number, context: Invocation): Promise<void> {
  const until = performance.now() + delayMs;
  while (performance.now() < until) {
    let timer: ReturnType<typeof setTimeout> | undefined;
    try {
      await abortable(new Promise<void>((resolve) => {
        timer = setTimeout(resolve, Math.ceil(until - performance.now()));
      }), context.signal);
    } finally {
      clearTimeout(timer);
    }
  }
}

async function requestWithRetry(
  context: Invocation, stage: Stage, url: string, init: RequestInit, readJson: boolean,
): Promise<unknown> {
  context.stage = stage;
  for (let attempt = 1; attempt <= MAX_STAGE_ATTEMPTS; attempt++) {
    if (context.signal.aborted || remaining(context) <= 0) {
      throw new RelayFailure('budget_exhausted');
    }
    if (context.httpAttempts >= MAX_HTTP_ATTEMPTS) {
      throw new RelayFailure('retry_budget_exhausted');
    }
    context.httpAttempts++;
    const startedAt = performance.now();
    trace(context, stage, 'started', attempt);
    const controller = new AbortController();
    const onAbort = () => controller.abort();
    context.signal.addEventListener('abort', onAbort, { once: true });
    const timer = setTimeout(onAbort, Math.min(HTTP_TIMEOUT_MS, remaining(context)));
    let failure: RelayFailure;
    try {
      const body = await abortable((async () => {
        const response = await fetch(url, { ...init, signal: controller.signal, redirect: 'error' });
        if (!response.ok) {
          // Never read/log upstream error bodies (they can contain tokens).
          const transient = [408, 429, 500, 502, 503, 504].includes(response.status);
          const retryAfter = Math.max(retryAfterMillis(response.headers.get('Retry-After')),
            stage === 'fcm' && response.status === 429 ? 60_000 : 0);
          throw new RelayFailure('upstream_error', transient, response.status, retryAfter);
        }
        // Keep the fetch signal and timer alive until the body has been consumed.
        return readJson ? await response.json() : await response.text();
      })(), controller.signal);
      trace(context, stage, 'succeeded', attempt, { duration_ms: performance.now() - startedAt });
      return body;
    } catch (error) {
      failure = context.signal.aborted || remaining(context) <= 0
        ? new RelayFailure('budget_exhausted')
        : controller.signal.aborted ? new RelayFailure('timeout', true)
        : error instanceof RelayFailure ? error
        : error instanceof TypeError ? new RelayFailure('network_error', true)
        : new RelayFailure('invalid_response');
    } finally {
      clearTimeout(timer);
      context.signal.removeEventListener('abort', onAbort);
      controller.abort();
    }
    trace(context, stage, 'failed', attempt, {
      reason: failure.code, http_status: failure.httpStatus,
      duration_ms: performance.now() - startedAt,
    });
    if (!failure.transient || attempt === MAX_STAGE_ATTEMPTS ||
      context.httpAttempts >= MAX_HTTP_ATTEMPTS) throw failure;
    const delayMs = Math.max(1000 * 2 ** (attempt - 1), failure.retryAfterMs);
    // Reserve a complete next attempt; never shorten a server's Retry-After.
    if (delayMs + HTTP_TIMEOUT_MS >= remaining(context)) {
      throw new RelayFailure('retry_budget_exhausted');
    }
    trace(context, stage, 'retry_wait', attempt, { retry_delay_ms: delayMs });
    await waitForRetry(delayMs, context);
  }
  throw new RelayFailure('retry_budget_exhausted');
}

interface ServiceAccount {
  private_key: string;
  client_email: string;
  project_id?: string;
}

/**
 * Cached FCM OAuth access token.
 *
 * Minting a token costs a full round trip to Google's token endpoint plus an RSA signature, and
 * that round trip sits directly on the path between the owner's payment and the employee's
 * notification. The token is valid for an hour, so it is reused for fifty-five minutes.
 */
let cachedFcmToken: { source: string; token: string; expiresAt: number } | null = null;
let pendingFcmToken: { source: string; promise: Promise<string> } | null = null;
const FCM_TOKEN_TTL_MILLIS = 55 * 60 * 1000;

function parseServiceAccount(value: string): ServiceAccount {
  try {
    const credentials: unknown = JSON.parse(value);
    if (isObject(credentials) && typeof credentials.private_key === 'string' &&
      credentials.private_key && typeof credentials.client_email === 'string' &&
      credentials.client_email && (credentials.project_id === undefined ||
        typeof credentials.project_id === 'string')) {
      return credentials as unknown as ServiceAccount;
    }
  } catch {
    // Configuration/parsing errors can contain secret material; discard them.
  }
  throw new RelayFailure('configuration_error');
}

function base64Url(bytes: Uint8Array): string {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function utf8(value: string): Uint8Array {
  return new TextEncoder().encode(value);
}

function pemToBytes(pem: string): Uint8Array {
  const encoded = pem
    .replace('-----BEGIN PRIVATE KEY-----', '')
    .replace('-----END PRIVATE KEY-----', '')
    .replace(/\s/g, '');
  const binary = atob(encoded);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0));
}

/**
 * Exchange service account credentials for an FCM access token.
 *
 * The token is cached until shortly before it expires, so a burst of relay messages does not
 * turn into a burst of token requests to Google.
 */
async function getFcmAccessToken(serviceAccountJson: string, context: Invocation): Promise<string> {
  context.stage = 'oauth';
  const nowMillis = Date.now();
  if (cachedFcmToken?.source === serviceAccountJson && cachedFcmToken.expiresAt > nowMillis) {
    trace(context, 'oauth', 'cache_hit');
    return cachedFcmToken.token;
  }
  if (pendingFcmToken?.source === serviceAccountJson) {
    trace(context, 'oauth', 'shared_wait');
    const token = await abortable(pendingFcmToken.promise, context.signal);
    trace(context, 'oauth', 'shared_ready');
    return token;
  }
  // One mint per credential source in this isolate. Waiters retain their own
  // deadlines; the mint is bounded by its initiating invocation. A failed mint
  // clears the slot so a later invocation can try again, without an unbounded loop.
  const pending: { source: string; promise: Promise<string> } = {
    source: serviceAccountJson,
    promise: abortable(mintFcmAccessToken(serviceAccountJson, context), context.signal).then((result) => {
      if (pendingFcmToken === pending) {
        cachedFcmToken = { source: serviceAccountJson, ...result };
      }
      return result.token;
    }).finally(() => {
      if (pendingFcmToken === pending) pendingFcmToken = null;
    }),
  };
  pendingFcmToken = pending;
  return await abortable(pending.promise, context.signal);
}

async function mintFcmAccessToken(
  serviceAccountJson: string, context: Invocation,
): Promise<{ token: string; expiresAt: number }> {
  const nowMillis = Date.now();
  const credentials = parseServiceAccount(serviceAccountJson);
  const now = Math.floor(nowMillis / 1000);
  const encodedHeader = base64Url(utf8(JSON.stringify({ alg: 'RS256', typ: 'JWT' })));
  const encodedPayload = base64Url(utf8(JSON.stringify({
    iss: credentials.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud: 'https://oauth2.googleapis.com/token',
    exp: now + 3600,
    iat: now,
  })));
  const unsignedToken = `${encodedHeader}.${encodedPayload}`;

  const key = await crypto.subtle.importKey(
    'pkcs8',
    pemToBytes(credentials.private_key),
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  );
  const signature = await crypto.subtle.sign(
    'RSASSA-PKCS1-v1_5',
    key,
    utf8(unsignedToken),
  );
  const assertion = `${unsignedToken}.${base64Url(new Uint8Array(signature))}`;

  const body = await requestWithRetry(context, 'oauth', 'https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion,
    }),
  }, true);
  if (!isObject(body) || typeof body.access_token !== 'string' || !body.access_token.trim() ||
    typeof body.expires_in !== 'number' || !Number.isFinite(body.expires_in) || body.expires_in <= 0) {
    throw new RelayFailure('invalid_response');
  }
  return {
    token: body.access_token,
    expiresAt: nowMillis + Math.max(0, Math.min(FCM_TOKEN_TTL_MILLIS, body.expires_in * 1000 - 60_000)),
  };
}
