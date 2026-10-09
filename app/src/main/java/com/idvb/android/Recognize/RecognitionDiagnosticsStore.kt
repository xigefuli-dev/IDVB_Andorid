package com.idvb.android.recognize

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import com.idvb.android.recognize.cv.OpenCvRuntime
import com.idvb.android.BuildConfig
import com.idvb.android.diagnostics.DiagnosticWriteTracker
import kotlinx.serialization.json.*
import java.security.MessageDigest

data class CaptureDiagnosticsContext(
    val screenWidth: Int,
    val screenHeight: Int,
    val captureLeft: Int,
    val captureTop: Int,
    val captureRight: Int,
    val captureBottom: Int,
    val classId: String?,
    val className: String?,
    val sessionId: String? = null,
    val captureSource: String? = null,
    val scanRequestId: String? = null,
    val rotationAtCaptureCallback: Int? = null,
    val calibrationRevisionAtCaptureCallback: Long? = null,
    val captureCallbackNanos: Long? = null,
    val workerStartedNanos: Long? = null,
)

/**
 * 保存可同时交给 Android 与 Desktop 识别器的原始输入及 Android 证据输出。
 * 包内 captured.png 从 MediaProjection 裁剪结果直接编码，不包含候选界面或悬浮窗。
 */
class RecognitionDiagnosticsStore(context: Context) {
    private val appContext = context.applicationContext
    private val history = com.idvb.android.diagnostics.DiagnosticHistory(File(appContext.filesDir, "idvb/diagnostics"))

