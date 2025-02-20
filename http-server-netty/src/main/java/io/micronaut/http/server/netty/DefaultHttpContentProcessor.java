/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.http.server.netty;

import io.micronaut.core.annotation.Internal;
import io.micronaut.http.exceptions.ContentLengthExceededException;
import io.micronaut.http.server.HttpServerConfiguration;
import io.netty.buffer.ByteBufHolder;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.multipart.HttpData;

import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This class will handle subscribing to a stream of {@link io.netty.handler.codec.http.HttpContent}.
 *
 * @author Graeme Rocher
 * @since 1.0
 */
@Internal
public class DefaultHttpContentProcessor implements HttpContentProcessor {

    protected final NettyHttpRequest<?> nettyHttpRequest;
    protected final ChannelHandlerContext ctx;
    protected final HttpServerConfiguration configuration;
    protected final long advertisedLength;
    protected final long requestMaxSize;
    protected final AtomicLong receivedLength = new AtomicLong();

    /**
     * @param nettyHttpRequest The {@link NettyHttpRequest}
     * @param configuration    The {@link HttpServerConfiguration}
     */
    public DefaultHttpContentProcessor(NettyHttpRequest<?> nettyHttpRequest, HttpServerConfiguration configuration) {
        this.nettyHttpRequest = nettyHttpRequest;
        this.configuration = configuration;
        this.requestMaxSize = configuration.getMaxRequestSize();
        this.ctx = nettyHttpRequest.getChannelHandlerContext();
        this.advertisedLength = nettyHttpRequest.getContentLength();
    }

    @Override
    public void add(ByteBufHolder message, Collection<Object> out) {
        long receivedLength = this.receivedLength.addAndGet(resolveLength(message));

        if (advertisedLength > requestMaxSize) {
            fireExceedsLength(advertisedLength, requestMaxSize, message);
        } else if (receivedLength > requestMaxSize) {
            fireExceedsLength(receivedLength, requestMaxSize, message);
        } else {
            out.add(message);
        }
    }

    private long resolveLength(ByteBufHolder message) {
        if (message instanceof HttpData) {
            return ((HttpData) message).length();
        } else {
            return message.content().readableBytes();
        }
    }

    private void fireExceedsLength(long receivedLength, long expected, ByteBufHolder message) {
        message.release();
        throw new ContentLengthExceededException(expected, receivedLength);
    }
}
