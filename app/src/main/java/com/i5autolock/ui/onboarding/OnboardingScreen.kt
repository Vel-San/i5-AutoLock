package com.i5autolock.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.i5autolock.R
import com.i5autolock.ui.theme.ParametricPixels
import com.i5autolock.ui.theme.ambientBackground
import com.i5autolock.ui.theme.brandGradient
import com.i5autolock.ui.theme.heroGlow
import kotlinx.coroutines.launch

private data class OnboardStep(val icon: ImageVector, val titleRes: Int, val bodyRes: Int)

private val steps = listOf(
    OnboardStep(Icons.Default.Lock, R.string.onboarding_welcome_title, R.string.onboarding_welcome_body),
    OnboardStep(Icons.Default.Bluetooth, R.string.onboarding_how_title, R.string.onboarding_how_body),
    OnboardStep(Icons.Default.Shield, R.string.onboarding_safety_title, R.string.onboarding_safety_body),
    OnboardStep(Icons.AutoMirrored.Filled.HelpOutline, R.string.onboarding_setup_title, R.string.onboarding_setup_body),
)

@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val pager = rememberPagerState(pageCount = { steps.size })
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize().background(ambientBackground())) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onFinish) { Text(stringResource(R.string.onboarding_skip)) }
            }

            HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
                OnboardPage(steps[page])
            }

            Row(
                Modifier.fillMaxWidth().padding(vertical = 16.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                repeat(steps.size) { i ->
                    val selected = i == pager.currentPage
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (selected) 10.dp else 8.dp)
                            .clip(CircleShape)
                            .background(
                                if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.outlineVariant,
                            ),
                    )
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                if (pager.currentPage > 0) {
                    TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) {
                        Text(stringResource(R.string.onboarding_back))
                    }
                } else {
                    Spacer(Modifier.size(1.dp))
                }
                val last = pager.currentPage == steps.lastIndex
                Button(
                    onClick = {
                        if (last) onFinish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                    },
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(
                        stringResource(if (last) R.string.onboarding_get_started else R.string.onboarding_next),
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardPage(step: OnboardStep) {
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(36.dp))
                .background(brandGradient()),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxSize().background(heroGlow()))
            ParametricPixels(Modifier.align(Alignment.TopEnd).padding(12.dp).size(40.dp, 25.dp), color = Color.White.copy(alpha = 0.3f))
            Icon(step.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(64.dp))
        }
        Spacer(Modifier.height(32.dp))
        Text(
            stringResource(step.titleRes),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(step.bodyRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
