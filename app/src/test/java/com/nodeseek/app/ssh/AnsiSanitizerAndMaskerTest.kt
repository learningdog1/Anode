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
}
