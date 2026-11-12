package com.v2ray.ang.ui.main

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.RxGlassButton
import com.v2ray.ang.ui.compose.RxGlassPanel

/** A real import entry point, shown both before and after subscription groups exist. */
@Composable
fun MainEmptyState(
    onImportConfig: () -> Unit,
    onImportClipboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        RxGlassPanel(
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
            shape = RoundedCornerShape(36.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // The mark sits in a soft accent disc: it gives the empty screen a focal point
                // without a stock illustration, and follows the active accent in every theme.
                Box(
                    modifier = Modifier
                        .size(108.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f),
                                )
                            )
                        )
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "RX",
                        style = TextStyle(
                            brush = Brush.linearGradient(
                                0f to Color(0xFFFFFFFF),
                                0.35f to Color(0xFFAEB4C0),
                                0.6f to Color(0xFFF6F8FB),
                                1f to Color(0xFF7D838F),
                            ),
                            fontSize = 34.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.sp,
                        ),
                    )
                }
                Text(
                    text = stringResource(R.string.empty_state_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 18.dp)
                )
                Text(
                    text = stringResource(R.string.empty_state_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 10.dp)
                )
                RxGlassButton(
                    onClick = onImportConfig,
                    accent = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_24dp),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(stringResource(R.string.menu_item_add_config))
                }
                // Same shape as the primary action, one step quieter: two ways in, one clear
                // choice, instead of a button and a stray text link.
                RxGlassButton(
                    onClick = onImportClipboard,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_copy),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Text(stringResource(R.string.empty_state_paste))
                }
            }
        }
    }
}
