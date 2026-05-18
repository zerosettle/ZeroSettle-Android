package io.zerosettle.justone.screens.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.zerosettle.justone.data.UserPrefs
import io.zerosettle.justone.notifications.Reminders
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderCard(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    val reminderTime by UserPrefs(ctx).reminderTime.collectAsState(initial = null)
    val isOn = reminderTime != null

    // Controls whether the time-picker dialog is visible.
    var showPicker by remember { mutableStateOf(false) }

    // Default to 21:00 when no time is set.
    val initialHour = reminderTime?.hour ?: 21
    val initialMinute = reminderTime?.minute ?: 0
    val pickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = true,
    )

    // Permission launcher for POST_NOTIFICATIONS (required on API 33+).
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showPicker = true
        }
        // If denied, we silently do nothing — the Switch will not toggle on.
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = "Daily Reminder",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Remind me each day",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = isOn,
                    onCheckedChange = { turningOn ->
                        if (turningOn) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                showPicker = true
                            }
                        } else {
                            scope.launch { UserPrefs(ctx).setReminderTime(null) }
                            Reminders.cancel(ctx)
                        }
                    },
                )
            }

            if (isOn && reminderTime != null) {
                Spacer(modifier = Modifier.height(12.dp))

                val formattedTime = String.format(
                    Locale.getDefault(),
                    "%02d:%02d",
                    reminderTime!!.hour,
                    reminderTime!!.minute,
                )

                Text(
                    text = "Reminder at $formattedTime",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { showPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Change time")
                }
            }
        }
    }

    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = { Text("Set reminder time") },
            text = {
                TimePicker(state = pickerState)
            },
            confirmButton = {
                Button(
                    onClick = {
                        val hour = pickerState.hour
                        val minute = pickerState.minute
                        scope.launch {
                            UserPrefs(ctx).setReminderTime(
                                UserPrefs.ReminderTime(hour, minute)
                            )
                        }
                        Reminders.schedule(ctx, hour, minute)
                        showPicker = false
                    },
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}
