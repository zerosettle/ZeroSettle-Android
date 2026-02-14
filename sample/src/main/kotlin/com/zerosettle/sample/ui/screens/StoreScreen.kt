package com.zerosettle.sample.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Diamond
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zerosettle.sample.SampleAppState
import com.zerosettle.sample.ui.components.PaymentFooter
import com.zerosettle.sample.ui.components.PaymentMethod
import com.zerosettle.sample.ui.components.ProductCard
import com.zerosettle.sample.ui.components.getProductVisuals
import com.zerosettle.sample.ui.theme.Cyan
import com.zerosettle.sample.ui.theme.Green
import com.zerosettle.sample.ui.theme.Indigo
import com.zerosettle.sample.ui.theme.Orange
import com.zerosettle.sample.ui.theme.Purple
import com.zerosettle.sdk.model.ZSProduct
import com.zerosettle.sdk.model.ZSProductType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StoreScreen(
    appState: SampleAppState,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var selectedProduct by remember { mutableStateOf<ZSProduct?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var processingMethod by remember { mutableStateOf<PaymentMethod?>(null) }

    val products = appState.products
    val isLoading = appState.isLoadingProducts
    val errorMessage = appState.productError

    val consumables = products.filter { it.type == ZSProductType.CONSUMABLE }
    val nonConsumables = products.filter { it.type == ZSProductType.NON_CONSUMABLE }
    val subscriptions = products.filter {
        it.type == ZSProductType.AUTO_RENEWABLE_SUBSCRIPTION ||
            it.type == ZSProductType.NON_RENEWING_SUBSCRIPTION
    }

    // Background gradient matching StoreFront
    val bgGradient = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Indigo.copy(alpha = 0.12f),
            0.35f to Cyan.copy(alpha = 0.06f),
            0.55f to MaterialTheme.colorScheme.background,
        ),
    )

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("Store") },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                ),
            )
        },
        bottomBar = {
            if (!isLoading && products.isNotEmpty()) {
                PaymentFooter(
                    selectedProduct = selectedProduct,
                    isProcessing = isProcessing,
                    processingMethod = processingMethod,
                    isWebCheckoutEnabled = appState.isWebCheckoutEnabled,
                    onPlayStorePurchase = {
                        val product = selectedProduct ?: return@PaymentFooter
                        isProcessing = true
                        processingMethod = PaymentMethod.PLAY_STORE
                        scope.launch {
                            try {
                                appState.purchaseViaPlayStore(product.id)
                                snackbarHostState.showSnackbar("Play Store purchase complete!")
                                selectedProduct = null
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Error: ${e.message}")
                            } finally {
                                isProcessing = false
                                processingMethod = null
                            }
                        }
                    },
                    onWebCheckoutPurchase = {
                        val product = selectedProduct ?: return@PaymentFooter
                        isProcessing = true
                        processingMethod = PaymentMethod.WEB_CHECKOUT
                        scope.launch {
                            try {
                                appState.purchaseViaWeb(product.id)
                                snackbarHostState.showSnackbar("Web checkout complete!")
                                selectedProduct = null
                            } catch (e: Exception) {
                                snackbarHostState.showSnackbar("Error: ${e.message}")
                            } finally {
                                isProcessing = false
                                processingMethod = null
                            }
                        }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(bgGradient) },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                // Header
                StoreHeader()

                when {
                    isLoading -> LoadingSection()
                    errorMessage != null -> ErrorSection(
                        error = errorMessage,
                        onRetry = { scope.launch { appState.fetchProducts() } },
                    )
                    products.isEmpty() -> EmptySection()
                    else -> {
                        if (consumables.isNotEmpty()) {
                            ProductSection(
                                title = "Gem Bundles",
                                icon = Icons.Filled.Star,
                                iconColor = Cyan,
                                products = consumables,
                                selectedProductId = selectedProduct?.id,
                                onProductSelected = { product ->
                                    selectedProduct = if (selectedProduct?.id == product.id) null else product
                                },
                            )
                        }
                        if (nonConsumables.isNotEmpty()) {
                            ProductSection(
                                title = "Permanent Unlocks",
                                icon = Icons.Filled.LockOpen,
                                iconColor = Green,
                                products = nonConsumables,
                                selectedProductId = selectedProduct?.id,
                                onProductSelected = { product ->
                                    selectedProduct = if (selectedProduct?.id == product.id) null else product
                                },
                            )
                        }
                        if (subscriptions.isNotEmpty()) {
                            ProductSection(
                                title = "Premium",
                                icon = Icons.Filled.WorkspacePremium,
                                iconColor = Orange,
                                products = subscriptions,
                                selectedProductId = selectedProduct?.id,
                                onProductSelected = { product ->
                                    selectedProduct = if (selectedProduct?.id == product.id) null else product
                                },
                            )
                        }
                    }
                }

                // Bottom spacer for footer
                Spacer(Modifier.height(100.dp))
            }
        }
    }
}

@Composable
private fun StoreHeader() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Glow effect
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(Purple.copy(alpha = 0.3f), Color.Transparent),
                        ),
                        CircleShape,
                    ),
            )
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(Indigo, Purple),
                        ),
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Diamond,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = Color.White,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "Power Up",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Grab gems or go premium",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProductSection(
    title: String,
    icon: ImageVector,
    iconColor: Color,
    products: List<ZSProduct>,
    selectedProductId: String?,
    onProductSelected: (ZSProduct) -> Unit,
) {
    var consumableIndex = 0
    var subscriptionIndex = 0

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = iconColor,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }

        products.forEach { product ->
            val index = when (product.type) {
                ZSProductType.CONSUMABLE, ZSProductType.NON_CONSUMABLE -> consumableIndex++
                else -> subscriptionIndex++
            }
            val visuals = getProductVisuals(product, index)
            ProductCard(
                product = product,
                visuals = visuals,
                isSelected = selectedProductId == product.id,
                onClick = { onProductSelected(product) },
            )
        }
    }
}

@Composable
private fun LoadingSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Text(
            text = "Loading products...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorSection(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = Orange,
        )
        Text(
            text = "Failed to Load Products",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = error,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
        ) {
            Text("Try Again")
        }
    }
}

@Composable
private fun EmptySection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.ShoppingBag,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "No Products Available",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Check back later for new offerings",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
