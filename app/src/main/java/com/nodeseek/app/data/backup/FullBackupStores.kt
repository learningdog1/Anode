package com.nodeseek.app.data.backup

import com.nodeseek.app.data.activity.ActivityPreferencesStore
import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.data.overseas.OverseasPromoPreferencesStore
import com.nodeseek.app.data.valuation.ExchangeRateRepository
import com.nodeseek.app.domain.fleet.FleetRepository
import com.nodeseek.app.domain.mail.SeekMailRepository
import com.nodeseek.app.domain.overseas.OverseasIntelRepository
import com.nodeseek.app.domain.restock.RestockRepository
import com.nodeseek.app.domain.ssh.SshVaultRepository
import com.nodeseek.app.domain.trust.OfficialTradeRepository
import com.nodeseek.app.domain.trust.SellerTrustRepository

/**
 * Optional full-app store bindings powering Encrypted Full Backup v2.
 *
 * All properties default to `null` so v1-only unit tests and lightweight preview containers
 * continue instantiating [BackupManagerImpl] without extra boilerplate.
 */
data class FullBackupV2Stores(
    val fleetRepository: FleetRepository? = null,
    val restockRepository: RestockRepository? = null,
    val sshVaultRepository: SshVaultRepository? = null,
    val seekMailRepository: SeekMailRepository? = null,
    val overseasIntelRepository: OverseasIntelRepository? = null,
    val overseasPromoPreferencesStore: OverseasPromoPreferencesStore? = null,
    val activityPreferencesStore: ActivityPreferencesStore? = null,
    val sellerTrustRepository: SellerTrustRepository? = null,
    val officialTradeRepository: OfficialTradeRepository? = null,
    val netBenchHistoryStore: NetBenchHistoryStore? = null,
    val exchangeRateRepository: ExchangeRateRepository? = null,
)
