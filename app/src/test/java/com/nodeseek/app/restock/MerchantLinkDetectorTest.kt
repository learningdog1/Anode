package com.nodeseek.app.restock

import com.nodeseek.app.data.restock.MerchantLinkDetector
import com.nodeseek.app.domain.restock.MerchantPanelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MerchantLinkDetectorTest {

    @Test
    fun `unwraps NodeSeek jump URLs cleanly`() {
        val jumpUrl =
            "https://www.nodeseek.com/jump?to=https%3A%2F%2Fmy.racknerd.com%2Fcart.php%3Fa%3Dadd%26pid%3D801"
        val resolved = MerchantLinkDetector.resolveOrderUrl(jumpUrl)
        assertEquals("https://my.racknerd.com/cart.php?a=add&pid=801", resolved)
        assertEquals(MerchantPanelType.WHMCS, MerchantLinkDetector.inferPanelTypeFromUrl(jumpUrl))
    }

    @Test
    fun `extracts candidate merchant links from NodeSeek Markdown and HTML while filtering noise`() {
        val postContent = """
            今天黑五补货汇总：
            1. [RackNerd 10.98刀年付直达](https://www.nodeseek.com/jump?to=https%3A%2F%2Fmy.racknerd.com%2Fcart.php%3Fa%3Dadd%26pid%3D801)
            2. <a href="https://www.nodeseek.com/jump?to=https%3A%2F%2Fbilling.dmit.io%2Faff.php%3Faff%3D42%26pid%3D183">DMIT Pro 购买链接</a>
            3. VirtFusion 套餐：https://cp.ryzencloud.io/order/package/15
            4. HostBill 节点：https://portal.eucloud.net/index.php?/cart/kvm/
            5. 重复链接 https://my.racknerd.com/cart.php?a=add&pid=801。
            
            参考测速图：![bench](https://www.nodeimage.com/i/abc123.png)
            脚本仓库：https://github.com/spiritLHLS/ecs
            讨论原帖：https://www.nodeseek.com/post-99999-1
            TG 通知群：https://t.me/somechannel
        """.trimIndent()

        val links = MerchantLinkDetector.extractMerchantLinks(postContent)

        assertEquals(4, links.size)
        val urls = links.map { it.resolvedUrl }
        assertTrue(urls.contains("https://my.racknerd.com/cart.php?a=add&pid=801"))
        assertTrue(urls.contains("https://billing.dmit.io/aff.php?aff=42&pid=183"))
        assertTrue(urls.contains("https://cp.ryzencloud.io/order/package/15"))
        assertTrue(urls.contains("https://portal.eucloud.net/index.php?/cart/kvm/"))

        val rackNerd = links.first { it.resolvedUrl.contains("racknerd.com") }
        assertEquals(MerchantPanelType.WHMCS, rackNerd.panelType)
        assertEquals("RackNerd 10.98刀年付直达", rackNerd.anchorText)

        val dmit = links.first { it.resolvedUrl.contains("dmit.io") }
        assertEquals(MerchantPanelType.WHMCS, dmit.panelType)
        assertEquals("DMIT Pro 购买链接", dmit.anchorText)

        val vf = links.first { it.resolvedUrl.contains("ryzencloud.io") }
        assertEquals(MerchantPanelType.VIRTFUSION, vf.panelType)

        val hb = links.first { it.resolvedUrl.contains("eucloud.net") }
        assertEquals(MerchantPanelType.HOSTBILL, hb.panelType)
    }

    @Test
    fun `isLikelyMerchantLink differentiates merchant billing URLs from general sites`() {
        assertTrue(MerchantLinkDetector.isLikelyMerchantLink("https://my.frantech.ca/cart.php?gid=37"))
        assertTrue(MerchantLinkDetector.isLikelyMerchantLink("https://www.nodeseek.com/jump?to=https%3A%2F%2Fapp.cloudcone.com%2Fvps%2F245%2Fcreate"))
        assertTrue(MerchantLinkDetector.isLikelyMerchantLink("https://panel.myhost.com:5656/solusvm/order"))

        assertFalse(MerchantLinkDetector.isLikelyMerchantLink("https://www.nodeseek.com/post-12345-1"))
        assertFalse(MerchantLinkDetector.isLikelyMerchantLink("https://github.com/nodeseek/app"))
        assertFalse(MerchantLinkDetector.isLikelyMerchantLink("https://i.imgur.com/demo.png"))
        assertFalse(MerchantLinkDetector.isLikelyMerchantLink("https://ping.pe/1.1.1.1"))
    }
}
