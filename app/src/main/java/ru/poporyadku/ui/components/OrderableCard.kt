package ru.poporyadku.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import ru.poporyadku.R
import ru.poporyadku.ui.theme.Elevation
import ru.poporyadku.ui.theme.Opacity
import ru.poporyadku.ui.theme.OrderableCardCutCorner
import ru.poporyadku.ui.theme.ProjectTextStyles
import ru.poporyadku.ui.theme.Sizing
import ru.poporyadku.ui.theme.Spacing
import ru.poporyadku.ui.theme.rememberMotionTokens

/** Стабильные testTag карточки: адресуются по `cardId`, никогда по позиции. */
object OrderableCardTestTags {
    fun card(cardId: String): String = "orderable_card_$cardId"
    fun moveUp(cardId: String): String = "orderable_card_move_up_$cardId"
    fun moveDown(cardId: String): String = "orderable_card_move_down_$cardId"
}

/**
 * Управление карточкой. `null` означает read-only: ни `MoveButton`, ни custom actions
 * не существуют в дереве — не «disabled», а отсутствуют (COMPONENTS.md).
 *
 * [enabled] = `false` — состояние `Submitting.Answer`: кнопки видны и объявлены
 * disabled, а custom actions **отсутствуют**, иначе TalkBack-пользователь получил бы
 * работающее перемещение там, где ответ уже отправлен (I3-C16).
 */
data class OrderableCardControls(
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
    val enabled: Boolean,
    val onMoveUp: () -> Unit,
    val onMoveDown: () -> Unit,
    val onMoveToTop: () -> Unit,
    val onMoveToBottom: () -> Unit,
)

/**
 * Карточка упорядочивания (COMPONENTS.md, «OrderableCard»).
 *
 * Анатомия слева направо: индексная зона `size.orderableCard.indexZoneWidth` (56 dp,
 * не сжимается ни при 320 dp, ни при 200%), hairline, центральная зона (гибкая), зона
 * управления с двумя `MoveButton`.
 *
 * **`DragHandle` в итерации 3 не рендерится** (I3-D24): жеста за ним нет до итерации 6,
 * а иконка без действия на экране не размещается. Ширина индексной зоны при этом
 * сохранена — геометрия не поедет, когда ручка появится.
 *
 * **Центральная зона не интерактивна**: нажатие вне `MoveButton` не делает ничего, и
 * собственного `pressed`-состояния (ripple, state layer) у неё поэтому нет.
 *
 * Семантика: содержимое карточки — **один составной узел** «Позиция N из 4. {Название}»
 * с применимыми custom actions; `MoveButton` остаются отдельными узлами, иначе
 * TalkBack не смог бы ими воспользоваться.
 */
