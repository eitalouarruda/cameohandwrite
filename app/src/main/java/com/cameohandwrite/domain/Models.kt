package com.cameohandwrite.domain

import kotlin.math.hypot

data class Pt(val x: Double, val y: Double) {
    fun distance(other: Pt) = hypot(x - other.x, y - other.y)
}
typealias Stroke = List<Pt>
data class Paper(
    val name: String = "Fichário A4", val width: Double = 210.0, val height: Double = 297.0,
    val left: Double = 25.0, val right: Double = 15.0, val top: Double = 10.0,
    val bottom: Double = 15.0, val holes: Double = 20.0,
    val firstLine: Double = 20.0, val lineSpacing: Double = 8.0,
    val offsetX: Double = 0.0, val offsetY: Double = 0.0
) {
    val startX get() = maxOf(left, holes)
    val usableHeight get() = height - bottom - maxOf(top, firstLine)
    val lineCount get() = if (lineSpacing > 0) (usableHeight / lineSpacing).toInt().coerceAtLeast(-1) + 1 else 0
    fun validate() {
        require(listOf(width,height,left,right,top,bottom,holes,firstLine,lineSpacing,offsetX,offsetY).all { it.isFinite() }) { "Valores da folha inválidos" }
        require(width in 30.0..305.0 && height in 30.0..600.0) { "Folha fora dos limites configuráveis (30–305 × 30–600 mm)" }
        require(left >= 0 && right >= 0 && top >= 0 && bottom >= 0 && holes >= 0 && startX + right < width) { "Margens sem área útil" }
        require(lineSpacing >= 1 && firstLine >= top && firstLine <= height-bottom && lineCount in 1..600) { "Pautas fora da área útil" }
        require(kotlin.math.abs(offsetX) <= 30 && kotlin.math.abs(offsetY) <= 30) { "Calibração limitada a ±30 mm" }
    }
}
enum class Style(val label: String) { SIMPLE("Manuscrita simples"), CURSIVE("Manuscrita cursiva"), PRINT("Letra de forma"), UNEVEN("Letra irregular"), MESSY("Letra feia") }
enum class Align(val label: String) { LEFT("Esquerda"), CENTER("Centralizado"), JUSTIFY("Justificado") }
enum class Quality(val label: String, val tolerance: Double, val speed: Double) { FAST("Rápido",0.12,45.0), NORMAL("Normal",0.05,25.0), CAREFUL("Caprichado",0.015,12.0) }
data class Settings(
    val text: String = "Olá! Esta é a minha escrita.\nAção, coração, café e amanhã. ¡Buenos días!",
    val paper: Paper = Paper(), val style: Style = Style.SIMPLE,
    val letterHeight: Double = 4.0, val wordSpace: Double = 2.0,
    val irregularity: Int = 15, val alignment: Align = Align.LEFT,
    val quality: Quality = Quality.NORMAL, val writeSpeed: Double = 25.0,
    val travelSpeed: Double = 70.0, val tolerance: Double = 0.05,
    val preferredDevice: String = "", val seed: Int = 517
)
data class Page(val strokes: List<Stroke>, val lines: Int)
data class Document(val pages: List<Page>, val warnings: List<String> = emptyList())
