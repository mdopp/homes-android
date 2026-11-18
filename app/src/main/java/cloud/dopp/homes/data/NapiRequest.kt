package cloud.dopp.homes.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * The one place an [Napi.Endpoint] becomes an HTTP request: the endpoint's
 * method, the device token in the header Homes reads it from, and the target
 * the endpoint built. Nothing else in the app builds a request
 * (`NapiContractTest.onlyNapiRequestBuildsARequest`).
 *
 * [target] is the endpoint's path with its query ([Napi.Endpoint.target]); a
 * GET, HEAD or DELETE carries no [body], a POST, PATCH or PUT always one. [headers] are the
 * values of the endpoint's [Napi.Endpoint.headers], by name; no other header
 * is sent but the phone's language ([inSprache]).
 */
fun Napi.Endpoint.request(
    base: String,
    token: String,
    target: String = path,
    body: RequestBody? = null,
    headers: Map<String, String> = emptyMap(),
): Request {
    val bodiless = method == Napi.Method.GET || method == Napi.Method.HEAD || method == Napi.Method.DELETE
    require(bodiless == (body == null)) { "$this: a GET, HEAD or DELETE has no body, a POST, PATCH or PUT has one" }
    require(headers.keys == this.headers.toSet()) { "$this sends the headers ${this.headers}, not ${headers.keys}" }
    val b = Request.Builder()
        .url(base.trimEnd('/') + target)
        .header(Napi.AUTH_HEADER, Napi.bearer(token))
        .method(method.name, body)
    accept?.let { b.header("Accept", it) }
    headers.forEach { (name, value) -> b.header(name, value) }
    return b.build()
}

/** [this] with the phone's languages as `Accept-Language` ([Sprache] puts it on every call). */
fun Request.inSprache(acceptLanguage: String): Request = newBuilder().header("Accept-Language", acceptLanguage).build()

/** A reply header the endpoint declares ([Napi.Endpoint.replyHeaders]), or null when Homes sent none. */
fun Response.replyHeader(endpoint: Napi.Endpoint, name: String): String? {
    require(name in endpoint.replyHeaders) { "$endpoint reads no header $name" }
    return header(name)
}

/** This endpoint's JSON body: its [Napi.Endpoint.body] names with [values], in order, nulls left out. */
fun Napi.Endpoint.json(vararg values: Any?): JSONObject = JSONObject(fields(*values))

/** [this] as a request body. */
fun JSONObject.asBody(): RequestBody = toString().toRequestBody(JSON_TYPE)

private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