@Composable
fun OrderableCard(
    cardId: String,
    position: Int,
    totalPositions: Int,
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    /** Ключевое значение карточки на `PuzzleResult` («5642 м») — то, что делает экран обучающим. */
    displayValue: String? = null,
    controls: OrderableCardControls? = null,
    /**
     * Карточку держат пальцем (COMPONENTS.md, состояние `dragging`).
     *
     * Локальное состояние экрана, а не поле `PuzzleUiState`: см. `ReorderDragState`.
     */
    dragging: Boolean = false,
    /**
     * `DragHandle` в индексной зоне. `null` — ручки нет в дереве вовсе: read-only
     * `PuzzleResult`, `Loading` и `Error` (I6-D11).
     *
     * Слотом, а не набором колбэков: компонент из `ui/components` не знает ни о
     * `PuzzleEvent`, ни о сессии жеста — он только отводит ручке её место.
     */
    dragHandle: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val motion = rememberMotionTokens()
    val description = stringResource(R.string.cd_card_position, position, totalPositions, title)

    // Подъём и опускание — разные кривые (DESIGN_TOKENS.md §6.8): взятие карточки
    // ускоряется, возврат замедляется. Тень и слой появляются и при reduced motion —
    // это состояние, а не декор; сокращается только длительность.
    val dragSpec = tween<Dp>(
        durationMillis = motion.durationMedium,
        easing = if (dragging) motion.easingStandardAccelerate else motion.easingEmphasizedDecelerate,
    )
    val elevation by animateDpAsState(
        targetValue = if (dragging) Elevation.dragged else Elevation.resting,
        animationSpec = dragSpec,
        label = "orderableCardElevation",
    )
    val stateLayerAlpha by animateFloatAsState(
        targetValue = if (dragging) Opacity.dragStateLayer else NO_STATE_LAYER,
        animationSpec = tween(
            durationMillis = motion.durationMedium,
            easing = if (dragging) motion.easingStandardAccelerate else motion.easingEmphasizedDecelerate,
        ),
        label = "orderableCardDragStateLayer",
    )
    // Слой `primary` поверх заливки — смесью, а не полупрозрачной подложкой: контраст
    // текста на поднятой карточке рассчитан в DESIGN_TOKENS.md §6.2 именно для смеси.
    val containerColor = colors.primary
        .copy(alpha = stateLayerAlpha)
        .compositeOver(colors.surfaceContainerLow)

    val moveUpLabel = stringResource(R.string.action_move_up)
    val moveDownLabel = stringResource(R.string.action_move_down)
    val moveToTopLabel = stringResource(R.string.action_move_to_top)
    val moveToBottomLabel = stringResource(R.string.action_move_to_bottom)

    // Действие, которое не изменило бы позицию, пользователю не предлагается. В
    // read-only и во время записи custom actions отсутствуют целиком.
    val actions = if (controls == null || !controls.enabled) {
        emptyList()
    } else {
        buildList {
            if (controls.canMoveUp) {
                add(CustomAccessibilityAction(moveUpLabel) { controls.onMoveUp(); true })
                add(CustomAccessibilityAction(moveToTopLabel) { controls.onMoveToTop(); true })
            }
            if (controls.canMoveDown) {
                add(CustomAccessibilityAction(moveDownLabel) { controls.onMoveDown(); true })
                add(CustomAccessibilityAction(moveToBottomLabel) { controls.onMoveToBottom(); true })
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .defaultMinSize(minHeight = Sizing.orderableCardMinHeight)
            // Настоящая тень, а не тональная подсветка: `elevation.dragged` — одно из
            // двух функциональных исключений системы (DESIGN_TOKENS.md §6.7).
            .shadow(elevation, OrderableCardCutCorner)
            .background(containerColor, OrderableCardCutCorner)
            .border(Sizing.dividerThickness, colors.outlineVariant, OrderableCardCutCorner)
            // Клип ПОСЛЕ обводки: иначе содержимое (в первую очередь верхняя
            // `MoveButton`) вылезало бы за диагональ срезанного угла.
            .clip(OrderableCardCutCorner)
            .testTag(OrderableCardTestTags.card(cardId)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(WEIGHT_FILL)
                .fillMaxHeight()
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    if (actions.isNotEmpty()) customActions = actions
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardIndexZone(position = position, dragHandle = dragHandle)

            Box(
                modifier = Modifier
                    .width(Sizing.dividerThickness)
                    .fillMaxHeight()
                    .background(colors.outlineVariant),
            )

            Column(
                modifier = Modifier
                    .weight(WEIGHT_FILL)
                    .padding(horizontal = Spacing.scale400, vertical = Spacing.scale300),
                verticalArrangement = Arrangement.spacedBy(Spacing.scale100),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (displayValue != null) {
                    Text(
                        text = displayValue,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }

        if (controls != null) {
            // 4 + 48 + 8 + 48 + 4 = 112 dp: вертикальный бюджет карточки задают ровно
            // две `MoveButton` и эти отступы (DESIGN_TOKENS.md §6.5).
            Column(
                modifier = Modifier.padding(
                    end = Spacing.scale200,
                    top = Spacing.scale100,
                    bottom = Spacing.scale100,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.scale200),
            ) {
                MoveButton(
                    direction = MoveDirection.UP,
                    contentDescription = stringResource(R.string.cd_move_up),
                    onClick = controls.onMoveUp,
                    enabled = controls.enabled && controls.canMoveUp,
                    modifier = Modifier.testTag(OrderableCardTestTags.moveUp(cardId)),
                )
                MoveButton(
                    direction = MoveDirection.DOWN,
                    contentDescription = stringResource(R.string.cd_move_down),
                    onClick = controls.onMoveDown,
                    enabled = controls.enabled && controls.canMoveDown,
                    modifier = Modifier.testTag(OrderableCardTestTags.moveDown(cardId)),
                )
            }
        }
    }
}

/**
 * Индексная зона фиксированной ширины 56 dp: номер сверху, `DragHandle` снизу
 * (COMPONENTS.md, «OrderableCard», анатомия). Номер — текущая позиция, а не идентификатор
 * карточки: при перестановке значение обновляется у всех затронутых карточек.
 *
 * Зона не расширяется вбок за счёт центральной колонки при 200%: длинный номер
 * переносится внутри неё.
 *
 * Вертикальный бюджет карточки ручка не увеличивает: 12 + 18 (`CardIndex`) + 48
 * (`size.dragHandle.touchTarget`) + 12 = 90 dp — меньше, чем колонка двух `MoveButton`
 * (112 dp), которая и задаёт `size.orderableCard.minHeight` (`I6-C1`).
 */
@Composable
private fun CardIndexZone(position: Int, dragHandle: (@Composable () -> Unit)?) {
    Column(
        modifier = Modifier
            .width(Sizing.orderableCardIndexZoneWidth)
            .fillMaxHeight()
            .padding(vertical = Spacing.scale300),
        horizontalAlignment = Alignment.CenterHorizontally,
        // Номер сверху, ручка снизу, разделены позицией, а не линией и не фиксированным
        // зазором: зазор добавил бы к собственной высоте зоны и мог бы вытолкнуть её за
        // вертикальный бюджет карточки при переносе номера на вторую строку.
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = stringResource(R.string.card_index, position),
            style = ProjectTextStyles.cardIndex,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        dragHandle?.invoke()
    }
}

/**
 * Плейсхолдер карточки на `Puzzle.Loading` (COMPONENTS.md, «Loading skeleton»).
 *
 * Форма — `OrderableCardCutCorner`, а не `shape.medium`: прямоугольный skeleton
 * предвосхищал бы не ту форму, которая появится после загрузки, и визуально «дёргался»
 * бы в момент подмены.
 */
@Composable
fun OrderableCardSkeleton(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Sizing.orderableCardMinHeight)
            .background(MaterialTheme.colorScheme.surfaceContainer, OrderableCardCutCorner),
    )
}

private const val WEIGHT_FILL = 1f

/** Карточка не поднята: слоя `primary` поверх заливки нет вовсе. */
private const val NO_STATE_LAYER = 0f
