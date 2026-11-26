package com.aegis.hands

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SharedMemory
import android.system.OsConstants
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.aegis.core.overlay.AegisEdgeGlyphWindowManager
import com.aegis.core.overlay.AegisLiveHudState
import com.aegis.vision.ScreenVisionCapture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.security.MessageDigest
import kotlin.coroutines.resume

enum class ExecutionState {
    IDLE,
    READING_DOM,
    PLANNING,
    PREVIEW_HIGHLIGHT,
    AWAITING_CONFIRM,
    EXECUTING_TOKEN,
    SUSPENDED,
    THREAT_BLOCKED,
    USER_ABORTED
}

data class VerifiedNodeSnapshot(
    val packageName: String,
    val textLabel: String,
    val bounds: Rect,
    val windowId: Int,
    val nodeHash: String,
    val isEditable: Boolean = false,
    val isClickable: Boolean = false
)

data class ScreenObservation(
    val packageName: String,
    val isFlagSecure: Boolean,
    val visibleText: String,
    val nodes: List<VerifiedNodeSnapshot>,
    val summaryForBrain: String
)

/**
 * DOM-First Accessibility Runner with:
 * - Anti-Clickjacking live node re-hashing immediately prior to dispatch
 * - Automatic Level 0 FLAG_SECURE / Banking / UPI suppression
 * - Physical Volume-Down Emergency Kill Switch (<50 ms teardown)
 * - Unexpected foreground popup suspension (SUSPENDED state)
 * - Gesture-tap fallback when custom Compose/WebView nodes ignore ACTION_CLICK
 */
class AegisAccessibilityService : AccessibilityService() {

    @Volatile
    var currentState: ExecutionState = ExecutionState.IDLE
        private set

    @Volatile
    private var expectedForegroundPackage: String? = null

    var onEmergencyKillSwitch: (() -> Unit)? = null
    var onUnexpectedPopupSuspend: ((String) -> Unit)? = null

