package org.matrix.chromext.extensions

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.util.Locale

/**
 * Bounded, strict parsing also rejects duplicate keys instead of silently changing their meaning.
 */
internal fun parseJsonObject(text: String, label: String): JsonObject {
  var nodes = 0
  fun read(reader: JsonReader, depth: Int): JsonElement {
    if (depth > 64 || ++nodes > 65536) invalid("$label is too complex")
    return when (reader.peek()) {
      JsonToken.BEGIN_OBJECT -> {
        val result = JsonObject()
        reader.beginObject()
        while (reader.hasNext()) {
          val name = reader.nextName()
          if (result.has(name)) invalid("$label has duplicate key: $name")
          result.add(name, read(reader, depth + 1))
        }
        reader.endObject()
        result
      }
      JsonToken.BEGIN_ARRAY -> {
        val result = JsonArray()
        reader.beginArray()
        while (reader.hasNext()) result.add(read(reader, depth + 1))
        reader.endArray()
        result
      }
      JsonToken.STRING -> JsonPrimitive(reader.nextString())
      JsonToken.NUMBER -> {
        val value = reader.nextString()
        if (value.length > 128) invalid("$label has an oversized number")
        JsonPrimitive(BigDecimal(value))
      }
      JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
      JsonToken.NULL -> {
        reader.nextNull()
        JsonNull.INSTANCE
      }
      else -> invalid("Invalid JSON in $label")
    }
  }
  try {
    JsonReader(StringReader(text)).use { reader ->
      reader.strictness = Strictness.STRICT
      val result = read(reader, 0)
      if (!result.isJsonObject || reader.peek() != JsonToken.END_DOCUMENT) {
        invalid("$label must contain one JSON object")
      }
      return result.asJsonObject
    }
  } catch (exception: ExtensionPackageException) {
    throw exception
  } catch (exception: Exception) {
    throw ExtensionPackageException(
        PackageError.INVALID_MANIFEST, "Invalid JSON in $label", exception)
  }
}

private fun invalid(message: String): Nothing =
    throw ExtensionPackageException(PackageError.INVALID_MANIFEST, message)

