package com.gigasan.ai.config

import com.intellij.util.xmlb.annotations.Tag

@Tag("Endpoint")
data class EndpointSettings(

    // connection
    var baseUrl: String = "",
    var modelListEndpointUrl: String = "",
    var chatEndpointUrl: String = "",
    var apiKey: String = "",

    // model
    var selectedModelName: String = "",
    var selectedModelKey: String = "",
    //   V
    var system: String = "",
    var maxContext: Int = 16384,
    var maxTokenLimit: Int = 4000,
    var reasoning: Boolean = false,
    var stream: Boolean = false,
    var temperature: Float = 0.7f,
    var logprobs: Boolean = false,
    var top_logprobs: Int = 0,
    var keep_alive: Int = 5,

)
