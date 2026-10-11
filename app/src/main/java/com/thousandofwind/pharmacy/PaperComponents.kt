package com.thousandofwind.pharmacy

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp

@Composable
fun PaperWordmark() {
    Row(Modifier.clearAndSetSemantics { contentDescription = "我的药箱" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("我的", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
        Image(painterResource(R.drawable.ink_yao), null, Modifier.size(43.dp))
        Image(painterResource(R.drawable.ink_xiang), null, Modifier.size(43.dp))
    }
}

enum class InkSymbol { SEARCH, PLUS, BACK, NEXT, DOWN, MORE, FILTER, CHECK, CLOSE, CAMERA, BOOK, WARNING }

@Composable
fun InkIcon(symbol: InkSymbol, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.size(24.dp)) {
        val stroke = Stroke(1.65f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        scale(size.width / 28f, size.height / 28f, pivot = Offset.Zero) {
            val p = Path()
            fun line(x: Float, y: Float, x2: Float, y2: Float) { p.moveTo(x, y); p.lineTo(x2, y2) }
            when (symbol) {
                InkSymbol.SEARCH -> {
                    p.moveTo(16f, 5f); p.cubicTo(8f, 1f, 3f, 7f, 4f, 13f)
                    p.cubicTo(5f, 20f, 12f, 23f, 17f, 18f); p.cubicTo(22f, 13f, 21f, 9f, 17f, 6f)
                    line(18f, 18f, 25f, 25f)
                }
                InkSymbol.PLUS -> { p.moveTo(14f, 5f); p.quadraticBezierTo(13f, 14f, 14f, 24f); p.moveTo(5f, 15f); p.quadraticBezierTo(13f, 13f, 23f, 14f) }
                InkSymbol.BACK -> { p.moveTo(13f, 6f); p.lineTo(4f, 14f); p.lineTo(13f, 22f); line(6f, 14f, 25f, 14f) }
                InkSymbol.NEXT -> { p.moveTo(17f, 6f); p.lineTo(25f, 14f); p.lineTo(16f, 22f); line(4f, 15f, 23f, 14f) }
                InkSymbol.DOWN -> { p.moveTo(6f, 11f); p.lineTo(14f, 18f); p.lineTo(23f, 10f) }
                InkSymbol.MORE -> { listOf(5f, 14f, 23f).forEach { drawCircle(color, 1.2f, Offset(it, 14f)) } }
                InkSymbol.FILTER -> { line(4f, 7f, 24f, 7f); line(7f, 14f, 21f, 13f); line(10f, 21f, 18f, 20f); line(10f, 4f, 10f, 10f); line(18f, 11f, 17f, 17f); line(13f, 18f, 13f, 24f) }
                InkSymbol.CHECK -> { p.moveTo(5f, 15f); p.lineTo(11f, 21f); p.quadraticBezierTo(19f, 9f, 24f, 5f) }
                InkSymbol.CLOSE -> { line(6f, 6f, 23f, 23f); line(22f, 5f, 5f, 23f) }
                InkSymbol.CAMERA -> {
                    p.moveTo(4f, 10f); p.lineTo(9f, 9f); p.lineTo(11f, 5f); p.lineTo(19f, 6f); p.lineTo(21f, 10f)
                    p.lineTo(25f, 10f); p.lineTo(24f, 25f); p.lineTo(3f, 24f); p.close()
                    drawCircle(color, 4.5f, Offset(15f, 17f), style = stroke)
                }
                InkSymbol.BOOK -> {
                    p.moveTo(14f, 7f); p.quadraticBezierTo(8f, 3f, 3f, 6f); p.lineTo(3f, 24f)
                    p.quadraticBezierTo(9f, 21f, 14f, 24f); p.quadraticBezierTo(19f, 20f, 24f, 23f)
                    p.lineTo(25f, 5f); p.quadraticBezierTo(19f, 3f, 14f, 7f); p.lineTo(13f, 23f)
                }
                InkSymbol.WARNING -> { p.moveTo(13f, 3f); p.lineTo(2f, 24f); p.quadraticBezierTo(14f, 25f, 26f, 23f); p.lineTo(16f, 4f); line(14f, 10f, 13f, 16f); line(13f, 20f, 14f, 20f) }
            }
            drawPath(p, color, style = stroke)
        }
    }
}

/** Authored open contours, scaled to a stable touch/layout rectangle; never random jitter. */
fun Modifier.paperBorder(color: Color = PaperPalette.Frame) = drawBehind {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w * .035f, h * .08f); cubicTo(w * .3f, h * .025f, w * .7f, h * .11f, w * .95f, h * .065f)
        quadraticBezierTo(w * .993f, h * .06f, w * .987f, h * .23f); lineTo(w * .983f, h * .76f)
        moveTo(w * .963f, h * .86f); cubicTo(w * .7f, h * .92f, w * .4f, h * .86f, w * .045f, h * .9f)
        quadraticBezierTo(w * .012f, h * .91f, w * .017f, h * .74f); lineTo(w * .014f, h * .3f)
        quadraticBezierTo(w * .012f, h * .2f, w * .025f, h * .14f)
    }
    drawPath(p, color, style = Stroke(1.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun Modifier.paperWash() = drawBehind {
    val w = size.width; val h = size.height
    drawPath(Path().apply {
        moveTo(w * .02f, h * .14f); quadraticBezierTo(w * .35f, h * .03f, w * .7f, h * .11f)
        lineTo(w * .96f, h * .08f); lineTo(w * .985f, h * .35f); lineTo(w * .97f, h * .43f)
        lineTo(w * .99f, h * .65f); lineTo(w * .965f, h * .86f)
        quadraticBezierTo(w * .65f, h * .95f, w * .35f, h * .9f)
        lineTo(w * .035f, h * .95f); lineTo(w * .02f, h * .72f); lineTo(w * .034f, h * .5f)
        lineTo(w * .012f, h * .3f); close()
    }, PaperPalette.Wash)
}

@Composable
fun PenButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: InkSymbol? = null) {
    // Material Surface supplies press indication, button semantics and disabled behavior.
    Surface(onClick = onClick, enabled = enabled, color = Color.Transparent, modifier = modifier.heightIn(min = 56.dp).paperWash()) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            icon?.let { InkIcon(it, color = if (enabled) PaperPalette.Green else PaperPalette.Muted) }
            Text(text, style = PaperHeading, color = if (enabled) PaperPalette.Green else PaperPalette.Muted)
        }
    }
}

