package com.tsato.reverse_proxy

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.content.TextContent
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.*
import io.ktor.utils.io.*

fun main(args: Array<String>) : Unit = EngineMain.main(args)

fun Application.module() {
    val frontendDomain = System.getenv("FRONTEND_DOMAIN")
    val backendDomain = System.getenv("BACKEND_DOMAIN")

    install(CORS) {
        allowHost(frontendDomain)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
    }
    install(ContentNegotiation) {
        json()
    }

    val client = HttpClient()

    intercept(ApplicationCallPipeline.Call) {
        println("request uri ==> ${call.request.uri}")
        println("request method ==> ${call.request.httpMethod}")
        println("request header ==> ${call.request.header(HttpHeaders.ContentType)}")

        val response: HttpResponse = client.request("$backendDomain${call.request.uri}") {
            method = call.request.httpMethod
            call.request.header(HttpHeaders.ContentType)?.let { contentType ->
                headers {
                    append(HttpHeaders.ContentType, contentType)
                }
            }
            setBody(call.receiveChannel())
        }

        // Get the relevant headers of the client response.
        val proxiedHeaders = response.headers
        val location = proxiedHeaders[HttpHeaders.Location]
        val contentType = proxiedHeaders[HttpHeaders.ContentType]
        val contentLength = proxiedHeaders[HttpHeaders.ContentLength]

        // Extension method to process all the served HTML documents
        fun String.stripDomain() = this.replace(Regex("(https?:)?//\\w+\\.kakeibo\\.com"), "")

        // Propagates location header, removing the domain from it
        if (location != null) {
            call.response.header(HttpHeaders.Location, location.stripDomain())
        }

        // Depending on the ContentType, we process the request one way or another.
        when {
            contentType?.startsWith("text/html") == true -> {
                // In the case of HTML we download the whole content and process it as a string replacing links.
                val text = response.bodyAsText()
                val filteredText = text.stripDomain()
                call.respond(
                    TextContent(
                        filteredText,
                        ContentType.Text.Html.withCharset(Charsets.UTF_8),
                        response.status
                    )
                )
            }

            else -> {
                // In the case of other content, we simply pipe it. We return a [OutgoingContent.WriteChannelContent]
                // propagating the contentLength, the contentType and other headers, and simply we copy
                // the ByteReadChannel from the HTTP client response, to the HTTP server ByteWriteChannel response.
                call.respond(object : OutgoingContent.WriteChannelContent() {
                    override val contentLength: Long? = contentLength?.toLong()
                    override val contentType: ContentType? = contentType?.let { ContentType.parse(it) }
                    override val headers: Headers = Headers.build {
                        appendAll(proxiedHeaders.filter { key, _ ->
                            !key.equals(
                                HttpHeaders.ContentType,
                                ignoreCase = true
                            ) && !key.equals(HttpHeaders.ContentLength, ignoreCase = true)
                        })
                    }
                    override val status: HttpStatusCode = response.status
                    override suspend fun writeTo(channel: ByteWriteChannel) {
                        response.bodyAsChannel().copyAndClose(channel)
                    }
                })
            }
        }
    }
}