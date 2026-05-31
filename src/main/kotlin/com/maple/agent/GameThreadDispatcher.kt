package com.maple.agent

import kotlinx.coroutines.suspendCancellableCoroutine
import net.minecraft.server.MinecraftServer
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 协程 ↔ MC 主线程桥接。
 * 将游戏线程操作挂起为协程调用。
 */
object GameThreadDispatcher {

    /**
     * 在游戏主线程上执行操作，并挂起等待结果。
     */
    suspend fun <T> runOnGameThread(server: MinecraftServer, action: () -> T): T {
        return suspendCancellableCoroutine { continuation ->
            val future = CompletableFuture.supplyAsync({
                try {
                    action()
                } catch (e: Exception) {
                    throw e
                }
            }, server)

            future.whenComplete { result, error ->
                if (error != null) {
                    continuation.resumeWithException(error)
                } else {
                    continuation.resume(result)
                }
            }
        }
    }

    /**
     * 在游戏主线程上执行操作（无返回值）。
     */
    suspend fun runOnGameThread(server: MinecraftServer, action: Runnable) {
        runOnGameThread(server) {
            action.run()
            Unit
        }
    }
}
