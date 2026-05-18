package com.claudecodesetup.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PersonalAiChoiceScreen(
    onRemoteServer: () -> Unit,
    onOnDevice: () -> Unit,
    onBack: () -> Unit
) {
    var entered by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(400), label = "alpha")
    val offset by animateFloatAsState(
        if (entered) 0f else 20f, tween(400, easing = FastOutSlowInEasing), label = "offset")
    LaunchedEffect(Unit) { entered = true }

    val accentColor = Color(0xFFEF4444)

    AppBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha; translationY = offset * density }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "←", fontSize = 20.sp, color = Color(0xFF60A5FA),
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 10.dp, top = 4.dp, bottom = 4.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "PERSONAL AI", fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                        letterSpacing = 3.sp, color = Color(0xB3EF4444)
                    )
                    Text(
                        "Choose Setup", fontFamily = DmSansFamily, fontSize = 17.sp,
                        fontWeight = FontWeight.Bold, color = Color.White
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 360.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(62.dp)
                            .background(
                                Brush.linearGradient(
                                    listOf(accentColor.copy(alpha = 0.22f), accentColor.copy(alpha = 0.12f))
                                ),
                                RoundedCornerShape(18.dp)
                            )
                            .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("💻", fontSize = 26.sp)
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "How do you want to connect?",
                            fontFamily = DmSansFamily, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, color = Color.White,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "Run AI locally on your phone, or connect to a server you already have running.",
                            fontFamily = DmSansFamily, fontSize = 13.sp,
                            color = Color(0xFF9CA3AF), textAlign = TextAlign.Center
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    ChoiceCard(
                        icon = "📱",
                        title = "Install AI on This Device",
                        subtitle = "Set up Termux + Ollama on your phone.\nFree, private, works offline.",
                        accentColor = Color(0xFF10B981),
                        onClick = onOnDevice
                    )

                    ChoiceCard(
                        icon = "🌐",
                        title = "Connect to Remote Server",
                        subtitle = "Oracle Cloud, home PC, or any server\nrunning an OpenAI-compatible API.",
                        accentColor = Color(0xFF60A5FA),
                        onClick = onRemoteServer
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    icon: String,
    title: String,
    subtitle: String,
    accentColor: Color,
    onClick: () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val isPressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) 0.97f else 1f, tween(120), label = "scale")

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glowShadow(accentColor.copy(alpha = 0.10f), 12.dp, 14.dp)
            .background(Color(0x0FFFFFFF), RoundedCornerShape(16.dp))
            .border(1.dp, accentColor.copy(alpha = 0.25f), RoundedCornerShape(16.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(accentColor.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
                .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, fontSize = 22.sp)
        }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                title, fontFamily = DmSansFamily, fontSize = 14.sp,
                fontWeight = FontWeight.Bold, color = Color.White
            )
            Text(
                subtitle, fontFamily = DmSansFamily, fontSize = 11.sp,
                color = Color(0xFF6B7280), lineHeight = 16.sp
            )
        }

        Text("›", fontSize = 20.sp, color = accentColor.copy(alpha = 0.7f))
    }
}

// ── On-device AI guide ────────────────────────────────────────────────────────

