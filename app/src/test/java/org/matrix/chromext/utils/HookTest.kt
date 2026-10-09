package org.matrix.chromext.utils

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookBuilder
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable
import java.lang.reflect.Proxy
import java.util.Collections
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Checks our callbacks against the published API. ART interception is a separate device check. */
class HookTest {
  private val method = String::class.java.getMethod("substring", Int::class.javaPrimitiveType)
  private lateinit var registration: Registration

  @Before
  fun attachFramework() {
    val framework =
        Proxy.newProxyInstance(
            XposedInterface::class.java.classLoader, arrayOf(XposedInterface::class.java)) {
                _,
                call,
                args ->
              check(call.name == "hook") { "Unexpected framework call: ${call.name}" }
              Registration(args!![0] as Executable).also { registration = it }
            } as XposedInterface
    Hooks.attach(framework)
  }

  @Test
  fun beforeRunsOnceWithTheOriginalCallContext() {
    val events = mutableListOf<String>()
    val chain =
        TestChain(method, "browser", listOf(1)) { receiver, args ->
          events += "original"
          assertEquals("browser", receiver)
          assertEquals(listOf(1), args)
          "rowser"
        }
    val handle =
        method.hookBefore(priority = 75) {
          assertSame(method, it.executable)
          assertEquals("browser", it.thisObject)
          assertEquals(1, it.getArg(0))
          events += "before"
        }
    assertEquals("rowser", registration.hooker.intercept(chain))
    assertEquals(listOf("before", "original"), events)
    assertEquals(1, chain.calls)
    assertEquals(75, registration.priority)
    assertEquals(ExceptionMode.PROTECTIVE, registration.mode)
    assertSame(registration, handle)
    handle.unhook()
    assertTrue(registration.removed)
  }

  @Test
  fun afterObservesAndPreservesTheOriginalResult() {
    val events = mutableListOf<String>()
    val result = Any()
    val chain =
        TestChain(method) { _, _ ->
          events += "original"
          result
        }
    method.hookAfter { call, returned ->
      assertSame(chain, call)
      assertSame(result, returned)
      events += "after"
    }
    assertSame(result, registration.hooker.intercept(chain))
    assertEquals(listOf("original", "after"), events)
    assertEquals(1, chain.calls)
  }

  @Test
  fun staticCallsAndNullResultsAreSupported() {
    val target = System::class.java.getMethod("gc")
    val chain = TestChain(target) { _, _ -> null }
    var observed = false
    target.hookAfter { call, result ->
      assertNull(call.thisObject)
      assertNull(result)
      observed = true
    }
    assertNull(registration.hooker.intercept(chain))
    assertTrue(observed)
    assertEquals(1, chain.calls)
  }

  @Test
  fun constructorObserverRunsAfterInitialization() {
    val constructor = StringBuilder::class.java.getConstructor()
    val instance = StringBuilder()
    val chain =
        TestChain(constructor, instance) { _, _ ->
          instance.append("ready")
          null
        }
    constructor.hookAfter { call, result ->
      assertSame(instance, call.thisObject)
      assertEquals("ready", call.thisObject.toString())
      assertNull(result)
    }
    assertNull(registration.hooker.intercept(chain))
    assertEquals(1, chain.calls)
  }

  @Test
  fun beforeDoesNotSwallowAnOriginalException() {
    val error = IllegalStateException("browser failure")
    val chain = TestChain(method) { _, _ -> throw error }
    var observed = false
    method.hookBefore { observed = true }
    assertSame(
        error,
        assertThrows(IllegalStateException::class.java) { registration.hooker.intercept(chain) })
    assertTrue(observed)
    assertEquals(1, chain.calls)
  }

  @Test
  fun afterDoesNotTurnAFailedCallIntoASuccess() {
    val error = IllegalStateException("browser failure")
    val chain = TestChain(method) { _, _ -> throw error }
    var observed = false
    method.hookAfter { _, _ -> observed = true }
    assertSame(
        error,
        assertThrows(IllegalStateException::class.java) { registration.hooker.intercept(chain) })
    assertFalse(observed)
    assertEquals(1, chain.calls)
  }

  @Test
  fun hookFailuresAreLeftToFrameworkProtectionWithoutRepeatingTheOriginal() {
    val error = IllegalArgumentException("hook failure")
    for (after in listOf(false, true)) {
      val chain = TestChain(method) { _, _ -> "result" }
      if (after) method.hookAfter { _, _ -> throw error } else method.hookBefore { throw error }
      assertSame(
          error,
          assertThrows(IllegalArgumentException::class.java) {
            registration.hooker.intercept(chain)
          })
      assertEquals(if (after) 1 else 0, chain.calls)
      assertEquals(ExceptionMode.PROTECTIVE, registration.mode)
    }
  }

  @Test
  fun interceptionCanConsumeAnActionWithoutCallingTheBrowser() {
    val chain = TestChain(method) { _, _ -> fail("Consumed action must not proceed") }
    method.intercept { true }
    assertEquals(true, registration.hooker.intercept(chain))
    assertEquals(0, chain.calls)
  }

  @Test
  fun replacementArgumentsDoNotMutateTheImmutableIncomingArguments() {
    val chain =
        TestChain(method, "browser", listOf(1)) { receiver, args ->
          (receiver as String).substring(args[0] as Int)
        }
    method.intercept {
      val args = it.args.toTypedArray()
      args[0] = 3
      it.proceed(args)
    }
    assertEquals("wser", registration.hooker.intercept(chain))
    assertEquals(listOf(1), chain.args)
    assertEquals(1, chain.calls)
  }

  private class Registration(private val target: Executable) : HookBuilder, HookHandle {
    lateinit var hooker: Hooker
    var priority = XposedInterface.PRIORITY_DEFAULT
    var mode = ExceptionMode.DEFAULT
    var removed = false
    private var hookId: String? = null

    override fun setPriority(priority: Int) = apply { this.priority = priority }

    override fun setExceptionMode(mode: ExceptionMode) = apply { this.mode = mode }

    override fun setId(id: String?) = apply { hookId = id }

    override fun intercept(hooker: Hooker) = apply { this.hooker = hooker }

    override fun getExecutable() = target

    override fun getId() = hookId

    override fun replaceHook(hooker: Hooker) = apply { this.hooker = hooker }

    override fun unhook() {
      removed = true
    }
  }

  private class TestChain(
      private val target: Executable,
      private val receiver: Any? = null,
      args: List<Any?> = emptyList(),
      private val original: (Any?, List<Any?>) -> Any?
  ) : Chain {
    private val incoming = Collections.unmodifiableList(args.toList())
    var calls = 0
      private set

    override fun getExecutable() = target

    override fun getThisObject() = receiver

    override fun getArgs(): List<Any?> = incoming

    override fun getArg(index: Int) = incoming[index]

    override fun proceed() = invoke(receiver, incoming)

    override fun proceed(args: Array<out Any?>) = invoke(receiver, args.toList())

    override fun proceedWith(thisObject: Any) = invoke(thisObject, incoming)

    override fun proceedWith(thisObject: Any, args: Array<out Any?>) =
        invoke(thisObject, args.toList())

    private fun invoke(receiver: Any?, args: List<Any?>): Any? {
      calls++
      return original(receiver, args)
    }
  }
}
