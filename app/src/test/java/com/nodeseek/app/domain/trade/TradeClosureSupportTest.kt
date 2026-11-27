package com.nodeseek.app.domain.trade

import com.nodeseek.app.domain.fleet.FleetDeliveryMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TradeClosureSupportTest {

    @Test
    fun `TradeTitleFormatter applySoldPrefix is idempotent across sold markers`() {
        assertEquals("[已出] DMIT LAX.Pro.Tiny", TradeTitleFormatter.applySoldPrefix("DMIT LAX.Pro.Tiny"))
        assertEquals("[已出] DMIT LAX.Pro.Tiny", TradeTitleFormatter.applySoldPrefix("[已出] DMIT LAX.Pro.Tiny"))
        assertEquals("【已出】DMIT LAX.Pro.Tiny", TradeTitleFormatter.applySoldPrefix("【已出】DMIT LAX.Pro.Tiny"))
        assertEquals("[SOLD] RackNerd 2C2G", TradeTitleFormatter.applySoldPrefix("[SOLD] RackNerd 2C2G"))
        assertEquals("[已出掉] Claw HK", TradeTitleFormatter.applySoldPrefix("[已出掉] Claw HK"))

        assertTrue(TradeTitleFormatter.isMarkedSold("[已出] test"))
        assertTrue(TradeTitleFormatter.isMarkedSold("test [已出]"))
        assertFalse(TradeTitleFormatter.isMarkedSold("[出] DMIT Pro 溢价 50"))
        assertEquals("DMIT Pro", TradeTitleFormatter.removeSoldPrefix("[已出] DMIT Pro"))
    }

    @Test
    fun `TradePrivacySanitizer masks IPs emails credentials order IDs while preserving links mentions and idempotency`() {
        val raw = """
            ![bench](https://img.nodeseek.com/uploads/alice@2026/shot.png)
            联系 @admin 或发邮件至 seller.user@example.com
            测试 IP：203.0.113.42 与 IPv6 2a01:4f8:c012:1234::1
            内网保留 192.168.1.10 与 DNS 1.1.1.1
            Root密码：MySecretPass123!
            Token=tok_live_987654321
            UUID: 550e8400-e29b-41d4-a716-446655440000
            订单号：20261125998877
            TG: @my_trade_handle
            QQ: 123456789
            [原帖链接](https://www.nodeseek.com/post-12345-1)
        """.trimIndent()

        val firstPass = TradePrivacySanitizer.sanitize(raw, TradePrivacyOptions())
        val out = firstPass.sanitizedMarkdown

        // Public IPs masked via IpPrivacyMasker; private/DNS preserved
        assertTrue(out.contains("203.0.*.*"))
        assertFalse(out.contains("203.0.113.42"))
        assertTrue(out.contains("2a01:4f8:*:*"))
        assertTrue(out.contains("192.168.1.10"))
        assertTrue(out.contains("1.1.1.1"))

        // Emails masked; Markdown image URL, link URL, and @mentions untouched
        assertTrue(out.contains("s***@example.com"))
        assertFalse(out.contains("seller.user@example.com"))
        assertTrue(out.contains("![bench](https://img.nodeseek.com/uploads/alice@2026/shot.png)"))
        assertTrue(out.contains("[原帖链接](https://www.nodeseek.com/post-12345-1)"))
        assertTrue(out.contains("@admin"))

        // Credentials masked
        assertFalse(out.contains("MySecretPass123!"))
        assertFalse(out.contains("tok_live_987654321"))
        assertFalse(out.contains("550e8400-e29b-41d4-a716-446655440000"))
        assertTrue(out.contains("******"))

        // Order & contact IDs masked
        assertFalse(out.contains("20261125998877"))
        assertFalse(out.contains("my_trade_handle"))
        assertFalse(out.contains("123456789"))
        assertTrue(out.contains("****"))

        // Closure note appended
        assertTrue(out.contains(TradePrivacySanitizer.CLOSURE_NOTE_MARKER))
        assertTrue(firstPass.redactionCount >= 6)
        assertTrue(firstPass.changed)

        // Strict idempotency: sanitize(sanitize(x)) == sanitize(x)
        val secondPass = TradePrivacySanitizer.sanitize(out, TradePrivacyOptions())
        assertEquals(out, secondPass.sanitizedMarkdown)
        assertEquals(0, secondPass.redactionCount)
        assertFalse(secondPass.closureNoteAppended)
        assertFalse(secondPass.changed)
    }

    @Test
    fun `TradePrivacySanitizer respects disabled toggles`() {
        val raw = "IP 203.0.113.42 邮箱 a@b.com 密码: abc"
        val onlyIp = TradePrivacySanitizer.sanitize(
            raw,
            TradePrivacyOptions(
                maskIpAddresses = true,
                maskEmails = false,
                maskCredentials = false,
                maskOrderAndContactIds = false,
                appendClosureNote = false,
            ),
        )
        assertTrue(onlyIp.sanitizedMarkdown.contains("203.0.*.*"))
        assertTrue(onlyIp.sanitizedMarkdown.contains("a@b.com"))
        assertTrue(onlyIp.sanitizedMarkdown.contains("abc"))
        assertFalse(onlyIp.sanitizedMarkdown.contains(TradePrivacySanitizer.CLOSURE_NOTE_MARKER))
    }

    @Test
    fun `TradeDeliveryChecklistEngine infers delivery method and returns checklists and handover templates`() {
        assertEquals(
            FleetDeliveryMethod.ORIGINAL_EMAIL,
            TradeDeliveryChecklistEngine.inferDeliveryMethod("[出] DMIT Pro 带原油出", ""),
        )
        assertEquals(
            FleetDeliveryMethod.CHANGE_EMAIL,
            TradeDeliveryChecklistEngine.inferDeliveryMethod("[出] Claw HK", "支持改邮交付"),
        )
        assertEquals(
            FleetDeliveryMethod.PUSH,
            TradeDeliveryChecklistEngine.inferDeliveryMethod("[出] RackNerd 2C2G 包Push", ""),
        )
        assertEquals(
            FleetDeliveryMethod.EITHER,
            TradeDeliveryChecklistEngine.inferDeliveryMethod("[出] 绿云 软银", "Push 或改邮均可"),
        )

        FleetDeliveryMethod.entries.forEach { method ->
            val steps = TradeDeliveryChecklistEngine.checklistFor(method)
            assertTrue(steps.size >= 5)
            assertTrue(steps.map { it.id }.distinct().size == steps.size)
            val msg = TradeDeliveryChecklistEngine.buildHandoverMessage(method, "[已出] DMIT Pro")
            assertTrue(msg.contains("DMIT Pro"))
            assertFalse(msg.contains("[已出] DMIT Pro"))
        }
    }

    @Test
    fun `TradePrivacySanitizer masks quoted and email-shaped credentials preserves newlines and image URL IPs`() {
        val quoted = TradePrivacySanitizer.sanitize(
            """password: "hunter2" and token='tok_live_999'""",
            TradePrivacyOptions(appendClosureNote = false),
        )
        assertFalse(quoted.sanitizedMarkdown.contains("hunter2"))
        assertFalse(quoted.sanitizedMarkdown.contains("tok_live_999"))
        assertTrue(quoted.sanitizedMarkdown.contains("""password: "******""""))
        assertTrue(quoted.sanitizedMarkdown.contains("""token='******'"""))
        assertEquals(2, quoted.credentialRedactions)

        val newlineAfterColon = TradePrivacySanitizer.sanitize(
            "密码:\n这是一台好机器",
            TradePrivacyOptions(appendClosureNote = false),
        )
        assertEquals("密码:\n这是一台好机器", newlineAfterColon.sanitizedMarkdown)
        assertEquals(0, newlineAfterColon.redactionCount)

        val emailCredential = TradePrivacySanitizer.sanitize(
            "密码: secret@domain.com",
            TradePrivacyOptions(appendClosureNote = false),
        )
        assertEquals("密码: ******", emailCredential.sanitizedMarkdown)
        assertFalse(emailCredential.sanitizedMarkdown.contains("domain.com"))
        assertEquals(1, emailCredential.credentialRedactions)
        assertEquals(0, emailCredential.emailRedactions)

        val imageWithIp = TradePrivacySanitizer.sanitize(
            "![speed](https://203.0.113.42/bench.png) 实测 IP 203.0.113.42",
            TradePrivacyOptions(appendClosureNote = false),
        )
        assertTrue(imageWithIp.sanitizedMarkdown.contains("![speed](https://203.0.113.42/bench.png)"))
        assertTrue(imageWithIp.sanitizedMarkdown.contains("实测 IP 203.0.*.*"))
        assertEquals(1, imageWithIp.ipRedactions)
    }

    @Test
    fun `TradePrivacySanitizer masks scheme-less t me handle and avoids quadratic backtracking on malformed links`() {
        val tgInput = "TG: t.me/my_trade_handle\n官网: https://t.me/public_channel"
        val tgFirst = TradePrivacySanitizer.sanitize(tgInput, TradePrivacyOptions(appendClosureNote = false))
        assertFalse(tgFirst.sanitizedMarkdown.contains("my_trade_handle"))
        assertTrue(tgFirst.sanitizedMarkdown.contains("https://t.me/public_channel"))
        assertEquals(1, tgFirst.orderAndContactRedactions)
        val tgSecond = TradePrivacySanitizer.sanitize(tgFirst.sanitizedMarkdown, TradePrivacyOptions(appendClosureNote = false))
        assertEquals(tgFirst.sanitizedMarkdown, tgSecond.sanitizedMarkdown)
        assertEquals(0, tgSecond.redactionCount)

        val pathological = "[a](b".repeat(10_000) + " " + "user@".repeat(5_000) + " password: " + "x".repeat(5_000)
        val startNs = System.nanoTime()
        val result = TradePrivacySanitizer.sanitize(pathological, TradePrivacyOptions(appendClosureNote = false))
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue("Expected linear-time sanitization (< 500ms), took ${elapsedMs}ms", elapsedMs < 500L)
        assertTrue(result.sanitizedMarkdown.endsWith("password: ******"))
    }

    @Test
    fun `TradePrivacySanitizer bounds unclosed brackets and parens and preserves balanced URL parentheses`() {
        // 1. Unclosed `[` across lines must not swallow secrets before a later `[label](url)`
        val unclosedLinkInput = "[出 DMIT Pro\n密码: hunter2\nIP: 203.0.113.42\n[原帖](https://www.nodeseek.com/post-1-1)"
        val unclosedLinkResult = TradePrivacySanitizer.sanitize(
            unclosedLinkInput,
            TradePrivacyOptions(appendClosureNote = false),
        )
        assertFalse(unclosedLinkResult.sanitizedMarkdown.contains("hunter2"))
        assertFalse(unclosedLinkResult.sanitizedMarkdown.contains("203.0.113.42"))
        assertTrue(unclosedLinkResult.sanitizedMarkdown.contains("密码: ******"))
        assertTrue(unclosedLinkResult.sanitizedMarkdown.contains("IP: 203.0.*.*"))
        assertTrue(unclosedLinkResult.sanitizedMarkdown.contains("[原帖](https://www.nodeseek.com/post-1-1)"))

        // Also check nested `[` on the same line
        val nestedBracketInput = "[outer [inner](https://example.com/203.0.113.42) password: hunter2"
        val nestedBracketResult = TradePrivacySanitizer.sanitize(
            nestedBracketInput,
            TradePrivacyOptions(appendClosureNote = false),
        )
        assertTrue(nestedBracketResult.sanitizedMarkdown.contains("[inner](https://example.com/203.0.113.42)"))
        assertFalse(nestedBracketResult.sanitizedMarkdown.contains("hunter2"))

        // 2. Unclosed `![` across lines must not swallow secrets
        val unclosedImageInput = "![broken\n密码: hunter2\nIP: 203.0.113.42\n[speed](https://example.com/bench.png)"
        val unclosedImageResult = TradePrivacySanitizer.sanitize(
            unclosedImageInput,
            TradePrivacyOptions(appendClosureNote = false),
        )
        assertFalse(unclosedImageResult.sanitizedMarkdown.contains("hunter2"))
        assertFalse(unclosedImageResult.sanitizedMarkdown.contains("203.0.113.42"))
        assertTrue(unclosedImageResult.sanitizedMarkdown.contains("密码: ******"))
        assertTrue(unclosedImageResult.sanitizedMarkdown.contains("IP: 203.0.*.*"))
        assertTrue(unclosedImageResult.sanitizedMarkdown.contains("[speed](https://example.com/bench.png)"))

        // 3. Same-line unclosed `(` in `[a](b` must not swallow secrets before `[c](url)`
        val sameLineUnclosedParen = "[a](b password: hunter2 [c](https://example.com/x)"
        val sameLineResult = TradePrivacySanitizer.sanitize(
            sameLineUnclosedParen,
            TradePrivacyOptions(appendClosureNote = false),
        )
        assertFalse(sameLineResult.sanitizedMarkdown.contains("hunter2"))
        assertTrue(sameLineResult.sanitizedMarkdown.contains("password: ******"))
        assertTrue(sameLineResult.sanitizedMarkdown.contains("[c](https://example.com/x)"))

        // Angle-bracketed & quoted-title destinations remain protected
        val angleAndTitle = "[a](<https://example.com/a b/203.0.113.42> \"my title\") password: hunter2"
        val angleAndTitleResult = TradePrivacySanitizer.sanitize(
            angleAndTitle,
            TradePrivacyOptions(appendClosureNote = false),
        )
        assertTrue(angleAndTitleResult.sanitizedMarkdown.contains("[a](<https://example.com/a b/203.0.113.42> \"my title\")"))
        assertFalse(angleAndTitleResult.sanitizedMarkdown.contains("hunter2"))

        // 4. Balanced nested parentheses in image/link destinations and bare URLs
        val nestedParensImage = "![alt](https://example.com/(1)/203.0.113.42)"
        assertEquals(
            nestedParensImage,
            TradePrivacySanitizer.sanitize(nestedParensImage, TradePrivacyOptions(appendClosureNote = false)).sanitizedMarkdown,
        )

        val nestedParensLink = "[report](https://example.com/path_(v1)?u=secret@domain.com)"
        assertEquals(
            nestedParensLink,
            TradePrivacySanitizer.sanitize(nestedParensLink, TradePrivacyOptions(appendClosureNote = false)).sanitizedMarkdown,
        )

        val nestedParensBareUrl = "https://example.com/(1)/203.0.113.42"
        assertEquals(
            nestedParensBareUrl,
            TradePrivacySanitizer.sanitize(nestedParensBareUrl, TradePrivacyOptions(appendClosureNote = false)).sanitizedMarkdown,
        )

        // Outer wrapping parens around bare URLs strip outer `)` while keeping inner balanced parens
        val wrappedBareUrl = "(https://example.com/(1)/203.0.113.42) password: hunter2"
        val wrappedBareResult = TradePrivacySanitizer.sanitize(
            wrappedBareUrl,
            TradePrivacyOptions(appendClosureNote = false),
        )
        assertTrue(wrappedBareResult.sanitizedMarkdown.contains("(https://example.com/(1)/203.0.113.42)"))
        assertFalse(wrappedBareResult.sanitizedMarkdown.contains("hunter2"))
    }

    @Test
    fun `TradePrivacySanitizer sustains strict linear-time performance across adversarial inputs`() {
        val payloads = listOf(
            "[a](b".repeat(10_000),
            "[a\n".repeat(5_000) + "[b](https://example.com)",
            "[a]([b]([c](".repeat(3_000),
            "(".repeat(10_000),
            "![alt](https://example.com/" + "(".repeat(5_000),
        )
        // Warm up JIT once before measuring strict < 100ms budgets
        TradePrivacySanitizer.sanitize("[a](b".repeat(100), TradePrivacyOptions(appendClosureNote = false))

        payloads.forEachIndexed { idx, payload ->
            val startNs = System.nanoTime()
            TradePrivacySanitizer.sanitize(payload, TradePrivacyOptions(appendClosureNote = false))
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            assertTrue("Adversarial payload #$idx exceeded 100ms: ${elapsedMs}ms", elapsedMs < 100L)
        }
    }

    @Test
    fun `TradePrivacySanitizer stops bare URLs at table pipes non-ASCII punctuation and control chars`() {
        val opts = TradePrivacyOptions(appendClosureNote = false)

        val chineseComma = TradePrivacySanitizer.sanitize(
            "探针：https://example.com/bench，密码：hunter2，IP：203.0.113.42",
            opts,
        )
        assertEquals(
            "探针：https://example.com/bench，密码：******，IP：203.0.*.*",
            chineseComma.sanitizedMarkdown,
        )
        assertEquals(1, chineseComma.credentialRedactions)
        assertEquals(1, chineseComma.ipRedactions)

        val compactTable = TradePrivacySanitizer.sanitize(
            "|https://example.com/bench|密码:hunter2|203.0.113.42|",
            opts,
        )
        assertEquals(
            "|https://example.com/bench|密码:******|203.0.*.*|",
            compactTable.sanitizedMarkdown,
        )
        assertEquals(1, compactTable.credentialRedactions)
        assertEquals(1, compactTable.ipRedactions)

        val fullwidthParens = TradePrivacySanitizer.sanitize(
            "测速https://example.com/bench（IP：203.0.113.42，密码：hunter2）",
            opts,
        )
        assertEquals(
            "测速https://example.com/bench（IP：203.0.*.*，密码：******）",
            fullwidthParens.sanitizedMarkdown,
        )
        assertEquals(1, fullwidthParens.credentialRedactions)
        assertEquals(1, fullwidthParens.ipRedactions)

        // Control chars (< 0x20) terminate bare URLs while valid ASCII commas/semicolons in URLs remain preserved
        val controlSeparated = TradePrivacySanitizer.sanitize(
            "https://example.com/map?ll=1,2;m=3\u0007,password:hunter2",
            opts,
        )
        assertEquals(
            "https://example.com/map?ll=1,2;m=3\u0007,password:******",
            controlSeparated.sanitizedMarkdown,
        )
        assertEquals(1, controlSeparated.credentialRedactions)
    }

    @Test
    fun `TradePrivacySanitizer masks credentials contacts and IPs across CJK brackets punctuation and control char boundaries with strict idempotency`() {
        val cases = listOf(
            "（TG：my_handle）" to "（TG：my****le）",
            "（订单号：12345678）" to "（订单号：12****78）",
            "测速https://example.com/bench（TG：my_handle，密码：hunter2）" to "测速https://example.com/bench（TG：my****le，密码：******）",
            "（QQ：12345678）" to "（QQ：12****78）",
            "（微信：wxid_secret99）" to "（微信：wx****99）",
            "（TG：t.me/my_handle）" to "（TG：t.me/my****le）",
            "【TG：my_handle】" to "【TG：my****le】",
            "「TG：my_handle」" to "「TG：my****le」",
            "『TG：my_handle』" to "『TG：my****le』",
            "（TG：my_handle，QQ：12345678）" to "（TG：my****le，QQ：12****78）",
            "已测。订单号：12345678" to "已测。订单号：12****78",
            "已测、QQ：12345678" to "已测、QQ：12****78",
            "【密码：hunter2】" to "【密码：******】",
            "「密码：hunter2」" to "「密码：******」",
            "『密码：hunter2』" to "『密码：******』",
            "https://example.com/a\u0000password:secret" to "https://example.com/a\u0000password:******",
            "https://example.com/a\u001F密码:secret" to "https://example.com/a\u001F密码:******",
            "https://example.com/a\u007F203.0.113.42" to "https://example.com/a\u007F203.0.*.*",
            "https://example.com/a\u007Fpassword:secret" to "https://example.com/a\u007Fpassword:******",
            "https://example.com/a\u0080密码:secret" to "https://example.com/a\u0080密码:******",
            "https://example.com/bench\u0000TG:my_handle" to "https://example.com/bench\u0000TG:my****le",
            "https://example.com/bench\u007FTG:my_handle" to "https://example.com/bench\u007FTG:my****le",
            "https://example.com/bench\u0080TG:my_handle" to "https://example.com/bench\u0080TG:my****le",
            "https://example.com/bench。密码：hunter2" to "https://example.com/bench。密码：******",
            "https://example.com/bench：密码：hunter2" to "https://example.com/bench：密码：******",
            "https://example.com/bench、密码：hunter2" to "https://example.com/bench、密码：******",
            "https://example.com/bench【密码：hunter2】" to "https://example.com/bench【密码：******】",
            "密码：hunter2。订单号：12345678" to "密码：******。订单号：12****78",
            "password:hunter2\u0000next" to "password:******\u0000next",
            "password:hunter2\u007Fnext" to "password:******\u007Fnext",
        )

        val noNoteOpts = TradePrivacyOptions(appendClosureNote = false)
        val noteOpts = TradePrivacyOptions(appendClosureNote = true)

        cases.forEach { (input, expected) ->
            val firstNoNote = TradePrivacySanitizer.sanitize(input, noNoteOpts)
            assertEquals("Failed sanitization (no note) for: $input", expected, firstNoNote.sanitizedMarkdown)
            assertTrue("Expected redactionCount > 0 for: $input", firstNoNote.redactionCount > 0)

            val secondNoNote = TradePrivacySanitizer.sanitize(firstNoNote.sanitizedMarkdown, noNoteOpts)
            assertEquals("Failed idempotency (no note) for: $input", firstNoNote.sanitizedMarkdown, secondNoNote.sanitizedMarkdown)
            assertEquals("Expected 0 redactions on second pass (no note) for: $input", 0, secondNoNote.redactionCount)
            assertFalse("Expected changed=false on second pass (no note) for: $input", secondNoNote.changed)

            val firstWithNote = TradePrivacySanitizer.sanitize(input, noteOpts)
            assertTrue(firstWithNote.sanitizedMarkdown.startsWith(expected))
            assertTrue(firstWithNote.closureNoteAppended)

            val secondWithNote = TradePrivacySanitizer.sanitize(firstWithNote.sanitizedMarkdown, noteOpts)
            assertEquals("Failed idempotency (with note) for: $input", firstWithNote.sanitizedMarkdown, secondWithNote.sanitizedMarkdown)
            assertEquals("Expected 0 redactions on second pass (with note) for: $input", 0, secondWithNote.redactionCount)
            assertFalse("Expected changed=false on second pass (with note) for: $input", secondWithNote.changed)
        }

        // Preserve non-delimited ASCII false-positive lookalikes untouched
        val benign = "foo:pass:123 user.token:abc https://t.me/public_channel"
        val benignResult = TradePrivacySanitizer.sanitize(benign, noNoteOpts)
        assertEquals(benign, benignResult.sanitizedMarkdown)
        assertEquals(0, benignResult.redactionCount)
    }

    @Test
    fun `TradePrivacySanitizer masks credentials and contacts across unicode whitespace and opening CJK brackets punctuation with strict idempotency`() {
        val cases = listOf(
            "　密码：hunter2" to "　密码：******",
            "https://example.com/bench　密码：hunter2" to "https://example.com/bench　密码：******",
            "　TG：my_handle" to "　TG：my****le",
            "　　订单号：12345678" to "　　订单号：12****78",
            "\u00A0TG:my_handle" to "\u00A0TG:my****le",
            "https://example.com/bench\u00A0password:secret" to "https://example.com/bench\u00A0password:******",
            "密码：hunter2　TG：my_handle" to "密码：******　TG：my****le",
            "密码：\u00A0hunter2" to "密码：\u00A0******",
            "密码\u3000：\u3000hunter2" to "密码\u3000：\u3000******",
            "订单号\u3000：\u300012345678" to "订单号\u3000：\u300012****78",
            "密码：hunter2\u200BTG：my_handle" to "密码：******\u200BTG：my****le",
            "\uFEFF密码：hunter2" to "\uFEFF密码：******",
            "密码：hunter2（仅测试）" to "密码：******（仅测试）",
            "密码：hunter2【测试】" to "密码：******【测试】",
            "密码：hunter2「测试」" to "密码：******「测试」",
            "密码：hunter2『测试』" to "密码：******『测试』",
            "密码：hunter2！已重装" to "密码：******！已重装",
            "密码：hunter2！TG：my_handle" to "密码：******！TG：my****le",
            "密码：hunter2？" to "密码：******？",
            "密码：hunter2？TG：my_handle" to "密码：******？TG：my****le",
            "密码：hunter2：TG：my_handle" to "密码：******：TG：my****le",
        )

        val noNoteOpts = TradePrivacyOptions(appendClosureNote = false)
        val noteOpts = TradePrivacyOptions(appendClosureNote = true)

        cases.forEach { (input, expected) ->
            val firstNoNote = TradePrivacySanitizer.sanitize(input, noNoteOpts)
            assertEquals("Failed sanitization (no note) for: $input", expected, firstNoNote.sanitizedMarkdown)
            assertTrue("Expected redactionCount > 0 for: $input", firstNoNote.redactionCount > 0)

            val secondNoNote = TradePrivacySanitizer.sanitize(firstNoNote.sanitizedMarkdown, noNoteOpts)
            assertEquals("Failed idempotency (no note) for: $input", firstNoNote.sanitizedMarkdown, secondNoNote.sanitizedMarkdown)
            assertEquals("Expected 0 redactions on second pass (no note) for: $input", 0, secondNoNote.redactionCount)
            assertFalse("Expected changed=false on second pass (no note) for: $input", secondNoNote.changed)

            val firstWithNote = TradePrivacySanitizer.sanitize(input, noteOpts)
            assertTrue("Failed sanitization (with note) for: $input", firstWithNote.sanitizedMarkdown.startsWith(expected.trimEnd()))
            assertTrue("Expected closureNoteAppended=true for: $input", firstWithNote.closureNoteAppended)

            val secondWithNote = TradePrivacySanitizer.sanitize(firstWithNote.sanitizedMarkdown, noteOpts)
            assertEquals("Failed idempotency (with note) for: $input", firstWithNote.sanitizedMarkdown, secondWithNote.sanitizedMarkdown)
            assertEquals("Expected 0 redactions on second pass (with note) for: $input", 0, secondWithNote.redactionCount)
            assertFalse("Expected changed=false on second pass (with note) for: $input", secondWithNote.changed)
        }

        // Horizontal Unicode spacing around separators must still never cross CR/LF newlines
        val newlineAfterNbsp = TradePrivacySanitizer.sanitize("密码：\u00A0\n正文内容", noNoteOpts)
        assertEquals("密码：\u00A0\n正文内容", newlineAfterNbsp.sanitizedMarkdown)
        assertEquals(0, newlineAfterNbsp.redactionCount)
    }

    @Test
    fun `TradePrivacySanitizer handles symmetric Unicode categories curly quotes emojis password symbols and O(N) unclosed quotes`() {
        val cases = listOf(
            // Challenger R6 asymmetric ASCII '(' regression cases
            "密码：hunter2(仅测试)" to "密码：******(仅测试)",
            "密码：hunter2(TG:my_handle)" to "密码：******(TG:my****le)",
            "密码：hunter2[TG:my_handle]" to "密码：******[TG:my****le]",
            // Symmetric Unicode Ps/Pe/Pi/Pf brackets and quotes
            "密码：hunter2{仅测试}" to "密码：******{仅测试}",
            "密码：hunter2{TG:my_handle}" to "密码：******{TG:my****le}",
            "密码：hunter2《仅测试》" to "密码：******《仅测试》",
            "密码：hunter2“仅测试”" to "密码：******“仅测试”",
            "密码：hunter2‘仅测试’" to "密码：******‘仅测试’",
            "密码：hunter2«TG:my_handle»" to "密码：******«TG:my****le»",
            "《订单号: 12345678》" to "《订单号: 12****78》",
            "{TG: my_handle}" to "{TG: my****le}",
            "〈密码：hunter2〉" to "〈密码：******〉",
            "〔密码：hunter2〕" to "〔密码：******〕",
            "【配置】密码：hunter2" to "【配置】密码：******",
            "（已测）TG: my_handle" to "（已测）TG: my****le",
            // Quoted whole KVs and quoted values (ASCII + symmetric curly quotes)
            "\"TG: my_handle\"" to "\"TG: my****le\"",
            "\"password: hunter2\"" to "\"password: ******\"",
            "`TG: my_handle`" to "`TG: my****le`",
            "'password: hunter2'" to "'password: ******'",
            "“TG: my_handle”" to "“TG: my****le”",
            "密码：“hunter2”" to "密码：“******”",
            "TG: “my_handle”" to "TG: “my****le”",
            "密码：‘hunter2’" to "密码：‘******’",
            "TG: ‘my_handle’" to "TG: ‘my****le’",
            // Non-ASCII dashes, fullwidth math/pipe symbols, supplementary UTF-16 emojis (So), PUA, Zl/Zp
            "密码：hunter2—TG：my_handle" to "密码：******—TG：my****le",
            "密码：hunter2～TG：my_handle" to "密码：******～TG：my****le",
            "密码：hunter2｜TG：my_handle" to "密码：******｜TG：my****le",
            "密码：hunter2📦TG：my_handle" to "密码：******📦TG：my****le",
            "🔑密码：hunter2" to "🔑密码：******",
            "密码：\uE000pass123" to "密码：******",
            "密码：hunter2\u2029TG：my_handle" to "密码：******\u2029TG：my****le",
            // Emails with brackets and Unicode spaces
            "（a@b.com）" to "（a***@b.com）",
            "(a@b.com)" to "(a***@b.com)",
            "《a@b.com》" to "《a***@b.com》",
            "\u00A0a@b.com\u3000" to "\u00A0a***@b.com\u3000",
            // Additional Unicode Zs / Cf / Markdown wrappers
            "\u2003密码：\u2009hunter2\u202FTG：\u2002my_handle" to "\u2003密码：\u2009******\u202FTG：\u2002my****le",
            "Done! password: secret" to "Done! password: ******",
            "What? TG: my_handle" to "What? TG: my****le",
            "**密码：hunter2**" to "**密码：******",
            ">密码：hunter2" to ">密码：******",
            "invoice_id: 12345678" to "invoice_id: 12****78",
            "order\u3000id\u00A0：\u200912345678" to "order\u3000id\u00A0：\u200912****78",
            "root\u00A0密码\u3000：\u00A0hunter2" to "root\u00A0密码\u3000：\u00A0******",
            // ASCII password-allowed symbols must NOT truncate mid-password
            "密码：Pass!word123" to "密码：******",
            "密码：Pass?word123" to "密码：******",
            "初始密码：Claw@2026#HK" to "初始密码：******",
            "Token：YWJjMTIz==" to "Token：******",
            "Root密码：MySecretPass123! Token=tok_live_987654321 UUID: 550e8400-e29b-41d4-a716-446655440000" to
                "Root密码：****** Token=****** UUID: ******",
        )

        val noNoteOpts = TradePrivacyOptions(appendClosureNote = false)
        val noteOpts = TradePrivacyOptions(appendClosureNote = true)

        cases.forEach { (input, expected) ->
            val firstNoNote = TradePrivacySanitizer.sanitize(input, noNoteOpts)
            assertEquals("Failed sanitization (no note) for: $input", expected, firstNoNote.sanitizedMarkdown)
            assertTrue("Expected redactionCount > 0 for: $input", firstNoNote.redactionCount > 0)

            val secondNoNote = TradePrivacySanitizer.sanitize(firstNoNote.sanitizedMarkdown, noNoteOpts)
            assertEquals("Failed idempotency (no note) for: $input", firstNoNote.sanitizedMarkdown, secondNoNote.sanitizedMarkdown)
            assertEquals("Expected 0 redactions on second pass (no note) for: $input", 0, secondNoNote.redactionCount)
            assertFalse("Expected changed=false on second pass (no note) for: $input", secondNoNote.changed)

            val firstWithNote = TradePrivacySanitizer.sanitize(input, noteOpts)
            assertTrue("Failed sanitization (with note) for: $input", firstWithNote.sanitizedMarkdown.startsWith(expected.trimEnd()))
            assertTrue("Expected closureNoteAppended=true for: $input", firstWithNote.closureNoteAppended)

            val secondWithNote = TradePrivacySanitizer.sanitize(firstWithNote.sanitizedMarkdown, noteOpts)
            assertEquals("Failed idempotency (with note) for: $input", firstWithNote.sanitizedMarkdown, secondWithNote.sanitizedMarkdown)
            assertEquals("Expected 0 redactions on second pass (with note) for: $input", 0, secondWithNote.redactionCount)
            assertFalse("Expected changed=false on second pass (with note) for: $input", secondWithNote.changed)
        }

        // Untouched lookalikes, empty/bracketed placeholders, and CR/LF / Zl boundaries
        val untouched = listOf(
            "foo:pass:123 user.token:abc x-password:secret my_token:secret a/password:secret https://t.me/public_channel",
            "密码：（仅测试）",
            "密码：(仅测试)",
            "密码：{仅测试}",
            "密码：\u3000\r\n正文内容",
            "密码：\u2028正文内容",
            "root: hunter2",
        )
        untouched.forEach { input ->
            val res = TradePrivacySanitizer.sanitize(input, noNoteOpts)
            assertEquals("Expected untouched for: $input", input, res.sanitizedMarkdown)
            assertEquals("Expected 0 redactions for: $input", 0, res.redactionCount)
        }

        // O(N) unclosed-quote stress test (must stay strictly linear across ASCII & curly quotes)
        TradePrivacySanitizer.sanitize("password: \"".repeat(50) + " token: secret", noNoteOpts)
        listOf(
            "password: \"".repeat(5_000) + " token: secret",
            "password: “".repeat(5_000) + " token: secret",
        ).forEach { unclosedQuoteStress ->
            val startNs = System.nanoTime()
            val stressResult = TradePrivacySanitizer.sanitize(unclosedQuoteStress, noNoteOpts)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            assertTrue("Unclosed-quote stress took ${elapsedMs}ms", elapsedMs < 100L)
            assertTrue(stressResult.sanitizedMarkdown.endsWith("token: ******"))
        }
    }

    @Test
    fun `TradePrivacySanitizer handles repeated curly quotes with trailing close in O(N) time and multi-line multi-quote boundaries`() {
        val noNoteOpts = TradePrivacyOptions(appendClosureNote = false)
        // JIT warmup
        TradePrivacySanitizer.sanitize("TG: “".repeat(100) + "”", noNoteOpts)

        listOf(
            "TG: “".repeat(20_000) + "”",
            "TG: ‘".repeat(10_000) + "’",
            "订单号: “".repeat(10_000) + "”",
        ).forEach { payload ->
            val startNs = System.nanoTime()
            val res = TradePrivacySanitizer.sanitize(payload, noNoteOpts)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            assertTrue("Curly-quote trailing close stress (len=${payload.length}) took ${elapsedMs}ms", elapsedMs < 100L)
            assertEquals(payload, res.sanitizedMarkdown)
            assertEquals(0, res.redactionCount)
        }

        // Verify nextCloseQuoteByKind never over-caches past a closing quote or across line breaks
        val multiQuoteCases = listOf(
            "TG: “hello world” TG: “my_handle”" to "TG: “hello world” TG: “my****le”",
            "TG: “bad1 TG: “bad2” TG: “good_val”" to "TG: “bad1 TG: “****” TG: “go****al”",
            "TG: “unclosed\nTG: “good_val”" to "TG: “unclosed\nTG: “go****al”",
            "TG: “” TG: “my_handle”" to "TG: “” TG: “my****le”",
            "TG: “hello world” TG: “unclosed" to "TG: “hello world” TG: “unclosed",
            "TG: “my_handle”\nTG: “other_handle”" to "TG: “my****le”\nTG: “ot****le”",
        )
        multiQuoteCases.forEach { (input, expected) ->
            val first = TradePrivacySanitizer.sanitize(input, noNoteOpts)
            assertEquals("Failed multi-quote case: $input", expected, first.sanitizedMarkdown)
            val second = TradePrivacySanitizer.sanitize(first.sanitizedMarkdown, noNoteOpts)
            assertEquals("Failed multi-quote idempotency: $input", first.sanitizedMarkdown, second.sanitizedMarkdown)
            assertEquals(0, second.redactionCount)
        }
    }

    @Test
    fun `TradePrivacySanitizer handles emoji VS16 and keycap leading boundaries while ignoring combining accents`() {
        val noNoteOpts = TradePrivacyOptions(appendClosureNote = false)
        val positiveCases = listOf(
            "⚠️密码：hunter2" to "⚠️密码：******",
            "☁️password: secret" to "☁️password: ******",
            "1️⃣密码：hunter2" to "1️⃣密码：******",
            "⚠️TG：my_handle" to "⚠️TG：my****le",
            "⚠️订单号：12345678" to "⚠️订单号：12****78",
            "1️⃣TG：my_handle" to "1️⃣TG：my****le",
            "ℹ️密码：hunter2" to "ℹ️密码：******",
            "1\u20E3密码：hunter2" to "1\u20E3密码：******",
            "⚠︎密码：hunter2" to "⚠︎密码：******",
        )
        positiveCases.forEach { (input, expected) ->
            val first = TradePrivacySanitizer.sanitize(input, noNoteOpts)
            assertEquals("Failed emoji boundary masking for: $input", expected, first.sanitizedMarkdown)
            assertEquals("Expected 1 redaction for: $input", 1, first.redactionCount)

            val second = TradePrivacySanitizer.sanitize(first.sanitizedMarkdown, noNoteOpts)
            assertEquals(first.sanitizedMarkdown, second.sanitizedMarkdown)
            assertEquals(0, second.redactionCount)
        }

        val untouchedCases = listOf(
            "a\u0301password: secret",
            "a\u0301密码：hunter2",
        )
        untouchedCases.forEach { input ->
            val res = TradePrivacySanitizer.sanitize(input, noNoteOpts)
            assertEquals("Expected untouched combining-mark case: $input", input, res.sanitizedMarkdown)
            assertEquals(0, res.redactionCount)
        }
    }

    @Test
    fun `TradePrivacySanitizer masks contact and order identifiers longer than 64 chars without leaking tail`() {
        val noNoteOpts = TradePrivacyOptions(appendClosureNote = false)
        val cases = listOf(
            ("TG: " + "a".repeat(70)) to "TG: aa****aa",
            ("订单号: " + "9".repeat(80)) to "订单号: 99****99",
            ("TG: t.me/" + "b".repeat(70)) to "TG: t.me/bb****bb",
            ("TG: “" + "c".repeat(70) + "”") to "TG: “cc****cc”",
        )
        cases.forEach { (input, expected) ->
            val first = TradePrivacySanitizer.sanitize(input, noNoteOpts)
            assertEquals("Failed long-identifier masking for len=${input.length}", expected, first.sanitizedMarkdown)
            assertEquals(1, first.orderAndContactRedactions)

            val second = TradePrivacySanitizer.sanitize(first.sanitizedMarkdown, noNoteOpts)
            assertEquals(first.sanitizedMarkdown, second.sanitizedMarkdown)
            assertEquals(0, second.redactionCount)
        }

        // Full https://t.me/... URL remains a protected link without partial tail masking
        val protectedTgUrl = "TG: https://t.me/" + "b".repeat(70)
        val urlResult = TradePrivacySanitizer.sanitize(protectedTgUrl, noNoteOpts)
        assertEquals(protectedTgUrl, urlResult.sanitizedMarkdown)
        assertEquals(0, urlResult.redactionCount)
    }

    @Test
    fun `TradePrivacySanitizer masks compound credential keys and emoji-containing bare credential values`() {
        val noNoteOpts = TradePrivacyOptions(appendClosureNote = false)
        val cases = listOf(
            "测试密码: TestPass111" to "测试密码: ******",
            "测试密码: “TopSecret_255”" to "测试密码: “******”",
            "临时密码: TempPass222" to "临时密码: ******",
            "VPS密码: VpsPass333" to "VPS密码: ******",
            "管理密码: AdminPass444" to "管理密码: ******",
            "备用密码: BackupPass555" to "备用密码: ******",
            "新密码: NewPass666" to "新密码: ******",
            "面板密码: PanelPass777" to "面板密码: ******",
            "小鸡密码: ChickPass888" to "小鸡密码: ******",
            "机器密码: BoxPass999" to "机器密码: ******",
            "面板登录密码: StackedPass000" to "面板登录密码: ******",
            "admin password: AdminSecret123" to "admin password: ******",
            "工单密码: CustomHanPass" to "工单密码: ******",
            "密码: 🔑EmojiStartSecret" to "密码: ******",
            "密码: Mid1🔑Mid2Secret" to "密码: ******",
            "密码: 👨‍👩‍👧‍👦FamilySecret" to "密码: ******",
            "密码: 1️⃣KeycapSecret" to "密码: ******",
            "密码: 👩🏽‍💻SkinToneSecret" to "密码: ******",
            "密码: Mid1🔑Mid2Secret📦TG: my_handle" to "密码: ******📦TG: my****le",
            // Keycap & emoji-starting bare credential boundaries before next KV
            "密码：hunter21️⃣TG：my_handle" to "密码：******1️⃣TG：my****le",
            "密码：hunter2#️⃣TG：my_handle" to "密码：******#️⃣TG：my****le",
            "密码：hunter2*️⃣TG：my_handle" to "密码：*******️⃣TG：my****le",
            "密码：hunter21\u20E3TG：my_handle" to "密码：******1\u20E3TG：my****le",
            "密码：hunter2👩🏽‍💻TG：my_handle" to "密码：******👩🏽‍💻TG：my****le",
            "密码：hunter21️⃣🔑TG：my_handle" to "密码：******1️⃣🔑TG：my****le",
            "密码：🔑TG：my_handle" to "密码：******TG：my****le",
            "密码：©️TG：my_handle" to "密码：******TG：my****le",
            "密码：🔑🔑TG：my_handle" to "密码：******TG：my****le",
            "密码：🔑1️⃣TG：my_handle" to "密码：******TG：my****le",
            "密码：1️⃣TG：my_handle" to "密码：******TG：my****le",
            "密码：1️⃣KeycapSecret" to "密码：******",
            "密码：🔑EmojiStartSecret" to "密码：******",
            "密码：1️⃣" to "密码：******",
            "密码：🔑" to "密码：******",
        )
        cases.forEach { (input, expected) ->
            val first = TradePrivacySanitizer.sanitize(input, noNoteOpts)
            assertEquals("Failed compound/emoji sanitization for: $input", expected, first.sanitizedMarkdown)
            assertTrue("Expected redactionCount > 0 for: $input", first.redactionCount > 0)

            val second = TradePrivacySanitizer.sanitize(first.sanitizedMarkdown, noNoteOpts)
            assertEquals("Failed idempotency for: $input", first.sanitizedMarkdown, second.sanitizedMarkdown)
            assertEquals("Expected 0 redactions on second pass for: $input", 0, second.redactionCount)
        }
    }

    @Test
    fun `TradePrivacySanitizer scans repeated emojis in bare credentials in linear O(N) time`() {
        val noNoteOpts = TradePrivacyOptions(appendClosureNote = false)
        // JIT warmup
        TradePrivacySanitizer.sanitize("密码: " + "🔑".repeat(100), noNoteOpts)

        listOf(
            "密码: " + "🔑".repeat(20_000),
            "密码: " + "1️⃣".repeat(20_000),
            "密码: abc" + "🔑a".repeat(10_000),
        ).forEach { payload ->
            val startNs = System.nanoTime()
            val result = TradePrivacySanitizer.sanitize(payload, noNoteOpts)
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
            assertTrue("Repeated emoji stress (len=${payload.length}) took ${elapsedMs}ms", elapsedMs < 100L)
            assertEquals("密码: ******", result.sanitizedMarkdown)
            assertEquals(1, result.redactionCount)
        }
    }

    @Test
    fun `TradePrivacySanitizer masks underscore hyphen credentials, mailto links, and bracketed IPv6`() {
        val opts = TradePrivacyOptions(appendClosureNote = false)
        val credInput = "root_password: MySecret123\nssh_password: Hunter2!\npanel-pass: Admin999"
        val credResult = TradePrivacySanitizer.sanitize(credInput, opts)
        assertEquals(3, credResult.credentialRedactions)
        assertFalse(credResult.sanitizedMarkdown.contains("MySecret123"))
        assertFalse(credResult.sanitizedMarkdown.contains("Hunter2!"))
        assertFalse(credResult.sanitizedMarkdown.contains("Admin999"))

        val mailtoInput = "联系 [alice@example.com](mailto:alice@example.com) 或 [联系我](mailto:bob@test.org)"
        val mailtoResult = TradePrivacySanitizer.sanitize(mailtoInput, opts)
        assertFalse(mailtoResult.sanitizedMarkdown.contains("alice@example.com"))
        assertFalse(mailtoResult.sanitizedMarkdown.contains("bob@test.org"))
        assertTrue(mailtoResult.emailRedactions >= 2)

        val ipv6Input = "机器IP=2001:db8::1，备用[fe80::1]:8080"
        val ipv6Result = TradePrivacySanitizer.sanitize(ipv6Input, opts)
        assertFalse(ipv6Result.sanitizedMarkdown.contains("fe80::1"))
        assertTrue(ipv6Result.sanitizedMarkdown.contains("[fe80:*:*]:8080") || ipv6Result.sanitizedMarkdown.contains("[fe80:0:*:*]:8080"))
        assertTrue(ipv6Result.ipRedactions >= 2)
    }
}
