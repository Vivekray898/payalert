import assert from 'node:assert/strict';
import test from 'node:test';
import { randomUUID } from 'node:crypto';
import {
  harness, request, payload, secrets, credentials, json, success, traces, clock,
  hangingFetch, hangingBody,
} from './harness.mjs';

for (const stage of ['device_lookup', 'oauth', 'fcm']) {
  test(`${stage}: transient 503 recovers with bounded backoff and a new attempt ID`, async () => {
    const time = clock();
    let attempts = 0;
    const app = harness({ globals: time.globals, fetch: (call) => {
      if (call.stage === stage && ++attempts === 1) return new Response(secrets.device, { status: 503 });
      return success(call);
    } });
    assert.equal((await time.run(app.handler(request()))).status, 200);
    const calls = app.calls.filter((call) => call.stage === stage);
    assert.equal(calls.length, 2);
    assert.ok(calls[1].at - calls[0].at >= 1000);
    assert.notEqual(calls[0].init.signal, calls[1].init.signal);
    const started = traces(app).filter((entry) => entry.stage === stage && entry.outcome === 'started');
    assert.equal(new Set(started.map((entry) => entry.attempt_id)).size, 2);
    assert.equal(time.pendingTimers(), 0);
  });

  test(`${stage}: permanent HTTP errors are not retried`, async () => {
    for (const status of [400, 401, 403, 404, 422, 501]) {
      const time = clock();
      const app = harness({ globals: time.globals, fetch: (call) => call.stage === stage
        ? new Response(secrets.oauth, { status }) : success(call) });
      const response = await time.run(app.handler(request()));
      assert.equal(response.status, 502, `${status}`);
      assert.equal(app.calls.filter((call) => call.stage === stage).length, 1);
      assert.equal(time.now(), 0);
      assert.ok(!JSON.stringify(app.logs).includes(secrets.oauth));
    }
  });

  for (const [kind, hang] of [['headers', hangingFetch], ['body', hangingBody]]) {
    test(`${stage}: timeout aborts actual ${kind} I/O and stops after three attempts`, async () => {
      const time = clock();
      const app = harness({ globals: time.globals, fetch: (call) => call.stage === stage
        ? hang(call) : success(call) });
      const response = await time.run(app.handler(request()));
      assert.equal(response.status, 502);
      assert.equal((await response.json()).error, 'timeout');
      const calls = app.calls.filter((call) => call.stage === stage);
      assert.equal(calls.length, 3);
      assert.ok(calls.every((call) => call.init.signal.aborted));
      assert.deepEqual(calls.map((call) => call.at), [0, 4000, 9000]);
      assert.equal(time.now(), 12_000);
      assert.equal(time.pendingTimers(), 0);
      assert.ok(!JSON.stringify(app.logs).includes('synthetic-sensitive'));
    });
  }
}

test('transient transport errors retry; their raw messages never leave the function', async () => {
  const time = clock();
  let sends = 0;
  const app = harness({ globals: time.globals, fetch: (call) => {
    if (call.stage === 'fcm' && ++sends < 3) throw new TypeError(secrets.oauth);
    return success(call);
  } });
  assert.equal((await time.run(app.handler(request()))).status, 200);
  assert.equal(sends, 3);
  assert.equal(time.now(), 3000);
  assert.ok(!JSON.stringify(app.logs).includes(secrets.oauth));
});

test('transient HTTP statuses retry but FCM quota backoff cannot fit this invocation', async () => {
  for (const [stage, status] of [
    ['device_lookup', 408], ['oauth', 429], ['fcm', 500], ['fcm', 502], ['fcm', 504],
  ]) {
    const time = clock();
    let attempts = 0;
    const app = harness({ globals: time.globals, fetch: (call) => {
      if (call.stage === stage && ++attempts === 1) return new Response('', { status });
      return success(call);
    } });
    assert.equal((await time.run(app.handler(request()))).status, 200);
    assert.equal(attempts, 2);
  }
  const time = clock();
  const app = harness({ globals: time.globals, fetch: (call) => call.stage === 'fcm'
    ? new Response('', { status: 429 }) : success(call) });
  assert.equal((await time.run(app.handler(request()))).status, 502);
  assert.equal(app.calls.filter((call) => call.stage === 'fcm').length, 1);
  assert.equal(time.now(), 0);
});

test('each stage has at most three attempts even for immediate transient HTTP errors', async () => {
  const time = clock();
  const app = harness({ globals: time.globals, fetch: () => new Response('', { status: 503 }) });
  assert.equal((await time.run(app.handler(request()))).status, 502);
  assert.equal(app.calls.length, 3);
  assert.deepEqual(app.calls.map((call) => call.at), [0, 1000, 3000]);
});

test('seven-attempt total budget is shared across lookup, OAuth, and FCM', async () => {
  const time = clock();
  const counts = { device_lookup: 0, oauth: 0, fcm: 0 };
  const app = harness({ globals: time.globals, fetch: (call) => {
    if (++counts[call.stage] < 3) return new Response('', { status: 503 });
    return success(call);
  } });
  assert.equal((await time.run(app.handler(request()))).status, 502);
  assert.deepEqual(counts, { device_lookup: 3, oauth: 3, fcm: 1 });
  assert.equal(app.calls.length, 7);
  assert.equal(time.now(), 6000);
  assert.equal(time.pendingTimers(), 0);
});

