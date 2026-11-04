package com.phototok.di

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CoroutineModuleTest {

    @Test
    fun `the application scope runs on the injected dispatcher, not a literal one`() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher: CoroutineDispatcher = StandardTestDispatcher(scheduler)
        val scope = CoroutineModule.provideApplicationScope(dispatcher)
        var ran = 0

        scope.launch { ran++ }
        assertEquals("nothing runs until the injected dispatcher is advanced", 0, ran)
        scheduler.advanceUntilIdle()

        assertEquals(1, ran)
    }
}
