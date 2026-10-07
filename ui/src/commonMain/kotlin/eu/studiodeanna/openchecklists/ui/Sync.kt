package eu.studiodeanna.openchecklists.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.studiodeanna.openchecklists.ListEntry
import eu.studiodeanna.openchecklists.SyncState
import eu.studiodeanna.openchecklists.currentTimeMillis

/** A small "shared" marker, with the sync state folded in. */
@Composable
fun SyncBadge(entry: ListEntry) {
    val sync = entry.sync
    if (sync == SyncState.LocalOnly) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when (sync) {
            SyncState.Syncing -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            is SyncState.Failed -> Icon(
                Icons.Default.Warning,
                contentDescription = strings.syncProblem,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            else -> Icon(
                Icons.Default.Share,
                contentDescription = strings.shared,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(strings.shared, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun describe(sync: SyncState, strings: Strings): String = when (sync) {
    SyncState.LocalOnly -> strings.onlyOnDevice
    SyncState.Syncing -> strings.syncing
    is SyncState.Failed -> sync.message
    is SyncState.Synced -> {
        val minutes = (currentTimeMillis() - sync.at) / 60_000
        when {
            minutes < 1 -> strings.syncedJustNow
            minutes < 60 -> strings.syncedMinutesAgo(minutes)
            minutes < 48 * 60 -> strings.syncedHoursAgo(minutes / 60)
            else -> strings.syncedDaysAgo(minutes / (24 * 60))
        }
    }
}
