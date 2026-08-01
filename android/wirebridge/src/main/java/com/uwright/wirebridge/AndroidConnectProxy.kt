package com.uwright.wirebridge

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Local HTTP CONNECT proxy used by Linux-native binaries running on Android.
 *
 * Static Linux binaries can read Android's placeholder resolver as ::1:53 and
 * fail DNS resolution. This proxy accepts CONNECT requests on loopback and
 * resolves the destination through Android's Java networking stack instead.
 */
class AndroidConnectProxy(
    private val port: Int = 18924,
    private val logger: (String) -> Unit = {},
) {
    @Volatile
    private var running = false

    @Volatile
    private var serverSocket: ServerSocket? = null

    private var executor: ExecutorService = Executors.newCachedThreadPool()

    @Synchronized
    fun start(): Boolean {
        if (running) return true
        if (executor.isShutdown) executor = Executors.newCachedThreadPool()

        return try {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            }
            serverSocket = server
            running = true
            executor.execute { acceptLoop(server) }
            logger("Android CONNECT proxy listening on 127.0.0.1:$port")
            true
        } catch (t: Throwable) {
            running = false
            serverSocket = null
            logger("Proxy start failed: ${t::class.java.simpleName}: ${t.message}")
            false
        }
    }

    @Synchronized
    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        executor.shutdownNow()
        logger("Android CONNECT proxy stopped")
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running) {
            try {
                val client = server.accept()
                executor.execute { handleClient(client) }
            } catch (e: SocketException) {
                if (running) logger("Proxy accept failed: ${e.message}")
            } catch (t: Throwable) {
                if (running) logger("Proxy accept failed: ${t::class.java.simpleName}: ${t.message}")
            }
        }
    }

    private fun handleClient(client: Socket) {
        var remote: Socket? = null
        try {
            client.soTimeout = 30_000
            val clientInput = BufferedInputStream(client.getInputStream())
            val clientOutput = client.getOutputStream()

            val requestLine = readAsciiLine(clientInput) ?: return
            val parts = requestLine.split(' ', limit = 3)
            if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) {
                writeResponse(clientOutput, "405 Method Not Allowed")
                return
            }

            while (true) {
                val header = readAsciiLine(clientInput) ?: break
                if (header.isEmpty()) break
            }

            val (host, destinationPort) = parseAuthority(parts[1])
            remote = connectResolved(host, destinationPort)
            remote.soTimeout = 0
            client.soTimeout = 0

            clientOutput.write(
                "HTTP/1.1 200 Connection Established\r\n".toByteArray(StandardCharsets.ISO_8859_1)
            )
            clientOutput.write(
                "Proxy-Agent: WIRE-Android-Bridge\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1)
            )
            clientOutput.flush()

            logger("CONNECT $host:$destinationPort")

            val upstream = executor.submit {
                try {
                    pipe(clientInput, remote.getOutputStream())
                } catch (_: Throwable) {
                } finally {
                    try {
                        remote.shutdownOutput()
                    } catch (_: Throwable) {
                    }
                }
            }

            try {
                pipe(remote.getInputStream(), clientOutput)
            } finally {
                upstream.cancel(true)
            }
        } catch (t: Throwable) {
            logger("Proxy connection failed: ${t::class.java.simpleName}: ${t.message}")
            try {
                writeResponse(client.getOutputStream(), "502 Bad Gateway")
            } catch (_: Throwable) {
            }
        } finally {
            try {
                remote?.close()
            } catch (_: Throwable) {
            }
            try {
                client.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun connectResolved(host: String, port: Int): Socket {
        var lastFailure: Throwable? = null
        val addresses = InetAddress.getAllByName(host)
        for (address in addresses) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, port), 15_000)
                return socket
            } catch (t: Throwable) {
                lastFailure = t
                try {
                    socket.close()
                } catch (_: Throwable) {
                }
            }
        }
        throw lastFailure ?: SocketException("No address resolved for $host")
    }

    private fun parseAuthority(authority: String): Pair<String, Int> {
        if (authority.startsWith("[")) {
            val closing = authority.indexOf(']')
            require(closing > 1) { "Invalid IPv6 authority: $authority" }
            val host = authority.substring(1, closing)
            val port = authority.substring(closing + 1).removePrefix(":").toIntOrNull() ?: 443
            return host to port
        }

        val separator = authority.lastIndexOf(':')
        return if (separator > 0) {
            authority.substring(0, separator) to
                (authority.substring(separator + 1).toIntOrNull() ?: 443)
        } else {
            authority to 443
        }
    }

    private fun readAsciiLine(input: InputStream): String? {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 16_384) {
            val next = input.read()
            if (next == -1) {
                return if (bytes.size() == 0) null else bytes.toString(StandardCharsets.ISO_8859_1.name())
            }
            if (next == '\n'.code) break
            if (next != '\r'.code) bytes.write(next)
        }
        return bytes.toString(StandardCharsets.ISO_8859_1.name())
    }

    private fun writeResponse(output: OutputStream, status: String) {
        output.write("HTTP/1.1 $status\r\nConnection: close\r\n\r\n".toByteArray(StandardCharsets.ISO_8859_1))
        output.flush()
    }

    private fun pipe(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        output.flush()
    }
}
