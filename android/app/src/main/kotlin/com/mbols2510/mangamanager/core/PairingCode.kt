package com.mbols2510.mangamanager.core

data class Pairing(val url: String, val token: String)

object PairingCode {
    fun parse(raw: String): Pairing? = null
}
