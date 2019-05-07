/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pulsar.proxy.server;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.net.URI;
import java.net.URISyntaxException;

import javax.net.ssl.SSLSession;

import org.apache.http.conn.ssl.DefaultHostnameVerifier;
import org.apache.pulsar.PulsarVersion;
import org.apache.pulsar.client.api.Authentication;
import org.apache.pulsar.client.api.AuthenticationDataProvider;
import org.apache.pulsar.common.api.AuthData;
import org.apache.pulsar.common.api.Commands;
import org.apache.pulsar.common.api.PulsarDecoder;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandAuthChallenge;
import org.apache.pulsar.common.api.proto.PulsarApi.CommandConnected;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;

public class DirectProxyHandler {

    private Channel inboundChannel;
    Channel outboundChannel;
    private String originalPrincipal;
    private AuthData clientAuthData;
    private String clientAuthMethod;
    private int protocolVersion;
    public static final String TLS_HANDLER = "tls";

    private final Authentication authentication;
    private final SslContext sslCtx;
    private AuthenticationDataProvider authenticationDataProvider;

    public DirectProxyHandler(ProxyService service, ProxyConnection proxyConnection, String targetBrokerUrl,
            int protocolVersion, SslContext sslCtx) {
        this.authentication = proxyConnection.getClientAuthentication();
        this.inboundChannel = proxyConnection.ctx().channel();
        this.originalPrincipal = proxyConnection.clientAuthRole;
        this.clientAuthData = proxyConnection.clientAuthData;
        this.clientAuthMethod = proxyConnection.clientAuthMethod;
        this.protocolVersion = protocolVersion;
        this.sslCtx = sslCtx;
        ProxyConfiguration config = service.getConfiguration();

        // Start the connection attempt.
        Bootstrap b = new Bootstrap();
        // Tie the backend connection on the same thread to avoid context
        // switches when passing data between the 2
        // connections
        b.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        b.group(inboundChannel.eventLoop()).channel(inboundChannel.getClass()).option(ChannelOption.AUTO_READ, false);
        b.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                if (sslCtx != null) {
                    ch.pipeline().addLast(TLS_HANDLER, sslCtx.newHandler(ch.alloc()));
                }
                ch.pipeline().addLast("frameDecoder",
                        new LengthFieldBasedFrameDecoder(PulsarDecoder.MaxFrameSize, 0, 4, 0, 4));
                ch.pipeline().addLast("proxyOutboundHandler", new ProxyBackendHandler(config, protocolVersion));
            }
        });

        URI targetBroker;
        try {
            // targetBrokerUrl is coming in the "hostname:6650" form, so we need
            // to extract host and port
            targetBroker = new URI("pulsar://" + targetBrokerUrl);
        } catch (URISyntaxException e) {
            log.warn("[{}] Failed to parse broker url '{}'", inboundChannel, targetBrokerUrl, e);
            inboundChannel.close();
            return;
        }

        ChannelFuture f = b.connect(targetBroker.getHost(), targetBroker.getPort());
        outboundChannel = f.channel();
        f.addListener(future -> {
            if (!future.isSuccess()) {
                // Close the connection if the connection attempt has failed.
                inboundChannel.close();
                return;
            }
            final ProxyBackendHandler cnx = (ProxyBackendHandler) outboundChannel.pipeline()
                    .get("proxyOutboundHandler");
            cnx.setRemoteHostName(targetBroker.getHost());
        });
    }

    enum BackendState {
        Init, HandshakeCompleted
    }

    public class ProxyBackendHandler extends PulsarDecoder implements FutureListener<Void> {

        private BackendState state = BackendState.Init;
        private String remoteHostName;
        protected ChannelHandlerContext ctx;
        private ProxyConfiguration config;
        private int protocolVersion;

        public ProxyBackendHandler(ProxyConfiguration config, int protocolVersion) {
            this.config = config;
            this.protocolVersion = protocolVersion;
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            this.ctx = ctx;
            // Send the Connect command to broker
            authenticationDataProvider = authentication.getAuthData(remoteHostName);
            AuthData authData = authenticationDataProvider.authenticate(AuthData.of(AuthData.INIT_AUTH_DATA));
            ByteBuf command = null;
            command = Commands.newConnect(authentication.getAuthMethodName(), authData, protocolVersion, "Pulsar proxy",
                    null /* target broker */, originalPrincipal, clientAuthData, clientAuthMethod);
            outboundChannel.writeAndFlush(command);
            outboundChannel.read();
        }

        @Override
        public void channelRead(final ChannelHandlerContext ctx, Object msg) throws Exception {
            switch (state) {
            case Init:
                if (log.isDebugEnabled()) {
                    log.debug("[{}] [{}] Received msg on broker connection: {}", inboundChannel, outboundChannel,
                            msg.getClass());
                }

                // Do the regular decoding for the Connected message
                super.channelRead(ctx, msg);
                break;

            case HandshakeCompleted:
                ProxyService.opsCounter.inc();
                if (msg instanceof ByteBuf) {
                    ProxyService.bytesCounter.inc(((ByteBuf) msg).readableBytes());
                }
                inboundChannel.writeAndFlush(msg).addListener(this);
                break;

            default:
                break;
            }

        }

        @Override
        protected void handleAuthChallenge(CommandAuthChallenge authChallenge) {
            checkArgument(authChallenge.hasChallenge());
            checkArgument(authChallenge.getChallenge().hasAuthData() && authChallenge.getChallenge().hasAuthData());

            // mutual authn. If auth not complete, continue auth; if auth complete, complete connectionFuture.
            try {
                AuthData authData = authenticationDataProvider
                    .authenticate(AuthData.of(authChallenge.getChallenge().getAuthData().toByteArray()));

                checkState(!authData.isComplete());

                ByteBuf request = Commands.newAuthResponse(authentication.getAuthMethodName(),
                    authData,
                    this.protocolVersion,
                    PulsarVersion.getVersion());

                if (log.isDebugEnabled()) {
                    log.debug("{} Mutual auth {}", ctx.channel(), authentication.getAuthMethodName());
                }

                outboundChannel.writeAndFlush(request);
                outboundChannel.read();
            } catch (Exception e) {
                log.error("Error mutual verify: {}", e);
                return;
            }
        }

        @Override
        public void operationComplete(Future<Void> future) throws Exception {
            // This is invoked when the write operation on the paired connection
            // is completed
            if (future.isSuccess()) {
                outboundChannel.read();
            } else {
                log.warn("[{}] [{}] Failed to write on proxy connection. Closing both connections.", inboundChannel,
                        outboundChannel, future.cause());
                inboundChannel.close();
            }
        }

        @Override
        protected void messageReceived() {
            // no-op
        }

        @Override
        protected void handleConnected(CommandConnected connected) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] [{}] Received Connected from broker", inboundChannel, outboundChannel);
            }

            if (config.isTlsHostnameVerificationEnabled() && remoteHostName != null
                    && !verifyTlsHostName(remoteHostName, ctx)) {
                // close the connection if host-verification failed with the
                // broker
                log.warn("[{}] Failed to verify hostname of {}", ctx.channel(), remoteHostName);
                ctx.close();
                return;
            }

            state = BackendState.HandshakeCompleted;

            inboundChannel.writeAndFlush(Commands.newConnected(connected.getProtocolVersion())).addListener(future -> {
                if (log.isDebugEnabled()) {
                    log.debug("[{}] [{}] Removing decoder from pipeline", inboundChannel, outboundChannel);
                }
                inboundChannel.pipeline().remove("frameDecoder");
                outboundChannel.pipeline().remove("frameDecoder");

                // Start reading from both connections
                inboundChannel.read();
                outboundChannel.read();
            });
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            inboundChannel.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.warn("[{}] [{}] Caught exception: {}", inboundChannel, outboundChannel, cause.getMessage(), cause);
            ctx.close();
        }

        public void setRemoteHostName(String remoteHostName) {
            this.remoteHostName = remoteHostName;
        }

        private boolean verifyTlsHostName(String hostname, ChannelHandlerContext ctx) {
            ChannelHandler sslHandler = ctx.channel().pipeline().get("tls");

            SSLSession sslSession = null;
            if (sslHandler != null) {
                sslSession = ((SslHandler) sslHandler).engine().getSession();
                return (new DefaultHostnameVerifier()).verify(hostname, sslSession);
            }
            return false;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(DirectProxyHandler.class);
}
