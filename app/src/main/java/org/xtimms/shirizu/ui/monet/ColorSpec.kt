package org.xtimms.shirizu.ui.monet

data class ColorSpec(
    val chroma: (Double) -> Double = { it },
    val hueShift: (Double) -> Double = { 0.0 }
)