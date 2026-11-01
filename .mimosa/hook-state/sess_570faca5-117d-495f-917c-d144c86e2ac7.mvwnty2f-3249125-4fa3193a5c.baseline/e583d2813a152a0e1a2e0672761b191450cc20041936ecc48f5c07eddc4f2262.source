package com.jkdc.app.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jkdc.app.R
import com.jkdc.app.core.PACK_TOKEN_KIND
import com.jkdc.app.core.PACK_UNLOCK_KIND
import com.jkdc.app.core.PurchaseChannel
import com.jkdc.app.core.MinutePack
import com.jkdc.app.core.MinutePurchaseNotice
import com.jkdc.app.core.MinutePurchaseState
import com.jkdc.app.core.tokenCreditText
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat

/** Render-only checkout surface. Prices and eligibility come from the purchase controller. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MinutePurchaseSheet(
    state: MinutePurchaseState,
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
    // The minutes sheet sells minutes (and their AI-value estimates) only; the
    // token unlock/pack cards belong to the dedicated TokensPurchaseSheet, which
    // filters the shared controller state the other way around.
    val minutePacks = state.packs.filter { !it.isPack }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = AppColors.Cream,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight(.90f).testTag("minute-purchase-sheet")) {
            SoftAnimatedBackground(Modifier.matchParentSize())
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    AppTextButton(onDismiss, Modifier.testTag("minute-purchase-close")) {
                        Text(stringResource(R.string.settings_done), color = AppColors.Secondary)
                    }
                }
                Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    AppOrb(modifier = Modifier.size(104.dp), energy = if (checking) .10f else 0f)
                    Text(stringResource(R.string.minute_purchases_title), style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                    Text(stringResource(R.string.minute_purchases_intro), style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.Secondary, textAlign = TextAlign.Center)
                    if (!needsSignIn) state.balance?.availableMilliseconds?.let { available ->
                        Surface(color = AppColors.Peach.copy(alpha = .70f), shape = RoundedCornerShape(50)) {
                            Text(minuteBalanceText(available), Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                .testTag("minute-purchase-balance"), style = MaterialTheme.typography.labelMedium, color = AppColors.Ink)
                        }
                    }
                    Text(stringResource(R.string.hosted_minimum_charge_disclosure), style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Secondary, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 480.dp)
                            .testTag("minute-purchase-minimum"))
                    if (!needsSignIn) state.balance?.let { PaidBalanceText(it) }
                    Column(Modifier.widthIn(max = 520.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        minutePacks.forEach { pack ->
                            MinutePackCard(pack, enabled = canChoose, onBuy = { onBuy(pack.sku) })
                        }
                        if (minutePacks.isEmpty()) Surface(color = Color.White.copy(alpha = .72f), shape = RoundedCornerShape(26.dp)) {
                            Text(stringResource(if (checking) R.string.minute_purchases_loading else R.string.minute_purchases_unavailable),
                                Modifier.fillMaxWidth().padding(24.dp).testTag("minute-purchase-empty"),
                                style = MaterialTheme.typography.bodyMedium, color = AppColors.Secondary, textAlign = TextAlign.Center)
                        }
                    }
                    if (needsSignIn) {
                        Text(stringResource(R.string.minute_purchases_sign_in_detail), style = MaterialTheme.typography.bodyMedium,
                            color = AppColors.Secondary, textAlign = TextAlign.Center)
                        Button(onClick = onSignIn, enabled = !checking && !accountBusy, shape = RoundedCornerShape(50),
                            modifier = Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(min = 54.dp).testTag("minute-purchase-sign-in")) {
                            Text(stringResource(R.string.minute_purchases_sign_in), textAlign = TextAlign.Center)
                        }
                    }
                    val statusText = when {
                        state.notice != null -> stringResource(state.notice.textResource())
                        checking -> stringResource(R.string.minute_purchases_loading)
                        state.purchaseInProgress -> stringResource(R.string.minute_purchases_opened)
                        else -> null
                    }
                    statusText?.let { text ->
                        Row(Modifier.widthIn(max = 520.dp).fillMaxWidth().testTag("minute-purchase-status")
                            .semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (checking) CircularProgressIndicator(Modifier.size(20.dp), color = AppColors.Ink, strokeWidth = 2.dp)
                            Text(text, style = MaterialTheme.typography.bodyMedium, color = AppColors.Secondary)
                        }
                    }
                    if (minutePacks.isNotEmpty()) Text(stringResource(R.string.minute_purchases_one_time),
                        style = MaterialTheme.typography.labelMedium, color = AppColors.Secondary, textAlign = TextAlign.Center)
                    Text(stringResource(if (state.channel == PurchaseChannel.STRIPE) R.string.minute_purchases_stripe_terms else R.string.minute_purchases_play_terms), style = MaterialTheme.typography.bodySmall,
                        color = AppColors.Secondary, textAlign = TextAlign.Center)
                    AppTextButton(onRefresh, enabled = !checking && !accountBusy,
                        modifier = Modifier.testTag("minute-purchase-refresh")) {
                        Text(stringResource(R.string.minute_purchases_check))
                    }
                }
            }
        }
    }
}

@Composable
internal fun MinutePackCard(pack: MinutePack, enabled: Boolean, onBuy: () -> Unit) {
    // Token-billing pack products (unlock / token pack) render their own copy;
    // minute and AI-value packs keep the minutes/estimate title.
    val title = if (pack.isPack) {
        stringResource(if (pack.entitlementKind == PACK_TOKEN_KIND) R.string.tokens_pack_card_title else R.string.tokens_unlock_card_title)
    } else {
        pack.aiValue?.let { value -> stringResource(R.string.paid_pack_estimate,
            NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1; roundingMode = RoundingMode.DOWN }
                .format(value.estimatedMilliseconds / 60_000.0)) }
            ?: pluralStringResource(R.plurals.minute_purchases_pack_minutes, pack.minutes, NumberFormat.getIntegerInstance().format(pack.minutes))
    }
    val detail = when {
        pack.entitlementKind == PACK_TOKEN_KIND -> stringResource(R.string.tokens_pack_card_credit, tokenCreditText(pack.creditNanoUSD))
        pack.entitlementKind == PACK_UNLOCK_KIND -> stringResource(R.string.tokens_unlock_card_detail)
        pack.aiValue != null -> stringResource(R.string.paid_balance_detail)
        else -> null
    }
    val action = stringResource(R.string.minute_purchases_pack_action, title, pack.formattedPrice)
    Surface(onClick = onBuy, enabled = enabled, shape = RoundedCornerShape(26.dp),
        color = Color.White.copy(alpha = if (enabled) .88f else .60f),
        border = BorderStroke(1.dp, Color.White), modifier = Modifier.fillMaxWidth().testTag("minute-purchase-pack-${pack.sku}")
            .semantics { contentDescription = action }) {
        // A vertical card preserves full localized prices and long text at larger font sizes.
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = AppColors.Ink)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary) }
            if (!pack.isPack) pack.aiValue?.let { value ->
                val quote = value.quote
                val money = NumberFormat.getCurrencyInstance().apply { currency = java.util.Currency.getInstance(quote.currency.uppercase(java.util.Locale.ROOT)) }
                val feePercent = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 2 }
                    .format(quote.serviceFeeBasisPoints / 100.0)
                val lines = listOf(stringResource(R.string.paid_ai_allocation) to quote.aiValueMinor,
                    stringResource(R.string.paid_app_fee, feePercent) to quote.serviceFeeMinor,
                    stringResource(R.string.paid_processing_fee) to quote.processingEstimateMinor,
                    stringResource(R.string.paid_processing_buffer) to quote.processingBufferMinor)
                lines.forEach { (label, amount) ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
                        Text(money.format(BigDecimal.valueOf(amount, quote.currencyExponent)), style = MaterialTheme.typography.bodySmall,
                            color = AppColors.Ink)
                    }
                }
            }
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

@Composable
internal fun minuteBalanceText(milliseconds: Long): String = when {
    milliseconds == 0L -> stringResource(R.string.minute_purchases_balance_empty)
    milliseconds < 60_000L -> stringResource(R.string.minute_purchases_balance_small)
    else -> stringResource(R.string.minute_purchases_balance, NumberFormat.getNumberInstance().apply {
        maximumFractionDigits = 1; roundingMode = RoundingMode.DOWN
    }.format(BigDecimal.valueOf(milliseconds).divide(BigDecimal.valueOf(60_000), 1, RoundingMode.DOWN)))
}

@StringRes
private fun MinutePurchaseNotice.textResource() = when (this) {
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
