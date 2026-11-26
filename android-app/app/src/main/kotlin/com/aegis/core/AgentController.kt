package com.aegis.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import com.aegis.core.overlay.AegisLiveHudState
import com.aegis.core.overlay.HudGlowMode
import com.aegis.hands.AegisAccessibilityService
import com.aegis.hands.ScreenObservation
import com.aegis.hardware.HardwareProfile
import com.aegis.hardware.HardwareProfiler
import com.aegis.ipc.IBrainEngine
import com.aegis.vision.ScreenVisionCapture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uniffi.aegis_broker.AegisBroker
import uniffi.aegis_broker.BrokerException
import uniffi.aegis_broker.ProposedAction
import uniffi.aegis_broker.RiskLevel
import java.security.MessageDigest
import java.util.UUID

sealed class AgentStepOutcome {
    data class Executed(
        val summary: String,
        val brainJson: String
    ) : AgentStepOutcome()

    data class NeedsTapApproval(
        val action: ProposedAction,
        val compositeNodeHash: String,
        val expiresAtMonoMs: Long,
        val brainJson: String
    ) : AgentStepOutcome()

    data class Blocked(
        val sourceLabel: String,
        val attemptSummary: String,
        val matchedSnippet: String,
        val statusText: String
    ) : AgentStepOutcome()

    data class Denied(val reason: String) : AgentStepOutcome()
}

data class PlannedStep(
    val action: String,
    val targetPackage: String,
    val targetHint: String,
    val payloadText: String,
    val engineName: String,
    val rawBrainJson: String,
    val tapX: Int = -1,
    val tapY: Int = -1
)

data class SubgoalStep(
    val action: String,
    val targetHint: String,
    val payload: String = ""
)

data class ListingReport(
    val title: String,
    val price: String,
    val rating: String,
    val reviews: String,
    val score: Double,
    val summary: String
)

/**
 * Coordinates Process 1 (Sanitizer, Hard-Rule Policy, Capability Minting, Multi-Step Accessibility Dispatch)
 * with Process 2 (:aegis_brain_isolated Binder IPC / llama.cpp).
 */
