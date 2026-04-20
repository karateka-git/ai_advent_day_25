package ru.compadre.indexer.chat.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskStateTest {
    @Test
    fun `default task state is empty`() {
        val taskState = TaskState()

        assertNull(taskState.goal)
        assertEquals(emptyList(), taskState.constraints)
        assertEquals(emptyList(), taskState.fixedTerms)
        assertEquals(emptyList(), taskState.knownFacts)
        assertEquals(emptyList(), taskState.openQuestions)
        assertNull(taskState.lastUserIntent)
    }
}
