package com.nodeseek.app.domain.mail

import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.SoftwareAesGcmTokenCipher
import com.nodeseek.app.data.mail.VpsMailAutoIngestServiceImpl
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.fleet.FleetAssetType
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DomainMailParserAndIngestTest {

    private lateinit var tempDir: File
    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("domain_mail_ingest_test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `parses Porkbun, Cloudflare Registrar, Namecheap, Spaceship, Dynadot, Aliyun, and Tencent domain renewal mails`() {
        val porkbun = VpsBillingMailParser.parse(
            subject = "Porkbun - Domain Renewal Confirmation for anode-lab.dev",
            bodyText = """
                Thank you for renewing your domain!
                Domain Name: anode-lab.dev
                Billing Cycle: 1 Year
                Amount Paid: $10.18 USD
                Next Due Date: 2028-05-18
                Invoice #PB-99412
            """.trimIndent(),
            fromName = "Porkbun",
            fromAddress = "support@porkbun.com",
        )
        assertNotNull(porkbun)
        assertEquals(VpsMailType.RENEWAL, porkbun!!.mailType)
        assertEquals(FleetAssetType.DOMAIN_CERT, porkbun.assetType)
        assertEquals("anode-lab.dev", porkbun.domainName)
        assertEquals("Porkbun", porkbun.registrarName)
        assertEquals("10.18", porkbun.billingAmount)
        assertEquals(ValuationCurrency.USD, porkbun.currency)
        assertEquals(BillingCyclePreset.ANNUAL, porkbun.billingCycle)
        assertEquals("2028-05-18", porkbun.nextDueDateIso)

        val cloudflare = VpsBillingMailParser.parse(
            subject = "[Cloudflare] Domain Renewal Receipt - edge-node.org",
            bodyHtml = """
                <table>
                  <tr><td>Domain</td><td>edge-node.org</td></tr>
                  <tr><td>Registrar</td><td>Cloudflare Registrar</td></tr>
                  <tr><td>Total Paid</td><td>$10.44 USD</td></tr>
                  <tr><td>Expiration Date</td><td>2027-11-01</td></tr>
                </table>
            """.trimIndent(),
            fromName = "Cloudflare",
            fromAddress = "billing@cloudflare.com",
        )
        assertNotNull(cloudflare)
        assertEquals(VpsMailType.RENEWAL, cloudflare!!.mailType)
        assertEquals(FleetAssetType.DOMAIN_CERT, cloudflare.assetType)
        assertEquals("edge-node.org", cloudflare.domainName)
        assertEquals("Cloudflare", cloudflare.registrarName)
        assertEquals("10.44", cloudflare.billingAmount)

        val namecheap = VpsBillingMailParser.parse(
            subject = "Renewal Reminder: Your domain myblog.xyz is expiring soon",
            bodyText = """
                Domain: myblog.xyz
                Expiration Date: 2026-12-20
                Renewal Amount: $14.98 USD
                Billing Cycle: Annual
            """.trimIndent(),
            fromName = "Namecheap Support",
            fromAddress = "support@namecheap.com",
        )
        assertNotNull(namecheap)
        assertEquals(VpsMailType.INVOICE, namecheap!!.mailType)
        assertEquals(FleetAssetType.DOMAIN_CERT, namecheap.assetType)
        assertEquals("myblog.xyz", namecheap.domainName)
        assertEquals("Namecheap", namecheap.registrarName)

        val spaceship = VpsBillingMailParser.parse(
            subject = "Spaceship - Domain Renewal Confirmation for fastship.io",
            bodyText = "Domain: fastship.io\nAmount Paid: $32.00 USD\nExpiry Date: 2028-03-01",
            fromAddress = "billing@spaceship.com",
        )
        assertNotNull(spaceship)
        assertEquals("Spaceship", spaceship!!.registrarName)
        assertEquals("fastship.io", spaceship.domainName)

        val dynadot = VpsBillingMailParser.parse(
            subject = "Dynadot Payment Receipt - Domain Renewal",
            bodyText = "Domain Name: dynadomain.cc\nTotal: $9.99 USD\nNext Due Date: 2027-09-15",
            fromAddress = "orders@dynadot.com",
        )
        assertNotNull(dynadot)
        assertEquals("Dynadot", dynadot!!.registrarName)
        assertEquals("dynadomain.cc", dynadot.domainName)

        val aliyun = VpsBillingMailParser.parse(
            subject = "【阿里云万网】域名续费成功通知",
            bodyText = """
                尊敬的用户，您的域名续费成功：
                域名：cloudnote.cn
                付款金额：¥39.00 CNY
                续费周期：1年
                到期日期：2028年04月12日
            """.trimIndent(),
            fromName = "阿里云",
            fromAddress = "system@aliyun.com",
        )
        assertNotNull(aliyun)
        assertEquals(VpsMailType.RENEWAL, aliyun!!.mailType)
        assertEquals(FleetAssetType.DOMAIN_CERT, aliyun.assetType)
        assertEquals("cloudnote.cn", aliyun.domainName)
        assertEquals("阿里云万网", aliyun.registrarName)
        assertEquals("39.00", aliyun.billingAmount)
        assertEquals(ValuationCurrency.CNY, aliyun.currency)
        assertEquals("2028-04-12", aliyun.nextDueDateIso)

        val tencent = VpsBillingMailParser.parse(
            subject = "腾讯云 DNSPod 域名续费提醒",
            bodyText = "续费域名：devtool.net\n应付金额：88.00元\n到期时间：2027-07-07",
            fromAddress = "notice@dnspod.cn",
        )
        assertNotNull(tencent)
        assertEquals(VpsMailType.INVOICE, tencent!!.mailType)
        assertEquals("devtool.net", tencent.domainName)
        assertEquals("腾讯云 DNSPod", tencent.registrarName)

        val hint = VpsBillingMailParser.parseSummaryHint(
            SeekMailSummary(
                id = 101L,
                mailboxId = 1L,
                fromName = "Porkbun",
                fromAddress = "support@porkbun.com",
                toAddress = "billing@example.net",
                subject = "Porkbun - Domain Renewal Confirmation for anode-lab.dev",
                snippet = "Domain Name: anode-lab.dev Amount Paid: $10.18 USD",
            ),
        )
        assertNotNull(hint)
        assertEquals("anode-lab.dev", hint!!.domainName)
        assertEquals(VpsMailType.RENEWAL, hint.mailType)
    }

    @Test
    fun `rejects non-billing Cloudflare alerts, OTP codes, and WHOIS contact verification notices`() {
        assertNull(
            VpsBillingMailParser.parse(
                subject = "Your Cloudflare verification code",
                bodyText = "Use code 482910 to verify your login.",
                fromAddress = "noreply@cloudflare.com",
            ),
        )
        assertNull(
            VpsBillingMailParser.parse(
                subject = "Cloudflare Workers usage alert",
                bodyText = "Your Workers script exceeded 80% of free daily requests.",
                fromAddress = "notifications@cloudflare.com",
            ),
        )
        assertNull(
            VpsBillingMailParser.parse(
                subject = "IMMEDIATE VERIFICATION REQUIRED for example.org",
                bodyText = "Please verify your WHOIS contact email address within 15 days.",
                fromAddress = "support@namecheap.com",
            ),
        )
    }

    @Test
    fun `VpsMailAutoIngestServiceImpl matches DOMAIN_CERT by domainName idempotently and skips SSH vault`() =
        runTest(testDispatcher) {
            val fleetCipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
            val fleetRepo = FleetRepositoryImpl(
                storeFile = File(tempDir, "fleet.json"),
                tokenCipher = fleetCipher,
                ioDispatcher = testDispatcher,
            )
            val sshVault = SshServerVault(
                vaultFile = File(tempDir, "ssh_vault.json"),
                cipher = InMemoryTestCipher(),
                ioDispatcher = testDispatcher,
            )
            val ingestService = VpsMailAutoIngestServiceImpl(
                fleetRepository = fleetRepo,
                sshVaultRepository = sshVault,
                stateFile = File(tempDir, "ingest_state.json"),
                ioDispatcher = testDispatcher,
                todayProvider = { LocalDate.of(2026, 5, 1) },
            )

            // 1. Pre-existing DOMAIN_CERT asset gets auto-updated idempotently by renewal email
            fleetRepo.upsertAsset(
                VpsAssetRecord(
                    id = "dom_existing",
                    nickname = "anode-lab.dev",
                    providerName = "Porkbun",
                    specsSummary = "",
                    region = "",
                    renewalAmount = "10.18",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2027-05-18",
                    assetType = FleetAssetType.DOMAIN_CERT,
                    domainName = "anode-lab.dev",
                    registrarName = "Porkbun",
                ),
            )

            val renewalSummary = SeekMailSummary(
                id = 501L,
                mailboxId = 1L,
                messageId = "<pb-501@porkbun.com>",
                fromName = "Porkbun",
                fromAddress = "support@porkbun.com",
                toAddress = "billing@example.net",
                subject = "Porkbun - Domain Renewal Confirmation for anode-lab.dev",
                snippet = "Domain Name: anode-lab.dev Amount Paid: $10.18 USD Next Due Date: 2028-05-18",
            )
            val firstPass = ingestService.processIncomingSummaries(listOf(renewalSummary))
            assertEquals(1, firstPass.size)
            assertEquals("2028-05-18", fleetRepo.assetsFlow.value.first { it.id == "dom_existing" }.expiryDateIso)

            // Re-running on same summary is idempotent (0 new updates)
            val secondPass = ingestService.processIncomingSummaries(listOf(renewalSummary))
            assertTrue(secondPass.isEmpty())

            // 2. Manual ingest of a new domain creates DOMAIN_CERT asset and NO bogus SSH server profile
            val newDomainParsed = VpsBillingMailParser.parse(
                subject = "[Cloudflare] Domain Renewal Receipt - brandnew.org",
                bodyText = "Domain: brandnew.org\nTotal Paid: $10.44 USD\nExpiration Date: 2027-11-01",
                fromAddress = "billing@cloudflare.com",
            )!!
            val outcome = ingestService.ingestMail(summary = null, parsed = newDomainParsed).getOrThrow()
            assertTrue(outcome.assetCreated)
            assertEquals(FleetAssetType.DOMAIN_CERT, outcome.asset.assetType)
            assertEquals("brandnew.org", outcome.asset.domainName)
            assertEquals("Cloudflare", outcome.asset.registrarName)
            assertNull(outcome.sshProfile)
            assertTrue(sshVault.serversFlow.value.isEmpty())
        }

    @Test
    fun `VpsBillingMailParser parses Expires date line and domain renewal does not hijack VPS mentioning domain`() =
        runTest(testDispatcher) {
            // 1. Parser handles "Expires:", "Expires On:", and "Expiration:"
            val spaceshipExpires = VpsBillingMailParser.parse(
                subject = "Spaceship - Domain Renewal Confirmation for orbit.dev",
                bodyText = "Domain: orbit.dev\nAmount Paid: $14.00 USD\nExpires: 2028-02-15",
                fromAddress = "billing@spaceship.com",
            )
            assertNotNull(spaceshipExpires)
            assertEquals("2028-02-15", spaceshipExpires!!.nextDueDateIso)
            assertEquals(FleetAssetType.DOMAIN_CERT, spaceshipExpires.assetType)

            val expiresOn = VpsBillingMailParser.parse(
                subject = "Porkbun - Domain Renewal Confirmation for launch.dev",
                bodyText = "Domain Name: launch.dev\nAmount Paid: $11.00 USD\nExpires On: 2029-03-20",
                fromAddress = "support@porkbun.com",
            )
            assertEquals("2029-03-20", expiresOn?.nextDueDateIso)

            // 2. Existing VPS asset mentioning example.com in notes & panelManageUrl must NEVER be hijacked
            val fleetCipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
            val fleetRepo = FleetRepositoryImpl(
                storeFile = File(tempDir, "fleet_isolation.json"),
                tokenCipher = fleetCipher,
                ioDispatcher = testDispatcher,
            )
            val sshVault = SshServerVault(
                vaultFile = File(tempDir, "ssh_vault_isolation.json"),
                cipher = InMemoryTestCipher(),
                ioDispatcher = testDispatcher,
            )
            val ingestService = VpsMailAutoIngestServiceImpl(
                fleetRepository = fleetRepo,
                sshVaultRepository = sshVault,
                stateFile = File(tempDir, "ingest_isolation.json"),
                ioDispatcher = testDispatcher,
                todayProvider = { LocalDate.of(2026, 5, 1) },
            )

            fleetRepo.upsertAsset(
                VpsAssetRecord(
                    id = "vps_mentions_domain",
                    nickname = "mybrand.com Web Node",
                    providerName = "DMIT",
                    specsSummary = "2C/2G",
                    region = "LAX",
                    renewalAmount = "49.90",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2027-01-10",
                    panelManageUrl = "https://panel.mybrand.com",
                    notes = "部署 mybrand.com 与 example.com 站点",
                    assetType = FleetAssetType.VPS,
                ),
            )

            val parsedDomainMail = VpsBillingMailParser.parse(
                subject = "Porkbun - Domain Renewal Confirmation for mybrand.com",
                bodyText = "Domain Name: mybrand.com\nAmount Paid: $10.18 USD\nExpires: 2028-02-15",
                fromAddress = "support@porkbun.com",
            )!!

            val domainSummary = SeekMailSummary(
                id = 9001L,
                mailboxId = 1L,
                messageId = "<pb-9001@porkbun.com>",
                fromName = "Porkbun",
                fromAddress = "support@porkbun.com",
                toAddress = "billing@example.net",
                subject = "Porkbun - Domain Renewal Confirmation for mybrand.com",
                snippet = "Domain Name: mybrand.com Amount Paid: $10.18 USD Expires: 2028-02-15",
            )
            val autoUpdates = ingestService.processIncomingSummaries(listOf(domainSummary))
            assertTrue("VPS asset must not be auto-updated by domain renewal email", autoUpdates.isEmpty())

            val outcome = ingestService.ingestMail(summary = domainSummary, parsed = parsedDomainMail).getOrThrow()
            assertTrue("Should create a brand-new DOMAIN_CERT asset rather than overwriting VPS", outcome.assetCreated)
            assertEquals(FleetAssetType.DOMAIN_CERT, outcome.asset.assetType)
            assertEquals("mybrand.com", outcome.asset.domainName)
            assertEquals("2028-02-15", outcome.asset.expiryDateIso)

            // Also verify direct ParsedVpsBillingMail with example.com never matches the VPS asset
            val directExampleParsed = parsedDomainMail.copy(
                domainName = "example.com",
                hostname = "example.com",
                productName = "example.com",
            )
            assertTrue(
                "matchAssetsForMail must never return a VPS asset for a DOMAIN_CERT mail",
                ingestService.matchAssetsForMail(directExampleParsed, fleetRepo.assetsFlow.value, emptyList()).isEmpty(),
            )

            val vpsAfter = fleetRepo.assetsFlow.value.first { it.id == "vps_mentions_domain" }
            assertEquals(FleetAssetType.VPS, vpsAfter.assetType)
            assertFalse(vpsAfter.isDomainAsset)
            assertEquals("2027-01-10", vpsAfter.expiryDateIso)
            assertEquals("49.90", vpsAfter.renewalAmount)
            assertEquals("DMIT", vpsAfter.providerName)
            assertNull(vpsAfter.domainName)
        }
}
