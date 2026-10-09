package org.matrix.chromext.utils

import android.net.Uri
import android.provider.OpenableColumns
import org.matrix.chromext.Chrome

const val ERUD_URL = "https://cdn.jsdelivr.net/npm/eruda"
private const val DEV_FRONT_END = "https://chrome-devtools-frontend.appspot.com"

fun randomString(length: Int): String {
  val alphabet: List<Char> = ('a'..'z') + ('A'..'Z')
  return List(length) { alphabet.random() }.joinToString("")
}

fun isDevToolsFrontEnd(url: String?): Boolean {
  if (url == null) return false
  val parsed = Uri.parse(url)
  return parsed.scheme == "https" &&
      parsed.host == Uri.parse(DEV_FRONT_END).host &&
      parsed.port in listOf(-1, 443) &&
      parsed.userInfo == null
}

fun resolveContentUrl(url: String): String {
  if (!url.startsWith("content://")) return ""
  Chrome.getContext().contentResolver.query(Uri.parse(url), null, null, null, null)?.use { cursor ->
    cursor.moveToFirst()
    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    val dataIndex = cursor.getColumnIndex("_data")
    if (dataIndex != -1) {
      return cursor.getString(dataIndex) ?: cursor.getString(nameIndex)
    } else {
      return cursor.getString(nameIndex)
    }
  }
  return ""
}

fun parseOrigin(url: String): String? {
  val protocol = url.split("://")
  if (protocol.size > 1 && arrayOf("https", "http", "file").contains(protocol.first())) {
    return protocol.first() + "://" + protocol[1].split("/").first()
  } else {
    return null
  }
}

fun isChromeScheme(url: String): Boolean {
  val protocol = url.split("://")
  return (protocol.size > 1 &&
      arrayOf("chrome", "chrome-native", "edge").contains(protocol.first()))
}
