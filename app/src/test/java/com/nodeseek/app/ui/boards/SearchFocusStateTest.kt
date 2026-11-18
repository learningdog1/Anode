package com.nodeseek.app.ui.boards

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFocusStateTest {

    @Test
    fun `initial search entry requests auto focus when no query has been submitted`() {
        assertTrue(
            shouldRequestSearchAutoFocus(
                autoFocusSearch = true,
                hasConsumedInitialAutoFocus = false,
                submittedQuery = "",
                focusSearchTrigger = 0,
                lastHandledFocusTrigger = 0,
            )
        )
    }

    @Test
    fun `returning from post detail after consuming auto focus does not request focus again`() {
        assertFalse(
            shouldRequestSearchAutoFocus(
                autoFocusSearch = false,
                hasConsumedInitialAutoFocus = true,
                submittedQuery = "claw",
                focusSearchTrigger = 0,
                lastHandledFocusTrigger = 0,
            )
        )
    }

    @Test
    fun `returning from post detail does not re-focus even if caller keeps autoFocusSearch true`() {
        assertFalse(
            shouldRequestSearchAutoFocus(
                autoFocusSearch = true,
                hasConsumedInitialAutoFocus = true,
                submittedQuery = "",
                focusSearchTrigger = 0,
                lastHandledFocusTrigger = 0,
            )
        )
        assertFalse(
            shouldRequestSearchAutoFocus(
                autoFocusSearch = true,
                hasConsumedInitialAutoFocus = false,
                submittedQuery = "claw",
                focusSearchTrigger = 0,
                lastHandledFocusTrigger = 0,
            )
        )
    }

    @Test
    fun `explicit focus trigger only fires once per trigger increment`() {
        assertTrue(
            shouldRequestSearchAutoFocus(
                autoFocusSearch = false,
                hasConsumedInitialAutoFocus = true,
                submittedQuery = "claw",
                focusSearchTrigger = 1,
                lastHandledFocusTrigger = 0,
            )
        )
        assertFalse(
            shouldRequestSearchAutoFocus(
                autoFocusSearch = false,
                hasConsumedInitialAutoFocus = true,
                submittedQuery = "claw",
                focusSearchTrigger = 1,
                lastHandledFocusTrigger = 1,
            )
        )
    }
}
