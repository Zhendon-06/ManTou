package com.hfad.mantou.data.logging

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

/**
 * 内存中的 API 日志环形缓冲,只保留最近 [CAPACITY] 条。
 * 不持久化,App 退出即清空。供 RequestLogActivity 订阅。
 */
object ApiLogStore {

    const val CAPACITY = 30

    private val idSeq = AtomicLong(0)
    private val buffer = ArrayDeque<ApiLogEntry>(CAPACITY)
    private val lock = Any()

    private val _entries = MutableStateFlow<List<ApiLogEntry>>(emptyList())
    val entries: StateFlow<List<ApiLogEntry>> = _entries

    fun nextId(): Long = idSeq.incrementAndGet()

    fun append(entry: ApiLogEntry) {
        synchronized(lock) {
            if (buffer.size >= CAPACITY) {
                buffer.pollFirst()
            }
            buffer.offerLast(entry)
            _entries.value = buffer.toList().asReversed()
        }
    }

    fun update(traceId: String, transform: (ApiLogEntry) -> ApiLogEntry): Boolean {
        synchronized(lock) {
            val entries = buffer.toMutableList()
            val index = entries.indexOfLast { it.traceId == traceId }
            if (index < 0) return false
            entries[index] = transform(entries[index])
            buffer.clear()
            entries.forEach(buffer::offerLast)
            _entries.value = buffer.toList().asReversed()
            return true
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _entries.value = emptyList()
        }
    }
}
