package com.nodeseek.app.ssh

import com.nodeseek.app.domain.ssh.AnsiStreamSanitizer
import com.nodeseek.app.domain.ssh.IpPrivacyMasker
import com.nodeseek.app.ui.components.AnsiAndCodeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiSanitizerAndMaskerTest {

    @Test
    fun `AnsiStreamSanitizer collapses carriage-return progress bars while keeping CRLF newlines`() {
        val raw = buildString {
            append("Starting YABS benchmark...\r\n")
            append("Downloading fio  10% [=>        ]\r")
            append("Downloading fio  55% [=====>    ]\r")
            append("Downloading fio 100% [==========]\r\n")
            append("Running 4k test  |\r")
            append("Running 4k test  /\r")
            append("\u001B[32m4k Read/Write: 128 MB/s\u001B[0m\r\n")
        }

        val sanitized = AnsiStreamSanitizer.sanitize(raw)
        val lines = sanitized.trimEnd('\n').lines()

        assertEquals(3, lines.size)
        assertEquals("Starting YABS benchmark...", lines[0])
        assertEquals("Downloading fio 100% [==========]", lines[1])
        assertEquals("\u001B[32m4k Read/Write: 128 MB/s\u001B[0m", lines[2])
        assertTrue(AnsiAndCodeFormatter.containsAnsiEscapes(sanitized))
    }

    @Test
    fun `AnsiStreamSanitizer strips non-SGR CSI and OSC sequences while preserving SGR colors`() {
        val raw = buildString {
            append("\u001B]0;root@tokyo-vps: ~\u0007") // OSC window title (BEL terminated)
            append("\u001B[?25l") // hide cursor
            append("\u001B[2J\u001B[H") // clear screen & cursor home
            append("\u001B[1;36m=== NodeQuality Report ===\u001B[0m\u001B[K\n")
            append("\u001B[32mNetflix: Yes (JP)\u001B[0m\n")
            append("\u001B[38;5;208m256-color badge\u001B[0m\n")
            append("\u001B[38:2:120:200:255mTrueColor\u001B[0m\n")
            append("\u001B]8;;https://example.com\u001B\\Report Link\u001B]8;;\u001B\\\n") // OSC 8 hyperlink (ST terminated)
            append("\u001B[?25h") // show cursor
        }

        val sanitized = AnsiStreamSanitizer.sanitize(raw)

        assertFalse(sanitized.contains("\u001B[?25l"))
        assertFalse(sanitized.contains("\u001B[?25h"))
        assertFalse(sanitized.contains("\u001B[2J"))
        assertFalse(sanitized.contains("\u001B[H"))
        assertFalse(sanitized.contains("\u001B[K"))
        assertFalse(sanitized.contains("root@tokyo-vps"))

        assertTrue(sanitized.contains("\u001B[1;36m=== NodeQuality Report ===\u001B[0m"))
        assertTrue(sanitized.contains("\u001B[32mNetflix: Yes (JP)\u001B[0m"))
        assertTrue(sanitized.contains("\u001B[38;5;208m256-color badge\u001B[0m"))
        // Colon-delimited SGR subparameters are normalized to semicolons for AnsiAndCodeFormatter
        assertTrue(sanitized.contains("\u001B[38;2;120;200;255mTrueColor\u001B[0m"))
        assertTrue(sanitized.contains("Report Link"))
    }

    @Test
    fun `AnsiStreamSanitizer handles escape sequences and erase-line split across streaming chunks`() {
        val sanitizer = AnsiStreamSanitizer()
        sanitizer.append("Spinner...\u001B[2")
        sanitizer.append("K\r\u001B[3")
        sanitizer.append("2mPASSED\u001B[0")
        sanitizer.append("m\r")
        sanitizer.append("\n\u001B[?25")
        sanitizer.append("lNext\b\bXT\n")

        val result = sanitizer.snapshot()
        assertEquals("\u001B[32mPASSED\u001B[0m\nNeXT\n", result)
    }

    @Test
    fun `AnsiStreamSanitizer enforces maxLines cap without crashing on runaway streams`() {
        val sanitizer = AnsiStreamSanitizer(maxLines = 5)
        for (i in 1..12) {
            sanitizer.append("Line $i\n")
        }
        val snapshot = sanitizer.snapshot()
        assertTrue(snapshot.contains("已省略早期 7 行"))
        assertTrue(snapshot.contains("Line 8"))
        assertTrue(snapshot.contains("Line 12"))
        assertFalse(snapshot.contains("Line 1\n"))
    }

    @Test
    fun `IpPrivacyMasker masks public IPv4 and IPv6 while preserving ANSI colors and non-IP tokens`() {
        val input = buildString {
            append("IPv4: \u001B[32m203.0.113.42\u001B[0m (port 203.0.113.42:8080, subnet 45.33.32.156/24)\n")
            append("IPv6: \u001B[36m2a01:4f8:c012:1234::1\u001B[0m / 2001:0db8:85a3:0000:0000:8a2e:0370:7334\n")
            append("CN2 Hop: 59.43.130.9 -> DNS: 1.1.1.1 / 8.8.8.8 / Loopback: 127.0.0.1 / ::1\n")
            append("LAN: 192.168.1.10 / 10.0.0.5 / Time: 14:23:55 / MAC: aa:bb:cc:dd:ee:ff / Ver: v1.2.3.4\n")
        }

        val masked = IpPrivacyMasker.mask(input)

        // Public IPv4 masked keeping /16 routing prefix and ANSI SGR wrappers intact
        assertTrue(masked.contains("\u001B[32m203.0.*.*\u001B[0m"))
        assertTrue(masked.contains("203.0.*.*:8080"))
        assertTrue(masked.contains("45.33.*.*/24"))
        assertTrue(masked.contains("59.43.*.*"))
        assertFalse(masked.contains("203.0.113.42"))
        assertFalse(masked.contains("45.33.32.156"))

        // Public IPv6 masked keeping /32 prefix and ANSI SGR intact
        assertTrue(masked.contains("\u001B[36m2a01:4f8:*:*\u001B[0m"))
        assertTrue(masked.contains("2001:0db8:*:*"))
        assertFalse(masked.contains("c012:1234::1"))
        assertFalse(masked.contains("8a2e:0370:7334"))

        // DNS, loopback, private LAN, timestamps, MAC addresses, and version tags preserved
        assertTrue(masked.contains("1.1.1.1"))
        assertTrue(masked.contains("8.8.8.8"))
        assertTrue(masked.contains("127.0.0.1"))
        assertTrue(masked.contains("::1"))
        assertTrue(masked.contains("192.168.1.10"))
        assertTrue(masked.contains("10.0.0.5"))
        assertTrue(masked.contains("14:23:55"))
        assertTrue(masked.contains("aa:bb:cc:dd:ee:ff"))
        assertTrue(masked.contains("v1.2.3.4"))
    }

    @Test
    fun `IpPrivacyMasker always masks explicit knownServerHost even if private`() {
        val text = "Connected to 192.168.50.88 from 192.168.1.1"
        val masked = IpPrivacyMasker.mask(text, knownServerHost = "192.168.50.88")
        assertEquals("Connected to 192.168.*.* from 192.168.1.1", masked)
    }

    @Test
    fun `IpPrivacyMasker masks label-colon IPv6 and IPv4-mapped IPv6 while preserving loopback and trailing punctuation`() {
        val input = buildString {
            append("IPv6:2a01:4f8:c012:1234::1\n")
            append("IP:2001:db8:85a3::8a2e:370:7334\n")
            append("Mapped: ::ffff:203.0.113.42 and full 0:0:0:0:0:ffff:198.51.100.77\n")
            append("LoopbackMapped: ::ffff:127.0.0.1 PrivateMapped: ::ffff:192.168.1.10\n")
            append("Sentence end 2a01:4f8:c012::1. Done\n")
        }

        val masked = IpPrivacyMasker.mask(input)

        assertTrue("Expected label-colon IPv6 masked, got:\n$masked", masked.contains("IPv6:2a01:4f8:*:*"))
        assertTrue("Expected IP: IPv6 masked, got:\n$masked", masked.contains("IP:2001:db8:*:*"))
        assertTrue("Expected ::ffff: IPv4-mapped masked, got:\n$masked", masked.contains("Mapped: ::ffff:203.0.*.*"))
        assertTrue("Expected full IPv4-mapped masked, got:\n$masked", masked.contains("0:0:0:0:0:ffff:198.51.*.*"))
        assertFalse(masked.contains("203.0.113.42"))
        assertFalse(masked.contains("198.51.100.77"))
        // Loopback & private IPv4-mapped addresses must remain preserved without corruption
        assertTrue(masked.contains("LoopbackMapped: ::ffff:127.0.0.1"))
        assertTrue(masked.contains("PrivateMapped: ::ffff:192.168.1.10"))
        // Trailing sentence period after IPv6 preserved
        assertTrue(masked.contains("Sentence end 2a01:4f8:*:*. Done"))
    }

    @Test
    fun `IpPrivacyMasker preserves already-masked IPv4-embedded IPv6 and already-masked IPv6 idempotently`() {
        assertEquals(
            "From ::ffff:203.0.*.*",
            IpPrivacyMasker.mask(IpPrivacyMasker.mask("From ::ffff:203.0.113.42")),
        )
        assertEquals(
            "::ffff:203.0.113.*",
            IpPrivacyMasker.mask("::ffff:203.0.113.*"),
        )
        assertEquals(
            "::ffff:192.168.1.*",
            IpPrivacyMasker.mask("::ffff:192.168.1.*"),
        )
        assertEquals(
            "::ffff:203.*.*.*",
            IpPrivacyMasker.mask("::ffff:203.*.*.*"),
        )
        assertEquals(
            "64:ff9b::192.168.1.*",
            IpPrivacyMasker.mask("64:ff9b::192.168.1.*"),
        )
        assertEquals(
            "2001:db8::ffff:192.168.1.*",
            IpPrivacyMasker.mask("2001:db8::ffff:192.168.1.*"),
        )
        assertEquals(
            "240e:390::****",
            IpPrivacyMasker.mask("240e:390::****"),
        )
        assertEquals(
            "240e:390:*:*",
            IpPrivacyMasker.mask("240e:390:*:*"),
        )
        assertEquals(
            "240e:390:*:dead::1",
            IpPrivacyMasker.mask("240e:390:*:dead::1"),
        )
        // Non-embedded IPv4 preceded by hex:colon must still mask IPv4 without false IPv6 truncation
        assertEquals(
            "dead:beef:203.0.*.*",
            IpPrivacyMasker.mask("dead:beef:203.0.113.42"),
        )
        // Standalone IPv6 followed by DNS/hostname suffix continues masking
        assertEquals(
            "2001:db8:*:*.com",
            IpPrivacyMasker.mask("2001:db8::1.com"),
        )
        // Dotted-suffix IPv4-embedded IPv6 must not be truncated at '.' into ffff:192:*:*
        assertEquals(
            "::ffff:192.168.1.1.dead",
            IpPrivacyMasker.mask("::ffff:192.168.1.1.dead"),
        )
    }

    @Test
    fun `IpPrivacyMasker handles markdown bold and italic asterisk boundaries without prefix corruption`() {
        assertEquals("**240e:390:*:***", IpPrivacyMasker.mask("**240e:390::1**"))
        assertEquals("*240e:390:*:**", IpPrivacyMasker.mask("*240e:390::1*"))
        assertEquals("*240e:390:*:*", IpPrivacyMasker.mask("*240e:390::1"))
        assertEquals("240e:390:*:**", IpPrivacyMasker.mask("240e:390::1*"))

        assertEquals("**2001:4860:*:***", IpPrivacyMasker.mask("**2001:4860::1**"))
        assertEquals("*2001:4860:*:**", IpPrivacyMasker.mask("*2001:4860::1*"))
        assertEquals("*2001:4860:*:*", IpPrivacyMasker.mask("*2001:4860::1"))
        assertEquals("2001:4860:*:**", IpPrivacyMasker.mask("2001:4860::1*"))

        assertEquals("**203.0.*.***", IpPrivacyMasker.mask("**203.0.113.42**"))
        assertEquals("*203.0.*.**", IpPrivacyMasker.mask("*203.0.113.42*"))
        assertEquals("**::ffff:203.0.*.***", IpPrivacyMasker.mask("**::ffff:203.0.113.42**"))
        assertEquals("IPv6:**2001:4860:*:***", IpPrivacyMasker.mask("IPv6:**2001:4860::1**"))

        // Idempotency on already-masked IPv6 forms
        assertEquals("240e:390::****", IpPrivacyMasker.mask("240e:390::****"))
        assertEquals("240e:390::*", IpPrivacyMasker.mask("240e:390::*"))
        assertEquals("240e:390:*:*", IpPrivacyMasker.mask("240e:390:*:*"))
        assertEquals("240e:390:*:dead::1", IpPrivacyMasker.mask("240e:390:*:dead::1"))
        assertEquals("240e:390:*::dead", IpPrivacyMasker.mask("240e:390:*::dead"))
        assertEquals("240e:390::1:****", IpPrivacyMasker.mask("240e:390::1:****"))
        assertEquals("**240e:390:*:***", IpPrivacyMasker.mask("**240e:390:*:***"))
        assertEquals("*2001:4860:*:*", IpPrivacyMasker.mask("*2001:4860:*:*"))

        // Markdown-emphasized labels before colon, `::`-ending IPv6 in Markdown, `:` + Markdown,
        // 39-char uncompressed IPv6 + `:**bold**`, and `.**`
        assertEquals("**IPv6**:2001:4860:*:*", IpPrivacyMasker.mask("**IPv6**:2001:4860::1"))
        assertEquals("**Client IPv6**:240e:390:*:*", IpPrivacyMasker.mask("**Client IPv6**:240e:390::1"))
        assertEquals("*IP*:240e:390:*:*", IpPrivacyMasker.mask("*IP*:240e:390::1"))
        assertEquals("***IPv6***:240e:390:*:*", IpPrivacyMasker.mask("***IPv6***:240e:390::1"))
        assertEquals("**IPv6**:::ffff:203.0.*.*", IpPrivacyMasker.mask("**IPv6**:::ffff:203.0.113.42"))
        assertEquals("**2001:4860:*:***", IpPrivacyMasker.mask("**2001:4860::**"))
        assertEquals("*2001:4860:*:**", IpPrivacyMasker.mask("*2001:4860::*"))
        assertEquals("2001:4860:*:*:**bold**", IpPrivacyMasker.mask("2001:4860::1:**bold**"))
        assertEquals("240e:390:*:*:*italic*", IpPrivacyMasker.mask("240e:390::1:*italic*"))
        assertEquals("240e:390:*:*:*", IpPrivacyMasker.mask("240e:390::1:*"))
        assertEquals(
            "2001:0db8:*:*:**bold**",
            IpPrivacyMasker.mask("2001:0db8:85a3:0000:0000:8a2e:0370:7334:**bold**"),
        )
        assertEquals("**240e:390:*:*.**", IpPrivacyMasker.mask("**240e:390::1.**"))
        assertEquals("*240e:390:*:*.*", IpPrivacyMasker.mask("*240e:390::1.*"))
        assertEquals(
            "**2001:4860:*:***",
            IpPrivacyMasker.mask(IpPrivacyMasker.mask("**2001:4860::**")),
        )
        assertEquals(
            "**240e:390:*:*.**",
            IpPrivacyMasker.mask(IpPrivacyMasker.mask("**240e:390::1.**")),
        )

        // Punctuation-prefixed bold after IPv6, multi-word bold, sentence-ending & .**-wrapped
        // IPv4/IPv4-mapped IPv6, and non-asterisk/hex label colons
        assertEquals("2001:4860:*:*:**[CN2 GIA]**", IpPrivacyMasker.mask("2001:4860::1:**[CN2 GIA]**"))
        assertEquals("240e:390:*:*:**(中国电信)**", IpPrivacyMasker.mask("240e:390::1:**(中国电信)**"))
        assertEquals("240e:390:*:*:**/api**", IpPrivacyMasker.mask("240e:390::1:**/api**"))
        assertEquals("**Client 240e:390:*:***", IpPrivacyMasker.mask("**Client 240e:390::**"))
        assertEquals("**Client 240e:390:*:*:**", IpPrivacyMasker.mask("**Client 240e:390::1:**"))
        assertEquals("203.0.*.*.", IpPrivacyMasker.mask("203.0.113.42."))
        assertEquals("**203.0.*.*.**", IpPrivacyMasker.mask("**203.0.113.42.**"))
        assertEquals("*203.0.*.*.*", IpPrivacyMasker.mask("*203.0.113.42.*"))
        assertEquals("**::ffff:203.0.*.*.**", IpPrivacyMasker.mask("**::ffff:203.0.113.42.**"))
        assertEquals("*::ffff:203.0.*.*.*", IpPrivacyMasker.mask("*::ffff:203.0.113.42.*"))
        assertEquals("`IPv6`:240e:390:*:*", IpPrivacyMasker.mask("`IPv6`:240e:390::1"))
        assertEquals("[IPv6]:240e:390:*:*", IpPrivacyMasker.mask("[IPv6]:240e:390::1"))
        assertEquals("(IPv6):240e:390:*:*", IpPrivacyMasker.mask("(IPv6):240e:390::1"))
        assertEquals("\"IPv6\":240e:390:*:*", IpPrivacyMasker.mask("\"IPv6\":240e:390::1"))
        assertEquals("**dead**:240e:390:*:*", IpPrivacyMasker.mask("**dead**:240e:390::1"))

        // Idempotency and false-positive boundary preservation
        for (unchanged in listOf(
            "2001:4860:*:*:**[CN2 GIA]**",
            "**Client 240e:390:*:***",
            "**Client 240e:390:*:*:**",
            "**Client 240e:390::****",
            "**bold** 240e:390::**",
            "203.0.*.*.",
            "**203.0.*.*.**",
            "*203.0.*.*.*",
            "**::ffff:203.0.*.*.**",
            "*::ffff:203.0.*.*.*",
            "1.2.3.4.5",
            "1.2.3.4.example",
            "1.2.3.4.*",
            "1.2.3.4.*.5",
            "1.2.3.* 5.6.7.8.*",
            "203.0.*.*",
            "::ffff:192.168.1.*",
            "::ffff:192.168.1.1.dead",
        )) {
            assertEquals(unchanged, IpPrivacyMasker.mask(unchanged))
        }

        // Iteration 9 regressions:
        // 1) Single-star Markdown italic with period around/after IPv4 & IPv4-mapped IPv6
        assertEquals("*Client 203.0.*.*.*", IpPrivacyMasker.mask("*Client 203.0.113.42.*"))
        assertEquals("*Client ::ffff:203.0.*.*.*", IpPrivacyMasker.mask("*Client ::ffff:203.0.113.42.*"))
        assertEquals("203.0.*.*.*italic*", IpPrivacyMasker.mask("203.0.113.42.*italic*"))
        assertEquals("::ffff:203.0.*.*.*italic*", IpPrivacyMasker.mask("::ffff:203.0.113.42.*italic*"))
        assertEquals("*Client 203.0.*.*.* 5.6.7.8.*", IpPrivacyMasker.mask("*Client 203.0.113.42.* 5.6.7.8.*"))
        assertEquals("203.0.*.*.*italic* 1.2.3.4.*", IpPrivacyMasker.mask("203.0.113.42.*italic* 1.2.3.4.*"))

        // 2) Same-line bolded IP followed by an already-masked `::**` token
        assertEquals(
            "**Hop1 2001:4860:*:*** 2001:4860:1::**",
            IpPrivacyMasker.mask("**Hop1 2001:4860::** 2001:4860:1::**"),
        )
        assertEquals(
            "**IP 203.0.*.*** 2001:4860:1::**",
            IpPrivacyMasker.mask("**IP 203.0.113.42** 2001:4860:1::**"),
        )
        assertEquals(
            "**Hop1 2001:4860:*:*** **Hop2 2001:4860:*:***",
            IpPrivacyMasker.mask("**Hop1 2001:4860::** **Hop2 2001:4860:1::**"),
        )

        // Idempotency across all Iteration 9 outputs
        for (sample in listOf(
            "*Client 203.0.113.42.*",
            "*Client ::ffff:203.0.113.42.*",
            "203.0.113.42.*italic*",
            "::ffff:203.0.113.42.*italic*",
            "*Client 203.0.113.42.* 5.6.7.8.*",
            "203.0.113.42.*italic* 1.2.3.4.*",
            "**Hop1 2001:4860::** 2001:4860:1::**",
            "**IP 203.0.113.42** 2001:4860:1::**",
            "**Hop1 2001:4860::** **Hop2 2001:4860:1::**",
            "***Client 240e:390::***",
            "**a** **b 240e:390::**",
            "1.2.3.* 240e:390::*",
            "**Client 240e:390::****",
            "240e:390:*:dead::1",
            "240e:390:*::dead",
            "240e:390::1:****",
            "::ffff:203.*.*.*",
            "64:ff9b::192.168.1.*",
        )) {
            val once = IpPrivacyMasker.mask(sample)
            val twice = IpPrivacyMasker.mask(once)
            assertEquals("Expected idempotency for: $sample", once, twice)
        }

        // Iteration 10 regressions:
        // 1) Nested bold+italic `***` stack handling & unbalanced `***a**` split
        assertEquals("**a *b 2001:4860:*:****", IpPrivacyMasker.mask("**a *b 2001:4860::***"))
        assertEquals("*a **b 2001:4860:*:****", IpPrivacyMasker.mask("*a **b 2001:4860::***"))
        assertEquals("**a *b 240e:390:*:****", IpPrivacyMasker.mask("**a *b 240e:390::***"))
        assertEquals(
            "**a *b 203.0.*.*.*** 2001:4860:1::**",
            IpPrivacyMasker.mask("**a *b 203.0.113.42.*** 2001:4860:1::**"),
        )
        assertEquals(
            "**bold *italic 203.0.*.**** 2001:4860:1::**",
            IpPrivacyMasker.mask("**bold *italic 203.0.113.42*** 2001:4860:1::**"),
        )
        assertEquals(
            "**a *b 1.2.3.*** 2001:4860:1::**",
            IpPrivacyMasker.mask("**a *b 1.2.3.*** 2001:4860:1::**"),
        )
        assertEquals("***a** 203.0.*.*.*", IpPrivacyMasker.mask("***a** 203.0.113.42.*"))
        assertEquals("***a** 203.0.*.*.* 5.6.7.8.*", IpPrivacyMasker.mask("***a** 203.0.113.42.* 5.6.7.8.*"))
        assertEquals("***a** 240e:390:*:**", IpPrivacyMasker.mask("***a** 240e:390::*"))
        assertEquals(
            "**a *b 2001:4860:*:**** 2001:4860:1::**",
            IpPrivacyMasker.mask("**a *b 2001:4860::*** 2001:4860:1::**"),
        )

        // 2) Already-masked /16 IPv4 (`203.0.*.*`) & /32 IPv6 (`2001:4860:*:*`) terminal '*' must not pop `*...*`
        assertEquals(
            "*Hop1 203.0.*.* Hop2 198.51.*.*.*",
            IpPrivacyMasker.mask("*Hop1 203.0.*.* Hop2 198.51.100.2.*"),
        )
        assertEquals(
            "*Hop1 203.0.*.*. Hop2 198.51.*.*.*",
            IpPrivacyMasker.mask("*Hop1 203.0.*.*. Hop2 198.51.100.2.*"),
        )
        assertEquals(
            "*Hop1 2001:4860:*:* Hop2 198.51.*.*.*",
            IpPrivacyMasker.mask("*Hop1 2001:4860:*:* Hop2 198.51.100.2.*"),
        )
        assertEquals(
            "*Hop1 203.0.*.* Hop2 ::ffff:198.51.*.*.*",
            IpPrivacyMasker.mask("*Hop1 203.0.*.* Hop2 ::ffff:198.51.100.2.*"),
        )
        assertEquals(
            "*Hop1 203.0.*.*.* 5.6.7.8.*",
            IpPrivacyMasker.mask("*Hop1 203.0.*.*.* 5.6.7.8.*"),
        )
        assertEquals(
            "*Hop1 2001:4860:*:** 5.6.7.8.*",
            IpPrivacyMasker.mask("*Hop1 2001:4860:*:** 5.6.7.8.*"),
        )

        // 3) Single-star italic wrapping `::`-ending IPv6
        assertEquals("*Client 240e:390:*:**", IpPrivacyMasker.mask("*Client 240e:390::*"))
        assertEquals(
            "*Hop1 2001:4860:*:** 2001:4860:1::*",
            IpPrivacyMasker.mask("*Hop1 2001:4860::* 2001:4860:1::*"),
        )

        // 4) Closing `**` followed by `.` or `:` must still pop from openStack
        assertEquals(
            "**IP 203.0.*.***. 2001:4860:1::**",
            IpPrivacyMasker.mask("**IP 203.0.113.42**. 2001:4860:1::**"),
        )
        assertEquals(
            "**Hop1 2001:4860:*:***: 2001:4860:1::**",
            IpPrivacyMasker.mask("**Hop1 2001:4860::**: 2001:4860:1::**"),
        )

        // Preserved standalone / non-unclosed `::`-ending tokens & idempotency across Iteration 10 vectors
        for (unchanged in listOf(
            "240e:390::*",
            "1.2.3.* 240e:390::*",
            "*italic* 240e:390::*",
            "**bold** 240e:390::*",
            "203.0.*.*.",
            "2001:4860:*:*",
            "240e:390:*:dead::1",
            "240e:390::1:****",
        )) {
            assertEquals(unchanged, IpPrivacyMasker.mask(unchanged))
        }
        for (sample in listOf(
            "**a *b 2001:4860::***",
            "*a **b 2001:4860::***",
            "**a *b 240e:390::***",
            "**a *b 203.0.113.42.*** 2001:4860:1::**",
            "**bold *italic 203.0.113.42*** 2001:4860:1::**",
            "**a *b 1.2.3.*** 2001:4860:1::**",
            "***a** 203.0.113.42.*",
            "***a** 203.0.113.42.* 5.6.7.8.*",
            "***a** 240e:390::*",
            "**a *b 2001:4860::*** 2001:4860:1::**",
            "*Hop1 203.0.*.* Hop2 198.51.100.2.*",
            "*Hop1 203.0.*.*. Hop2 198.51.100.2.*",
            "*Hop1 2001:4860:*:* Hop2 198.51.100.2.*",
            "*Hop1 203.0.*.* Hop2 ::ffff:198.51.100.2.*",
            "*Hop1 203.0.*.*.* 5.6.7.8.*",
            "*Hop1 2001:4860:*:** 5.6.7.8.*",
            "*Client 240e:390::*",
            "*Hop1 2001:4860::* 2001:4860:1::*",
            "**IP 203.0.113.42**. 2001:4860:1::**",
            "**Hop1 2001:4860::**: 2001:4860:1::**",
        )) {
            val once = IpPrivacyMasker.mask(sample)
            val twice = IpPrivacyMasker.mask(once)
            assertEquals("Expected Iteration 10 idempotency for: $sample", once, twice)
        }

        // Iteration 11 regressions:
        // Bug 1: /24-masked IPv4 (`d.d.d.*`) & /120-masked IPv4-mapped IPv6 (`::ffff:d.d.d.*`) inside `*...*` / `**...**`
        assertEquals(
            "*Client 1.2.3.* 198.51.*.*.*",
            IpPrivacyMasker.mask("*Client 1.2.3.* 198.51.100.2.*"),
        )
        assertEquals(
            "*Hop1 203.0.113.* Hop2 198.51.*.*.*",
            IpPrivacyMasker.mask("*Hop1 203.0.113.* Hop2 198.51.100.2.*"),
        )
        assertEquals(
            "*Hop1 ::ffff:203.0.113.* Hop2 198.51.*.*.*",
            IpPrivacyMasker.mask("*Hop1 ::ffff:203.0.113.* Hop2 198.51.100.2.*"),
        )
        assertEquals(
            "*Hop1 64:ff9b::192.168.1.* Hop2 198.51.*.*.*",
            IpPrivacyMasker.mask("*Hop1 64:ff9b::192.168.1.* Hop2 198.51.100.2.*"),
        )
        assertEquals(
            "**Hop1 203.0.113.* Hop2 2001:4860:*:***",
            IpPrivacyMasker.mask("**Hop1 203.0.113.* Hop2 2001:4860::**"),
        )
        assertEquals(
            "*Hop1 203.0.113.* Hop2 240e:390:*:**",
            IpPrivacyMasker.mask("*Hop1 203.0.113.* Hop2 240e:390::*"),
        )
        assertEquals(
            "*Target 203.0.113.** 2001:4860:1::**",
            IpPrivacyMasker.mask("*Target 203.0.113.** 2001:4860:1::**"),
        )
        assertEquals(
            "**Target 203.0.113.*** 2001:4860:1::**",
            IpPrivacyMasker.mask("**Target 203.0.113.*** 2001:4860:1::**"),
        )
        assertEquals(
            "**Target 203.0.113.** 2001:4860:*:**",
            IpPrivacyMasker.mask("**Target 203.0.113.** 2001:4860:1::*"),
        )
        assertEquals(
            "***Target 203.0.113.*** 240e:390:*:**",
            IpPrivacyMasker.mask("***Target 203.0.113.*** 240e:390::*"),
        )

        // Bug 2: Split/partial Markdown `*` closures in `hasUnclosedOpeningStarRun` & `closingBudget`
        assertEquals("**a 203.0.*.*.*", IpPrivacyMasker.mask("**a 203.0.113.42.*"))
        assertEquals("**a 240e:390:*:**", IpPrivacyMasker.mask("**a 240e:390::*"))
        assertEquals("***a 203.0.*.*.*", IpPrivacyMasker.mask("***a 203.0.113.42.*"))
        assertEquals("***a 240e:390:*:**", IpPrivacyMasker.mask("***a 240e:390::*"))
        assertEquals("***a 2001:4860:*:***", IpPrivacyMasker.mask("***a 2001:4860::**"))
        assertEquals("**a *b *c 2001:4860:*:****", IpPrivacyMasker.mask("**a *b *c 2001:4860::***"))
        assertEquals("**a **b 2001:4860:*:****", IpPrivacyMasker.mask("**a **b 2001:4860::***"))
        assertEquals("*a **2001:4860:*:****", IpPrivacyMasker.mask("*a **2001:4860::***"))
        assertEquals(
            "**a 203.0.*.*.* 2001:4860:1::**",
            IpPrivacyMasker.mask("**a 203.0.113.42.* 2001:4860:1::**"),
        )
        assertEquals(
            "**a 240e:390:*:** 2001:4860:1::**",
            IpPrivacyMasker.mask("**a 240e:390::* 2001:4860:1::**"),
        )
        assertEquals(
            "***a 2001:4860:*:*** 2001:4860:1::**",
            IpPrivacyMasker.mask("***a 2001:4860::** 2001:4860:1::**"),
        )
        assertEquals(
            "**a 1.2.3.** 2001:4860:1::**",
            IpPrivacyMasker.mask("**a 1.2.3.** 2001:4860:1::**"),
        )
        assertEquals(
            "***a 1.2.3.*** 2001:4860:1::**",
            IpPrivacyMasker.mask("***a 1.2.3.*** 2001:4860:1::**"),
        )

        // All 18 preserved tokens & idempotency across Iteration 11 vectors
        for (unchanged in listOf(
            "1.2.3.4.*",
            "1.2.3.* 5.6.7.8.*",
            "1.2.3.4.*.5",
            "1.2.3.4.5",
            "1.2.3.4.example",
            "203.0.*.*.",
            "::ffff:192.168.1.*",
            "240e:390::****",
            "240e:390::*",
            "1.2.3.* 240e:390::*",
            "**bold** 240e:390::**",
            "**Client 240e:390::****",
            "64:ff9b::192.168.1.*",
            "240e:390:*:*",
            "240e:390:*:dead::1",
            "240e:390:*::dead",
            "240e:390::1:****",
            "::ffff:203.*.*.*",
        )) {
            assertEquals("Preserved token altered: $unchanged", unchanged, IpPrivacyMasker.mask(unchanged))
        }
        for (sample in listOf(
            "*Client 1.2.3.* 198.51.100.2.*",
            "*Hop1 203.0.113.* Hop2 198.51.100.2.*",
            "*Hop1 ::ffff:203.0.113.* Hop2 198.51.100.2.*",
            "*Hop1 64:ff9b::192.168.1.* Hop2 198.51.100.2.*",
            "**Hop1 203.0.113.* Hop2 2001:4860::**",
            "*Hop1 203.0.113.* Hop2 240e:390::*",
            "*Target 203.0.113.** 2001:4860:1::**",
            "**Target 203.0.113.*** 2001:4860:1::**",
            "**Target 203.0.113.** 2001:4860:1::*",
            "***Target 203.0.113.*** 240e:390::*",
            "**a 203.0.113.42.*",
            "**a 240e:390::*",
            "***a 203.0.113.42.*",
            "***a 240e:390::*",
            "***a 2001:4860::**",
            "**a *b *c 2001:4860::***",
            "**a **b 2001:4860::***",
            "*a **2001:4860::***",
            "**a 203.0.113.42.* 2001:4860:1::**",
            "**a 240e:390::* 2001:4860:1::**",
            "***a 2001:4860::** 2001:4860:1::**",
            "**a 1.2.3.** 2001:4860:1::**",
            "***a 1.2.3.*** 2001:4860:1::**",
        )) {
            val once = IpPrivacyMasker.mask(sample)
            val twice = IpPrivacyMasker.mask(once)
            assertEquals("Expected Iteration 11 idempotency for: $sample", once, twice)
        }
    }

    @Test
    fun `AnsiStreamSanitizer honors CSI 2J and 3J only when handleFullScreenClear is enabled`() {
        // Default (benchmark mode): CSI 2J is ignored so full benchmark logs are never wiped
        val benchSanitizer = AnsiStreamSanitizer()
        benchSanitizer.append("Banner Line 1\n\u001B[2J\u001B[HAfter Clear\n")
        assertTrue(benchSanitizer.snapshot().contains("Banner Line 1"))
        assertTrue(benchSanitizer.snapshot().contains("After Clear"))

        // Interactive shell mode: CSI 2J / 3J clears previous terminal screen lines
        val shellSanitizer = AnsiStreamSanitizer(handleFullScreenClear = true)
        shellSanitizer.append("Old Output 1\nOld Output 2\n")
        assertTrue(shellSanitizer.snapshot().contains("Old Output 1"))
        shellSanitizer.append("\u001B[2J\u001B[Hroot@vps:~# ")
        assertFalse(shellSanitizer.snapshot().contains("Old Output 1"))
        assertEquals("root@vps:~# ", shellSanitizer.snapshot())

        shellSanitizer.append("ls\nfile.txt\n\u001B[3J\u001B[Hfresh")
        assertFalse(shellSanitizer.snapshot().contains("file.txt"))
        assertEquals("fresh", shellSanitizer.snapshot())
    }

    @Test
    fun `IpPrivacyMasker passes G1 Iteration 12 structural tokenizer vectors and idempotency`() {
        val cases = listOf(
            // `:*****` / `:******` after `::1` or 7-hextet prefix
            "**Hop1 240e:390::1:***** Hop2 198.51.100.2.*" to "**Hop1 240e:390::1:***** Hop2 198.51.*.*.*",
            "***Hop1 240e:390::1:****** Hop2 198.51.100.2.*" to "***Hop1 240e:390::1:****** Hop2 198.51.*.*.*",
            "*a **240e:390::1:***** Hop2 198.51.100.2.*" to "*a **240e:390::1:***** Hop2 198.51.*.*.*",
            "**Hop1 240e:390::1:**** Hop2 198.51.100.2.*" to "**Hop1 240e:390::1:**** Hop2 198.51.*.*.*",
            "**Hop1 2001:db8:1:2:3:4:5:***** Hop2 198.51.100.2.*" to "**Hop1 2001:db8:1:2:3:4:5:***** Hop2 198.51.*.*.*",
            // `totalOpen >= k` invariant on `2001:db8:1:2:3:4:5:*****` + `2001:4860::**`/`*`
            "**Hop1 2001:db8:1:2:3:4:5:***** Hop2 2001:4860::**" to "**Hop1 2001:db8:1:2:3:4:5:***** Hop2 2001:4860::**",
            "***Hop1 2001:db8:1:2:3:4:5:***** Hop2 2001:4860::**" to "***Hop1 2001:db8:1:2:3:4:5:***** Hop2 2001:4860:*:***",
            "**Hop1 2001:db8:1:2:3:4:5:***** Hop2 2001:4860::*" to "**Hop1 2001:db8:1:2:3:4:5:***** Hop2 2001:4860:*:**",
            // `:*:****` vs `totalOpen < 3` and `totalOpen >= 3`
            "*Hop1 240e:390:*:**** Hop2 198.51.100.2.*" to "*Hop1 240e:390:*:**** Hop2 198.51.*.*.*",
            "**Hop1 240e:390:*:**** Hop2 2001:4860::**" to "**Hop1 240e:390:*:**** Hop2 2001:4860:*:***",
            "**Hop1 240e:390:*:***** Hop2 198.51.100.2.*" to "**Hop1 240e:390:*:***** Hop2 198.51.*.*.*",
            "***Hop1 2001:4860:*:**** 2001:4860:1::***" to "***Hop1 2001:4860:*:**** 2001:4860:1::***",
            "**Hop1 240e:390:*:*** Hop2 198.51.100.2.*" to "**Hop1 240e:390:*:*** Hop2 198.51.100.2.*",
            // `/8`-masked IPv4 (`d.*.*.*`) & `::ffff:d.*.*.*` & split `***`
            "*Hop1 ::ffff:203.*.*.* Hop2 198.51.100.2.*" to "*Hop1 ::ffff:203.*.*.* Hop2 198.51.*.*.*",
            "*Hop1 203.*.*.* Hop2 198.51.100.2.*" to "*Hop1 203.*.*.* Hop2 198.51.*.*.*",
            "**Hop1 203.*.*.* Hop2 2001:4860::**" to "**Hop1 203.*.*.* Hop2 2001:4860:*:***",
            "*Hop1 203.*.*.** Hop2 198.51.100.2.*" to "*Hop1 203.*.*.** Hop2 198.51.100.2.*",
            "**203.0.113.*** Hop2 198.51.100.2.*" to "**203.0.113.*** Hop2 198.51.100.2.*",
            // Trailing-`::` single-hextet (`2001::`) & leading-`::` (`::2`) idempotency
            "2001::" to "2001:*:*",
            "*Hop1 2001::" to "*Hop1 2001:*:*",
            "**Hop1 2001::" to "**Hop1 2001:*:*",
            "*Hop1 2001:: Hop2 198.51.100.2.*" to "*Hop1 2001:*:* Hop2 198.51.*.*.*",
            "*Hop1 2001::* Hop2 198.51.100.2.*" to "*Hop1 2001:*:** Hop2 198.51.100.2.*",
            "*Hop1 2001:*:* Hop2 198.51.100.2.*" to "*Hop1 2001:*:* Hop2 198.51.*.*.*",
            "**Hop1 2001::**" to "**Hop1 2001:*:***",
            "::2" to "0:0:*:*",
            "*Hop1 ::2" to "*Hop1 0:0:*:*",
        )
        for ((input, expected) in cases) {
            val once = IpPrivacyMasker.mask(input)
            assertEquals("Mismatch for: $input", expected, once)
            val twice = IpPrivacyMasker.mask(once)
            assertEquals("Expected strict idempotency for: $input", once, twice)
        }
    }

    @Test
    fun `IpPrivacyMasker preserves G2 private DNS loopback link-local and tests G3 ANSI SGR plus Markdown`() {
        // G2: Private, DNS, Loopback, Link-Local, Multicast, IPv4-mapped Private
        val preservedIps = listOf(
            "127.0.0.1", "10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.1.1",
            "169.254.1.1", "0.0.0.0", "255.255.255.1", "1.1.1.1", "1.0.0.1",
            "8.8.8.8", "8.8.4.4", "9.9.9.9", "114.114.114.114", "223.5.5.5", "223.6.6.6",
            "::1", "::", "fe80::1", "fc00::1", "fd12::1", "ff02::1",
            "2606:4700:4700::1111", "2001:4860:4860::8888", "::ffff:127.0.0.1", "::ffff:192.168.1.10",
        )
        for (ip in preservedIps) {
            assertEquals(ip, IpPrivacyMasker.mask(ip))
            assertEquals("*$ip*", IpPrivacyMasker.mask("*$ip*"))
            assertEquals("**$ip**", IpPrivacyMasker.mask("**$ip**"))
        }
        assertEquals(
            "host=192.168.*.* dns=8.8.8.8 lo=127.0.0.1",
            IpPrivacyMasker.mask("host=192.168.1.1 dns=8.8.8.8 lo=127.0.0.1", knownServerHost = "192.168.1.1"),
        )
        assertEquals("100.64.*.*", IpPrivacyMasker.mask("100.64.0.1"))
        assertEquals("172.15.*.* 172.32.*.*", IpPrivacyMasker.mask("172.15.0.1 172.32.0.1"))
        assertEquals("*DNS 8.8.8.8 Hop2 198.51.*.*.*", IpPrivacyMasker.mask("*DNS 8.8.8.8 Hop2 198.51.100.2.*"))
        assertEquals("**LAN 192.168.1.1 Hop2 203.0.*.*.**", IpPrivacyMasker.mask("**LAN 192.168.1.1 Hop2 203.0.113.42.**"))
        assertEquals("*Loopback ::1 Hop2 2001:4860:*:**", IpPrivacyMasker.mask("*Loopback ::1 Hop2 2001:4860::*"))
        assertEquals("*DNS 8.8.8.8.* Hop2 198.51.100.2.*", IpPrivacyMasker.mask("*DNS 8.8.8.8.* Hop2 198.51.100.2.*"))

        // G3: ANSI SGR / CSI / OSC + Markdown '*' + knownServerHost
        val ansiCases = listOf(
            "\u001B[32m203.0.113.42\u001B[0m" to "\u001B[32m203.0.*.*\u001B[0m",
            "\u001B[38;5;208m240e:390::1\u001B[0m" to "\u001B[38;5;208m240e:390:*:*\u001B[0m",
            "\u001B[38:2:120:200:255m203.0.113.42\u001B[0m" to "\u001B[38:2:120:200:255m203.0.*.*\u001B[0m",
            "foo\u001B[32m203.0.113.42\u001B[0m" to "foo\u001B[32m203.0.*.*\u001B[0m",
            "240e:390::1:\u001B[1m**[CN2 GIA]**\u001B[0m" to "240e:390:*:*:\u001B[1m**[CN2 GIA]**\u001B[0m",
            "**\u001B[32m240e:390::**\u001B[0m" to "**\u001B[32m240e:390:*:***\u001B[0m",
            "*\u001B[36m203.0.113.42.*\u001B[0m" to "*\u001B[36m203.0.*.*.*\u001B[0m",
            "**Hop1 \u001B[36m240e:390::1:*****\u001B[0m Hop2 198.51.100.2.*" to "**Hop1 \u001B[36m240e:390::1:*****\u001B[0m Hop2 198.51.*.*.*",
            "\u001B[?25l**203.0.113.42**\u001B[K" to "\u001B[?25l**203.0.*.***\u001B[K",
            "\u001B]8;;https://203.0.113.42/r\u001B\\Link\u001B]8;;\u001B\\" to "\u001B]8;;https://203.0.*.*/r\u001B\\Link\u001B]8;;\u001B\\",
        )
        for ((input, expected) in ansiCases) {
            val once = IpPrivacyMasker.mask(input)
            assertEquals("ANSI+Markdown mismatch for: $input", expected, once)
            assertEquals("ANSI+Markdown idempotency failed for: $input", once, IpPrivacyMasker.mask(once))
        }
        assertEquals(
            "\u001B[36m192.168.*.*\u001B[0m",
            IpPrivacyMasker.mask("\u001B[36m192.168.50.88\u001B[0m", knownServerHost = "192.168.50.88"),
        )
    }

    @Test
    fun `IpPrivacyMasker isolates G6 multi-line spans and handles G7 mixed-token deep nesting`() {
        // G6: Multi-line isolation & 80-asterisk banners
        assertEquals("*Line1\n203.0.113.42.*", IpPrivacyMasker.mask("*Line1\n203.0.113.42.*"))
        assertEquals("**Line1\n240e:390::**", IpPrivacyMasker.mask("**Line1\n240e:390::**"))
        assertEquals("*Line1\n*Line2 203.0.*.*.*", IpPrivacyMasker.mask("*Line1\n*Line2 203.0.113.42.*"))
        assertEquals(
            "*".repeat(80) + "\n*Hop1 203.0.*.*.*",
            IpPrivacyMasker.mask("*".repeat(80) + "\n*Hop1 203.0.113.42.*"),
        )
        assertEquals(
            "*".repeat(80) + "\n203.0.113.42.*",
            IpPrivacyMasker.mask("*".repeat(80) + "\n203.0.113.42.*"),
        )

        // G7: Mixed-token stress & deep nesting (>16 spans)
        val deepPrefix = (1..25).joinToString(" ") { "*$it" }
        val g7Cases = listOf(
            "*Hop1 203.*.*.* Hop2 203.0.*.* Hop3 203.0.113.* Hop4 240e:390::**** Hop5 240e:390:*:**** Hop6 2001:*:* Hop7 198.51.100.2.*" to
                "*Hop1 203.*.*.* Hop2 203.0.*.* Hop3 203.0.113.* Hop4 240e:390::**** Hop5 240e:390:*:**** Hop6 2001:*:* Hop7 198.51.*.*.*",
            "*a 2*3=6 198.51.100.2.*" to "*a 2*3=6 198.51.*.*.*",
            "$deepPrefix 203.0.113.42.*" to "$deepPrefix 203.0.*.*.*",
            "**Hop1 64:ff9b::203.0.*.* Hop2 ::ffff:203.*.*.* Hop3 240e:390::1:***** Hop4 198.51.100.2.*" to
                "**Hop1 64:ff9b::203.0.*.* Hop2 ::ffff:203.*.*.* Hop3 240e:390::1:***** Hop4 198.51.*.*.*",
            "240e:390::*italic* 198.51.100.2" to "240e:390:*:**italic* 198.51.*.*",
        )
        for ((input, expected) in g7Cases) {
            val once = IpPrivacyMasker.mask(input)
            assertEquals("G7 mismatch for: $input", expected, once)
            assertEquals("G7 idempotency failed for: $input", once, IpPrivacyMasker.mask(once))
        }
    }

    @Test
    fun `IpPrivacyMasker handles Iteration 13 trailing IPv4 after masked IPv6 1-colon prefixes and tagged terminal stars`() {
        val cases = listOf(
            // Bug 1: raw IPv4 / masked IPv4 / hostname after masked IPv6 + ':'
            "240e:390::****:198.51.100.2" to "240e:390::****:198.51.*.*",
            "240e:390:*:198.51.100.2" to "240e:390:*:198.51.*.*",
            "240e:390:****:198.51.100.2" to "240e:390:****:198.51.*.*",
            "240e:390::1:****:198.51.100.2" to "240e:390::1:****:198.51.*.*",
            "[240e:390::****]:198.51.100.2" to "[240e:390::****]:198.51.*.*",
            "*Hop1 240e:390::****:198.51.100.2.*" to "*Hop1 240e:390::****:198.51.*.*.*",
            "240e:390::****:198.51.100.*" to "240e:390::****:198.51.100.*",
            "240e:390::****:203.*.*.*" to "240e:390::****:203.*.*.*",
            "240e:390::****:1.2.3.example" to "240e:390::****:1.2.3.example",
            // Bug 2: 1-colon hex/digit prefixes before IPv4
            "aa:198.51.100.2" to "aa:198.51.*.*",
            "1234:198.51.100.2" to "1234:198.51.*.*",
            "dead:198.51.100.2" to "dead:198.51.*.*",
            "cafe:203.0.113.42" to "cafe:203.0.*.*",
            // Bug 4: k >= 4 terminal stars immediately before [tag] / (tag) / quotes / CJK brackets
            "240e:390::1:****[CN2]" to "240e:390::1:****[CN2]",
            "240e:390::1:****(电信)" to "240e:390::1:****(电信)",
            "240e:390::****[CN2]" to "240e:390::****[CN2]",
            "240e:390::****(电信)" to "240e:390::****(电信)",
            "240e:390::1:****\"quoted\"" to "240e:390::1:****\"quoted\"",
            "240e:390::1:****【CN2】" to "240e:390::1:****【CN2】",
            "240e:390::*[CN2]*" to "240e:390:*:**[CN2]*",
        )
        for ((input, expected) in cases) {
            val once = IpPrivacyMasker.mask(input)
            assertEquals("Iteration 13 mismatch for: $input", expected, once)
            assertEquals("Iteration 13 idempotency failed for: $input", once, IpPrivacyMasker.mask(once))
        }
    }

    @Test
    fun `IpPrivacyMasker fixes Iteration 14 colon-dot wildcard closer leaks and long hex-colon trailing IPv4`() {
        val longHexPrefix120 = "deadbeef:".repeat(14) // 126 chars
        val cases = listOf(
            // Bug A: colon/dot-bounded wildcard hextets/octets must not pop emphasis stack
            "*Hop1 240e:390:*:198.51.100.2.*" to "*Hop1 240e:390:*:198.51.*.*.*",
            "*Hop1 240e:390:****:198.51.100.2.*" to "*Hop1 240e:390:****:198.51.*.*.*",
            "*Hop1 240e:390:1:****:198.51.100.2.*" to "*Hop1 240e:390:1:****:198.51.*.*.*",
            "*Hop1 240e:390::*:198.51.100.2.*" to "*Hop1 240e:390::*:198.51.*.*.*",
            "*Hop1 240e:390::1:*****:198.51.100.2.*" to "*Hop1 240e:390::1:******:198.51.*.*.*".replace("******:", "*****:"),
            "**Hop1 240e:390:*:198.51.100.2.**" to "**Hop1 240e:390:*:198.51.*.*.**",
            "**Hop1 240e:390:****:198.51.100.2.**" to "**Hop1 240e:390:****:198.51.*.*.**",
            "**Hop1 240e:390:1:****:198.51.100.2.**" to "**Hop1 240e:390:1:****:198.51.*.*.**",
            "**Hop1 240e:390::*:198.51.100.2.**" to "**Hop1 240e:390::*:198.51.*.*.**",
            "**Hop1 240e:390:****:198.51.100.2.*" to "**Hop1 240e:390:****:198.51.*.*.*",
            "*Hop1 192.168.*.1:198.51.100.2.*" to "*Hop1 192.168.*.1:198.51.*.*.*",
            "*Hop1 192.168.*:198.51.100.2.*" to "*Hop1 192.168.*:198.51.*.*.*",
            "**a:**: 2001:4860:1::**" to "**a:**: 2001:4860:1::**",
            "**a.**. 2001:4860:1::**" to "**a.**. 2001:4860:1::**",
            // Bug B: >=41/46-char and >100-char hex/colon prefixes before IPv4
            "deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:198.51.100.2" to
                "deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:198.51.*.*",
            "deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:203.0.113.42" to
                "deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:203.0.*.*",
            "0123456789abcdef0123456789abcdef0123456789ab:198.51.100.2" to
                "0123456789abcdef0123456789abcdef0123456789ab:198.51.*.*",
            "abcd:abcd:abcd:abcd:abcd:abcd:abcd:abcd:198.51.100.2" to
                "abcd:abcd:abcd:abcd:abcd:abcd:abcd:abcd:198.51.*.*",
            "${longHexPrefix120}198.51.100.2" to "${longHexPrefix120}198.51.*.*",
            "*Hop1 deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:198.51.100.2.*" to
                "*Hop1 deadbeef:deadbeef:deadbeef:deadbeef:deadbeef:198.51.*.*.*",
        )
        for ((input, expected) in cases) {
            val once = IpPrivacyMasker.mask(input)
            assertEquals("Iteration 14 mismatch for: $input", expected, once)
            assertEquals("Iteration 14 idempotency failed for: $input", once, IpPrivacyMasker.mask(once))
        }
    }
}