    @Synchronized
    fun record(
        capturedFrame: Bitmap,
        result: RecognitionResult?,
        captureContext: CaptureDiagnosticsContext,
        replayInputs: RecognitionReplayInputs? = null,
        failure: Throwable? = null,
        inputCaptureFailure: String? = null,
        scanTrace: com.idvb.android.alignment.AlignmentTrace? = null,
    ): Result<File> = history.preservingFiles {
        val pending = DiagnosticWriteTracker.begin(File(appContext.filesDir, "idvb/diagnostics"), "recognition-record")
        try { runCatching {
        require(!capturedFrame.isRecycled) { "原始扫描帧已经释放" }
        val now = System.currentTimeMillis()
        val directory = captureContext.sessionId?.let { history.directoryFor(it, "recognition") }
            ?: history.directoryAt(now, "recognition")
        directory.mkdirs()
        require(directory.isDirectory) { "无法创建识别诊断目录" }

        val suffix = "$now-${java.util.UUID.randomUUID()}"
        val finalFile = File(directory, "recognition-$suffix.zip")
        val temporary = File(directory, ".recognition-$suffix.tmp")
        val writeStarted = System.nanoTime()
        val artifacts = linkedMapOf<String, Pair<Long, String>>()
        val missing = replayInputs?.verifyUnchanged()?.toMutableList()
            ?: mutableListOf("scan-input-snapshot-not-captured")
        inputCaptureFailure?.let { missing += "snapshot-capture-failed:$it" }
        if (replayInputs?.classId != captureContext.classId) missing += "snapshot-class-does-not-match-capture"
        val recorded = result ?: RecognitionResult(capturedFrame, emptyList(), route = "unavailable")
        var captureEncodeMs = 0.0
        var referenceWriteMs = 0.0
        var sourceWriteMs = 0.0
        var sourceFingerprint: String? = null
        try {
            ZipOutputStream(FileOutputStream(temporary).buffered()).use { zip ->
                fun add(name: String, write: (java.io.OutputStream) -> Unit) {
                    zip.putNextEntry(ZipEntry(name))
                    val digest = MessageDigest.getInstance("SHA-256")
                    var count = 0L
                    val output = object : java.io.OutputStream() {
                        override fun write(b: Int) { zip.write(b); digest.update(b.toByte()); count++ }
                        override fun write(b: ByteArray, off: Int, len: Int) { zip.write(b, off, len); digest.update(b, off, len); count += len }
                    }
                    write(output); zip.closeEntry()
                    artifacts[name] = count to digest.digest().joinToString("") { "%02x".format(it) }
                }
                val encodeStarted = System.nanoTime()
                add("captured.png") { output -> check(capturedFrame.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "无法编码原始扫描帧"
                } }
                captureEncodeMs = (System.nanoTime() - encodeStarted) / 1e6
                val referencesStarted = System.nanoTime()
                replayInputs?.files?.forEach { (name, file) -> add(name) { output -> file.inputStream().use { it.copyTo(output, 64 * 1024) } } }
                referenceWriteMs = (System.nanoTime() - referencesStarted) / 1e6
                scanTrace?.let { trace ->
                    val eventJson = Json { allowSpecialFloatingPointValues=true; encodeDefaults=true }
                    val chunk = java.io.ByteArrayOutputStream(64*1024)
                    var part = 0
                    fun flushEvents() {
                        if (chunk.size()==0) return
                        add("scan-events-${part++}.jsonl") { chunk.writeTo(it) }; chunk.reset()
                    }
                    trace.forEachEvent { event ->
                        val bytes = eventJson.encodeToString(com.idvb.android.alignment.AlignmentLogEvent.serializer(),event).encodeToByteArray()
                        if (chunk.size()+bytes.size > 4*1024*1024) flushEvents()
                        chunk.write(bytes); chunk.write(10)
                    }
                    flushEvents()
                    val scratch = ByteArray(64*1024)
                    trace.artifactDataSnapshot().forEach { (name,artifact) -> add("scan-evidence/$name") { artifact.writeTo(it,scratch) } }
                }
                val provenanceStarted = System.nanoTime()
                runCatching {
                    val provenanceBytes = appContext.assets.open("alignment-provenance.json").use { it.readBytes() }
                    val provenance = Json.parseToJsonElement(provenanceBytes.decodeToString()).jsonObject
                    add("alignment-provenance.json") { it.write(provenanceBytes) }
                    val files = provenance.getValue("files").jsonObject
                    check(files.isNotEmpty()) { "Empty source snapshot" }
                    files.forEach { (path, hash) ->
                        val name = "alignment-source/$path"
                        add(name) { output -> appContext.assets.open(name).use { it.copyTo(output, 64 * 1024) } }
                        check(artifacts.getValue(name).second == hash.jsonPrimitive.content) { "Source snapshot hash mismatch: $path" }
                    }
                    // Templates are immutable build inputs; bundle and bind their exact runtime bytes too.
                    appContext.assets.list("recognition").orEmpty().filter { it.endsWith(".png") }.forEach { name ->
                        add("runtime-assets/recognition/$name") { output -> appContext.assets.open("recognition/$name").use { it.copyTo(output, 64 * 1024) } }
                    }
                    sourceFingerprint = provenance.getValue("sourceFingerprint").jsonPrimitive.content
                }.onFailure { missing += "source-or-runtime-assets-unavailable:${it.message}" }
                sourceWriteMs = (System.nanoTime() - provenanceStarted) / 1e6
                if (captureContext.captureRight - captureContext.captureLeft != capturedFrame.width ||
                    captureContext.captureBottom - captureContext.captureTop != capturedFrame.height)
                    missing += "capture-dimensions-do-not-match-region"
                val replayMetadata = buildJsonObject {
                    put("requestId", suffix); put("buildVersion", BuildConfig.BUILD_VERSION)
                    put("scanEvidence", if (scanTrace != null) "scan-events-*.jsonl+scan-evidence; private-streamed; large-series-zlib-f64le" else "not-recorded")
                    put("scanEvidenceRequestId",scanTrace?.id?.let(::JsonPrimitive) ?: JsonNull)
                    put("sourceFingerprint", sourceFingerprint?.let(::JsonPrimitive) ?: JsonNull)
                    put("replayReady", missing.isEmpty() && result != null)
                    put("replayScope", "full-class-catalog-production-recognition")
                    put("replayUnavailableReasons", JsonArray((missing + if (result == null) listOf("scan-has-no-result") else emptyList()).map(::JsonPrimitive)))
                    put("inputBinding", "catalog-and-reference-bytes-snapshotted-before-scan; live-files-and-catalog-verified-unchanged-before-publication")
                    put("terminal", if (failure is java.util.concurrent.CancellationException) "cancelled" else if (failure != null) "error" else "completed")
                    put("failure", failure?.let { JsonPrimitive("${it.javaClass.simpleName}:${it.message}") } ?: JsonNull)
                    put("eligibleCatalogMapCount", replayInputs?.catalog?.maps?.size ?: 0)
                    put("artifacts", buildJsonObject { artifacts.forEach { (name, info) -> put(name, buildJsonObject {
                        put("bytes", info.first); put("sha256", info.second)
                    }) } })
                    put("diagnosticTimings", buildJsonObject {
                        put("inputSnapshotMs", replayInputs?.captureMs ?: 0.0)
                        put("inputVerificationMs", replayInputs?.verificationMs ?: 0.0)
                        put("captureEncodeAndWriteMs", captureEncodeMs); put("referenceWriteMs", referenceWriteMs); put("sourceWriteMs", sourceWriteMs)
                        put("scope", "diagnostic-cost-only; encoding and archive phases are independent; total archive time is in adjacent write receipt")
                    })
                }

                zip.putNextEntry(ZipEntry("diagnostics.json"))
                zip.write(JsonObject(buildManifest(capturedFrame, recorded, captureContext, now) + replayMetadata).toString().encodeToByteArray())
                zip.closeEntry()
            }
            check(temporary.renameTo(finalFile)) { "无法原子提交识别诊断包" }
            File(directory, "${finalFile.name}.write.json").writeText(buildJsonObject {
                put("archive", finalFile.name); put("archiveBytes", finalFile.length())
                put("archiveWriteMs", (System.nanoTime() - writeStarted) / 1e6)
                put("inputSnapshotMs", replayInputs?.captureMs ?: 0.0)
                put("scope", "archive writer through ZIP close and publish; excludes input snapshot and later feedback export")
            }.toString())
            scanTrace?.releaseWrittenArtifacts()
            finalFile
        } finally {
            temporary.delete()
        }
    }.onFailure { error -> runCatching {
        val folder = captureContext.sessionId?.let { history.directoryFor(it, "recognition") }
            ?: history.directoryAt(System.currentTimeMillis(), "recognition")
        File(folder, "recognition-${java.util.UUID.randomUUID()}.failed.json").writeText(buildJsonObject {
            put("schemaVersion", RecognitionPortContract.DIAGNOSTICS_SCHEMA_VERSION)
            put("replayReady", false); put("replayUnavailableReason", "archive-write-failed:${error.message}")
            put("terminal", if (failure is java.util.concurrent.CancellationException) "cancelled" else "error")
            put("recordedResult", buildManifest(capturedFrame, result ?: RecognitionResult(capturedFrame, emptyList()), captureContext, System.currentTimeMillis()))
        }.toString())
    } } } finally { scanTrace?.retainUnpublishedEvidence(); pending.close() }
    }

