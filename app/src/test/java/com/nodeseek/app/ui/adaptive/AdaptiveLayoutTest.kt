package com.nodeseek.app.ui.adaptive

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowHeightSizeClass
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
class AdaptiveLayoutTest {

    private val permissiveSaverScope = SaverScope { true }

    @Test
    fun `phone portrait 412x915 resolves compact single pane`() {
        val info = calculatePulseAdaptiveLayoutInfo(
            windowWidthDp = 412.dp,
            windowHeightDp = 915.dp,
        )

        assertEquals(WindowWidthSizeClass.Compact, info.widthSizeClass)
        assertEquals(WindowHeightSizeClass.Expanded, info.heightSizeClass)
        assertFalse(info.isDualPane)
        assertFalse(info.useNavigationRail)
        assertFalse(info.isExpandedWidth)
        assertEquals(1f, info.listPaneWeight, 0.0001f)
        assertEquals(1f, info.detailPaneWeight, 0.0001f)
    }

    @Test
    fun `foldable cover screen and 599dp boundary stay single pane`() {
        val coverInfo = calculatePulseAdaptiveLayoutInfo(360.dp, 800.dp)
        val boundaryInfo = calculatePulseAdaptiveLayoutInfo(599.dp, 900.dp)

        assertEquals(WindowWidthSizeClass.Compact, coverInfo.widthSizeClass)
        assertFalse(coverInfo.isDualPane)
        assertFalse(coverInfo.useNavigationRail)

        assertEquals(WindowWidthSizeClass.Compact, boundaryInfo.widthSizeClass)
        assertFalse(boundaryInfo.isDualPane)
        assertFalse(boundaryInfo.useNavigationRail)
    }

    @Test
    fun `unfolded book-style foldables 600dp to 839dp resolve medium dual pane with 42-58 ratio`() {
        val widths = listOf(600.dp, 673.dp, 720.dp, 839.dp)
        widths.forEach { width ->
            val info = calculatePulseAdaptiveLayoutInfo(
                windowWidthDp = width,
                windowHeightDp = 841.dp,
            )

            assertEquals("Expected Medium for $width", WindowWidthSizeClass.Medium, info.widthSizeClass)
            assertTrue("Expected dual pane for $width", info.isDualPane)
            assertTrue("Expected navigation rail for $width", info.useNavigationRail)
            assertFalse("Expected non-expanded width for $width", info.isExpandedWidth)
            assertEquals(0.42f, info.listPaneWeight, 0.0001f)
            assertEquals(0.58f, info.detailPaneWeight, 0.0001f)
            assertEquals(1f, info.listPaneWeight + info.detailPaneWeight, 0.0001f)
        }
    }

    @Test
    fun `tablets and wide landscape 840dp and above resolve expanded dual pane with 38-62 ratio`() {
        val widths = listOf(840.dp, 960.dp, 1280.dp)
        widths.forEach { width ->
            val info = calculatePulseAdaptiveLayoutInfo(
                windowWidthDp = width,
                windowHeightDp = 800.dp,
            )

            assertEquals("Expected Expanded for $width", WindowWidthSizeClass.Expanded, info.widthSizeClass)
            assertTrue("Expected dual pane for $width", info.isDualPane)
            assertTrue("Expected navigation rail for $width", info.useNavigationRail)
            assertTrue("Expected expanded width for $width", info.isExpandedWidth)
            assertEquals(0.38f, info.listPaneWeight, 0.0001f)
            assertEquals(0.62f, info.detailPaneWeight, 0.0001f)
            assertEquals(1f, info.listPaneWeight + info.detailPaneWeight, 0.0001f)
        }
    }

