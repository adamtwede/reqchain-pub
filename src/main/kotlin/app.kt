import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.Klaxon
import com.beust.klaxon.Parser
import io.github.rybalkinsd.kohttp.dsl.*
import io.github.rybalkinsd.kohttp.ext.asStream
import okhttp3.Response
import java.io.IOException
import java.lang.Exception

typealias Stack = MutableList<MappingToOutgoingRequest>

val fakeEnvVarMap = mutableMapOf<String, String?>( // for testing, new env vars sometimes don't like to show up
    "reqchain_host" to "my.idp.com",
    "reqchain_client_id" to "PLiAGZ46qyc72yPNXoJ3t7b2f01LRvPL",
    "reqchain_client_secret" to "ctYZvUsCd4sRklQzez4ieGcFUwx5k3XCgjrZmkdAbPvdeu4NQwpIlgEZUBs3bcb4",
    "reqchain_audience" to "https://my.audience.com",
    "reqchain_grant_type" to "client_credentials"
)

val responseRootArrayKey = "__responseRootWrapperKeyForArray__"
val defaultRequestsUri = ""
val interpolationStartDelimiter = "\${"
val interpolationEndDelimiter = "}"
val interpolationPatternString = "\\\$\\{([a-zA-Z]+)}"
val envSafetyInfix = "reqchain_"
val envPrefix = "env."
val envPatternStartDelimiter = "<"
val envPatternEndDelimiter = ">"
val envPatternString = "$envPatternStartDelimiter$envPrefix([a-zA-Z_]+)$envPatternEndDelimiter"

val envPattern = Regex(envPatternString)
val interpolationPattern = Regex(interpolationPatternString)

fun main(args: Array<String>) {

    if(args.isEmpty() || (args.contains("configFilePath=") || args.contains("--configFilePath="))) {
        throw Exception("configFilePath argument required")
    }

    val appConfig = AppConfig()

    args.forEach {
        if(it.startsWith("configFilePath=") || it.startsWith("--configFilePath=")) {
            val configFilePath = it.split("=")
            if(configFilePath.size < 2) throw Exception("configFilePath value not found")
            if(configFilePath[1].startsWith("http")) throw Exception("config files as URLs not yet supported")
            val configJson = Parser().parse(configFilePath[1]) as JsonObject

            if(configJson.containsKey("requestsUri")) {
                val requestsUri = configJson["requestsUri"]
                val valString = requestsUri?.toString()?:defaultRequestsUri
                if(valString.startsWith("http")) throw Exception("URLs not yet supported")
                    else appConfig.requestsUri = valString
            } else {
                throw Exception("required config key 'requestsUri' not found in config file")
            }
            if(configJson.containsKey("mockResponsesUri")) {
                val mockResponsesUri = configJson["mockResponsesUri"]
                val valString = mockResponsesUri?.toString()?:""
                if(valString.startsWith("http")) throw Exception("URLs not yet supported")
                    else appConfig.mockResponsesUri = valString
            }
            if(configJson.containsKey("useMocks")) {
                appConfig.useMocks = configJson["useMocks"] as Boolean
            }
            if(configJson.containsKey("useEnv")) {
                appConfig.useEnv = configJson["useEnv"] as Boolean
            }
            if(configJson.containsKey("templateString")) {
                appConfig.templateString = configJson["templateString"].toString()
            }
        }
    }

    val reqUri = appConfig.requestsUri
    val mockRespUri = appConfig.mockResponsesUri
    val useMocks = appConfig.useMocks
    val useEnv = appConfig.useEnv
    val outputTemplate = appConfig.templateString

    val result = executeRequestChain(reqUri, mockRespUri, mock = useMocks, env = useEnv)
    val preservedItems = result["preservedItems"]?: emptyMap<String, String>()

    if(outputTemplate.isNotBlank()) {
        val replaced = interpolationPattern.replace(outputTemplate) { m ->
                val key = m.value.replace(interpolationStartDelimiter, "")
                                        .replace(interpolationEndDelimiter, "")
                preservedItems[key].toString()
        }
        println(replaced)
    } else {
        println(Klaxon().toJsonString(preservedItems))
    }
}