    fun recentPackages(): List<File> = history.files("recognition").filter { file ->
        file.isFile && file.name.startsWith("recognition-") && file.extension == "zip"
    }.sortedByDescending(File::lastModified)

    fun latestPackage(): File? = recentPackages().firstOrNull()

    /** JSON is safe to copy without also copying the captured game screenshot. */
    fun diagnosticsJson(packageFile: File): Result<String> = runCatching {
        val source = requireRecentPackage(packageFile)
        ZipFile(source).use { zip ->
            val entry = zip.getEntry("diagnostics.json") ?: error("诊断包缺少 diagnostics.json")
            zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    fun exportPackage(packageFile: File, context: Context, destination: Uri): Result<Unit> = runCatching {
        val source = requireRecentPackage(packageFile)
        val output = context.contentResolver.openOutputStream(destination)
            ?: error("无法写入所选位置")
        output.use { stream -> source.inputStream().use { it.copyTo(stream) } }
    }

    private fun requireRecentPackage(packageFile: File): File = recentPackages()
        .firstOrNull { it.canonicalFile == packageFile.canonicalFile }
        ?: error("诊断包已不存在")

    fun shareLatest(context: Context): Boolean {
        val packageFile = latestPackage() ?: return false
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.diagnostics.files",
            packageFile,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "IDVB Android 识别诊断")
            clipData = ClipData.newRawUri("IDVB recognition diagnostics", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "分享识别诊断"))
        return true
    }

