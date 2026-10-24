import assert from 'node:assert/strict';
import test from 'node:test';
import {
  harness, ids, request, payload, secrets, credentials, json, success, traces, clock,
} from './harness.mjs';

test('standard type INSERT reaches FCM with the stable server message_id', async () => {
  const app = harness();
  const response = await app.handler(request());
  const body = await response.text();
  assert.equal(response.status, 200);
  assert.equal(body, JSON.stringify({ status: 'sent', message_id: ids.message_id }),
    `actual body=${JSON.stringify(body)}; outbound calls=${app.calls.length}`);
  assert.deepEqual(app.calls.map((call) => call.stage), ['device_lookup', 'oauth', 'fcm']);
});

test('HTTP v1 high-priority data-only payload has exactly the existing wakeup contract', async () => {
  const app = harness();
  const input = payload();
  input.record.payment = secrets.payment;
  input.record.lease_token = '77777777-7777-4777-8777-777777777777';
  assert.equal((await app.handler(request(input))).status, 200);
  const [lookup, oauth, fcm] = app.calls;
  assert.equal(lookup.init.method, 'GET');
  assert.equal(new URL(lookup.url).searchParams.get('id'), `eq.${ids.employee_device_id}`);
  assert.equal(new URL(lookup.url).searchParams.get('select'), 'device_token');
   assert.equal(lookup.init.headers.apikey, secrets.supabaseSecret);
   assert.equal(lookup.init.headers.Authorization, undefined);
  assert.equal(oauth.url, 'https://oauth2.googleapis.com/token');
  assert.equal(oauth.init.body.get('grant_type'), 'urn:ietf:params:oauth:grant-type:jwt-bearer');
  const assertion = oauth.init.body.get('assertion');
  const claims = JSON.parse(Buffer.from(assertion.split('.')[1], 'base64url'));
  assert.equal(claims.iss, credentials.client_email);
  assert.equal(claims.aud, 'https://oauth2.googleapis.com/token');
  assert.equal(claims.scope, 'https://www.googleapis.com/auth/firebase.messaging');
  assert.equal(claims.exp - claims.iat, 3600);
  assert.equal(fcm.url, 'https://fcm.googleapis.com/v1/projects/synthetic-project/messages:send');
  assert.equal(fcm.init.method, 'POST');
  assert.equal(fcm.init.headers.Authorization, `Bearer ${secrets.oauth}`);
  assert.deepEqual(JSON.parse(fcm.init.body), {
    message: {
      token: secrets.device,
      data: { type: 'relay_message', message_id: ids.message_id },
      android: { priority: 'high', ttl: '60s' },
    },
  });
  for (const call of app.calls) {
    assert.ok(call.init.signal instanceof AbortSignal);
    assert.equal(call.init.redirect, 'error');
  }
  assert.ok(!JSON.stringify(app.logs).includes(assertion));
});

for (const overrides of [
  { type: 'UPDATE' }, { type: 'DELETE', record: null },
  { schema: 'public' }, { table: 'devices' },
]) {
  test(`valid non-target webhook is ignored: ${JSON.stringify(overrides)}`, async () => {
    const app = harness();
    const response = await app.handler(request(payload(overrides)));
    assert.equal(response.status, 200);
    assert.equal(await response.text(), 'Ignored non-relay insert');
    assert.equal(app.calls.length, 0);
  });
}

test('type is authoritative even if an extraneous eventType disagrees', async () => {
  const app = harness();
  assert.equal((await app.handler(request(payload({ eventType: 'UPDATE' })))).status, 200);
  assert.equal(app.calls.filter((call) => call.stage === 'fcm').length, 1);
});

const malformed = [
  null, [], 'INSERT', 1, {},
  payload({ type: undefined, eventType: 'INSERT' }),
  payload({ type: null }), payload({ type: ['INSERT'] }), payload({ type: 'insert' }),
  payload({ schema: null }), payload({ schema: {} }), payload({ schema: '' }),
  payload({ table: [] }), payload({ table: '' }), payload({ table: null }),
  payload({ record: undefined }), payload({ record: null }), payload({ record: [] }),
];
test('malformed JSON, payload objects, routing fields, and records fail with 400', async () => {
  for (const body of malformed) {
    const app = harness();
    assert.equal((await app.handler(request(body))).status, 400, JSON.stringify(body));
    assert.equal(app.calls.length, 0);
  }
  const app = harness();
  assert.equal((await app.handler(request(null, { raw: '{"secret":' }))).status, 400);
  assert.equal(app.calls.length, 0);
});

