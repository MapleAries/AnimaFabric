package com.maple.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * 步骤间数据传递。使用 $key 占位符引用前一步骤的结果。
 */
class SharedState {
    private val state = ConcurrentHashMap<String, String>()

    fun set(key: String, value: String) {
        state[key] = value
    }

    fun get(key: String): String? = state[key]

    fun resolve(template: String): String {
        var result = template
        for ((key, value) in state) {
            result = result.replace("\$$key", value)
        }
        return result
    }

    fun resolveAll(params: Map<String, String>): Map<String, String> {
        return params.mapValues { (_, value) -> resolve(value) }
    }

    fun clear() {
        state.clear()
    }
}