fun executeRequestChain(requestsUri: String, mockResponsesUri: String, haltOnAssertionFailure: Boolean = true, mock: Boolean = false, env: Boolean = true): MutableMap<String, MutableMap<String, String>> {
    val requestsJson = Parser().parse(requestsUri) as JsonArray<*>

//    if(mock) println("using mocked responses")
    val result = mutableMapOf<String, MutableMap<String, String>>()
    result["preservedItems"] = mutableMapOf()
    result["assertionFailures"] = mutableMapOf()

    val mappings: Stack = mutableListOf()

    for (i in 0 until requestsJson.size) {
        val req = requestsJson[i] as JsonObject

        if(env) {
            replaceReqValsWithEnvVars(req)
        }

        while (mappings.hasMore()) {
            val mapping = mappings.pop()
            val keyToReplace = mapping?.targetKeyToReplaceInRequest?:""
            val targetLocation = mapping?.targetPathInNextRequest?.split(".")?:emptyList()
            val targetValue = mapping?.targetValueInNextRequest
            val targetJsonObject = findParentOfJsonObjectByPath(targetLocation, req)

            if(keyToReplace.isNotBlank()) {
                targetJsonObject?.let {
                    // apply mappings from previous response to outgoing request.
                    // mutating original json object "req" in place. this is not ideal.
                    val targetKey = targetLocation.last()
                    val existingValue = it[targetKey]
                    val replacementPattern = Regex("<$keyToReplace>")
                    val newValue = existingValue.toString().replace(replacementPattern, targetValue ?: "")
                    it[targetLocation.last()] = newValue
                }
            }
        }
        // these will now contain mapped values from previous request (if any):
        val reqEndpoint = req.obj("endpoint")?.toJsonString()?.let { Klaxon().parse<Endpoint>(it) }
        val reqBody = req.obj("body")?.toJsonString() ?: ""
        val reqHeaders = req.obj("headers")?.toMap() ?: emptyMap()
        val reqParams = req.obj("params")?.toMap() ?: emptyMap()

        val responseJson = if(mock) {
            getMockedResponse(i, mockResponsesUri)
        } else {
            executeRequest(reqEndpoint, reqBody, reqHeaders, reqParams)
        }

        val responseRootIsArray = responseJson?.containsKey(responseRootArrayKey) == true

        val reqChain = req.array<JsonObject>("chainMappings")?.map {
            Klaxon().parse<MappingFromJson>(it.toJsonString())
        }?.map {
            // prepend array root key for responses that are json arrays instead of json objects
            if(responseRootIsArray)
                MappingFromJson(responseRootArrayKey + it?.useResponseKey,
                    it?.usePreservedValue?:"",
                    it?.currentResponseValue?:"",
                    it?.mapTo?:"",
                    it?.preserveValueAs?:"")
            else it
        }?.toList()

        val responseAssertions = req.array<JsonObject>("responseAssertions")?.map {
            Klaxon().parse<ResponseAssertion<*>>(it.toJsonString())
        }?.map {
            // same as above
            if(responseRootIsArray)
                ResponseAssertion(responseRootArrayKey + it?.checkResponseKey,
                    it?.expectedValue)
            else it
        }?.toList()?: emptyList()

        // check assertions and set up mappings for next request to apply
        if(responseJson != null) {

            // check assertions
            responseAssertions.forEach { assertion ->
                val responseKeyString = assertion?.checkResponseKey
                val responseKey = responseKeyString?.split(".")?: emptyList()
                val expectedValue = assertion?.expectedValue
                val actualValue = getJsonValue(responseKey, responseJson)

                if(expectedValue != actualValue) {
                    result["assertionFailures"]?.put(expectedValue.toString(), actualValue.toString())
                    if(haltOnAssertionFailure) {
                        val expectedClassName: String = expectedValue?.javaClass?.simpleName?:""
                        val actualClassName: String = actualValue?.javaClass?.simpleName?:""
                        throw AssertionError("ASSERTION FAILURE on request ${i + 1} at " +
                                "$responseKeyString -> expected: $expectedValue ($expectedClassName), " +
                                "actual from response: $actualValue ($actualClassName)")
                    }
                }
            }

            reqChain?.map { mapping ->
                val targetPathInResponse = mapping?.useResponseKey.toString()
                val targetLocationInPreserved = mapping?.usePreservedValue.toString()

                var usePreserved = false
                if(targetPathInResponse.isNotBlank() && targetLocationInPreserved.isNotBlank()) {
                    throw Exception("target location incorrectly defined in one of request ${i+1}'s mappings. " +
                            "defining 'useResponseKey' and 'usePreservedValue' in same mapping is not allowed.")
                } else if(targetLocationInPreserved.count() > 0) {
                    usePreserved = true
                }

                val targetLocationInResponse: List<String>

                var targetValue = ""

                if(usePreserved) {
                    targetLocationInResponse = listOf(targetLocationInPreserved)
                    targetValue = result["preservedItems"]?.get(targetLocationInPreserved)?:""
                } else {
                    targetLocationInResponse = targetPathInResponse.split(".")
                    val parentObjInResponse = findParentOfJsonObjectByPath(targetLocationInResponse, responseJson)
                    parentObjInResponse?.let {
                        targetValue =
                            it[targetLocationInResponse.last()].toString() // this seems to work for integers too, not sure how
                    }
                }

                var templatedValueInRequest = targetLocationInResponse.first()
                val preserveValueAs = mapping?.preserveValueAs.toString()
                val mapTo = mapping?.mapTo.toString()

                preserveValueAs.let {
                    if (it.isNotBlank()) {
                        result["preservedItems"]?.put(it, targetValue)
                        // if preserveValueAs exists in mapping, that takes priority in request templated string
                        // over usePreservedValue or useResponseKey
                        templatedValueInRequest = it
                    }
                }
                MappingToOutgoingRequest(
                    templatedValueInRequest, // for replacing partial values with templating
                    mapTo, // where to find the value we want to replace in outgoing request
                    targetValue // new value for mapTo location key
                )
            }
                //?.filter { it.currentResponseValue.isNotBlank() }
                ?.forEach { mappings.push(it) }
        }
    }

    return result
}