internal class ManifestParser(
    private val resourcePaths: Set<String>,
    private val readText: (String) -> String,
) {
  private var globWorkRemaining = 2_000_000

  fun parse(rawJson: String): ExtensionManifest {
    val root = parseJsonObject(rawJson, "manifest.json")
    val manifestVersionElement = root.get("manifest_version")
    if (manifestVersionElement == null ||
        !manifestVersionElement.isJsonPrimitive ||
        !manifestVersionElement.asJsonPrimitive.isNumber ||
        manifestVersionElement.asString !in setOf("2", "3"))
        invalid("manifest_version must be 2 or 3")
    val manifestVersion = manifestVersionElement.asInt
    val defaultLocale = string(root, "default_locale")
    if (defaultLocale != null &&
        (defaultLocale.length > 32 ||
            !Regex("[A-Za-z0-9]+(?:[_-][A-Za-z0-9]+)*").matches(defaultLocale))) {
      invalid("Invalid default_locale")
    }
    val messages =
        defaultLocale?.let { locale ->
          val path = resource("_locales/$locale/messages.json")
          parseJsonObject(readText(path), path)
        }
    val messageIndex = mutableMapOf<String, JsonElement>()
    messages?.entrySet()?.forEach { (key, value) ->
      if (messageIndex.put(key.lowercase(Locale.ROOT), value) != null) {
        invalid("Duplicate case-insensitive locale message: $key")
      }
    }
    fun localized(value: String, maximumLength: Int, label: String): String {
      if (value.length > 4096) invalid("$label contains too much localization input")
      val output = StringBuilder()
      fun append(part: String) {
        if (part.length > maximumLength - output.length)
            invalid("$label is too long after localization")
        output.append(part)
      }
      var previousEnd = 0
      Regex("__MSG_([A-Za-z0-9_@]+)__").findAll(value).forEach { match ->
        append(value.substring(previousEnd, match.range.first))
        val messageKey = match.groupValues[1]
        val message = messageIndex[messageKey.lowercase(Locale.ROOT)]
        if (message == null || !message.isJsonObject)
            invalid("Cannot resolve localized message: $messageKey")
        append(string(message.asJsonObject, "message", required = true)!!)
        previousEnd = match.range.last + 1
      }
      append(value.substring(previousEnd))
      return output.toString()
    }
    val name = localized(string(root, "name", required = true)!!, 75, "name")
    if (name.isBlank() || name.length > 75 || name.any { it.code < 32 || it.code == 127 }) {
      invalid("name must contain 1–75 visible characters")
    }
    if (name.contains("__MSG_", ignoreCase = true)) invalid("Unresolved localized extension name")
    val version = string(root, "version", required = true)!!
    val parts = version.split('.')
    if (parts.size !in 1..4 ||
        parts.any { !Regex("0|[1-9][0-9]{0,4}").matches(it) || it.toInt() > 65535 } ||
        parts.all { it == "0" })
        invalid("version must contain 1–4 integers from 0 to 65535 and cannot be all zero")
    val description = string(root, "description")?.let { localized(it, 132, "description") }
    val permissions = strings(root, "permissions")
    val hostPermissions = strings(root, "host_permissions")
    val optionalPermissions = strings(root, "optional_permissions")
    val optionalHostPermissions = strings(root, "optional_host_permissions")
    val background =
        objectValue(root, "background")?.let { value ->
          val worker = string(value, "service_worker")?.let(::resource)
          val scripts = strings(value, "scripts").map(::resource)
          val page = string(value, "page")?.let(::resource)
          val persistent = boolean(value, "persistent")
          val type = string(value, "type")
          if (manifestVersion == 3) {
            if (worker == null || value.has("scripts") || page != null || persistent != null) {
              invalid(
                  "MV3 background must declare service_worker without scripts, page, or persistent")
            }
            if (type != null && type !in setOf("classic", "module"))
                invalid("Invalid background.type")
          } else {
            if (worker != null || type != null || (scripts.isEmpty() == (page == null))) {
              invalid("MV2 background must declare either scripts or page")
            }
          }
          BackgroundDeclaration(worker, scripts, page, persistent, type)
        }
    val contentScripts =
        array(root, "content_scripts")?.map { element ->
          if (!element.isJsonObject) invalid("content_scripts entries must be objects")
          val item = element.asJsonObject
          val matches = strings(item, "matches", required = true)
          if (matches.isEmpty()) invalid("content_scripts.matches must not be empty")
          val js = strings(item, "js").map(::resource)
          val css = strings(item, "css").map(::resource)
          if (js.isEmpty() && css.isEmpty()) invalid("content_scripts must reference JS or CSS")
          val runAt = string(item, "run_at")
          if (runAt != null && runAt !in setOf("document_start", "document_end", "document_idle")) {
            invalid("Invalid content_scripts.run_at")
          }
          val world = string(item, "world")
          if (world != null && world !in setOf("ISOLATED", "MAIN"))
              invalid("Invalid content_scripts.world")
          ContentScriptDeclaration(
              matches,
              strings(item, "exclude_matches"),
              js,
              css,
              runAt,
              boolean(item, "all_frames") ?: false,
              boolean(item, "match_about_blank") ?: false,
              world,
          )
        } ?: emptyList()
    val actionKeys = listOf("action", "browser_action", "page_action").filter(root::has)
    if (actionKeys.size > 1) invalid("Only one action declaration is allowed")
    val action =
        actionKeys.firstOrNull()?.let { key ->
          if ((manifestVersion == 3) != (key == "action"))
              invalid("$key is not valid for MV$manifestVersion")
          val item = objectValue(root, key)!!
          validateIcons(item, "default_icon")
          ActionDeclaration(
              key,
              string(item, "default_popup")?.takeIf { it.isNotEmpty() }?.let(::resource),
              string(item, "default_title")?.let { localized(it, 1024, "default_title") },
          )
        }
    val optionsPage = string(root, "options_page")?.let(::resource)
    val optionsUi =
        objectValue(root, "options_ui")?.let {
          OptionsDeclaration(
              resource(string(it, "page", required = true)!!), boolean(it, "open_in_tab"))
        }
    validateIcons(root, "icons")
    objectValue(root, "theme")?.let { validateIcons(it, "images") }
    string(root, "devtools_page")?.let(::resource)
    objectValue(root, "storage")?.let { string(it, "managed_schema")?.let(::resource) }
    objectValue(root, "side_panel")?.let { resource(string(it, "default_path", required = true)!!) }
    objectValue(root, "chrome_url_overrides")?.entrySet()?.forEach { (_, value) ->
      resource(primitiveString(value, "chrome_url_overrides"))
    }
    objectValue(root, "sandbox")?.let { strings(it, "pages").forEach(::resource) }
    objectValue(root, "user_scripts")?.let { string(it, "api_script")?.let(::resource) }
    objectValue(root, "declarative_net_request")?.let { declaration ->
      array(declaration, "rule_resources")?.forEach {
        if (!it.isJsonObject) invalid("rule_resources entries must be objects")
        resource(string(it.asJsonObject, "path", required = true)!!)
      }
    }
    array(root, "web_accessible_resources")?.forEach { entry ->
      if (manifestVersion == 2) {
        resource(primitiveString(entry, "web_accessible_resources"), allowGlob = true)
      } else {
        if (!entry.isJsonObject) invalid("MV3 web_accessible_resources entries must be objects")
        strings(entry.asJsonObject, "resources", required = true).forEach {
          resource(it, allowGlob = true)
        }
      }
    }
    return ExtensionManifest(
        manifestVersion,
        name,
        version,
        description,
        defaultLocale,
        permissions,
        hostPermissions,
        optionalPermissions,
        optionalHostPermissions,
        background,
        contentScripts,
        action,
        optionsPage,
        optionsUi,
        rawJson,
    )
  }

  private fun resource(raw: String, allowGlob: Boolean = false): String {
    val path = packagePath(raw)
    if (path.contains('?') || path.contains('#'))
        invalid("Resource must be a package file path: $raw")
    val exists =
        if (allowGlob && path.contains('*')) {
          resourcePaths.any { matchesGlob(path, it) }
        } else resourcePaths.contains(path)
    if (!exists)
        throw ExtensionPackageException(
            PackageError.MISSING_RESOURCE, "Missing package resource: $raw")
    return path
  }

  /**
   * Only '*' is a wildcard. Bound all matching work across this manifest, including failed globs.
   */
  private fun matchesGlob(pattern: String, value: String): Boolean {
    var patternIndex = 0
    var valueIndex = 0
    var lastStar = -1
    var retryAt = 0
    while (valueIndex < value.length) {
      if (--globWorkRemaining < 0) invalid("web_accessible_resources patterns are too complex")
      if (patternIndex < pattern.length && pattern[patternIndex] == '*') {
        lastStar = patternIndex++
        retryAt = valueIndex
      } else if (patternIndex < pattern.length && pattern[patternIndex] == value[valueIndex]) {
        patternIndex++
        valueIndex++
      } else if (lastStar >= 0) {
        patternIndex = lastStar + 1
        valueIndex = ++retryAt
      } else return false
    }
    while (patternIndex < pattern.length && pattern[patternIndex] == '*') patternIndex++
    return patternIndex == pattern.length
  }

  private fun validateIcons(root: JsonObject, key: String) {
    val value = root.get(key) ?: return
    if (value.isJsonPrimitive && value.asJsonPrimitive.isString) {
      resource(value.asString)
    } else if (value.isJsonObject) {
      value.asJsonObject.entrySet().forEach { resource(primitiveString(it.value, key)) }
    } else invalid("$key must be a path or an object of paths")
  }

  private fun primitiveString(value: JsonElement, label: String): String {
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString)
        invalid("$label must contain strings")
    return value.asString
  }

  private fun string(root: JsonObject, key: String, required: Boolean = false): String? {
    val value = root.get(key)
    if (value == null) {
      if (required) invalid("Missing $key")
      return null
    }
    return primitiveString(value, key)
  }

  private fun boolean(root: JsonObject, key: String): Boolean? {
    val value = root.get(key) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isBoolean)
        invalid("$key must be a boolean")
    return value.asBoolean
  }

  private fun objectValue(root: JsonObject, key: String): JsonObject? {
    val value = root.get(key) ?: return null
    if (!value.isJsonObject) invalid("$key must be an object")
    return value.asJsonObject
  }

  private fun array(root: JsonObject, key: String): JsonArray? {
    val value = root.get(key) ?: return null
    if (!value.isJsonArray) invalid("$key must be an array")
    return value.asJsonArray
  }

  private fun strings(root: JsonObject, key: String, required: Boolean = false): List<String> {
    if (required && !root.has(key)) invalid("Missing $key")
    return array(root, key)?.map {
      primitiveString(it, key).also { value ->
        if (value.isBlank()) invalid("$key contains an empty value")
      }
    } ?: emptyList()
  }
}
