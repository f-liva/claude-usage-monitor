package com.claudemonitor.app.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.claudemonitor.app.R
import com.claudemonitor.app.data.model.UsageData
import com.claudemonitor.app.ui.theme.*

@Composable
fun StatusBanner(
    usageData: UsageData,
    modifier: Modifier = Modifier
) {
    val overallStatus = when {
        usageData.modelLimits.any { it.isAtLimit } -> BannerStatus.AT_LIMIT
        usageData.modelLimits.any { it.isNearLimit } -> BannerStatus.NEAR_LIMIT
        usageData.modelLimits.isEmpty() -> BannerStatus.NO_DATA
        else -> BannerStatus.OK
    }

    val (icon, title, subtitle, gradientColors) = when (overallStatus) {
        BannerStatus.OK -> BannerInfo(
            Icons.Rounded.CheckCircle,
            stringResource(R.string.status_ok),
            stringResource(R.string.status_ok_desc),
            listOf(Success.copy(alpha = 0.8f), Success.copy(alpha = 0.6f))
        )
        BannerStatus.NEAR_LIMIT -> BannerInfo(
            Icons.Rounded.Warning,
            stringResource(R.string.status_near_limit),
            stringResource(R.string.status_near_limit_desc),
            listOf(Warning.copy(alpha = 0.8f), Warning.copy(alpha = 0.6f))
        )
        BannerStatus.AT_LIMIT -> BannerInfo(
            Icons.Rounded.Error,
            stringResource(R.string.status_at_limit),
            stringResource(R.string.status_at_limit_desc),
            listOf(Danger.copy(alpha = 0.8f), Danger.copy(alpha = 0.6f))
        )
        BannerStatus.NO_DATA -> BannerInfo(
            Icons.Rounded.CloudOff,
            stringResource(R.string.status_no_data),
            stringResource(R.string.status_no_data_desc),
            listOf(
                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(gradientColors)
                )
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.surface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

private enum class BannerStatus { OK, NEAR_LIMIT, AT_LIMIT, NO_DATA }

private data class BannerInfo(
    val icon: ImageVector,
    val title: String,
    val subtitle: String,
    val gradientColors: List<androidx.compose.ui.graphics.Color>
)
