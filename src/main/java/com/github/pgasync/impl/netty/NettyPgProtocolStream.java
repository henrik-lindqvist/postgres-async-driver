/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.pgasync.impl.netty;

import com.github.pgasync.SqlException;
import com.github.pgasync.impl.PgProtocolStream;
import com.github.pgasync.impl.message.*;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.concurrent.*;
import io.netty.util.concurrent.Future;

import java.net.SocketAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

import static java.util.Collections.singletonList;

/**
 * Netty connection to PostgreSQL backend.
 * 
 * @author Antti Laisi
 */
public class NettyPgProtocolStream implements PgProtocolStream {

    final EventLoopGroup group;
    final SocketAddress address;
    final boolean useSsl;
    final ConcurrentMap<String,Map<String,Consumer<String>>> listeners = new ConcurrentHashMap<>();

    ChannelHandlerContext ctx;
    final Queue<Consumer<Message>> onReceivers;

    public NettyPgProtocolStream(EventLoopGroup group, SocketAddress address, boolean useSsl, boolean pipeline) {
        this.group = group;
        this.address = address;
        this.useSsl = useSsl; // TODO: refactor into SSLConfig with trust parameters
        this.onReceivers = pipeline ? new LinkedBlockingDeque<>() : new ArrayBlockingQueue<>(1);
    }

    @Override
    public void connect(StartupMessage startup, Consumer<List<Message>> replyTo) {
        new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(newProtocolInitializer(newStartupHandler(startup, replyTo)))
                .connect(address)
                .addListener(errorListener(replyTo));
    }

    @Override
    public void send(Message message, Consumer<List<Message>> replyTo) {
        if(!isConnected()) {
            throw new IllegalStateException("Channel is closed");
        }
        addNewReplyHandler(replyTo);
        ctx.writeAndFlush(message).addListener(errorListener(replyTo));
    }

    private void addNewReplyHandler(Consumer<List<Message>> replyTo) {
        if (!onReceivers.offer(newReplyHandler(replyTo))) {
            replyTo.accept(singletonList(new ChannelError("Pipelining not enabled")));
        }
    }

    @Override
    public void send(List<Message> messages, Consumer<List<Message>> replyTo) {
        if(!isConnected()) {
            throw new IllegalStateException("Channel is closed");
        }
        addNewReplyHandler(replyTo);
        GenericFutureListener<Future<Object>> errorListener = errorListener(replyTo);
        messages.forEach(msg -> ctx.write(msg).addListener(errorListener));
        ctx.flush();
    }

    @Override
    public boolean isConnected() {
        return ctx.channel().isOpen();
    }

    @Override
    public String registerNotificationHandler(String channel, Consumer<String> onNotification) {
        Map<String,Consumer<String>> consumers = new ConcurrentHashMap<>();
        Map<String,Consumer<String>> old = listeners.putIfAbsent(channel, consumers);
        consumers = old != null ? old : consumers;

        String token = UUID.randomUUID().toString();
        consumers.put(token, onNotification);
        return token;
    }

    @Override
    public void unRegisterNotificationHandler(String channel, String unlistenToken) {
        Map<String,Consumer<String>> consumers = listeners.get(channel);
        if(consumers == null || consumers.remove(unlistenToken) == null) {
            throw new IllegalStateException("No consumers on channel " + channel + " with token " + unlistenToken);
        }
    }

    @Override
    public void close() {
        ctx.close();
    }

    Consumer<Message> newReplyHandler(Consumer<List<Message>> consumer) {
        List<Message> messages = new ArrayList<>();
        return msg -> {
            messages.add(msg);
            if(msg instanceof ReadyForQuery
                    || msg instanceof ChannelError
                    || (msg instanceof Authentication && !((Authentication) msg).isAuthenticationOk())) {
                onReceivers.remove();
                consumer.accept(messages);
            }
        };
    }

    void publishNotification(NotificationResponse notification) {
        Map<String,Consumer<String>> consumers = listeners.get(notification.getChannel());
        if(consumers != null) {
            consumers.values().forEach(c -> c.accept(notification.getPayload()));
        }
    }

    <T> GenericFutureListener<io.netty.util.concurrent.Future<T>> errorListener(Consumer<List<Message>> replyTo) {
        return future -> {
            if(!future.isSuccess()) {
                replyTo.accept(singletonList(new ChannelError(future.cause())));
            }
        };
    }

    ChannelInboundHandlerAdapter newStartupHandler(StartupMessage startup, Consumer<List<Message>> replyTo) {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext context, Object evt) throws Exception {
                if (evt instanceof SslHandshakeCompletionEvent && ((SslHandshakeCompletionEvent) evt).isSuccess()) {
                    startup(context);
                }
            }
            @Override
            public void channelActive(ChannelHandlerContext context) {
                if(useSsl) {
                    context.writeAndFlush(SSLHandshake.INSTANCE).addListener(errorListener(replyTo));
                } else {
                    startup(context);
                }
            }
            void startup(ChannelHandlerContext context) {
                ctx = context;
                addNewReplyHandler(replyTo);
                context.writeAndFlush(startup).addListener(errorListener(replyTo));
                context.pipeline().remove(this);
            }
        };
    }

    ChannelInitializer<Channel> newProtocolInitializer(ChannelHandler onActive) {
        return new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel channel) throws Exception {
                if(useSsl) {
                    channel.pipeline().addLast(newSslInitiator());
                }
                channel.pipeline().addLast(new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 1, 4, -4, 0, true));
                channel.pipeline().addLast(new ByteBufMessageDecoder());
                channel.pipeline().addLast(new ByteBufMessageEncoder());
                channel.pipeline().addLast(newProtocolHandler());
                channel.pipeline().addLast(onActive);
            }
        };
    }

    ChannelHandler newSslInitiator() {
        return new ByteToMessageDecoder() {
            @Override
            protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
                if(in.readableBytes() < 1) {
                    return;
                }
                if('S' != in.readByte()) {
                    ctx.fireExceptionCaught(new IllegalStateException("SSL required but not supported by backend server"));
                    return;
                }
                ctx.pipeline().remove(this);
                ctx.pipeline().addFirst(
                        SslContext.newClientContext(InsecureTrustManagerFactory.INSTANCE)
                                .newHandler(ctx.alloc()));

            }
        };
    }

    ChannelHandler newProtocolHandler() {
        return new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext context, Object msg) throws Exception {
                if(msg instanceof NotificationResponse) {
                    publishNotification((NotificationResponse) msg);
                    return;
                }
                onReceivers.peek().accept((Message) msg);
            }
            @Override
            public void channelInactive(ChannelHandlerContext context) throws Exception {
                onReceivers.forEach(r -> r.accept(new ChannelError("Channel state changed to inactive")));
            }
            @Override
            public void exceptionCaught(ChannelHandlerContext context, Throwable cause) throws Exception {
                onReceivers.forEach(r -> r.accept(new ChannelError(cause)));
            }
        };
    }

    static class ChannelError extends SqlException implements Message {
        ChannelError(String message) {
            super(message);
        }
        public ChannelError(Throwable cause) {
            super(cause);
        }
    }

}
