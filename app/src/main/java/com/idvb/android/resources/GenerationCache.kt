package com.idvb.android.resources

/** Borrowed values remain valid after eviction. A decode begun before clear cannot refill it. */
internal class GenerationCache<K, V>(private val maximumBytes: Long, private val bytesOf: (V) -> Long) {
    private val values = LinkedHashMap<K, V>(16, .75f, true)
    private var generation = 0L
    private var bytes = 0L
    val retainedBytes: Long get() = synchronized(this) { bytes }
    val size: Int get() = synchronized(this) { values.size }

    init { require(maximumBytes > 0) }

    fun load(key: K, decode: () -> V?): V? {
        val revision = synchronized(this) {
            values[key]?.let { return it }
            generation
        }
        val value = decode() ?: return null
        val cost = bytesOf(value).also { require(it >= 0) }
        synchronized(this) {
            if (revision == generation && cost <= maximumBytes) {
                values.remove(key)?.let { bytes -= bytesOf(it) }
                while (values.isNotEmpty() && bytes + cost > maximumBytes) {
                    val first = values.entries.first()
                    bytes -= bytesOf(first.value)
                    values.remove(first.key)
                }
                values[key] = value
                bytes += cost
            }
        }
        return value
    }

    @Synchronized fun clear() { generation++; values.clear(); bytes = 0 }
}
