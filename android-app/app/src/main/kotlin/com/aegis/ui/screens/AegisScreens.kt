package com.aegis.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aegis.hardware.HardwareProfile
import com.aegis.ui.components.CleanCard
import com.aegis.ui.components.SubtlePillTag
import com.aegis.ui.theme.AegisTokens

/**
 * Minimalist Onboarding Screen
 */
@Composable
fun OnboardingProfilerScreen(
    detectedProfile: HardwareProfile,
    hasDownloadedModel: Boolean,
    onDownloadAndVerify: () -> Unit,
    onContinueSandbox: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AegisTokens.Canvas)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AegisTokens.SurfaceRaised)
                    .border(1.dp, AegisTokens.BorderSubtle, RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = "Lumen",
                    tint = AegisTokens.TextPrimary,
                    modifier = Modifier.size(26.dp)
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Lumen · Private\non-device phone AI.", style = MaterialTheme.typography.displaySmall)
                Text(
                    "No cloud inference. Banking, OTP sharing, and destructive actions stay strictly locked by deterministic hard rules.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = AegisTokens.TextSecondary
                )
            }

            CleanCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Device Readiness", style = MaterialTheme.typography.titleMedium)
                    SubtlePillTag(label = "${detectedProfile.totalRamGb} GB RAM", color = AegisTokens.TrustGreen)
                }
                HorizontalDivider(color = AegisTokens.Divider)
                OnboardingRow("Local runtime", "llama.cpp + mtmd VLM (Isolated)")
                OnboardingRow("Default model", "SmolVLM 500M Vision (521 MB)")
                OnboardingRow("Guard model", "Guard-2M (2.02M int8 params)")
                OnboardingRow("Memory budget", "Under ${detectedProfile.safeCeilingMb} MB")
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = if (hasDownloadedModel) onContinueSandbox else onDownloadAndVerify,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AegisTokens.AccentPrimary,
                    contentColor = AegisTokens.AccentOnPrimary
                )
            ) {
                Text(
                    text = if (hasDownloadedModel) "Continue (Model Verified)" else "Download Model (469 MB)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
            }
            if (!hasDownloadedModel) {
                OutlinedButton(
                    onClick = onContinueSandbox,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text("Continue in Fast Test Mode", color = AegisTokens.TextSecondary, fontSize = 14.sp)
                }
            }
        }
    }
}

@Composable
private fun OnboardingRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = AegisTokens.TextSecondary)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AegisTokens.TextPrimary)
    }
}

/**
 * Blocked Threat Sheet
 */
@Composable
fun BlockedThreatModal(
    sourceLabel: String,
    blockedAttempt: String,
    matchedInjectionSnippet: String,
    onInspectReceipt: () -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD9000000))
            .padding(20.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AegisTokens.Surface, RoundedCornerShape(24.dp))
                .border(1.dp, AegisTokens.BorderSubtle, RoundedCornerShape(24.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(AegisTokens.DangerRedSoft, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.GppBad,
                        contentDescription = null,
                        tint = AegisTokens.DangerRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text("Action Blocked", style = MaterialTheme.typography.titleMedium)
                    Text(sourceLabel, fontSize = 12.sp, color = AegisTokens.TextSecondary)
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AegisTokens.Canvas, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(blockedAttempt, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = AegisTokens.TextPrimary)
                Text(
                    matchedInjectionSnippet,
                    fontSize = 12.sp,
                    color = AegisTokens.DangerRed
                )
            }

            Text(
                "Blocked by deterministic hard rules. Your apps, keys, and files were not touched.",
                fontSize = 13.sp,
                color = AegisTokens.TextSecondary
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onInspectReceipt,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("View Log", color = AegisTokens.TextPrimary)
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AegisTokens.AccentPrimary,
                        contentColor = AegisTokens.AccentOnPrimary
                    )
                ) {
                    Text("Done", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/**
 * Level 2 Explicit Confirmation Bottom Sheet (Hard-Rule Checked, Zero Biometric)
 */
@Composable
fun TapApprovalModal(
    actionLabel: String,
    targetApp: String,
    initialPayload: String,
    allowEdit: Boolean,
    remainingSeconds: Float,
    onDeny: () -> Unit,
    onConfirmTap: (editedPayload: String) -> Unit
) {
    var payload by remember(initialPayload) { mutableStateOf(initialPayload) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(AegisTokens.Surface, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .border(1.dp, AegisTokens.BorderSubtle, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(36.dp)
                    .height(4.dp)
                    .background(AegisTokens.BorderSubtle, CircleShape)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = AegisTokens.TextPrimary)
                    Column {
                        Text("Confirm Action", style = MaterialTheme.typography.titleMedium)
                        Text("$targetApp · $actionLabel · Hard Rules Checked", fontSize = 12.sp, color = AegisTokens.TextSecondary)
                    }
                }
                SubtlePillTag(label = "${"%.1f".format(remainingSeconds)}s", color = AegisTokens.TrustGreen)
            }

            LinearProgressIndicator(
                progress = { (remainingSeconds / 5.0f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = AegisTokens.TrustGreen,
                trackColor = AegisTokens.SurfaceRaised
            )

            if (allowEdit) {
                OutlinedTextField(
                    value = payload,
                    onValueChange = { payload = it },
                    label = { Text("Action text") },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "Review text for $targetApp" }
                )
            } else if (payload.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AegisTokens.Canvas, RoundedCornerShape(12.dp))
                        .padding(12.dp)
                ) {
                    Text(payload, fontSize = 14.sp, color = AegisTokens.TextPrimary)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDeny,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Cancel", color = AegisTokens.TextPrimary)
                }
                Button(
                    onClick = { onConfirmTap(payload) },
                    modifier = Modifier
                        .weight(1.2f)
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = AegisTokens.AccentPrimary,
                        contentColor = AegisTokens.AccentOnPrimary
                    )
                ) {
                    Text("Confirm", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