    @Test
    fun `explicit windowSizeClass and 600dp guard work consistently`() {
        val explicitCompact = WindowSizeClass.calculateFromSize(DpSize(400.dp, 800.dp))
        val compactGuard = calculatePulseAdaptiveLayoutInfo(
            windowWidthDp = 640.dp,
            windowHeightDp = 800.dp,
            explicitWindowSizeClass = explicitCompact,
        )
        assertFalse(compactGuard.isDualPane)
        assertFalse(compactGuard.useNavigationRail)

        val explicitMedium = WindowSizeClass.calculateFromSize(DpSize(700.dp, 800.dp))
        val narrowWidthGuard = calculatePulseAdaptiveLayoutInfo(
            windowWidthDp = 580.dp,
            windowHeightDp = 800.dp,
            explicitWindowSizeClass = explicitMedium,
        )
        assertFalse(narrowWidthGuard.isDualPane)
        assertFalse(narrowWidthGuard.useNavigationRail)

        val explicitExpanded = WindowSizeClass.calculateFromSize(DpSize(1024.dp, 768.dp))
        val tabletInfo = calculatePulseAdaptiveLayoutInfo(
            windowWidthDp = 1024.dp,
            windowHeightDp = 768.dp,
            explicitWindowSizeClass = explicitExpanded,
        )
        assertTrue(tabletInfo.isDualPane)
        assertTrue(tabletInfo.useNavigationRail)
        assertEquals(0.38f, tabletInfo.listPaneWeight, 0.0001f)
        assertEquals(0.62f, tabletInfo.detailPaneWeight, 0.0001f)
    }

    @Test
    fun `selectedPaneTopic saver round-trips null and topic arguments`() {
        val saver = SelectedPaneTopic.NullableSaver

        val savedNull = with(saver) { permissiveSaverScope.save(null) }
        assertNull(savedNull?.let { saver.restore(it) })

        val sample = SelectedPaneTopic(
            topicId = 884210L,
            initialFloor = 18,
            titleHint = "Tokyo Tier-1 BGP Review",
        )
        val savedTopic = with(saver) { permissiveSaverScope.save(sample) }
        assertEquals(sample, savedTopic?.let { saver.restore(it) })
    }

    @Test
    fun `folding from dual-pane list host with active topic navigates to TopicDetail and clears pane`() {
        val activeTopic = SelectedPaneTopic(topicId = 9001L, initialFloor = 12, titleHint = "Test Topic")
        val listDestinations = listOf(
            DualPaneDestinationKind.EXPLORE_FEED,
            DualPaneDestinationKind.BOARDS_OR_SEARCH,
            DualPaneDestinationKind.HUB_COLLECTION,
            DualPaneDestinationKind.INBOX,
            DualPaneDestinationKind.PULSE_RADAR,
        )

        listDestinations.forEach { destination ->
            val transition = resolveAdaptivePostureTransition(
                wasDualPane = true,
                isDualPane = false,
                currentDestination = destination,
                previousDestination = null,
                selectedPaneTopic = activeTopic,
                currentTopicDetailArgs = null,
            )
            assertEquals(
                "Expected PushTopicDetailAndClearPane for $destination",
                AdaptivePostureTransition.PushTopicDetailAndClearPane(activeTopic),
                transition,
            )
        }
    }

    @Test
    fun `folding without active topic or off dual-pane list clears pane without hijacking route`() {
        val activeTopic = SelectedPaneTopic(topicId = 9001L, initialFloor = 4, titleHint = "Hidden Topic")

        assertEquals(
            AdaptivePostureTransition.ClearPaneState,
            resolveAdaptivePostureTransition(
                wasDualPane = true,
                isDualPane = false,
                currentDestination = DualPaneDestinationKind.EXPLORE_FEED,
                previousDestination = null,
                selectedPaneTopic = null,
                currentTopicDetailArgs = null,
            ),
        )
        assertEquals(
            AdaptivePostureTransition.ClearPaneState,
            resolveAdaptivePostureTransition(
                wasDualPane = true,
                isDualPane = false,
                currentDestination = DualPaneDestinationKind.EXPLORE_OTHER_TAB,
                previousDestination = null,
                selectedPaneTopic = activeTopic,
                currentTopicDetailArgs = null,
            ),
        )
        assertEquals(
            AdaptivePostureTransition.ClearPaneState,
            resolveAdaptivePostureTransition(
                wasDualPane = true,
                isDualPane = false,
                currentDestination = DualPaneDestinationKind.OTHER,
                previousDestination = null,
                selectedPaneTopic = activeTopic,
                currentTopicDetailArgs = null,
            ),
        )
    }

