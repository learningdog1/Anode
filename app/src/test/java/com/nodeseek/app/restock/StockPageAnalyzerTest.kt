package com.nodeseek.app.restock

import com.nodeseek.app.data.restock.FlareSolverrClient
import com.nodeseek.app.data.restock.StockPageAnalyzer
import com.nodeseek.app.domain.restock.FlareSolverrConfig
import com.nodeseek.app.domain.restock.MerchantPanelType
import com.nodeseek.app.domain.restock.RestockMonitorSettings
import com.nodeseek.app.domain.restock.RestockStockStatus
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StockPageAnalyzerTest {

    private lateinit var server: MockWebServer
    private lateinit var analyzer: StockPageAnalyzer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        analyzer = StockPageAnalyzer(
            httpClient = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build(),
            clockMillis = { 1_760_000_000_000L },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `analyzes WHMCS in-stock configuration page and extracts title and price hint`() {
        val html = """
            <!doctype html>
            <html>
            <head><title>Shopping Cart - RackNerd</title></head>
            <body>
              <div id="order-standard_cart">
                <form id="frmConfigureProduct" action="cart.php?a=confproduct&i=0" method="post">
                  <div class="product-info">
                    <p class="product-title">2 GB KVM VPS - Black Friday Special</p>
                    <p>2 vCPU Cores · 2 GB RAM · 35 GB SSD</p>
                  </div>
                  <select name="billingcycle" id="inputBillingcycle">
                    <option value="annually" selected="selected">$10.98 USD Annually</option>
                  </select>
                  <button type="submit" id="btnCompleteProductConfig" class="btn btn-primary">
                    Continue
                  </button>
                </form>
              </div>
            </body>
            </html>
        """.trimIndent()

        val result = analyzer.analyzeHtml(
            rawUrl = "https://my.racknerd.com/cart.php?a=add&pid=801",
            html = html,
        )

        assertEquals(MerchantPanelType.WHMCS, result.panelType)
        assertEquals(RestockStockStatus.IN_STOCK, result.stockStatus)
        assertEquals("2 GB KVM VPS - Black Friday Special", result.detectedTitle)
        assertEquals("$10.98/年", result.detectedPriceHint)
    }

    @Test
    fun `analyzes WHMCS out-of-stock page without false positive on Continue Shopping button`() {
        val html = """
            <!doctype html>
            <html>
            <head><title>DMIT LAX.Pro.WEE - Shopping Cart</title></head>
            <body>
              <header><nav><a class="btn" href="/order">Order Now</a></nav></header>
              <div id="order-standard_cart">
                <div class="header-lined"><h1>Out of Stock</h1></div>
                <div class="alert alert-danger">
                  We are currently out of stock on this item so orders for it have been suspended until more stock is available.
                </div>
                <a href="cart.php" class="btn btn-default">Continue Shopping</a>
              </div>
            </body>
            </html>
        """.trimIndent()

        val result = analyzer.analyzeHtml(
            rawUrl = "https://www.dmit.io/cart.php?a=add&pid=183",
            html = html,
        )

        assertEquals(MerchantPanelType.WHMCS, result.panelType)
        assertEquals(RestockStockStatus.OUT_OF_STOCK, result.stockStatus)
        assertEquals("DMIT LAX.Pro.WEE", result.detectedTitle)
    }

    @Test
    fun `analyzes WHMCS order-boxes numeric availability 0 Available vs 7 Available`() {
        val zeroStockHtml = """
            <html><body>
              <div id="order-boxes">
                <div class="product">
                  <span class="product-title">BWH THE PLAN</span>
                  <span class="qty">0 Available</span>
                  <div class="price">$92.00 USD Annually</div>
                  <a class="btn btn-order-now disabled">Order Now</a>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val zeroResult = analyzer.analyzeHtml(
            rawUrl = "https://bwh81.net/cart.php?a=add&pid=131",
            html = zeroStockHtml,
        )
        assertEquals(MerchantPanelType.WHMCS, zeroResult.panelType)
        assertEquals(RestockStockStatus.OUT_OF_STOCK, zeroResult.stockStatus)
        assertEquals("BWH THE PLAN", zeroResult.detectedTitle)
        assertEquals("$92.00/年", zeroResult.detectedPriceHint)

        val positiveStockHtml = """
            <html><body>
              <div id="order-boxes">
                <div class="product">
                  <span class="product-title">BWH THE PLAN</span>
                  <span class="qty">7 Available</span>
                  <div class="price">$92.00 USD Annually</div>
                  <a href="cart.php?a=add&pid=131" class="btn btn-order-now">Order Now</a>
                </div>
              </div>
            </body></html>
        """.trimIndent()

        val positiveResult = analyzer.analyzeHtml(
            rawUrl = "https://bwh81.net/cart.php?a=add&pid=131",
            html = positiveStockHtml,
        )
        assertEquals(MerchantPanelType.WHMCS, positiveResult.panelType)
        assertEquals(RestockStockStatus.IN_STOCK, positiveResult.stockStatus)
    }

    @Test
    fun `analyzes SolusVM HostBill and VirtFusion panels accurately`() {
        val solusOosHtml = """
            <html>
            <head><title>SolusVM 2 Cloud Plan</title></head>
            <body>
              <main class="solus-order">
                <h1>Tokyo KVM 1G</h1>
                <div class="alert alert-warning">No nodes available in this region.</div>
                <button type="submit" disabled>Deploy Server</button>
              </main>
            </body>
            </html>
        """.trimIndent()
        val solusOos = analyzer.analyzeHtml(
            rawUrl = "https://panel.example.com/solusvm/order/tokyo-1g",
            html = solusOosHtml,
        )
        assertEquals(MerchantPanelType.SOLUSVM, solusOos.panelType)
        assertEquals(RestockStockStatus.OUT_OF_STOCK, solusOos.stockStatus)

        val solusInStockHtml = """
            <html>
            <head><title>SolusVM 2 Cloud Plan</title></head>
            <body>
              <main class="solus-order">
                <h1>Tokyo KVM 1G</h1>
                <span class="price">$3.50/mo</span>
                <form action="/order/deploy" method="post">
                  <button type="submit">Deploy Server</button>
                </form>
              </main>
            </body>
            </html>
        """.trimIndent()
        val solusInStock = analyzer.analyzeHtml(
            rawUrl = "https://panel.example.com/solusvm/order/tokyo-1g",
            html = solusInStockHtml,
        )
        assertEquals(MerchantPanelType.SOLUSVM, solusInStock.panelType)
        assertEquals(RestockStockStatus.IN_STOCK, solusInStock.stockStatus)
        assertEquals("$3.50/月", solusInStock.detectedPriceHint)

        val hostBillHtml = """
            <html><body>
              <div class="hb-order">
                <h1>HostBill NVMe VPS</h1>
                <div class="price">€4.99/mo</div>
                <form action="/index.php?/cart/add" method="post">
                  <button type="submit">Add to Cart</button>
                </form>
              </div>
            </body></html>
        """.trimIndent()
        val hostBillResult = analyzer.analyzeHtml(
            rawUrl = "https://billing.euhost.net/index.php?/cart/nvme/",
            html = hostBillHtml,
        )
        assertEquals(MerchantPanelType.HOSTBILL, hostBillResult.panelType)
        assertEquals(RestockStockStatus.IN_STOCK, hostBillResult.stockStatus)
        assertEquals("€4.99/月", hostBillResult.detectedPriceHint)

        val virtFusionHtml = """
            <html><body>
              <div class="vf-order">
                <h1>VirtFusion Ryzen 2G</h1>
                <div class="out-of-stock">Sold Out</div>
              </div>
            </body></html>
        """.trimIndent()
        val virtFusionResult = analyzer.analyzeHtml(
            rawUrl = "https://cp.ryzenvps.io/order/package/42",
            html = virtFusionHtml,
        )
        assertEquals(MerchantPanelType.VIRTFUSION, virtFusionResult.panelType)
        assertEquals(RestockStockStatus.OUT_OF_STOCK, virtFusionResult.stockStatus)
    }

    @Test
    fun `analyzes Chinese and English storefronts and ignores script noise and negated phrases`() {
        val cnOosHtml = """
            <html><body>
              <main>
                <h1>香港 CMI 迷你鸡</h1>
                <div class="price">¥99/年</div>
                <span class="badge">暂时缺货</span>
                <button disabled>已售罄</button>
              </main>
            </body></html>
        """.trimIndent()
        val cnOos = analyzer.analyzeHtml(
            rawUrl = "https://shop.cnvps.example/goods/101",
            html = cnOosHtml,
        )
        assertEquals(RestockStockStatus.OUT_OF_STOCK, cnOos.stockStatus)
        assertEquals("香港 CMI 迷你鸡", cnOos.detectedTitle)
        assertEquals("¥99/年", cnOos.detectedPriceHint)

        val cnInStockWithScriptNoise = """
            <html><body>
              <script>
                window.i18n = { oos: "Out of Stock", cnOos: "已售罄" };
              </script>
              <main>
                <h1>香港 CMI 迷你鸡</h1>
                <p>官方承诺常年现货不缺货，库存：15台</p>
                <div class="price">￥19.9/月</div>
                <button type="submit">立即购买</button>
              </main>
            </body></html>
        """.trimIndent()
        val cnInStock = analyzer.analyzeHtml(
            rawUrl = "https://shop.cnvps.example/goods/101",
            html = cnInStockWithScriptNoise,
        )
        assertEquals(RestockStockStatus.IN_STOCK, cnInStock.stockStatus)
        assertEquals("¥19.9/月", cnInStock.detectedPriceHint)

        val disabledCtaHtml = """
            <html><body>
              <main>
                <h1>Promo VPS</h1>
                <button type="submit" disabled>Order Now</button>
              </main>
            </body></html>
        """.trimIndent()
        val disabledCta = analyzer.analyzeHtml(
            rawUrl = "https://store.example.com/product/promo",
            html = disabledCtaHtml,
        )
        assertEquals(RestockStockStatus.OUT_OF_STOCK, disabledCta.stockStatus)

        val cnZeroAvailable = analyzer.analyzeHtml(
            rawUrl = "https://my.host.example/cart.php?a=add&pid=77",
            html = """<html><body><div id="order-boxes"><span class="qty">0 可用</span><div class="price">39.90 USD Annually</div></div></body></html>""",
        )
        assertEquals(RestockStockStatus.OUT_OF_STOCK, cnZeroAvailable.stockStatus)
        assertEquals("$39.90/年", cnZeroAvailable.detectedPriceHint)

        val cnPositiveAvailable = analyzer.analyzeHtml(
            rawUrl = "https://my.host.example/cart.php?a=add&pid=77",
            html = """<html><body><div id="order-boxes"><span class="qty">8 可用</span><div class="price">39.90 USD Annually</div></div></body></html>""",
        )
        assertEquals(RestockStockStatus.IN_STOCK, cnPositiveAvailable.stockStatus)
    }

    @Test
    fun `custom keywords override default heuristics accurately`() {
        val htmlWithFooterOos = """
            <html><body>
              <main>
                <h1>Special Flash VPS</h1>
                <p>Some other item is Out of Stock</p>
                <div>限量放货中-立即抢</div>
              </main>
            </body></html>
        """.trimIndent()

        // Without custom keyword, standard heuristic catches "Out of Stock"
        val defaultResult = analyzer.analyzeHtml(
            rawUrl = "https://shop.example.com/flash",
            html = htmlWithFooterOos,
        )
        assertEquals(RestockStockStatus.OUT_OF_STOCK, defaultResult.stockStatus)

        // Custom in-stock keyword overrides
        val customInResult = analyzer.analyzeHtml(
            rawUrl = "https://shop.example.com/flash",
            html = htmlWithFooterOos,
            customInStockKeyword = "限量放货中-立即抢",
        )
        assertEquals(RestockStockStatus.IN_STOCK, customInResult.stockStatus)
        assertTrue(customInResult.matchedSignal.contains("限量放货中-立即抢"))

        // Custom in-stock keyword NOT present -> OUT_OF_STOCK
        val customInMissing = analyzer.analyzeHtml(
            rawUrl = "https://shop.example.com/flash",
            html = htmlWithFooterOos,
            customInStockKeyword = "本批次已补货",
        )
        assertEquals(RestockStockStatus.OUT_OF_STOCK, customInMissing.stockStatus)

        // Custom out-of-stock keyword overrides an active Order Now page
        val activePageHtml = """
            <html><body>
              <main>
                <h1>Flash Deal</h1>
                <p>当前批次暂停发放</p>
                <button>Order Now</button>
              </main>
            </body></html>
        """.trimIndent()
        val customOosResult = analyzer.analyzeHtml(
            rawUrl = "https://shop.example.com/flash",
            html = activePageHtml,
            customOutOfStockKeyword = "暂停发放, 已下架",
        )
        assertEquals(RestockStockStatus.OUT_OF_STOCK, customOosResult.stockStatus)
        assertTrue(customOosResult.matchedSignal.contains("暂停发放"))
    }

    @Test
    fun `analyzeUrl handles redirects, Cloudflare challenge, rate limits, and network errors via MockWebServer`() = runTest {
        // 1. WHMCS 302 redirect to /cart.php?a=outofstock
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .addHeader("Location", "/cart.php?a=outofstock&pid=99"),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("<html><body><h1>Notice</h1><p>Please check back later.</p></body></html>"),
        )
        val redirected = analyzer.analyzeUrl(server.url("/cart.php?a=add&pid=99").toString())
        assertEquals(MerchantPanelType.WHMCS, redirected.panelType)
        assertEquals(RestockStockStatus.OUT_OF_STOCK, redirected.stockStatus)
        assertTrue(redirected.finalUrl.contains("outofstock"))

        // 2. Cloudflare challenge -> CHECK_ERROR (non-transient, with FlareSolverr setup hint)
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("cf-mitigated", "challenge")
                .setBody("<html><title>Just a moment...</title></html>"),
        )
        val cf = analyzer.analyzeUrl(server.url("/cart.php?a=add&pid=100").toString())
        assertEquals(RestockStockStatus.CHECK_ERROR, cf.stockStatus)
        assertFalse(cf.isTransientNetworkError)
        assertNotNull(cf.errorMessage)
        assertTrue(cf.errorMessage!!.contains("FlareSolverr"))

        // 3. HTTP 503 -> CHECK_ERROR (transient)
        server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        val http503 = analyzer.analyzeUrl(server.url("/cart.php?a=add&pid=101").toString())
        assertEquals(RestockStockStatus.CHECK_ERROR, http503.stockStatus)
        assertTrue(http503.isTransientNetworkError)

        // 4. Socket disconnect -> CHECK_ERROR (transient)
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        val disconnected = analyzer.analyzeUrl(server.url("/cart.php?a=add&pid=102").toString())
        assertEquals(RestockStockStatus.CHECK_ERROR, disconnected.stockStatus)
        assertTrue(disconnected.isTransientNetworkError)
    }

    private fun flareSolverrAnalyzer(flareServer: MockWebServer): StockPageAnalyzer =
        StockPageAnalyzer(
            httpClient = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build(),
            clockMillis = { 1_760_000_000_000L },
            flareSolverrClient = FlareSolverrClient(),
            settingsProvider = {
                RestockMonitorSettings(
                    flareSolverr = FlareSolverrConfig(
                        enabled = true,
                        baseUrl = flareServer.url("/").toString().trimEnd('/'),
                    ),
                )
            },
        )

    private fun flareSolverrOkBody(html: String, finalUrl: String): String =
        """{"status":"ok","message":"Challenge solved!","solution":{"url":"$finalUrl","status":200,"userAgent":"Mozilla/5.0 CF","response":"$html"}}"""

    private val solvedInStockHtml = """
        <html><head><title>CF Shop</title></head><body>
          <div id=order-standard_cart>
            <form id=frmConfigureProduct action=cart.php?a=confproduct method=post>
              <div class=product-info><p class=product-title>2 GB KVM VPS CF Special</p></div>
              <select name=billingcycle id=inputBillingcycle>
                <option value=annually selected>9.99 USD Annually</option>
              </select>
              <button type=submit id=btnCompleteProductConfig class=btn-primary>Continue</button>
            </form>
          </div>
        </body></html>
    """.trimIndent()

    @Test
    fun `analyzeUrl solves Cloudflare challenge via FlareSolverr and analyzes the solved html`() = runTest {
        val flareServer = MockWebServer()
        flareServer.start()
        val fsAnalyzer = flareSolverrAnalyzer(flareServer)

        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("cf-mitigated", "challenge")
                .setBody("<html><title>Just a moment...</title></html>"),
        )
        flareServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                flareSolverrOkBody(solvedInStockHtml, server.url("/cart.php?a=add&pid=118").toString()),
            ),
        )

        val result = fsAnalyzer.analyzeUrl(server.url("/cart.php?a=add&pid=118").toString())

        assertEquals(RestockStockStatus.IN_STOCK, result.stockStatus)
        assertTrue(result.usedFlareSolverr)
        assertTrue(result.matchedSignal.startsWith("CF 盾已突破（FlareSolverr）"))
        assertEquals("2 GB KVM VPS CF Special", result.detectedTitle)

        val flareRequest = flareServer.takeRequest()
        assertEquals("/v1", flareRequest.path)
        assertTrue(flareRequest.body.readUtf8().contains("\"cmd\":\"request.get\""))
        flareServer.shutdown()
    }

    @Test
    fun `analyzeUrl learns Cloudflare hosts and routes subsequent checks straight to FlareSolverr`() = runTest {
        val flareServer = MockWebServer()
        flareServer.start()
        val fsAnalyzer = flareSolverrAnalyzer(flareServer)
        val watchedUrl = server.url("/cart.php?a=add&pid=201").toString()

        // 1st check: direct fetch eats the challenge, FlareSolverr solves it (OOS page)
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("cf-mitigated", "challenge")
                .setBody("<html><title>Just a moment...</title></html>"),
        )
        flareServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                flareSolverrOkBody(
                    "<html><body><main><h1>Flash Deal</h1><p>Out of Stock - please check back later</p></main></body></html>",
                    watchedUrl,
                ),
            ),
        )
        val first = fsAnalyzer.analyzeUrl(watchedUrl)
        assertEquals(RestockStockStatus.OUT_OF_STOCK, first.stockStatus)
        assertTrue(first.usedFlareSolverr)

        // 2nd check on the same host: merchant server must NOT be hit again
        flareServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                flareSolverrOkBody(solvedInStockHtml, watchedUrl),
            ),
        )
        val second = fsAnalyzer.analyzeUrl(watchedUrl)
        assertTrue(second.usedFlareSolverr)
        assertEquals(RestockStockStatus.IN_STOCK, second.stockStatus)

        assertEquals(1, server.requestCount)
        assertEquals(2, flareServer.requestCount)
        flareServer.shutdown()
    }

    @Test
    fun `analyzeUrl surfaces FlareSolverr failure reason when the challenge stays unsolved`() = runTest {
        val flareServer = MockWebServer()
        flareServer.start()
        val fsAnalyzer = flareSolverrAnalyzer(flareServer)

        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("cf-mitigated", "challenge")
                .setBody("<html><title>Just a moment...</title></html>"),
        )
        flareServer.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"status":"error","message":"Captcha detected"}""",
            ),
        )

        val result = fsAnalyzer.analyzeUrl(server.url("/cart.php?a=add&pid=300").toString())

        assertEquals(RestockStockStatus.CHECK_ERROR, result.stockStatus)
        assertFalse(result.usedFlareSolverr)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("Captcha detected"))
        assertFalse(result.isTransientNetworkError)
        flareServer.shutdown()
    }
}
