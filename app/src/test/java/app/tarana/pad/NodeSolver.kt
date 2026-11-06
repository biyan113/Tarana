package app.tarana.pad

import app.tarana.pad.player.ChallengeSolver
import app.tarana.pad.player.PlayerScript
import app.tarana.pad.player.WebViewSolver
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

/** 测试用：用 node 跑 app 里打包的同一份 yt-dlp-ejs（对应 Android 上的 WebViewSolver） */
class NodeSolver(private val ejsDir: File) : ChallengeSolver {
    override fun solve(player: PlayerScript, sig: List<String>, n: List<String>): Map<String, String> {
        val dir = File(System.getProperty("java.io.tmpdir"), "tarana-node-solver").apply { mkdirs() }
        val runner = dir.resolve("run.cjs")
        runner.writeText(
            """
            const fs = require('fs');
            const lib = new Function(fs.readFileSync(process.argv[2] + '/yt.solver.lib.min.js', 'utf8') + ';return lib;')();
            globalThis.meriyah = lib.meriyah; globalThis.astring = lib.astring;
            const jsc = new Function(fs.readFileSync(process.argv[2] + '/yt.solver.core.js', 'utf8') + ';return jsc;')();
            const input = JSON.parse(fs.readFileSync(0, 'utf8'));
            input.player = fs.readFileSync(input.playerFile, 'utf8');
            process.stdout.write(JSON.stringify(jsc(input)));
            """.trimIndent()
        )
        val playerFile = dir.resolve("player-${player.id}.js").apply { if (!exists()) writeText(player.code) }
        val input = buildJsonObject {
            put("type", "player")
            put("playerFile", playerFile.absolutePath)
            put("requests", JsonArray(buildList {
                if (sig.isNotEmpty()) add(buildJsonObject { put("type", "sig"); put("challenges", JsonArray(sig.map(::JsonPrimitive))) })
                if (n.isNotEmpty()) add(buildJsonObject { put("type", "n"); put("challenges", JsonArray(n.map(::JsonPrimitive))) })
            }))
        }
        val p = ProcessBuilder("node", runner.absolutePath, ejsDir.absolutePath).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        p.outputStream.use { it.write(input.toString().toByteArray()) }
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return WebViewSolver.parseOutput(out)
    }
}
