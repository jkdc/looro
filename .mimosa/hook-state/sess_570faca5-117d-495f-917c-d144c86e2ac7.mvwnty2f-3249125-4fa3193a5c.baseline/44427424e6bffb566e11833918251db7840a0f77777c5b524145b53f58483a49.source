package com.jkdc.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.jkdc.app.R
import com.jkdc.app.core.MinuteBalance
import java.text.NumberFormat
import java.math.RoundingMode

@Composable
internal fun PaidBalanceText(balance: MinuteBalance) {
    val subscription = balance.subscription
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (subscription != null && subscription.tier != "free") {
            Text(stringResource(R.string.sub_plan_label, subscriptionTierName(subscription.tier),
                if (subscription.trialing) stringResource(R.string.sub_trial_active) else ""),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("subscription-tier"))
        }
        Text(stringResource(R.string.minutes_remaining_estimate, formatMinutes(balance.availableMilliseconds)),
            style = MaterialTheme.typography.titleMedium,
            color = if (balance.warningLevel >= 2) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag("minutes-remaining"))
        if (balance.warningLevel == 1) Text(stringResource(R.string.minutes_low_warning),
            style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
        if (balance.warningLevel == 2) Text(stringResource(R.string.minutes_critical_warning),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (balance.warningLevel == 3) Text(stringResource(R.string.minutes_exhausted_upsell),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("minutes-exhausted"))
        val paid = balance.paid ?: return@Column
        if (paid.availableNanoUSD == "0") return@Column
        Text(if (paid.estimatedMilliseconds < 60_000) stringResource(R.string.paid_balance_small)
            else stringResource(R.string.paid_balance_estimate, NumberFormat.getNumberInstance().apply {
                maximumFractionDigits = 1; roundingMode = RoundingMode.DOWN
            }.format(paid.estimatedMilliseconds / 60_000.0)),
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("paid-minute-estimate"))
        Text(stringResource(R.string.paid_balance_detail), style = MaterialTheme.typography.bodySmall,
            color = AppColors.Secondary)
        if (balance.availableMilliseconds > 0) Text(stringResource(R.string.paid_balance_free_first),
            style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
        subscription?.drip?.takeIf { it.enabled && it.minutesPerMonth > 0 }?.let {
            Text(stringResource(R.string.sub_drip_note, it.minutesPerMonth),
                style = MaterialTheme.typography.bodySmall, color = AppColors.Secondary)
        }
    }
}

internal fun subscriptionTierName(tier: String): Int = when (tier) {
    "pro" -> R.string.sub_tier_pro
    "standard" -> R.string.sub_tier_standard
    else -> R.string.sub_tier_free
}

private fun formatMinutes(milliseconds: Long): String =
    NumberFormat.getNumberInstance().apply { maximumFractionDigits = 1; roundingMode = RoundingMode.DOWN }
        .format(milliseconds / 60_000.0)
