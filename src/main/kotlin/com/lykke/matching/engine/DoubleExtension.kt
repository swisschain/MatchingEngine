package com.lykke.matching.engine

val PRECISION = 0.0000000001
fun Double.greaterThan(other: Double): Boolean {
    return Math.abs(this - other) > PRECISION
}
