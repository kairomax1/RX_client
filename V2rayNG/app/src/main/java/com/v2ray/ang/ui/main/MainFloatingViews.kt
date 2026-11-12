package com.v2ray.ang.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ServersCache
import com.v2ray.ang.ui.compose.rxFloatingBorder
import com.v2ray.ang.ui.compose.rxFloatingColor

private val FailureTint = Color(0xFFFF5A5A)
private val MESSAGE_SHAPE = RoundedCornerShape(16.dp)

/**
 * The one message shown around connecting: it rises out of the connect button when the attempt
 * failed and leaves on its own. Same floating surface as the menus, with a red edge.
 */
@Composable
fun ConnectFailureMessage(visible: Boolean, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = slideInVertically(
            spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow)
        ) { it * 2 } + fadeIn(tween(220)) + scaleIn(tween(260), initialScale = 0.85f),
        exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(220)) +
                scaleOut(tween(260), targetScale = 0.92f),
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 26.dp)
                .widthIn(max = 420.dp)
                .shadow(18.dp, MESSAGE_SHAPE, ambientColor = FailureTint, spotColor = FailureTint)
                .clip(MESSAGE_SHAPE)
                .background(rxFloatingColor())
                .background(
                    Brush.horizontalGradient(
                        listOf(FailureTint.copy(alpha = 0.16f), FailureTint.copy(alpha = 0.04f))
                    )
                )
                .border(1.dp, FailureTint.copy(alpha = 0.45f), MESSAGE_SHAPE)
                .semantics { liveRegion = LiveRegionMode.Assertive }
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(FailureTint))
            Text(
                text = stringResource(R.string.rx_connect_failed),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** Tick several configs of the current group, then copy them all at once. */
@Composable
fun CopySelectedDialog(
    servers: List<ServersCache>,
    onDismiss: () -> Unit,
    onCopy: (List<String>) -> Unit,
) {
    val checked = remember { mutableStateMapOf<String, Boolean>() }
    var shown by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shown = true }
    Dialog(onDismissRequest = onDismiss) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(200)) + scaleIn(
                spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
                initialScale = 0.86f,
            ),
        ) {
            val shape = RoundedCornerShape(24.dp)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(rxFloatingColor())
                    .border(rxFloatingBorder(), shape)
                    .padding(top = 18.dp, bottom = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.menu_more_copy_selected),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                if (servers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.empty_state_title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 380.dp).padding(top = 8.dp)) {
                    items(servers, key = { it.guid }) { server ->
                        val isChecked = checked[server.guid] == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { checked[server.guid] = !isChecked }
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = isChecked, onCheckedChange = { checked[server.guid] = it })
                            Text(
                                text = server.profile.remarks,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(end = 12.dp),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    val selected = servers.filter { checked[it.guid] == true }.map { it.guid }
                    TextButton(
                        enabled = selected.isNotEmpty(),
                        onClick = { onCopy(selected) },
                    ) {
                        Text(stringResource(R.string.menu_more_copy_selected_confirm, selected.size))
                    }
                }
            }
        }
    }
}
