package io.zerosettle.justone.screens.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Home screen header — greeting, habit count, and optional Pro badge.
 *
 * Pure UI: receives all data as parameters, no side effects.
 */
@Composable
fun HomeHeader(
    displayName: String,
    habitCount: Int,
    isPremium: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        // Greeting row: "Hi, Name" + optional Pro badge
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Hi, $displayName",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )

            if (isPremium) {
                Spacer(modifier = Modifier.width(10.dp))
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = "Pro",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                        )
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        labelColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    border = null,
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Habit count subtitle
        val habitLabel = if (habitCount == 1) "habit tracked" else "habits tracked"
        Text(
            text = "$habitCount $habitLabel",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
        )
    }
}
