package com.rainy.token.data.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * APP 内调试日志，内存 ring buffer（默认 200 条）。
 * 各 Repository 在关键路径写入，用户可在设置页 → 调试日志 查看。
 */
object DebugLog {

    enum class Level(val label: String) { INFO("INFO"), WARN("WARN"), ERROR("ERROR") }

    data class Entry(
        val timestamp: Long,
        val tag: String,
        val level: Level,
        val message: String
    ) {
        override fun toString(): String {
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
            return "${fmt.format(Calendar.getInstance().apply { timeInMillis = this@Entry.timestamp }.time)} ${level.label}/$tag: $message"
        }
    }

    private const val MAX_SIZE = 200
    private val deque = ConcurrentLinkedDeque<Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun log(tag: String, level: Level, message: String) {
        val entry = Entry(System.currentTimeMillis(), tag, level, message)
        deque.addFirst(entry)
        while (deque.size > MAX_SIZE) deque.pollLast()
        _entries.value = deque.toList()
    }

    fun i(tag: String, message: String) = log(tag, Level.INFO, message)
    fun w(tag: String, message: String) = log(tag, Level.WARN, message)
    fun e(tag: String, message: String) = log(tag, Level.ERROR, message)

    fun clear() {
        deque.clear()
        _entries.value = emptyList()
    }
}