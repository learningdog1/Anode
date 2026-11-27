package com.nodeseek.app.domain.carpool

import com.nodeseek.app.domain.valuation.ValuationCurrency
import com.nodeseek.app.ui.components.MdBlock
import com.nodeseek.app.ui.components.parseNodeSeekMarkdownDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class CarpoolCalculatorTest {

    @Test
    fun `calculate splits CNY and USD cycles accurately across equal and driver-excluded modes`() {
        val quarterlyCny = CarpoolCalculator.calculate(
            CarpoolCalculationInput(
                serviceName = "Netflix 4K",
                totalSeats = 4,
                occupiedSeats = 3,
                totalCycleCost = BigDecimal("240.00"),
                currency = ValuationCurrency.CNY,
                billingCycle = CarpoolBillingCycle.QUARTERLY,
                seatSplitMode = CarpoolSeatSplitMode.EQUAL_ALL_SEATS,
            ),
        ) as CarpoolCalculationOutcome.Success

        assertEquals(BigDecimal("240.00"), quarterlyCny.result.totalCycleCostCny)
        assertEquals(BigDecimal("60.00"), quarterlyCny.result.perPersonCycleCostCny)
        assertEquals(BigDecimal("20.00"), quarterlyCny.result.perPersonMonthlyCny)
        assertEquals(BigDecimal("60.00"), quarterlyCny.result.perPersonQuarterlyCny)
        assertEquals(BigDecimal("240.00"), quarterlyCny.result.perPersonYearlyCny)
        assertEquals(BigDecimal("0.67"), quarterlyCny.result.perPersonDailyCny)
        assertEquals(1, quarterlyCny.result.remainingSeats)
        assertEquals("3=1", quarterlyCny.result.statusTag)

        // Driver excluded on 4 seats => 3 paying seats
        val driverFree = CarpoolCalculator.calculate(
            CarpoolCalculationInput(
                serviceName = "ChatGPT Team",
                totalSeats = 4,
                occupiedSeats = 4,
                totalCycleCost = BigDecimal("100.00"),
                currency = ValuationCurrency.USD,
                fxRateToCny = BigDecimal("7.2000"),
                billingCycle = CarpoolBillingCycle.MONTHLY,
                seatSplitMode = CarpoolSeatSplitMode.DRIVER_EXCLUDED,
            ),
        ) as CarpoolCalculationOutcome.Success

        assertEquals(3, driverFree.result.payingSeats)
        assertEquals(BigDecimal("720.00"), driverFree.result.totalCycleCostCny)
        assertEquals(BigDecimal("240.00"), driverFree.result.perPersonCycleCostCny)
        assertEquals(BigDecimal("720.00"), driverFree.result.perPersonQuarterlyCny)
        assertEquals(BigDecimal("2880.00"), driverFree.result.perPersonYearlyCny)
        assertEquals("已满车", driverFree.result.statusTag)
    }

    @Test
    fun `calculate validates boundaries and supports custom days`() {
        val invalid = CarpoolCalculator.calculate(
            CarpoolCalculationInput(
                totalSeats = 1,
                occupiedSeats = 2,
                totalCycleCost = BigDecimal("-10"),
                billingCycle = CarpoolBillingCycle.CUSTOM_DAYS,
                customCycleDays = 0,
            ),
        )
        assertTrue(invalid is CarpoolCalculationOutcome.Invalid)

        val custom = CarpoolCalculator.calculate(
            CarpoolCalculationInput(
                serviceName = "自定义车",
                totalSeats = 3,
                occupiedSeats = 2,
                totalCycleCost = BigDecimal("300.00"),
                billingCycle = CarpoolBillingCycle.CUSTOM_DAYS,
                customCycleDays = 100,
            ),
        ) as CarpoolCalculationOutcome.Success
        assertEquals(100, custom.result.effectiveCycleDays)
        assertEquals(BigDecimal("100.00"), custom.result.perPersonCycleCostCny)
        assertEquals(BigDecimal("1.00"), custom.result.perPersonDailyCny)
        assertEquals(BigDecimal("30.00"), custom.result.perPersonMonthlyCny)
        assertEquals(BigDecimal("90.00"), custom.result.perPersonQuarterlyCny)
        assertEquals(BigDecimal("365.00"), custom.result.perPersonYearlyCny)
    }

    @Test
    fun `calculateProRataRefund computes crash and jump refunds across boundary days`() {
        // 0 used days -> 100% refund
        val zeroUsed = CarpoolCalculator.calculateProRataRefund(
            paidAmountCny = BigDecimal("120.00"),
            totalDays = 90,
            usedDays = 0,
            penaltyPercent = BigDecimal("10"),
        )
        assertEquals(90, zeroUsed.remainingDays)
        assertEquals(BigDecimal("120.00"), zeroUsed.crashRefundCny)
        assertEquals(BigDecimal("108.00"), zeroUsed.jumpRefundCny)
        assertEquals(BigDecimal("12.00"), zeroUsed.penaltyDeductedCny)

        // Mid-cycle: 30 used out of 90 -> remaining 60/90 = 2/3 -> 80.00 crash, 72.00 jump (10% penalty)
        val mid = CarpoolCalculator.calculateProRataRefund(
            paidAmountCny = BigDecimal("120.00"),
            totalDays = 90,
            usedDays = 30,
            penaltyPercent = BigDecimal("10"),
        )
        assertEquals(60, mid.remainingDays)
        assertEquals(BigDecimal("80.00"), mid.crashRefundCny)
        assertEquals(BigDecimal("72.00"), mid.jumpRefundCny)
        assertEquals(BigDecimal("40.00"), mid.usedCostCny)

        // usedDays == totalDays and usedDays > totalDays -> clamped 0.00
        val exactEnd = CarpoolCalculator.calculateProRataRefund(
            paidAmountCny = BigDecimal("120.00"),
            totalDays = 90,
            usedDays = 90,
            penaltyPercent = BigDecimal("20"),
        )
        assertEquals(0, exactEnd.remainingDays)
        assertEquals(BigDecimal("0.00"), exactEnd.crashRefundCny)
        assertEquals(BigDecimal("0.00"), exactEnd.jumpRefundCny)

        val overEnd = CarpoolCalculator.calculateProRataRefund(
            paidAmountCny = BigDecimal("120.00"),
            totalDays = 90,
            usedDays = 150,
            penaltyPercent = BigDecimal("20"),
        )
        assertEquals(0, overEnd.remainingDays)
        assertEquals(BigDecimal("0.00"), overEnd.crashRefundCny)
        assertEquals(BigDecimal("0.00"), overEnd.jumpRefundCny)
    }

    @Test
    fun `CarpoolMarkdownGenerator output parses cleanly into 3-column MarkdownRenderer GFM table`() {
        val calc = (CarpoolCalculator.calculate(
            CarpoolCalculationInput(
                serviceName = "DMIT | CN2 GIA",
                totalSeats = 4,
                occupiedSeats = 3,
                totalCycleCost = BigDecimal("240.00"),
                billingCycle = CarpoolBillingCycle.QUARTERLY,
            ),
        ) as CarpoolCalculationOutcome.Success).result

        val slots = listOf(
            CarpoolSeatSlot(1, "车主|OP", CarpoolSeatStatus.OCCUPIED, "主控"),
            CarpoolSeatSlot(2, "车友A", CarpoolSeatStatus.OCCUPIED, "已付"),
            CarpoolSeatSlot(3, "车友B", CarpoolSeatStatus.OCCUPIED, "已付"),
            CarpoolSeatSlot(4, "空车位", CarpoolSeatStatus.OPEN, "可私信"),
        )

        val md = CarpoolMarkdownGenerator.generateMarkdown(calc, slots)
        val doc = parseNodeSeekMarkdownDocument(md)
        val table = doc.blocks.filterIsInstance<MdBlock.Table>().firstOrNull()
        assertNotNull("Expected generated markdown to contain MdBlock.Table", table)
        assertEquals(3, table!!.headers.size)
        assertEquals(4, table.rows.size)
        assertTrue(md.contains("`3=1`"))
        assertTrue(md.contains("翻车按天退费公式"))
    }

    @Test
    fun `CarpoolStatusUpdater handles 3=1 to full transitions and body status updates idempotently`() {
        assertEquals("3=1", CarpoolStatusUpdater.formatStatusTag(3, 4))
        assertEquals("已满车", CarpoolStatusUpdater.formatStatusTag(4, 4))

        assertEquals(
            "[拼车] Netflix 4K [已满车]",
            CarpoolStatusUpdater.updateTitle("[拼车] Netflix 4K [3=1]", "已满车"),
        )
        assertEquals(
            "[拼车] Netflix 4K 【3=1】",
            CarpoolStatusUpdater.updateTitle("[拼车] Netflix 4K 【已满车】", "3=1"),
        )
        assertEquals(
            "[3=1] Netflix 4K 车位",
            CarpoolStatusUpdater.updateTitle("Netflix 4K 车位", "3=1"),
        )
        assertEquals(
            "Netflix 4K 已满车",
            CarpoolStatusUpdater.updateTitle("Netflix 4K 3=1", "已满车"),
        )

        val bodyWithStatus = """
            ### 🚗 拼车信息 · Netflix
            **当前状态**：`3=1`（共 4 车位，已上车 3，空余 1）
            其余自定义说明文字
        """.trimIndent()

        val updatedBody = CarpoolStatusUpdater.updateMarkdownStatus(
            markdown = bodyWithStatus,
            newStatusToken = "已满车",
            occupiedSeats = 4,
            totalSeats = 4,
        )
        assertTrue(updatedBody.contains("**当前状态**：`已满车`（共 4 车位，已上车 4，空余 0）"))
        assertTrue(updatedBody.contains("其余自定义说明文字"))
        // Idempotent second call
        assertEquals(
            updatedBody,
            CarpoolStatusUpdater.updateMarkdownStatus(updatedBody, "已满车", 4, 4),
        )

        val plainBody = "自定义拼车正文，没有表格"
        val quotedOnce = CarpoolStatusUpdater.updateMarkdownStatus(plainBody, "3=1")
        val quotedTwice = CarpoolStatusUpdater.updateMarkdownStatus(quotedOnce, "已满车")
        assertTrue(quotedOnce.contains("`3=1`"))
        assertTrue(quotedTwice.contains("`已满车`"))
        assertEquals(1, Regex("车位状态更新").findAll(quotedTwice).count())
    }

    @Test
    fun `CarpoolStatusUpdater updates counts consistently for quick full transition and escapes bare CR`() {
        val calc = (CarpoolCalculator.calculate(
            CarpoolCalculationInput(
                serviceName = "Netflix\rLine2",
                totalSeats = 4,
                occupiedSeats = 3,
                totalCycleCost = BigDecimal("240.00"),
                billingCycle = CarpoolBillingCycle.QUARTERLY,
            ),
        ) as CarpoolCalculationOutcome.Success).result

        val slots = listOf(
            CarpoolSeatSlot(1, "车主|a\rb", CarpoolSeatStatus.OCCUPIED, "备注|a\r\nb\rc\\"),
            CarpoolSeatSlot(2, "车友A", CarpoolSeatStatus.OCCUPIED, "已付"),
            CarpoolSeatSlot(3, "车友B", CarpoolSeatStatus.OCCUPIED, "已付"),
            CarpoolSeatSlot(4, "虚位以待", CarpoolSeatStatus.OPEN, "欢迎"),
        )
        val generated = CarpoolMarkdownGenerator.generateMarkdown(calc, slots)
        assertFalse(generated.contains('\r'))
        val doc = parseNodeSeekMarkdownDocument(generated)
        val table = doc.blocks.filterIsInstance<MdBlock.Table>().firstOrNull()
        assertNotNull(table)
        assertEquals(4, table!!.rows.size)

        val quickFull = CarpoolStatusUpdater.updateMarkdownStatus(
            markdown = generated,
            newStatusToken = "已满车",
            occupiedSeats = null,
            totalSeats = null,
        )
        assertTrue(quickFull.contains("**当前状态**：`已满车`（共 4 车位，已上车 4，空余 0）"))
        assertFalse(quickFull.contains("空余 1"))
    }

    @Test
    fun `CarpoolStatusUpdater deduplicates multiple tags and CarpoolCalculator flags micro-share rounded to zero`() {
        val updated = CarpoolStatusUpdater.updateTitle("[3=1] Netflix [已满车]", "已满车")
        assertEquals("[已满车] Netflix", updated)
        assertEquals(1, Regex("已满车").findAll(updated).count())

        val microOutcome = CarpoolCalculator.calculate(
            CarpoolCalculationInput(
                serviceName = "MicroPool",
                totalSeats = 10,
                occupiedSeats = 1,
                totalCycleCost = BigDecimal("0.01"),
                billingCycle = CarpoolBillingCycle.MONTHLY,
            ),
        ) as CarpoolCalculationOutcome.Success
        assertEquals(BigDecimal("0.00"), microOutcome.result.perPersonCycleCostCny)
        assertTrue(microOutcome.result.isMicroShareRoundedToZero)
    }
}
