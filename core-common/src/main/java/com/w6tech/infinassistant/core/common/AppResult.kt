package com.w6tech.infinassistant.core.common

sealed interface AppResult<out T> {
    data class Ok<T>(val value: T) : AppResult<T>
    data class Err(val error: Throwable) : AppResult<Nothing>
}
