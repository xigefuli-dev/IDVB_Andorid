package com.idvb.android.alignment

/** Explain the terminal gate, never a discarded candidate's failure. Thresholds stay in the solver. */
object AlignmentExplanation {
    fun rejected(events: List<AlignmentLogEvent>): AlignmentResult.Rejected {
        val final = events.lastOrNull { it.stage == "vpsg.verify.final" || it.stage == "vpsg.verify.alternative-final" }
        val failed = (final ?: events.lastOrNull { e -> e.gates.any { !it.passed } })
            ?.gates.orEmpty().filterNot { it.passed }
        fun number(value: Double) = String.format(java.util.Locale.ROOT, "%.1f", value)
        val reasons = failed.map { gate -> when (gate.name) {
            "minimum-live-points", "live-points" -> "可见结构点不足（${gate.actual.toInt()}/${gate.threshold.toInt()}）"
            "live-span-x" -> "可见结构过窄（${gate.actual.toInt()} px，需 ≥${gate.threshold.toInt()} px），请放大或展开地图"
            "live-span-y" -> "可见结构高度不足（${gate.actual.toInt()} px，需 ≥${gate.threshold.toInt()} px）"
            "visible-support" -> "结构支持 ${number(gate.actual * 100)}%，需 ≥${number(gate.threshold * 100)}%"
            "spatial-conflict" -> "局部墙线与参考图冲突"
            "longest-conflict" -> "连续墙线冲突 ${number(gate.actual)} px，需 <${number(gate.threshold)} px"
            "unique-pose", "pose-margin" -> "存在难以区分的位置，请展开更多地图结构"
            "reverse-points" -> "可验证的参考结构不足"
            "reverse-support" -> "已揭开区域的参考结构支持不足"
            "contour-count" -> "未提取到有效墙线轮廓"
            else -> "未通过 ${gate.name} 校验"
        } }.distinct()
        val fallback = when (events.lastOrNull { it.stage == "verification" }?.detail) {
            "SCALE_UNRESOLVED" -> "未找到可靠缩放，请调整地图缩放或展开更多结构"
            "NO_TRANSLATION" -> "未找到可靠位置，请展开更多地图结构"
            else -> "未找到可验证的结构，请检查当前地图和楼层"
        }
        return AlignmentResult.Rejected(reasons.take(2).joinToString("；").ifEmpty { fallback },
            failed.firstOrNull()?.name ?: "structure-unresolved")
    }
}
