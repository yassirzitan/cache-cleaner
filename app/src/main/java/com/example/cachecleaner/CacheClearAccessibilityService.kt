package com.example.cachecleaner

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Drives the real click path: App Info -> "Storage" row -> "Clear cache" button
 * -> Back -> Back (out of Storage, out of App Info, landing back on our app).
 *
 * MainActivity tells this service "a fresh App Info screen just opened" by
 * setting [AutoClickState.resetForNewApp] right before it launches each app's
 * settings screen. That's more reliable than guessing from Android's window
 * events, since whether "Storage" is a separate screen/Activity or just a
 * fragment swap varies by OEM and Android version.
 *
 * Safety: a hard blacklist blocks it from ever clicking "Clear storage",
 * "Clear data", "Uninstall", "Force stop", etc. Only "Clear cache" is a valid
 * target, and only the exact "Storage" / "Storage & cache" row is treated as
 * the entry point — never a substring match that could catch a dangerous
 * button by accident.
 */
class CacheClearAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())

    private enum class Step { FIND_STORAGE, FIND_CLEAR_CACHE, DONE }
    private var step = Step.DONE
    private var actionInFlight = false

    private val storageRowLabels = listOf("storage", "storage & cache", "storage and cache")

    private val neverClick = listOf(
        "clear storage", "clear data", "uninstall", "force stop",
        "delete app", "reset app", "disable"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!AutoClickState.enabled) {
            step = Step.DONE
            return
        }
        if (event == null) return

        if (AutoClickState.resetForNewApp) {
            AutoClickState.resetForNewApp = false
            step = Step.FIND_STORAGE
        }

        if (step == Step.DONE || actionInFlight) return

        val root = rootInActiveWindow ?: return

        when (step) {
            Step.FIND_STORAGE -> {
                val node = findExactRow(root, storageRowLabels) ?: return
                clickThenWait(node) { step = Step.FIND_CLEAR_CACHE }
            }
            Step.FIND_CLEAR_CACHE -> {
                val node = findClearCacheButton(root) ?: return
                clickThenWait(node) {
                    step = Step.DONE
                    // Let the OS actually finish clearing before backing out twice:
                    // once out of Storage, once out of App Info.
                    handler.postDelayed({
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 450)
                    }, 700)
                }
            }
            Step.DONE -> {}
        }
    }

    private fun clickThenWait(node: AccessibilityNodeInfo, after: () -> Unit) {
        actionInFlight = true
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({
            actionInFlight = false
            after()
        }, 500)
    }

    /** Exact (trimmed, case-insensitive) match only — never a substring match,
     *  so this can't accidentally land on something like "Clear storage". */
    private fun findExactRow(node: AccessibilityNodeInfo, labels: List<String>): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.trim()?.lowercase()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase()
        if ((text != null && labels.contains(text)) || (desc != null && labels.contains(desc))) {
            clickableSelfOrParent(node)?.let { return it }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findExactRow(child, labels)?.let { return it }
        }
        return null
    }

    private fun findClearCacheButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()
        val combined = listOfNotNull(text, desc)

        if (combined.none { isBlacklisted(it) } && combined.any { it.contains("clear cache") }) {
            clickableSelfOrParent(node)?.let { return it }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findClearCacheButton(child)?.let { return it }
        }
        return null
    }

    private fun isBlacklisted(text: String) = neverClick.any { text.contains(it) }

    private fun clickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return if (n != null && n.isEnabled) n else null
    }

    override fun onInterrupt() {}
}

/** Coordination flags MainActivity uses to drive this service. */
object AutoClickState {
    @Volatile var enabled: Boolean = false
    @Volatile var resetForNewApp: Boolean = false
}