test('every required record ID must be a UUID before use or logging', async () => {
  for (const key of Object.keys(ids)) {
    for (const invalid of [undefined, null, 42, [], 'not-a-uuid', `${ids[key]}\n`, 'eq.1&select=*']) {
      const app = harness();
      const body = payload();
      body.record[key] = invalid;
      assert.equal((await app.handler(request(body))).status, 400, key);
      assert.equal(app.calls.length, 0);
      assert.ok(traces(app).every((entry) => !entry.message_id && !entry.client_message_id));
    }
  }
});

test('all methods except POST are rejected before outbound work', async () => {
  const app = harness();
  assert.equal((await app.handler(request(undefined, { method: 'GET' }))).status, 405);
  assert.equal(app.calls.length, 0);
});

test('shared-secret authentication rejects missing and incorrect headers before parsing', async () => {
  for (const secret of [null, '', 'wrong-secret']) {
    const app = harness();
    assert.equal((await app.handler(request(null, { secret, raw: '{' }))).status, 401);
    assert.equal(app.calls.length, 0);
  }
});

test('absent, empty, or whitespace-only configured secret fails closed', async () => {
  for (const value of [undefined, '', '   ']) {
    const app = harness({ env: { RELAY_WEBHOOK_SECRET: value } });
    assert.equal((await app.handler(request())).status, 500);
    assert.equal(app.calls.length, 0);
  }
});

test('missing device or token is a safe successful skip', async () => {
  for (const devices of [[], [{ device_token: null }], [{ device_token: '' }]]) {
    const app = harness({ fetch: () => json(devices) });
    const response = await app.handler(request());
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { status: 'skipped_no_token' });
    assert.equal(app.calls.length, 1);
    assert.ok(!JSON.stringify(app.logs).includes(ids.employee_device_id));
  }
});

test('malformed upstream JSON/shape/token is permanent and never forwarded to FCM', async () => {
  for (const [stage, value] of [
    ['device_lookup', null], ['device_lookup', {}], ['device_lookup', [null]],
    ['device_lookup', [{ device_token: { token: secrets.device } }]],
    ['oauth', null], ['oauth', {}], ['oauth', { access_token: 42, expires_in: 3600 }],
    ['oauth', { access_token: secrets.oauth, expires_in: 0 }],
    ['oauth', { access_token: secrets.oauth }],
  ]) {
    const app = harness({ fetch: (call) => call.stage === stage ? json(value) : success(call) });
    assert.equal((await app.handler(request())).status, 502);
    assert.equal(app.calls.filter((call) => call.stage === stage).length, 1);
    assert.equal(app.calls.filter((call) => call.stage === 'fcm').length, 0);
  }
  for (const stage of ['device_lookup', 'oauth']) {
    const app = harness({ fetch: (call) => call.stage === stage
      ? new Response(`invalid JSON ${secrets.oauth}`) : success(call) });
    assert.equal((await app.handler(request())).status, 502);
    assert.equal(app.calls.filter((call) => call.stage === stage).length, 1);
    assert.ok(!JSON.stringify(app.logs).includes(secrets.oauth));
  }
});

test('logs and error responses never disclose sensitive fields or raw upstream errors', async () => {
  const sensitive = [...Object.values(secrets), credentials.private_key, credentials.client_email,
    ids.owner_device_id, ids.employee_device_id, ids.employee_key_id, ids.pairing_id];
  const rawError = sensitive.join(' | ');
  for (const stage of ['device_lookup', 'oauth', 'fcm']) {
    for (const kind of ['http', 'thrown']) {
      const app = harness({ fetch: (call) => {
        if (call.stage !== stage) return success(call);
        if (kind === 'thrown') throw new Error(rawError);
        return new Response(rawError, { status: 403 });
      } });
      const response = await app.handler(request());
      assert.equal(response.status, 502);
      const output = JSON.stringify(app.logs) + await response.text();
      for (const value of sensitive) assert.ok(!output.includes(value), `leaked ${stage}/${kind}`);
    }
  }
  for (const value of ['{', 'null', '[]', JSON.stringify({ private_key: secrets.oauth })]) {
    const app = harness({ env: { FCM_SERVICE_ACCOUNT_JSON: value } });
    const response = await app.handler(request());
    assert.equal(response.status, 500);
    assert.ok(!(JSON.stringify(app.logs) + await response.text()).includes(secrets.oauth));
  }
});

