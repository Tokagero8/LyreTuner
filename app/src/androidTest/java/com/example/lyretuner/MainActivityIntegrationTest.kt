package com.example.lyretuner

import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.lyretuner.ui.tuner.TunerTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityIntegrationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun activityLaunchesWithReadyTunerAndAllStringEndpoints() {
        composeRule.onNodeWithTag(TunerTestTags.SCREEN).assertExists()
        composeRule.onNodeWithTag(TunerTestTags.STATUS).assertTextEquals("READY WHEN YOU ARE")
        composeRule.onNodeWithTag(TunerTestTags.LISTENING_BUTTON).assertExists()
        composeRule.onNodeWithTag(TunerTestTags.AUTO_CHIP).assertIsSelected()
        composeRule.onNodeWithTag(TunerTestTags.string(50)).assertExists()

        composeRule.onNodeWithTag(TunerTestTags.STRING_GRID).performScrollToIndex(23)
        composeRule.onNodeWithTag(TunerTestTags.string(89)).assertExists()
    }

    @Test
    fun activityWiresManualAndAutomaticStringSelection() {
        composeRule.onNodeWithTag(TunerTestTags.string(50)).performClick()

        composeRule.onNodeWithTag(TunerTestTags.string(50)).assertIsSelected()
        composeRule.onNodeWithTag(TunerTestTags.AUTO_CHIP).assertIsNotSelected()
        composeRule.onNodeWithTag(TunerTestTags.TARGET_NOTE).assertTextEquals("D")
        composeRule.onNodeWithTag(TunerTestTags.TARGET_OCTAVE).assertTextEquals("3")
        composeRule.onNodeWithTag(TunerTestTags.FREQUENCY).assertTextContains("Target 146.8 Hz")

        composeRule.onNodeWithTag(TunerTestTags.AUTO_CHIP).performClick()
        composeRule.onNodeWithTag(TunerTestTags.AUTO_CHIP).assertIsSelected()
        composeRule.onNodeWithTag(TunerTestTags.string(50)).assertIsNotSelected()
    }
}
