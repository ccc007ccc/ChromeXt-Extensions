package org.matrix.chromext.utils

import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Modifier

/**
 * Uses PropertyModel's own accessors: recent Chromium stores values directly, older builds box
 * them.
 */
class PropertyModels(private val modelClass: Class<*>) {
  private val data = findField(modelClass) { Map::class.java.isAssignableFrom(type) }
  private val methods =
      modelClass.declaredMethods
          .filter { !Modifier.isStatic(it.modifiers) }
          .onEach { it.isAccessible = true }

  fun keys(model: Any): List<Any> {
    @Suppress("UNCHECKED_CAST")
    return (data.get(model) as Map<Any, Any?>).keys.toList()
  }

  fun get(model: Any, name: String): Any? {
    val key = keys(model).firstOrNull { it.toString() == name } ?: return null
    val getters =
        methods.filter {
          it.parameterCount == 1 &&
              it.parameterTypes[0].isInstance(key) &&
              it.returnType != Void.TYPE
        }
    // Chrome 154 exposes a common object accessor for every key, including primitive values.
    val getter =
        getters.firstOrNull { it.returnType == Any::class.java }
            ?: getters.filter { it.returnType.isPrimitive }.singleOrNull()
            ?: throw NoSuchMethodException("Cannot read PropertyModel key $name")
    return getter.invoke(model, key)
  }

  fun set(model: Any, name: String, value: Any) {
    val key =
        keys(model).firstOrNull { it.toString() == name }
            ?: throw NoSuchFieldException("PropertyModel key $name")
    val valueType =
        when (value) {
          is Int -> Int::class.java
          is Boolean -> Boolean::class.java
          is Long -> Long::class.java
          is Float -> Float::class.java
          else -> Any::class.java
        }
    val setter =
        methods.singleOrNull {
          it.parameterCount == 2 &&
              it.returnType == Void.TYPE &&
              it.parameterTypes[0].isInstance(key) &&
              it.parameterTypes[1] == valueType
        } ?: throw NoSuchMethodException("Cannot write PropertyModel key $name")
    setter.invoke(model, key, value)
  }

  /** Construct fresh values and observers; never copy a menu item's callbacks or value holders. */
  fun emptyLike(template: Any): Any {
    val keys = keys(template)
    val constructor =
        modelClass.declaredConstructors.firstOrNull {
          it.parameterCount == 1 &&
              it.parameterTypes[0].isArray &&
              keys.all(it.parameterTypes[0].componentType::isInstance)
        }
            ?: modelClass.declaredConstructors.firstOrNull {
              it.parameterCount == 1 &&
                  Collection::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                  it.parameterTypes[0].isAssignableFrom(ArrayList::class.java)
            }
            ?: throw NoSuchMethodException("No PropertyModel key-array or collection constructor")
    constructor.isAccessible = true
    val argument =
        if (constructor.parameterTypes[0].isArray) {
          ReflectArray.newInstance(
                  requireNotNull(constructor.parameterTypes[0].componentType), keys.size)
              .also { array ->
                keys.forEachIndexed { index, key -> ReflectArray.set(array, index, key) }
              }
        } else ArrayList(keys)
    return constructor.newInstance(argument)
  }
}
