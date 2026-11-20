package com.oneproxyclick.app.pro

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.oneproxyclick.app.BuildConfig
import com.oneproxyclick.core.common.pro.Distribution
import com.oneproxyclick.core.common.pro.ProFeatureManager
import com.oneproxyclick.core.common.pro.PurchaseState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Play flavor: advanced features unlock with the one-time in-app product [BuildConfig.PRO_PRODUCT_ID]. */
object ProFeatures {
    fun create(context: Context): ProFeatureManager = PlayBillingProManager(context.applicationContext)
}

/**
 * Google Play Billing (v7) for a single non-consumable `inapp` product (lifetime Pro).
 *
 * - Entitlement is cached in SharedPreferences so Pro works offline / before the store answers,
 *   and is re-validated against `queryPurchasesAsync` on every [connect] (refunds revoke it).
 * - Purchases are acknowledged (otherwise Google refunds them after 3 days).
 * - Pending purchases unlock only once they reach PURCHASED.
 *
 * Production note: verify `purchase.originalJson`/signature on a backend before trusting it in
 * high-value contexts; client-side checks can be bypassed on rooted devices.
 */
private class PlayBillingProManager(private val context: Context) : ProFeatureManager, PurchasesUpdatedListener {

    private val productId = BuildConfig.PRO_PRODUCT_ID
    private val prefs = context.getSharedPreferences("pro_entitlement", Context.MODE_PRIVATE)

    override val distribution = Distribution.PLAY

    private val _isPro = MutableStateFlow(prefs.getBoolean(KEY_OWNED, false))
    override val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

    private val _purchaseState = MutableStateFlow<PurchaseState>(PurchaseState.Connecting)
    override val purchaseState: StateFlow<PurchaseState> = _purchaseState.asStateFlow()

    @Volatile private var productDetails: ProductDetails? = null

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    override fun connect() {
        if (client.isReady) {
            refresh()
            return
        }
        if (_purchaseState.value !is PurchaseState.Owned) _purchaseState.value = PurchaseState.Connecting
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    refresh()
                } else if (!_isPro.value) {
                    _purchaseState.value = PurchaseState.Unavailable(describe(result))
                }
            }

            override fun onBillingServiceDisconnected() {
                // Next connect()/purchase() reconnects; keep the cached entitlement meanwhile.
                if (!_isPro.value) _purchaseState.value = PurchaseState.Unavailable("Google Play disconnected")
            }
        })
    }

    override fun restore() = connect()

    override fun purchase(activity: Activity) {
        val details = productDetails
        if (!client.isReady || details == null) {
            connect()
            _purchaseState.value = PurchaseState.Error("Store not ready yet, try again in a moment")
            return
        }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).build()),
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _purchaseState.value = PurchaseState.Error(describe(result))
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> handlePurchases(purchases.orEmpty(), authoritative = false)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> refresh()
            BillingClient.BillingResponseCode.USER_CANCELED -> showAvailability()
            else -> _purchaseState.value = PurchaseState.Error(describe(result))
        }
    }

    private fun refresh() {
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        ) { result, purchases ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(purchases, authoritative = true)
            }
        }
        val query = QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                ),
            )
            .build()
        client.queryProductDetailsAsync(query) { result, detailsList ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails = detailsList.firstOrNull { it.productId == productId }
                showAvailability()
            } else if (!_isPro.value) {
                _purchaseState.value = PurchaseState.Unavailable(describe(result))
            }
        }
    }

    /**
     * @param authoritative true for the full owned-purchases list (may revoke after a refund);
     *        false for an incremental purchase update (only ever grants).
     */
    private fun handlePurchases(purchases: List<Purchase>, authoritative: Boolean) {
        val mine = purchases.filter { productId in it.products }
        val owned = mine.firstOrNull { it.purchaseState == Purchase.PurchaseState.PURCHASED }
        val pending = mine.any { it.purchaseState == Purchase.PurchaseState.PENDING }

        if (owned != null) {
            if (!owned.isAcknowledged) {
                client.acknowledgePurchase(
                    AcknowledgePurchaseParams.newBuilder().setPurchaseToken(owned.purchaseToken).build(),
                ) { /* re-acknowledged on the next refresh if this fails */ }
            }
            setOwned(true)
            _purchaseState.value = PurchaseState.Owned
        } else {
            if (authoritative) setOwned(false)
            if (pending) _purchaseState.value = PurchaseState.Pending else showAvailability()
        }
    }

    private fun showAvailability() {
        if (_isPro.value) {
            _purchaseState.value = PurchaseState.Owned
            return
        }
        val price = productDetails?.oneTimePurchaseOfferDetails?.formattedPrice
        _purchaseState.value = if (price != null) PurchaseState.Available(price)
        else PurchaseState.Unavailable("Product $productId not found in Play Console")
    }

    private fun setOwned(owned: Boolean) {
        _isPro.value = owned
        prefs.edit().putBoolean(KEY_OWNED, owned).apply()
    }

    private fun describe(result: BillingResult): String =
        result.debugMessage.ifBlank { "Billing error ${result.responseCode}" }

    private companion object {
        const val KEY_OWNED = "owned"
    }
}
