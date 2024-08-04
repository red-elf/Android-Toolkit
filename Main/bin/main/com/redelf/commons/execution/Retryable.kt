package com.redelf.commons.execution

class Retryable(private val count: Int = 5) {

    fun execute(operation: () -> Boolean): Int {

        var counter = 0
        while (!operation() && counter < count) {

            counter++
        }
        return counter
    }
}