    @Test
    fun `unfolding from TopicDetail over list host pops route into selectedPaneTopic`() {
        val topicArgs = SelectedPaneTopic(topicId = 7788L, initialFloor = 25, titleHint = "NodeSeek Thread")
        val underlyingListDestinations = listOf(
            DualPaneDestinationKind.EXPLORE_FEED,
            DualPaneDestinationKind.BOARDS_OR_SEARCH,
            DualPaneDestinationKind.HUB_COLLECTION,
            DualPaneDestinationKind.INBOX,
            DualPaneDestinationKind.PULSE_RADAR,
        )

        underlyingListDestinations.forEach { underlying ->
            val transition = resolveAdaptivePostureTransition(
                wasDualPane = false,
                isDualPane = true,
                currentDestination = DualPaneDestinationKind.TOPIC_DETAIL,
                previousDestination = underlying,
                selectedPaneTopic = null,
                currentTopicDetailArgs = topicArgs,
            )
            assertEquals(
                "Expected PopTopicDetailToDualPane for underlying $underlying",
                AdaptivePostureTransition.PopTopicDetailToDualPane(
                    topic = topicArgs,
                    switchToExploreTab = false,
                ),
                transition,
            )
        }
    }

    @Test
    fun `unfolding from TopicDetail pushed from non-explore root tab switches back to Explore tab`() {
        val topicArgs = SelectedPaneTopic(topicId = 3344L, initialFloor = 0, titleHint = "Deep Link")

        val transition = resolveAdaptivePostureTransition(
            wasDualPane = false,
            isDualPane = true,
            currentDestination = DualPaneDestinationKind.TOPIC_DETAIL,
            previousDestination = DualPaneDestinationKind.EXPLORE_OTHER_TAB,
            selectedPaneTopic = null,
            currentTopicDetailArgs = topicArgs,
        )

        assertEquals(
            AdaptivePostureTransition.PopTopicDetailToDualPane(
                topic = topicArgs,
                switchToExploreTab = true,
            ),
            transition,
        )
    }

    @Test
    fun `unfolding from TopicDetail over non-list screen preserves full-screen TopicDetail`() {
        val topicArgs = SelectedPaneTopic(topicId = 5566L, initialFloor = 1, titleHint = "From Profile")

        val transition = resolveAdaptivePostureTransition(
            wasDualPane = false,
            isDualPane = true,
            currentDestination = DualPaneDestinationKind.TOPIC_DETAIL,
            previousDestination = DualPaneDestinationKind.OTHER,
            selectedPaneTopic = null,
            currentTopicDetailArgs = topicArgs,
        )

        assertEquals(AdaptivePostureTransition.None, transition)
    }

    @Test
    fun `unchanged dual-pane posture produces no transition`() {
        val topic = SelectedPaneTopic(topicId = 100L)
        assertEquals(
            AdaptivePostureTransition.None,
            resolveAdaptivePostureTransition(
                wasDualPane = false,
                isDualPane = false,
                currentDestination = DualPaneDestinationKind.TOPIC_DETAIL,
                previousDestination = DualPaneDestinationKind.EXPLORE_FEED,
                selectedPaneTopic = null,
                currentTopicDetailArgs = topic,
            ),
        )
        assertEquals(
            AdaptivePostureTransition.None,
            resolveAdaptivePostureTransition(
                wasDualPane = true,
                isDualPane = true,
                currentDestination = DualPaneDestinationKind.EXPLORE_FEED,
                previousDestination = null,
                selectedPaneTopic = topic,
                currentTopicDetailArgs = null,
            ),
        )
    }
}
