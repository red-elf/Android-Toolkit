package com.redelf.commons.type

data class PairDataInfo(

    var first: Any,
    var second: Any,
    var firstType: String? = first::class.java.canonicalName,
    var secondType: String? = first::class.java.canonicalName
)
