package com.i5autolock.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.i5autolock.ui.theme.ParametricPixels
import com.i5autolock.ui.theme.PixelBand
import com.i5autolock.ui.theme.brandGradient
import com.i5autolock.ui.theme.heroGlow

/**
 * Reusable gradient hero banner echoing the home "System Status" card — a glowing brand-gradient
 * surface with the parametric-pixel accent. Used as the header on secondary screens.
 */
@Composable
fun HeroBanner(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    eyebrow: String? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Box(Modifier.fillMaxWidth().background(brandGradient())) {
            Box(Modifier.fillMaxWidth().height(180.dp).background(heroGlow()))
            ParametricPixels(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .size(56.dp, 35.dp),
                color = Color.White.copy(alpha = 0.30f),
            )
            Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                eyebrow?.let {
                    Text(
                        it.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.75f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    icon?.let {
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color.White.copy(alpha = 0.18f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(it, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                        }
                    }
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                    )
                }
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.9f))
                }
                PixelBand(
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = Color.White.copy(alpha = 0.85f),
                    cells = 22,
                )
            }
        }
    }
}
