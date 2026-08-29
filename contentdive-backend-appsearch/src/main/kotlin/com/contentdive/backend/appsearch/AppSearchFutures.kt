package com.contentdive.backend.appsearch

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

internal object DirectExecutor : Executor {
    override fun execute(command: Runnable) {
        command.run()
    }
}

internal suspend fun <T> ListenableFuture<T>.await(): T = suspendCoroutine { continuation ->
    addListener(
        {
            try {
                continuation.resume(get())
            } catch (error: Throwable) {
                continuation.resumeWithException(error.unwrapFutureFailure())
            }
        },
        DirectExecutor,
    )
}

internal suspend fun <T> CompletableFuture<T>.await(): T = suspendCoroutine { continuation ->
    whenComplete { value, error ->
        if (error == null) {
            continuation.resume(value)
        } else {
            continuation.resumeWithException(error.unwrapFutureFailure())
        }
    }
}

internal fun Throwable.unwrapFutureFailure(): Throwable =
    if (this is ExecutionException && cause != null) checkNotNull(cause) else this
