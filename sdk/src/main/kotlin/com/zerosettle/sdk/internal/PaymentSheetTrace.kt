package com.zerosettle.sdk.internal

import android.util.Log
import java.util.UUID

/**
 * Hierarchical performance tracer for payment sheet operations.
 * Records spans and instant events, then prints a flamegraph-style tree.
 * Maps to iOS `PaymentSheetTrace`.
 */
internal class PaymentSheetTrace(private val label: String) {

    companion object {
        private const val TAG = "ZS-PaymentSheet"

        /** The currently active trace. Set before preloading begins. */
        var current: PaymentSheetTrace? = null
    }

    private data class SpanNode(
        val id: String,
        val label: String,
        val startTime: Long,
        var endTime: Long? = null,
        val metadata: MutableMap<String, String> = mutableMapOf(),
        var parent: SpanNode? = null,
        val children: MutableList<SpanNode> = mutableListOf(),
        val events: MutableList<InstantEvent> = mutableListOf(),
    ) {
        val durationMs: Long? get() = endTime?.let { it - startTime }

        val formattedDuration: String
            get() {
                val ms = durationMs ?: return "..."
                return if (ms < 1) "<1ms" else "${ms}ms"
            }
    }

    private data class InstantEvent(
        val label: String,
        val timestamp: Long,
        val metadata: Map<String, String>,
    )

    private val root = SpanNode(
        id = UUID.randomUUID().toString(),
        label = label,
        startTime = System.currentTimeMillis(),
    )
    private val nodes = mutableMapOf(root.id to root)
    private val spanStack = mutableListOf(root.id)
    private val lock = Any()

    fun begin(label: String, metadata: Map<String, String> = emptyMap()): String {
        synchronized(lock) {
            val node = SpanNode(
                id = UUID.randomUUID().toString(),
                label = label,
                startTime = System.currentTimeMillis(),
                metadata = metadata.toMutableMap(),
            )
            nodes[node.id] = node

            spanStack.lastOrNull()?.let { parentId ->
                nodes[parentId]?.let { parent ->
                    node.parent = parent
                    parent.children.add(node)
                }
            }

            spanStack.add(node.id)
            Log.d(TAG, "▶ $label${formatMeta(metadata)}")
            return node.id
        }
    }

    fun end(id: String, metadata: Map<String, String> = emptyMap()) {
        synchronized(lock) {
            val node = nodes[id] ?: return
            node.endTime = System.currentTimeMillis()
            metadata.forEach { (k, v) -> node.metadata[k] = v }

            spanStack.indexOfLast { it == id }.let { idx ->
                if (idx >= 0) spanStack.removeAt(idx)
            }

            Log.d(TAG, "◀ ${node.label}: ${node.formattedDuration}${formatMeta(metadata)}")
        }
    }

    fun event(label: String, metadata: Map<String, String> = emptyMap()) {
        synchronized(lock) {
            val evt = InstantEvent(label, System.currentTimeMillis(), metadata)
            spanStack.lastOrNull()?.let { parentId ->
                nodes[parentId]?.events?.add(evt)
            }
        }
        Log.d(TAG, "● $label${formatMeta(metadata)}")
    }

    fun finish() {
        synchronized(lock) {
            root.endTime = System.currentTimeMillis()
        }
        val output = buildFlamegraph()
        Log.i(TAG, output)
        current = null
    }

    private fun buildFlamegraph(): String {
        val divider = "─".repeat(54)
        val lines = mutableListOf<String>()
        lines.add("┌$divider")
        lines.add("│ ZSPaymentSheet Trace — ${root.formattedDuration}")
        lines.add("├$divider")
        renderNode(root, "│ ", lines)
        lines.add("└$divider")
        return lines.joinToString("\n")
    }

    private fun renderNode(node: SpanNode, prefix: String, lines: MutableList<String>) {
        val timeline = mutableListOf<Pair<Long, Any>>()
        node.children.forEach { timeline.add(it.startTime to it) }
        node.events.forEach { timeline.add(it.timestamp to it) }
        timeline.sortBy { it.first }

        timeline.forEachIndexed { i, (_, item) ->
            val isLast = i == timeline.lastIndex
            val connector = if (isLast) "└─" else "├─"
            val childPrefix = prefix + if (isLast) "   " else "│  "

            when (item) {
                is SpanNode -> {
                    val meta = formatMeta(item.metadata)
                    lines.add("$prefix$connector ${item.label} ${item.formattedDuration}$meta")
                    renderNode(item, childPrefix, lines)
                }
                is InstantEvent -> {
                    val offsetMs = item.timestamp - root.startTime
                    val meta = formatMeta(item.metadata)
                    lines.add("$prefix$connector ${item.label}$meta · +${offsetMs}ms")
                }
            }
        }
    }

    private fun formatMeta(meta: Map<String, String>): String {
        if (meta.isEmpty()) return ""
        return " [${meta.entries.sortedBy { it.key }.joinToString(", ") { "${it.key}=${it.value}" }}]"
    }
}
