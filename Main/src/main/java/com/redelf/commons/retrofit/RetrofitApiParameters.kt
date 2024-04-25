package com.redelf.commons.retrofit

import android.content.Context
import com.google.gson.annotations.SerializedName
import com.redelf.commons.R
import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap

data class RetrofitApiParameters(

    @Transient
    val ctx: Context,
    @SerializedName("readTimeoutInSeconds")
    val readTimeoutInSeconds: Long = 10,
    @SerializedName("connectTimeoutInSeconds")
    val connectTimeoutInSeconds: Long = 10,
    @SerializedName("writeTimeoutInSeconds")
    val writeTimeoutInSeconds: Long = -1,
    @SerializedName("endpoint")
    val endpoint: Int = R.string.retrofit_endpoint,
    @SerializedName("scalar")
    val scalar: Boolean = false,
    @SerializedName("callsWrapper")
    val callsWrapper: ConcurrentHashMap<String, Call> = GlobalCallsWrapper.CALLS
)