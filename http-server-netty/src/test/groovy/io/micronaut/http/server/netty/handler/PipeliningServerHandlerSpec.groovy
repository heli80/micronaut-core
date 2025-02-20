package io.micronaut.http.server.netty.handler

import io.micronaut.http.netty.stream.StreamedHttpRequest
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.codec.http.DefaultFullHttpRequest
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.DefaultHttpContent
import io.netty.handler.codec.http.DefaultHttpRequest
import io.netty.handler.codec.http.DefaultHttpResponse
import io.netty.handler.codec.http.DefaultLastHttpContent
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpContent
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpHeaderValues
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponse
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import spock.lang.Specification

import java.nio.charset.StandardCharsets

class PipeliningServerHandlerSpec extends Specification {
    def 'pipelined requests have their responses batched'() {
        given:
        def mon = new MonitorHandler()
        def resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT)
        def ch = new EmbeddedChannel(mon, new PipeliningServerHandler(new RequestHandler() {
            @Override
            void accept(ChannelHandlerContext ctx, HttpRequest request, PipeliningServerHandler.OutboundAccess outboundAccess) {
                outboundAccess.writeFull(resp)
            }

            @Override
            void handleUnboundError(Throwable cause) {
                cause.printStackTrace()
            }
        }))

        expect:
        mon.read == 1
        mon.flush == 0

        when:
        ch.writeOneInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
        then:
        mon.read == 1
        mon.flush == 0

        when:
        ch.writeOneInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
        then:
        mon.read == 1
        mon.flush == 0

        when:
        ch.flushInbound()
        then:
        mon.read == 2
        mon.flush == 1
        ch.readOutbound() == resp
        ch.readOutbound() == resp
        ch.readOutbound() == null
        ch.checkException()
    }

    def 'streaming responses flush after every item'() {
        given:
        def mon = new MonitorHandler()
        def resp = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK)
        resp.headers().add(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED)
        def sink = Sinks.many().unicast().<HttpContent>onBackpressureBuffer()
        def ch = new EmbeddedChannel(mon, new PipeliningServerHandler(new RequestHandler() {
            @Override
            void accept(ChannelHandlerContext ctx, HttpRequest request, PipeliningServerHandler.OutboundAccess outboundAccess) {
                outboundAccess.writeStreamed(resp, sink.asFlux())
            }

            @Override
            void handleUnboundError(Throwable cause) {
                cause.printStackTrace()
            }
        }))

        expect:
        mon.read == 1
        mon.flush == 0

        when:
        ch.writeInbound(new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/"))
        then:
        mon.read == 2
        // response is delayed until first content
        mon.flush == 0

        when:
        def c1 = new DefaultHttpContent(Unpooled.wrappedBuffer("foo".getBytes(StandardCharsets.UTF_8)))
        sink.tryEmitNext(c1)
        then:
        mon.read == 2
        mon.flush == 1
        ch.readOutbound() instanceof HttpResponse
        ch.readOutbound() == c1
        ch.readOutbound() == null

        when:
        def c2 = new DefaultHttpContent(Unpooled.wrappedBuffer("foo".getBytes(StandardCharsets.UTF_8)))
        sink.tryEmitNext(c2)
        then:
        mon.read == 2
        mon.flush == 2
        ch.readOutbound() == c2
        ch.readOutbound() == null
        ch.checkException()
    }

    def 'requests that come in a single packet are accumulated'() {
        given:
        def mon = new MonitorHandler()
        def resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT)
        def ch = new EmbeddedChannel(mon, new PipeliningServerHandler(new RequestHandler() {
            @Override
            void accept(ChannelHandlerContext ctx, HttpRequest request, PipeliningServerHandler.OutboundAccess outboundAccess) {
                assert request instanceof FullHttpRequest
                assert request.content().toString(StandardCharsets.UTF_8) == "foobar"
                request.release()
                outboundAccess.writeFull(resp)
            }

            @Override
            void handleUnboundError(Throwable cause) {
                cause.printStackTrace()
            }
        }))

        expect:
        mon.read == 1
        mon.flush == 0

        when:
        def req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/")
        req.headers().add(HttpHeaderNames.CONTENT_LENGTH, 6)
        ch.writeInbound(
                req,
                new DefaultHttpContent(Unpooled.wrappedBuffer("foo".getBytes(StandardCharsets.UTF_8))),
                new DefaultLastHttpContent(Unpooled.wrappedBuffer("bar".getBytes(StandardCharsets.UTF_8)))
        )
        then:
        ch.checkException()
        mon.read == 2
        mon.flush == 1
        ch.readOutbound() == resp
        ch.readOutbound() == null
    }

    def 'continue support'() {
        given:
        def mon = new MonitorHandler()
        def resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT)
        def ch = new EmbeddedChannel(mon, new PipeliningServerHandler(new RequestHandler() {
            @Override
            void accept(ChannelHandlerContext ctx, HttpRequest request, PipeliningServerHandler.OutboundAccess outboundAccess) {
                Flux.from((StreamedHttpRequest) request).collectList().subscribe { outboundAccess.writeFull(resp) }
            }

            @Override
            void handleUnboundError(Throwable cause) {
                cause.printStackTrace()
            }
        }))

        expect:
        mon.read == 1
        mon.flush == 0

        when:
        def req = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/")
        req.headers().add(HttpHeaderNames.EXPECT, HttpHeaderValues.CONTINUE)
        req.headers().add(HttpHeaderNames.CONTENT_LENGTH, 3)
        ch.writeInbound(req)
        then:
        HttpResponse cont = ch.readOutbound()
        cont.status() == HttpResponseStatus.CONTINUE
        ch.readOutbound() == null

        when:
        ch.writeInbound(new DefaultLastHttpContent(Unpooled.wrappedBuffer("foo".getBytes(StandardCharsets.UTF_8))))
        then:
        ch.readOutbound() == resp
        ch.readOutbound() == null
    }

    static class MonitorHandler extends ChannelOutboundHandlerAdapter {
        int flush = 0
        int read = 0

        @Override
        void flush(ChannelHandlerContext ctx) throws Exception {
            super.flush(ctx)
            flush++
        }

        @Override
        void read(ChannelHandlerContext ctx) throws Exception {
            super.read(ctx)
            read++
        }
    }
}
