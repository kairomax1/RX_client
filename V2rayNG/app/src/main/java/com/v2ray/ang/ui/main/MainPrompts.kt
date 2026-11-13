package com.v2ray.ang.ui.main

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.v2ray.ang.R
import com.v2ray.ang.ui.compose.RxPromptButton
import com.v2ray.ang.ui.compose.RxPromptButtons
import com.v2ray.ang.ui.compose.RxPromptDialog
import com.v2ray.ang.ui.compose.RxPromptField
import com.v2ray.ang.ui.compose.RxPromptMessage
import com.v2ray.ang.ui.compose.RxPromptSubject
import com.v2ray.ang.ui.compose.RxPromptTone

/**
 * Asks whether a pasted subscription link should get a name. "Yes" turns the same card into a
 * name box; "no", leaving it empty or backing out all answer with null, which means the
 * automatic name.
 */
@Composable
internal fun SubscriptionNamePrompt(onAnswer: (String?) -> Unit) {
    var naming by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    RxPromptDialog(onDismissRequest = { onAnswer(null) }, dismissOnOutsideTap = false) {
        RxPromptMessage(stringResource(R.string.sub_name_ask))
        AnimatedContent(
            targetState = naming,
            transitionSpec = {
                (fadeIn() + slideInVertically { it / 3 }) togetherWith (fadeOut() + slideOutVertically { -it / 3 })
            },
            label = "subNameStep",
        ) { typing ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (typing) {
                    Spacer(Modifier.height(18.dp))
                    RxPromptField(
                        value = name,
                        onValueChange = { name = it.take(40) },
                        placeholder = stringResource(R.string.prompt_write_hint),
                        onDone = { onAnswer(name) },
                    )
                    RxPromptButtons {
                        RxPromptButton(
                            text = stringResource(R.string.prompt_do_it),
                            tone = RxPromptTone.Silver,
                            onClick = { onAnswer(name) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        RxPromptButton(
                            text = stringResource(R.string.prompt_cancel),
                            tone = RxPromptTone.Ghost,
                            onClick = { onAnswer(null) },
                            modifier = Modifier.fillMaxWidth(),
                            order = 1,
                        )
                    }
                } else {
                    RxPromptButtons {
                        RxPromptButton(
                            text = stringResource(R.string.sub_name_yes),
                            tone = RxPromptTone.Silver,
                            onClick = { naming = true },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        RxPromptButton(
                            text = stringResource(R.string.sub_name_no),
                            tone = RxPromptTone.Ghost,
                            onClick = { onAnswer(null) },
                            modifier = Modifier.fillMaxWidth(),
                            order = 1,
                        )
                    }
                }
            }
        }
    }
}

/** The last check before one config is deleted. */
@Composable
internal fun ConfigDeletePrompt(configName: String, onKeep: () -> Unit, onDelete: () -> Unit) {
    RxPromptDialog(onDismissRequest = onKeep) {
        RxPromptMessage(stringResource(R.string.delete_ask))
        Spacer(Modifier.height(14.dp))
        RxPromptSubject(configName)
        Spacer(Modifier.height(14.dp))
        RxPromptMessage(stringResource(R.string.delete_warn), fontSize = 19)
        RxPromptButtons {
            RxPromptButton(
                text = stringResource(R.string.delete_no),
                tone = RxPromptTone.MistRed,
                onClick = onKeep,
                modifier = Modifier.fillMaxWidth(),
            )
            RxPromptButton(
                text = stringResource(R.string.delete_yes),
                tone = RxPromptTone.DarkGreen,
                onClick = onDelete,
                modifier = Modifier.fillMaxWidth(),
                order = 1,
            )
        }
    }
}
