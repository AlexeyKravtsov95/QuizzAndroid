package ru.poporyadku.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import ru.poporyadku.ui.theme.PoPoRyadkuTheme
import ru.poporyadku.ui.theme.Sizing

/**
 * `I6-C10` — `NotificationOptInDialog` (COMPONENTS.md; ITERATION_6_DESIGN.md, §8.3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w390dp-h844dp")
class NotificationOptInDialogTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** `I6-C10`. Вопрос — один текстовый узел с `heading()`, кнопки в порядке «Да» → «Не нужно». */
    @Test
    fun `I6-C10 the question is a single heading followed by both actions`() {
        rule.setContent { Dialog() }

        rule.onNode(hasText(QUESTION) and isHeading()).assertIsDisplayed()
        rule.onNodeWithTag(NotificationOptInTestTags.ACCEPT).assertIsDisplayed()
        rule.onNodeWithTag(NotificationOptInTestTags.DECLINE).assertIsDisplayed()

        val questionY = rule.onNodeWithTag(NotificationOptInTestTags.QUESTION)
            .fetchSemanticsNode().positionInRoot.y
        val acceptY = rule.onNodeWithTag(NotificationOptInTestTags.ACCEPT)
            .fetchSemanticsNode().positionInRoot.y
        val declineY = rule.onNodeWithTag(NotificationOptInTestTags.DECLINE)
            .fetchSemanticsNode().positionInRoot.y
        assertTrue("вопрос первым", questionY < acceptY)
        assertTrue("«Да, в 9:00» перед «Не нужно»", acceptY < declineY)
    }

    /** `I6-C10`. Тексты — дословно из `UX_FLOW.md` §2. */
    @Test
    fun `I6-C10 the texts are exactly the approved wording`() {
        rule.setContent { Dialog() }

        rule.onNodeWithText(QUESTION).assertIsDisplayed()
        rule.onNodeWithText("Да, в 9:00").assertIsDisplayed()
        rule.onNodeWithText("Не нужно").assertIsDisplayed()
    }

    /** `I6-C10`. Нажатия дают ровно по одному ответу каждое. */
    @Test
    fun `I6-C10 each action reports itself exactly once`() {
        val answers = mutableListOf<String>()
        rule.setContent {
            Dialog(onAccept = { answers += "accept" }, onDecline = { answers += "decline" })
        }

        rule.onNodeWithTag(NotificationOptInTestTags.ACCEPT).performClick()
        assertEquals(listOf("accept"), answers)

        rule.onNodeWithTag(NotificationOptInTestTags.DECLINE).performClick()
        assertEquals(listOf("accept", "decline"), answers)
    }

    /**
     * `I6-C10`. Системная «назад» равносильна «Не нужно»: отметка ставится, напоминание не
     * включается, системного запроса нет.
     */
    @Test
    fun `I6-C10 the system back acts as decline`() {
        val answers = mutableListOf<String>()
        rule.setContent {
            Dialog(onAccept = { answers += "accept" }, onDecline = { answers += "decline" })
        }

        // Диалог Compose живёт в собственном окне со своим диспетчером «назад»:
        // нажатие на диспетчер Activity до него не доходит.
        val dialog = ShadowDialog.getLatestDialog() as ComponentDialog
        rule.runOnUiThread { dialog.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()

        assertEquals(listOf("decline"), answers)
    }

    /**
     * `I6-C10`. При 200 % и 320 dp обе кнопки видимы и сохраняют цель: складываются
     * вертикально, а не сжимаются.
     */
    @Test
    @Config(qualifiers = "w320dp-h844dp")
    fun `I6-C10 at 200 percent both actions keep their touch targets`() {
        rule.setContent { Dialog(fontScale = FONT_SCALE_200) }

        rule.onNodeWithTag(NotificationOptInTestTags.ACCEPT)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(Sizing.touchTargetMin)
        rule.onNodeWithTag(NotificationOptInTestTags.DECLINE)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(Sizing.touchTargetMin)
        rule.onNodeWithText(QUESTION).assertIsDisplayed()
    }

    /** `I6-C10`. Тёмная тема: диалог рендерится ролями `ColorScheme`, узлы на месте. */
    @Test
    fun `I6-C10 the dialog renders in the dark theme`() {
        rule.setContent { Dialog(darkTheme = true) }

        rule.onNodeWithText(QUESTION).assertIsDisplayed()
        rule.onNodeWithTag(NotificationOptInTestTags.ACCEPT).assertIsDisplayed()
    }

    @Composable
    private fun Dialog(
        onAccept: () -> Unit = {},
        onDecline: () -> Unit = {},
        darkTheme: Boolean = false,
        fontScale: Float = 1f,
    ) {
        val density = androidx.compose.ui.unit.Density(
            density = androidx.compose.ui.platform.LocalDensity.current.density,
            fontScale = fontScale,
        )
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.ui.platform.LocalDensity provides density,
        ) {
            PoPoRyadkuTheme(darkTheme = darkTheme) {
                NotificationOptInDialog(onAccept = onAccept, onDecline = onDecline)
            }
        }
    }

    private companion object {
        const val QUESTION = "Напоминать о новом задании?"
        const val FONT_SCALE_200 = 2f
    }
}
