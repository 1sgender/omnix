package com.omnix.assistant.presentation.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт слоёв ядра (дизайн-матрица 2026-09-23): дуга-прогресс и
 * амплитудные штрихи делят геометрию кольца, поэтому их множества
 * состояний НЕ ПЕРЕСЕКАЮТСЯ по построению — конфликт разрешается
 * состоянием, а не компромиссом на экране.
 */
class CoreLayersTest {

    @Test
    fun `arc is allowed only in IDLE and EXECUTING with measurable progress`() {
        for (state in CoreState.entries) {
            val expected = state == CoreState.IDLE || state == CoreState.EXECUTING
            assertEquals("state=$state", expected, CoreLayers.showArc(state, 0.5f))
        }
    }

    @Test
    fun `null progress never shows the arc`() {
        for (state in CoreState.entries) {
            assertFalse("state=$state", CoreLayers.showArc(state, null))
        }
    }

    @Test
    fun `thinking never becomes a fake progress arc`() {
        // У LLM нет честного процента — пульс вместо фальшивой дуги.
        assertFalse(CoreLayers.showArc(CoreState.THINKING, 0.7f))
    }

    @Test
    fun `fraction is clamped to 0..1 and null is zero`() {
        assertEquals(0f, CoreLayers.arcFraction(null))
        assertEquals(0f, CoreLayers.arcFraction(-0.3f))
        assertEquals(0.5f, CoreLayers.arcFraction(0.5f))
        assertEquals(1f, CoreLayers.arcFraction(1.7f))
    }
}
