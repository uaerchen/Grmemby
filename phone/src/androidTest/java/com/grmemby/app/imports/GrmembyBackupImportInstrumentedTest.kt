package com.grmemby.app.imports

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.grmemby.data.repository.ServerTransferRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GrmembyBackupImportInstrumentedTest {
    @Test
    fun importUserBackupFileWithFirstLaunchOptions() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backupFile = File(context.cacheDir, "grmemby-import-regression.json")
        assertTrue("Prepared backup file is missing", backupFile.isFile && backupFile.length() > 0L)

        val result = ServerTransferRepository(context).importFromUri(
            Uri.fromFile(backupFile),
            ServerTransferRepository.TransferOptions(
                playbackSettings = false,
                servers = true,
                remarks = true,
                credentials = true
            )
        )
        assertTrue("Import failed: ${result.exceptionOrNull()?.javaClass?.name}: ${result.exceptionOrNull()?.message}", result.isSuccess)
        assertTrue("Expected at least one imported/merged server", result.getOrThrow().importedServers > 0)
    }
}