class AgentController(
    private val context: Context,
    private val brokerProvider: () -> AegisBroker,
    private val brainProvider: () -> IBrainEngine?,
    private val profileProvider: () -> HardwareProfile
) {
    /**
     * Multi-step Observe -> Plan -> Hard-Rule Gate -> Act loop for live phone use.
     * Suspends safely when Level 2 explicit confirmation is required.
     */
    suspend fun runAutonomousGoal(
        userCommand: String,
        defaultPackage: String,
        defaultAction: String,
        defaultPayload: String,
        explicitScreenOverride: String = "",
        maxSteps: Int = 10,
        onStepProgress: suspend (String) -> Unit,
        requestUserConfirm: suspend (AgentStepOutcome.NeedsTapApproval) -> ProposedAction?,
        onBlocked: suspend (AgentStepOutcome.Blocked) -> Unit
    ): AgentStepOutcome = withContext(Dispatchers.IO) {
        val svc = AegisAccessibilityService.instance
        val broker = brokerProvider()
        val outboundCheck = runCatching {
            broker.validateOutboundPayload(defaultAction, defaultPayload.ifBlank { userCommand })
        }
        if (outboundCheck.isFailure) {
            val blocked = mapBrokerFailure(outboundCheck.exceptionOrNull()!!, defaultPackage, defaultAction)
            if (blocked is AgentStepOutcome.Blocked) onBlocked(blocked)
            return@withContext blocked
        }
        // Gate 2: 2.02M-param Guard verifies command & passes clearance directly to next model (llama.cpp)
        val guardVerdict = Guard2MEngine.evaluate(context, defaultAction, defaultPackage, userCommand, defaultPayload)
        if (guardVerdict.loaded && guardVerdict.label == "BLOCK_THREAT" && guardVerdict.probBlock >= 0.55) {
            val blocked = AgentStepOutcome.Blocked(
                sourceLabel = defaultPackage,
                attemptSummary = "$defaultAction blocked by Guard-2M (${guardVerdict.latencyUs} us)",
                matchedSnippet = "Guard-2M threat confidence ${(guardVerdict.probBlock * 100).toInt()}%",
                statusText = "Blocked by Guard-2M"
            )
            onBlocked(blocked)
            return@withContext blocked
        }
        val guardInputNote = if (guardVerdict.loaded) {
            "Guard2M=${guardVerdict.label}(p=${"%.2f".format(guardVerdict.probSafe)},${guardVerdict.latencyUs}us)"
        } else {
            "Guard2M=UNLOADED"
        }

        val isAllowedSettings = defaultPackage.equals("com.android.settings", ignoreCase = true) &&
            guardVerdict.loaded && guardVerdict.label == "SAFE_AUTO" && guardVerdict.probSafe >= 0.70
        if (svc == null || explicitScreenOverride.isNotBlank() ||
            (broker.classifyAction(defaultPackage, defaultAction, false) == RiskLevel.LEVEL0_HARD_BLOCKED && !isAllowedSettings)
        ) {
            // Single-step evaluation in sandbox mode, simulated screen text, or Level 0 fast-path block
            val outcome = evaluateStep(
                userCommand = userCommand,
                targetPackage = defaultPackage,
                actionType = defaultAction,
                rawScreenContext = explicitScreenOverride,
                payloadText = defaultPayload
            )
            return@withContext when (outcome) {
                is AgentStepOutcome.NeedsTapApproval -> {
                    val approved = requestUserConfirm(outcome)
                        ?: return@withContext AgentStepOutcome.Denied("Cancelled by user.")
                    completeTapApproval(approved).fold(
                        onSuccess = { AgentStepOutcome.Executed(it, outcome.brainJson) },
                        onFailure = { err -> mapBrokerFailure(err, approved.targetPackage, approved.actionType) }
                    )
                }
                is AgentStepOutcome.Blocked -> {
                    onBlocked(outcome)
                    outcome
                }
                else -> outcome
            }
        }

        // Real-world multi-step Accessibility Observe -> VLM Plan -> Grounded Act -> Verify loop (up to 10 steps)
        var lastSummary = "Done."
        var lastBrainJson = "{}"
        var historyNote = guardInputNote
        var launchedTargetOnce = false
        var ranVlmVisionOnce = false
        var lastVlmScreenSig = ""
        var lastActionKey = ""
        val subgoals = decomposeGoalIntoSubgoals(userCommand)
        val totalSteps = (subgoals.size + 1).coerceAtLeast(1)
        val memLabel = runCatching {
            val mappedBytes = org.json.JSONObject(brainProvider()?.getEngineTelemetry() ?: "{}").optLong("mapped_bytes", 0L)
            "${HardwareProfiler.estimatePeakMb(mappedBytes, profileProvider().maxContextTokens)} MB"
        }.getOrDefault("540 MB")
        AegisLiveHudState.resetSteps(memLabel)
        var subgoalIdx = 0
        var executedSteps = 0

        var hashRetryCount = 0
        var stallRecoveries = 0
        var exploreScrolls = 0
        var lastClickedListing = ""
        var lastDialogDismiss = ""
        val visitedListings = mutableSetOf<String>()
        val collectedListings = mutableListOf<ListingReport>()
        val amazonQuery = extractSearchQuery(userCommand)
        try {
            for (stepIdx in 1..28) {
                currentCoroutineContext().ensureActive()
                AegisLiveHudState.update(
                    mode = HudGlowMode.STEP_ACTIVE,
                    currentStep = (executedSteps + 1).coerceAtMost(totalSteps),
                    totalSteps = totalSteps,
                    statusTitle = "Lumen · Step ${executedSteps + 1}/$totalSteps",
                    actionDetail = if (launchedTargetOnce) "Observing active window..." else "Preparing $defaultPackage..."
                )
                var obs = svc.captureScreenObservation(maxNodes = 60, maxChars = 1600)
                if (launchedTargetOnce && obs.packageName.isBlank() && obs.nodes.isEmpty()) {
                    delay(280L)
                    obs = svc.captureScreenObservation(maxNodes = 60, maxChars = 1600)
                }
            if (obs.isFlagSecure) {
                val blocked = AgentStepOutcome.Blocked(
                    sourceLabel = "Active Window",
                    attemptSummary = "FLAG_SECURE Screen Protection",
                    matchedSnippet = "FLAG_SECURE window detected",
                    statusText = "Blocked by Level 0 FLAG_SECURE rule"
                )
                onBlocked(blocked)
                return@withContext blocked
            }

            // Fast-path Step 1 for Level 0 Hard-Blocked targets (except user-requested Settings navigation,
            // which Guard-2M evaluates) and simple single-action "Open <App>" commands.
            if (stepIdx == 1) {
                val preRisk = brokerProvider().classifyAction(defaultPackage, defaultAction, false)
                if (preRisk == RiskLevel.LEVEL0_HARD_BLOCKED &&
                    !defaultPackage.equals("com.android.settings", ignoreCase = true)
                ) {
                    val blockedOutcome = evaluateStep(
                        userCommand = userCommand,
                        targetPackage = defaultPackage,
                        actionType = defaultAction,
                        rawScreenContext = obs.visibleText,
                        payloadText = defaultPayload,
                        skipBrainInference = true
                    )
                    if (blockedOutcome is AgentStepOutcome.Blocked) {
                        onBlocked(blockedOutcome)
                    }
                    return@withContext blockedOutcome
                }
                if (isSimpleLaunchCommand(userCommand) && !defaultPackage.equals("com.android.core", ignoreCase = true) && !defaultPackage.equals("com.aegis.core", ignoreCase = true)) {
                    val launchOutcome = evaluateStep(
                        userCommand = userCommand,
                        targetPackage = defaultPackage,
                        actionType = "CLICK_NODE",
                        rawScreenContext = obs.visibleText,
                        payloadText = "",
                        skipBrainInference = true
                    )
                    when (launchOutcome) {
                        is AgentStepOutcome.Blocked -> {
                            onBlocked(launchOutcome)
                            return@withContext launchOutcome
                        }
                        is AgentStepOutcome.Denied -> return@withContext launchOutcome
                        is AgentStepOutcome.Executed -> {
                            if (!launchOutcome.summary.contains("(Opened ")) {
                                launchTargetApp(defaultPackage)
                            }
                            val msg = "Opened $defaultPackage ($guardInputNote)"
                            onStepProgress("Step 1 (Guard-2M): $msg")
                            return@withContext AgentStepOutcome.Executed(msg, lastBrainJson)
                        }
                        is AgentStepOutcome.NeedsTapApproval -> {
                            val approved = requestUserConfirm(launchOutcome)
                                ?: return@withContext AgentStepOutcome.Denied("Cancelled before opening $defaultPackage.")
                            val res = completeTapApproval(approved)
                            if (res.isFailure) {
                                return@withContext mapBrokerFailure(res.exceptionOrNull()!!, defaultPackage, "OPEN_APP")
                            }
                            launchTargetApp(defaultPackage)
                            val msg = "Opened $defaultPackage ($guardInputNote)"
                            onStepProgress("Step 1 (Guard-2M): $msg")
                            return@withContext AgentStepOutcome.Executed(msg, lastBrainJson)
                        }
                    }
                }
            }

            var currentSubgoal = subgoals.getOrNull(subgoalIdx)
            if (launchedTargetOnce && currentSubgoal?.targetHint.equals("stopwatch", ignoreCase = true) &&
                findGroundedNodeHint(obs, "stopwatch", "CLICK_NODE") == null &&
                (findGroundedNodeHint(obs, "start", "CLICK_NODE") != null ||
                    findGroundedNodeHint(obs, "pause", "CLICK_NODE") != null ||
                    findGroundedNodeHint(obs, "lap", "CLICK_NODE") != null)
            ) {
                subgoalIdx++
                currentSubgoal = subgoals.getOrNull(subgoalIdx)
            }
            if (launchedTargetOnce && currentSubgoal?.targetHint.equals("start", ignoreCase = true) &&
                findGroundedNodeHint(obs, "start", "CLICK_NODE") == null &&
                findGroundedNodeHint(obs, "pause", "CLICK_NODE") != null &&
                findGroundedNodeHint(obs, "lap", "CLICK_NODE") != null
            ) {
                subgoalIdx++
                currentSubgoal = subgoals.getOrNull(subgoalIdx)
            }
            val groundedMatch = if (launchedTargetOnce && currentSubgoal != null) {
                if (currentSubgoal.action in setOf("SCROLL_DOWN", "SCROLL_UP", "NAVIGATE_BACK", "NAVIGATE_HOME", "READ_DOM", "PRESS_ENTER", "EXPLORE_LISTING", "SYNTHESIZE_BEST")) {
                    currentSubgoal.action
                } else {
                    findGroundedNodeHint(obs, currentSubgoal.targetHint, currentSubgoal.action)
                }
            } else {
                null
            }
            val isUnexpectedOverlay = launchedTargetOnce &&
                (obs.packageName in setOf("com.google.android.youtube", "com.android.vending") ||
                    (obs.packageName == "com.google.android.settings.intelligence" &&
                        !currentSubgoal?.targetHint.equals("search", ignoreCase = true))) &&
                !defaultPackage.equals(obs.packageName, ignoreCase = true)
            val dialogDismiss = if (launchedTargetOnce && groundedMatch == null && !isUnexpectedOverlay) {
                findDialogDismissHint(obs)?.takeIf { it != lastDialogDismiss }
            } else {
                null
            }
            if (dialogDismiss != null) lastDialogDismiss = dialogDismiss

            // Run live VLM inference (with screenshot once inside target app, or when screen changes & grounded node isn't found)
            val currentScreenSig = "${obs.packageName}|${obs.nodes.size}|${obs.nodes.take(4).joinToString(",") { it.textLabel.take(16) }}"
            val shouldCallBrain = !launchedTargetOnce || !ranVlmVisionOnce ||
                (!isUnexpectedOverlay && groundedMatch == null && dialogDismiss == null && currentScreenSig != lastVlmScreenSig)
            val plan = if (shouldCallBrain) {
                val p = planWithIsolatedBrain(
                    userCommand = if (currentSubgoal != null) "$userCommand [Next: ${currentSubgoal.action} ${currentSubgoal.targetHint}]" else userCommand,
                    screenSummary = obs.summaryForBrain,
                    activePackage = obs.packageName,
                    stepIndex = stepIdx,
                    totalSteps = totalSteps,
                    historyNote = historyNote,
                    fallbackPackage = defaultPackage,
                    fallbackAction = currentSubgoal?.action ?: defaultAction,
                    fallbackPayload = currentSubgoal?.payload?.ifBlank { defaultPayload } ?: defaultPayload
                )
                if (launchedTargetOnce) {
                    lastVlmScreenSig = currentScreenSig
                    if (p.rawBrainJson.contains("\"vlm_vision\":true")) {
                        ranVlmVisionOnce = true
                    }
                }
                p
            } else {
                PlannedStep(
                    action = when {
                        isUnexpectedOverlay -> "NAVIGATE_BACK"
                        dialogDismiss != null -> "CLICK_NODE"
                        else -> currentSubgoal?.action ?: defaultAction
                    },
                    targetPackage = defaultPackage,
                    targetHint = when {
                        isUnexpectedOverlay -> ""
                        dialogDismiss != null -> dialogDismiss
                        else -> groundedMatch ?: (currentSubgoal?.targetHint ?: "")
                    },
                    payloadText = if (isUnexpectedOverlay || dialogDismiss != null) "" else (currentSubgoal?.payload?.ifBlank { defaultPayload } ?: defaultPayload),
                    engineName = "vlm+dom-grounded",
                    rawBrainJson = lastBrainJson
                )
            }
            currentCoroutineContext().ensureActive()
            lastBrainJson = plan.rawBrainJson

            if (plan.action.equals("DONE", ignoreCase = true) && subgoalIdx >= subgoals.size) {
                return@withContext AgentStepOutcome.Executed(lastSummary, lastBrainJson)
            }

            // Launch target app once on first step if Aegis (or another unrelated app) is foreground
            val resolvedPkg = plan.targetPackage.ifBlank { defaultPackage }
            val initialAmazonUrl = if (resolvedPkg == "com.android.chrome" && userCommand.lowercase().contains("amazon")) {
                if (amazonQuery.isNotBlank()) {
                    "https://www.amazon.com/s?k=${Uri.encode(amazonQuery)}"
                } else {
                    "https://www.amazon.com"
                }
            } else {
                null
            }
            if (!launchedTargetOnce && obs.packageName != resolvedPkg && !resolvedPkg.equals("com.aegis.core", ignoreCase = true)) {
                val openOutcome = evaluateStep(
                    userCommand = userCommand,
                    targetPackage = resolvedPkg,
                    actionType = "CLICK_NODE",
                    rawScreenContext = obs.visibleText,
                    payloadText = plan.targetHint,
                    skipBrainInference = true
                )
                when (openOutcome) {
                    is AgentStepOutcome.Blocked -> {
                        AegisLiveHudState.update(HudGlowMode.BLOCKED, 1, totalSteps, "Blocked by Policy", openOutcome.matchedSnippet)
                        delay(1100L)
                        onBlocked(openOutcome)
                        return@withContext openOutcome
                    }
                    is AgentStepOutcome.Denied -> return@withContext openOutcome
                    is AgentStepOutcome.NeedsTapApproval -> {
                        val approved = requestUserConfirm(openOutcome)
                            ?: return@withContext AgentStepOutcome.Denied("Cancelled before opening $resolvedPkg.")
                        val res = completeTapApproval(approved)
                        if (res.isFailure) {
                            val mapped = mapBrokerFailure(res.exceptionOrNull()!!, resolvedPkg, "OPEN_APP")
                            if (mapped is AgentStepOutcome.Blocked) onBlocked(mapped)
                            return@withContext mapped
                        }
                        launchTargetApp(resolvedPkg, initialAmazonUrl)
                    }
                    is AgentStepOutcome.Executed -> {
                        if (initialAmazonUrl != null || !openOutcome.summary.contains("(Opened ")) {
                            launchTargetApp(resolvedPkg, initialAmazonUrl)
                        }
                    }
                }
                launchedTargetOnce = true
                if (initialAmazonUrl != null && amazonQuery.isNotBlank()) {
                    while (subgoalIdx < subgoals.size &&
                        (subgoals[subgoalIdx].targetHint == "search" || subgoals[subgoalIdx].action == "TYPE_AND_ENTER")
                    ) {
                        subgoalIdx++
                    }
                }
                executedSteps++
                val shortPkg = resolvedPkg.substringAfterLast('.')
                AegisLiveHudState.update(
                    mode = HudGlowMode.STEP_ACTIVE,
                    currentStep = executedSteps,
                    totalSteps = totalSteps,
                    statusTitle = "Lumen · Step $executedSteps/$totalSteps",
                    actionDetail = if (initialAmazonUrl != null) "Loading Amazon in Chrome..." else "Opened $shortPkg",
                    completedStepTag = if (initialAmazonUrl != null) "Amazon ✓" else "Open $shortPkg ✓"
                )
                lastSummary = "Opened $resolvedPkg ($guardInputNote)"
                val openStepMsg = "Step $stepIdx (${plan.engineName}): Opened $resolvedPkg"
                android.util.Log.i("AegisStep", openStepMsg)
                onStepProgress(openStepMsg)
                if (isSimpleLaunchCommand(userCommand)) {
                    android.util.Log.i("AegisStep", "DONE: $lastSummary")
                    return@withContext AgentStepOutcome.Executed(lastSummary, lastBrainJson)
                }
                historyNote += " -> Opened $resolvedPkg"
                val maxTicks = if (initialAmazonUrl != null) 22 else 18
                for (waitTick in 0 until maxTicks) {
                    delay(200L)
                    val curObs = svc.captureScreenObservation(maxNodes = 20, maxChars = 400)
                    if (curObs.packageName == resolvedPkg && curObs.nodes.size >= if (initialAmazonUrl != null) 8 else 2) break
                }
                delay(if (initialAmazonUrl != null) 900L else 250L)
                continue
            }

            // Decide grounded action + target for Step 2..maxSteps
            var effSubgoal = subgoals.getOrNull(subgoalIdx)
            if (effSubgoal?.action == "EXPLORE_LISTING") {
                val cand = pickNextListingCandidate(obs, visitedListings, context.resources.displayMetrics.heightPixels, amazonQuery)
                if (cand == null && exploreScrolls >= 4 && collectedListings.isNotEmpty()) {
                    while (subgoalIdx < subgoals.size && subgoals[subgoalIdx].action != "SYNTHESIZE_BEST") {
                        subgoalIdx++
                    }
                    effSubgoal = subgoals.getOrNull(subgoalIdx)
                }
            }
            if (effSubgoal?.action == "SYNTHESIZE_BEST") {
                val bestReport = selectBestListing(collectedListings, obs.visibleText)
                executedSteps++
                AegisLiveHudState.update(
                    mode = HudGlowMode.STEP_ACTIVE,
                    currentStep = executedSteps,
                    totalSteps = totalSteps,
                    statusTitle = "Lumen · Compared ${collectedListings.size} listings",
                    actionDetail = bestReport.take(72),
                    completedStepTag = "Best Pick ✓"
                )
                android.util.Log.i("AegisStep", "DONE: $bestReport")
                onStepProgress("Done ($executedSteps steps): $bestReport")
                return@withContext AgentStepOutcome.Executed(bestReport, lastBrainJson)
            }

            var dynamicExploreFallbackScroll = false
            val effSubgoalForPick = effSubgoal
            val dynamicExploreLabel = if (effSubgoalForPick?.action == "EXPLORE_LISTING" && !isUnexpectedOverlay && dialogDismiss == null) {
                val picked = pickNextListingCandidate(obs, visitedListings, context.resources.displayMetrics.heightPixels, amazonQuery)
                if (picked != null) {
                    exploreScrolls = 0
                    visitedListings.add(normalizeUiLabel(picked).take(32))
                    lastClickedListing = picked
                    picked
                } else {
                    dynamicExploreFallbackScroll = true
                    exploreScrolls++
                    ""
                }
            } else {
                null
            }

            val effHint = when {
                isUnexpectedOverlay -> ""
                dialogDismiss != null -> dialogDismiss
                dynamicExploreLabel != null -> dynamicExploreLabel
                effSubgoal != null -> findGroundedNodeHint(obs, effSubgoal.targetHint, effSubgoal.action) ?: effSubgoal.targetHint
                plan.targetHint.isNotBlank() -> plan.targetHint
                else -> extractActionHint(userCommand, plan.payloadText)
            }
            val effAction = when {
                isUnexpectedOverlay -> "NAVIGATE_BACK"
                dialogDismiss != null -> "CLICK_NODE"
                dynamicExploreFallbackScroll -> "SCROLL_DOWN"
                dynamicExploreLabel != null -> "CLICK_NODE"
                effSubgoal != null -> effSubgoal.action
                plan.tapX in 1..999 && plan.tapY in 1..999 && effHint.isBlank() -> "TAP_COORDS"
                else -> plan.action
            }
            val effPayload = when {
                isUnexpectedOverlay || dialogDismiss != null || dynamicExploreFallbackScroll -> ""
                effAction == "TAP_COORDS" -> "${plan.tapX},${plan.tapY}"
                effSubgoal != null && effSubgoal.payload.isNotBlank() -> effSubgoal.payload
                else -> plan.payloadText.ifBlank { defaultPayload }
            }

            val actionKey = "$effAction:$resolvedPkg:$effHint:$effPayload:$subgoalIdx:$exploreScrolls"
            if (stepIdx >= 2 && actionKey == lastActionKey) {
                if (stallRecoveries < 2) {
                    stallRecoveries++
                    delay(650L)
                    if (stallRecoveries == 2 && effAction != "SCROLL_DOWN") {
                        evaluateStep(userCommand, resolvedPkg, "SCROLL_DOWN", obs.visibleText, "", skipBrainInference = true)
                        delay(450L)
                    }
                    lastActionKey = "RECOVER:$stallRecoveries:$subgoalIdx"
                    continue
                }
                if (effSubgoal != null && effAction != "SCROLL_DOWN") {
                    lastActionKey = "SCROLL_RETRY:$subgoalIdx"
                    evaluateStep(userCommand, resolvedPkg, "SCROLL_DOWN", obs.visibleText, "", skipBrainInference = true)
                    delay(450L)
                    continue
                }
                val stalledMsg = if (collectedListings.isNotEmpty()) {
                    selectBestListing(collectedListings, obs.visibleText)
                } else {
                    "Completed $executedSteps steps: $lastSummary"
                }
                android.util.Log.i("AegisStep", "DONE: $stalledMsg")
                return@withContext AgentStepOutcome.Executed(stalledMsg, lastBrainJson)
            }
            lastActionKey = actionKey

            val beforeSig = "${obs.packageName}|${obs.nodes.size}|${obs.visibleText.take(120)}"
            val stepOutcome = evaluateStep(
                userCommand = userCommand,
                targetPackage = if (isUnexpectedOverlay) resolvedPkg else obs.packageName.ifBlank { resolvedPkg },
                actionType = effAction,
                rawScreenContext = obs.visibleText,
                payloadText = effPayload,
                targetHint = effHint,
                skipBrainInference = true
            )

            when (stepOutcome) {
                is AgentStepOutcome.Blocked -> {
                    android.util.Log.w("AegisStep", "BLOCKED: ${stepOutcome.attemptSummary} (${stepOutcome.matchedSnippet})")
                    AegisLiveHudState.update(HudGlowMode.BLOCKED, executedSteps + 1, totalSteps, "Blocked by Guard-2M / Policy", stepOutcome.matchedSnippet)
                    delay(1100L)
                    onBlocked(stepOutcome)
                    return@withContext stepOutcome
                }
                is AgentStepOutcome.Denied -> {
                    android.util.Log.w("AegisStep", "DENIED: ${stepOutcome.reason}")
                    return@withContext stepOutcome
                }
                is AgentStepOutcome.Executed -> {
                    val isHashMismatch = stepOutcome.summary.contains("NodeHashMismatch", ignoreCase = true)
                    if (isHashMismatch && hashRetryCount < 2) {
                        val canScrollRetry = hashRetryCount == 1 && effAction == "CLICK_NODE" &&
                            svc.resolveLiveTargetSnapshot(effAction, effHint) == null
                        if (canScrollRetry) {
                            evaluateStep(userCommand, resolvedPkg, "SCROLL_DOWN", obs.visibleText, "", skipBrainInference = true)
                        }
                        delay(350L)
                        hashRetryCount++
                        lastActionKey = ""
                        continue
                    }
                    val exhaustedHashRetry = isHashMismatch && hashRetryCount >= 2
                    hashRetryCount = 0
                    stallRecoveries = 0
                    executedSteps++
                    val engineTag = if (plan.engineName.contains("vlm") || ranVlmVisionOnce) "SmolVLM-500M+Vision" else plan.engineName
                    lastSummary = stepOutcome.summary
                    val stepLog = "Step $stepIdx ($engineTag): $effAction($effHint) -> ${stepOutcome.summary}"
                    android.util.Log.i("AegisStep", stepLog)
                    if (effAction == "READ_DOM" && effHint == "listing_detail") {
                        val fullDom = svc.captureVisibleDomText(2200).second
                        val report = extractListingReport(lastClickedListing, fullDom)
                        collectedListings.add(report)
                        android.util.Log.i("AegisStep", "LISTING #${collectedListings.size}: ${report.summary}")
                    }
                    val shortActionDesc = when (effAction.uppercase()) {
                        "SCROLL_DOWN" -> "Scrolling down"
                        "SCROLL_UP" -> "Scrolling up"
                        "NAVIGATE_BACK" -> "Returning to listings"
                        "READ_DOM" -> if (effHint == "listing_detail") {
                            collectedListings.lastOrNull()?.summary ?: "Reading listing details"
                        } else {
                            "Verifying state"
                        }
                        "TAP_COORDS" -> "Tap ($effPayload)"
                        else -> "$effAction ${effHint.take(24)}".trim()
                    }
                    val shortTag = when (effAction.uppercase()) {
                        "SCROLL_DOWN" -> "Scroll ✓"
                        "NAVIGATE_BACK" -> "Back ✓"
                        "READ_DOM" -> if (effHint == "listing_detail") "Item #${collectedListings.size} ✓" else "Verify ✓"
                        else -> "${effHint.take(12).ifBlank { effAction.take(8) }} ✓"
                    }
                    AegisLiveHudState.update(
                        mode = HudGlowMode.STEP_ACTIVE,
                        currentStep = executedSteps,
                        totalSteps = totalSteps,
                        statusTitle = if (ranVlmVisionOnce) "SmolVLM-500M · Step $executedSteps/$totalSteps" else "Lumen · Step $executedSteps/$totalSteps",
                        actionDetail = shortActionDesc,
                        completedStepTag = shortTag
                    )
                    onStepProgress(stepLog)
                    historyNote += " -> $effAction($effHint)"
                    delay(
                        when {
                            effHint.contains("create", ignoreCase = true) -> 950L
                            effSubgoal?.action == "EXPLORE_LISTING" && !dynamicExploreFallbackScroll -> 950L
                            effAction == "READ_DOM" -> 60L
                            effAction in setOf("SCROLL_DOWN", "SCROLL_UP") -> 350L
                            effAction == "NAVIGATE_BACK" -> 650L
                            effAction in setOf("CLICK_NODE", "TYPE_AND_ENTER") -> 550L
                            else -> 300L
                        }
                    )
                    if (effAction == "NAVIGATE_BACK" && svc.captureVisibleDomText(16).first != resolvedPkg) {
                        launchTargetApp(resolvedPkg, initialAmazonUrl)
                        delay(1100L)
                    }

                    val afterObs = svc.captureScreenObservation(maxNodes = 24, maxChars = 700)
                    val actionWorked = (!stepOutcome.summary.contains("returned false", ignoreCase = true) &&
                        !isHashMismatch) || exhaustedHashRetry
                    if (!isUnexpectedOverlay && dialogDismiss == null && !dynamicExploreFallbackScroll && effSubgoal != null && actionWorked) {
                        subgoalIdx++
                    }
                    if (subgoals.isNotEmpty() && subgoalIdx >= subgoals.size) {
                        val finalSummary = "Verified $executedSteps steps complete ($lastSummary)"
                        android.util.Log.i("AegisStep", "DONE: $finalSummary")
                        onStepProgress("Done ($executedSteps steps): $finalSummary")
                        return@withContext AgentStepOutcome.Executed(finalSummary, lastBrainJson)
                    }
                    if (subgoals.isEmpty() && stepOutcome.summary.contains("(Sandbox mode)", ignoreCase = true)) {
                        return@withContext AgentStepOutcome.Executed(lastSummary, lastBrainJson)
                    }
                }
                is AgentStepOutcome.NeedsTapApproval -> {
                    val approved = requestUserConfirm(stepOutcome)
                        ?: return@withContext AgentStepOutcome.Denied("Cancelled by user.")
                    val execResult = completeTapApproval(approved)
                    if (execResult.isFailure) {
                        val mapped = mapBrokerFailure(
                            execResult.exceptionOrNull()!!,
                            approved.targetPackage,
                            approved.actionType
                        )
                        if (mapped is AgentStepOutcome.Blocked) onBlocked(mapped)
                        return@withContext mapped
                    }
                    executedSteps++
                    lastSummary = execResult.getOrDefault("Confirmed")
                    onStepProgress("Step $stepIdx (${plan.engineName}): $lastSummary")
                    if (approved.actionType.uppercase() in setOf("SEND_MESSAGE", "SEND_EMAIL") && subgoals.isEmpty()) {
                        return@withContext AgentStepOutcome.Executed(lastSummary, lastBrainJson)
                    }
                    if (dialogDismiss == null && effSubgoal != null) {
                        subgoalIdx++
                    }
                    historyNote += " -> ${approved.actionType}"
                    delay(850L)
                    if (subgoals.isNotEmpty() && subgoalIdx >= subgoals.size) {
                        val doneMsg = "Verified $executedSteps steps: $lastSummary"
                        android.util.Log.i("AegisStep", "DONE: $doneMsg")
                        return@withContext AgentStepOutcome.Executed(doneMsg, lastBrainJson)
                    }
                }
            }
            }

            val capMsg = "Completed $executedSteps steps: $lastSummary"
            android.util.Log.i("AegisStep", "DONE: $capMsg")
            AgentStepOutcome.Executed(capMsg, lastBrainJson)
        } finally {
            AegisLiveHudState.clearToIdle()
        }
    }

    private suspend fun planWithIsolatedBrain(
        userCommand: String,
        screenSummary: String,
        activePackage: String,
        stepIndex: Int,
        totalSteps: Int,
        historyNote: String,
        fallbackPackage: String,
        fallbackAction: String,
        fallbackPayload: String
    ): PlannedStep {
        val broker = brokerProvider()
        val sanitized = broker.sanitizeScreenContext(userCommand, screenSummary)
        val reqId = UUID.randomUUID().toString()
        val knownPackageResolved = fallbackPackage.isNotBlank() && !fallbackPackage.equals("com.aegis.core", ignoreCase = true)
        val appsHint = if (stepIndex == 1 && !knownPackageResolved) buildInstalledAppsHint() else ""
        val lowCmd = userCommand.lowercase()
        val explicitScreenQuery = lowCmd.contains("screen") || lowCmd.contains("look") || lowCmd.contains("see")
        val needVision = explicitScreenQuery || (activePackage.isNotBlank() && !activePackage.equals("com.aegis.core", ignoreCase = true))

        val prompt = buildString {
            append("Goal: ").append(userCommand)
            append("\nStep: ").append(stepIndex)
            if (appsHint.isNotBlank()) append("\nApps: ").append(appsHint)
            if (stepIndex > 1) append("\nHistory:").append(historyNote.takeLast(120))
            // Keep prompts compact so llama.cpp / SmolVLM prefill stays fast & low-memory
            val ctxLimit = when {
                needVision -> 200
                stepIndex == 1 && knownPackageResolved -> 96
                else -> 400
            }
            append('\n').append(sanitized.wrappedText.take(ctxLimit))
        }
        val vlmFrame = if (needVision) {
            ScreenVisionCapture.captureProjectionAshmem()
                ?: AegisAccessibilityService.instance?.captureScreenRgbFrame768()
        } else {
            null
        }
        val frameBytes = if (vlmFrame != null) 768L * 768L * 3L else 0L
        if (vlmFrame != null) {
            AegisLiveHudState.update(
                mode = HudGlowMode.VLM_GLOW,
                currentStep = stepIndex,
                totalSteps = totalSteps,
                statusTitle = "SmolVLM-500M · Vision Glow",
                actionDetail = "Analyzing live screen pixels (Step $stepIndex)..."
            )
        } else {
            AegisLiveHudState.update(
                mode = HudGlowMode.STEP_ACTIVE,
                currentStep = stepIndex,
                totalSteps = totalSteps,
                statusTitle = "Lumen · Planning",
                actionDetail = "Resolving Step $stepIndex target..."
            )
        }
        val rawJson = try {
            runCatching {
                brainProvider()?.runInference(reqId, prompt, vlmFrame, frameBytes)
            }.getOrNull() ?: "{}"
        } finally {
            runCatching { vlmFrame?.close() }
            if (vlmFrame != null) {
                AegisLiveHudState.update(
                    mode = HudGlowMode.STEP_ACTIVE,
                    currentStep = stepIndex,
                    totalSteps = totalSteps,
                    statusTitle = "SmolVLM-500M · Grounded",
                    actionDetail = "Dispatching verified action..."
                )
            }
        }
        currentCoroutineContext().ensureActive()

        return runCatching {
            android.util.Log.i("AegisTTFT", rawJson)
            val obj = JSONObject(rawJson)
            val engine = obj.optString("engine", "fallback")
            val rawAct = obj.optString("proposed_action", fallbackAction).ifBlank { fallbackAction }
            val allowedActions = setOf(
                "CLICK_NODE", "CLICK_SEND", "TYPE_DRAFT", "TYPE_TEXT", "TYPE_AND_ENTER", "PRESS_ENTER",
                "SEND_MESSAGE", "SEND_EMAIL", "SCROLL_DOWN", "READ_DOM", "NAVIGATE_BACK",
                "NAVIGATE_HOME", "TAP_COORDS", "DONE",
                "CHANGE_SETTING", "UNINSTALL_APP", "INSTALL_APK", "DELETE_FILE", "INITIATE_PAYMENT", "EXPORT_CREDENTIALS"
            )
            val action = when {
                rawAct.equals("OPEN_APP", ignoreCase = true) -> "CLICK_NODE"
                rawAct.uppercase() in allowedActions -> rawAct.uppercase()
                else -> fallbackAction
            }
            val rawPkg = obj.optString("package", "").ifBlank {
                obj.optString("suggested_package", fallbackPackage)
            }
            val explicitNonChromeFallback = fallbackPackage.isNotBlank() &&
                !fallbackPackage.equals("com.aegis.core", ignoreCase = true) &&
                !fallbackPackage.equals("com.android.chrome", ignoreCase = true)
            val pkg = if (rawPkg == "com.aegis.core" || rawPkg.isBlank() ||
                (stepIndex == 1 && explicitNonChromeFallback && rawPkg == "com.android.chrome") ||
                context.packageManager.getLaunchIntentForPackage(rawPkg) == null
            ) {
                fallbackPackage
            } else {
                rawPkg
            }
            val target = obj.optString("target", "")
            val text = obj.optString("payload_text", "").ifBlank { fallbackPayload }
            val tapX = obj.optInt("tap_x", -1)
            val tapY = obj.optInt("tap_y", -1)
            PlannedStep(action, pkg, target, text, engine, rawJson, tapX, tapY)
        }.getOrElse {
            PlannedStep(fallbackAction, fallbackPackage, "", fallbackPayload, "fallback", rawJson)
        }
    }

    private fun extractSearchQuery(userCommand: String): String {
        val raw = userCommand.trim()
        val m = Regex(
            """(?:search(?:\s+for)?|find|look\s+up|check(?:\s+multiple)?(?:\s+amazon)?\s+(?:listings?|products?|items?|videos?|apps?|places?)\s+(?:for|of|about)?|for)\s+([^,]+?)(?:\s*(?:,| and (?:check|compare|find|pick|select|get|scroll)| then | on amazon| in amazon| in the app|$))""",
            RegexOption.IGNORE_CASE
        ).find(raw)
        val cand = m?.groupValues?.getOrNull(1)?.trim().orEmpty()
            .replace(Regex("""\b(?:on|in)\s+amazon\b""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cand.isNotBlank() && !cand.equals("the best one", true) && !cand.equals("best one", true)) {
            return cand
        }
        return ""
    }

    private fun extractListingReport(titleHint: String, visibleText: String): ListingReport {
        val price = Regex("""(?:\$|₹|Rs\.?\s?)\s?\d[\d,]*(?:\.\d{1,2})?""")
            .find(visibleText)?.value?.replace("  ", " ")?.trim() ?: "price n/a"
        val ratingMatch = Regex("""([1-5]\.\d)\s*(?:out of 5|stars?|★)""", RegexOption.IGNORE_CASE).find(visibleText)
            ?: Regex("""([1-5])\s*(?:out of 5|stars?|★)""", RegexOption.IGNORE_CASE).find(visibleText)
        val rating = ratingMatch?.groupValues?.getOrNull(1) ?: "--"
        val reviewsMatch = Regex("""\(?([\d,]+(?:\.\d+)?[KkMm]?)\s*(?:global\s+)?(?:ratings?|reviews?)\)?""", RegexOption.IGNORE_CASE)
            .find(visibleText)
        val reviews = reviewsMatch?.groupValues?.getOrNull(1) ?: "0"

        val lines = visibleText.lines().map { it.trim() }.filter {
            it.length >= 16 &&
                !it.startsWith("Deliver to", true) &&
                !it.startsWith("Skip to", true) &&
                !it.startsWith("Search", true) &&
                !it.startsWith("Sign in", true)
        }
        val title = titleHint.takeIf { it.length >= 14 }?.take(68)
            ?: lines.maxByOrNull { it.length }?.take(68)
            ?: "Checked Listing"

        val ratingVal = rating.toDoubleOrNull() ?: 3.5
        val revClean = reviews.lowercase().replace(",", "")
        val revNum = when {
            revClean.endsWith("k") -> (revClean.dropLast(1).toDoubleOrNull() ?: 0.0) * 1000.0
            revClean.endsWith("m") -> (revClean.dropLast(1).toDoubleOrNull() ?: 0.0) * 1_000_000.0
            else -> revClean.toDoubleOrNull() ?: 0.0
        }
        val priceVal = Regex("""[\d,]+(?:\.\d+)?""").find(price)?.value?.replace(",", "")?.toDoubleOrNull() ?: 0.0
        val score = ratingVal * 25.0 + kotlin.math.ln(revNum + 1.0) * 3.5 - (if (priceVal > 0) kotlin.math.min(priceVal / 100.0, 8.0) else 0.0)
        val summary = "${title.take(36)} ($price · ${rating}★ · $reviews rev)"
        return ListingReport(title, price, rating, reviews, score, summary)
    }

    private fun pickNextListingCandidate(
        obs: ScreenObservation,
        visited: Set<String>,
        screenHeightPx: Int,
        searchQuery: String = ""
    ): String? {
        val chromeIgnore = setOf(
            "search", "menu", "cart", "sign in", "your account", "back", "navigate up", "home",
            "all filters", "all filters icon", "filters", "sort", "submit", "go", "clear",
            "clear search keywords", "dismiss", "close", "accept", "reject", "allow", "share",
            "save", "copy", "edit", "skip", "open", "open all categories menu", "amazon",
            "4 stars & up", "most purchased", "discover", "connectivity", "wireless technology",
            "noise control", "brands", "condition", "top brands", "earpiece shape",
            "compatible devices", "battery life", "headphones form factor", "color",
            "water resistance level", "black friday deals", "shop in our free app today",
            "deliver to", "learn more", "sponsored", "next", "previous", "see more",
            "get it by", "free shipping", "prime", "results", "price, product page"
        )
        val normQuery = normalizeUiLabel(searchQuery)
        val minY = (screenHeightPx * 0.11f).toInt()
        val maxY = (screenHeightPx * 0.92f).toInt()
        val candidates = obs.nodes.filter { node ->
            val raw = node.textLabel.trim()
            val norm = normalizeUiLabel(raw)
            if (norm.length < 18 || node.isEditable) return@filter false
            if (norm.startsWith("ref=") || norm.startsWith("http") || norm.contains("dib=") ||
                norm.startsWith("skip to") || norm.startsWith("refine:") ||
                norm.contains("sponsored") || norm.contains("advertiser") ||
                norm.contains("install") || norm.contains("download app")
            ) {
                return@filter false
            }
            if (normQuery.isNotBlank() && (norm == normQuery || norm.contains("amazon.com : $normQuery"))) return@filter false
            if (chromeIgnore.any { norm == it || norm.startsWith("$it ") } ||
                norm.contains("filter") || norm.contains("deliver to") ||
                norm.startsWith("apply ") || norm.contains("transition alert") ||
                norm.contains("search keywords") || norm.contains("free app today")
            ) {
                return@filter false
            }
            val key = norm.take(32)
            if (key in visited) return@filter false
            val hasBounds = (node.bounds.right > 0 || node.bounds.bottom > 0)
            if (!hasBounds || node.bounds.top < minY || node.bounds.bottom > maxY) return@filter false
            true
        }
        return candidates.sortedByDescending { node ->
            val low = node.textLabel.lowercase()
            var s = 0
            if (low.contains("out of 5 stars") && low.length < 40) s -= 25
            if (low.contains("$") || low.contains("₹") || low.contains("rs")) s += 30
            if (low.contains("star") || low.contains("★") || low.contains("rating") || low.contains("views")) s += 15
            if (node.textLabel.length >= 28) s += 20
            s + (node.textLabel.length.coerceAtMost(80) / 5)
        }.firstOrNull()?.textLabel
    }

    private fun selectBestListing(items: List<ListingReport>, fallbackText: String): String {
        if (items.isEmpty()) {
            return "Checked screen (${fallbackText.replace('\n', ' ').take(140)})"
        }
        val best = items.maxByOrNull { it.score }!!
        val others = items.joinToString(" | ") { it.summary }
        return "Best Pick: ${best.title} (${best.price}, ${best.rating}★, ${best.reviews} reviews) · Checked ${items.size}: $others"
    }

    private fun decomposeGoalIntoSubgoals(userCommand: String): List<SubgoalStep> {
        val lowCmd = userCommand.lowercase()
        val hasCompareIntent = lowCmd.contains("multiple") ||
            lowCmd.contains("compare") ||
            lowCmd.contains("best one") ||
            (lowCmd.contains("listing") && lowCmd.contains("best")) ||
            Regex("""check\s+(?:\d+|two|three|several)\s+(?:listing|product|item|video|app|place|result)""").containsMatchIn(lowCmd)

        val rawClauses = userCommand.split(Regex("""\s*(?:,| then | and )\s*""", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotBlank() }
        val steps = mutableListOf<SubgoalStep>()
        for ((idx, clause) in rawClauses.withIndex()) {
            val low = clause.lowercase()
            // Skip pure app-opening first clause since Step 1 handles app launch
            if (idx == 0 && (low.startsWith("open ") || low.startsWith("launch "))) {
                continue
            }
            if (low.contains("start") || low.contains("lap") || low.contains("reset") ||
                low.contains("pause") || low == "stop" || low.contains("stop ")
            ) {
                if (low.contains("start")) steps.add(SubgoalStep("CLICK_NODE", "start"))
                if (low.contains("lap")) steps.add(SubgoalStep("CLICK_NODE", "lap"))
                if (low.contains("pause") || low == "stop" || low.contains("stop ")) {
                    steps.add(SubgoalStep("CLICK_NODE", "pause"))
                }
                if (low.contains("reset")) steps.add(SubgoalStep("CLICK_NODE", "reset"))
                continue
            }
            if (low.contains("create contact") || low.contains("new contact") || low.contains("add contact")) {
                steps.add(SubgoalStep("CLICK_NODE", "create"))
            }
            if (low.contains("named ") || low.contains("name ")) {
                val after = clause.substringAfter("named ", clause.substringAfter("name ", "")).trim()
                val namePart = after.substringBefore(" with ").trim()
                val parts = namePart.split(" ").filter { it.isNotBlank() }
                if (parts.isNotEmpty()) steps.add(SubgoalStep("TYPE_DRAFT", "first name", parts[0]))
                if (parts.size >= 2) steps.add(SubgoalStep("TYPE_DRAFT", "last name", parts[1]))
                val phoneMatch = Regex("""(?:phone|number)\s+([+\d-]+)""", RegexOption.IGNORE_CASE).find(clause)
                if (phoneMatch != null) {
                    steps.add(SubgoalStep("TYPE_DRAFT", "phone", phoneMatch.groupValues[1]))
                }
                continue
            }
            if (low.contains("create contact") || low.contains("new contact") || low.contains("add contact")) {
                continue
            }
            if (hasCompareIntent && (low.contains("multiple") || low.contains("listing") || low.contains("best") || low.contains("compare"))) {
                continue
            }
            when {
                low.contains("stopwatch") -> steps.add(SubgoalStep("CLICK_NODE", "stopwatch"))
                low.contains("timer") && (low.contains("switch") || low.contains("open") || low.contains("tab")) ->
                    steps.add(SubgoalStep("CLICK_NODE", "timer"))
                low.contains("alarm") && (low.contains("switch") || low.contains("open") || low.contains("tab")) ->
                    steps.add(SubgoalStep("CLICK_NODE", "alarm"))
                low.contains("phone ") -> {
                    val num = clause.substringAfter("phone ", "").trim().split(" ").firstOrNull().orEmpty()
                    if (num.isNotBlank()) steps.add(SubgoalStep("TYPE_DRAFT", "phone", num))
                }
                low.contains("save") -> steps.add(SubgoalStep("CLICK_NODE", "save"))
                low.contains("search for ") || low.startsWith("search ") -> {
                    val query = clause.substringAfter("search for ", clause.substringAfter("search ", ""))
                        .replace(Regex("""\b(?:on|in)\s+amazon\b""", RegexOption.IGNORE_CASE), "")
                        .trim()
                    steps.add(SubgoalStep("CLICK_NODE", "search"))
                    if (query.isNotBlank()) steps.add(SubgoalStep("TYPE_AND_ENTER", "search", query))
                }
                low.contains("scroll") -> steps.add(SubgoalStep("SCROLL_DOWN", ""))
                low.contains("back") -> steps.add(SubgoalStep("NAVIGATE_BACK", ""))
                low.contains("network") -> steps.add(SubgoalStep("CLICK_NODE", "network"))
                low.contains("internet") -> steps.add(SubgoalStep("CLICK_NODE", "internet"))
                low.contains("wi-fi") || low.contains("wifi") -> steps.add(SubgoalStep("CLICK_NODE", "wi-fi"))
                low.contains("verify") || low.contains("check") || low.contains("read") ->
                    steps.add(SubgoalStep("READ_DOM", ""))
                else -> {
                    val clean = low.replace(Regex("""^(switch to|go to|open|tap|click)\s+"""), "").trim()
                    if (clean.isNotBlank()) steps.add(SubgoalStep("CLICK_NODE", clean.take(24)))
                }
            }
        }
        if (hasCompareIntent) {
            val count = when {
                lowCmd.contains("2 ") || lowCmd.contains("two") -> 2
                lowCmd.contains("4 ") || lowCmd.contains("four") -> 4
                else -> 3
            }
            val query = extractSearchQuery(userCommand)
            if (query.isNotBlank() && steps.none { it.action == "TYPE_AND_ENTER" }) {
                steps.add(SubgoalStep("CLICK_NODE", "search"))
                steps.add(SubgoalStep("TYPE_AND_ENTER", "search", query))
            }
            repeat(count) {
                steps.add(SubgoalStep("EXPLORE_LISTING", ""))
                steps.add(SubgoalStep("READ_DOM", "listing_detail"))
                steps.add(SubgoalStep("NAVIGATE_BACK", ""))
            }
            steps.add(SubgoalStep("SYNTHESIZE_BEST", ""))
        }
        return steps
    }

    private fun normalizeUiLabel(raw: String): String =
        raw.replace('\u2011', '-')
            .replace('\u2013', '-')
            .replace('\u00A0', ' ')
            .trim()
            .lowercase()

    private fun findGroundedNodeHint(obs: ScreenObservation, rawHint: String, action: String): String? {
        val hint = normalizeUiLabel(rawHint)
        if (hint.isBlank()) return null
        val wantEdit = action.uppercase() in setOf("TYPE_DRAFT", "TYPE_TEXT", "TYPE_AND_ENTER")
        val candidates = obs.nodes.filter { !wantEdit || it.isEditable }
        val pool = if (wantEdit) candidates else candidates.ifEmpty { obs.nodes }
        if (pool.isEmpty()) return null
        val hintsToTry = when (hint) {
            "last name" -> listOf("last name", "surname", "family name")
            "first name" -> listOf("first name", "given name")
            "phone" -> listOf("phone", "mobile", "telephone", "number")
            "create" -> listOf("create contact", "create", "new contact", "add contact")
            "pause" -> listOf("pause")
            "wi-fi", "wifi" -> listOf("wi-fi", "wifi", "internet", "network & internet")
            else -> listOf(hint)
        }
        for (h in hintsToTry) {
            pool.filter { normalizeUiLabel(it.textLabel) == h }
                .minByOrNull { it.textLabel.length }?.let { return it.textLabel }
            pool.filter {
                val lbl = normalizeUiLabel(it.textLabel)
                lbl.contains(h) || (lbl.length >= 3 && lbl.length <= 28 && h.contains(lbl))
            }.minByOrNull { it.textLabel.length }?.let { return it.textLabel }
        }
        val hintTokens = hint.split(" ").filter { it.length >= 3 }
        if (hintTokens.isNotEmpty()) {
            val tok = pool.filter { n ->
                val lbl = normalizeUiLabel(n.textLabel)
                hintTokens.any { lbl.contains(it) }
            }.minByOrNull { it.textLabel.length }
            if (tok != null) return tok.textLabel
        }
        if (action.equals("TYPE_AND_ENTER", ignoreCase = true)) {
            return pool.firstOrNull()?.textLabel
        }
        return null
    }

    private fun findDialogDismissHint(obs: ScreenObservation): String? {
        if (obs.visibleText.contains("choose where to save", ignoreCase = true)) {
            obs.nodes.filter {
                it.isClickable && (
                    it.textLabel.contains("device only", ignoreCase = true) ||
                        it.textLabel.contains("device & google", ignoreCase = true)
                    )
            }.minByOrNull { it.textLabel.length }?.let { return it.textLabel }
        }
        val exactModalLabels = listOf(
            "use without an account", "no thanks", "while using the app",
            "allow", "accept & continue", "accept all", "got it", "not now",
            "skip ad", "skip", "close sheet", "dismiss", "ok"
        )
        for (lbl in exactModalLabels) {
            val found = obs.nodes.filter {
                val n = normalizeUiLabel(it.textLabel)
                it.isClickable && n.length in 2..26 && !n.startsWith("skip to") &&
                    (n == lbl || (lbl.length >= 6 && n.contains(lbl)))
            }.minByOrNull { it.textLabel.length }
            if (found != null) return found.textLabel
        }
        return null
    }

    private fun isSimpleLaunchCommand(command: String): Boolean {
        val low = command.trim().lowercase()
        if (!(low.startsWith("open ") || low.startsWith("launch "))) return false
        val multiStepMarkers = listOf(
            ",", " and ", " then ", " switch ", " search ", " type ", " send ", " ask ",
            " create ", " add ", " start ", " lap", " pause", " reset", " turn ", " toggle ",
            " check ", " find ", " scroll ", " tap ", " click ", " go to "
        )
        return multiStepMarkers.none { low.contains(it) }
    }

    private fun buildInstalledAppsHint(): String {
        val known = listOf(
            "Messages" to "com.google.android.apps.messaging",
            "WhatsApp" to "com.whatsapp",
            "Clock" to "com.google.android.deskclock",
            "Contacts" to "com.google.android.contacts",
            "Calendar" to "com.google.android.calendar",
            "Chrome" to "com.android.chrome",
            "Settings" to "com.android.settings",
            "Gmail" to "com.google.android.gm"
        )
        return known.filter { (_, pkg) ->
            context.packageManager.getLaunchIntentForPackage(pkg) != null
        }.joinToString(",") { (name, pkg) -> "$name=$pkg" }
    }

    private fun extractActionHint(command: String, payload: String): String {
        val low = command.lowercase()
        return when {
            low.contains("search") -> "search"
            low.contains("note") || low.contains("keep") -> "new"
            low.contains("send") -> "send"
            else -> payload.take(24)
        }
    }

    fun evaluateStep(
        userCommand: String,
        targetPackage: String,
        actionType: String,
        rawScreenContext: String,
        payloadText: String,
        targetNodeHash: String = "node_primary_cta",
        windowId: String = "win_active",
        displayBoundsHash: String = "bounds_1080_2400",
        isFlagSecure: Boolean = false,
        targetHint: String = "",
        skipBrainInference: Boolean = false
    ): AgentStepOutcome {
        val broker = brokerProvider()
        val profile = profileProvider()

        if (!skipBrainInference) {
            val mappedBytes = runCatching {
                val json = brainProvider()?.getEngineTelemetry() ?: "{}"
                org.json.JSONObject(json).optLong("mapped_bytes", 0L)
            }.getOrDefault(0L)
            val estimatedPeakMb = HardwareProfiler.estimatePeakMb(mappedBytes, profile.maxContextTokens)
            val admission = HardwareProfiler.checkMemoryAdmission(context, profile, estimatedPeakMb)
            if (!admission.allowed) {
                runCatching { brainProvider()?.unloadAndTrim(true) }
                return AgentStepOutcome.Denied("Memory Admission denied: ${admission.reason}")
            }
        }

        val svc = AegisAccessibilityService.instance
        val actUpper = actionType.uppercase()
        val isDirectGesture = actUpper in setOf("SCROLL_DOWN", "SCROLL_UP", "NAVIGATE_BACK", "NAVIGATE_HOME", "TAP_COORDS")
        val effectiveScreenText = if (svc != null && rawScreenContext.isBlank() && !(skipBrainInference && isDirectGesture)) {
            svc.captureVisibleDomText(900).second
        } else {
            rawScreenContext.ifBlank { if (isDirectGesture) "vision-gesture" else "" }
        }

        val sanitized = broker.sanitizeScreenContext(userCommand, effectiveScreenText)
        val preRisk = broker.classifyAction(targetPackage, actionType, isFlagSecure)
        val reqId = UUID.randomUUID().toString()
        val brainOut = if (!skipBrainInference && !sanitized.threatDetected && preRisk != RiskLevel.LEVEL0_HARD_BLOCKED) {
            val promptForBrain = "$userCommand\n${sanitized.wrappedText.take(320)}"
            runCatching {
                brainProvider()?.runInference(reqId, promptForBrain, null, 0L)
            }.getOrNull() ?: """{"status":"LOCAL_BRAIN_UNBOUND"}"""
        } else {
            """{"status":"${if (skipBrainInference) "REUSED_PLAN" else "BLOCKED_FAST_PATH"}"}"""
        }

        val liveSnapshot = if (svc != null && !isDirectGesture) {
            val (livePkg, _) = svc.captureVisibleDomText(64)
            if (livePkg == targetPackage) {
                svc.resolveLiveTargetSnapshot(actionType, targetHint.ifBlank { payloadText })
            } else null
        } else {
            null
        }

        val action = ProposedAction(
            sessionId = reqId,
            userCommand = userCommand,
            targetPackage = targetPackage,
            actionType = actionType,
            targetNodeHash = liveSnapshot?.nodeHash ?: targetNodeHash,
            windowId = liveSnapshot?.windowId?.toString() ?: windowId,
            displayBoundsHash = liveSnapshot?.bounds?.toShortString() ?: displayBoundsHash,
            payloadText = payloadText,
            rawScreenContext = effectiveScreenText,
            isFlagSecure = isFlagSecure
        )
        val composite = computeCompositeHash(action)

        return runCatching {
            val guard = Guard2MEngine.evaluate(context, actionType, targetPackage, userCommand, payloadText)
            val risk = broker.classifyAction(targetPackage, actionType, isFlagSecure)
            when {
                sanitized.threatDetected -> {
                    broker.evaluateAndMint(
                        action,
                        System.currentTimeMillis().toULong(),
                        SystemClock.elapsedRealtime().toULong()
                    )
                    AgentStepOutcome.Denied("Blocked by PromptSanitizer")
                }
                guard.loaded && guard.label == "BLOCK_THREAT" && guard.probBlock >= 0.55 -> {
                    AgentStepOutcome.Blocked(
                        sourceLabel = targetPackage,
                        attemptSummary = "$actionType blocked by Guard-2M (${guard.latencyUs} us)",
                        matchedSnippet = "Guard-2M threat confidence ${(guard.probBlock * 100).toInt()}%",
                        statusText = "Blocked by Guard-2M"
                    )
                }
                risk == RiskLevel.LEVEL0_HARD_BLOCKED && targetPackage.equals("com.android.settings", ignoreCase = true) &&
                    guard.loaded && guard.label == "SAFE_AUTO" && guard.probSafe >= 0.70 -> {
                    val a11yNote = maybeDispatchAccessibility(action, action.targetNodeHash, true)
                    AgentStepOutcome.Executed(
                        summary = "Guard-2M auto-confirmed (${guard.latencyUs} us) $a11yNote",
                        brainJson = brainOut
                    )
                }
                risk == RiskLevel.LEVEL0_HARD_BLOCKED -> {
                    broker.evaluateAndMint(
                        action,
                        System.currentTimeMillis().toULong(),
                        SystemClock.elapsedRealtime().toULong()
                    )
                    AgentStepOutcome.Denied("Unexpected pass on blocked action")
                }
                risk == RiskLevel.LEVEL2_EXPLICIT_CONFIRM -> {
                    broker.validateOutboundPayload(actionType, payloadText.ifBlank { userCommand })
                    if (guard.loaded && guard.label == "SAFE_AUTO" && guard.probSafe >= 0.70) {
                        val token = broker.evaluateAndMint(
                            action,
                            System.currentTimeMillis().toULong(),
                            SystemClock.elapsedRealtime().toULong()
                        )
                        val burned = broker.verifyAndBurnToken(
                            token,
                            targetPackage,
                            composite,
                            SystemClock.elapsedRealtime().toULong()
                        )
                        val a11yNote = maybeDispatchAccessibility(action, action.targetNodeHash, burned)
                        AgentStepOutcome.Executed(
                            summary = "Guard-2M auto-confirmed (${guard.latencyUs} us) $a11yNote",
                            brainJson = brainOut
                        )
                    } else {
                        AgentStepOutcome.NeedsTapApproval(
                            action = action,
                            compositeNodeHash = composite,
                            expiresAtMonoMs = SystemClock.elapsedRealtime() + 5_000L,
                            brainJson = brainOut
                        )
                    }
                }
                else -> {
                    val token = broker.evaluateAndMint(
                        action,
                        System.currentTimeMillis().toULong(),
                        SystemClock.elapsedRealtime().toULong()
                    )
                    val burned = broker.verifyAndBurnToken(
                        token,
                        targetPackage,
                        composite,
                        SystemClock.elapsedRealtime().toULong()
                    )
                    val a11yNote = maybeDispatchAccessibility(action, action.targetNodeHash, burned)
                    AgentStepOutcome.Executed(
                        summary = "Token ${token.tokenId.take(12)} burned=$burned (L1 AUTO) $a11yNote",
                        brainJson = brainOut
                    )
                }
            }
        }.getOrElse { err ->
            mapBrokerFailure(err, targetPackage, actionType)
        }
    }

    fun completeTapApproval(action: ProposedAction): Result<String> = runCatching {
        val broker = brokerProvider()
        val svc = AegisAccessibilityService.instance
        val refreshedAction = if (svc != null) {
            val (livePkg, _) = svc.captureVisibleDomText(64)
            if (livePkg == action.targetPackage) {
                val snap = svc.resolveLiveTargetSnapshot(action.actionType, action.payloadText)
                if (snap != null) {
                    action.copy(
                        targetNodeHash = snap.nodeHash,
                        windowId = snap.windowId.toString(),
                        displayBoundsHash = snap.bounds.toShortString()
                    )
                } else action
            } else action
        } else action

        val compositeNodeHash = computeCompositeHash(refreshedAction)
        val token = broker.evaluateAndMint(
            refreshedAction,
            System.currentTimeMillis().toULong(),
            SystemClock.elapsedRealtime().toULong()
        )
        val burned = broker.verifyAndBurnToken(
            token,
            refreshedAction.targetPackage,
            compositeNodeHash,
            SystemClock.elapsedRealtime().toULong()
        )
        val a11yNote = maybeDispatchAccessibility(refreshedAction, refreshedAction.targetNodeHash, burned)
        "Confirmed ${token.tokenId.take(12)} burned=$burned $a11yNote"
    }

    fun triggerEmergencyKillSwitch(reason: String) {
        runCatching {
            brokerProvider().revokeAllActiveTokens(reason, System.currentTimeMillis().toULong())
        }
        runCatching {
            brainProvider()?.cancelInference("emergency_kill")
        }
    }

    private fun maybeDispatchAccessibility(
        action: ProposedAction,
        rawNodeHash: String,
        tokenBurned: Boolean
    ): String {
        if (!tokenBurned) return "(Token invalid)"
        val svc = AegisAccessibilityService.instance
        if (svc == null) return "(Sandbox mode)"
        var livePkg = svc.captureVisibleDomText(32).first
        if (livePkg.isBlank()) {
            Thread.sleep(160L)
            livePkg = svc.captureVisibleDomText(32).first
        }
        val isLaunchRequest = rawNodeHash == "node_primary_cta" && action.actionType == "CLICK_NODE" && action.payloadText.isBlank()
        if (livePkg == "com.aegis.core" || (livePkg.isBlank() && isLaunchRequest)) {
            val launched = launchTargetApp(action.targetPackage)
            return if (launched) {
                "(Opened ${action.targetPackage})"
            } else {
                "(App ${action.targetPackage} not installed)"
            }
        }
        val (_, msg) = svc.executeVerifiedAction(action.actionType, rawNodeHash, action.payloadText) { true }
        return "($msg)"
    }

    fun launchTargetApp(packageName: String, initialUrl: String? = null): Boolean {
        val intent = if (!initialUrl.isNullOrBlank() && packageName.equals("com.android.chrome", ignoreCase = true)) {
            Intent(Intent.ACTION_VIEW, Uri.parse(initialUrl)).apply {
                setPackage("com.android.chrome")
            }
        } else {
            context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageName.equals("com.android.settings", ignoreCase = true)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun mapBrokerFailure(err: Throwable, pkg: String, actionType: String): AgentStepOutcome {
        return when (err) {
            is BrokerException.PromptInjectionDetected -> AgentStepOutcome.Blocked(
                sourceLabel = pkg,
                attemptSummary = "$actionType blocked by PromptSanitizer",
                matchedSnippet = err.message ?: "Prompt injection detected",
                statusText = "Blocked injection in Rust Broker"
            )
            is BrokerException.PayloadPolicyViolation -> AgentStepOutcome.Blocked(
                sourceLabel = pkg,
                attemptSummary = "$actionType blocked by Payload Firewall",
                matchedSnippet = err.message ?: "Sensitive payload forbidden by hard rule",
                statusText = "Payload blocked by Hard Rule"
            )
            is BrokerException.RateLimitExceeded -> AgentStepOutcome.Blocked(
                sourceLabel = pkg,
                attemptSummary = "$actionType blocked by Rate Limit",
                matchedSnippet = err.message ?: "Mutation quota exceeded (max 24 per 60s)",
                statusText = "Rate limit enforced"
            )
            is BrokerException.HardBlockedApp -> AgentStepOutcome.Blocked(
                sourceLabel = pkg,
                attemptSummary = "Level 0 Hard-Rule Block",
                matchedSnippet = "$actionType locked for $pkg",
                statusText = "Level 0 Hard-Block enforced for $pkg"
            )
            else -> AgentStepOutcome.Denied("Broker denied: ${err.message ?: err.javaClass.simpleName}")
        }
    }

    fun computeCompositeHash(action: ProposedAction): String {
        val raw = "${action.targetPackage}|${action.targetNodeHash}|${action.windowId}|${action.displayBoundsHash}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
        return "sha256:" + digest.joinToString("") { "%02x".format(it) }
    }
}
