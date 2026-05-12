package com.zerosettle.sdk.internal

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Centralised DataStore handles. Each store is created at most once per process.
 *
 * Three named stores keep concerns separated and let `clear()` operate per-store:
 *  - `zerosettle_identity` — anonymous UUID, active userId, customer metadata.
 *  - `zerosettle_sync_queue` — pending Play purchase syncs (Task 28).
 *  - `zerosettle_offer_dismissals` — per-userId offer dismissal flags (Task 41).
 */
internal val Context.identityDataStore: DataStore<Preferences> by preferencesDataStore(name = "zerosettle_identity")
internal val Context.syncQueueDataStore: DataStore<Preferences> by preferencesDataStore(name = "zerosettle_sync_queue")
internal val Context.offerDismissalsDataStore: DataStore<Preferences> by preferencesDataStore(name = "zerosettle_offer_dismissals")
