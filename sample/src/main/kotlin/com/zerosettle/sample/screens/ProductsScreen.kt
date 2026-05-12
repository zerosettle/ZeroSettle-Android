package com.zerosettle.sample.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.collectAsState
import androidx.navigation.NavController
import com.zerosettle.sdk.ZeroSettle
import kotlinx.coroutines.launch

@Composable
fun ProductsScreen(nav: NavController) {
    val products by ZeroSettle.products.collectAsState()
    val activity = LocalContext.current as android.app.Activity
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { ZeroSettle.fetchProducts() }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { nav.navigate("entitlements") }) { Text("Entitlements") }
            TextButton(onClick = { nav.navigate("offers") }) { Text("Offers") }
            TextButton(onClick = { nav.navigate("pending") }) { Text("Pending") }
            TextButton(onClick = { nav.navigate("debug") }) { Text("Debug") }
        }
        Text("Products (${products.size})")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(products) { p ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(p.displayName)
                        Text(p.productDescription)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scope.launch { ZeroSettle.purchase(activity, p.id) } }) { Text("Buy (web)") }
                            Button(onClick = { scope.launch { ZeroSettle.purchaseViaPlayBilling(activity, p.id) } }) { Text("Buy (Play)") }
                        }
                    }
                }
            }
        }
    }
}
