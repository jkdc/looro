package com.jkdc.looro.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.jkdc.looro.R

// TODO(rebrand): replace the placeholder contact with the publisher's real
// support address and URL before any store submission.
internal const val DELETION_SUPPORT_EMAIL = "support@looro.example"
internal const val DELETION_SUPPORT_URL = "https://looro.example/support/#delete-account"
// The draft intentionally includes no account identifiers, credentials or learning data.
internal const val DELETION_SUPPORT_MAILTO =
    "mailto:$DELETION_SUPPORT_EMAIL?subject=Looro%20account%20deletion%20request"

@Composable
internal fun AccountDeletionSupportDialog(onDismiss: () -> Unit) {
    val uri = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    var openingFailed by rememberSaveable { mutableStateOf(false) }
    var copied by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LooroColors.Cream,
        title = { Text(stringResource(R.string.account_request_deletion)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()).testTag("account-deletion-request"),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.account_deletion_support_detail))
                SelectionContainer { Text(DELETION_SUPPORT_EMAIL, style = MaterialTheme.typography.titleMedium) }
                LooroTextButton(onClick = {
                    clipboard.setText(AnnotatedString(DELETION_SUPPORT_EMAIL)); copied = true
                }, modifier = Modifier.testTag("account-deletion-copy-email")) {
                    Text(stringResource(if (copied) R.string.account_deletion_email_copied else R.string.account_deletion_copy_email),
                        color = LooroColors.Ink)
                }
                Text(stringResource(R.string.account_deletion_draft_detail), style = MaterialTheme.typography.bodySmall,
                    color = LooroColors.Secondary)
                if (openingFailed) Text(stringResource(R.string.account_deletion_open_failed), color = LooroColors.Secondary,
                    modifier = Modifier.testTag("account-deletion-open-failed"))
                LooroTextButton(onClick = {
                    openingFailed = runCatching { uri.openUri(DELETION_SUPPORT_URL) }.isFailure
                }, modifier = Modifier.testTag("account-deletion-web")) {
                    Text(stringResource(R.string.account_deletion_web), color = LooroColors.Ink)
                }
            }
        },
        confirmButton = {
            LooroTextButton(onClick = {
                openingFailed = runCatching { uri.openUri(DELETION_SUPPORT_MAILTO) }.isFailure
            }, modifier = Modifier.testTag("account-deletion-email")) {
                Text(stringResource(R.string.account_deletion_email), color = LooroColors.Ink)
            }
        },
        dismissButton = { LooroTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}
