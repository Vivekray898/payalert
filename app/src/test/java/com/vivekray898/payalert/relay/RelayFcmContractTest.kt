package com.vivekray898.payalert.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayFcmContractTest {

    @Test
    fun `relay wake-up accepts only a non-blank message id`() {
        assertEquals(
            "message-123",
            RelayFcmContract.relayMessageId(
                mapOf(
                    RelayFcmContract.TYPE_KEY to RelayFcmContract.RELAY_MESSAGE_TYPE,
                    RelayFcmContract.MESSAGE_ID_KEY to "  message-123  "
                )
            )
        )
        assertNull(
            RelayFcmContract.relayMessageId(
                mapOf(
                    RelayFcmContract.TYPE_KEY to RelayFcmContract.RELAY_MESSAGE_TYPE,
                    RelayFcmContract.MESSAGE_ID_KEY to "   "
                )
            )
        )
    }

    @Test
    fun `non relay messages never schedule a relay fetch`() {
        assertNull(
            RelayFcmContract.relayMessageId(
                mapOf(
                    RelayFcmContract.TYPE_KEY to RelayFcmContract.PAIRING_CHANGED_TYPE,
                    RelayFcmContract.MESSAGE_ID_KEY to "message-123"
                )
            )
        )
    }
}
