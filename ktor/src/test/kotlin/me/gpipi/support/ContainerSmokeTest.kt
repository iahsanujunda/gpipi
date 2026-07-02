package me.gpipi.support

import kotlin.test.Test
import kotlin.test.assertTrue

class ContainerSmokeTest {
    @Test fun `postgres container boots`() {
        assertTrue { TestPostgres.container.isRunning }
    }
}