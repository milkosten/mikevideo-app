package com.mikeos.video.agent

import android.content.Context
import android.util.Log
import com.mikeos.core.MikeAgentConfig
import com.mikeos.core.agent.MikeAgent
import com.mikeos.core.agent.Skill
import com.mikeos.core.agent.Soul
import com.mikeos.core.runtime.HeartbeatService
import com.mikeos.video.BuildConfig
import com.mikeos.video.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Wires the shared MikeAgent runtime (vendored under `com.mikeos.core`) into MikeVideo.
 *
 * MikeVideo's agent is Mike's **archivist** — it keeps every camera video safe on the cloud
 * and browsable. The auto-sync itself is DETERMINISTIC on the heartbeat (the perception
 * provider drives [SyncManager.tick] each beat, throttled) — it is NOT gated on the LLM
 * choosing a skill (CLAUDE.md: proactive features must fire on the heartbeat). The skills only
 * let the brain / siblings query and nudge that machine.
 *
 * Skills:
 *  • list_videos  -> the cloud library (title + status)
 *  • sync_status  -> how many camera videos are safe vs pending
 *  • upload_now   -> force a sync tick immediately (bypass the throttle)
 */
object VideoMikeAgent {

    private const val TAG = "VideoMikeAgent"

    // Sibling agents an archivist naturally collaborates with on this phone.
    private val SIBLINGS = listOf("MikePhotos", "MikeBrief", "MikeGuide", "MikeMind")

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        val sync = SyncManager.get(app)

        val soul = Soul(
            agentName = "Video",
            appName = "MikeVideo",
            persona = "I'm MikeVideo's agent — Mike's archivist. My primary job is to keep every " +
                "video Mike shoots safe on his own server and instantly browsable. I quietly " +
                "auto-sync new camera clips over Wi-Fi and make sure nothing is ever lost.",
            goals = listOf(
                "Automatically back up every new camera video to mikevideo-cloud over Wi-Fi",
                "Never lose or re-upload a clip — resume interrupted uploads, skip synced ones",
                "Keep Mike's library browsable and playable (HLS) across all his devices",
            ),
        )

        val skills = buildSkills(sync)

        // DETERMINISTIC auto-sync: every beat runs a (throttled) sync tick and reports it as the
        // agent's perception. This is the heartbeat-driven proactive work, not an LLM skill.
        HeartbeatService.perceptionProvider = {
            val line = runCatching { sync.tick() }.getOrElse { "sync error: ${it.message}" }
            "MikeVideo auto-sync: $line"
        }

        // CROSS-DEVICE SYNC (Phase 1): every beat, pull the user-scoped video library LIST
        // (metadata only — titles + status + HLS urls, never the media bytes) from the cloud
        // into the resident SyncManager state, so clips Mike shot on his OTHER device are
        // already present here live (not only when the grid re-fetches on app-open).
        HeartbeatService.syncProvider = { SyncManager.get(app).refreshLibrary() }

        bg.launch {
            runCatching {
                MikeAgent.install(
                    app,
                    MikeAgentConfig(
                        daemonToken = BuildConfig.DAEMON_TOKEN,
                        userName = "Mike",
                        siblings = SIBLINGS,
                    ),
                    soul,
                    skills,
                )
                HeartbeatService.start(app)
                MikeAgent.get()?.connectHive()
                Log.i(TAG, "MikeAgent installed; heartbeat + hive started (siblings=$SIBLINGS)")
            }.onFailure { Log.w(TAG, "MikeAgent install failed: ${it.message}") }
        }
    }

    private fun buildSkills(sync: SyncManager): List<Skill> = listOf(
        Skill(
            name = "list_videos",
            description = "List Mike's videos on the cloud (newest first) with their processing " +
                "status (uploading / encoding / ready). Use this to tell Mike what's in his library.",
            paramsSchema = """{}""",
            run = {
                val vids = sync.library()
                if (vids.isEmpty()) "No videos in the cloud library yet."
                else vids.take(20).joinToString("\n") { v ->
                    "- ${v.filename} [${v.status}]" +
                        (v.durationSec?.let { " ${it.toInt()}s" } ?: "")
                }
            },
        ),
        Skill(
            name = "sync_status",
            description = "Report how many of Mike's local camera videos are safely backed up to " +
                "the cloud vs still pending, and what the sync engine is currently doing.",
            paramsSchema = """{}""",
            run = { sync.statusLine() },
        ),
        Skill(
            name = "upload_now",
            description = "Force an auto-sync pass right now (bypasses the normal throttle): scan " +
                "the camera for new videos and upload any that aren't backed up yet. Wi-Fi/battery " +
                "gating still applies. Use when Mike asks to back up now.",
            paramsSchema = """{}""",
            run = { sync.syncNow() },
        ),
    )
}
