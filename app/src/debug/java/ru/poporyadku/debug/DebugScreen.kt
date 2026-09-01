package ru.poporyadku.debug

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.time.LocalDate
import ru.poporyadku.R
import ru.poporyadku.core.model.ThemeMode
import ru.poporyadku.data.db.entity.DailySetEntity
import ru.poporyadku.data.db.entity.DayAssignmentEntity
import ru.poporyadku.data.db.entity.DayResultEntity
import ru.poporyadku.data.db.entity.PuzzleAttemptEntity

// Ряд кнопок/полей, который скроллится по горизонтали, а не сжимает содержимое до
// вертикальной "лесенки" из одной буквы на строку на узких экранах.
@Composable
private fun ScrollableRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

// ITERATION_2_DESIGN.md, раздел 6: рабочий инструмент, не продуктовый экран.
// Дизайн-полировка не требуется — обычный Material3 без токенов проекта.
@Composable
fun DebugScreen(viewModel: DebugViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val dayAssignments by viewModel.dayAssignments.collectAsState()
    val puzzleAttempts by viewModel.puzzleAttempts.collectAsState()
    val dayResults by viewModel.dayResults.collectAsState()
    val dailySets by viewModel.dailySets.collectAsState()
    val preferences by viewModel.preferences.collectAsState()

    var dateText by rememberSaveable { mutableStateOf(uiState.selectedDate.toString()) }
    var attemptSlot by rememberSaveable { mutableIntStateOf(0) }
    var attemptScore by rememberSaveable { mutableIntStateOf(6) }
    var streakCurrentText by rememberSaveable { mutableStateOf("0") }
    var streakBestText by rememberSaveable { mutableStateOf("0") }
    var streakDateText by rememberSaveable { mutableStateOf(uiState.selectedDate.toString()) }
    var resetDialogVisible by rememberSaveable { mutableStateOf(false) }

    // ITERATION_3_DESIGN.md, I3-D48: действие необратимо и удаляет весь локальный
    // прогресс, поэтому подтверждение обязательно, а текст называет цену прямо.
    if (resetDialogVisible) {
        AlertDialog(
            onDismissRequest = { resetDialogVisible = false },
            title = { Text(stringResource(R.string.debug_reset_temporary_content_title)) },
            text = { Text(stringResource(R.string.debug_reset_temporary_content_message)) },
            confirmButton = {
                Button(onClick = {
                    resetDialogVisible = false
                    viewModel.resetTemporaryContent()
                }) { Text(stringResource(R.string.debug_reset_temporary_content_confirm)) }
            },
            dismissButton = {
                Button(onClick = { resetDialogVisible = false }) {
                    Text(stringResource(R.string.debug_reset_temporary_content_cancel))
                }
            },
        )
    }

    LazyColumn(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        item {
            Text("Debug — По порядку!", style = MaterialTheme.typography.titleLarge)
            uiState.error?.let { Text("Ошибка: $it") }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item {
            Text("Дата")
            OutlinedTextField(
                value = dateText,
                onValueChange = { dateText = it },
                label = { Text("yyyy-MM-dd") },
            )
            ScrollableRow {
                Button(onClick = {
                    val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
                    if (date != null) viewModel.onDateSelected(date)
                }) { Text("Установить дату") }
                Button(onClick = {
                    viewModel.resetClock()
                    dateText = LocalDate.now().toString()
                }) { Text("Сброс к системным часам") }
            }
            ScrollableRow {
                Button(onClick = { viewModel.peek() }) { Text("Показать решение (peek)") }
                Button(onClick = { viewModel.startSession() }) { Text("Начать сессию") }
            }
            Text("Decision: ${uiState.lastDecision}")
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item {
            Text("Фикстура и база")
            ScrollableRow {
                Button(onClick = { viewModel.installFixture(5) }) { Text("Залить фикстуру (5 наборов)") }
                Button(onClick = { viewModel.clearDatabase() }) { Text("Очистить базу") }
            }
            ScrollableRow {
                Button(onClick = { resetDialogVisible = true }) {
                    Text(stringResource(R.string.debug_reset_temporary_content))
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item {
            Text("Записать попытку")
            ScrollableRow {
                OutlinedTextField(
                    value = attemptSlot.toString(),
                    onValueChange = { attemptSlot = it.toIntOrNull()?.coerceIn(0, 2) ?: attemptSlot },
                    label = { Text("slot 0..2") },
                )
                OutlinedTextField(
                    value = attemptScore.toString(),
                    onValueChange = { attemptScore = it.toIntOrNull()?.coerceIn(0, 6) ?: attemptScore },
                    label = { Text("score 0..6") },
                )
            }
            Button(onClick = { viewModel.recordAttempt(attemptSlot, attemptScore) }) {
                Text("Записать попытку")
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item {
            Text(
                "Диагностика — снимок прочитан отдельно, после решения",
                style = MaterialTheme.typography.bodyMedium,
            )
            val view = uiState.diagnostics
            if (view == null) {
                Text("Ещё не читалась. Нажмите peek/начать сессию.")
            } else {
                Text("today = ${view.today}")
                Text("activePackId = ${view.activePackId}")
                Text("pending (до двух) = ${view.pending}")
                Text("todayAssignment = ${view.todayAssignment}")
                Text("lastAssignedDate = ${view.lastAssignedDate}")
                Text("maxSetIndexInActivePack = ${view.maxSetIndexInActivePack}")
                Text("setCountInActivePack = ${view.setCountInActivePack}")
                Text("nextSetIndex = ${view.nextSetIndex}")
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        }

        item { Text("Дамп: day_assignments (${dayAssignments.size})") }
        items(dayAssignments) { row: DayAssignmentEntity -> Text("  $row") }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        item { Text("Дамп: puzzle_attempts (${puzzleAttempts.size})") }
        items(puzzleAttempts) { row: PuzzleAttemptEntity -> Text("  $row") }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        item { Text("Дамп: day_results (${dayResults.size})") }
        items(dayResults) { row: DayResultEntity -> Text("  $row") }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        item { Text("Дамп: daily_sets (${dailySets.size})") }
        items(dailySets) { row: DailySetEntity -> Text("  $row") }
        item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

        item {
            Text("UserPreferences")
            val prefs = preferences
            if (prefs == null) {
                Text("Загрузка…")
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Звук")
                    Switch(checked = prefs.soundEnabled, onCheckedChange = { viewModel.setSoundEnabled(it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Вибрация")
                    Switch(checked = prefs.vibrationEnabled, onCheckedChange = { viewModel.setVibrationEnabled(it) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Напоминание")
                    Switch(checked = prefs.reminderEnabled, onCheckedChange = { viewModel.setReminderEnabled(it) })
                }
                Text("reminderTime = ${prefs.reminderTime}")
                Text("themeMode = ${prefs.themeMode}")
                ScrollableRow {
                    ThemeMode.entries.forEach { mode ->
                        Button(onClick = { viewModel.setThemeMode(mode) }) { Text(mode.name) }
                    }
                }
                Text("storedContentVersion = ${prefs.storedContentVersion}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("hasSeenDragHint")
                    Switch(
                        checked = prefs.hasSeenDragHint,
                        onCheckedChange = { viewModel.setHasSeenDragHint(it) },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("hasSeenScoringHint")
                    Switch(
                        checked = prefs.hasSeenScoringHint,
                        onCheckedChange = { viewModel.setHasSeenScoringHint(it) },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("hasCompletedFirstDay")
                    Switch(
                        checked = prefs.hasCompletedFirstDay,
                        onCheckedChange = { viewModel.setHasCompletedFirstDay(it) },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("notificationPromptShown")
                    Switch(
                        checked = prefs.notificationPromptShown,
                        onCheckedChange = { viewModel.setNotificationPromptShown(it) },
                    )
                }
                Text("lastSeenDate = ${prefs.lastSeenDate}")
                ScrollableRow {
                    Button(onClick = { viewModel.setLastSeenDate(uiState.selectedDate) }) {
                        Text("lastSeenDate = выбранная дата")
                    }
                    Button(onClick = { viewModel.setLastSeenDate(null) }) { Text("lastSeenDate = null") }
                }

                Text("streakCache = ${prefs.streakCache}")
                ScrollableRow {
                    OutlinedTextField(
                        value = streakCurrentText,
                        onValueChange = { streakCurrentText = it },
                        label = { Text("current") },
                    )
                    OutlinedTextField(
                        value = streakBestText,
                        onValueChange = { streakBestText = it },
                        label = { Text("best") },
                    )
                    OutlinedTextField(
                        value = streakDateText,
                        onValueChange = { streakDateText = it },
                        label = { Text("date") },
                    )
                }
                Button(onClick = {
                    val current = streakCurrentText.toIntOrNull()
                    val best = streakBestText.toIntOrNull()
                    val date = runCatching { LocalDate.parse(streakDateText) }.getOrNull()
                    if (current != null && best != null && date != null) {
                        viewModel.updateStreakCache(current, best, date)
                    }
                }) { Text("updateStreakCache") }
            }
        }
    }
}
