package com.mbols2510.mangamanager.ui.pairing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import android.view.View
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mbols2510.mangamanager.R

@Composable
fun PairingScreen(viewModel: PairingViewModel, onPaired: () -> Unit, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) {
        if (state == PairingUiState.Success) onPaired()
    }
    PairingContent(
        state = state,
        onSubmit = viewModel::submit,
        onConfirmAuthDisabled = viewModel::confirmAuthDisabled,
        onDismissAuthDisabled = viewModel::dismissAuthDisabled,
        modifier = modifier,
    )
}

@Composable
fun PairingContent(
    state: PairingUiState,
    onSubmit: (String) -> Unit,
    onConfirmAuthDisabled: () -> Unit,
    onDismissAuthDisabled: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Deliberately `remember`, not `rememberSaveable`: the code contains the token and must never reach a
    // saved-state bundle. It is cleared as soon as it is submitted.
    var code by remember { mutableStateOf("") }
    val validating = state == PairingUiState.Validating
    val canSubmit = !validating && code.isNotBlank()
    val submit = {
        if (canSubmit) {
            val submitted = code
            code = ""
            onSubmit(submitted)
        }
    }

    // The code is a secret: keep password managers and autofill from offering to save or fill it. Compose has
    // no per-field opt-out, so the host view (and with it every field of this screen) is excluded while shown.
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.importantForAutofill
        view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        onDispose { view.importantForAutofill = previous }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.pairing_title),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = stringResource(R.string.pairing_explanation), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text(stringResource(R.string.pairing_code_label)) },
            enabled = !validating,
            // Masked, and a password keyboard so the IME neither suggests nor learns the token.
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { submit() },
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.pairing_submit))
        }
        if (validating) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                Text(text = stringResource(R.string.pairing_validating), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state is PairingUiState.Error) {
            Text(
                text = errorText(state),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }

    if (state is PairingUiState.ConfirmAuthDisabled) {
        AlertDialog(
            onDismissRequest = onDismissAuthDisabled,
            title = { Text(stringResource(R.string.pairing_auth_disabled_title)) },
            text = { Text(stringResource(R.string.pairing_auth_disabled_message, state.host)) },
            confirmButton = {
                TextButton(onClick = onConfirmAuthDisabled) {
                    Text(stringResource(R.string.pairing_auth_disabled_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissAuthDisabled) {
                    Text(stringResource(R.string.pairing_auth_disabled_cancel))
                }
            },
        )
    }
}

@Composable
private fun errorText(error: PairingUiState.Error): String =
    if (error.detail != null) stringResource(error.message, error.detail) else stringResource(error.message)
