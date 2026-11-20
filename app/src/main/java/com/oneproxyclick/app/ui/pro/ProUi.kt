package com.oneproxyclick.app.ui.pro

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.oneproxyclick.app.R
import com.oneproxyclick.app.ui.theme.OpcColors
import com.oneproxyclick.core.common.pro.Distribution
import com.oneproxyclick.core.common.pro.Pro
import com.oneproxyclick.core.common.pro.ProFeature
import com.oneproxyclick.core.common.pro.PurchaseState

/** Opens the "OneProxyClick Pro" dialog from anywhere in the tree (provided by AppRoot). */
val LocalShowProUpsell = staticCompositionLocalOf<() -> Unit> { {} }

/** Reactive feature check (recomposes when the purchase state changes). */
@Composable
fun rememberProUnlocked(feature: ProFeature): Boolean {
    val pro by Pro.manager.isPro.collectAsState()
    return pro || Pro.isUnlocked(feature)
}

@Composable
fun ProBadge(modifier: Modifier = Modifier) {
    Text(
        stringResource(R.string.pro_badge),
        color = MaterialTheme.colorScheme.onPrimary,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        modifier = modifier
            .background(OpcColors.Orange, RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** Inline notice shown in place of a locked feature's controls. */
@Composable
fun ProLockedRow(featureName: String, modifier: Modifier = Modifier) {
    val showUpsell = LocalShowProUpsell.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable { showUpsell() }
            .padding(vertical = 6.dp),
    ) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = OpcColors.Orange)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.pro_locked, featureName), fontSize = 13.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = showUpsell) { Text(stringResource(R.string.pro_unlock)) }
    }
}

/** Settings card: status, what Pro unlocks, what's always free, buy / restore. */
@Composable
fun ProCard(modifier: Modifier = Modifier) {
    val manager = Pro.manager
    val state by manager.purchaseState.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { manager.connect() }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.pro_title), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                ProBadge()
            }
            if (manager.distribution == Distribution.FOSS) {
                Text(stringResource(R.string.pro_foss), color = OpcColors.Green, fontSize = 13.sp)
                return@Column
            }
            Text(stringResource(R.string.pro_subtitle), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Text(stringResource(R.string.pro_features_header), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            listOf(R.string.pro_f_live, R.string.pro_f_bridge, R.string.pro_f_tabs, R.string.pro_f_rules).forEach {
                Text("★ " + stringResource(it), fontSize = 12.sp)
            }
            Text(stringResource(R.string.pro_free_header), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            Text(stringResource(R.string.pro_free_list), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

            when (val s = state) {
                PurchaseState.Owned -> Text(stringResource(R.string.pro_owned), color = OpcColors.Green)
                PurchaseState.Pending -> Text(stringResource(R.string.pro_pending), color = OpcColors.Yellow, fontSize = 12.sp)
                PurchaseState.Connecting -> Text(stringResource(R.string.pro_connecting), fontSize = 12.sp)
                is PurchaseState.Unavailable -> Text(stringResource(R.string.pro_unavailable, s.reason), color = OpcColors.Yellow, fontSize = 12.sp)
                is PurchaseState.Error -> Text(stringResource(R.string.pro_error, s.message), color = OpcColors.Red, fontSize = 12.sp)
                is PurchaseState.Available, PurchaseState.NotRequired -> Unit
            }
            if (state != PurchaseState.Owned) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val price = (state as? PurchaseState.Available)?.price
                    Button(
                        enabled = price != null,
                        onClick = { context.findActivity()?.let { manager.purchase(it) } },
                    ) { Text(stringResource(R.string.pro_buy, price ?: "…")) }
                    TextButton(onClick = { manager.restore() }) { Text(stringResource(R.string.pro_restore)) }
                }
            }
        }
    }
}

@Composable
fun ProUpsellDialog(onDismiss: () -> Unit) {
    val isPro by Pro.manager.isPro.collectAsState()
    if (isPro) {
        // Purchase completed while the dialog was open.
        LaunchedEffect(Unit) { onDismiss() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.pro_close)) } },
        text = { ProCard() },
    )
}

/** Unwraps (localized) ContextWrappers down to the hosting Activity, needed by the billing flow. */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}
