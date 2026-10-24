import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { stripTypeScriptTypes } from 'node:module';
import { randomUUID } from 'node:crypto';
import { Script, createContext } from 'node:vm';
import { setImmediate as flush } from 'node:timers/promises';

const source = readFileSync(new URL('../index.ts', import.meta.url), 'utf8');
const serveImport = "import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';";
assert.equal(source.split(serveImport).length, 2, 'replace only the serve import boundary');
const script = new Script(stripTypeScriptTypes(source.replace(serveImport, '')), {
  filename: 'relay-notify/index.ts',
});

export const ids = {
  message_id: '11111111-1111-4111-8111-111111111111',
  pairing_id: '22222222-2222-4222-8222-222222222222',
  owner_device_id: '33333333-3333-4333-8333-333333333333',
  employee_device_id: '44444444-4444-4444-8444-444444444444',
  client_message_id: '55555555-5555-4555-8555-555555555555',
  employee_key_id: '66666666-6666-4666-8666-666666666666',
};

// Deliberately fake credentials, never read from the machine's environment.
export const secrets = {
  webhook: 'synthetic-webhook-secret',
  supabaseSecret: 'synthetic-supabase-secret',
  device: 'synthetic-device-token',
  oauth: 'synthetic-oauth-access-token',
  ciphertext: 'synthetic-ciphertext-never-log',
  payment: 'synthetic-payment-never-log',
};
export const credentials = {
  client_email: 'synthetic@example.invalid',
  private_key: '-----BEGIN PRIVATE KEY-----\nZmFrZS1rZXk=\n-----END PRIVATE KEY-----',
  project_id: 'synthetic-project',
};

export function payload(overrides = {}) {
  return {
    type: 'INSERT', schema: 'relay_private', table: 'envelopes', old_record: null,
    record: {
      ...ids, encrypted_envelope: secrets.ciphertext,
      created_at: '2026-10-22T00:00:00.000Z', expires_at: '2026-10-23T00:00:00.000Z',
      lease_token: null, leased_until: null, delivery_attempts: 0,
    },
    ...overrides,
  };
}

export function request(body = payload(), { method = 'POST', secret = secrets.webhook, raw } = {}) {
  return new Request('https://relay.example.invalid/relay-notify', {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(secret === null ? {} : { 'x-relay-webhook-secret': secret }),
    },
    ...(method === 'GET' ? {} : { body: raw ?? JSON.stringify(body) }),
  });
}

export const json = (body, status = 200, headers = {}) => new Response(JSON.stringify(body), {
  status, headers: { 'Content-Type': 'application/json', ...headers },
});

export function success(call) {
  if (call.stage === 'device_lookup') return json([{ device_token: secrets.device }]);
  if (call.stage === 'oauth') return json({ access_token: secrets.oauth, expires_in: 3600 });
  return json({ name: 'projects/synthetic-project/messages/synthetic' });
}

export function harness({ env = {}, fetch: mockFetch = success, globals = {} } = {}) {
  const logs = [];
  const calls = [];
  let handler;
  const environment = {
    RELAY_WEBHOOK_SECRET: secrets.webhook,
    PAYALERT_SUPABASE_SECRET_KEY: secrets.supabaseSecret,
    SUPABASE_URL: 'https://database.example.invalid',
    FCM_SERVICE_ACCOUNT_JSON: JSON.stringify(credentials),
    ...env,
  };
  const context = createContext({
    serve: (callback) => { handler = callback; },
    Deno: { env: { get: (key) => environment[key] } },
    fetch: async (url, init = {}) => {
      const address = new URL(url);
      const stage = address.host === 'database.example.invalid' ? 'device_lookup'
        : address.host === 'oauth2.googleapis.com' ? 'oauth'
        : address.host === 'fcm.googleapis.com' ? 'fcm' : null;
      assert.ok(stage, 'unexpected outbound destination');
      const call = { url: String(url), init, stage, at: (globals.performance ?? performance).now() };
      calls.push(call);
      return mockFetch(call, calls);
    },
    console: Object.fromEntries(['log', 'warn', 'error'].map((level) => [level, (...args) => {
      logs.push({ level, args });
    }])),
    // Only WebCrypto's external signing boundary is mocked, not relay/OAuth logic.
    crypto: {
      randomUUID,
      subtle: {
        importKey: async () => ({}),
        sign: async () => new Uint8Array([1, 2, 3]).buffer,
      },
    },
    Request, Response, Headers, URL, URLSearchParams, TextEncoder, TextDecoder,
    AbortController, AbortSignal, DOMException, Uint8Array, atob, btoa, Error, TypeError,
    setTimeout, clearTimeout, performance,
    ...globals,
  });
  script.runInContext(context);
  assert.equal(typeof handler, 'function');
  return { handler, calls, logs, environment };
}

export const traces = (app) => app.logs.map(({ args }) => {
  assert.equal(args.length, 1, 'logs must be structured, not raw errors');
  return JSON.parse(args[0]);
});

// Drive only injected platform clocks/timers, leaving the handler's production
// constants and retry decisions untouched. No real sleeps or network are needed.
export function clock() {
  let now = 0;
  let epoch = Date.parse('2026-10-22T00:00:00Z');
  let nextId = 0;
  const timers = new Map();
  const globals = {
    performance: { now: () => now },
    Date: class extends Date { static now() { return epoch + now; } },
    setTimeout: (callback, delay = 0) => {
      const id = ++nextId;
      timers.set(id, { callback, due: now + Math.max(0, delay) });
      return id;
    },
    clearTimeout: (id) => { timers.delete(id); },
  };
  return {
    globals,
    now: () => now,
    shiftWall: (ms) => { epoch += ms; },
    elapse: (ms) => { now += ms; },
    pendingTimers: () => timers.size,
    async run(work) {
      let complete = false;
      let result;
      let error;
      work.then((value) => { result = value; complete = true; }, (value) => {
        error = value; complete = true;
      });
      for (let i = 0; i < 200; i++) {
        await flush(); // Drain promise chains/Response body reads before ticking.
        if (complete) {
          if (error) throw error;
          return result;
        }
        const next = [...timers].sort((a, b) => a[1].due - b[1].due)[0];
        assert.ok(next, 'handler stalled without a deadline');
        const [id, timer] = next;
        timers.delete(id);
        now = Math.max(now, timer.due);
        timer.callback();
      }
      assert.fail('handler exceeded the finite timer-step limit');
    },
  };
}

export function hangingFetch(call) {
  assert.ok(call.init.signal instanceof AbortSignal, 'fetch must receive a real AbortSignal');
  return new Promise((_, reject) => {
    call.init.signal.addEventListener('abort', () => {
      reject(new DOMException('synthetic-sensitive-abort', 'AbortError'));
    }, { once: true });
  });
}

export function hangingBody(call) {
  assert.ok(call.init.signal instanceof AbortSignal, 'body must remain abortable');
  return new Response(new ReadableStream({
    start(controller) {
      call.init.signal.addEventListener('abort', () => {
        controller.error(new DOMException('synthetic-sensitive-body-abort', 'AbortError'));
      }, { once: true });
    },
  }), { headers: { 'Content-Type': 'application/json' } });
}
