package org.matrix.chromext.utils

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedInterface.HookHandle
import io.github.libxposed.api.XposedInterface.Hooker
import java.lang.reflect.Executable

/** Available only inside an injected process, after XposedModule.onModuleLoaded. */
object Hooks {
  lateinit var framework: XposedInterface
    private set

  fun attach(framework: XposedInterface) {
    this.framework = framework
  }
}

/** Use the native chain for replacement results or calls with changed arguments. */
fun Executable.intercept(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    hooker: Hooker
): HookHandle =
    Hooks.framework
        .hook(this)
        .setPriority(priority)
        .setExceptionMode(ExceptionMode.PROTECTIVE)
        .intercept(hooker)

fun Executable.hookBefore(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    hook: (Chain) -> Unit
): HookHandle =
    intercept(priority) { chain ->
      hook(chain)
      chain.proceed()
    }

/** Run only after a successful call; the original result or exception is preserved. */
fun Executable.hookAfter(
    priority: Int = XposedInterface.PRIORITY_DEFAULT,
    hook: (Chain, Any?) -> Unit
): HookHandle =
    intercept(priority) { chain ->
      val result = chain.proceed()
      hook(chain, result)
      result
    }
