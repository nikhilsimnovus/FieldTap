package com.fieldtap.ui.common

import androidx.lifecycle.ViewModel
import com.fieldtap.app.AppGraph
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GraphViewModelFactoryTest {
    private class GraphViewModel(val graph: AppGraph) : ViewModel()

    private class OtherViewModel : ViewModel()

    @Test
    fun buildsTheViewModelFromTheGraph() {
        val graph = FakeAppGraph()
        var builds = 0
        val factory = graphViewModelFactory(graph) {
            builds += 1
            GraphViewModel(it)
        }

        val viewModel = factory.create(GraphViewModel::class.java)

        assertSame(graph, viewModel.graph)
        assertEquals(1, builds)
    }

    @Test
    fun refusesToBuildAnotherType() {
        val factory = graphViewModelFactory(FakeAppGraph()) { GraphViewModel(it) }

        assertThrows(IllegalArgumentException::class.java) { factory.create(OtherViewModel::class.java) }
    }
}

class FileSharerTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun sharesFilesInsideExportsAtAnyDepth() {
        val cache = folder.newFolder("cache")
        val zip = file(cache, "exports/20260910-143000_Mall-walk.zip")
        val probe = file(cache, "exports/probe/probe-google-pixel-8.json")

        assertTrue(FileSharer.isShareable(cache, zip))
        assertTrue(FileSharer.isShareable(cache, probe))
    }

    @Test
    fun neverSharesAnythingOutsideExports() {
        val cache = folder.newFolder("cache")
        val sessions = folder.newFolder("sessions", "20260910-143000_Mall-walk")
        val kpi = File(sessions, "kpi.csv").apply { writeText("time_utc\r\n") }
        file(cache, "exports/keep.zip")
        val beside = file(cache, "other.zip")
        val sneaky = File(cache, "exports/../../sessions/20260910-143000_Mall-walk/kpi.csv")

        assertFalse(FileSharer.isShareable(cache, kpi))
        assertFalse(FileSharer.isShareable(cache, beside))
        assertFalse("a path that climbs out of exports is resolved first", FileSharer.isShareable(cache, sneaky))
    }

    @Test
    fun neverSharesADirectoryOrAMissingFile() {
        val cache = folder.newFolder("cache")
        val exports = File(cache, FileSharer.EXPORTS_DIR).apply { mkdirs() }

        assertFalse(FileSharer.isShareable(cache, exports))
        assertFalse(FileSharer.isShareable(cache, File(exports, "gone.zip")))
    }

    private fun file(root: File, path: String): File = File(root, path).apply {
        requireNotNull(parentFile).mkdirs()
        writeBytes(byteArrayOf(0x50, 0x4b, 0x05, 0x06))
    }
}
