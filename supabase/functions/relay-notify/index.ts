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
 * - SUPABASE_SERVICE_ROLE_KEY: Service role key for database access
 * - FCM_SERVICE_ACCOUNT_JSON: Google service account JSON for FCM v1 API
 * - FIREBASE_PROJECT_ID: Firebase project id (optional when present in the service account JSON)
 * - RELAY_WEBHOOK_SECRET: shared secret sent in x-relay-webhook-secret (recommended)
 *
 * Security: This function must NEVER include the encrypted_envelope in the push payload.
 * The payload is opaque to the server and only decrypted on the employee device.
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';

interface DbWebhookPayload {
  schema: string;
  table: string;
  commit_timestamp: string;
  eventType: string;
  old_record?: Record<string, unknown>;
  record: {
    message_id: string;
    pairing_id: string;
    owner_device_id: string;
    employee_device_id: string;
    client_message_id: string;
    employee_key_id: string;
    encrypted_envelope?: string; // Opaque - NEVER log or forward this
    created_at: string;
    expires_at: string;
    lease_token?: string;
    leased_until?: string;
    delivery_attempts: number;
  };
}

serve(async (req) => {
  // Only handle POST requests from Supabase webhook.
  if (req.method !== 'POST') {
    return new Response('Method not allowed', { status: 405 });
  }

  const webhookSecret = Deno.env.get('RELAY_WEBHOOK_SECRET');
  if (webhookSecret && req.headers.get('x-relay-webhook-secret') !== webhookSecret) {
    return new Response('Unauthorized', { status: 401 });
  }

  try {
    const payload: DbWebhookPayload = await req.json();

    // Verify this is an INSERT event for the opaque relay table.
    if (
      payload.eventType !== 'INSERT' ||
      payload.schema !== 'relay_private' ||
      payload.table !== 'envelopes'
    ) {
      return new Response('Ignored non-relay insert', { status: 200 });
    }

    const record = payload.record;
    const employeeDeviceId = record.employee_device_id as string;
    const messageId = record.message_id as string;

    console.log(`relay-notify: relay_private.envelopes insert for employee ${employeeDeviceId}, message ${messageId}`);

    // Fetch the FCM token from the devices table
    const serviceRoleKey = Deno.env.get('SUPABASE_SERVICE_ROLE_KEY');
    if (!serviceRoleKey) {
      console.error('SUPABASE_SERVICE_ROLE_KEY not configured');
      return new Response('Internal configuration error', { status: 500 });
    }

    const supabaseUrl = Deno.env.get('SUPABASE_URL');
    if (!supabaseUrl) {
      console.error('SUPABASE_URL not configured');
      return new Response('Internal configuration error', { status: 500 });
    }

    // Query devices table for FCM token
    const devicesResponse = await fetch(
      `${supabaseUrl}/rest/v1/devices?id=eq.${encodeURIComponent(employeeDeviceId)}&select=device_token`,
      {
        headers: {
          'apikey': serviceRoleKey,
          'Authorization': `Bearer ${serviceRoleKey}`,
          'Prefer': 'return=minimal',
        },
        method: 'GET',
      }
    );

    if (!devicesResponse.ok) {
      console.error(`Failed to fetch device: ${devicesResponse.status}`);
      return new Response('Device lookup failed', { status: 500 });
    }

    const devices = await devicesResponse.json() as Array<{ device_token: string | null }>;
    const fcmToken = devices[0]?.device_token;

    if (!fcmToken) {
      console.log(`No FCM token for device ${employeeDeviceId}, skipping push`);
      // Return 200 so Supabase doesn't retry - WebSocket path still delivers
      return new Response(JSON.stringify({ status: 'skipped_no_token' }), {
        headers: { 'Content-Type': 'application/json' },
        status: 200,
      });
    }

    const fcmServiceAccountJson = Deno.env.get('FCM_SERVICE_ACCOUNT_JSON');
    if (!fcmServiceAccountJson) {
      console.error('FCM_SERVICE_ACCOUNT_JSON not configured');
      return new Response('FCM configuration error', { status: 500 });
    }

    const credentials = parseServiceAccount(fcmServiceAccountJson);
    const projectId = (Deno.env.get('FIREBASE_PROJECT_ID') || credentials.project_id || '').trim();
    if (!projectId) {
      console.error('FIREBASE_PROJECT_ID is not configured');
      return new Response('FCM project configuration error', { status: 500 });
    }

    // Send a high-priority data-only push. The client immediately posts a
    // temporary visible notification, then fetches the ciphertext through RPC.

    // Get FCM access token
    const accessToken = await getFcmAccessToken(fcmServiceAccountJson);

    // Send data-only push with message_id only (NEVER include encrypted_envelope)
    const fcmPayload = {
      message: {
        token: fcmToken,
        data: {
          type: 'relay_message',
          message_id: messageId,
          // Intentionally NOT including:
          // - encrypted_envelope (would expose ciphertext to Google)
          // - employee_device_id (client knows this)
          // - owner_device_id (client knows this)
        },
        android: {
          priority: 'high',
          ttl: '60s',
        },
      },
    };

    const fcmResponse = await fetch(
      `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`,
      {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${accessToken}`,
        },
        body: JSON.stringify(fcmPayload),
      },
    );

    if (!fcmResponse.ok) {
      console.error(`FCM send failed: ${fcmResponse.status}`);
      // Ask the webhook delivery system to retry transient FCM failures. The
      // employee's message-id work is idempotent, so duplicate pushes are safe.
      return new Response(JSON.stringify({ status: 'fcm_failed', message_id: messageId }), {
        headers: { 'Content-Type': 'application/json' },
        status: 502,
      });
    }

    console.log(`FCM push sent successfully for message ${messageId}`);
    return new Response(JSON.stringify({ status: 'sent', message_id: messageId }), {
      headers: { 'Content-Type': 'application/json' },
    });

  } catch (error) {
    console.error('relay-notify error:', error);
    return new Response(
      JSON.stringify({ error: error instanceof Error ? error.message : 'Unexpected error' }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    );
  }
});

interface ServiceAccount {
  private_key: string;
  client_email: string;
  project_id?: string;
}

function parseServiceAccount(value: string): ServiceAccount {
  const credentials = JSON.parse(value) as Partial<ServiceAccount>;
  if (!credentials.private_key || !credentials.client_email) {
    throw new Error('FCM service account is missing private_key or client_email');
  }
  return credentials as ServiceAccount;
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
 */
async function getFcmAccessToken(serviceAccountJson: string): Promise<string> {
  const credentials = parseServiceAccount(serviceAccountJson);
  const now = Math.floor(Date.now() / 1000);
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

  const response = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion,
    }),
  });
  if (!response.ok) {
    throw new Error(`FCM OAuth token request failed: ${response.status}`);
  }
  const body = await response.json() as { access_token?: string };
  if (!body.access_token) throw new Error('FCM OAuth response did not contain access_token');
  return body.access_token;
}