fun replaceReqValsWithEnvVars(currentRoot: JsonObject) {
    currentRoot.keys.forEach {
        when(val childNode = currentRoot[it]) {
            is String -> {
                currentRoot[it] = replaceStringForEnvVar(childNode)
            }

            is JsonObject -> {
                replaceReqValsWithEnvVars(childNode)
            }

            is JsonArray<*> -> {
                //for (obj in childNode) {
                for(i in 0 until childNode.size) {
                    val obj = childNode[i]
                    when(obj) {
                        is JsonObject -> {
                            replaceReqValsWithEnvVars(obj)
                        }
                        is String -> {
                            val arrayVal = childNode[i]
                            if(arrayVal is String) {
                                // should be safe to do this cast with arrayVal type check even though
                                // things other than strings can be in a json array
                                val childNodeAs = childNode as JsonArray<String>
                                childNodeAs[i] = replaceStringForEnvVar(arrayVal)
                            }
                        }
                    }
                }
            }
        }
    }
}

fun replaceStringForEnvVar(str: String): String {
    return envPattern.replace(str) { m ->
        val envKey = m.value.replace(envPatternStartDelimiter, "")
            .replace(envPatternEndDelimiter, "")
            .replace(envPrefix, "")
        if(!envKey.startsWith(envSafetyInfix)) m.value
        val envVar = System.getenv(envKey) ?: fakeEnvVarMap[envKey] ?: ""
        if (envVar.isNotBlank()) envVar else m.value
    }
}

fun getJsonValue(path: List<String>, jsonObj: JsonObject): Any? {
    val wrappingObj = findParentOfJsonObjectByPath(path, jsonObj)

    return wrappingObj.let {
        it?.get(path.last())
    }
}

