package com.jkdc.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jkdc.app.R
import com.jkdc.app.core.MinutePack
import com.jkdc.app.core.MinutePurchaseNotice
import com.jkdc.app.core.MinutePurchaseState
import com.jkdc.app.core.PACK_TOKEN_KIND
import com.jkdc.app.core.PACK_UNLOCK_KIND
import com.jkdc.app.core.PurchaseChannel
import com.jkdc.app.core.TokenWallet
import com.jkdc.app.core.tokenCreditText

/** Paywall + pack purchase surface for "Palique (tokens)". Render-only: prices,
    SKUs and availability come from the shared purchase controller, and buying
    rides the same MinutePurchaseViewModel flow as the minute sheet. The balance
    chip is the server's display hint (GET /v1/wallet) — never a cached balance.
    Copy states plainly that tokens are prepaid service credit, not a currency,
    and never promises unlimited usage. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TokensPurchaseSheet(
    state: MinutePurchaseState,
    wallet: TokenWallet?,
    exhausted: Boolean,
    signedIn: Boolean,
    onBuy: (String) -> Unit,
    onSignIn: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    accountBusy: Boolean = false,
) {
    val checking = state.busy || state.notice == MinutePurchaseNotice.VERIFYING
    val needsSignIn = !signedIn || state.notice == MinutePurchaseNotice.SIGN_IN_REQUIRED
    val canChoose = state.available && !checking && !state.purchaseInProgress && !accountBusy && !needsSignIn
    val tokenPacks = state.packs.filter { it.isPack }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.Cream,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.90f).testTag("tokens-purchase-sheet")) {
            SoftAnimatedBackground(Modifier.matchParentSize())
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    AppTextButton(onDismiss, Modifier.testTag("tokens-purchase-close")) {
                        Text(stringResource(R.string.settings_done), color = AppColors.Secondary)
                    }
                }
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppOrb(modifier = Modifier.size(104.dp), energy = if (checking) .10f else 0f)
                    Text(stringResource(R.string.tokens_sheet_title), style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.tokens_sheet_intro), style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.Secondary, textAlign = TextAlign.Center)
                    if (!needsSignIn) wallet?.let { hint ->
                        Surface(color = AppColors.Peach.copy(alpha = .70f), shape = RoundedCornerShape(50)) {
                            Text(
                                stringResource(if (hint.availableNanoUSD > 0) R.string.tokens_balance_chip else R.string.tokens_balance_empty,
                                    tokenCreditText(hint.availableNanoUSD)),
                                Modifier.padding(horizontal = 16.dp, vertical = 10.dp).testTag("tokens-balance-chip"),
                                style = MaterialTheme.typography.labelMedium, color = AppColors.Ink)
                        }
                    }
                    if (exhausted) Surface(color = AppColors.Peach, shape = RoundedCornerShape(22.dp)) {
                        Text(stringResource(R.string.tokens_exhausted), Modifier.fillMaxWidth().padding(16.dp)
                            .testTag("tokens-exhausted"), style = MaterialTheme.typography.bodyMedium, color = AppColors.Ink,
                            textAlign = TextAlign.Center)
                    }
                    Text(stringResource(R.string.tokens_credit_disclosure), style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Secondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 480.dp)
                            .testTag("tokens-credit-disclosure"))
                    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        tokenPacks.forEach { pack ->
                            TokenPackCard(pack, enabled = canChoose, onBuy = { onBuy(pack.sku) })
                        }
                        if (tokenPacks.isEmpty()) Surface(color = Color.White.copy(alpha = .72f), shape = RoundedCornerShape(26.dp)) {
                            Text(stringResource(if (checking) R.string.tokens_loading else R.string.tokens_unavailable),
                                Modifier.fillMaxWidth().padding(24.dp).testTag("tokens-purchase-empty"),
                                style = MaterialTheme.typography.bodyMedium, color = AppColors.Secondary, textAlign = TextAlign.Center)
                        }
                    }
                    if (needsSignIn) {
                        Text(stringResource(R.string.tokens_sign_in_detail), style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.Secondary, textAlign = TextAlign.Center)
                        Button(onClick = onSignIn, enabled = !checking && !accountBusy, shape = RoundedCornerShape(50),
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(min = 54.dp).testTag("tokens-purchase-sign-in")) {
                            Text(stringResource(R.string.tokens_sign_in), textAlign = TextAlign.Center)
                        }
                    }
                    val statusText = when {
                        state.notice != null -> stringResource(statusTextResource(state.notice))
                        checking -> stringResource(R.string.tokens_loading)
                        state.purchaseInProgress -> stringResource(R.string.minute_purchases_opened)
                        else -> null
                    }
                    statusText?.let { text ->
                        Row(Modifier.widthIn(max = 520.dp).fillMaxWidth().testTag("tokens-purchase-status")
                            .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (checking) CircularProgressIndicator(Modifier.size(20.dp), color = AppColors.Ink, strokeWidth = 2.dp)
                            Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.Secondary)
                        }
                    }
                    if (tokenPacks.isNotEmpty()) Text(stringResource(R.string.tokens_one_time),
                        style = MaterialTheme.typography.labelMedium, color = AppColors.Secondary, textAlign = TextAlign.Center)
                    Text(stringResource(if (state.channel == PurchaseChannel.STRIPE) R.string.minute_purchases_stripe_terms
                        else R.string.minute_purchases_play_terms), style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Secondary, textAlign = TextAlign.Center)
                    AppTextButton(onRefresh, enabled = !checking && !accountBusy,
                        modifier = Modifier.testTag("tokens-purchase-refresh")) {
                        Text(stringResource(R.string.minute_purchases_check))
                    }
                }
            }
        }
    }
}

@Composable
private fun TokenPackCard(pack: MinutePack, enabled: Boolean, onBuy: () -> Unit) {
    val isTokenPack = pack.entitlementKind == PACK_TOKEN_KIND
    val title = stringResource(if (isTokenPack) R.string.tokens_pack_card_title else R.string.tokens_unlock_card_title)
    val detail = if (isTokenPack) stringResource(R.string.tokens_pack_card_credit, tokenCreditText(pack.creditNanoUSD))
        else stringResource(R.string.tokens_unlock_card_detail)
    val action = stringResource(R.string.minute_purchases_pack_action, title, pack.formattedPrice)
    Surface(onClick = onBuy, enabled = enabled, shape = RoundedCornerShape(26.dp),
        color = Color.White.copy(alpha = if (enabled) .88f else .60f),
        border = BorderStroke(1.dp, Color.White),
        modifier = Modifier.fillMaxWidth().testTag("tokens-pack-${pack.sku}")
            .semantics { contentDescription = action }) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = AppColors.Ink)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
            Surface(color = AppColors.Peach.copy(alpha = if (enabled) 1f else .65f), shape = RoundedCornerShape(50)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(pack.formattedPrice, style = MaterialTheme.typography.titleMedium, color = AppColors.Ink,
                        modifier = Modifier.weight(1f, fill = false))
                    AppIcon(AppSymbol.ChevronRight, Modifier.size(14.dp), color = AppColors.Secondary)
                }
            }
        }
    }
}

/** Token-specific status copy where the minute wording would mislead; generic
    outcomes (canceled, price change, verification) reuse the shared strings. */