@Composable
fun PersonalAiGuideScreen(
    onDone: () -> Unit,
    onBack: () -> Unit
) {
    var entered by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (entered) 1f else 0f, tween(400), label = "alpha")
    LaunchedEffect(Unit) { entered = true }

    AppBackground {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { this.alpha = alpha }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "←", fontSize = 20.sp, color = Color(0xFF60A5FA),
                    modifier = Modifier
                        .clickable(onClick = onBack)
                        .padding(end = 10.dp, top = 4.dp, bottom = 4.dp)
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "ON-DEVICE AI", fontFamily = SpaceMonoFamily, fontSize = 8.sp,
                        letterSpacing = 3.sp, color = Color(0xB310B981)
                    )
                    Text(
                        "Setup Guide", fontFamily = DmSansFamily, fontSize = 17.sp,
                        fontWeight = FontWeight.Bold, color = Color.White
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Spacer(Modifier.height(4.dp))

                // Intro card
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x0F10B981), RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0x2210B981), RoundedCornerShape(16.dp))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "📱 Run AI 100% on your phone",
                        fontFamily = DmSansFamily, fontSize = 14.sp,
                        fontWeight = FontWeight.Bold, color = Color(0xFF10B981)
                    )
                    Text(
                        "Uses Termux (Linux on Android) + Ollama (AI runtime). Free, private, and works with no internet after setup.",
                        fontFamily = DmSansFamily, fontSize = 12.sp,
                        color = Color(0xFF9CA3AF), lineHeight = 17.sp
                    )
                }

                // Steps
                GuideStep(
                    number = "1",
                    title = "Install Termux from F-Droid",
                    body = "Important: use F-Droid, NOT Google Play. The Play Store version is outdated and won't work.",
                    code = null,
                    link = "https://f-droid.org/packages/com.termux/",
                    linkLabel = "→ Open F-Droid page"
                )

                GuideStep(
                    number = "2",
                    title = "Open Termux and install Ollama",
                    body = "Tap the Termux app and run this command. It may take a few minutes.",
                    code = "pkg update && pkg install ollama",
                    link = null,
                    linkLabel = null
                )

                GuideStep(
                    number = "3",
                    title = "Pull an AI model",
                    body = "Choose based on your phone's RAM. More RAM = bigger, smarter models.",
                    code = null,
                    link = null,
                    linkLabel = null
                )

                // Model table
                RamModelTable()

                GuideStep(
                    number = "4",
                    title = "Start the Ollama server",
                    body = "Run this in Termux to start serving AI. Keep Termux open (or minimized) while using Nexus Mind.",
                    code = "ollama serve",
                    link = null,
                    linkLabel = null
                )

                // Done button
                Spacer(Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(
                            Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF059669))),
                            RoundedCornerShape(14.dp)
                        )
                        .clickable(onClick = onDone),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "✓  I've Installed It — Continue",
                        fontFamily = DmSansFamily, fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold, color = Color.White
                    )
                }

                Text(
                    "This will connect Nexus Mind to http://localhost:11434 automatically.",
                    fontFamily = DmSansFamily, fontSize = 11.sp,
                    color = Color(0xFF4B5563), textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun GuideStep(
    number: String,
    title: String,
    body: String,
    code: String?,
    link: String?,
    linkLabel: String?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(Color(0x1560A5FA), RoundedCornerShape(8.dp))
                .border(1.dp, Color(0x3360A5FA), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number, fontFamily = SpaceMonoFamily, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, color = Color(0xFF60A5FA)
            )
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                title, fontFamily = DmSansFamily, fontSize = 13.sp,
                fontWeight = FontWeight.Bold, color = Color.White
            )
            Text(
                body, fontFamily = DmSansFamily, fontSize = 12.sp,
                color = Color(0xFF9CA3AF), lineHeight = 17.sp
            )
            if (code != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x0FFFFFFF), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(
                        code, fontFamily = SpaceMonoFamily, fontSize = 11.sp,
                        color = Color(0xFF10B981)
                    )
                }
            }
            if (link != null && linkLabel != null) {
                Text(
                    linkLabel, fontFamily = DmSansFamily, fontSize = 12.sp,
                    color = Color(0xFF60A5FA), fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun RamModelTable() {
    val rows = listOf(
        Triple("6–8 GB",  "Qwen3 1.5B",  "ollama pull qwen3:1.5b"),
        Triple("6–8 GB",  "Phi-4 Mini",  "ollama pull phi4-mini"),
        Triple("6–8 GB",  "Llama 3.2 3B","ollama pull llama3.2:3b"),
        Triple("8–12 GB", "Qwen3 4B",    "ollama pull qwen3:4b"),
        Triple("8–12 GB", "Gemma3 4B",   "ollama pull gemma3:4b"),
        Triple("12 GB+",  "Qwen3 8B",    "ollama pull qwen3:8b"),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0x0AFFFFFF), RoundedCornerShape(12.dp))
            .border(1.dp, Color(0x15FFFFFF), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text(
                "RAM", fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                letterSpacing = 1.sp, color = Color(0xFF4B5563),
                modifier = Modifier.width(64.dp)
            )
            Text(
                "MODEL", fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                letterSpacing = 1.sp, color = Color(0xFF4B5563),
                modifier = Modifier.width(90.dp)
            )
            Text(
                "COMMAND", fontFamily = SpaceMonoFamily, fontSize = 9.sp,
                letterSpacing = 1.sp, color = Color(0xFF4B5563),
                modifier = Modifier.weight(1f)
            )
        }

        rows.forEachIndexed { idx, (ram, model, cmd) ->
            val isEven = idx % 2 == 0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isEven) Color(0x08FFFFFF) else Color.Transparent,
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    ram, fontFamily = DmSansFamily, fontSize = 11.sp,
                    color = Color(0xFF6B7280), modifier = Modifier.width(64.dp)
                )
                Text(
                    model, fontFamily = DmSansFamily, fontSize = 11.sp,
                    fontWeight = FontWeight.Bold, color = Color.White,
                    modifier = Modifier.width(90.dp)
                )
                Text(
                    cmd, fontFamily = SpaceMonoFamily, fontSize = 10.sp,
                    color = Color(0xFF10B981), modifier = Modifier.weight(1f)
                )
            }
        }
    }
}
