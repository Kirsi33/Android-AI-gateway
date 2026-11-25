package dev.aigw.core

import dev.aigw.core.store.KeyValueStore
import java.util.Collections

/**
 * 测试用的内存键值存储。
 *
 * 并发测试（32 路同时对话等）会从多个线程读写同一个实例。裸 LinkedHashMap 下
 * `keys()` 的遍历会和并发写入撞出 ConcurrentModificationException，表现为网关偶发 500；
 * 这里用同步包装 + 遍历时锁住整个 map，保证测试基础设施是线程安全的。
 */
class InMemoryKeyValueStore : KeyValueStore {
    private val data = Collections.synchronizedMap(LinkedHashMap<String, String>())

    override fun read(key: String): String? = data[key]

    override fun write(key: String, value: String) {
        data[key] = value
    }

    override fun delete(key: String) {
        data.remove(key)
    }

    override fun keys(prefix: String): List<String> = synchronized(data) {
        data.keys.filter { it.startsWith(prefix) }
    }

    fun raw(): Map<String, String> = synchronized(data) { data.toMap() }
}