test('Retry-After seconds and HTTP dates are lower bounds on the next attempt', async () => {
  for (const retryAfter of ['4', 'Thu, 22 Oct 2026 00:00:04 GMT']) {
    const time = clock();
    let sends = 0;
    const app = harness({ globals: time.globals, fetch: (call) => {
      if (call.stage === 'fcm' && ++sends === 1) {
        return new Response('', { status: 503, headers: { 'Retry-After': retryAfter } });
      }
      return success(call);
    } });
    assert.equal((await time.run(app.handler(request()))).status, 200);
    const calls = app.calls.filter((call) => call.stage === 'fcm');
    assert.equal(calls.length, 2);
    assert.ok(calls[1].at - calls[0].at >= 4000);
  }
});

test('Retry-After beyond the remaining budget returns failure without an early retry', async () => {
  for (const retryAfter of ['60', 'Thu, 22 Oct 2026 00:01:00 GMT', '9'.repeat(400)]) {
    const time = clock();
    const app = harness({ globals: time.globals, fetch: (call) => call.stage === 'fcm'
      ? new Response('', { status: 503, headers: { 'Retry-After': retryAfter } }) : success(call) });
    const response = await time.run(app.handler(request()));
    assert.equal(response.status, 502);
    assert.equal((await response.json()).error, 'retry_budget_exhausted');
    assert.equal(app.calls.filter((call) => call.stage === 'fcm').length, 1);
    assert.equal(time.now(), 0);
  }
});

test('retry budget is elapsed invocation time, not a fresh budget for each stage', async () => {
  const time = clock();
  let lookups = 0;
  const app = harness({ globals: time.globals, fetch: (call) => {
    if (call.stage === 'device_lookup' && ++lookups <= 2) return hangingFetch(call);
    if (call.stage === 'fcm') return new Response('', { status: 503, headers: { 'Retry-After': '4' } });
    return success(call);
  } });
  const response = await time.run(app.handler(request()));
  assert.equal(response.status, 502);
  assert.equal((await response.json()).error, 'retry_budget_exhausted');
  assert.equal(app.calls.filter((call) => call.stage === 'fcm').length, 1);
  assert.equal(time.now(), 9000);
});

test('failed shared OAuth mint is cleared so a later invocation can succeed', async () => {
  const time = clock();
  let tokens = 0;
  const app = harness({ globals: time.globals, fetch: (call) => {
    if (call.stage === 'oauth' && ++tokens === 1) return new Response('', { status: 401 });
    return success(call);
  } });
  const responses = await time.run(Promise.all([app.handler(request()), app.handler(request())]));
  assert.deepEqual(responses.map((response) => response.status), [502, 502]);
  assert.equal(tokens, 1);
  assert.equal((await time.run(app.handler(request()))).status, 200);
  assert.equal(tokens, 2);
});

test('15-second invocation deadline includes signing and clears a stalled shared mint', async () => {
  const time = clock();
  let imports = 0;
  const app = harness({ globals: { ...time.globals, crypto: {
    randomUUID,
    subtle: {
      importKey: async () => ++imports === 1 ? await new Promise(() => {}) : {},
      sign: async () => new Uint8Array([1, 2, 3]).buffer,
    },
  } } });
  const responses = await time.run(Promise.all([app.handler(request()), app.handler(request())]));
  assert.deepEqual(responses.map((response) => response.status), [502, 502]);
  assert.equal(time.now(), 15_000);
  assert.equal(app.calls.filter((call) => call.stage === 'oauth').length, 0);
  assert.equal(time.pendingTimers(), 0);
  assert.equal((await time.run(app.handler(request()))).status, 200);
  assert.equal(imports, 2);
});

test('OAuth cache respects expiry and credential rotation', async () => {
  const time = clock();
  const app = harness({ globals: time.globals, fetch: (call) => call.stage === 'oauth'
    ? json({ access_token: secrets.oauth, expires_in: 120 }) : success(call) });
  assert.equal((await time.run(app.handler(request()))).status, 200);
  time.elapse(30_000);
  assert.equal((await time.run(app.handler(request()))).status, 200);
  assert.equal(app.calls.filter((call) => call.stage === 'oauth').length, 1);
  time.elapse(31_000);
  assert.equal((await time.run(app.handler(request()))).status, 200);
  assert.equal(app.calls.filter((call) => call.stage === 'oauth').length, 2);
  app.environment.FCM_SERVICE_ACCOUNT_JSON = JSON.stringify({ ...credentials,
    client_email: 'rotated@example.invalid' });
  assert.equal((await time.run(app.handler(request()))).status, 200);
  assert.equal(app.calls.filter((call) => call.stage === 'oauth').length, 3);
});

test('one employee delivery failure does not fail another employee invocation', async () => {
  const time = clock();
  const other = payload({ record: { ...payload().record,
    employee_device_id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    message_id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb' } });
  const app = harness({ globals: time.globals, fetch: (call) => {
    if (call.stage === 'fcm' && JSON.parse(call.init.body).message.data.message_id === other.record.message_id) {
      return new Response('', { status: 404 });
    }
    return success(call);
  } });
  const responses = await time.run(Promise.all([app.handler(request()), app.handler(request(other))]));
  assert.deepEqual(responses.map((response) => response.status), [200, 502]);
  assert.equal(app.calls.filter((call) => call.stage === 'oauth').length, 1);
  assert.equal(app.calls.filter((call) => call.stage === 'fcm').length, 2);
});