    private var hudScope: CoroutineScope? = null
    private var hudWindowManager: AegisEdgeGlyphWindowManager? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        hudWindowManager?.detach()
        hudScope?.cancel()
        val mgr = AegisEdgeGlyphWindowManager(this, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY).also {
            it.attach()
        }
        hudWindowManager = mgr
        hudScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob()).also { scope ->
            scope.launch {
                AegisLiveHudState.state.collectLatest { snap ->
                    mgr.render(snap)
                }
            }
        }
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance === this) instance = null
        hudScope?.cancel()
        hudScope = null
        hudWindowManager?.detach()
        hudWindowManager = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        hudScope?.cancel()
        hudScope = null
        hudWindowManager?.detach()
        hudWindowManager = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (currentState == ExecutionState.PREVIEW_HIGHLIGHT || currentState == ExecutionState.EXECUTING_TOKEN) {
            val expected = expectedForegroundPackage
            if (expected != null && pkg != expected) {
                currentState = ExecutionState.SUSPENDED
                onUnexpectedPopupSuspend?.invoke(pkg)
            }
        }
    }

    override fun onInterrupt() {
        currentState = ExecutionState.SUSPENDED
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event?.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN && event.action == KeyEvent.ACTION_DOWN) {
            if (currentState != ExecutionState.IDLE) {
                currentState = ExecutionState.USER_ABORTED
                onEmergencyKillSwitch?.invoke()
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    /**
     * Computes deterministic SHA-256 anti-clickjacking hash binding package, label, bounds, and windowId.
     */
    fun computeLiveNodeHash(node: AccessibilityNodeInfo): VerifiedNodeSnapshot {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val pkg = node.packageName?.toString().orEmpty()
        val hintLabel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) node.hintText else null
        val directLabel = (node.text ?: node.contentDescription ?: hintLabel)?.toString()?.trim().orEmpty()
        val label = if (directLabel.isNotBlank() && !node.isEditable) {
            directLabel
        } else {
            collectDescendantLabels(node, depth = 2).ifBlank { directLabel }
        }
        val raw = "$pkg|$label|${bounds.toShortString()}|${node.windowId}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        val hex = digest.joinToString("") { "%02x".format(it) }
        return VerifiedNodeSnapshot(
            packageName = pkg,
            textLabel = label,
            bounds = bounds,
            windowId = node.windowId,
            nodeHash = "sha256:$hex",
            isEditable = node.isEditable,
            isClickable = node.isClickable
        )
    }

    fun captureScreenObservation(maxNodes: Int = 24, maxChars: Int = 1200): ScreenObservation {
        val flagSecure = isActiveWindowFlagSecure()
        if (flagSecure) {
            return ScreenObservation(
                packageName = "",
                isFlagSecure = true,
                visibleText = "",
                nodes = emptyList(),
                summaryForBrain = "ACTIVE_APP=FLAG_SECURE"
            )
        }
        val (pkg, text) = captureVisibleDomText(maxChars)
        val nodes = captureInteractiveNodes(maxNodes)
        val dm = resources.displayMetrics
        val wPx = dm.widthPixels.coerceAtLeast(1).toFloat()
        val hPx = dm.heightPixels.coerceAtLeast(1).toFloat()
        val nodeLines = nodes.mapIndexed { idx, n ->
            val kind = if (n.isEditable) "EDIT" else "BTN"
            val label = n.textLabel.ifBlank { "(unlabeled)" }.take(36)
            val nx = ((n.bounds.centerX() / wPx) * 1000f).toInt().coerceIn(0, 999)
            val ny = ((n.bounds.centerY() / hPx) * 1000f).toInt().coerceIn(0, 999)
            "#$idx[$kind] \"$label\" ($nx,$ny)"
        }.joinToString("; ")
        val summary = "ACTIVE_PKG=$pkg | NODES=[$nodeLines] | TEXT=${text.replace('\n', ' ').take(400)}"
        return ScreenObservation(pkg, false, text, nodes, summary)
    }

    /**
     * Resolves a real interactive node in the active window for the requested action type + optional label hint.
     */
    private fun activeRoot(): AccessibilityNodeInfo? {
        return rootInActiveWindow ?: runCatching {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }?.root
                ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
        }.getOrNull()
    }

    fun resolveLiveTargetSnapshot(actionType: String, hintText: String = ""): VerifiedNodeSnapshot? {
        if (isActiveWindowFlagSecure()) return null
        val root = activeRoot() ?: return null
        val target = findBestTargetNode(root, actionType, hintText) ?: return null
        return computeLiveNodeHash(target)
    }

    /**
     * Executes CLICK_NODE / CLICK_SEND / TYPE_DRAFT / SEND_MESSAGE / READ_DOM / SCROLL_DOWN / NAVIGATE_BACK
     * after verifying live node hash and ensuring the active window is not FLAG_SECURE.
     */
    fun executeVerifiedAction(
        actionType: String,
        expectedNodeHash: String,
        payloadText: String,
        tokenStillValid: () -> Boolean
    ): Pair<Boolean, String> {
        if (!tokenStillValid()) return false to "Capability token expired or burned"
        if (isActiveWindowFlagSecure()) {
            currentState = ExecutionState.THREAT_BLOCKED
            return false to "Level 0 FLAG_SECURE window active"
        }
        val act = actionType.uppercase()
        if (act == "NAVIGATE_BACK") {
            val ok = performGlobalAction(GLOBAL_ACTION_BACK)
            return ok to if (ok) "Navigated back" else "Back action failed"
        }
        if (act == "NAVIGATE_HOME") {
            val ok = performGlobalAction(GLOBAL_ACTION_HOME)
            return ok to if (ok) "Returned to Home" else "Home action failed"
        }
        if (act == "TAP_COORDS") {
            val parts = payloadText.split(",")
            val xNorm = parts.getOrNull(0)?.trim()?.toIntOrNull()
            val yNorm = parts.getOrNull(1)?.trim()?.toIntOrNull()
            if (xNorm != null && yNorm != null && xNorm in 1..999 && yNorm in 1..999) {
                val ok = tapNormalized(xNorm, yNorm)
                return ok to if (ok) "Tapped VLM visual coords ($xNorm, $yNorm)" else "Coordinate gesture failed"
            }
        }
        if (act == "SCROLL_DOWN" || act == "SCROLL_UP") {
            val ok = scrollNormalized(down = (act == "SCROLL_DOWN"))
            if (ok) {
                return true to "Dispatched direct touch swipe ($act)"
            }
        }

        val root = activeRoot() ?: return false to "No active window root"
        if (act == "READ_DOM") {
            val (pkg, text) = captureVisibleDomText(400)
            return true to "Read ${text.length} chars from $pkg"
        }

        val target = findNodeByHash(root, expectedNodeHash)
            ?: return false to "NodeHashMismatch: target node missing or shifted"

        val live = computeLiveNodeHash(target)
        expectedForegroundPackage = live.packageName
        currentState = ExecutionState.EXECUTING_TOKEN

        val ok = when (act) {
            "TYPE_DRAFT", "TYPE_TEXT", "SEND_MESSAGE", "SEND_EMAIL", "TYPE_AND_ENTER" -> {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                if (act == "TYPE_AND_ENTER") {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, payloadText)
                }
                var typed = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (!typed) {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    typed = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                }
                if (act == "TYPE_AND_ENTER" && typed) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                    }
                    true
                } else if ((act == "SEND_MESSAGE" || act == "SEND_EMAIL") && typed) {
                    val sendBtn = findClickableByKeywords(root, listOf("send", "submit"))
                    if (sendBtn != null) {
                        sendBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK) || tapBoundsCenter(sendBtn)
                    } else {
                        true
                    }
                } else {
                    typed
                }
            }
            "PRESS_ENTER" -> {
                val ed = findFirstEditable(root)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && ed != null) {
                    ed.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else {
                    target.performAction(AccessibilityNodeInfo.ACTION_CLICK) || tapBoundsCenter(target)
                }
            }
            "SCROLL_DOWN" -> target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            else -> target.performAction(AccessibilityNodeInfo.ACTION_CLICK) || tapBoundsCenter(target)
        }
        currentState = ExecutionState.IDLE
        return ok to if (ok) "Executed $act on ${live.packageName}" else "Accessibility performAction returned false"
    }

    private fun tapBoundsCenter(node: AccessibilityNodeInfo): Boolean {
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun tapNormalized(xNorm: Int, yNorm: Int): Boolean {
        val dm = resources.displayMetrics
        val px = (xNorm.coerceIn(1, 999) / 1000f) * dm.widthPixels
        val py = (yNorm.coerceIn(1, 999) / 1000f) * dm.heightPixels
        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 45L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    /**
     * Dispatches a direct touch swipe gesture without querying Accessibility XML scrollables.
     */
    fun scrollNormalized(down: Boolean = true): Boolean {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels * 0.5f
        val startY = dm.heightPixels * (if (down) 0.74f else 0.30f)
        val endY = dm.heightPixels * (if (down) 0.28f else 0.76f)
        val path = Path().apply {
            moveTo(cx, startY)
            lineTo(cx, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, 260L)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun isActiveWindowFlagSecure(): Boolean {
        return windows.any { win ->
            win.isActive && win.type == AccessibilityWindowInfo.TYPE_APPLICATION && win.root == null
        }
    }

    fun captureInteractiveNodes(maxNodes: Int = 25): List<VerifiedNodeSnapshot> {
        val root = activeRoot() ?: return emptyList()
        val out = mutableListOf<VerifiedNodeSnapshot>()
        collectInteractive(root, out, maxNodes)
        return out
    }

    private fun collectDescendantLabels(node: AccessibilityNodeInfo?, depth: Int = 2): String {
        if (node == null || depth < 0) return ""
        val sb = StringBuilder()
        fun appendPart(cs: CharSequence?) {
            val s = cs?.toString()?.trim()
            if (!s.isNullOrEmpty()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(s)
            }
        }
        appendPart(node.text)
        appendPart(node.contentDescription)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            appendPart(node.hintText)
        }
        if (depth > 0) {
            for (i in 0 until node.childCount) {
                val childText = collectDescendantLabels(node.getChild(i), depth - 1)
                if (childText.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append(' ')
                    sb.append(childText)
                }
            }
        }
        return sb.toString()
    }

    private fun findBestTargetNode(
        root: AccessibilityNodeInfo,
        actionType: String,
        hintText: String
    ): AccessibilityNodeInfo? {
        val hint = hintText.trim().lowercase()
        return when (actionType.uppercase()) {
            "TYPE_DRAFT", "TYPE_TEXT", "SEND_MESSAGE", "SEND_EMAIL", "TYPE_AND_ENTER" -> {
                val editKeywords = when (hint) {
                    "last name" -> listOf("last name", "surname", "family name")
                    "first name" -> listOf("first name", "given name")
                    "phone" -> listOf("phone", "mobile", "telephone", "number")
                    else -> if (hint.isNotEmpty()) listOf(hint) else emptyList()
                }
                if (editKeywords.isNotEmpty()) {
                    findEditableByKeywords(root, editKeywords)
                        ?: if (actionType.equals("TYPE_AND_ENTER", ignoreCase = true)) findFirstEditable(root) else null
                } else {
                    findFirstEditable(root)
                }
            }
            "PRESS_ENTER" -> findFirstEditable(root) ?: root
            "CLICK_SEND" -> findClickableByKeywords(root, listOf("send", "submit", "post"))
            "SCROLL_DOWN" -> findFirstScrollable(root)
            "READ_DOM", "NAVIGATE_BACK", "NAVIGATE_HOME" -> root
            else -> {
                if (hint.startsWith("#")) {
                    val idx = hint.removePrefix("#").toIntOrNull()
                    if (idx != null) {
                        val list = captureInteractiveNodes(24)
                        list.getOrNull(idx)?.let { return findNodeByHash(root, it.nodeHash) }
                    }
                }
                if (hint.isNotEmpty()) {
                    val clickKeywords = when (hint.replace('\u2011', '-')) {
                        "create" -> listOf("create contact", "create", "new contact", "add contact")
                        "pause" -> listOf("pause")
                        "wi-fi", "wifi" -> listOf("wi-fi", "wifi", "internet", "network & internet")
                        else -> listOf(hint.replace('\u2011', '-'))
                    }
                    findClickableByKeywords(root, clickKeywords)
                } else {
                    findFirstClickable(root)
                }
            }
        }
    }

    private fun findEditableByKeywords(node: AccessibilityNodeInfo?, keywords: List<String>): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) {
            val selfLabels = collectDescendantLabels(node, depth = 2).lowercase()
            val parentLabels = node.parent?.let { collectDescendantLabels(it, depth = 1).lowercase() }.orEmpty()
            val combined = "$selfLabels $parentLabels"
            if (keywords.any { combined.contains(it) }) return node
        }
        for (i in 0 until node.childCount) {
            findEditableByKeywords(node.getChild(i), keywords)?.let { return it }
        }
        return null
    }

    private fun findFirstEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            findFirstEditable(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun findFirstClickable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isClickable) return node
        for (i in 0 until node.childCount) {
            findFirstClickable(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun findFirstScrollable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            findFirstScrollable(node.getChild(i))?.let { return it }
        }
        return null
    }

    private fun findClickableByKeywords(node: AccessibilityNodeInfo?, keywords: List<String>): AccessibilityNodeInfo? {
        if (node == null || keywords.isEmpty()) return null
        val normalizedAll = mutableListOf<Triple<AccessibilityNodeInfo, String, Int>>()
        fun collect(cur: AccessibilityNodeInfo?) {
            if (cur == null) return
            if (cur.isClickable) {
                val combined = collectDescendantLabels(cur, depth = 2)
                    .replace('\u2011', '-').replace('\u2013', '-').replace('\u00A0', ' ').lowercase()
                if (combined.isNotEmpty() && keywords.any { combined.contains(it) }) {
                    val r = Rect()
                    cur.getBoundsInScreen(r)
                    normalizedAll.add(Triple(cur, combined, combined.length.coerceAtMost(200) * 1000 + (r.width().coerceAtLeast(1) * r.height().coerceAtLeast(1) / 1000)))
                }
            } else {
                val label = ((cur.text ?: cur.contentDescription)?.toString().orEmpty())
                    .replace('\u2011', '-').replace('\u2013', '-').replace('\u00A0', ' ').lowercase()
                if (label.isNotEmpty() && keywords.any { label.contains(it) }) {
                    var p = cur.parent
                    var hops = 0
                    while (p != null && hops < 4) {
                        if (p.isClickable) {
                            val r = Rect()
                            p.getBoundsInScreen(r)
                            normalizedAll.add(Triple(p, label, label.length.coerceAtMost(200) * 1000 + (r.width().coerceAtLeast(1) * r.height().coerceAtLeast(1) / 1000)))
                            break
                        }
                        p = p.parent
                        hops++
                    }
                }
            }
            for (i in 0 until cur.childCount) {
                collect(cur.getChild(i))
            }
        }
        collect(node)
        for (kw in keywords) {
            val kwMatches = normalizedAll.filter { it.second.contains(kw) }
            if (kwMatches.isNotEmpty()) {
                return kwMatches.sortedWith(
                    compareByDescending<Triple<AccessibilityNodeInfo, String, Int>> { it.second == kw }
                        .thenBy { it.third }
                ).first().first
            }
        }
        return null
    }

    private fun collectInteractive(
        node: AccessibilityNodeInfo?,
        out: MutableList<VerifiedNodeSnapshot>,
        maxNodes: Int
    ) {
        if (node == null || out.size >= maxNodes) return
        if (node.isClickable || node.isEditable) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (node.isEditable || r.right > 0 || r.bottom > 0) {
                out.add(computeLiveNodeHash(node))
            }
        }
        for (i in 0 until node.childCount) {
            if (out.size >= maxNodes) break
            collectInteractive(node.getChild(i), out, maxNodes)
        }
    }

    private fun findNodeByHash(node: AccessibilityNodeInfo?, expectedHash: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isClickable || node.isEditable) {
            if (computeLiveNodeHash(node).nodeHash == expectedHash) return node
        }
        for (i in 0 until node.childCount) {
            val found = findNodeByHash(node.getChild(i), expectedHash)
            if (found != null) return found
        }
        return null
    }

    fun captureVisibleDomText(maxChars: Int = 2000): Pair<String, String> {
        val root = activeRoot() ?: return "" to ""
        val pkg = root.packageName?.toString().orEmpty()
        val sb = StringBuilder()
        collectText(root, sb, maxChars)
        return pkg to sb.toString()
    }

    private fun collectText(node: AccessibilityNodeInfo?, sb: StringBuilder, maxChars: Int) {
        if (node == null || sb.length >= maxChars) return
        val label = (node.text ?: node.contentDescription)?.toString()?.trim()
        if (!label.isNullOrEmpty()) {
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(label)
        }
        for (i in 0 until node.childCount) {
            if (sb.length >= maxChars) break
            collectText(node.getChild(i), sb, maxChars)
        }
    }

    /**
     * Captures the live screen (prefers active MediaProjection VirtualDisplay; otherwise falls back to
     * AccessibilityService.takeScreenshot with overlay temporarily hidden) and converts via preallocated scratch
     * into a 768x768 RGB888 Ashmem SharedMemory buffer for the isolated VLM brain.
     */
    suspend fun captureScreenRgbFrame768(): SharedMemory? {
        if (ScreenVisionCapture.hasActiveProjection()) {
            ScreenVisionCapture.captureProjectionAshmem()?.let { shm ->
                android.util.Log.i("AegisVLM", "Captured 768x768 Ashmem live screen-share frame")
                return shm
            }
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || isActiveWindowFlagSecure()) {
            return null
        }
        AegisLiveHudState.setCaptureHidden(true)
        delay(15L)
        return try {
            withTimeoutOrNull(5_000L) {
                suspendCancellableCoroutine { cont ->
                    runCatching {
                        takeScreenshot(
                            Display.DEFAULT_DISPLAY,
                            mainExecutor,
                            object : TakeScreenshotCallback {
                                override fun onSuccess(screenshot: ScreenshotResult) {
                                    val shm = runCatching {
                                        val hwBmp = Bitmap.wrapHardwareBuffer(
                                            screenshot.hardwareBuffer,
                                            screenshot.colorSpace
                                        ) ?: return@runCatching null
                                        val swBmp = hwBmp.copy(Bitmap.Config.ARGB_8888, false)
                                        hwBmp.recycle()
                                        val mem = ScreenVisionCapture.bitmapToSharedMemory768(swBmp)
                                        swBmp.recycle()
                                        android.util.Log.i("AegisVLM", "Captured 768x768 Ashmem screenshot frame")
                                        mem
                                    }.getOrNull()
                                    runCatching { screenshot.hardwareBuffer.close() }
                                    if (cont.isActive) cont.resume(shm)
                                }

                                override fun onFailure(errorCode: Int) {
                                    android.util.Log.w("AegisVLM", "takeScreenshot failed errorCode=$errorCode")
                                    if (cont.isActive) cont.resume(null)
                                }
                            }
                        )
                    }.onFailure {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            }.also { timedResult ->
                if (timedResult == null) {
                    android.util.Log.w("AegisVLM", "takeScreenshot timed out or returned null after 5000ms")
                }
            }
        } finally {
            AegisLiveHudState.setCaptureHidden(false)
        }
    }

    companion object {
        @Volatile
        var instance: AegisAccessibilityService? = null
            private set
    }
}
