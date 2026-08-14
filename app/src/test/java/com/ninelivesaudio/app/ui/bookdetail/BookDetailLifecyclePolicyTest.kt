package com.ninelivesaudio.app.ui.bookdetail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookDetailLifecyclePolicyTest {

    @Test
    fun `initial resume keeps the ViewModel init load as sole owner`() {
        val tracker = BookDetailResumeTracker()

        assertFalse(tracker.shouldRefresh())
    }

    @Test
    fun `later resume refreshes external folder access`() {
        val tracker = BookDetailResumeTracker()
        tracker.shouldRefresh()

        assertTrue(tracker.shouldRefresh())
    }

    @Test
    fun `process recreation gives the new ViewModel sole ownership of its initial load`() {
        val retainedViewModelTracker = BookDetailResumeTracker()
        retainedViewModelTracker.shouldRefresh()
        assertTrue(retainedViewModelTracker.shouldRefresh())

        val recreatedViewModelTracker = BookDetailResumeTracker()
        assertFalse(recreatedViewModelTracker.shouldRefresh())
    }
}