test('trace is allowlisted, correlated, and monotonic even when UTC wall time moves backward', async () => {
  const time = clock();
  const app = harness({ globals: time.globals, fetch: (call) => {
    time.elapse(25);
    if (call.stage === 'oauth') time.shiftWall(-10_000);
    return success(call);
  } });
  assert.equal((await time.run(app.handler(request()))).status, 200);
  const entries = traces(app);
  const allowed = new Set(['event', 'stage', 'outcome', 'epoch_ms', 'elapsed_ms', 'clock_domain',
    'invocation_id', 'attempt_id', 'attempt', 'message_id', 'client_message_id', 'reason',
    'http_status', 'duration_ms', 'retry_delay_ms', 'webhook_delay_ms']);
  for (const entry of entries) {
    assert.ok(Object.keys(entry).every((key) => allowed.has(key)));
    assert.ok(Number.isInteger(entry.epoch_ms));
    assert.equal(entry.clock_domain, `invocation:${entry.invocation_id}`);
    assert.equal(entry.attempt_id, `${entry.invocation_id}:${entry.stage}:${entry.attempt}`);
  }
  assert.equal(new Set(entries.map((entry) => entry.invocation_id)).size, 1);
  assert.deepEqual(entries.map((entry) => entry.elapsed_ms),
    entries.map((entry) => entry.elapsed_ms).sort((a, b) => a - b));
  assert.ok(entries.some((entry, i) => i > 0 && entry.epoch_ms < entries[i - 1].epoch_ms));
  assert.deepEqual(entries.filter((entry) => entry.outcome === 'succeeded')
    .map((entry) => entry.duration_ms), [25, 25, 25]);
  assert.ok(entries.filter((entry) => entry.message_id).every((entry) =>
    entry.message_id === ids.message_id && entry.client_message_id === ids.client_message_id));
  assert.equal(time.pendingTimers(), 0);
});

test('duplicate webhooks send the identical safe wakeup with distinct invocation clocks', async () => {
  const app = harness();
  for (let i = 0; i < 2; i++) assert.equal((await app.handler(request())).status, 200);
  const sends = app.calls.filter((call) => call.stage === 'fcm');
  assert.equal(sends.length, 2);
  assert.equal(sends[0].init.body, sends[1].init.body);
  assert.equal(JSON.parse(sends[1].init.body).message.data.message_id, ids.message_id);
  assert.equal(app.calls.filter((call) => call.stage === 'oauth').length, 1);
  assert.equal(new Set(traces(app).map((entry) => entry.clock_domain)).size, 2);
});

test('multiple employees have independent invocations and a single concurrent OAuth mint', async () => {
  const bodies = [payload(), payload({ record: {
    ...payload().record,
    employee_device_id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    message_id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    client_message_id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
  } })];
  const app = harness({ fetch: (call) => call.stage === 'device_lookup'
    ? json([{ device_token: `token-for-${new URL(call.url).searchParams.get('id')}` }])
    : success(call) });
  const responses = await Promise.all(bodies.map((body) => app.handler(request(body))));
  assert.deepEqual(responses.map((response) => response.status), [200, 200]);
  assert.equal(app.calls.filter((call) => call.stage === 'oauth').length, 1);
  assert.equal(app.calls.filter((call) => call.stage === 'device_lookup').length, 2);
  const sends = app.calls.filter((call) => call.stage === 'fcm').map((call) => JSON.parse(call.init.body));
  for (const body of bodies) {
    const send = sends.find((value) => value.message.data.message_id === body.record.message_id);
    assert.equal(send.message.token, `token-for-eq.${body.record.employee_device_id}`);
  }
  const entries = traces(app);
  assert.equal(new Set(entries.map((entry) => entry.invocation_id)).size, 2);
  assert.ok(entries.some((entry) => entry.outcome === 'shared_wait'));
  for (const id of new Set(entries.map((entry) => entry.invocation_id))) {
    assert.equal(new Set(entries.filter((entry) => entry.invocation_id === id && entry.message_id)
      .map((entry) => entry.message_id)).size, 1);
  }
});
