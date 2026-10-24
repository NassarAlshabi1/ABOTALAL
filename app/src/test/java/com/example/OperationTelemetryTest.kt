package com.example

import com.example.util.OperationTelemetry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
class OperationTelemetryTest {
    @Test
    fun `event json contains bounded operational fields only`() {
        val json = JSONObject(
            OperationTelemetry.encode(
                event = OperationTelemetry.Event.COMMAND_RESULT,
                operation = OperationTelemetry.Operation.ROUTER_COMMAND,
                operationId = "test-operation-id",
                outcome = OperationTelemetry.Outcome.FAILURE,
                errorClass = OperationTelemetry.ErrorClass.ROUTER_REJECTED,
                transport = OperationTelemetry.Transport.NATIVE_API,
                commandFamily = OperationTelemetry.CommandFamily.USER_MANAGER,
                durationMs = -8,
                requestedCount = 200_000,
                affectedCount = 2,
                routerOsMajor = 6,
                retryCount = 10
            )
        )

        assertEquals("router_command_result", json.getString("event"))
        assertEquals("test-operation-id", json.getString("operation_id"))
        assertEquals("failure", json.getString("outcome"))
        assertEquals("user_manager", json.getString("command_family"))
        assertEquals(0L, json.getLong("duration_ms"))
        assertEquals(100_000, json.getInt("requested_count"))
        assertEquals(2, json.getInt("affected_count"))
        assertEquals(6, json.getInt("routeros_major"))
        assertEquals(3, json.getInt("retry_count"))
        assertFalse(json.has("host"))
        assertFalse(json.has("username"))
        assertFalse(json.has("password"))
        assertFalse(json.has("command"))
        assertFalse(json.has("reply"))
    }

    @Test
    fun `command family classification never returns raw command`() {
        assertEquals(
            OperationTelemetry.CommandFamily.USER_MANAGER,
            OperationTelemetry.commandFamily("/tool/user-manager/user/add")
        )
        assertEquals(
            OperationTelemetry.CommandFamily.HOTSPOT,
            OperationTelemetry.commandFamily("/ip/hotspot/user/print")
        )
        assertEquals(
            OperationTelemetry.CommandFamily.OTHER,
            OperationTelemetry.commandFamily("/vendor/custom/path")
        )
    }

    @Test
    fun `mutation detection and error classification use fixed categories`() {
        assertTrue(OperationTelemetry.isMutationCommand("/system/reboot"))
        assertTrue(OperationTelemetry.isMutationCommand("/ip/hotspot/user/add"))
        assertFalse(OperationTelemetry.isMutationCommand("/ip/hotspot/user/print"))
        assertEquals(
            OperationTelemetry.ErrorClass.TIMEOUT,
            OperationTelemetry.classify(SocketTimeoutException("never included in event"))
        )
    }
}
