package eu.studiodeanna.openchecklists.store

import kotlinx.serialization.Serializable

/** This device's preferences. Never leaves the device. */
@Serializable
data class Settings(
    val theme: ThemeChoice = ThemeChoice.System,
    val language: LanguageChoice = LanguageChoice.System,
)

@Serializable
enum class ThemeChoice { System, Light, Dark }

@Serializable
enum class LanguageChoice { System, English, Italian }
