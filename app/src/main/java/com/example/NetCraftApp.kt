package com.example

import android.app.Application
import com.example.util.OperationTelemetry

class NetCraftApp : Application() {
    override fun onCreate() {
        super.onCreate()

        OperationTelemetry.record(
            event = OperationTelemetry.Event.APP_STARTED,
            operation = OperationTelemetry.Operation.APP_STARTUP,
            outcome = OperationTelemetry.Outcome.SUCCESS
        )

        runCatching { installAppCheck(this) }
            .onFailure {
                OperationTelemetry.record(
                    event = OperationTelemetry.Event.APP_CHECK_RESULT,
                    operation = OperationTelemetry.Operation.APP_CHECK_INIT,
                    outcome = OperationTelemetry.Outcome.FAILURE,
                    errorClass = OperationTelemetry.classify(it)
                )
            }

        // Record only a fixed error category, then preserve Android's normal crash path.
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            OperationTelemetry.record(
                event = OperationTelemetry.Event.APP_CRASH,
                operation = OperationTelemetry.Operation.APP_STARTUP,
                outcome = OperationTelemetry.Outcome.FAILURE,
                errorClass = OperationTelemetry.classify(throwable)
            )
            if (defaultHandler != null) defaultHandler.uncaughtException(thread, throwable)
            else throw throwable
        }
    }
}