@Composable
fun InkDivider(modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(9.dp)) {
        val p = Path().apply {
            moveTo(2.dp.toPx(), size.height * .5f)
            cubicTo(size.width * .18f, size.height * .25f, size.width * .37f, size.height * .6f, size.width * .54f, size.height * .43f)
            quadraticBezierTo(size.width * .8f, size.height * .4f, size.width - 2.dp.toPx(), size.height * .5f)
        }
        drawPath(p, PaperPalette.Line, style = Stroke(.8.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
fun PaperField(
    value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: String? = null, enabled: Boolean = true, singleLine: Boolean = true,
    placeholder: @Composable (() -> Unit)? = null, supportingText: @Composable (() -> Unit)? = null,
    leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default, isError: Boolean = false,
    accessibilityLabel: String? = label,
    minLines: Int = 1, maxLines: Int = if (singleLine) 1 else 5
) {
    val focus = remember { FocusRequester() }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) Box(
            Modifier.clearAndSetSemantics { }.pointerInput(enabled) {
                detectTapGestures { if (enabled) focus.requestFocus() }
            }
        ) { Text(label) }
        OutlinedTextField(
            value = value, onValueChange = onValueChange, enabled = enabled, singleLine = singleLine,
            placeholder = placeholder, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
            keyboardOptions = keyboardOptions, isError = isError, minLines = minLines, maxLines = maxLines,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent, unfocusedBorderColor = Color.Transparent,
                disabledBorderColor = Color.Transparent, errorBorderColor = Color.Transparent
            ), modifier = Modifier.fillMaxWidth().focusRequester(focus)
                .semantics { if (accessibilityLabel != null) contentDescription = accessibilityLabel }
                .paperBorder(if (isError) PaperPalette.Error else PaperPalette.Frame)
        )
        supportingText?.invoke()
    }
}

@Composable
fun PaperDisclosure(title: String, expanded: Boolean, toggle: () -> Unit, subtitle: String? = null, enabled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .clickable(enabled = enabled, role = Role.Button, onClick = toggle).heightIn(min = 58.dp).padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = PaperPalette.Muted) }
            }
            InkIcon(InkSymbol.DOWN)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(tween(220)) + fadeIn(tween(170)),
            exit = shrinkVertically(tween(180)) + fadeOut(tween(120))
        ) {
            Column(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
        InkDivider()
    }
}
