package com.panofusion.app

/** Selects an induced connected subgraph of geometrically verified edges; time is not a match. */
object ConnectedFrameSelector {
    const val CANDIDATE_LIMIT = 96
    data class Result(val indices: List<Int>, val disconnected: List<Int>, val componentSizes: List<Int>)

    fun select(groups: List<String>, edges: DoubleArray, limit: Int, anchor: Int = -1): Result {
        val n = groups.size
        require(n > 0 && edges.size == n * n && limit >= 2)
        fun linked(a: Int, b: Int) = a != b && edges[a * n + b] >= 0 && edges[a * n + b].isFinite()
        val remaining = (0 until n).toMutableSet()
        val components = mutableListOf<List<Int>>()
        while (remaining.isNotEmpty()) {
            val queue = ArrayDeque<Int>(); queue.add(remaining.first()); remaining.remove(queue.first())
            val component = mutableListOf<Int>()
            while (queue.isNotEmpty()) {
                val v = queue.removeFirst(); component += v
                val neighbours = remaining.filter { linked(v, it) }
                neighbours.forEach { remaining.remove(it); queue.add(it) }
            }
            components += component
        }
        val required = groups.toSet()
        val usable = components.filter { c -> c.size >= 2 && c.map { groups[it] }.toSet().containsAll(required) && (anchor !in 0 until n || anchor in c) }
        require(usable.isNotEmpty()) {
            "E_DISCONNECTED：${n} 张候选帧形成 ${components.size} 个未连通片段（${components.map { it.size }.sortedDescending().joinToString("/")} 帧）。" +
                "至少一项素材或参考帧没有通过视觉匹配，尚未改变已有全景。请保留素材并导出工程/诊断包，或停用不相关批次后重试。"
        }
        val component = usable.maxBy { it.size }.toSet()
        val chosen = linkedSetOf(if (anchor in component) anchor else component.min())
        // Before filling the budget, connect at least one frame of every asset by verified paths.
        while (!chosen.map { groups[it] }.toSet().containsAll(required)) {
            val represented = chosen.map { groups[it] }.toSet()
            val queue = ArrayDeque<Int>(); queue.addAll(chosen)
            val parent = chosen.associateWith { -1 }.toMutableMap()
            var target = -1
            while (queue.isNotEmpty() && target < 0) {
                val v = queue.removeFirst()
                for (u in component.sorted()) if (u !in parent && linked(v, u)) {
                    parent[u] = v
                    if (groups[u] !in represented) { target = u; break }
                    queue.add(u)
                }
            }
            check(target >= 0)
            val path = mutableListOf<Int>()
            while (target !in chosen) { path += target; target = parent.getValue(target) }
            require(chosen.size + path.size <= limit) { "E_BRIDGE_BUDGET：连接全部素材需要更多桥接帧，请减少本次启用批次" }
            chosen.addAll(path.reversed())
        }
        while (chosen.size < limit) {
            val candidates = component.filter { it !in chosen && chosen.any { v -> linked(v, it) } }
            if (candidates.isEmpty()) break
            // A near-identical repeated revolution has a small verified image displacement,
            // whereas a new overlapping direction contributes a larger displacement.
            fun novelty(v: Int) = chosen.filter { linked(v, it) }.minOf { edges[v * n + it] }
            val next = candidates.maxWith(compareBy<Int> { novelty(it) }.thenBy { -it })
            if (chosen.size >= 2 && novelty(next) < 0.015) break
            chosen += next
        }
        require(chosen.size >= 2) { "E_FRAMES：没有足够的不同视角可生成全景" }
        return Result(chosen.sorted(), (0 until n).filter { it !in component }, components.map { it.size })
    }
}
