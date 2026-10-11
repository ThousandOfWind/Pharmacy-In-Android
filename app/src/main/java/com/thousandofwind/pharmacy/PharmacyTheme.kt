package com.thousandofwind.pharmacy

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object PaperPalette {
    val Paper = Color(0xFFFCFAF3)
    val Ink = Color(0xFF3E4738)
    val Muted = Color(0xFF657553)
    val Green = Color(0xFF526640)
    val Wash = Color(0xFFDFE8D0)
    val Line = Color(0xFFBAC3AB)
    val Frame = Color(0xFF808F6C)
    val Error = Color(0xFF974E3D)
    val Amber = Color(0xFF876330)
}

// Full-character font for all user data. The subset display font is used only for static headings.
private val PaperText = FontFamily(Font(R.font.paper_text))
private val PaperDisplay = FontFamily(Font(R.font.paper_display))
private fun text(size: Int, line: Int) = TextStyle(fontFamily = PaperText, fontSize = size.sp, lineHeight = line.sp)
val PaperHeading = TextStyle(fontFamily = PaperDisplay, fontSize = 27.sp, lineHeight = 36.sp)

@Composable
fun PharmacyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = PaperPalette.Green, onPrimary = PaperPalette.Paper,
            primaryContainer = PaperPalette.Wash, onPrimaryContainer = PaperPalette.Ink,
            secondary = PaperPalette.Muted, onSecondary = PaperPalette.Paper,
            secondaryContainer = Color(0xFFE9EFDE), onSecondaryContainer = PaperPalette.Green,
            tertiary = PaperPalette.Amber, onTertiary = PaperPalette.Paper,
            background = PaperPalette.Paper, onBackground = PaperPalette.Ink,
            surface = PaperPalette.Paper, onSurface = PaperPalette.Ink,
            surfaceVariant = Color(0xFFF1F3E7), onSurfaceVariant = PaperPalette.Muted,
            outline = PaperPalette.Frame, outlineVariant = PaperPalette.Line,
            error = PaperPalette.Error, onError = Color.White,
            errorContainer = Color(0xFFF8E9DE), onErrorContainer = PaperPalette.Error,
            surfaceTint = PaperPalette.Green
        ),
        typography = Typography(
            headlineLarge = text(29, 40), headlineMedium = text(26, 36), headlineSmall = text(24, 34),
            titleLarge = text(22, 32), titleMedium = text(19, 28), titleSmall = text(17, 25),
            bodyLarge = text(17, 27), bodyMedium = text(15, 24), bodySmall = text(13, 21),
            labelLarge = text(16, 24), labelMedium = text(13, 20), labelSmall = text(12, 19)
        ),
        shapes = Shapes(
            extraSmall = RoundedCornerShape(5.dp), small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp), large = RoundedCornerShape(20.dp),
            extraLarge = RoundedCornerShape(25.dp)
        ), content = content
    )
}
