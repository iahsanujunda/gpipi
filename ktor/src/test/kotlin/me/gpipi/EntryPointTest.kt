package me.gpipi

import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class EntryPointTest {

    /**
     * Regression guard: `mainClass` pointed at io.ktor.server.netty.EngineMain once, which
     * silently bypassed our main() — so dotenv never ran and no secret ever loaded. `gradlew run`
     * must launch me.gpipi.MainKt (loads .env, then delegates to EngineMain).
     */
    @Test
    fun `gradle run launches the dotenv-loading entry point`() {
        val build = File("build.gradle.kts").readText()
        assertContains(build, """mainClass = "me.gpipi.MainKt"""")

        // ...and that entry point actually exists as a launchable main.
        val main = Class.forName("me.gpipi.MainKt").getMethod("main", Array<String>::class.java)
        assertTrue(Modifier.isStatic(main.modifiers), "MainKt.main must be a static entry point")
    }
}
