package com.nodeseek.app.netbench

import com.nodeseek.app.data.netbench.NetBenchHistoryStore
import com.nodeseek.app.domain.netbench.IpFamily
import com.nodeseek.app.domain.netbench.IpOriginType
import com.nodeseek.app.domain.netbench.NetBenchHistoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class NetBenchHistoryStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `persists deduplicates trims and recovers from corrupt json`() = runBlocking {
        val file = File(tempFolder.root, NetBenchHistoryStore.HISTORY_FILE_NAME)
        val store = NetBenchHistoryStore(historyFile = file, maxEntries = 3, ioDispatcher = Dispatchers.IO)

        store.record(
            NetBenchHistoryEntry(
                host = "1.1.1.1",
                port = 443,
                resolvedIp = "1.1.1.1",
                ipFamily = IpFamily.IPV4,
                lastAvgMs = 30.0,
                lastOriginType = IpOriginType.ANYCAST,
                testedAtEpochMillis = 1000L,
            ),
        )
        store.record(
            NetBenchHistoryEntry(
                host = "tokyo.example.com",
                port = 22,
                resolvedIp = "45.12.34.56",
                ipFamily = IpFamily.IPV4,
                lastAvgMs = 42.5,
                lastOriginType = IpOriginType.BROADCAST,
                testedAtEpochMillis = 2000L,
            ),
        )
        // Re-record same host:port with newer timestamp -> should deduplicate & move to top
        store.record(
            NetBenchHistoryEntry(
                host = "1.1.1.1",
                port = 443,
                resolvedIp = "1.1.1.1",
                ipFamily = IpFamily.IPV4,
                lastAvgMs = 28.0,
                lastOriginType = IpOriginType.ANYCAST,
                testedAtEpochMillis = 3000L,
            ),
        )

        assertEquals(2, store.entries.value.size)
        assertEquals("1.1.1.1", store.entries.value[0].host)
        assertEquals(28.0, store.entries.value[0].lastAvgMs!!, 0.01)

        // Reload in a fresh store instance backed by the same file
        val reloaded = NetBenchHistoryStore(historyFile = file, maxEntries = 3, ioDispatcher = Dispatchers.IO)
        assertEquals(2, reloaded.entries.value.size)
        assertEquals("1.1.1.1", reloaded.entries.value[0].host)

        // Exceed maxEntries=3
        reloaded.record(NetBenchHistoryEntry(host = "a.com", port = 80, testedAtEpochMillis = 4000L))
        reloaded.record(NetBenchHistoryEntry(host = "b.com", port = 443, testedAtEpochMillis = 5000L))
        assertEquals(3, reloaded.entries.value.size)
        assertEquals("b.com", reloaded.entries.value[0].host)

        // Remove one entry
        reloaded.remove("a.com", 80)
        assertEquals(2, reloaded.entries.value.size)

        // Corrupt disk file recovery
        file.writeText("{not valid json!!!")
        val recovered = NetBenchHistoryStore(historyFile = file, maxEntries = 3, ioDispatcher = Dispatchers.IO)
        assertTrue(recovered.entries.value.isEmpty())
    }
}
