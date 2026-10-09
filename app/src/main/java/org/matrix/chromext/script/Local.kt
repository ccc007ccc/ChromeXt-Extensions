package org.matrix.chromext.script

import android.content.Context
import java.io.File
import java.io.FileReader
import kotlin.random.Random
import org.json.JSONArray
import org.matrix.chromext.Chrome
import org.matrix.chromext.Resource
import org.matrix.chromext.utils.Log
import org.matrix.chromext.utils.randomString

object Local {

  val customizeDevTool: String
  val eruda: String
  val encoding: String
  val initChromeXt: String
  val openEruda: String
  val cspRule: String
  val cosmeticFilter: String
  val key = Random.nextDouble()
  val name = randomString(25)

  var eruda_version: String?

  val anchorInChromeXt: Int

  // Line number of the scripts.js bridge anchor, used to verify ChromeXt.dispatch

  init {
    val ctx = Chrome.getContext()
    Resource.enrich(ctx)
    customizeDevTool = ctx.assets.open("devtools.js").bufferedReader().use { it.readText() }
    val css =
        JSONArray(ctx.assets.open("eruda.css").bufferedReader().use { it.readText() }.split("\n\n"))
    eruda =
        "eruda._styles = ${css};\n" +
            ctx.assets
                .open("eruda.js")
                .bufferedReader()
                .use { it.readText() }
                .replaceFirst("Symbol.ChromeXt", "Symbol." + name)
                .replaceFirst("ChromeXtUnlockKeyForEruda", key.toString())
    encoding = ctx.assets.open("encoding.js").bufferedReader().use { it.readText() }
    eruda_version = getErudaVersion()
    val localScript =
        ctx.assets
            .open("scripts.js")
            .bufferedReader()
            .use { it.readText() }
            .split("// Kotlin separator\n\n")

    val seed = Random.nextDouble()
    // Use empty lines to randomize anchorInChromeXt
    val parts =
        localScript[0]
            .replaceFirst("Symbol.ChromeXt", "Symbol." + name)
            .replaceFirst("ChromeXtUnlockKeyForInit", key.toString())
            .split("\n")
            .filter { if (it.length != 0) true else Random.nextDouble() > seed }
    anchorInChromeXt = parts.indexOfFirst { it.endsWith("// Kotlin anchor") } + 2
    initChromeXt = parts.joinToString("\n")
    openEruda =
        localScript[1]
            .replaceFirst("Symbol.ChromeXt", "Symbol." + name)
            .replaceFirst("ChromeXtUnlockKeyForEruda", key.toString())
    cspRule = localScript[2]
    cosmeticFilter = localScript[3]
  }

  fun getErudaVersion(ctx: Context = Chrome.getContext(), versionText: String? = null): String? {
    val eruda = File(ctx.filesDir, "Eruda.js")
    if (eruda.exists() || versionText != null) {
      val verisonReg = Regex(" eruda v(?<version>[\\d\\.]+) https://")
      val firstLine = (versionText ?: FileReader(eruda).use { it.readText() }).lines()[0]
      val vMatchGroup = verisonReg.find(firstLine)?.groups
      if (vMatchGroup != null) {
        return vMatchGroup[1]?.value as String
      } else if (eruda.exists()) {
        eruda.delete()
        Log.toast(ctx, "Eruda.js is corrupted")
      }
    }
    return null
  }
}
