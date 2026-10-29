package com.panofusion.app

import org.junit.Assert.*
import org.junit.Test

class ConnectedFrameSelectorTest {
    private fun matrix(n: Int, distance: (Int, Int) -> Double) = DoubleArray(n * n) { distance(it / n, it % n) }

    @Test fun threeRevolutionsKeepVisualBridgesInsteadOfUniformTimeGaps() {
        val n = 36
        val graph = matrix(n) { a, b ->
            val difference = kotlin.math.abs(a % 12 - b % 12)
            val steps = minOf(difference, 12 - difference)
            if (steps <= 1) steps * 0.1 else -1.0
        }
        val uniform = KeyframeBudget.evenly((0 until n).toList(), 24)
        val uniformGraph = matrix(uniform.size) { a, b -> graph[uniform[a] * n + uniform[b]] }
        val uniformResult = ConnectedFrameSelector.select(List(uniform.size) { "video" }, uniformGraph, 24)
        assertTrue("The old temporal sample splits the scene", uniformResult.componentSizes.size > 1)
        val result = ConnectedFrameSelector.select(List(n) { "video" }, graph, 24)
        assertTrue(result.disconnected.isEmpty())
        assertEquals("All real directions must be represented", (0 until 12).toSet(), result.indices.map { it % 12 }.toSet())
        assertTrue(result.indices.size <= 24)
    }

    @Test fun isolatedFrameCanBeExcludedButNotAnEntireUnrelatedAsset() {
        val graph = matrix(3) { a, b -> if (a < 2 && b < 2) 0.1 else -1.0 }
        val partial = ConnectedFrameSelector.select(List(3) { "same-video" }, graph, 24)
        assertEquals(listOf(0, 1), partial.indices)
        assertEquals(listOf(2), partial.disconnected)
        try { ConnectedFrameSelector.select(listOf("old", "old", "unrelated"), graph, 24); fail("Unrelated session silently discarded") }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().startsWith("E_DISCONNECTED")) }
    }

    @Test fun persistedAnchorCannotBeSilentlyReplaced() {
        val graph = matrix(3) { a, b -> if (a > 0 && b > 0) 0.1 else -1.0 }
        try { ConnectedFrameSelector.select(List(3) { "same" }, graph, 24, anchor = 0); fail("Lost reference anchor") }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().startsWith("E_DISCONNECTED")) }
    }

    @Test fun connectingEveryAssetMustFitTheRealFrameBudget() {
        val graph = matrix(10) { a, b -> if (kotlin.math.abs(a - b) == 1) 0.1 else -1.0 }
        try { ConnectedFrameSelector.select(List(10) { if (it == 9) "new" else "old" }, graph, 5, anchor = 0); fail("Bridge path was discarded") }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().startsWith("E_BRIDGE_BUDGET")) }
        val result = ConnectedFrameSelector.select(List(10) { if (it == 9) "new" else "old" }, graph, 10, anchor = 0)
        assertEquals((0..9).toList(), result.indices)
    }
}