fun findParentOfJsonObjectByPath(path: List<String>, jsonObj: JsonObject): JsonObject? {
    if(path.isEmpty()) {
        return jsonObj
    }

    var currentRoot = path.first()

    val currentRootIsJsonArray: Boolean
    val arraySelectorIndex: Int?
    val jsonArraySelectorPattern = Regex("\\[[0-9]+]")
    if(currentRoot.contains(jsonArraySelectorPattern)) {
        arraySelectorIndex = jsonArraySelectorPattern.find(currentRoot)?.value
            ?.replace("[", "")
            ?.replace("]", "")
            ?.toInt()?:-1
        currentRootIsJsonArray = true
        currentRoot = currentRoot.replace(jsonArraySelectorPattern, "")
    } else {
        currentRootIsJsonArray = false
        arraySelectorIndex = -1
    }

    if(jsonObj.containsKey(currentRoot)) {
        if(path.count() > 1) {
            val nextObj = if(currentRootIsJsonArray) {
                val jArr = jsonObj.array<JsonObject>(currentRoot)?: JsonArray()
                if(jArr.isEmpty() || arraySelectorIndex < 0) return null
                jArr[arraySelectorIndex]
            } else {
                jsonObj[currentRoot] as JsonObject
            }
            return findParentOfJsonObjectByPath(path.subList(1, path.count()), nextObj)
        } else if(path.count() == 1) {
            return jsonObj
        }
    }
    return null
}

fun executeRequest(endpoint: Endpoint?,
                   requestBodyJsonString: String,
                   requestHeaders: Map<String, Any?>,
                   requestParams: Map<String, Any?>): JsonObject {

    val response = dispatchRequest(endpoint, requestBodyJsonString, requestHeaders, requestParams)

    if (response.isSuccessful) {
        val parsedResult = Parser().parse(response.asStream()?.bufferedReader() ?: "".reader())
        if(parsedResult is JsonArray<*>) {
            val wrapper = JsonObject()
            wrapper[responseRootArrayKey] = parsedResult
            return wrapper
        }
        return parsedResult as JsonObject
    }
    throw IOException("${endpoint?.type} request to ${endpoint?.host}:${endpoint?.port}${endpoint?.path} failed with response code ${response.code()} and message: ${response.message()}")
}

fun dispatchRequest(endpoint: Endpoint?,
                    requestBodyJsonString: String,
                    requestHeaders: Map<String, Any?>,
                    requestParams: Map<String, Any?>): Response {
    return when(endpoint?.type?:"GET") {
        "GET" -> executeGet(endpoint, requestHeaders, requestParams)
        "HEAD" -> executeHead(endpoint, requestHeaders, requestParams)
        "DELETE" -> executeDelete(endpoint, requestHeaders, requestParams)
        "POST" -> executePost(endpoint, requestBodyJsonString, requestHeaders, requestParams)
        "PUT" -> executePut(endpoint, requestBodyJsonString, requestHeaders, requestParams)
        "PATCH" -> executePatch(endpoint, requestBodyJsonString, requestHeaders, requestParams)
        else -> throw Exception("invalid request type specified")
    }
}

fun executeGet(endpoint: Endpoint?,
               requestHeaders: Map<String, Any?>,
               requestParams: Map<String, Any?>): Response {
    return httpGet {
        host = endpoint?.host?:""
        port = endpoint?.port
        path = endpoint?.path?:""
        scheme = endpoint?.scheme?:"http"

        param {
            requestParams.keys.forEach { it to requestParams[it].toString() }
        }

        header {
            requestHeaders.keys.forEach { it to requestHeaders[it].toString() }
        }
    }
}
fun executeHead(endpoint: Endpoint?,
                requestHeaders: Map<String, Any?>,
                requestParams: Map<String, Any?>): Response {
    return httpHead {
        host = endpoint?.host?:""
        port = endpoint?.port
        path = endpoint?.path?:""
        scheme = endpoint?.scheme?:"http"

        param {
            requestParams.keys.forEach { it to requestParams[it].toString() }
        }

        header {
            requestHeaders.keys.forEach { it to requestHeaders[it].toString() }
        }
    }
}
fun executeDelete(endpoint: Endpoint?,
                  requestHeaders: Map<String, Any?>,
                  requestParams: Map<String, Any?>): Response {
    return httpDelete {
        host = endpoint?.host?:""
        port = endpoint?.port
        path = endpoint?.path?:""
        scheme = endpoint?.scheme?:"http"

        param {
            requestParams.keys.forEach { it to requestParams[it].toString() }
        }

        header {
            requestHeaders.keys.forEach { it to requestHeaders[it].toString() }
        }
    }
}

