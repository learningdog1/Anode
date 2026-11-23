package com.nodeseek.app.domain.mail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.nodeseek.app.data.fleet.FleetRepositoryImpl
import com.nodeseek.app.data.fleet.SoftwareAesGcmTokenCipher
import com.nodeseek.app.data.mail.SeekMailApiClient
import com.nodeseek.app.data.mail.SeekMailDiskCache
import com.nodeseek.app.data.mail.SeekMailRepositoryImpl
import com.nodeseek.app.data.mail.VpsMailAutoIngestServiceImpl
import com.nodeseek.app.data.repository.FakeSettingsRepository
import com.nodeseek.app.data.ssh.SshServerVault
import com.nodeseek.app.domain.fleet.VpsAssetRecord
import com.nodeseek.app.domain.ssh.InMemoryTestCipher
import com.nodeseek.app.domain.ssh.SshAuthMethod
import com.nodeseek.app.domain.valuation.BillingCyclePreset
import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.mail.SeekMailViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
class VpsBillingMailParserAndIngestTest {

    private lateinit var tempDir: File
    private lateinit var fleetCipher: SoftwareAesGcmTokenCipher
    private lateinit var sshCipher: InMemoryTestCipher

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        tempDir = Files.createTempDirectory("vps_mail_ingest_test").toFile()
        fleetCipher = SoftwareAesGcmTokenCipher(SoftwareAesGcmTokenCipher.randomKey())
        sshCipher = InMemoryTestCipher()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        tempDir.deleteRecursively()
    }

    // ── 1. English & Chinese WHMCS Provisioning / Renewal / Invoice Parsing ──

    @Test
    fun `parses English WHMCS provisioning email and ignores gateway netmask DNS and panel credentials`() {
        val subject = "[RackNerd] New Server Information"
        val body = """
            Dear Customer,

            Thank you for your order with us! Your new virtual server has now been set up.

            New Account Information
            Product/Service: 2GB KVM VPS - New Year Special
            Hostname: rn-lax-01.example.com
            Main IP: 198.51.100.88
            Gateway: 198.51.100.1
            Netmask: 255.255.255.0
            Nameserver 1: 8.8.8.8
            IPv6 Address: 2607:f130:0:ef::a1b2/64
            SSH Port: 22
            Root Password: Kw9#mP2${'$'}vL8!qR

            Control Panel URL: https://nerdvm.racknerd.com:5656
            Control Panel Username: vmuser882
            Control Panel Password: PanelOnlyPassword999

            First Payment Amount: ${'$'}10.98 USD
            Recurring Amount: ${'$'}10.98 USD
            Billing Cycle: Annually
            Next Due Date: 03/15/2027
        """.trimIndent()

        val parsed = VpsBillingMailParser.parse(
            subject = subject,
            bodyText = body,
            fromName = "RackNerd Billing",
            fromAddress = "billing@racknerd.com",
        )

        assertNotNull(parsed)
        parsed!!
        assertEquals(VpsMailType.PROVISIONING, parsed.mailType)
        assertEquals("RackNerd", parsed.providerName)
        assertEquals("2GB KVM VPS - New Year Special", parsed.productName)
        assertEquals("rn-lax-01.example.com", parsed.hostname)
        assertEquals("198.51.100.88", parsed.mainIpv4)
        assertEquals("2607:f130:0:ef::a1b2", parsed.mainIpv6)
        assertEquals(22, parsed.sshPort)
        assertEquals("root", parsed.sshUsername)
        assertEquals("Kw9#mP2\$vL8!qR", parsed.sshPassword)
        assertEquals("10.98", parsed.billingAmount)
        assertEquals(ValuationCurrency.USD, parsed.currency)
        assertEquals(BillingCyclePreset.ANNUAL, parsed.billingCycle)
        assertEquals("2027-03-15", parsed.nextDueDateIso)
        assertEquals("https://nerdvm.racknerd.com:5656", parsed.panelUrl)
        assertEquals("洛杉矶", parsed.region)
        assertTrue(parsed.hasSshCredentials)
    }

    @Test
    fun `parses Chinese WHMCS and IDCSmart provisioning email cleanly`() {
        val subject = "服务器开通通知 - 香港优化线路轻量云"
        val body = """
            尊敬的客户，您的产品已成功开通，连接信息如下：
            产品名称：HKG-CN2-Mini 2C2G
            主机名：hkg-mini-09.claw.local
            公网 IP：103.214.168.55
            IPv6地址：2400:cb00:2048:1::6814:155
            远程端口：51822
            系统账号：root
            初始密码：Claw@2026#HK
            月流量：1.5 TB
            续费金额：¥99.00 CNY
            计费周期：季付
            到期时间：2026年06月22日
        """.trimIndent()

        val parsed = VpsBillingMailParser.parse(
            subject = subject,
            bodyText = body,
            fromName = "ClawCloud 财务中心",
            fromAddress = "noreply@claw.cloud",
        )

        assertNotNull(parsed)
        parsed!!
        assertEquals(VpsMailType.PROVISIONING, parsed.mailType)
        assertEquals("ClawCloud", parsed.providerName)
        assertEquals("HKG-CN2-Mini 2C2G", parsed.productName)
        assertEquals("hkg-mini-09.claw.local", parsed.hostname)
        assertEquals("103.214.168.55", parsed.mainIpv4)
        assertEquals("2400:cb00:2048:1::6814:155", parsed.mainIpv6)
        assertEquals(51822, parsed.sshPort)
        assertEquals("root", parsed.sshUsername)
        assertEquals("Claw@2026#HK", parsed.sshPassword)
        assertEquals("99.00", parsed.billingAmount)
        assertEquals(ValuationCurrency.CNY, parsed.currency)
        assertEquals(BillingCyclePreset.QUARTERLY, parsed.billingCycle)
        assertEquals("2026-06-22", parsed.nextDueDateIso)
        assertEquals("香港", parsed.region)
        assertEquals(1536.0, parsed.monthlyTrafficQuotaGb ?: 0.0, 0.01)
    }

    @Test
    fun `preserves line breaks from HTML br p tr td and li tags so adjacent fields never glue together`() {
        val rawHtml = """
            <html>
              <body>
                <p>Dear User,<br>Dedicated/Cloud Server Information:<br/>
                IP Address: 45.142.122.77<br />
                SSH Port: 22022<br>
                Username: debian<br>
                Root Password: H7&amp;x9&lt;Pass&gt;!2</p>
                <table>
                  <tr><th>Package</th><td>Tokyo Nano Gen2</td></tr>
                  <tr><td>IPv6</td><td>2a01:4f8:c17:8899::1/64</td></tr>
                  <tr><td>Recurring Amount</td><td>€4,50 EUR</td></tr>
                  <tr><td>Billing Cycle</td><td>Semi-Annually</td></tr>
                  <tr><td>Next Due Date</td><td>25/12/2026</td></tr>
                </table>
                <ul>
                  <li>Location: Tokyo</li>
                  <li>Bandwidth: 1000 GB</li>
                </ul>
              </body>
            </html>
        """.trimIndent()

        // Simulate upstream message where bodyText was empty or flattened without line breaks
        val flattenedBrokenText = "Dear User,Dedicated/Cloud Server Information:IP Address: 45.142.122.77SSH Port: 22022Username: debianRoot Password: H7&x9<Pass>!2"

        val parsed = VpsBillingMailParser.parse(
            subject = "Dedicated/Cloud Server Information",
            bodyText = flattenedBrokenText,
            bodyHtml = rawHtml,
            fromName = "Billing",
            fromAddress = "billing@xtom.com",
        )

        assertNotNull(parsed)
        parsed!!
        assertEquals(VpsMailType.PROVISIONING, parsed.mailType)
        assertEquals("xTOM", parsed.providerName)
        assertEquals("Tokyo Nano Gen2", parsed.productName)
        assertEquals("45.142.122.77", parsed.mainIpv4)
        assertEquals("2a01:4f8:c17:8899::1", parsed.mainIpv6)
        assertEquals(22022, parsed.sshPort)
        assertEquals("debian", parsed.sshUsername)
        assertEquals("H7&x9<Pass>!2", parsed.sshPassword)
        assertEquals("4.50", parsed.billingAmount)
        assertEquals(ValuationCurrency.EUR, parsed.currency)
        assertEquals(BillingCyclePreset.SEMI_ANNUAL, parsed.billingCycle)
        assertEquals("2026-12-25", parsed.nextDueDateIso)
        assertEquals("东京", parsed.region)
    }

    @Test
    fun `parses WHMCS renewal confirmation and unpaid customer invoice accurately across currencies and cycles`() {
        val renewalMail = VpsBillingMailParser.parse(
            subject = "Invoice Payment Confirmation",
            bodyText = """
                Dear Client,
                This is a payment receipt for Invoice #849201 sent on March 15, 2026.

                LAX.Pro.MALIBU (lax-malibu.example.org - 154.17.22.88) (03/15/2026 - 03/15/2027) ${'$'}49.90 USD
                ------------------------------------------------------
                Total Paid: ${'$'}49.90 USD
                Due Date: 03/15/2026
                Status: Paid
            """.trimIndent(),
            fromName = "DMIT Inc.",
            fromAddress = "billing@dmit.io",
        )

        assertNotNull(renewalMail)
        renewalMail!!
        assertEquals(VpsMailType.RENEWAL, renewalMail.mailType)
        assertEquals("DMIT", renewalMail.providerName)
        assertEquals("849201", renewalMail.invoiceNumber)
        assertEquals("LAX.Pro.MALIBU", renewalMail.productName)
        assertEquals("lax-malibu.example.org", renewalMail.hostname)
        assertEquals("154.17.22.88", renewalMail.mainIpv4)
        assertEquals("49.90", renewalMail.billingAmount)
        assertEquals(ValuationCurrency.USD, renewalMail.currency)
        assertEquals(BillingCyclePreset.ANNUAL, renewalMail.billingCycle)
        // Must pick the renewed service period end date (2027-03-15), NOT the invoice's own payment due date (2026-03-15)!
        assertEquals("2027-03-15", renewalMail.nextDueDateIso)

        val unpaidInvoiceMail = VpsBillingMailParser.parse(
            subject = "Customer Invoice - Invoice #99231",
            bodyText = """
                Dear Customer,
                This is a notice that an invoice has been generated on November 20, 2026.
                Invoice #99231
                Amount Due: ${'$'}78.00 HKD
                Billing Cycle: Biennially
                Due Date: November 27, 2026
                Status: Unpaid

                Invoice Items:
                HKG-Standard-Biennial (hkg1.bagevm.local) (11/27/2026 - 11/27/2028) ${'$'}78.00 HKD
            """.trimIndent(),
            fromName = "BageVM",
            fromAddress = "billing@bagevm.com",
        )

        assertNotNull(unpaidInvoiceMail)
        unpaidInvoiceMail!!
        assertEquals(VpsMailType.INVOICE, unpaidInvoiceMail.mailType)
        assertEquals("BageVM", unpaidInvoiceMail.providerName)
        assertEquals("99231", unpaidInvoiceMail.invoiceNumber)
        assertEquals("78.00", unpaidInvoiceMail.billingAmount)
        assertEquals(ValuationCurrency.HKD, unpaidInvoiceMail.currency)
        assertEquals(BillingCyclePreset.BIENNIAL, unpaidInvoiceMail.billingCycle)
        assertEquals("2026-11-27", unpaidInvoiceMail.nextDueDateIso)

        val gbpTriennial = VpsBillingMailParser.parse(
            subject = "账单通知 - 待支付续费账单 #3012",
            bodyText = """
                套餐：UK-London-Micro
                应付金额：£18.50 GBP
                付款周期：三年付
                账单到期日：2027/01/09
            """.trimIndent(),
            fromName = "SpartanHost",
            fromAddress = "billing@spartanhost.net",
        )
        assertNotNull(gbpTriennial)
        assertEquals(VpsMailType.INVOICE, gbpTriennial!!.mailType)
        assertEquals("18.50", gbpTriennial.billingAmount)
        assertEquals(ValuationCurrency.GBP, gbpTriennial.currency)
        assertEquals(BillingCyclePreset.TRIENNIAL, gbpTriennial.billingCycle)
        assertEquals("2027-01-09", gbpTriennial.nextDueDateIso)

        val jpyMonthly = VpsBillingMailParser.parse(
            subject = "[V.PS] 账单已支付 #7712",
            bodyText = """
                您的续费订单已支付成功。
                产品：Tokyo-Starter
                实付金额：¥1,500 JPY
                周期：月付
                下次到期日：2026-12-18
            """.trimIndent(),
            fromName = "V.PS",
            fromAddress = "billing@v.ps",
        )
        assertNotNull(jpyMonthly)
        assertEquals(VpsMailType.RENEWAL, jpyMonthly!!.mailType)
        assertEquals("1500", jpyMonthly.billingAmount)
        assertEquals(ValuationCurrency.JPY, jpyMonthly.currency)
        assertEquals(BillingCyclePreset.MONTHLY, jpyMonthly.billingCycle)
        assertEquals("2026-12-18", jpyMonthly.nextDueDateIso)
    }

    @Test
    fun `supports all required date formats and ignores non-VPS OTP or forum emails`() {
        assertEquals("2027-03-15", VpsBillingMailParser.parseFlexibleDateIso("2027-03-15"))
        assertEquals("2027-03-05", VpsBillingMailParser.parseFlexibleDateIso("2027/3/5"))
        assertEquals("2027-03-25", VpsBillingMailParser.parseFlexibleDateIso("25/03/2027"))
        assertEquals("2027-03-25", VpsBillingMailParser.parseFlexibleDateIso("03/25/2027"))
        assertEquals("2027-04-02", VpsBillingMailParser.parseFlexibleDateIso("02/04/2027", preferDayFirst = true))
        assertEquals("2027-02-04", VpsBillingMailParser.parseFlexibleDateIso("02/04/2027", preferDayFirst = false))
        assertEquals("2027-11-22", VpsBillingMailParser.parseFlexibleDateIso("November 22, 2027"))
        assertEquals("2027-08-03", VpsBillingMailParser.parseFlexibleDateIso("Aug 3rd, 2027"))
        assertEquals("2027-09-14", VpsBillingMailParser.parseFlexibleDateIso("14 September 2027"))
        assertEquals("2027-06-08", VpsBillingMailParser.parseFlexibleDateIso("2027年06月08日"))

        // Pure OTP / forum emails must return null
        val otpMail = VpsBillingMailParser.parse(
            subject = "Your verification code is 839201",
            bodyText = "Use code 839201 to sign in to NodeSeek within 5 minutes.",
            fromName = "NodeSeek",
            fromAddress = "noreply@nodeseek.com",
        )
        assertNull(otpMail)

        val newsletterMail = VpsBillingMailParser.parse(
            subject = "Weekly Digest: Top Discussions",
            bodyText = "Check out what developers built this week.",
            fromName = "Newsletter",
            fromAddress = "digest@example.com",
        )
        assertNull(newsletterMail)

        // Summary hint recognizes provisioning, renewal, and invoice badges
        val summaryHint = VpsBillingMailParser.parseSummaryHint(
            SeekMailSummary(
                id = 10L,
                mailboxId = 1L,
                fromAddress = "billing@racknerd.com",
                fromName = "RackNerd",
                toAddress = "vps@seek.li",
                subject = "[RackNerd] New Server Information",
                snippet = "Main IP: 198.51.100.88 Root Password: secret",
            ),
        )
        assertNotNull(summaryHint)
        assertEquals(VpsMailType.PROVISIONING, summaryHint!!.mailType)
        assertEquals("198.51.100.88", summaryHint.primaryIp)
    }

    // ── 2. Idempotent Renewal Auto-Update Across Refreshes & Restarts ────────

    @Test
    fun `renewal email auto-updates matched server expiry idempotently across refreshes detail opens and restarts`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fleetStoreFile = File(tempDir, "fleet_store.json")
        val sshVaultFile = File(tempDir, "ssh_vault.json")
        val ingestStateFile = File(tempDir, VpsMailAutoIngestServiceImpl.STATE_FILE_NAME)

        val fleetRepo = FleetRepositoryImpl(
            storeFile = fleetStoreFile,
            tokenCipher = fleetCipher,
            ioDispatcher = dispatcher,
        )
        val sshVault = SshServerVault(
            vaultFile = sshVaultFile,
            cipher = sshCipher,
            ioDispatcher = dispatcher,
        )

        // Seed 3 servers in FleetRepository to test matching by IPv4, Hostname, and Provider+Product
        val assetByIp = fleetRepo.upsertAsset(
            VpsAssetRecord(
                id = "asset-ip",
                nickname = "主力洛杉矶小鸡",
                providerName = "RackNerd",
                specsSummary = "2C / 2G",
                region = "洛杉矶",
                renewalAmount = "10.98",
                currency = ValuationCurrency.USD,
                cyclePreset = BillingCyclePreset.ANNUAL,
                expiryDateIso = "2026-03-15",
                notes = "[SeekMail] IP: 198.51.100.88",
            ),
        )
        val assetByHost = fleetRepo.upsertAsset(
            VpsAssetRecord(
                id = "asset-host",
                nickname = "Tokyo Pro",
                providerName = "GreenCloud",
                specsSummary = "4C / 4G",
                region = "东京",
                renewalAmount = "25.00",
                currency = ValuationCurrency.USD,
                cyclePreset = BillingCyclePreset.SEMI_ANNUAL,
                expiryDateIso = "2026-04-10",
                notes = "Hostname: tyo-iij-09.green.local",
            ),
        )
        val assetByProviderProduct = fleetRepo.upsertAsset(
            VpsAssetRecord(
                id = "asset-prov-prod",
                nickname = "HKG.Pro.VICTORIA",
                providerName = "DMIT",
                specsSummary = "2C / 2G",
                region = "香港",
                renewalAmount = "39.90",
                currency = ValuationCurrency.USD,
                cyclePreset = BillingCyclePreset.QUARTERLY,
                expiryDateIso = "2026-05-01",
            ),
        )

        val service = VpsMailAutoIngestServiceImpl(
            fleetRepository = fleetRepo,
            sshVaultRepository = sshVault,
            stateFile = ingestStateFile,
            ioDispatcher = dispatcher,
            todayProvider = { LocalDate.of(2026, 3, 15) },
        )

        // 1) Renewal WITHOUT explicit new due date -> advances assetByHost ("2026-04-10") by SEMI_ANNUAL -> "2026-10-10"
        val cycleAdvanceSummary = SeekMailSummary(
            id = 501L,
            mailboxId = 1L,
            fromAddress = "billing@greencloudvps.com",
            fromName = "GreenCloud",
            toAddress = "me@seek.li",
            subject = "Invoice Payment Confirmation - Invoice #55102",
            snippet = "Payment received for tyo-iij-09.green.local. Amount Paid: $25.00 USD",
            messageId = "<inv-55102@greencloudvps.com>",
        )
        val firstRun = service.processIncomingSummaries(listOf(cycleAdvanceSummary))
        assertEquals(1, firstRun.size)
        assertEquals(assetByHost.id, firstRun.single().assetId)
        assertEquals("2026-04-10", firstRun.single().previousExpiryDateIso)
        assertEquals("2026-10-10", firstRun.single().newExpiryDateIso)
        assertEquals(
            "2026-10-10",
            fleetRepo.assetsFlow.value.first { it.id == assetByHost.id }.expiryDateIso,
        )

        // Re-running on inbox refresh AND opening mail detail MUST NOT double-advance!
        val secondRefreshRun = service.processIncomingSummaries(listOf(cycleAdvanceSummary))
        assertTrue(secondRefreshRun.isEmpty())
        val detailRun = service.processMailDetail(
            SeekMailDetail(
                summary = cycleAdvanceSummary,
                bodyText = "Payment received for Invoice #55102\nHostname: tyo-iij-09.green.local\nAmount Paid: $25.00 USD",
            ),
        )
        assertTrue(detailRun.isEmpty())
        assertEquals(
            "2026-10-10",
            fleetRepo.assetsFlow.value.first { it.id == assetByHost.id }.expiryDateIso,
        )

        // Cold restart simulation: create brand-new FleetRepositoryImpl + VpsMailAutoIngestServiceImpl on same files!
        val restartedFleetRepo = FleetRepositoryImpl(
            storeFile = fleetStoreFile,
            tokenCipher = fleetCipher,
            ioDispatcher = dispatcher,
        )
        val restartedService = VpsMailAutoIngestServiceImpl(
            fleetRepository = restartedFleetRepo,
            sshVaultRepository = sshVault,
            stateFile = ingestStateFile,
            ioDispatcher = dispatcher,
            todayProvider = { LocalDate.of(2026, 3, 15) },
        )
        val afterRestartRun = restartedService.processIncomingSummaries(listOf(cycleAdvanceSummary))
        assertTrue("Persisted renewal key must prevent double-advance after restart", afterRestartRun.isEmpty())
        assertEquals(
            "2026-10-10",
            restartedFleetRepo.assetsFlow.value.first { it.id == assetByHost.id }.expiryDateIso,
        )

        // 2) Renewal WITH explicit new due date matching by IPv4 -> sets exact date "2027-03-15"
        val explicitDateDetail = SeekMailDetail(
            summary = SeekMailSummary(
                id = 502L,
                mailboxId = 1L,
                fromAddress = "billing@racknerd.com",
                fromName = "RackNerd",
                toAddress = "me@seek.li",
                subject = "[RackNerd] 续费成功通知 #88120",
                snippet = "您的服务器 198.51.100.88 续费成功",
                messageId = "<inv-88120@racknerd.com>",
            ),
            bodyText = """
                账单 #88120 已支付成功！
                服务器 IP: 198.51.100.88
                实付金额: $10.98 USD
                新到期时间: 2027-03-20
            """.trimIndent(),
        )
        val explicitUpdate = restartedService.processMailDetail(explicitDateDetail)
        assertEquals(1, explicitUpdate.size)
        assertEquals(assetByIp.id, explicitUpdate.single().assetId)
        assertTrue(explicitUpdate.single().usedExplicitMailDate)
        assertEquals(
            "2027-03-20",
            restartedFleetRepo.assetsFlow.value.first { it.id == assetByIp.id }.expiryDateIso,
        )

        // 3) Renewal matching by Provider + Product name (`DMIT` + `HKG.Pro.VICTORIA`)
        val providerProductDetail = SeekMailDetail(
            summary = SeekMailSummary(
                id = 503L,
                mailboxId = 1L,
                fromAddress = "billing@dmit.io",
                fromName = "DMIT",
                toAddress = "me@seek.li",
                subject = "Payment Confirmation",
                snippet = "Invoice #91002 paid",
                messageId = "<inv-91002@dmit.io>",
            ),
            bodyText = """
                Invoice Payment Confirmation
                Invoice #91002
                Product/Service: HKG.Pro.VICTORIA
                Billing Cycle: Monthly
                Amount Paid: $39.90 USD
            """.trimIndent(),
        )
        // Parsed cycle in mail is Monthly, overriding asset's Quarterly -> 2026-05-01 + 1 month = 2026-06-01
        val provProdUpdate = restartedService.processMailDetail(providerProductDetail)
        assertEquals(1, provProdUpdate.size)
        assertEquals(assetByProviderProduct.id, provProdUpdate.single().assetId)
        assertEquals(
            "2026-06-01",
            restartedFleetRepo.assetsFlow.value.first { it.id == assetByProviderProduct.id }.expiryDateIso,
        )

        // 4) Unpaid INVOICE mail MUST NOT auto-advance expiry date!
        val unpaidInvoiceDetail = SeekMailDetail(
            summary = SeekMailSummary(
                id = 504L,
                mailboxId = 1L,
                fromAddress = "billing@dmit.io",
                fromName = "DMIT",
                toAddress = "me@seek.li",
                subject = "Customer Invoice - Invoice #99999",
                snippet = "Unpaid invoice generated",
                messageId = "<inv-99999@dmit.io>",
            ),
            bodyText = """
                Customer Invoice #99999
                Status: Unpaid
                Product/Service: HKG.Pro.VICTORIA
                Amount Due: $39.90 USD
                Due Date: 2026-06-01
            """.trimIndent(),
        )
        val unpaidResult = restartedService.processMailDetail(unpaidInvoiceDetail)
        assertTrue(unpaidResult.isEmpty())
        assertEquals(
            "2026-06-01",
            restartedFleetRepo.assetsFlow.value.first { it.id == assetByProviderProduct.id }.expiryDateIso,
        )
    }

    @Test
    fun `SeekMailRepositoryImpl getMails peeks unread renewal detail and auto-updates expiry while preserving unread state`() = runBlocking {
        val mockServer = MockWebServer()
        mockServer.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val fleetRepo = FleetRepositoryImpl(
                storeFile = File(tempDir, "repo_fleet.json"),
                tokenCipher = fleetCipher,
            )
            fleetRepo.upsertAsset(
                VpsAssetRecord(
                    id = "rn-1",
                    nickname = "RN SanJose",
                    providerName = "RackNerd",
                    specsSummary = "1C / 1G",
                    region = "圣何塞",
                    renewalAmount = "9.89",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2026-08-01",
                    notes = "[SeekMail] IP: 203.0.113.55",
                ),
            )
            val sshVault = SshServerVault(
                vaultFile = File(tempDir, "repo_ssh.json"),
                cipher = sshCipher,
            )
            val settingsRepo = FakeSettingsRepository()
            val apiKey = "sk_live_" + "c".repeat(64)
            settingsRepo.setSeekMailApiKey(apiKey)

            val apiClient = SeekMailApiClient(baseUrl = mockServer.url("/openapi/v1"))
            val diskCache = SeekMailDiskCache(cacheDir = File(tempDir, "mail_cache"), ioScope = scope)

            val autoIngest = VpsMailAutoIngestServiceImpl(
                fleetRepository = fleetRepo,
                sshVaultRepository = sshVault,
                stateFile = File(tempDir, "repo_ingest_state.json"),
                mailDetailPeeker = { mailId ->
                    diskCache.getMailDetail(apiKey, mailId)?.detail
                        ?: apiClient.getMailDetail(apiKey = apiKey, mailId = mailId)
                            .also { fresh -> diskCache.saveMailDetail(apiKey, mailId, fresh) }
                },
            )
            val mailRepo = SeekMailRepositoryImpl(
                apiClient = apiClient,
                settingsRepository = settingsRepo,
                diskCache = diskCache,
                externalScope = scope,
                vpsMailAutoIngestService = autoIngest,
            )

            // List response snippet is short (doesn't include the IP), while full detail body has IP + Next Due Date!
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "data": [
                            {
                              "id": 901,
                              "mailboxId": 1,
                              "fromAddress": "billing@racknerd.com",
                              "fromName": "RackNerd",
                              "toAddress": "me@seek.li",
                              "subject": "Invoice Payment Confirmation",
                              "bodyText": "Thank you for your payment on Invoice #77001.",
                              "bodyHtml": "",
                              "messageId": "<77001@racknerd.com>",
                              "isRead": false
                            }
                          ],
                          "pagination": {"limit": 20, "offset": 0, "total": 1}
                        }
                        """.trimIndent(),
                    ),
            )
            mockServer.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setBody(
                        """
                        {
                          "data": {
                            "mail": {
                              "id": 901,
                              "mailboxId": 1,
                              "fromAddress": "billing@racknerd.com",
                              "fromName": "RackNerd",
                              "toAddress": "me@seek.li",
                              "subject": "Invoice Payment Confirmation",
                              "bodyText": "Invoice #77001 Paid\nMain IP: 203.0.113.55\nNext Due Date: 2027-08-01\nAmount: $9.89 USD",
                              "bodyHtml": "",
                              "messageId": "<77001@racknerd.com>",
                              "isRead": false
                            },
                            "tags": []
                          }
                        }
                        """.trimIndent(),
                    ),
            )

            val page = mailRepo.getMails(forceRefresh = true).getOrThrow()
            assertFalse("Peeking detail for auto-ingest must not mark unread mail as read", page.items.single().isRead)
            assertTrue(901L !in mailRepo.locallyReadMailIds.value)
            val updated = withTimeout(5_000L) {
                fleetRepo.assetsFlow.first { it.singleOrNull()?.expiryDateIso == "2027-08-01" }
            }
            assertEquals("2027-08-01", updated.single().expiryDateIso)
        } finally {
            scope.cancel()
            mockServer.shutdown()
        }
    }

    // ── 3. Duplicate-Safe Ledger + SSH Vault One-Click Ingestion & ViewModel ─

    @Test
    fun `one-click ingest creates Ledger and encrypted SSH profile and updates cleanly without duplicates on re-tap`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fleetRepo = FleetRepositoryImpl(
            storeFile = File(tempDir, "ingest_fleet.json"),
            tokenCipher = fleetCipher,
            ioDispatcher = dispatcher,
        )
        val sshVault = SshServerVault(
            vaultFile = File(tempDir, "ingest_ssh.json"),
            cipher = sshCipher,
            ioDispatcher = dispatcher,
        )
        val ingestService = VpsMailAutoIngestServiceImpl(
            fleetRepository = fleetRepo,
            sshVaultRepository = sshVault,
            stateFile = File(tempDir, "ingest_state.json"),
            ioDispatcher = dispatcher,
            todayProvider = { LocalDate.of(2026, 5, 1) },
        )

        val provisioningSummary = SeekMailSummary(
            id = 701L,
            mailboxId = 2L,
            fromAddress = "noreply@claw.cloud",
            fromName = "ClawCloud",
            toAddress = "vps@seek.li",
            subject = "产品开通通知 - 日本东京优化云",
            snippet = "公网 IP: 45.88.190.12 Root 密码: InitPass#111",
            messageId = "<prov-701@claw.cloud>",
        )
        val provisioningParsed = VpsBillingMailParser.parse(
            subject = provisioningSummary.subject,
            bodyText = """
                您的产品已开通：
                套餐名称：TYO-Optimization-2G
                主机名：tyo-node-01.claw.local
                公网 IP：45.88.190.12
                SSH 端口：22022
                用户名：root
                Root 密码：InitPass#111
                续费价格：${'$'}24.00 USD / 年付
                到期时间：2027-05-01
            """.trimIndent(),
            fromName = provisioningSummary.fromName,
            fromAddress = provisioningSummary.fromAddress,
        )!!

        // First tap: creates both Ledger asset and SSH Vault server profile
        val firstOutcome = ingestService.ingestMail(provisioningSummary, provisioningParsed).getOrThrow()
        assertTrue(firstOutcome.assetCreated)
        assertTrue(firstOutcome.sshCreated)
        assertEquals(1, fleetRepo.assetsFlow.value.size)
        assertEquals(1, sshVault.serversFlow.value.size)

        val createdAsset = fleetRepo.assetsFlow.value.single()
        assertEquals("ClawCloud TYO-Optimization-2G", createdAsset.nickname)
        assertEquals("ClawCloud", createdAsset.providerName)
        assertEquals("24.00", createdAsset.renewalAmount)
        assertEquals(ValuationCurrency.USD, createdAsset.currency)
        assertEquals(BillingCyclePreset.ANNUAL, createdAsset.cyclePreset)
        assertEquals("2027-05-01", createdAsset.expiryDateIso)
        assertEquals("东京", createdAsset.region)

        val createdSsh = sshVault.serversFlow.value.single()
        assertEquals("45.88.190.12", createdSsh.host)
        assertEquals(22022, createdSsh.port)
        assertEquals("root", createdSsh.username)
        assertEquals(SshAuthMethod.PASSWORD, createdSsh.authMethod)
        assertEquals("日本", createdSsh.resolvedGroup)
        assertEquals("InitPass#111", sshVault.decryptCredentials(createdSsh).secret)

        // User customizes trafficResetDay in Fleet Insights; re-importing mail must preserve it!
        fleetRepo.upsertAsset(createdAsset.copy(trafficResetDay = 18))

        // Second tap (e.g. reinstall / password reset on same IP): updates in-place with ZERO duplicates!
        val reinstalledParsed = provisioningParsed.copy(
            sshPassword = "RotatedPass!999",
            sshPort = 33022,
        )
        val secondOutcome = ingestService.ingestMail(provisioningSummary, reinstalledParsed).getOrThrow()
        assertFalse("Re-importing same IP must update existing asset instead of duplicating", secondOutcome.assetCreated)
        assertFalse("Re-importing same IP must update existing SSH profile instead of duplicating", secondOutcome.sshCreated)
        assertEquals(1, fleetRepo.assetsFlow.value.size)
        assertEquals(1, sshVault.serversFlow.value.size)

        val updatedAsset = fleetRepo.assetsFlow.value.single()
        assertEquals(createdAsset.id, updatedAsset.id)
        assertEquals(18, updatedAsset.trafficResetDay)
        // Managed notes line must not duplicate on repeated imports
        assertEquals(1, updatedAsset.notes.lines().count { it.startsWith("[SeekMail]") })

        val updatedSsh = sshVault.serversFlow.value.single()
        assertEquals(createdSsh.id, updatedSsh.id)
        assertEquals(33022, updatedSsh.port)
        assertEquals("RotatedPass!999", sshVault.decryptCredentials(updatedSsh).secret)

        // Mail WITHOUT SSH credentials imports into Fleet Ledger only (SSH vault count stays 1)
        val invoiceOnlyParsed = VpsBillingMailParser.parse(
            subject = "Customer Invoice #4001",
            bodyText = """
                Invoice #4001
                Product/Service: SGP-Lite-1G (sgp1.hosthatch.local) (2026-06-01 - 2027-06-01)
                Amount Due: $15.00 USD
                Due Date: 2026-06-01
                Status: Unpaid
            """.trimIndent(),
            fromName = "HostHatch",
            fromAddress = "billing@hosthatch.com",
        )!!
        assertFalse(invoiceOnlyParsed.hasSshCredentials)

        val ledgerOnlyOutcome = ingestService.ingestMail(summary = null, parsed = invoiceOnlyParsed).getOrThrow()
        assertTrue(ledgerOnlyOutcome.assetCreated)
        assertNull(ledgerOnlyOutcome.sshProfile)
        assertEquals(2, fleetRepo.assetsFlow.value.size)
        assertEquals(1, sshVault.serversFlow.value.size)
    }

    @Test
    fun `SeekMailViewModel exposes openedVpsInsight toggles password visibility and triggers one-click ingest`() = runBlocking {
        val mockServer = MockWebServer()
        mockServer.start()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val fleetRepo = FleetRepositoryImpl(
                storeFile = File(tempDir, "vm_fleet.json"),
                tokenCipher = fleetCipher,
            )
            val sshVault = SshServerVault(
                vaultFile = File(tempDir, "vm_ssh.json"),
                cipher = sshCipher,
            )
            val ingestService = VpsMailAutoIngestServiceImpl(
                fleetRepository = fleetRepo,
                sshVaultRepository = sshVault,
                stateFile = File(tempDir, "vm_ingest_state.json"),
            )
            val settingsRepo = FakeSettingsRepository()
            val apiKey = "sk_live_" + "d".repeat(64)
            settingsRepo.setSeekMailApiKey(apiKey)

            val apiClient = SeekMailApiClient(baseUrl = mockServer.url("/openapi/v1"))
            val mailRepo = SeekMailRepositoryImpl(
                apiClient = apiClient,
                settingsRepository = settingsRepo,
                diskCache = SeekMailDiskCache(File(tempDir, "vm_cache"), scope),
                externalScope = scope,
                vpsMailAutoIngestService = ingestService,
            )

            // Route parallel workspace requests deterministically by path
            mockServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/openapi/v1/mailboxes") ->
                            MockResponse().setResponseCode(200)
                                .setBody("""{"data":[{"id":1,"address":"box@seek.li","status":1}]}""")
                        path.startsWith("/openapi/v1/tags") ->
                            MockResponse().setResponseCode(200).setBody("""{"data":[]}""")
                        path.startsWith("/openapi/v1/mails/808") ->
                            MockResponse().setResponseCode(200).setBody(
                                """
                                {
                                  "data": {
                                    "mail": {
                                      "id": 808,
                                      "mailboxId": 1,
                                      "fromAddress": "billing@racknerd.com",
                                      "fromName": "RackNerd",
                                      "toAddress": "box@seek.li",
                                      "subject": "[RackNerd] New Server Information",
                                      "bodyText": "Product/Service: KVM-1G\nIP Address: 198.51.100.200\nSSH Port: 22\nRoot Password: VmTest#Pass1\nRecurring Amount: $11.11 USD\nBilling Cycle: Annually\nNext Due Date: 2027-09-01",
                                      "bodyHtml": "",
                                      "isRead": false
                                    },
                                    "tags": []
                                  }
                                }
                                """.trimIndent(),
                            )
                        path.startsWith("/openapi/v1/mails") ->
                            MockResponse().setResponseCode(200)
                                .setBody("""{"data":[],"pagination":{"limit":20,"offset":0,"total":0}}""")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }

            val vmStore = ViewModelStore()
            val viewModel = ViewModelProvider(
                vmStore,
                object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        return SeekMailViewModel(
                            seekMailRepository = mailRepo,
                            settingsRepository = settingsRepo,
                            vpsMailAutoIngestService = ingestService,
                            initialMailId = 808L,
                        ) as T
                    }
                },
            )[SeekMailViewModel::class.java]

            // Keep WhileSubscribed uiState active during assertions
            val collectorJob = scope.launch { viewModel.uiState.collect {} }
            try {
                val loadedState = withTimeout(5_000L) {
                    viewModel.uiState.first {
                        it.openedDetail != null &&
                            it.openedVpsInsight != null &&
                            !it.isLoadingMailboxes &&
                            !it.isLoadingMails &&
                            !it.isLoadingDetail &&
                            !it.isLoadingAttachments
                    }
                }
                val insight = loadedState.openedVpsInsight!!
                assertEquals(VpsMailType.PROVISIONING, insight.parsed.mailType)
                assertEquals("198.51.100.200", insight.parsed.mainIpv4)
                assertFalse(insight.isPasswordRevealed)
                assertEquals("一键入库账本 + SSH", insight.actionButtonLabel)

                viewModel.toggleVpsPasswordVisibility()
                val revealed = withTimeout(5_000L) {
                    viewModel.uiState.first { it.openedVpsInsight?.isPasswordRevealed == true }
                }
                assertTrue(revealed.openedVpsInsight!!.isPasswordRevealed)

                viewModel.ingestOpenedVpsMail()
                val syncedState = withTimeout(5_000L) {
                    viewModel.uiState.first {
                        it.openedVpsInsight?.matchStatus?.isAssetMatched == true &&
                            it.openedVpsInsight.matchStatus.isSshMatched &&
                            !it.openedVpsInsight.isIngesting
                    }
                }
                assertEquals("更新账本 + SSH", syncedState.openedVpsInsight!!.actionButtonLabel)
                assertEquals(1, fleetRepo.assetsFlow.value.size)
                assertEquals(1, sshVault.serversFlow.value.size)
            } finally {
                collectorJob.cancelAndJoin()
                vmStore.clear()
            }
        } finally {
            scope.coroutineContext[Job]?.cancelAndJoin()
            mockServer.shutdown()
        }
    }

    @Test
    fun `ingestMail preserves existing asset multi-select tags and autoRenew flag on update`() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val fleetStoreFile = File(tempDir, "preserve_tags_autorenew_fleet.json")
            val fleetRepo = FleetRepositoryImpl(
                storeFile = fleetStoreFile,
                tokenCipher = fleetCipher,
                ioDispatcher = dispatcher,
            )
            val sshVault = SshServerVault(
                vaultFile = File(tempDir, "preserve_tags_autorenew_ssh.json"),
                cipher = sshCipher,
                ioDispatcher = dispatcher,
            )
            val existing = fleetRepo.upsertAsset(
                VpsAssetRecord(
                    id = "existing-auto",
                    nickname = "DMIT LAX Auto",
                    providerName = "DMIT",
                    specsSummary = "2C / 2G",
                    region = "洛杉矶",
                    routeTag = "CN2 GIA · IPv4/IPv6",
                    renewalAmount = "49.90",
                    currency = ValuationCurrency.USD,
                    cyclePreset = BillingCyclePreset.ANNUAL,
                    expiryDateIso = "2026-11-25",
                    notes = "IP: 198.51.100.77",
                    tags = listOf("CN2 GIA", "IPv4/IPv6"),
                    autoRenew = true,
                ),
            )

            val service = VpsMailAutoIngestServiceImpl(
                fleetRepository = fleetRepo,
                sshVaultRepository = sshVault,
                stateFile = File(tempDir, "preserve_tags_autorenew_ingest.json"),
                ioDispatcher = dispatcher,
                todayProvider = { LocalDate.of(2026, 11, 20) },
            )

            val renewalDetail = SeekMailDetail(
                summary = SeekMailSummary(
                    id = 901L,
                    mailboxId = 1L,
                    fromAddress = "billing@dmit.io",
                    fromName = "DMIT",
                    toAddress = "me@seek.li",
                    subject = "Invoice Payment Confirmation #77410",
                    snippet = "Payment received for 198.51.100.77",
                    messageId = "<inv-77410@dmit.io>",
                ),
                bodyText = """
                    Invoice Payment Confirmation
                    Invoice #77410
                    IP Address: 198.51.100.77
                    Amount Paid: $49.90 USD
                    Next Due Date: 2027-11-25
                """.trimIndent(),
            )
            val parsed = VpsBillingMailParser.parseDetail(renewalDetail)
            assertNotNull(parsed)

            val outcome = service.ingestMail(
                summary = renewalDetail.summary,
                parsed = parsed!!,
            ).getOrThrow()
            assertFalse(outcome.assetCreated)
            assertEquals(existing.id, outcome.asset.id)
            assertEquals("2027-11-25", outcome.asset.expiryDateIso)
            assertEquals(listOf("CN2 GIA", "IPv4/IPv6"), outcome.asset.tags)
            assertTrue("Existing autoRenew flag must be preserved on mail ingest update", outcome.asset.autoRenew)

            val reopened = FleetRepositoryImpl(
                storeFile = fleetStoreFile,
                tokenCipher = fleetCipher,
                ioDispatcher = dispatcher,
            )
            reopened.loadPersistedState()
            val persisted = reopened.assetsFlow.value.single()
            assertEquals(listOf("CN2 GIA", "IPv4/IPv6"), persisted.tags)
            assertTrue(persisted.autoRenew)
        }

    @Test
    fun `parser rejects non-VPS payment receipts, prefers explicit annual cycle over Monthly Bandwidth, and rejects MAC as IPv6`() {
        // Non-VPS receipts (Stripe / OpenAI / App Store / Alipay) must NOT match VPS billing
        val stripeReceipt = VpsBillingMailParser.parse(
            subject = "Your receipt from OpenAI #2044-8821",
            bodyText = "Payment Received: $20.00 USD\nReceipt #2044-8821\nChatGPT Plus Subscription\nThank you for your payment.",
            fromName = "OpenAI",
            fromAddress = "invoice+statements@openai.com",
        )
        assertNull("OpenAI receipt must not be parsed as VPS billing", stripeReceipt)

        val alipayReceipt = VpsBillingMailParser.parse(
            subject = "续费成功通知",
            bodyText = "您的会员自动续费账单已支付成功，金额 ¥18.00。",
            fromName = "影音会员",
            fromAddress = "noreply@music-app.example.com",
        )
        assertNull("Generic membership renewal without VPS context must be ignored", alipayReceipt)

        // WHMCS email containing "Monthly Bandwidth: 3000 GB" and annual period + MAC address
        val annualWithBandwidthAndMac = VpsBillingMailParser.parse(
            subject = "[RackNerd] New VPS Provisioned",
            bodyText = """
                Product/Service: KVM-2G-Special (2026-06-01 - 2027-06-01)
                Monthly Bandwidth: 3000 GB
                IP Address: 198.51.100.55
                MAC Address: 00:1a:2b:3c:4d:5e
                SSH Port: 22
                Root Password: MacTest#Pass1
                Amount: $19.99 USD
            """.trimIndent(),
            fromName = "RackNerd",
            fromAddress = "billing@racknerd.com",
        )
        assertNotNull(annualWithBandwidthAndMac)
        assertEquals(BillingCyclePreset.ANNUAL, annualWithBandwidthAndMac!!.billingCycle)
        assertNull("6-group MAC address must not be parsed as IPv6", annualWithBandwidthAndMac.mainIpv6)
    }

    @Test
    fun `auto-renewal skips ambiguous multi-asset matches, respects peeked non-renewal, preserves metadata notes, and rolls back on SSH failure`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val fleetRepo = FleetRepositoryImpl(
            storeFile = File(tempDir, "guard_fleet.json"),
            tokenCipher = fleetCipher,
            ioDispatcher = dispatcher,
        )
        val sshVault = SshServerVault(
            vaultFile = File(tempDir, "guard_ssh.json"),
            cipher = sshCipher,
            ioDispatcher = dispatcher,
        )
        // Two machines sharing the same provider + plan name
        fleetRepo.upsertAsset(
            VpsAssetRecord(
                id = "rn-1",
                nickname = "RackNerd KVM-2G #1",
                providerName = "RackNerd",
                specsSummary = "KVM-2G",
                region = "洛杉矶",
                renewalAmount = "12.00",
                cyclePreset = BillingCyclePreset.ANNUAL,
                expiryDateIso = "2026-08-01",
                notes = "[SeekMail] IP: 198.51.100.11 | Hostname: rn1.local\nUser custom memo",
            ),
        )
        fleetRepo.upsertAsset(
            VpsAssetRecord(
                id = "rn-2",
                nickname = "RackNerd KVM-2G #2",
                providerName = "RackNerd",
                specsSummary = "KVM-2G",
                region = "洛杉矶",
                renewalAmount = "12.00",
                cyclePreset = BillingCyclePreset.ANNUAL,
                expiryDateIso = "2026-09-01",
                notes = "[SeekMail] IP: 198.51.100.12 | Hostname: rn2.local",
            ),
        )

        var peekedDetail: SeekMailDetail? = null
        val service = VpsMailAutoIngestServiceImpl(
            fleetRepository = fleetRepo,
            sshVaultRepository = sshVault,
            stateFile = File(tempDir, "guard_state.json"),
            mailDetailPeeker = { peekedDetail },
            ioDispatcher = dispatcher,
            todayProvider = { LocalDate.of(2026, 7, 1) },
        )

        // 1. Ambiguous renewal matching 2 assets must NOT advance either machine
        val ambiguousRenewal = VpsBillingMailParser.parse(
            subject = "Invoice Payment Confirmation #8801",
            bodyText = "Provider: RackNerd\nProduct/Service: KVM-2G\nPayment received: $12.00 USD\nStatus: Paid",
            fromName = "RackNerd",
            fromAddress = "billing@racknerd.com",
        )!!
        val appliedAmbiguous = service.processMailDetail(
            SeekMailDetail(
                summary = SeekMailSummary(
                    id = 5001L,
                    mailboxId = 1L,
                    fromAddress = "billing@racknerd.com",
                    fromName = "RackNerd",
                    toAddress = "me@seek.li",
                    subject = "Invoice Payment Confirmation #8801",
                    snippet = "Payment received for KVM-2G",
                ),
                bodyText = "Provider: RackNerd\nProduct/Service: KVM-2G\nPayment received: $12.00 USD\nStatus: Paid",
            ),
        )
        assertTrue("Ambiguous multi-asset renewal must be skipped", appliedAmbiguous.isEmpty())
        assertEquals("2026-08-01", fleetRepo.assetsFlow.value.first { it.id == "rn-1" }.expiryDateIso)
        assertEquals("2026-09-01", fleetRepo.assetsFlow.value.first { it.id == "rn-2" }.expiryDateIso)
        assertNotNull(ambiguousRenewal)

        // 2. When full detail disproves RENEWAL (e.g. actually unpaid invoice), summary must NOT fall through
        val misleadingSummary = SeekMailSummary(
            id = 5002L,
            mailboxId = 1L,
            fromAddress = "billing@racknerd.com",
            fromName = "RackNerd",
            toAddress = "me@seek.li",
            subject = "Renewal Invoice #8802 Paid",
            snippet = "VPS renewal confirmation for 198.51.100.11",
        )
        peekedDetail = SeekMailDetail(
            summary = misleadingSummary.copy(subject = "Customer Invoice #8802 Generated"),
            bodyText = "Invoice #8802\nStatus: Unpaid\nAmount Due: $12.00 USD\nMain IP: 198.51.100.11\nDue Date: 2026-08-01",
        )
        val disprovedResult = service.processIncomingSummaries(listOf(misleadingSummary))
        assertTrue("Peeked non-renewal detail must not fall back to snippet", disprovedResult.isEmpty())
        assertEquals("2026-08-01", fleetRepo.assetsFlow.value.first { it.id == "rn-1" }.expiryDateIso)

        // 3. Re-importing renewal without IP/Hostname preserves prior [SeekMail] IP/Hostname & user notes
        val renewalWithoutIp = VpsBillingMailParser.parse(
            subject = "Invoice Payment Confirmation #8803",
            bodyText = "Invoice #8803\nProduct/Service: rn1.local\nAmount Paid: $12.00 USD\nStatus: Paid\nNext Due Date: 2027-08-01",
            fromName = "RackNerd",
            fromAddress = "billing@racknerd.com",
        )!!
        val mergedOutcome = service.ingestMail(summary = null, parsed = renewalWithoutIp).getOrThrow()
        assertEquals("rn-1", mergedOutcome.asset.id)
        assertTrue("Prior IP must be preserved in managed notes", mergedOutcome.asset.notes.contains("IP: 198.51.100.11"))
        assertTrue("Prior Hostname must be preserved in managed notes", mergedOutcome.asset.notes.contains("Hostname: rn1.local"))
        assertTrue("User custom memo must be preserved", mergedOutcome.asset.notes.contains("User custom memo"))

        // 4. Existing PRIVATE_KEY SSH profile is preserved when overwriteExistingSshCredentials = false
        sshVault.saveServer(
            com.nodeseek.app.domain.ssh.SshServerDraft(
                alias = "RN-1 Key",
                host = "198.51.100.11",
                port = 22,
                username = "root",
                authMethod = SshAuthMethod.PRIVATE_KEY,
                secret = "-----BEGIN OPENSSH PRIVATE KEY-----\nDUMMY\n-----END OPENSSH PRIVATE KEY-----",
            ),
        )
        val provWithPassword = VpsBillingMailParser.parse(
            subject = "[RackNerd] Root Password Reset",
            bodyText = "Product/Service: KVM-2G\nIP Address: 198.51.100.11\nSSH Port: 22\nRoot Password: ResetPass#123",
            fromName = "RackNerd",
            fromAddress = "billing@racknerd.com",
        )!!
        val keepKeyOutcome = service.ingestMail(
            summary = null,
            parsed = provWithPassword,
            overwriteExistingSshCredentials = false,
        ).getOrThrow()
        assertEquals(SshAuthMethod.PRIVATE_KEY, sshVault.serversFlow.value.single().authMethod)
        assertTrue(keepKeyOutcome.summaryMessage.contains("保留现有 SSH"))

        // 5. SSH vault write failure rolls back Fleet write so both stores remain consistent
        val failingSshVault = object : com.nodeseek.app.domain.ssh.SshVaultRepository by sshVault {
            override suspend fun saveServer(draft: com.nodeseek.app.domain.ssh.SshServerDraft): com.nodeseek.app.domain.ssh.SshServerProfile {
                throw IllegalStateException("Simulated SSH vault failure")
            }
        }
        val rollbackService = VpsMailAutoIngestServiceImpl(
            fleetRepository = fleetRepo,
            sshVaultRepository = failingSshVault,
            stateFile = File(tempDir, "rollback_state.json"),
            ioDispatcher = dispatcher,
            todayProvider = { LocalDate.of(2026, 7, 1) },
        )
        val countBeforeRollback = fleetRepo.assetsFlow.value.size
        val brandNewProv = VpsBillingMailParser.parse(
            subject = "[ClawCloud] New Server Provisioned",
            bodyText = "Product/Service: HK-Tiny\nIP Address: 198.51.100.240\nSSH Port: 22\nRoot Password: FailPass#1\nAmount: $9.99 USD",
            fromName = "ClawCloud",
            fromAddress = "billing@claw.cloud",
        )!!
        val rollbackResult = rollbackService.ingestMail(summary = null, parsed = brandNewProv)
        assertTrue("Ingest must fail when SSH vault write fails", rollbackResult.isFailure)
        assertEquals(
            "Newly inserted Fleet asset must be rolled back on SSH failure",
            countBeforeRollback,
            fleetRepo.assetsFlow.value.size,
        )

        // 6. Pure-Kotlin IPv6 canonicalizer handles valid/compressed/IPv4-mapped & rejects MAC/hostnames
        assertEquals("2001:0db8:0000:0000:0000:0000:0000:0001", service.canonicalizeIpv6("2001:db8::1"))
        assertEquals("2001:0db8:0000:0000:0000:0000:0000:0001", service.canonicalizeIpv6("[2001:0DB8:0:0:0:0:0:1]/64"))
        assertEquals("0000:0000:0000:0000:0000:ffff:c000:0280", service.canonicalizeIpv6("::ffff:192.0.2.128"))
        assertNull(service.canonicalizeIpv6("00:1a:2b:3c:4d:5e"))
        assertNull(service.canonicalizeIpv6("not-an-ipv6.example.com"))
    }
}
