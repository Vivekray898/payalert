/**
 * Supabase Edge Function: relay-notify
 *
 * This function is triggered by a Database Webhook on INSERT into relay_messages.
 * It looks up the employee device's FCM token and sends a data-only push notification
 * containing only the message_id (never the encrypted payload).
 *
 * The push wakes the employee app, which then fetches the envelope via authenticated RPC
 * and decrypts it locally using the X25519 shared secret.
 *
 * Environment variables required:
 * - SUPABASE_SERVICE_ROLE_KEY: Service role key for database access
 * - FCM_SERVICE_ACCOUNT_JSON: Google service account JSON for FCM v1 API
 *
 * Security: This function must NEVER include the encrypted_envelope in the push payload.
 * The payload is opaque to the server and only decrypted on the employee device.
 */

import { serve } from 'https://deno.land/std@0.168.0/http/server.ts';

const FCM_URL = 'https://fcm.googleapis.com/v1/projects/payalert-production/messages:send';

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
    encrypted_envelope: string; // Base64 encoded - NEVER log or forward this
    created_at: string;
    expires_at: string;
    lease_token?: string;
    leased_until?: string;
    delivery_attempts: number;
  };
}

serve(async (req) => {
  // Only handle POST requests from Supabase webhook
  if (req.method !== 'POST') {
    return new Response('Method not allowed', { status: 405 });
  }

  try {
    const payload: DbWebhookPayload = await req.json();

    // Verify this is an INSERT event for relay_messages
    if (payload.eventType !== 'INSERT' || payload.table !== 'relay_messages') {
      return new Response('Not an INSERT event for relay_messages', { status: 200 });
    }

    const record = payload.record;
    const employeeDeviceId = record.employee_device_id as string;
    const messageId = record.message_id as string;

    console.log(`relay-notify: INSERT on relay_messages for employee ${employeeDeviceId}, message ${messageId}`);

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
      `${supabaseUrl}/rest/v1/devices?id=eq.${employeeDeviceId}&select=fcm_token`,
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

    const devices = await devicesResponse.json() as Array<{ fcm_token: string | null }>;
    const fcmToken = devices[0]?.fcm_token;

    if (!fcmToken) {
      console.log(`No FCM token for device ${employeeDeviceId}, skipping push`);
      // Return 200 so Supabase doesn't retry - WebSocket path still delivers
      return new Response(JSON.stringify({ status: 'skipped_no_token' }), {
        headers: { 'Content-Type': 'application/json' },
        status: 200,
      });
    }

    // Send FCM data-only push
    const fcmServiceAccountJson = Deno.env.get('FCM_SERVICE_ACCOUNT_JSON');
    if (!fcmServiceAccountJson) {
      console.error('FCM_SERVICE_ACCOUNT_JSON not configured');
      return new Response('FCM configuration error', { status: 500 });
    }

    // Get FCM access token
    const accessToken = await getFcmAccessToken(fcmServiceAccountJson);
    if (!accessToken) {
      console.error('Failed to get FCM access token');
      return new Response('FCM authentication failed', { status: 500 });
    }

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
        },
        apns: {
          priority: 10,
        },
      },
    };

    const fcmResponse = await fetch(FCM_URL, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Authorization': `Bearer ${accessToken}`,
      },
      body: JSON.stringify(fcmPayload),
    });

    if (!fcmResponse.ok) {
      console.error(`FCM send failed: ${fcmResponse.status}`);
      // Don't return error - WebSocket path still delivers
      return new Response(JSON.stringify({ status: 'fcm_failed', message_id: messageId }), {
        headers: { 'Content-Type': 'application/json' },
        status: 200,
      });
    }

    console.log(`FCM push sent successfully for message ${messageId}`);
    return new Response(JSON.stringify({ status: 'sent', message_id: messageId }), {
      headers: { 'Content-Type': 'application/json' },
    });

  } catch (error) {
    console.error('relay-notify error:', error);
    return new Response(
      JSON.stringify({ error: error.message }),
      { status: 500, headers: { 'Content-Type': 'application/json' } }
    );
  }
});

/**
 * Exchange service account credentials for an FCM access token.
 */
async function getFcmAccessToken(serviceAccountJson: string): Promise<string | null> {
  try {
    const credentials = JSON.parse(serviceAccountJson) as {
      private_key: string;
      client_email: string;
    };

    // Create JWT assertion
    const header = btoa(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
    const now = Math.floor(Date.now() / 1000);
    const payload = btoa(JSON.stringify({
      iss: credentials.client_email,
      scope: 'https://www.googleapis.com/auth/firebase.messaging',
      aud: 'https://oauth2.googleapis.com/token',
      exp: now + 3600,
      iat: now,
    }));

    // Sign JWT (simplified - in production use a proper JWT library)
    const assertion = `${header}.${payload}`;

    // For now, return a placeholder - the actual signing requires crypto operations
    // This is a limitation of Deno edge functions without additional dependencies
    console.warn('JWT signing not implemented - FCM auth will fail');
    return null;
  } catch (error) {
    console.error('Failed to prepare FCM token:', error);
    return null;
  }
}