fun executePost(endpoint: Endpoint?,
                requestBodyJsonString: String,
                requestHeaders: Map<String, Any?>,
                requestParams: Map<String, Any?>): Response {
    return httpPost {
        host = endpoint?.host?:""
        port = endpoint?.port
        path = endpoint?.path?:""
        scheme = endpoint?.scheme?:"http"

        param {
            requestParams.keys.forEach { it to requestParams[it].toString() }
        }

        header {
            requestHeaders.keys.forEach { it to requestHeaders[it].toString() }
        }

        body {
            json(requestBodyJsonString)
        }
    }
}
fun executePut(endpoint: Endpoint?,
               requestBodyJsonString: String,
               requestHeaders: Map<String, Any?>,
               requestParams: Map<String, Any?>): Response {
    return httpPut {
        host = endpoint?.host?:""
        port = endpoint?.port
        path = endpoint?.path?:""
        scheme = endpoint?.scheme?:"http"

        param {
            requestParams.keys.forEach { it to requestParams[it].toString() }
        }

        header {
            requestHeaders.keys.forEach { it to requestHeaders[it].toString() }
        }

        body {
            json(requestBodyJsonString)
        }
    }
}
fun executePatch(endpoint: Endpoint?,
                 requestBodyJsonString: String,
                 requestHeaders: Map<String, Any?>,
                 requestParams: Map<String, Any?>): Response {
    return httpPatch {
        host = endpoint?.host?:""
        port = endpoint?.port
        path = endpoint?.path?:""
        scheme = endpoint?.scheme?:"http"

        param {
            requestParams.keys.forEach { it to requestParams[it].toString() }
        }

        header {
            requestHeaders.keys.forEach { it to requestHeaders[it].toString() }
        }

        body {
            json(requestBodyJsonString)
        }
    }
}

fun getMockedResponse(responseIndex: Int, mockResponsesUri: String): JsonObject? {
    val mockResponsesJson = Parser().parse(mockResponsesUri) as JsonArray<*>
    val mockResp = mockResponsesJson[responseIndex]
    if(mockResp is JsonArray<*>) {
        val wrapper = JsonObject()
        wrapper[responseRootArrayKey] = mockResp
        return wrapper
    }
    return mockResponsesJson[responseIndex] as JsonObject
}

data class Endpoint(val scheme: String, val host: String, val port: Int? = null, val path: String, val type: String)

data class AppConfig(var requestsUri: String = "",
                     var mockResponsesUri: String = "",
                     var useMocks: Boolean = false,
                     var useEnv: Boolean = false,
                     var templateString: String = "")

data class MappingFromJson(
    val useResponseKey: String = "",
    val usePreservedValue: String = "",
    val currentResponseValue: String = "",
    val mapTo: String = "",
    val preserveValueAs: String = ""
)

data class MappingToOutgoingRequest(
    val targetKeyToReplaceInRequest: String = "",
    val targetPathInNextRequest: String = "",
    val targetValueInNextRequest: String = ""
)

data class ResponseAssertion<T>(val checkResponseKey: String, val expectedValue: T)

fun <T> MutableList<T>.push(item: T) = this.add(this.count(), item)
fun <T> MutableList<T>.pop(): T? = if(this.count() > 0) this.removeAt(this.count() - 1) else null
fun <T> MutableList<T>.peek(): T? = if(this.count() > 0) this[this.count() - 1] else null
fun <T> MutableList<T>.hasMore() = this.count() > 0