package org.matrix.chromext.utils

import org.junit.Assert.*
import org.junit.Test

class PropertyModelsTest {
  private val title = Key("TITLE")
  private val enabled = Key("ENABLED")
  private val id = Key("MENU_ITEM_ID")
  private val callback = Key("CLICK_HANDLER")

  @Test
  fun rawValuesAndTypedArrayConstructorKeepIndependentMenuState() {
    val adapter = PropertyModels(RawModel::class.java)
    val original = RawModel(arrayOf(title, enabled, id, callback))
    adapter.set(original, "TITLE", StringBuilder("Existing title"))
    adapter.set(original, "ENABLED", false)
    adapter.set(original, "MENU_ITEM_ID", 10)
    adapter.set(original, "CLICK_HANDLER", Any())
    val added = adapter.emptyLike(original)
    assertNull(adapter.get(added, "CLICK_HANDLER"))
    adapter.set(added, "TITLE", "Extensions")
    adapter.set(added, "ENABLED", true)
    adapter.set(added, "MENU_ITEM_ID", 20)
    assertEquals("Existing title", adapter.get(original, "TITLE").toString())
    assertEquals(10, adapter.get(original, "MENU_ITEM_ID"))
    assertEquals("Extensions", adapter.get(added, "TITLE"))
    assertEquals(true, adapter.get(added, "ENABLED"))
    assertEquals(20, adapter.get(added, "MENU_ITEM_ID"))
    assertNull(adapter.get(added, "CLICK_HANDLER"))
  }

  @Test
  fun olderHolderStorageIsReadThroughTheModelAccessor() {
    val adapter = PropertyModels(HolderModel::class.java)
    val original = HolderModel(listOf(title, callback))
    adapter.set(original, "TITLE", "Original")
    adapter.set(original, "CLICK_HANDLER", Any())
    val added = adapter.emptyLike(original)
    adapter.set(added, "TITLE", "Extensions")
    assertEquals("Extensions", adapter.get(added, "TITLE"))
    assertEquals("Original", adapter.get(original, "TITLE"))
    assertNull(adapter.get(added, "CLICK_HANDLER"))
    assertNull(adapter.get(added, "UNKNOWN"))
    assertThrows(NoSuchFieldException::class.java) { adapter.set(added, "UNKNOWN", 1) }
  }

  class Key(private val name: String) {
    override fun toString() = name
  }

  class RawModel(keys: Array<Key>) {
    private val values: Map<Key, Any?> = keys.associateWith { UNSET }.toMutableMap()

    fun h(key: Key): Any? = values[key].takeUnless { it === UNSET }

    fun i(key: Key): Boolean = h(key) as? Boolean ?: false

    fun p(key: Key, value: Int) {
      (values as MutableMap)[key] = value
    }

    fun o(key: Key, value: Boolean) {
      (values as MutableMap)[key] = value
    }

    fun s(key: Key, value: Any) {
      (values as MutableMap)[key] = value
    }

    companion object {
      private val UNSET = Any()
    }
  }

  class HolderModel(keys: List<Key>) {
    private val values: Map<Key, Holder?> = keys.associateWith { null }.toMutableMap()

    fun get(key: Key): Any? = values[key]?.value

    fun set(key: Key, value: Any) {
      (values as MutableMap)[key] = Holder(value)
    }

    class Holder(val value: Any)
  }
}