    private fun buildManifest(
        frame: Bitmap,
        result: RecognitionResult,
        context: CaptureDiagnosticsContext,
        createdAt: Long,
    ): JsonObject = buildJsonObject {
        put("schemaVersion", RecognitionPortContract.DIAGNOSTICS_SCHEMA_VERSION)
        putNullable("sessionId", context.sessionId)
        put("createdAt", Instant.ofEpochMilli(createdAt).toString())
        put("route", result.route ?: result.diagnostics?.route ?: "unknown-or-debug-route")
        putNullable("desktopReferenceCommit", if (result.sparseGateDiagnostics == null)
            RecognitionPortContract.DESKTOP_SOURCE_COMMIT else null)
        if (result.sparseGateDiagnostics != null) {
            put("desktopReferenceBasis", "SideEntranceScanPipeline.Part2/Part3 + ScanIdentityEvidence working-tree source; Android full-reference and gate-residual adaptations")
        }
        put("sideEntranceFeatureAlgorithmVersion", RecognitionPortContract.SIDE_ENTRANCE_FEATURE_ALGORITHM_VERSION)
        put("openCvAvailable", OpenCvRuntime.available)
        put("openCvVersion", OpenCvRuntime.version)
        put("capturedImage", "captured.png")
        put("frameWidth", frame.width)
        put("frameHeight", frame.height)
        put("processingScale", result.processingScale)
        put("pixelPolicy", "structure-native-up-to-1440-short-side; gate-template-normalized-separately-up-to-2x")
        put("screenWidth", context.screenWidth)
        put("screenHeight", context.screenHeight)
        put("captureRegion", buildJsonObject {
            put("left", context.captureLeft)
            put("top", context.captureTop)
            put("right", context.captureRight)
            put("bottom", context.captureBottom)
        })
        putNullable("classId", context.classId)
        putNullable("className", context.className)
        put("captureContext", buildJsonObject {
            put("captureSource", context.captureSource?.let(::JsonPrimitive) ?: JsonNull)
            put("scanRequestId", context.scanRequestId?.let(::JsonPrimitive) ?: JsonNull)
            put("rotationAtCaptureCallback", context.rotationAtCaptureCallback?.let(::JsonPrimitive) ?: JsonNull)
            put("calibrationRevisionAtCaptureCallback", context.calibrationRevisionAtCaptureCallback?.let(::JsonPrimitive) ?: JsonNull)
            put("captureCallbackNanos", context.captureCallbackNanos?.let(::JsonPrimitive) ?: JsonNull)
            put("workerStartedNanos", context.workerStartedNanos?.let(::JsonPrimitive) ?: JsonNull)
            val queueMs = context.captureCallbackNanos?.let { captured -> context.workerStartedNanos?.let { (it - captured) / 1e6 } }
            put("workerQueueMs", queueMs?.let(::JsonPrimitive) ?: JsonNull)
            put("timingScope", "host callback receipt and worker start; native frame timestamp and capture sequence are unavailable in Bitmap callback")
            put("nativeFrameTimestampNanos", JsonNull); put("nativeFrameSequence", JsonNull)
        })
        result.vpsgDiagnostics?.let { scan ->
            put("vpsgScan", buildJsonObject {
                put("eligibleFloorCount", scan.eligibleFloorCount)
                put("readyFloorCount", scan.readyFloorCount)
                put("evaluatedFloorCount", scan.evaluatedFloorCount)
                put("visibleEdgePoints", scan.visibleEdgePoints)
                put("elapsedMilliseconds", scan.elapsedMilliseconds)
                put("identityUnique", scan.identityUnique)
                put("algorithm", scan.algorithm)
                put("referenceGeometryAlgorithm", com.idvb.android.alignment.VpsgReferenceGeometry.ALGORITHM_ID)
                put("evidencePolicy", "selected-alignment-prepared-forward+required-walls-reverse+revealed-domain; complete-evaluation-includes-rejected-floors")
                put("structuralThresholds", buildJsonObject {
                    com.idvb.android.recognize.vpsg.VpsgAlignmentTuning.thresholds.forEach { (key,value) -> put(key,value) }
                    put("maximumScale", com.idvb.android.recognize.vpsg.VpsgAlignmentTuning.MAX_SELECTED_SCALE)
                })
                put("floorTimings", buildJsonArray {
                    scan.floorTimings.forEach { floor -> add(buildJsonObject {
                        put("mapId", floor.mapId)
                        put("floorKey", floor.floorKey)
                        put("elapsedMilliseconds", floor.elapsedMilliseconds)
                        put("prepareMilliseconds", floor.prepareMilliseconds)
                        put("translationMilliseconds", floor.translationMilliseconds)
                        put("refinementMilliseconds", floor.refinementMilliseconds)
                        put("verificationMilliseconds", floor.verificationMilliseconds)
                        put("seedCount", floor.seedCount)
                        put("refinedCount", floor.refinedCount)
                        put("outcome", floor.outcome)
                    }) }
                })
            })
        }
        result.sparseGateDiagnostics?.let { scan ->
            put("sparseGateScan", buildJsonObject {
                put("referenceEvidence", "source-appearance-forward+required-structural-reverse+visibility-scoped-v4")
                put("candidatePolicy", "confirmed-or-structure-supported-only; rejected-fits-are-catalog-only")
                put("referenceGeometryAlgorithm", com.idvb.android.alignment.VpsgReferenceGeometry.ALGORITHM_ID)
                put("structuralThresholds", buildJsonObject {
                    com.idvb.android.recognize.vpsg.VpsgAlignmentTuning.thresholds.forEach { (key, value) -> put(key, value) }
                })
                put("wallObservationPolicy",scan.wallObservationPolicy)
                put("formalInputPolicy",scan.formalInputPolicy)
                put("identitySelectionPolicy", "single-explicit-variant-family-minimum-structure-cost")
                put("elapsedMilliseconds", scan.elapsedMilliseconds)
                put("preparationMilliseconds", scan.preparationMilliseconds)
                put("searchAndVerificationMilliseconds", scan.searchAndVerificationMilliseconds)
                put("registrationMilliseconds", scan.registrationMilliseconds)
                put("evaluatedFloorCount", scan.evaluatedFloorCount)
                put("visibleEdgePoints", scan.visibleEdgePoints)
                put("retrievalComplete", scan.retrievalComplete)
                put("supportedIdentityCount", scan.supportedIdentityCount)
                put("identityUnique", scan.identityUnique)
                put("floorEvidence", buildJsonArray {
                    scan.floorEvidence.forEach { floor -> add(buildJsonObject {
                        put("mapId", floor.mapId)
                        put("floorKey", floor.floorKey)
                        put("hypothesisCount", floor.hypothesisCount)
                        put("supportedFraction", floor.supportedFraction)
                        put("meanDistancePixels", floor.meanDistancePixels)
                        put("longestConflictPixels", floor.longestConflictPixels)
                        put("spatialConflict", floor.spatialConflict)
                        put("supported", floor.supported)
                        put("forwardPoints", floor.forwardPoints)
                        put("reverseSupportedFraction", floor.reverseSupportedFraction)
                        put("reversePoints", floor.reversePoints)
                        put("reverseEligiblePoints", floor.reverseEligiblePoints)
                        put("decisionReason", floor.decisionReason)
                        put("hypothesisCoordinateDomain", "processing-screen-pixels")
                        put("hypotheses", buildJsonArray { floor.hypotheses.forEach { add(sparsePose(it)) } })
                        put("formalAttempts", buildJsonArray { floor.formalAttempts.forEach { attempt -> add(buildJsonObject {
                            put("seedHypothesisIndex",attempt.seedHypothesisIndex)
                            put("seed",sparsePose(attempt.seed))
                            put("checked",attempt.checked?.let(::sparsePose) ?: JsonNull)
                            putFinite("registrationMilliseconds",attempt.elapsedMilliseconds)
                            putFinite("verificationMilliseconds",attempt.verificationMilliseconds)
                            put("registrationAccepted",attempt.registrationAccepted)
                            put("registrationRejectionReason",attempt.registrationRejectionReason)
                            put("registrationFailureReason",attempt.registrationFailureReason)
                            put("verificationFailureReason",attempt.verificationFailureReason)
                            put("usedGlobalRecovery",attempt.usedGlobalRecovery)
                            putFinite("registrationCandidateMargin",attempt.registrationCandidateMargin)
                            put("accepted",attempt.accepted)
                            put("decisionReason",attempt.decisionReason)
                            put("registrationBest",attempt.registrationBest?.let { candidate -> buildJsonObject {
                                putFinite("scale",candidate.scale)
                                put("referenceX",candidate.referenceX); put("referenceY",candidate.referenceY)
                                putFinite("offsetX",candidate.offsetX); putFinite("offsetY",candidate.offsetY)
                                putFinite("chamferPixels",candidate.chamferPixels)
                                putFinite("edgeCoverage",candidate.edgeCoverage)
                                putFinite("occupancyCoverage",candidate.occupancyCoverage)
                                putFinite("referenceCoverage",candidate.referenceCoverage)
                                put("referenceCoverageDomain",candidate.referenceCoverageDomain)
                                put("referenceKnownPixels",candidate.referenceKnownPixels)
                                put("referenceUnknownPixels",candidate.referenceUnknownPixels)
                                put("referenceKnownOverlapPixels",candidate.referenceKnownOverlapPixels)
                                put("queryKnownPixels",candidate.queryKnownPixels)
                                put("consistentPartitions",candidate.consistentPartitions)
                                putFinite("compositeCost",candidate.compositeCost)
                                put("isWithinValidBounds",candidate.isWithinValidBounds)
                                put("usedGlobalSearch",candidate.usedGlobalSearch)
                            } } ?: JsonNull)
                        }) } })
                    }) }
                })
            })
        }
        result.diagnostics?.let { diagnostics ->
            val rules = diagnostics.sideEntranceConfig
            put("sideEntranceRules", buildJsonObject {
                put("featureRatio", rules.featureRegionRatio)
                put("coarseScaleStep", rules.coarseScaleStep)
                put("refineStepsPerSide", rules.refineStepsPerSide)
                put("coarsePyramidFactor", rules.coarsePyramidFactor)
                put("scanParallelism", rules.scanParallelism)
                put("minimumScale", rules.minimumScale)
                put("maximumScale", rules.maximumScale)
                put("minimumReferenceSimilarity", rules.minimumReferenceSimilarity)
                put("minimumVerificationSimilarity", rules.minimumVerificationSimilarity)
                put("minimumTemplateMargin", rules.minimumTemplateMargin)
                put("maximumGateSpatialResidualPixels", rules.maximumGateSpatialResidualPixels)
                put("strictChamferLimit", SideEntranceRecognizer.STRICT_CHAMFER_LIMIT)
            })
            put("scanSummary", buildJsonObject {
                put("eligibleMapCount", diagnostics.eligibleMapCount)
                put("readyMapCount", diagnostics.readyMapCount)
                put("rejectedCandidateCount", diagnostics.rejectedCandidateCount)
                put("structureAlgorithmVersion", diagnostics.structureAlgorithmVersion)
                put("structureVerificationCount", diagnostics.structureVerificationCount)
                put("reliableCandidateCount", diagnostics.reliableCandidateCount)
                putFinite("structureTotalMilliseconds", diagnostics.structureTotalMilliseconds)
                put("failureReason", diagnostics.failureReason)
            })
            val gate = diagnostics.gateDetection
            put("gateDetection", buildJsonObject {
                put("searchMode", gate.searchModeUsed.name)
                put("stopReason", gate.stopReason.name)
                put("scalesEvaluated", gate.scalesEvaluated)
                put("regionsEvaluated", gate.regionsEvaluated)
                put("matchTemplateCalls", gate.matchTemplateCalls)
                put("budgetExceeded", gate.budgetExceeded)
                put("elapsedMilliseconds", gate.elapsedMilliseconds)
                put("gates", buildJsonArray {
                    gate.gates.forEachIndexed { index, item ->
                        add(buildJsonObject {
                            put("index", index)
                            put("score", item.score)
                            put("scale", item.scale)
                            put("bounds", buildJsonObject {
                                put("x", item.screenBounds.x)
                                put("y", item.screenBounds.y)
                                put("width", item.screenBounds.width)
                                put("height", item.screenBounds.height)
                            })
                        })
                    }
                })
            })
        }
        put("candidates", buildJsonArray {
            result.candidates.forEachIndexed { index, candidate ->
                add(buildJsonObject {
                    put("rank", index + 1)
                    put("localMapId", candidate.map.id)
                    put("sourceMapId", candidate.map.sourceMapId)
                    put("mapTitle", candidate.map.title)
                    put("floorKey", candidate.floorKey)
                    put("disposition", candidate.disposition.name)
                    putFinite("templateScore", candidate.templateScore)
                    putFinite("templateMargin", candidate.templateMargin)
                    putFinite("chamferPixels", candidate.chamferPixels)
                    putFinite("edgeCoverage", candidate.edgeCoverage)
                    putFinite("occupancyCoverage", candidate.occupancyCoverage)
                    putFinite("referenceCoverage", candidate.referenceCoverage)
                    put("consistentStructurePartitions", candidate.consistentStructurePartitions)
                    putFinite("structureCompositeCost", candidate.structureCompositeCost)
                    putFinite("structureCandidateMargin", candidate.structureCandidateMargin)
                    putFinite("structureScale", candidate.structureScale)
                    putFinite("structureOffsetX", candidate.structureOffsetX)
                    putFinite("structureOffsetY", candidate.structureOffsetY)
                    putNullable("structureRejectionReason", candidate.structureRejectionReason?.name)
                    put("structureForwardPoints", candidate.structureForwardPoints)
                    put("structureReversePoints", candidate.structureReversePoints)
                    put("structureReverseEligiblePoints", candidate.structureReverseEligiblePoints)
                    putNullable("structureDecisionReason", candidate.structureDecisionReason)
                    put("usedStructureGlobalRecovery", candidate.usedStructureGlobalRecovery)
                    putFinite("structureElapsedMilliseconds", candidate.structureElapsedMilliseconds)
                    putFinite("matchScale", candidate.matchScale)
                    put("gateAssociation", candidate.gateAssociationKind.name)
                    put("associatedGateIndex", candidate.associatedGateIndex)
                    putFinite("gateSpatialResidualPixels", candidate.gateSpatialResidualPixels)
                    candidate.matchBounds?.let { bounds ->
                        put("matchBounds", buildJsonObject {
                            put("x", bounds.x)
                            put("y", bounds.y)
                            put("width", bounds.width)
                            put("height", bounds.height)
                        })
                    }
                    put("evidenceLabel", candidate.evidenceLabel)
                })
            }
        })
    }

}

private fun sparsePose(pose: SparseGateHypothesisEvidence): JsonObject = buildJsonObject {
    putFinite("scale",pose.scale); putFinite("offsetX",pose.offsetX); putFinite("offsetY",pose.offsetY)
    put("gateIndex",pose.gateIndex); putFinite("gateResidualPixels",pose.gateResidualPixels)
    putFinite("forwardSupport",pose.forwardSupport); putFinite("reverseSupport",pose.reverseSupport)
    put("forwardPoints",pose.forwardPoints); put("reversePoints",pose.reversePoints)
    putFinite("meanDistancePixels",pose.meanDistancePixels); putFinite("longestConflictPixels",pose.longestConflictPixels)
    put("spatialConflict",pose.spatialConflict); put("rejectionCode",pose.rejectionCode)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putFinite(name: String, value: Double) {
    put(name, if (value.isFinite()) JsonPrimitive(value) else JsonNull)
}

private fun kotlinx.serialization.json.JsonObjectBuilder.putNullable(name: String, value: String?) {
    put(name, value?.let(::JsonPrimitive) ?: JsonNull)
}
