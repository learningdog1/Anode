package com.nodeseek.app.data.repository

import com.nodeseek.app.data.local.SettingsSecretCipher
import com.nodeseek.app.data.local.SoftwareSettingsSecretCipher
import com.nodeseek.app.domain.model.AccountSession
import com.nodeseek.app.domain.model.AccountSlotStatus
import com.nodeseek.app.domain.model.CookieHostScope
import com.nodeseek.app.domain.model.StoredCookieRecord
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicLong

class AccountCookieVaultTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `encrypts cookies with AES-GCM, persists atomically across reload, and supports status update and removal`() = runTest {
        val vaultFile = File(tempFolder.root, FileAccountCookieVault.VAULT_FILE_NAME)
        val cipher = SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey())
        val clock = AtomicLong(1_700_000_000_000L)

        val vault1 = FileAccountCookieVault(
            vaultFile = vaultFile,
            cipher = cipher,
            clock = { clock.getAndAdd(100L) },
        )

        val sessionA = AccountSession(
            uid = 1001L,
            username = "Alice",
            drumsticks = 450,
            stardust = 12,
        )
        val cookiesA = listOf(
            StoredCookieRecord("session", "secret_cookie_value_alpha", CookieHostScope.SHARED_DOT_DOMAIN),
            StoredCookieRecord("smac", "secret_smac_alpha", CookieHostScope.WWW_ONLY),
        )
        vault1.saveOrUpdateSlot(sessionA, cookiesA, AccountSlotStatus.ACTIVE)

        val rawDiskJson = vaultFile.readText(Charsets.UTF_8)
        assertFalse(rawDiskJson.contains("secret_cookie_value_alpha"))
        assertFalse(rawDiskJson.contains("secret_smac_alpha"))
        assertTrue(rawDiskJson.contains(SettingsSecretCipher.PREFIX_SW))

        val sessionB = AccountSession(
            uid = 2002L,
            username = "Bob",
            drumsticks = 920,
            stardust = 30,
        )
        val cookiesB = listOf(
            StoredCookieRecord("session", "secret_cookie_value_beta", CookieHostScope.SHARED_DOT_DOMAIN),
        )
        vault1.saveOrUpdateSlot(sessionB, cookiesB, AccountSlotStatus.ACTIVE)

        // Saving B as ACTIVE automatically demotes A to READY
        assertEquals(AccountSlotStatus.READY, vault1.getSlot(1001L)?.status)
        assertEquals(AccountSlotStatus.ACTIVE, vault1.getSlot(2002L)?.status)

        // Cold reload from disk into a fresh FileAccountCookieVault instance
        val vault2 = FileAccountCookieVault(
            vaultFile = vaultFile,
            cipher = cipher,
        )
        vault2.ensureLoaded()
        assertEquals(2, vault2.slotsFlow.value.size)
        assertEquals(cookiesA, vault2.decryptCookies(1001L))
        assertEquals(cookiesB, vault2.decryptCookies(2002L))

        // Mark A expired and verify persistence
        vault2.markStatus(1001L, AccountSlotStatus.EXPIRED)
        val vault3 = FileAccountCookieVault(vaultFile = vaultFile, cipher = cipher)
        vault3.ensureLoaded()
        assertEquals(AccountSlotStatus.EXPIRED, vault3.getSlot(1001L)?.status)

        // Remove A and verify only B remains
        vault3.removeSlot(1001L)
        assertNull(vault3.getSlot(1001L))
        assertNotNull(vault3.getSlot(2002L))
    }

    @Test
    fun `corrupt vault file is backed up and failed cipher decryption returns null without crashing`() = runTest {
        val vaultFile = File(tempFolder.root, FileAccountCookieVault.VAULT_FILE_NAME)
        vaultFile.writeText("{not valid json!!!", Charsets.UTF_8)

        val cipher1 = SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey())
        val vault = FileAccountCookieVault(
            vaultFile = vaultFile,
            cipher = cipher1,
            clock = { 123456L },
        )
        vault.ensureLoaded()
        assertTrue(vault.slotsFlow.value.isEmpty())
        val corruptBackup = File(tempFolder.root, "${FileAccountCookieVault.VAULT_FILE_NAME}.corrupt.123456")
        assertTrue(corruptBackup.exists())

        // Write valid slot encrypted by cipher1, then read with a mismatched key (simulating tampered/failed decryption)
        val session = AccountSession(uid = 3003L, username = "Charlie")
        vault.saveOrUpdateSlot(
            session,
            listOf(StoredCookieRecord("session", "charlie_secret", CookieHostScope.SHARED_DOT_DOMAIN)),
            AccountSlotStatus.ACTIVE,
        )

        val wrongKeyCipher = SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey())
        val vaultWithWrongKey = FileAccountCookieVault(vaultFile = vaultFile, cipher = wrongKeyCipher)
        vaultWithWrongKey.ensureLoaded()

        assertNotNull(vaultWithWrongKey.getSlot(3003L))
        assertNull(vaultWithWrongKey.decryptCookies(3003L))
    }

    @Test
    fun `unencrypted plaintext cookie envelope is rejected by decryptCookies`() = runTest {
        val vaultFile = File(tempFolder.root, FileAccountCookieVault.VAULT_FILE_NAME)
        val cipher = SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey())
        vaultFile.writeText(
            """
            {
              "schemaVersion": 1,
              "slots": [
                {
                  "session": { "uid": 4004, "username": "Tampered" },
                  "encryptedCookies": "[{\"name\":\"session\",\"value\":\"plaintext\",\"scope\":\"SHARED_DOT_DOMAIN\"}]",
                  "status": "READY",
                  "addedAtEpochMillis": 1000,
                  "lastUsedAtEpochMillis": 1000
                }
              ]
            }
            """.trimIndent(),
            Charsets.UTF_8,
        )

        val vault = FileAccountCookieVault(vaultFile = vaultFile, cipher = cipher)
        vault.ensureLoaded()
        assertNull(vault.decryptCookies(4004L))
    }

    @Test
    fun `blank username session is ignored by saveOrUpdateSlot and updateSessionMetadata`() = runTest {
        val vaultFile = File(tempFolder.root, FileAccountCookieVault.VAULT_FILE_NAME)
        val cipher = SoftwareSettingsSecretCipher(SoftwareSettingsSecretCipher.randomKey())
        val vault = FileAccountCookieVault(vaultFile = vaultFile, cipher = cipher)

        vault.saveOrUpdateSlot(
            session = AccountSession(uid = 88L, username = "   "),
            cookies = listOf(StoredCookieRecord("session", "blank_user_tok", CookieHostScope.SHARED_DOT_DOMAIN)),
            status = AccountSlotStatus.ACTIVE,
        )
        assertNull(vault.getSlot(88L))
        assertTrue(vault.slotsFlow.value.isEmpty())

        vault.updateSessionMetadata(AccountSession(uid = 88L, username = ""), AccountSlotStatus.READY)
        assertNull(vault.getSlot(88L))
    }
}