@Composable
private fun statusTextResource(notice: MinutePurchaseNotice): Int = when (notice) {
    MinutePurchaseNotice.ADDED -> R.string.tokens_added
    MinutePurchaseNotice.PENDING -> R.string.tokens_pending
    MinutePurchaseNotice.SANDBOX_RECORDED -> R.string.tokens_sandbox
    MinutePurchaseNotice.UNAVAILABLE -> R.string.tokens_unavailable
    MinutePurchaseNotice.SIGN_IN_REQUIRED -> R.string.tokens_sign_in_again
    else -> notice.sharedTextResource()
}

@Composable
private fun MinutePurchaseNotice.sharedTextResource(): Int = when (this) {
    MinutePurchaseNotice.UNAVAILABLE -> R.string.minute_purchases_unavailable
    MinutePurchaseNotice.SIGN_IN_REQUIRED -> R.string.minute_purchases_sign_in_again
    MinutePurchaseNotice.PRICE_CHANGED -> R.string.minute_purchases_price_changed
    MinutePurchaseNotice.CANCELED -> R.string.minute_purchases_canceled
    MinutePurchaseNotice.PENDING -> R.string.minute_purchases_pending
    MinutePurchaseNotice.VERIFYING -> R.string.minute_purchases_checking
    MinutePurchaseNotice.ADDED -> R.string.minute_purchases_added
    MinutePurchaseNotice.REVERSED -> R.string.minute_purchases_reversed
    MinutePurchaseNotice.VERIFICATION_FAILED -> R.string.minute_purchases_verification_failed
    MinutePurchaseNotice.SANDBOX_RECORDED -> R.string.tokens_sandbox
}
